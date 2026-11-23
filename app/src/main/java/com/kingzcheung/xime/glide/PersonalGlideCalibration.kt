package com.kingzcheung.xime.glide

import com.kingzcheung.xime.keyboard.LetterKeyRect
import com.kingzcheung.xime.settings.DoublePinyinScheme
import java.io.File
import java.util.Properties
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 本地中文整句滑行自递进校准引擎（严格仅保存在本机 [filesDir]，不上传网络）：
 * 1. 按方向桶记录每颗字母键的个人指腹落点偏移（EMA 渐进修正，降低长期固定偏斜误触）；
 * 2. 记录用户确认/局部修改/点选备选后的「高频连续词组与整句骨架」（动态补充静态词表盲区）；
 * 3. 记录「回删重打 / 改选备选」混淆对（[badCode -> goodCode]），负反馈压低误认路径；
 * 4. 环形缓存本机真实确认轨迹样本，支持离线回放自动调优 [SyllableGlideParams]。
 */
object PersonalGlideCalibration {
    private const val OFFSETS_FILE = "personal_glide_offsets.tsv"
    private const val PHRASES_FILE = "personal_glide_phrases.tsv"
    private const val CONFUSIONS_FILE = "personal_glide_confusions.tsv"
    private const val SAMPLES_FILE = "personal_glide_samples.tsv"
    private const val PARAMS_FILE = "personal_glide_params.properties"

    private const val MAX_PHRASES = 1500
    private const val MAX_CONFUSIONS = 400
    private const val MAX_SAMPLES = 600
    private const val MAX_OFFSET_UNITS = 0.42f
    private const val EMA_ALPHA = 0.045f
    private const val REJECT_WINDOW_MS = 20_000L

    data class KeyOffset(val dx: Float, val dy: Float, val count: Int)

    data class Stats(
        val calibratedKeys: Int,
        val phraseCount: Int,
        val sampleCount: Int,
        val confusionCount: Int,
    )

    data class TuneResult(
        val sampleCount: Int,
        val beforeTop1: Int,
        val afterTop1: Int,
        val beforeTop3: Int,
        val afterTop3: Int,
        val updated: Boolean,
    )

    private data class PhraseEntry(
        val schema: String,
        val code: String,
        var text: String,
        var freq: Int,
        var lastUsedMs: Long,
    )

    private data class SampleEntry(
        val schema: String,
        val bucket: String,
        val code: String,
        val text: String,
        val normXs: FloatArray,
        val normYs: FloatArray,
        val normTs: LongArray,
    )

    data class PendingGlide(
        val sessionId: Long,
        val schemaKey: String,
        val bucket: String,
        val keyboard: GlideKeyboard,
        val xs: FloatArray,
        val ys: FloatArray,
        val ts: LongArray,
        var code: String,
        val createdAtMs: Long,
        var deletedAtMs: Long = 0L,
        var hadManualEditAfterDelete: Boolean = false,
    )

    private val phraseRevisionCounter = AtomicLong(1L)
    private val offsetRevisionCounter = AtomicLong(1L)
    private val paramsRevisionCounter = AtomicLong(1L)

    @Volatile private var loadedDir: String? = null
    private val offsets = HashMap<String, HashMap<Char, KeyOffset>>()
    private val phrases = LinkedHashMap<String, PhraseEntry>()
    private val confusions = LinkedHashMap<String, Int>()
    private val samples = ArrayList<SampleEntry>()
    @Volatile private var currentParams: SyllableGlideParams = SyllableGlideParams()
    @Volatile private var pendingGlide: PendingGlide? = null
    private var samplesSinceAutoTune = 0

    fun phraseRevision(): Long = phraseRevisionCounter.get()
    fun offsetRevision(): Long = offsetRevisionCounter.get()
    fun paramsRevision(): Long = paramsRevisionCounter.get()

    fun bucketFor(keys: Map<Char, LetterKeyRect>): String {
        if (keys.isEmpty()) return "p"
        val minX = keys.values.minOf { it.x }
        val maxX = keys.values.maxOf { it.x + it.width }
        val minY = keys.values.minOf { it.y }
        val maxY = keys.values.maxOf { it.y + it.height }
        val w = maxX - minX
        val h = maxY - minY
        return if (h > 0f && w / h > 2.35f) "l" else "p"
    }

    @Synchronized
    fun ensureLoaded(dir: File) {
        val path = dir.absolutePath
        if (loadedDir == path) return
        loadedDir = path
        offsets.clear()
        phrases.clear()
        confusions.clear()
        samples.clear()
        currentParams = loadParams(File(dir, PARAMS_FILE))
        loadOffsets(File(dir, OFFSETS_FILE))
        loadPhrases(File(dir, PHRASES_FILE))
        loadConfusions(File(dir, CONFUSIONS_FILE))
        loadSamples(File(dir, SAMPLES_FILE))
    }

    @Synchronized
    fun params(dir: File): SyllableGlideParams {
        ensureLoaded(dir)
        return currentParams
    }

    @Synchronized
    fun stats(dir: File): Stats {
        ensureLoaded(dir)
        val keyCount = offsets.values.sumOf { it.size }
        return Stats(keyCount, phrases.size, samples.size, confusions.size)
    }

    /** 按学习到的按键偏移微调键盘中心，偏移按 1/16 键宽量化以减少模板重建频率。 */
    @Synchronized
    fun applyOffsets(dir: File, bucket: String, keyboard: GlideKeyboard): GlideKeyboard {
        ensureLoaded(dir)
        val map = offsets[bucket] ?: return keyboard
        if (map.isEmpty()) return keyboard
        var changed = false
        val shifted = HashMap<Char, Pair<Float, Float>>(keyboard.centers.size)
        for ((ch, center) in keyboard.centers) {
            val offset = map[ch]
            if (offset == null || offset.count < 2) {
                shifted[ch] = center
                continue
            }
            // 按 1/16 键宽量化，既足够精细又不因微幅 EMA 抖动频繁触发 3 万词模板重建。
            val qdx = (offset.dx * 16f).roundToInt() / 16f
            val qdy = (offset.dy * 16f).roundToInt() / 16f
            if (qdx != 0f || qdy != 0f) changed = true
            shifted[ch] = (center.first + qdx * keyboard.keyUnit) to (center.second + qdy * keyboard.keyUnit)
        }
        return if (changed) GlideKeyboard(keyboard.keyUnit, shifted) else keyboard
    }

    /** 高频个人连续短语编码（用于动态注入模板与打分加权）。 */
    @Synchronized
    fun hotPhraseCodes(dir: File, schemaKey: String, limit: Int = 350): List<String> {
        ensureLoaded(dir)
        if (schemaKey.isEmpty() || phrases.isEmpty()) return emptyList()
        return phrases.values
            .asSequence()
            .filter { it.schema == schemaKey && it.code.length >= 2 }
            .sortedWith(compareByDescending<PhraseEntry> { it.freq }.thenByDescending { it.lastUsedMs })
            .take(limit)
            .map { it.code }
            .toList()
    }

    @Synchronized
    fun phraseTextOf(dir: File, schemaKey: String, code: String): String? {
        ensureLoaded(dir)
        return phrases[phraseKey(schemaKey, code)]?.text
    }

    /** 对候选编码施加个人高频加分与历史误认路径惩戒。 */
    @Synchronized
    fun candidateCostDelta(dir: File, schemaKey: String, candidateCode: String, allCandidateCodes: Collection<String>): Float {
        ensureLoaded(dir)
        var delta = 0f
        phrases[phraseKey(schemaKey, candidateCode)]?.let { entry ->
            delta -= min(0.55f, 0.14f * ln(1.0 + entry.freq).toFloat())
        }
        for (other in allCandidateCodes) {
            if (other == candidateCode) continue
            val preferThis = confusions[confusionKey(schemaKey, other, candidateCode)] ?: 0
            if (preferThis > 0) delta -= min(0.60f, 0.20f * ln(1.0 + preferThis).toFloat())
            val preferOther = confusions[confusionKey(schemaKey, candidateCode, other)] ?: 0
            if (preferOther > 0) delta += min(0.45f, 0.15f * ln(1.0 + preferOther).toFloat())
        }
        return delta
    }

    fun noteDispatched(
        sessionId: Long,
        schemaKey: String,
        bucket: String,
        keyboard: GlideKeyboard,
        xs: FloatArray,
        ys: FloatArray,
        ts: LongArray,
        code: String,
    ) {
        if (code.isEmpty() || xs.size < 2 || xs.size != ys.size) return
        pendingGlide = PendingGlide(
            sessionId = sessionId,
            schemaKey = schemaKey,
            bucket = bucket,
            keyboard = keyboard,
            xs = xs.copyOf(),
            ys = ys.copyOf(),
            ts = ts.copyOf(),
            code = code,
            createdAtMs = System.currentTimeMillis(),
        )
    }

    fun updatePendingCode(sessionId: Long, code: String) {
        val p = pendingGlide ?: return
        if (p.sessionId == sessionId) p.code = code
    }

    fun noteManualTypingAfterGlide(sessionId: Long) {
        val p = pendingGlide ?: return
        if (p.sessionId == sessionId && p.deletedAtMs > 0L) p.hadManualEditAfterDelete = true
    }

    fun noteGlideDeleted(sessionId: Long) {
        val p = pendingGlide ?: return
        if (p.sessionId == sessionId && p.deletedAtMs == 0L) {
            p.deletedAtMs = System.currentTimeMillis()
        }
    }

    @Synchronized
    fun onAlternativeChosen(dir: File, sessionId: Long, schemaKey: String, chosenCode: String, chosenText: String) {
        ensureLoaded(dir)
        val cleanChosen = chosenCode.replace("'", "").lowercase()
        if (cleanChosen.isEmpty() || chosenText.isEmpty()) return
        val pending = pendingGlide?.takeIf { it.sessionId == sessionId && it.schemaKey == schemaKey }
        if (pending != null) {
            val bad = pending.code.replace("'", "").lowercase()
            if (bad.isNotEmpty() && bad != cleanChosen) {
                recordConfusionLocked(dir, schemaKey, bad, cleanChosen)
            }
            calibrateOffsetsFromTraceLocked(dir, pending.bucket, pending.keyboard, pending.xs, pending.ys, cleanChosen)
            recordSampleLocked(dir, schemaKey, pending.bucket, pending.keyboard, cleanChosen, chosenText, pending.xs, pending.ys, pending.ts)
            pending.code = cleanChosen
        }
        recordPhraseLocked(dir, schemaKey, cleanChosen, chosenText)
    }

    @Synchronized
    fun onCommitted(dir: File, sessionId: Long, schemaKey: String, rawCode: String, committedText: String) {
        ensureLoaded(dir)
        val cleanCode = rawCode.replace("'", "").lowercase().filter { it in 'a'..'z' }
        if (cleanCode.length < 2 || committedText.isEmpty()) {
            pendingGlide = null
            return
        }
        recordPhraseLocked(dir, schemaKey, cleanCode, committedText)

        val pending = pendingGlide
        pendingGlide = null
        if (pending == null || pending.sessionId != sessionId || pending.schemaKey != schemaKey) return

        val now = System.currentTimeMillis()
        val pendingClean = pending.code.replace("'", "").lowercase()
        if (pending.deletedAtMs > 0L) {
            if (now - pending.deletedAtMs <= REJECT_WINDOW_MS && pendingClean.isNotEmpty() && pendingClean != cleanCode) {
                recordConfusionLocked(dir, schemaKey, pendingClean, cleanCode)
                if (pending.hadManualEditAfterDelete && abs(cleanCode.length - pendingClean.length) <= 3) {
                    calibrateOffsetsFromTraceLocked(dir, pending.bucket, pending.keyboard, pending.xs, pending.ys, cleanCode)
                    recordSampleLocked(dir, schemaKey, pending.bucket, pending.keyboard, cleanCode, committedText, pending.xs, pending.ys, pending.ts)
                }
            }
            return
        }

        if (pendingClean == cleanCode || cleanCode.endsWith(pendingClean)) {
            val traceTarget = if (pendingClean == cleanCode) cleanCode else pendingClean
            calibrateOffsetsFromTraceLocked(dir, pending.bucket, pending.keyboard, pending.xs, pending.ys, traceTarget)
            recordSampleLocked(dir, schemaKey, pending.bucket, pending.keyboard, traceTarget, committedText, pending.xs, pending.ys, pending.ts)
        }
    }

    @Synchronized
    fun clear(dir: File) {
        loadedDir = dir.absolutePath
        offsets.clear()
        phrases.clear()
        confusions.clear()
        samples.clear()
        currentParams = SyllableGlideParams()
        pendingGlide = null
        samplesSinceAutoTune = 0
        listOf(OFFSETS_FILE, PHRASES_FILE, CONFUSIONS_FILE, SAMPLES_FILE, PARAMS_FILE).forEach {
            File(dir, it).delete()
        }
        phraseRevisionCounter.incrementAndGet()
        offsetRevisionCounter.incrementAndGet()
        paramsRevisionCounter.incrementAndGet()
    }

    /** 使用本机累计确认/纠错的真实轨迹做离线坐标回放调参，仅在 Top-1 确实提升时写回参数。 */
    @Synchronized
    fun tuneFromHistory(
        dir: File,
        schemaKey: String,
        keyboard: GlideKeyboard,
        syllables: List<String>,
    ): TuneResult {
        ensureLoaded(dir)
        val targetSamples = samples.filter { it.schema == schemaKey && it.code.length >= 4 }.takeLast(200)
        if (targetSamples.size < 12) {
            val r = evaluateSamples(targetSamples, keyboard, syllables, currentParams)
            return TuneResult(targetSamples.size, r.first, r.first, r.second, r.second, updated = false)
        }
        val baseline = currentParams
        val baseScore = evaluateSamples(targetSamples, keyboard, syllables, baseline)
        var best = baseline
        var bestScore = baseScore

        val candidates = listOf(
            baseline.copy(hitWeight = 1.25f, segmentWeight = 1.65f, pauseBonus = 0.28f),
            baseline.copy(hitWeight = 1.75f, detourWeight = 2.6f, pauseBonus = 0.28f),
            baseline.copy(keyCost = 0.38f, repeatCost = 0.52f, pauseBonus = 0.25f),
            baseline.copy(keyCost = 0.52f, detourWeight = 3.4f, pauseBonus = 0.30f),
            baseline.copy(syllablePrior = 0.45f, pauseBonus = 0.30f),
            baseline.copy(syllablePrior = 0.78f, hitWeight = 1.4f, pauseBonus = 0.20f),
        )
        for (cand in candidates) {
            val score = evaluateSamples(targetSamples, keyboard, syllables, cand)
            if (score.first > bestScore.first || (score.first == bestScore.first && score.second > bestScore.second)) {
                best = cand
                bestScore = score
            }
        }
        val improved = best !== baseline && (bestScore.first > baseScore.first ||
            (bestScore.first == baseScore.first && bestScore.second > baseScore.second))
        if (improved) {
            currentParams = best
            saveParams(File(dir, PARAMS_FILE), best)
            paramsRevisionCounter.incrementAndGet()
        }
        return TuneResult(targetSamples.size, baseScore.first, bestScore.first, baseScore.second, bestScore.second, improved)
    }

    private fun evaluateSamples(
        list: List<SampleEntry>,
        keyboard: GlideKeyboard,
        syllables: List<String>,
        params: SyllableGlideParams,
    ): Pair<Int, Int> {
        if (list.isEmpty()) return 0 to 0
        val unit = keyboard.keyUnit
        val minCenterX = keyboard.centers.values.minOfOrNull { it.first } ?: 0f
        val minCenterY = keyboard.centers.values.minOfOrNull { it.second } ?: 0f
        val decoder = SyllableGlide(keyboard, syllables, params)
        var top1 = 0
        var top3 = 0
        for (s in list) {
            val xs = FloatArray(s.normXs.size) { minCenterX + s.normXs[it] * unit }
            val ys = FloatArray(s.normYs.size) { minCenterY + s.normYs[it] * unit }
            val predicted = decoder.decodeScored(xs, ys, s.normTs, limit = 6).map { it.code }
            if (predicted.firstOrNull() == s.code) top1++
            if (s.code in predicted.take(3)) top3++
        }
        return top1 to top3
    }

    private fun calibrateOffsetsFromTraceLocked(
        dir: File,
        bucket: String,
        keyboard: GlideKeyboard,
        xs: FloatArray,
        ys: FloatArray,
        code: String,
    ) {
        val clean = GlideDecoder.collapseRepeats(code.lowercase().filter { it in keyboard.centers })
        if (clean.length < 2 || xs.size < 2) return
        val aligned = alignTraceToKeys(keyboard, xs, ys, clean) ?: return
        val bucketMap = offsets.getOrPut(bucket) { HashMap() }
        var changed = false
        for (idx in clean.indices) {
            val ch = clean[idx]
            val center = keyboard.centers[ch] ?: continue
            val (px, py) = aligned[idx]
            val resX = ((px - center.first) / keyboard.keyUnit).coerceIn(-0.65f, 0.65f)
            val resY = ((py - center.second) / keyboard.keyUnit).coerceIn(-0.65f, 0.65f)
            val old = bucketMap[ch]
            val nextDx = if (old == null) (resX * EMA_ALPHA).coerceIn(-MAX_OFFSET_UNITS, MAX_OFFSET_UNITS)
            else (old.dx * (1f - EMA_ALPHA) + resX * EMA_ALPHA).coerceIn(-MAX_OFFSET_UNITS, MAX_OFFSET_UNITS)
            val nextDy = if (old == null) (resY * EMA_ALPHA).coerceIn(-MAX_OFFSET_UNITS, MAX_OFFSET_UNITS)
            else (old.dy * (1f - EMA_ALPHA) + resY * EMA_ALPHA).coerceIn(-MAX_OFFSET_UNITS, MAX_OFFSET_UNITS)
            bucketMap[ch] = KeyOffset(nextDx, nextDy, (old?.count ?: 0) + 1)
            changed = true
        }
        if (changed) {
            offsetRevisionCounter.incrementAndGet()
            saveOffsets(File(dir, OFFSETS_FILE))
        }
    }

    /**
     * 对齐已知编码串到轨迹采样点（单调 DP，约束均摊偏差不超过 1.35 键宽），用于提取各按键真实落点残差。
     */
    internal fun alignTraceToKeys(
        keyboard: GlideKeyboard,
        xs: FloatArray,
        ys: FloatArray,
        collapsedCode: String,
    ): List<Pair<Float, Float>>? {
        val m = collapsedCode.length
        if (m < 2 || xs.size < 2) return null
        val n = min(xs.size, 96)
        val (rx, ry) = if (xs.size == n) xs to ys else GlideDecoder.resampleTo(xs, ys, n)
        if (n < m) return null
        val dp = Array(m) { FloatArray(n) { Float.POSITIVE_INFINITY } }
        val prev = Array(m) { IntArray(n) { -1 } }

        val c0 = keyboard.centers[collapsedCode[0]] ?: return null
        dp[0][0] = hypot(rx[0] - c0.first, ry[0] - c0.second) / keyboard.keyUnit
        for (j in 1 until n) {
            val d = hypot(rx[j] - c0.first, ry[j] - c0.second) / keyboard.keyUnit
            if (d < dp[0][j - 1]) dp[0][j] = d
            else { dp[0][j] = dp[0][j - 1] }
        }
        // 记录第 0 字符严格取 j 的直接拟合起点
        for (j in 0 until n) dp[0][j] = hypot(rx[j] - c0.first, ry[j] - c0.second) / keyboard.keyUnit

        for (i in 1 until m) {
            val ci = keyboard.centers[collapsedCode[i]] ?: return null
            var bestPrevCost = Float.POSITIVE_INFINITY
            var bestPrevIdx = -1
            for (j in i until n) {
                val candPrev = dp[i - 1][j - 1]
                if (candPrev < bestPrevCost) {
                    bestPrevCost = candPrev
                    bestPrevIdx = j - 1
                }
                if (bestPrevCost.isFinite()) {
                    val stepDist = hypot(rx[j] - ci.first, ry[j] - ci.second) / keyboard.keyUnit
                    dp[i][j] = bestPrevCost + stepDist
                    prev[i][j] = bestPrevIdx
                }
            }
        }
        var bestLastIdx = m - 1
        var bestTotal = dp[m - 1][m - 1]
        for (j in m until n) {
            if (dp[m - 1][j] < bestTotal) {
                bestTotal = dp[m - 1][j]
                bestLastIdx = j
            }
        }
        if (!bestTotal.isFinite() || bestTotal / m > 1.35f) return null
        val result = MutableList(m) { 0f to 0f }
        var curJ = bestLastIdx
        for (i in m - 1 downTo 0) {
            result[i] = rx[curJ] to ry[curJ]
            if (i > 0) curJ = prev[i][curJ].coerceAtLeast(0)
        }
        return result
    }

    private fun recordPhraseLocked(dir: File, schemaKey: String, code: String, text: String) {
        if ('\t' in text || '\n' in text || '\t' in code || '\n' in code) return
        val key = phraseKey(schemaKey, code)
        val now = System.currentTimeMillis()
        val existing = phrases[key]
        if (existing != null) {
            existing.freq += 1
            existing.lastUsedMs = now
            existing.text = text
        } else {
            if (phrases.size >= MAX_PHRASES) {
                val victim = phrases.entries.minByOrNull { it.value.freq * 1_000_000L + it.value.lastUsedMs / 1000L }?.key
                if (victim != null) phrases.remove(victim)
            }
            phrases[key] = PhraseEntry(schemaKey, code, text, 1, now)
        }
        phraseRevisionCounter.incrementAndGet()
        savePhrases(File(dir, PHRASES_FILE))
    }

    private fun recordConfusionLocked(dir: File, schemaKey: String, badCode: String, goodCode: String) {
        val k = confusionKey(schemaKey, badCode, goodCode)
        confusions[k] = (confusions[k] ?: 0) + 1
        while (confusions.size > MAX_CONFUSIONS) {
            val first = confusions.keys.firstOrNull() ?: break
            confusions.remove(first)
        }
        saveConfusions(File(dir, CONFUSIONS_FILE))
    }

    private fun recordSampleLocked(
        dir: File,
        schemaKey: String,
        bucket: String,
        keyboard: GlideKeyboard,
        code: String,
        text: String,
        xs: FloatArray,
        ys: FloatArray,
        ts: LongArray,
    ) {
        if (xs.size < 2 || ys.size != xs.size || '\t' in text || '\n' in text) return
        val unit = keyboard.keyUnit
        val minCenterX = keyboard.centers.values.minOfOrNull { it.first } ?: return
        val minCenterY = keyboard.centers.values.minOfOrNull { it.second } ?: return
        val cap = min(xs.size, 64)
        val (rx, ry) = if (xs.size <= cap) xs to ys else GlideDecoder.resampleTo(xs, ys, cap)
        val normXs = FloatArray(rx.size) { ((rx[it] - minCenterX) / unit * 100f).roundToInt() / 100f }
        val normYs = FloatArray(ry.size) { ((ry[it] - minCenterY) / unit * 100f).roundToInt() / 100f }
        val baseT = ts.firstOrNull() ?: 0L
        val normTs = if (ts.size == xs.size && rx.size == xs.size) {
            LongArray(ts.size) { (ts[it] - baseT).coerceAtLeast(0L) }
        } else if (ts.size >= 2) {
            val totalDt = (ts.last() - baseT).coerceAtLeast(1L)
            LongArray(rx.size) { idx -> totalDt * idx / max(1, rx.size - 1) }
        } else {
            LongArray(rx.size) { idx -> idx * 12L }
        }
        if (samples.size >= MAX_SAMPLES) samples.removeAt(0)
        samples += SampleEntry(schemaKey, bucket, code, text, normXs, normYs, normTs)
        samplesSinceAutoTune++
        saveSamples(File(dir, SAMPLES_FILE))
    }

    fun shouldAutoTune(): Boolean = samplesSinceAutoTune >= 25
    fun markAutoTuned() { samplesSinceAutoTune = 0 }

    private fun phraseKey(schema: String, code: String) = "$schema\t$code"
    private fun confusionKey(schema: String, bad: String, good: String) = "$schema\t$bad\t$good"
    private fun hypot(dx: Float, dy: Float): Float = sqrt(dx * dx + dy * dy)

    private fun loadOffsets(file: File) {
        if (!file.isFile) return
        runCatching {
            file.forEachLine { line ->
                val parts = line.split('\t')
                if (parts.size < 5) return@forEachLine
                val bucket = parts[0]
                val ch = parts[1].singleOrNull() ?: return@forEachLine
                val dx = parts[2].toFloatOrNull() ?: return@forEachLine
                val dy = parts[3].toFloatOrNull() ?: return@forEachLine
                val count = parts[4].toIntOrNull() ?: 1
                offsets.getOrPut(bucket) { HashMap() }[ch] = KeyOffset(dx, dy, count)
            }
        }
    }

    private fun saveOffsets(file: File) {
        writeAtomic(file) {
            buildString {
                offsets.forEach { (bucket, map) ->
                    map.forEach { (ch, off) ->
                        append(bucket).append('\t').append(ch).append('\t')
                            .append(off.dx).append('\t').append(off.dy).append('\t').append(off.count).append('\n')
                    }
                }
            }
        }
    }

    private fun loadPhrases(file: File) {
        if (!file.isFile) return
        runCatching {
            file.forEachLine { line ->
                val p = line.split('\t')
                if (p.size < 5) return@forEachLine
                val freq = p[3].toIntOrNull() ?: return@forEachLine
                val ts = p[4].toLongOrNull() ?: 0L
                phrases[phraseKey(p[0], p[1])] = PhraseEntry(p[0], p[1], p[2], freq, ts)
            }
        }
    }

    private fun savePhrases(file: File) {
        writeAtomic(file) {
            buildString {
                phrases.values.forEach {
                    append(it.schema).append('\t').append(it.code).append('\t')
                        .append(it.text).append('\t').append(it.freq).append('\t').append(it.lastUsedMs).append('\n')
                }
            }
        }
    }

    private fun loadConfusions(file: File) {
        if (!file.isFile) return
        runCatching {
            file.forEachLine { line ->
                val p = line.split('\t')
                if (p.size < 4) return@forEachLine
                val count = p[3].toIntOrNull() ?: return@forEachLine
                confusions[confusionKey(p[0], p[1], p[2])] = count
            }
        }
    }

    private fun saveConfusions(file: File) {
        writeAtomic(file) {
            buildString {
                confusions.forEach { (k, count) -> append(k).append('\t').append(count).append('\n') }
            }
        }
    }

    private fun loadSamples(file: File) {
        if (!file.isFile) return
        runCatching {
            file.forEachLine { line ->
                val p = line.split('\t')
                if (p.size < 7) return@forEachLine
                val xs = p[4].split(',').mapNotNull { it.toFloatOrNull() }.toFloatArray()
                val ys = p[5].split(',').mapNotNull { it.toFloatOrNull() }.toFloatArray()
                val ts = p[6].split(',').mapNotNull { it.toLongOrNull() }.toLongArray()
                if (xs.size >= 2 && xs.size == ys.size && ts.size == xs.size) {
                    samples += SampleEntry(p[0], p[1], p[2], p[3], xs, ys, ts)
                }
            }
        }
    }

    private fun saveSamples(file: File) {
        writeAtomic(file) {
            buildString {
                samples.forEach { s ->
                    append(s.schema).append('\t').append(s.bucket).append('\t')
                        .append(s.code).append('\t').append(s.text).append('\t')
                        .append(s.normXs.joinToString(",")).append('\t')
                        .append(s.normYs.joinToString(",")).append('\t')
                        .append(s.normTs.joinToString(",")).append('\n')
                }
            }
        }
    }

    private fun loadParams(file: File): SyllableGlideParams {
        if (!file.isFile) return SyllableGlideParams()
        return runCatching {
            val props = Properties()
            file.inputStream().use { props.load(it) }
            val def = SyllableGlideParams()
            SyllableGlideParams(
                hitWeight = props.getProperty("hitWeight")?.toFloatOrNull() ?: def.hitWeight,
                segmentWeight = props.getProperty("segmentWeight")?.toFloatOrNull() ?: def.segmentWeight,
                keyCost = props.getProperty("keyCost")?.toFloatOrNull() ?: def.keyCost,
                repeatCost = props.getProperty("repeatCost")?.toFloatOrNull() ?: def.repeatCost,
                detourWeight = props.getProperty("detourWeight")?.toFloatOrNull() ?: def.detourWeight,
                syllableCost = props.getProperty("syllableCost")?.toFloatOrNull() ?: def.syllableCost,
                syllablePrior = props.getProperty("syllablePrior")?.toFloatOrNull() ?: def.syllablePrior,
                pauseBonus = props.getProperty("pauseBonus")?.toFloatOrNull() ?: def.pauseBonus,
            )
        }.getOrElse { SyllableGlideParams() }
    }

    private fun saveParams(file: File, p: SyllableGlideParams) {
        writeAtomic(file) {
            buildString {
                append("hitWeight=").append(p.hitWeight).append('\n')
                append("segmentWeight=").append(p.segmentWeight).append('\n')
                append("keyCost=").append(p.keyCost).append('\n')
                append("repeatCost=").append(p.repeatCost).append('\n')
                append("detourWeight=").append(p.detourWeight).append('\n')
                append("syllableCost=").append(p.syllableCost).append('\n')
                append("syllablePrior=").append(p.syllablePrior).append('\n')
                append("pauseBonus=").append(p.pauseBonus).append('\n')
            }
        }
    }

    private inline fun writeAtomic(file: File, content: () -> String) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(content())
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }
}
