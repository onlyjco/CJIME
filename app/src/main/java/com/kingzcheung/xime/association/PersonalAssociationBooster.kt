package com.kingzcheung.xime.association

import java.io.File
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * 全输入模式通用的本机联想自递进重排器（严格保存在 [filesDir]，不上传网络）：
 * 1. 按整词/词尾（1～4 字）上下文记录正反馈，包括点选联想、整句连续提交与「局部改错」改对的新词；
 * 2. 对明确负反馈施压降权：忽略首选直接重打、选了联想又立即退格删除、「局部改错」被替换掉的原错词；
 * 3. 在 [NgramFusionEngine.fuseCandidates] 出口联合重排，并可直接把高置信个人接续顶入候选。
 */
object PersonalAssociationBooster {
    private const val BOOSTS_FILE = "personal_assoc_boosts.tsv"
    private const val PENALTIES_FILE = "personal_assoc_penalties.tsv"
    private const val MAX_BOOSTS = 4000
    private const val MAX_PENALTIES = 1500
    private const val UNDO_WINDOW_MS = 10_000L

    data class Entry(
        val ctx: String,
        val word: String,
        var score: Float,
        var count: Int,
        var lastUsedMs: Long,
    )

    data class Stats(
        val boostCount: Int,
        val penaltyCount: Int,
        val totalEvents: Int,
    )

    private data class RecentPick(
        val sessionId: Long,
        val context: String,
        val word: String,
        val timestampMs: Long,
    )

    @Volatile private var loadedDir: String? = null
    private val boosts = LinkedHashMap<String, Entry>()
    private val penalties = LinkedHashMap<String, Entry>()
    private val boostsByCtx = HashMap<String, ArrayList<Entry>>()
    private val penaltiesByCtx = HashMap<String, ArrayList<Entry>>()
    @Volatile private var recentPick: RecentPick? = null

    @Synchronized
    fun ensureLoaded(dir: File) {
        val path = dir.absolutePath
        if (loadedDir == path) return
        loadedDir = path
        boosts.clear()
        penalties.clear()
        boostsByCtx.clear()
        penaltiesByCtx.clear()
        loadEntries(File(dir, BOOSTS_FILE), boosts, boostsByCtx, filterSingleCharLongWord = true)
        loadEntries(File(dir, PENALTIES_FILE), penalties, penaltiesByCtx, filterSingleCharLongWord = false)
    }

    @Synchronized
    fun stats(dir: File): Stats {
        ensureLoaded(dir)
        val events = boosts.values.sumOf { it.count } + penalties.values.sumOf { it.count }
        return Stats(boosts.size, penalties.size, events)
    }

    @Synchronized
    fun clear(dir: File) {
        loadedDir = dir.absolutePath
        boosts.clear()
        penalties.clear()
        boostsByCtx.clear()
        penaltiesByCtx.clear()
        recentPick = null
        File(dir, BOOSTS_FILE).delete()
        File(dir, PENALTIES_FILE).delete()
    }

    @Synchronized
    fun recordContinuation(dir: File, contextText: String, nextWord: String, weight: Float = 1.0f) {
        ensureLoaded(dir)
        val cleanWord = cleanChineseWord(nextWord)
        if (cleanWord.isEmpty() || weight <= 0f) return
        val contexts = trailingContexts(contextText)
        if (contexts.isEmpty()) return
        val now = System.currentTimeMillis()
        var boostChanged = false
        var penaltyChanged = false
        for (ctx in contexts) {
            boostChanged = addBoostLocked(ctx, cleanWord, weight, now) || boostChanged
            penaltyChanged = decayPenaltyLocked(ctx, cleanWord, 0.5f) || penaltyChanged
        }
        if (boostChanged) saveEntries(File(dir, BOOSTS_FILE), boosts)
        if (penaltyChanged) saveEntries(File(dir, PENALTIES_FILE), penalties)
    }

    @Synchronized
    fun recordSelection(
        dir: File,
        sessionId: Long,
        contextText: String,
        pickedWord: String,
        shownWords: List<String>,
    ) {
        ensureLoaded(dir)
        val cleanWord = cleanChineseWord(pickedWord)
        if (cleanWord.isEmpty()) return
        val contexts = trailingContexts(contextText)
        if (contexts.isEmpty()) return
        val now = System.currentTimeMillis()
        val pickedRank = shownWords.indexOf(pickedWord)
        val positiveWeight = if (pickedRank > 0) 2.2f else 1.4f
        var boostChanged = false
        var penaltyChanged = false
        for (ctx in contexts) {
            boostChanged = addBoostLocked(ctx, cleanWord, positiveWeight, now) || boostChanged
            penaltyChanged = decayPenaltyLocked(ctx, cleanWord, 0.4f) || penaltyChanged
            if (pickedRank > 0) {
                val skippedTop = cleanChineseWord(shownWords.firstOrNull().orEmpty())
                if (skippedTop.isNotEmpty() && skippedTop != cleanWord) {
                    penaltyChanged = addPenaltyLocked(ctx, skippedTop, 0.45f, now) || penaltyChanged
                }
            }
        }
        recentPick = RecentPick(sessionId, contextText, cleanWord, now)
        if (boostChanged) saveEntries(File(dir, BOOSTS_FILE), boosts)
        if (penaltyChanged) saveEntries(File(dir, PENALTIES_FILE), penalties)
    }

    @Synchronized
    fun onShownAndCommittedOther(
        dir: File,
        shownContext: String,
        shownWords: List<String>,
        committedWord: String,
    ) {
        ensureLoaded(dir)
        val actual = cleanChineseWord(committedWord)
        if (actual.isEmpty()) return
        val contexts = trailingContexts(shownContext)
        if (contexts.isEmpty()) return
        val now = System.currentTimeMillis()
        val topShown = shownWords.asSequence()
            .map(::cleanChineseWord)
            .filter { it.isNotEmpty() && it != actual }
            .distinct()
            .take(2)
            .toList()
        var boostChanged = false
        var penaltyChanged = false
        for (ctx in contexts) {
            boostChanged = addBoostLocked(ctx, actual, 1.3f, now) || boostChanged
            for (ignored in topShown) {
                if (ignored !in actual) {
                    penaltyChanged = addPenaltyLocked(ctx, ignored, 0.28f, now) || penaltyChanged
                }
            }
        }
        if (boostChanged) saveEntries(File(dir, BOOSTS_FILE), boosts)
        if (penaltyChanged) saveEntries(File(dir, PENALTIES_FILE), penalties)
    }

    @Synchronized
    fun noteAssociationUndone(dir: File, sessionId: Long) {
        ensureLoaded(dir)
        val pick = recentPick ?: return
        if (pick.sessionId != sessionId) return
        val now = System.currentTimeMillis()
        if (now - pick.timestampMs > UNDO_WINDOW_MS) {
            recentPick = null
            return
        }
        recentPick = null
        val contexts = trailingContexts(pick.context)
        if (contexts.isEmpty()) return
        var penaltyChanged = false
        for (ctx in contexts) {
            penaltyChanged = addPenaltyLocked(ctx, pick.word, 1.8f, now) || penaltyChanged
        }
        if (penaltyChanged) saveEntries(File(dir, PENALTIES_FILE), penalties)
    }

    /**
     * 直接复用「局部改错」信号：把原错词在该上文下强力降权，把改对的新词强力提权。
     * 不顺带提权紧接其后的未修改后缀，避免多步句内改错时把尚未改完的错别字也写进正强化。
     */
    @Synchronized
    fun onSentenceEditCorrection(
        dir: File,
        leftContext: String,
        sentenceBefore: String,
        start: Int,
        end: Int,
        badWord: String,
        goodWord: String,
        @Suppress("UNUSED_PARAMETER") sentenceAfter: String,
    ) {
        ensureLoaded(dir)
        val prefix = leftContext + sentenceBefore.take(start.coerceIn(0, sentenceBefore.length))
        val cleanBad = cleanChineseWord(badWord)
        val cleanGood = cleanChineseWord(goodWord)
        if (cleanGood.isEmpty() && cleanBad.isEmpty()) return

        val now = System.currentTimeMillis()
        var boostChanged = false
        var penaltyChanged = false
        val editContexts = trailingContexts(prefix)
        for (ctx in editContexts) {
            if (cleanBad.isNotEmpty() && cleanBad != cleanGood) {
                penaltyChanged = addPenaltyLocked(ctx, cleanBad, 2.0f, now) || penaltyChanged
            }
            if (cleanGood.isNotEmpty()) {
                boostChanged = addBoostLocked(ctx, cleanGood, 2.8f, now) || boostChanged
                penaltyChanged = decayPenaltyLocked(ctx, cleanGood, 0.25f) || penaltyChanged
            }
        }
        if (boostChanged) saveEntries(File(dir, BOOSTS_FILE), boosts)
        if (penaltyChanged) saveEntries(File(dir, PENALTIES_FILE), penalties)
    }

    /**
     * 将本机个人联想权重叠加到 [NgramFusionEngine.fuseCandidates] 的概率分中。
     * - 改四层累加为「最长匹配主导 + 浅层微弱确认」并封顶，防止 1+2+3+4 字重复堆叠压死公共语料；
     * - 引入时间衰减（24 小时内轻微保鲜，45 天半衰期平滑回落），贴合当下聊天语境；
     * - [afterWholeWord] 为真且上文 ≥2 字时，1 字上下文降权且不凭空注入新词，防止「今天→气」类错拼。
     */
    @Synchronized
    fun applyToCandidates(
        dir: File,
        contextText: String,
        baseCandidates: MutableMap<String, Float>,
        afterWholeWord: Boolean = false,
    ) {
        ensureLoaded(dir)
        if (boosts.isEmpty() && penalties.isEmpty()) return
        val contexts = trailingContexts(contextText)
        if (contexts.isEmpty()) return
        val hasMultiCharTail = contexts.first().length >= 2
        val now = System.currentTimeMillis()

        val bestBoost = HashMap<String, Float>()
        val secondBoost = HashMap<String, Float>()
        val allowInject = HashSet<String>()
        val bestPenalty = HashMap<String, Float>()

        for (ctx in contexts) {
            val singleAfterWhole = afterWholeWord && hasMultiCharTail && ctx.length == 1
            val weight = if (singleAfterWhole) 0.04f else contextWeight(ctx.length)
            boostsByCtx[ctx]?.forEach { entry ->
                if (!allowsAssociationWord(ctx.length, entry.word.length)) return@forEach
                val factor = recencyFactor(entry.lastUsedMs, now)
                val bonus = weight * ln(1.0 + entry.score).toFloat() * factor
                val prevBest = bestBoost[entry.word] ?: 0f
                if (bonus > prevBest) {
                    if (prevBest > 0f) secondBoost[entry.word] = max(secondBoost[entry.word] ?: 0f, prevBest)
                    bestBoost[entry.word] = bonus
                } else if (bonus > (secondBoost[entry.word] ?: 0f)) {
                    secondBoost[entry.word] = bonus
                }
                // 新词注入门槛按衰减后的有效证据判断，避免久远低频词持续挤占候选。
                if (!singleAfterWhole && entry.score * factor >= 1.25f) {
                    allowInject.add(entry.word)
                }
            }
            // 先汇总全部上下文的负反馈（不提前过滤 baseCandidates），确保新注入的个人词同样受负反馈约束。
            penaltiesByCtx[ctx]?.forEach { entry ->
                val penalty = weight * 1.15f * ln(1.0 + entry.score).toFloat() * recencyFactor(entry.lastUsedMs, now)
                val prev = bestPenalty[entry.word] ?: 0f
                if (penalty > prev) bestPenalty[entry.word] = penalty
            }
        }

        for ((word, topBonus) in bestBoost) {
            val combined = min(MAX_PERSONAL_BOOST, topBonus + (secondBoost[word] ?: 0f) * 0.15f)
            val pen = min(MAX_PERSONAL_PENALTY, bestPenalty[word] ?: 0f)
            val existing = baseCandidates[word]
            if (existing != null) {
                baseCandidates[word] = (existing + combined - pen).coerceAtLeast(0.0001f)
            } else if (word in allowInject) {
                val injectedNet = combined * 0.90f - pen
                if (injectedNet >= MIN_INJECT_NET_SCORE) {
                    baseCandidates[word] = injectedNet
                }
            }
        }
        for ((word, pen) in bestPenalty) {
            if (word in bestBoost) continue
            val existing = baseCandidates[word] ?: continue
            baseCandidates[word] = (existing - min(MAX_PERSONAL_PENALTY, pen)).coerceAtLeast(0.0001f)
        }
    }

    private fun recencyFactor(lastUsedMs: Long, nowMs: Long): Float {
        if (lastUsedMs <= 0L) return 0.85f
        val ageDays = (nowMs - lastUsedMs).coerceAtLeast(0L) / 86_400_000.0
        // 连续平滑衰减：保留 0.5 长期记忆底线，超出部分严格 45 天半衰期，叠加 24 小时平滑保鲜项消除满 1 天断崖。
        val halfLife = 0.5 + 0.5 * Math.pow(2.0, -ageDays / 45.0)
        val freshness = 0.12 * Math.pow(2.0, -ageDays)
        return (halfLife + freshness).toFloat()
    }

    private const val MAX_PERSONAL_BOOST = 0.52f
    private const val MAX_PERSONAL_PENALTY = 0.55f
    private const val MIN_INJECT_NET_SCORE = 0.04f

    @Synchronized
    fun reportLines(dir: File, limit: Int = 10): Pair<List<String>, List<String>> {
        ensureLoaded(dir)
        val topBoosts = boosts.values
            .sortedWith(compareByDescending<Entry> { it.score }.thenByDescending { it.lastUsedMs })
            .take(limit)
            .map { "${it.ctx} → ${it.word}（+${"%.1f".format(it.score)} / ${it.count}次）" }
        val topPenalties = penalties.values
            .sortedWith(compareByDescending<Entry> { it.score }.thenByDescending { it.lastUsedMs })
            .take(limit)
            .map { "${it.ctx} ✕ ${it.word}（-${"%.1f".format(it.score)} / ${it.count}次）" }
        return topBoosts to topPenalties
    }

    private fun contextWeight(len: Int): Float = when (len) {
        1 -> 0.14f
        2 -> 0.24f
        3 -> 0.30f
        else -> 0.35f
    }

    private fun trailingContexts(contextText: String): List<String> {
        val tail = contextText.takeLastWhile { isCjk(it) }.takeLast(4)
        if (tail.isEmpty()) return emptyList()
        return (tail.length downTo 1).map { len -> tail.substring(tail.length - len) }
    }

    private fun cleanChineseWord(text: String): String =
        text.trim().filter(::isCjk).take(12)

    private fun isCjk(c: Char): Boolean = c in '\u4e00'..'\u9fff'

    /** 单字上文只允许 ≤4 字的常见词组，2 字以上长上文才允许记录/提权长短语，避免一次性长句污染高频单字。 */
    internal fun allowsAssociationWord(ctxLen: Int, wordLen: Int): Boolean =
        ctxLen >= 2 || wordLen <= 4

    private fun addBoostLocked(ctx: String, word: String, delta: Float, now: Long): Boolean {
        if (!allowsAssociationWord(ctx.length, word.length)) return false
        val k = "$ctx\t$word"
        val cur = boosts[k]
        if (cur != null) {
            cur.score = min(40f, cur.score + delta)
            cur.count += 1
            cur.lastUsedMs = now
        } else {
            val e = Entry(ctx, word, delta, 1, now)
            boosts[k] = e
            boostsByCtx.getOrPut(ctx) { ArrayList(4) }.add(e)
            trimMapLocked(boosts, boostsByCtx, MAX_BOOSTS)
        }
        return true
    }

    private fun addPenaltyLocked(ctx: String, word: String, delta: Float, now: Long): Boolean {
        val k = "$ctx\t$word"
        val cur = penalties[k]
        if (cur != null) {
            cur.score = min(30f, cur.score + delta)
            cur.count += 1
            cur.lastUsedMs = now
        } else {
            val e = Entry(ctx, word, delta, 1, now)
            penalties[k] = e
            penaltiesByCtx.getOrPut(ctx) { ArrayList(4) }.add(e)
            trimMapLocked(penalties, penaltiesByCtx, MAX_PENALTIES)
        }
        return true
    }

    private fun decayPenaltyLocked(ctx: String, word: String, factor: Float): Boolean {
        val k = "$ctx\t$word"
        val cur = penalties[k] ?: return false
        cur.score *= factor
        if (cur.score < 0.25f) {
            penalties.remove(k)
            penaltiesByCtx[ctx]?.remove(cur)
        }
        return true
    }

    private fun trimMapLocked(
        map: LinkedHashMap<String, Entry>,
        byCtx: HashMap<String, ArrayList<Entry>>,
        cap: Int,
    ) {
        if (map.size <= cap) return
        val victims = map.entries.sortedBy { it.value.score * 0.2 + it.value.lastUsedMs / 86_400_000.0 }
            .take(map.size - cap)
        for ((k, e) in victims) {
            map.remove(k)
            byCtx[e.ctx]?.remove(e)
        }
    }

    private fun loadEntries(
        file: File,
        map: LinkedHashMap<String, Entry>,
        byCtx: HashMap<String, ArrayList<Entry>>,
        filterSingleCharLongWord: Boolean = false,
    ) {
        if (!file.isFile) return
        var dropped = false
        runCatching {
            file.forEachLine(Charsets.UTF_8) { line ->
                val parts = line.split('\t')
                if (parts.size < 5) return@forEachLine
                val ctx = parts[0]
                val word = parts[1]
                val score = parts[2].toFloatOrNull() ?: return@forEachLine
                val count = parts[3].toIntOrNull() ?: 1
                val ts = parts[4].toLongOrNull() ?: 0L
                if (ctx.isEmpty() || word.isEmpty()) return@forEachLine
                if (filterSingleCharLongWord && !allowsAssociationWord(ctx.length, word.length)) {
                    dropped = true
                    return@forEachLine
                }
                val e = Entry(ctx, word, score, count, ts)
                map["$ctx\t$word"] = e
                byCtx.getOrPut(ctx) { ArrayList(4) }.add(e)
            }
        }
        if (dropped) saveEntries(file, map)
    }

    private fun saveEntries(file: File, map: Map<String, Entry>) {
        runCatching {
            file.parentFile?.mkdirs()
            val text = buildString {
                for (e in map.values) {
                    append(e.ctx).append('\t')
                        .append(e.word).append('\t')
                        .append(e.score).append('\t')
                        .append(e.count).append('\t')
                        .append(e.lastUsedMs).append('\n')
                }
            }
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(text, Charsets.UTF_8)
            tmp.renameTo(file)
        }
    }
}
