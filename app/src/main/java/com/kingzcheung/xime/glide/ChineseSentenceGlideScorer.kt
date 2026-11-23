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
    const val MAX_GEOMETRY_SPREAD = 2.8f
    const val LM_WEIGHT = 0.045f
    const val COHESION_BONUS = 0.42f
    const val INCOMPLETE_PENALTY = 3.0f

    data class SentencePreview(
        val topCandidate: String,
        val preeditText: String = "",
    )

    data class RerankedResult(
        val winnerCode: String,
        val alternativeCodes: List<String>,
        val winnerText: String?,
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

        data class Scored(val candidate: GlideCandidate, val text: String?, val totalCost: Float)

        val scored = pool.map { cand ->
            val prev = runCatching { preview(cand.word) }.getOrNull()
            val text = prev?.topCandidate?.takeIf { it.isNotEmpty() }
            var total = cand.cost + personalDelta(cand.word, allCodes)

            if (text == null) {
                total += INCOMPLETE_PENALTY
            } else {
                val hasUnresolvedAscii = text.any { it in 'a'..'z' || it in 'A'..'Z' } && cand.word.all { it in 'a'..'z' }
                if (hasUnresolvedAscii) total += INCOMPLETE_PENALTY * 0.7f

                if (lm != null && text.isNotEmpty()) {
                    val words = segmentWords(text, lm)
                    val lmAdvantage = scoreSentenceLift(words, h2Init, h1Init, lm)
                    total -= LM_WEIGHT * lmAdvantage
                    val cohesion = if (text.length >= 2) {
                        ((text.length - words.size).toFloat() / (text.length - 1)).coerceIn(0f, 1f)
                    } else 0f
                    total -= COHESION_BONUS * cohesion
                }
            }
            Scored(cand, text, total)
        }.sortedWith(compareBy<Scored> { it.totalCost }.thenBy { it.candidate.cost })

        val winner = scored.first()
        val seenTexts = HashSet<String>()
        winner.text?.let(seenTexts::add)
        val alternatives = ArrayList<String>()
        for (item in scored.drop(1)) {
            val t = item.text
            if (t != null && !seenTexts.add(t)) continue
            if (t == null && !item.candidate.listed) continue
            alternatives += item.candidate.word
        }
        return RerankedResult(winner.candidate.word, alternatives, winner.text)
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
