package com.kingzcheung.xime.glide

import com.kingzcheung.xime.speech.WordLanguageModel
import kotlin.math.min

/** 26 键中文滑行一次抬手后的完整上下文快照，供后端联合重排与自递进学习使用。 */
data class ChineseGlideDispatch(
    val candidates: List<GlideCandidate>,
    val keyboard: GlideKeyboard,
    val xs: FloatArray,
    val ys: FloatArray,
    val ts: LongArray,
    val bucket: String,
    val schemaKey: String,
    val leftContext: String?,
    val previewToken: Long?,
    val personalLearning: Boolean = true,
)

/**
 * 中文连续整句滑行联合重排器：
 * 把几何候选编码批量送入隔离预演引擎得到真实汉字句子，再结合 [WordLanguageModel]（trigram/bigram）
 * 与词组凝聚度、个人学习微调联合排序，选出语义最通顺且几何吻合的首选编码，并输出不重复中文句子的备选编码。
 */
internal object ChineseSentenceGlideScorer {
    const val MAX_PREVIEW_CANDIDATES = 8
    const val MIN_PREVIEW_CANDIDATES = 3
    const val MAX_RIME_CANDIDATES_PER_CODE = 3
    const val MAX_GEOMETRY_SPREAD = 2.8f
    const val LM_WEIGHT = 0.045f
    const val COHESION_BONUS = 0.42f
    const val INCOMPLETE_PENALTY = 3.0f
    const val RIME_RANK_PENALTY = 0.18f
    const val EARLY_STOP_MARGIN = 0.55f
    const val PREVIEW_BUDGET_NS = 24_000_000L

    data class SentencePreview(
        val topCandidate: String,
        val preeditText: String = "",
        val candidates: List<String> = if (topCandidate.isNotEmpty()) listOf(topCandidate) else emptyList(),
        val inputText: String = "",
    )

    data class RerankedResult(
        val winnerCode: String,
        val alternativeCodes: List<String>,
        val winnerText: String?,
        val alternativePreviews: Map<String, SentencePreview> = emptyMap(),
    )

    fun rerank(
        candidates: List<GlideCandidate>,
        leftContext: String?,
        lm: WordLanguageModel?,
        personalDelta: (String, Collection<String>) -> Float = { _, _ -> 0f },
        preview: (String) -> SentencePreview?,
    ): RerankedResult {
        if (candidates.isEmpty()) return RerankedResult("", emptyList(), null)
        if (candidates.size == 1) {
            val single = candidates.first().word
            val prev = runCatching { preview(single) }.getOrNull()
            return RerankedResult(single, emptyList(), prev?.topCandidate?.takeIf { it.isNotEmpty() })
        }

        val allCodes = candidates.map { it.word }
        val bestRawCost = candidates.minOf { it.cost }
        val pool = candidates.filter { it.cost <= bestRawCost + MAX_GEOMETRY_SPREAD }
            .ifEmpty { candidates.take(1) }
            .take(MAX_PREVIEW_CANDIDATES)

        val leftWords = if (lm != null && !leftContext.isNullOrEmpty()) segmentWords(leftContext.trimEnd(), lm).takeLast(2) else emptyList()
        val h2Init = leftWords.getOrNull(leftWords.size - 2)?.let(lm!!::id) ?: -1
        val h1Init = leftWords.lastOrNull()?.let(lm!!::id) ?: -1

        data class Scored(
            val candidate: GlideCandidate,
            val text: String?,
            val preview: SentencePreview?,
            val totalCost: Float,
            val complete: Boolean,
        )

        val startedNs = System.nanoTime()
        val scoredList = ArrayList<Scored>(pool.size)
        val completeTexts = HashSet<String>()
        var bestTotal = Float.POSITIVE_INFINITY
        var secondTotal = Float.POSITIVE_INFINITY
        var bestCompleteGeom = Float.POSITIVE_INFINITY

        for ((idx, cand) in pool.withIndex()) {
            if (idx >= MIN_PREVIEW_CANDIDATES) {
                val elapsedNs = System.nanoTime() - startedNs
                val clearlyAhead = completeTexts.size >= 2 &&
                    secondTotal.isFinite() &&
                    (secondTotal - bestTotal) >= EARLY_STOP_MARGIN &&
                    cand.cost > bestCompleteGeom + 1.2f
                if (elapsedNs >= PREVIEW_BUDGET_NS || clearlyAhead) break
            }

            val prev = runCatching { preview(cand.word) }.getOrNull()
            val candidateTexts = prev?.candidates?.filter { it.isNotEmpty() }?.take(MAX_RIME_CANDIDATES_PER_CODE)
                .orEmpty()
                .ifEmpty { listOfNotNull(prev?.topCandidate?.takeIf { it.isNotEmpty() }) }
            val baseTotal = cand.cost + personalDelta(cand.word, allCodes)

            val item = if (candidateTexts.isEmpty()) {
                Scored(cand, null, prev, baseTotal + INCOMPLETE_PENALTY, complete = false)
            } else {
                val pureAsciiCode = cand.word.all { it in 'a'..'z' }
                val pendingTokens = countPendingAsciiTokens(prev?.preeditText.orEmpty())
                var bestScore = Float.POSITIVE_INFINITY
                var bestIsComplete = false
                for ((rankIdx, candText) in candidateTexts.withIndex()) {
                    var score = baseTotal + rankIdx * RIME_RANK_PENALTY
                    val hasUnresolvedAscii = pureAsciiCode && candText.any { it in 'a'..'z' || it in 'A'..'Z' }
                    if (hasUnresolvedAscii) score += INCOMPLETE_PENALTY * 0.7f

                    val effectiveUnits = countEffectiveTextUnits(candText)
                    val incompleteRatio = when {
                        pendingTokens >= 2 && effectiveUnits < pendingTokens ->
                            (pendingTokens - effectiveUnits).toFloat() / pendingTokens
                        pendingTokens == 0 && pureAsciiCode && cand.word.length >= 12 -> {
                            val minExpected = (cand.word.length + 5) / 6
                            if (effectiveUnits < minExpected) (minExpected - effectiveUnits).toFloat() / minExpected else 0f
                        }
                        else -> 0f
                    }
                    val complete = !hasUnresolvedAscii && incompleteRatio <= 0f
                    if (incompleteRatio > 0f) {
                        score += INCOMPLETE_PENALTY * incompleteRatio
                    }

                    if (lm != null && candText.isNotEmpty()) {
                        val words = segmentWords(candText, lm)
                        val lmAdvantage = scoreSentenceLift(words, h2Init, h1Init, lm)
                        score -= LM_WEIGHT * lmAdvantage
                        // 只有完整覆盖全部音节的候选才给凝聚度加分，防止 2 字残缺前缀反超长句。
                        if (complete && candText.length >= 2) {
                            val cohesion = ((candText.length - words.size).toFloat() / (candText.length - 1)).coerceIn(0f, 1f)
                            score -= COHESION_BONUS * cohesion
                        }
                    }
                    if (score < bestScore) {
                        bestScore = score
                        bestIsComplete = complete
                    }
                }
                val topText = prev?.topCandidate?.takeIf { it.isNotEmpty() } ?: candidateTexts.first()
                Scored(cand, topText, prev, bestScore, bestIsComplete)
            }

            scoredList += item
            if (item.complete && item.text != null) {
                completeTexts += item.text
                if (item.candidate.cost < bestCompleteGeom) bestCompleteGeom = item.candidate.cost
            }
            if (item.totalCost < bestTotal) {
                secondTotal = bestTotal
                bestTotal = item.totalCost
            } else if (item.totalCost < secondTotal) {
                secondTotal = item.totalCost
            }
        }

        val scored = scoredList.sortedWith(compareBy<Scored> { it.totalCost }.thenBy { it.candidate.cost })
        val winner = scored.first()
        val seenTexts = HashSet<String>()
        winner.text?.let(seenTexts::add)
        val alternatives = ArrayList<String>()
        val altPreviews = LinkedHashMap<String, SentencePreview>()
        for (item in scored.drop(1)) {
            val t = item.text
            if (t != null && !seenTexts.add(t)) continue
            if (t == null && !item.candidate.listed) continue
            alternatives += item.candidate.word
            item.preview?.let { altPreviews[item.candidate.word] = it }
        }
        return RerankedResult(winner.candidate.word, alternatives, winner.text, altPreviews)
    }

    private fun countPendingAsciiTokens(preeditText: String): Int {
        if (preeditText.isEmpty()) return 0
        var tokens = 0
        var inAscii = false
        for (ch in preeditText) {
            val isLetter = ch in 'a'..'z' || ch in 'A'..'Z'
            if (isLetter) {
                if (!inAscii) {
                    tokens++
                    inAscii = true
                }
            } else {
                inAscii = false
            }
        }
        return tokens
    }

    private fun countEffectiveTextUnits(text: String): Int {
        var units = 0
        var inAscii = false
        for (ch in text) {
            if (ch.isWhitespace()) {
                inAscii = false
                continue
            }
            if (ch.code <= 0x7F) {
                if (!inAscii) {
                    units++
                    inAscii = true
                }
            } else {
                inAscii = false
                units++
            }
        }
        return units
    }

    /** 基于词表前缀匹配做正向+逆向最长优选分词，供 N-gram 句级打分使用。 */
    internal fun segmentWords(text: String, lm: WordLanguageModel): List<String> {
        val clean = text.trim()
        if (clean.isEmpty()) return emptyList()
        val out = ArrayList<String>(clean.length)
        var i = 0
        while (i < clean.length) {
            var matched: String? = null
            for (len in min(4, clean.length - i) downTo 2) {
                val sub = clean.substring(i, i + len)
                if (lm.id(sub) >= 0) {
                    matched = sub
                    break
                }
            }
            val w = matched ?: clean.substring(i, i + 1)
            out += w
            i += w.length
        }
        return out
    }

    private fun scoreSentenceLift(words: List<String>, h2Init: Int, h1Init: Int, lm: WordLanguageModel): Float {
        if (words.isEmpty()) return 0f
        var h2 = h2Init
        var h1 = h1Init
        var sumLift = 0.0
        for (word in words) {
            val id = lm.id(word)
            if (id >= 0) {
                val ctxLogP = lm.trigram(h2, h1, id)
                val uniLogP = lm.unigram(id)
                val gain = (ctxLogP - uniLogP).coerceIn(-1.5, 6.5)
                // 自身高频词给温和先验，上下文顺滑给明显加分。
                sumLift += gain + ((uniLogP + 12.0) * 0.12).coerceIn(0.0, 1.5)
            } else {
                sumLift -= 1.0
            }
            h2 = h1
            h1 = id
        }
        return (sumLift / kotlin.math.sqrt(words.size.toDouble())).toFloat()
    }
}
