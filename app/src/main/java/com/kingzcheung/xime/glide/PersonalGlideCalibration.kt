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
        val badCode: String = "",
        val source: String = "confirm",
    )

    data class ExportSummary(
        val file: File,
        val recordCount: Int,
        val fileCount: Int,
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
        var committedText: String = "",
        var learnedConsumed: Boolean = false,
        var originalBadCode: String = "",
        var editedBySentenceEdit: Boolean = false,
    )

    private val phraseRevisionCounter = AtomicLong(1L)
    private val offsetRevisionCounter = AtomicLong(1L)
    private val paramsRevisionCounter = AtomicLong(1L)

    @Volatile private var loadedDir: String? = null
    private val offsets = HashMap<String, HashMap<Char, KeyOffset>>()
    private val phrases = LinkedHashMap<String, PhraseEntry>()
    private val confusions = LinkedHashMap<String, Int>()
    private val localConfusions = HashMap<String, Int>()
    private val samples = ArrayList<SampleEntry>()
    @Volatile private var currentParams: SyllableGlideParams = SyllableGlideParams()
    @Volatile private var pendingGlide: PendingGlide? = null
    @Volatile private var lastCommittedGlide: PendingGlide? = null
    private val recentPendingGlides = ArrayDeque<PendingGlide>(4)
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
        localConfusions.clear()
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

    /** 对候选编码施加个人高频加分与历史误认路径惩戒（含局部音节/子串泛化）。 */
    @Synchronized
    fun candidateCostDelta(dir: File, schemaKey: String, candidateCode: String, allCandidateCodes: Collection<String>): Float {
        ensureLoaded(dir)
        var delta = 0f
        phrases[phraseKey(schemaKey, candidateCode)]?.let { entry ->
            delta -= min(0.55f, 0.14f * ln(1.0 + entry.freq).toFloat())
        }
        var localBonus = 0f
        var localPenalty = 0f
        for (other in allCandidateCodes) {
            if (other == candidateCode) continue
            val preferThis = confusions[confusionKey(schemaKey, other, candidateCode)] ?: 0
            if (preferThis > 0) delta -= min(0.60f, 0.20f * ln(1.0 + preferThis).toFloat())
            val preferOther = confusions[confusionKey(schemaKey, candidateCode, other)] ?: 0
            if (preferOther > 0) delta += min(0.45f, 0.15f * ln(1.0 + preferOther).toFloat())

            // 局部音节/子串泛化：即使整句编码从未出现过，只要差异片段命中历史混淆对也能生效。
            if (preferThis == 0 && preferOther == 0) {
                val local = extractLocalConfusion(schemaKey, other, candidateCode)
                if (local != null) {
                    val (localOther, localCand) = local
                    val localPreferThis = (confusions[confusionKey(schemaKey, localOther, localCand)] ?: 0) +
                        (localConfusions[confusionKey(schemaKey, localOther, localCand)] ?: 0)
                    if (localPreferThis > 0) {
                        localBonus = max(localBonus, min(0.42f, 0.16f * ln(1.0 + localPreferThis).toFloat()))
                    }
                    val localPreferOther = (confusions[confusionKey(schemaKey, localCand, localOther)] ?: 0) +
                        (localConfusions[confusionKey(schemaKey, localCand, localOther)] ?: 0)
                    if (localPreferOther > 0) {
                        localPenalty = max(localPenalty, min(0.32f, 0.12f * ln(1.0 + localPreferOther).toFloat()))
                    }
                }
            }
        }
        delta -= localBonus
        delta += localPenalty
        return delta
    }

    /**
     * 从整句混淆对 (badCode -> goodCode) 中抽取差异的局部音节/子串对，
     * 双拼按 2 字母音节边界对齐，使整句纠错泛化到任意新句子。
     */
    internal fun extractLocalConfusion(schemaKey: String, badCode: String, goodCode: String): Pair<String, String>? {
        if (badCode == goodCode || badCode.length < 2 || goodCode.length < 2) return null
        val isDoublePinyin = schemaKey != "pinyin"
        val minLen = min(badCode.length, goodCode.length)
        var prefix = 0
        while (prefix < minLen && badCode[prefix] == goodCode[prefix]) prefix++
        var suffix = 0
        while (suffix < minLen - prefix &&
            badCode[badCode.length - 1 - suffix] == goodCode[goodCode.length - 1 - suffix]
        ) {
            suffix++
        }
        if (isDoublePinyin) {
            prefix = (prefix / 2) * 2
            while (suffix > 0 && ((badCode.length - suffix) % 2 != 0 || (goodCode.length - suffix) % 2 != 0)) {
                suffix--
            }
        }
        var bStart = prefix
        var bEnd = badCode.length - suffix
        var gStart = prefix
        var gEnd = goodCode.length - suffix
        if (bEnd <= bStart || gEnd <= gStart) {
            val step = 2
            if (bStart >= step && gStart >= step) {
                bStart -= step
                gStart -= step
            } else if (bEnd + step <= badCode.length && gEnd + step <= goodCode.length) {
                bEnd += step
                gEnd += step
            } else {
                return null
            }
        }
        val b = badCode.substring(bStart, bEnd)
        val g = goodCode.substring(gStart, gEnd)
        if (b == g || b.length !in 2..6 || g.length !in 2..6) return null
        if (b == badCode && g == goodCode) return null
        return b to g
    }

    /** 冷启动自适应步长：前 10~15 次快速收敛到真实偏移，20 次后平滑落回稳定 EMA_ALPHA。 */
    internal fun adaptiveEmaAlpha(previousCount: Int): Float =
        max(EMA_ALPHA, 1f / (previousCount.coerceAtLeast(0) + 4f))

    @Synchronized
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
        lastCommittedGlide = null
        val created = PendingGlide(
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
        pendingGlide = created
        if (recentPendingGlides.size >= 4) recentPendingGlides.removeFirst()
        recentPendingGlides.addLast(created)
    }

    fun updatePendingCode(sessionId: Long, code: String) {
        val p = pendingGlide ?: return
        if (p.sessionId == sessionId) p.code = code
    }

    fun noteManualTypingAfterGlide(sessionId: Long) {
        pendingGlide?.let { p ->
            if (p.sessionId == sessionId && p.deletedAtMs > 0L) p.hadManualEditAfterDelete = true
        }
        lastCommittedGlide?.let { p ->
            if (p.sessionId == sessionId && p.deletedAtMs > 0L) p.hadManualEditAfterDelete = true
        }
    }

    fun noteGlideDeleted(sessionId: Long) {
        val now = System.currentTimeMillis()
        pendingGlide?.let { p ->
            if (p.sessionId == sessionId && p.deletedAtMs == 0L) p.deletedAtMs = now
        }
        lastCommittedGlide?.let { p ->
            if (p.sessionId == sessionId && p.deletedAtMs == 0L && now - p.createdAtMs <= REJECT_WINDOW_MS) {
                p.deletedAtMs = now
            }
        }
    }

    private fun isCompatibleCodeLength(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        val lenDiff = abs(a.length - b.length)
        val shortLen = min(a.length, b.length)
        val longLen = max(a.length, b.length)
        return longLen <= shortLen * 2 + 2 && lenDiff <= maxOf(5, (longLen + 1) / 2)
    }

    /**
     * 局部改错直接联动滑行校准：当用户在候选栏拖选修改了一句（部分偏离/同音错字）时，
     * 将改错后的片段编码拼回原句并把原始轨迹重新对齐到真实拼音串上。
     * [deferredUntilCommit] 为 true 时仅在内存中更新待提交轨迹的真实编码与原始错码，
     * 等真正 [onCommitted] 上屏时再按最终定稿一次性落盘，避免同一句连续多次局部改错写入中间态。
     */
    @Synchronized
    fun onSentenceEditApplied(
        dir: File,
        sessionId: Long,
        schemaKey: String,
        sentenceBefore: String,
        badPartCode: String,
        goodPartCode: String,
        sentenceAfter: String,
        fullInputCode: String = "",
        deferredUntilCommit: Boolean = false,
    ) {
        if (!deferredUntilCommit) ensureLoaded(dir)
        val candidate = (listOfNotNull(pendingGlide, lastCommittedGlide) + recentPendingGlides.reversed())
            .firstOrNull { g ->
                g.sessionId == sessionId && g.schemaKey == schemaKey && !g.learnedConsumed &&
                    (g.committedText.isEmpty() || g.committedText in sentenceBefore || sentenceBefore in g.committedText)
            } ?: return
        val cleanBadPart = badPartCode.replace("'", "").lowercase().filter { it in 'a'..'z' }
        val cleanGoodPart = goodPartCode.replace("'", "").lowercase().filter { it in 'a'..'z' }
        val baseCode = candidate.code.replace("'", "").lowercase().filter { it in 'a'..'z' }
        if (cleanBadPart.isEmpty() || cleanGoodPart.isEmpty() || baseCode.isEmpty()) return

        val cleanFullInput = fullInputCode.replace("'", "").lowercase().filter { it in 'a'..'z' }
        val idx = baseCode.indexOf(cleanBadPart)
        val fullIdx = if (idx < 0 && cleanFullInput.isNotEmpty()) cleanFullInput.indexOf(cleanBadPart) else -1
        val correctedCode = when {
            idx >= 0 -> baseCode.substring(0, idx) + cleanGoodPart + baseCode.substring(idx + cleanBadPart.length)
            fullIdx >= 0 -> cleanFullInput.substring(0, fullIdx) + cleanGoodPart + cleanFullInput.substring(fullIdx + cleanBadPart.length)
            else -> cleanGoodPart
        }
        val origBad = candidate.originalBadCode.ifEmpty { baseCode }
        if (correctedCode.length < 2 || !isCompatibleCodeLength(origBad, correctedCode)) return
        val isTailOnly = cleanFullInput.length > baseCode.length && cleanFullInput.endsWith(baseCode) && idx >= 0
        val sampleText = if (isTailOnly && schemaKey != "pinyin") {
            val expectedChars = max(1, (correctedCode.length + 1) / 2)
            if (sentenceAfter.length > expectedChars) sentenceAfter.takeLast(expectedChars) else sentenceAfter
        } else {
            sentenceAfter
        }
        if (candidate.originalBadCode.isEmpty()) candidate.originalBadCode = baseCode
        candidate.code = correctedCode
        candidate.committedText = sampleText
        candidate.editedBySentenceEdit = true
        if (deferredUntilCommit) return

        if (origBad != correctedCode) {
            recordConfusionLocked(dir, schemaKey, origBad, correctedCode)
        }
        calibrateOffsetsFromTraceLocked(dir, candidate.bucket, candidate.keyboard, candidate.xs, candidate.ys, correctedCode)
        recordSampleLocked(dir, schemaKey, candidate.bucket, candidate.keyboard, correctedCode, sampleText,
            candidate.xs, candidate.ys, candidate.ts,
            badCode = if (origBad != correctedCode) origBad else "", source = "sentence_edit")
        recordPhraseLocked(dir, schemaKey, correctedCode, sampleText)
        candidate.learnedConsumed = true
        recentPendingGlides.remove(candidate)
    }

    @Synchronized
    fun onAlternativeChosen(dir: File, sessionId: Long, schemaKey: String, chosenCode: String, chosenText: String) {
        ensureLoaded(dir)
        val cleanChosen = chosenCode.replace("'", "").lowercase()
        if (cleanChosen.isEmpty() || chosenText.isEmpty()) return
        val pending = pendingGlide?.takeIf { it.sessionId == sessionId && it.schemaKey == schemaKey && !it.learnedConsumed }
        if (pending != null) {
            val bad = pending.code.replace("'", "").lowercase()
            if (bad.isNotEmpty() && bad != cleanChosen) {
                recordConfusionLocked(dir, schemaKey, bad, cleanChosen)
            }
            calibrateOffsetsFromTraceLocked(dir, pending.bucket, pending.keyboard, pending.xs, pending.ys, cleanChosen)
            recordSampleLocked(dir, schemaKey, pending.bucket, pending.keyboard, cleanChosen, chosenText,
                pending.xs, pending.ys, pending.ts, badCode = if (bad != cleanChosen) bad else "", source = "alt")
            pending.code = cleanChosen
            pending.committedText = chosenText
            pending.learnedConsumed = true
            recentPendingGlides.remove(pending)
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

        val now = System.currentTimeMillis()
        val matchedFromQueue = recentPendingGlides.lastOrNull { g ->
            val pc = g.code.replace("'", "").lowercase()
            g.sessionId == sessionId && g.schemaKey == schemaKey && pc.isNotEmpty() &&
                (pc == cleanCode || cleanCode.endsWith(pc))
        }
        val pending = matchedFromQueue
            ?: pendingGlide?.takeIf { it.sessionId == sessionId && it.schemaKey == schemaKey }
            ?: lastCommittedGlide?.takeIf {
                it.sessionId == sessionId && it.schemaKey == schemaKey && it.deletedAtMs > 0L && now - it.deletedAtMs <= REJECT_WINDOW_MS
            }
        if (matchedFromQueue != null) recentPendingGlides.remove(matchedFromQueue)
        if (pending === pendingGlide) pendingGlide = null
        val alreadyLearnedSameTail = pending != null && pending.learnedConsumed &&
            pending.code.replace("'", "").lowercase() == cleanCode
        if (!alreadyLearnedSameTail) {
            recordPhraseLocked(dir, schemaKey, cleanCode, committedText)
        }
        if (pending == null || pending.sessionId != sessionId || pending.schemaKey != schemaKey || pending.learnedConsumed) return

        val pendingClean = pending.code.replace("'", "").lowercase()
        if (pending.deletedAtMs > 0L) {
            val lenDiff = abs(cleanCode.length - pendingClean.length)
            val shortLen = min(cleanCode.length, pendingClean.length)
            val longLen = max(cleanCode.length, pendingClean.length)
            val compatibleLen = longLen <= shortLen * 2 + 2 && lenDiff <= maxOf(5, (cleanCode.length + 1) / 2)
            if (now - pending.deletedAtMs <= REJECT_WINDOW_MS && pendingClean.isNotEmpty() && pendingClean != cleanCode && compatibleLen) {
                recordConfusionLocked(dir, schemaKey, pendingClean, cleanCode)
                // 就算识别偏离很多（比如 12 键错认成 6 键），只要在删除后手动补打出了真实拼音，仍可把原始偏离轨迹对齐回去校准指腹落点。
                if (pending.hadManualEditAfterDelete) {
                    calibrateOffsetsFromTraceLocked(dir, pending.bucket, pending.keyboard, pending.xs, pending.ys, cleanCode)
                    recordSampleLocked(dir, schemaKey, pending.bucket, pending.keyboard, cleanCode, committedText,
                        pending.xs, pending.ys, pending.ts, badCode = pendingClean, source = "delete_retype")
                }
            }
            lastCommittedGlide = null
            return
        }

        if (pendingClean == cleanCode || cleanCode.endsWith(pendingClean)) {
            val isWholeTrace = pendingClean == cleanCode
            val sampleText = if (isWholeTrace) {
                committedText
            } else {
                // 多笔连输入同一组词后再一次上屏时，[pending] 只包含最后一笔轨迹，截出末尾对应字数避免把整句误标给末笔编码。
                val rawSyls = rawCode.trim('\'', ' ').split('\'').filter { it.isNotEmpty() }
                var sumLen = 0
                var sylCount = 0
                for (s in rawSyls.asReversed()) {
                    val cleanS = s.lowercase().filter { it in 'a'..'z' }
                    if (cleanS.isEmpty()) continue
                    sumLen += cleanS.length
                    sylCount++
                    if (sumLen >= pendingClean.length) break
                }
                val tailCount = if (schemaKey != "pinyin") max(1, (pendingClean.length + 1) / 2)
                else max(1, sylCount)
                if (committedText.length > tailCount) committedText.takeLast(tailCount) else committedText
            }
            calibrateOffsetsFromTraceLocked(dir, pending.bucket, pending.keyboard, pending.xs, pending.ys, pendingClean)
            if (!isWholeTrace && sampleText.isNotEmpty()) {
                recordPhraseLocked(dir, schemaKey, pendingClean, sampleText)
            }
            val origBad = pending.originalBadCode.replace("'", "").lowercase().filter { it in 'a'..'z' }
            val validBad = if (pending.editedBySentenceEdit && origBad.isNotEmpty() && origBad != pendingClean &&
                isCompatibleCodeLength(origBad, pendingClean)) origBad else ""
            if (validBad.isNotEmpty()) {
                recordConfusionLocked(dir, schemaKey, validBad, pendingClean)
            }
            recordSampleLocked(dir, schemaKey, pending.bucket, pending.keyboard, pendingClean, sampleText,
                pending.xs, pending.ys, pending.ts,
                badCode = validBad, source = if (pending.editedBySentenceEdit) "sentence_edit" else "confirm")
            lastCommittedGlide = pending.copy(code = pendingClean, createdAtMs = now, committedText = sampleText, learnedConsumed = true)
        }
    }

    @Synchronized
    fun clear(dir: File) {
        loadedDir = dir.absolutePath
        offsets.clear()
        phrases.clear()
        confusions.clear()
        localConfusions.clear()
        samples.clear()
        currentParams = SyllableGlideParams()
        pendingGlide = null
        lastCommittedGlide = null
        recentPendingGlides.clear()
        samplesSinceAutoTune = 0
        listOf(OFFSETS_FILE, PHRASES_FILE, CONFUSIONS_FILE, SAMPLES_FILE, PARAMS_FILE).forEach {
            File(dir, it).delete()
        }
        phraseRevisionCounter.incrementAndGet()
        offsetRevisionCounter.incrementAndGet()
        paramsRevisionCounter.incrementAndGet()
    }

    /** 仅还原滑行打分权重为默认值，保留指腹偏移、高频短语、混淆表与轨迹样本。 */
    @Synchronized
    fun resetParams(dir: File): Boolean {
        ensureLoaded(dir)
        val file = File(dir, PARAMS_FILE)
        val hadCustom = file.isFile || currentParams != SyllableGlideParams()
        currentParams = SyllableGlideParams()
        file.delete()
        paramsRevisionCounter.incrementAndGet()
        return hadCustom
    }

    /**
     * 使用本机累计确认/纠错的真实轨迹做离线坐标回放调参：
     * 采用 80/20 留出验证 + 局部坐标搜索，仅在验证集确实提升且总命中不退化时才写回参数，避免小样本过拟合。
     */
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
        // 分层抽取 20% 留出验证集，兼顾早期与近期样本。
        val trainSet = ArrayList<SampleEntry>(targetSamples.size)
        val valSet = ArrayList<SampleEntry>(targetSamples.size / 4 + 1)
        targetSamples.forEachIndexed { idx, sample ->
            if (idx % 5 == 4) valSet.add(sample) else trainSet.add(sample)
        }
        val baseline = currentParams
        val baseTrain = evaluateSamples(trainSet, keyboard, syllables, baseline)
        val baseVal = evaluateSamples(valSet, keyboard, syllables, baseline)
        val baseTotalTop1 = baseTrain.first + baseVal.first
        val baseTotalTop3 = baseTrain.second + baseVal.second

        fun isBetter(a: Pair<Int, Int>, b: Pair<Int, Int>): Boolean =
            a.first > b.first || (a.first == b.first && a.second > b.second)

        var bestTrainParams = baseline
        var bestTrainScore = baseTrain

        val presets = listOf(
            SyllableGlideParams(),
            baseline.copy(hitWeight = 1.25f, segmentWeight = 1.65f, pauseBonus = 0.28f),
            baseline.copy(hitWeight = 1.75f, detourWeight = 2.6f, pauseBonus = 0.28f),
            baseline.copy(keyCost = 0.38f, repeatCost = 0.52f, pauseBonus = 0.25f),
            baseline.copy(keyCost = 0.52f, detourWeight = 3.4f, pauseBonus = 0.30f),
            baseline.copy(syllablePrior = 0.45f, pauseBonus = 0.30f),
            baseline.copy(syllablePrior = 0.78f, hitWeight = 1.4f, pauseBonus = 0.20f),
        )
        for (cand in presets) {
            if (cand == baseline) continue
            val score = evaluateSamples(trainSet, keyboard, syllables, cand)
            if (isBetter(score, bestTrainScore)) {
                bestTrainParams = cand
                bestTrainScore = score
            }
        }

        // 在训练集最优解附近做一轮局部坐标搜索（步长受安全范围约束）。
        val neighbors: List<(SyllableGlideParams) -> SyllableGlideParams> = listOf(
            { p -> p.copy(hitWeight = (p.hitWeight - 0.18f).coerceIn(0.95f, 2.20f)) },
            { p -> p.copy(hitWeight = (p.hitWeight + 0.18f).coerceIn(0.95f, 2.20f)) },
            { p -> p.copy(segmentWeight = (p.segmentWeight - 0.20f).coerceIn(1.10f, 2.60f)) },
            { p -> p.copy(segmentWeight = (p.segmentWeight + 0.20f).coerceIn(1.10f, 2.60f)) },
            { p -> p.copy(detourWeight = (p.detourWeight - 0.35f).coerceIn(1.80f, 4.20f)) },
            { p -> p.copy(detourWeight = (p.detourWeight + 0.35f).coerceIn(1.80f, 4.20f)) },
            { p -> p.copy(syllablePrior = (p.syllablePrior - 0.12f).coerceIn(0.25f, 1.00f)) },
            { p -> p.copy(syllablePrior = (p.syllablePrior + 0.12f).coerceIn(0.25f, 1.00f)) },
            { p -> p.copy(pauseBonus = (p.pauseBonus - 0.06f).coerceIn(0.08f, 0.42f)) },
            { p -> p.copy(pauseBonus = (p.pauseBonus + 0.06f).coerceIn(0.08f, 0.42f)) },
        )
        for (mutate in neighbors) {
            val cand = mutate(bestTrainParams)
            if (cand == bestTrainParams) continue
            val score = evaluateSamples(trainSet, keyboard, syllables, cand)
            if (isBetter(score, bestTrainScore)) {
                bestTrainParams = cand
                bestTrainScore = score
            }
        }

        if (bestTrainParams == baseline) {
            return TuneResult(targetSamples.size, baseTotalTop1, baseTotalTop1, baseTotalTop3, baseTotalTop3, updated = false)
        }
        val candVal = evaluateSamples(valSet, keyboard, syllables, bestTrainParams)
        val candTotalTop1 = bestTrainScore.first + candVal.first
        val candTotalTop3 = bestTrainScore.second + candVal.second
        val valNotWorse = candVal.first > baseVal.first ||
            (candVal.first == baseVal.first && candVal.second >= baseVal.second)
        val improved = valNotWorse &&
            (candTotalTop1 > baseTotalTop1 || (candTotalTop1 == baseTotalTop1 && candTotalTop3 > baseTotalTop3)) &&
            (candVal.first > baseVal.first || candVal.second > baseVal.second || bestTrainScore.first >= baseTrain.first + 2)
        if (improved) {
            currentParams = bestTrainParams
            saveParams(File(dir, PARAMS_FILE), bestTrainParams)
            paramsRevisionCounter.incrementAndGet()
            return TuneResult(targetSamples.size, baseTotalTop1, candTotalTop1, baseTotalTop3, candTotalTop3, updated = true)
        }
        return TuneResult(targetSamples.size, baseTotalTop1, baseTotalTop1, baseTotalTop3, baseTotalTop3, updated = false)
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
            val prevCount = old?.count ?: 0
            val alpha = adaptiveEmaAlpha(prevCount)
            val nextDx = if (old == null) (resX * alpha).coerceIn(-MAX_OFFSET_UNITS, MAX_OFFSET_UNITS)
            else (old.dx * (1f - alpha) + resX * alpha).coerceIn(-MAX_OFFSET_UNITS, MAX_OFFSET_UNITS)
            val nextDy = if (old == null) (resY * alpha).coerceIn(-MAX_OFFSET_UNITS, MAX_OFFSET_UNITS)
            else (old.dy * (1f - alpha) + resY * alpha).coerceIn(-MAX_OFFSET_UNITS, MAX_OFFSET_UNITS)
            bucketMap[ch] = KeyOffset(nextDx, nextDy, prevCount + 1)
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
        val denom = max(1, n - 1).toFloat()
        // 对第 0 键施加轻微起点贴合引导，避免偏离较大时起点飘到轨迹中段。
        for (j in 0 until n) {
            dp[0][j] = hypot(rx[j] - c0.first, ry[j] - c0.second) / keyboard.keyUnit + 0.08f * (j / denom)
        }

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
                    val endBias = if (i == m - 1) 0.08f * ((n - 1 - j) / denom) else 0f
                    dp[i][j] = bestPrevCost + stepDist + endBias
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
        if (!bestTotal.isFinite() || (bestTotal - 0.16f).coerceAtLeast(0f) / m > 1.35f) return null
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
        // 部分前缀已先选字时，上屏文本可能包含前面已选汉字而编码只剩尾段（例如 jkru → 下个版本加入）；
        // 任何拼音方案里每个汉字至少对应 1 个编码字母，编码长度短于汉字数时跳过记录。
        val hanziCount = text.count { it in '\u4E00'..'\u9FFF' }
        if (hanziCount > 0 && code.length < hanziCount) return
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
        // 同步在内存索引记录差异的局部音节/子串对，让整句纠错泛化到后续任意新句子。
        extractLocalConfusion(schemaKey, badCode, goodCode)?.let { (localBad, localGood) ->
            val lk = confusionKey(schemaKey, localBad, localGood)
            localConfusions[lk] = (localConfusions[lk] ?: 0) + 1
        }
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
        badCode: String = "",
        source: String = "confirm",
    ) {
        if (xs.size < 2 || ys.size != xs.size || '\t' in text || '\n' in text) return
        val unit = keyboard.keyUnit
        val minCenterX = keyboard.centers.values.minOfOrNull { it.first } ?: return
        val minCenterY = keyboard.centers.values.minOfOrNull { it.second } ?: return
        val cap = min(xs.size, 160)
        val (rx, ry) = if (xs.size <= cap) xs to ys else GlideDecoder.resampleTo(xs, ys, cap)
        val normXs = FloatArray(rx.size) { ((rx[it] - minCenterX) / unit * 100f).roundToInt() / 100f }
        val normYs = FloatArray(ry.size) { ((ry[it] - minCenterY) / unit * 100f).roundToInt() / 100f }
        val baseT = ts.firstOrNull() ?: 0L
        val normTs = LongArray(rx.size)
        if (ts.size == xs.size && rx.size == xs.size) {
            var prevT = 0L
            for (idx in ts.indices) {
                val cur = max(prevT, ts[idx] - baseT)
                normTs[idx] = cur
                prevT = cur
            }
        } else if (ts.size == xs.size && xs.size >= 2) {
            // 按原始弧长百分比插值真实时间戳，同时强制单调非减以屏蔽底层偶发负跳变。
            val cumDist = FloatArray(xs.size)
            for (k in 1 until xs.size) {
                val dx = xs[k] - xs[k - 1]
                val dy = ys[k] - ys[k - 1]
                cumDist[k] = cumDist[k - 1] + sqrt(dx * dx + dy * dy)
            }
            val totalLen = cumDist.last()
            var rawIdx = 0
            var prevT = 0L
            for (idx in rx.indices) {
                val rawT = if (totalLen <= 0f) {
                    val lastDt = (ts.last() - baseT).coerceAtLeast(1L)
                    lastDt * idx / max(1, rx.size - 1)
                } else {
                    val targetD = totalLen * idx / max(1, rx.size - 1)
                    while (rawIdx < cumDist.size - 2 && cumDist[rawIdx + 1] < targetD) rawIdx++
                    val segLen = cumDist[rawIdx + 1] - cumDist[rawIdx]
                    val frac = if (segLen > 1e-4f) ((targetD - cumDist[rawIdx]) / segLen).coerceIn(0f, 1f) else 0f
                    (ts[rawIdx] + (ts[rawIdx + 1] - ts[rawIdx]) * frac - baseT).toLong()
                }
                val monotonicT = max(prevT, rawT)
                normTs[idx] = monotonicT
                prevT = monotonicT
            }
        } else {
            for (idx in normTs.indices) normTs[idx] = idx * 12L
        }
        val cleanBad = badCode.replace("'", "").lowercase().filter { it in 'a'..'z' }
        val cleanSource = source.ifBlank { "confirm" }.replace('\t', '_').replace('\n', '_')
        if (samples.size >= MAX_SAMPLES) samples.removeAt(0)
        samples += SampleEntry(schemaKey, bucket, code, text, normXs, normYs, normTs, cleanBad, cleanSource)
        samplesSinceAutoTune++
        saveSamples(File(dir, SAMPLES_FILE))
    }

    fun shouldAutoTune(): Boolean = samplesSinceAutoTune >= 50
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
        var droppedDirty = false
        runCatching {
            file.forEachLine { line ->
                val p = line.split('\t')
                if (p.size < 5) return@forEachLine
                val freq = p[3].toIntOrNull() ?: return@forEachLine
                val ts = p[4].toLongOrNull() ?: 0L
                val hanziCount = p[2].count { it in '\u4E00'..'\u9FFF' }
                if (hanziCount > 0 && p[1].length < hanziCount) {
                    droppedDirty = true
                    return@forEachLine
                }
                phrases[phraseKey(p[0], p[1])] = PhraseEntry(p[0], p[1], p[2], freq, ts)
            }
        }
        if (droppedDirty) savePhrases(file)
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
                extractLocalConfusion(p[0], p[1], p[2])?.let { (lb, lg) ->
                    val lk = confusionKey(p[0], lb, lg)
                    localConfusions[lk] = (localConfusions[lk] ?: 0) + count
                }
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
                    val badCode = p.getOrNull(7).orEmpty()
                    val source = p.getOrNull(8)?.takeIf { it.isNotBlank() } ?: "confirm"
                    samples += SampleEntry(p[0], p[1], p[2], p[3], xs, ys, ts, badCode, source)
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
                        .append(s.normTs.joinToString(",")).append('\t')
                        .append(s.badCode).append('\t')
                        .append(s.source).append('\n')
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

    @Synchronized
    fun buildDiagnosticReport(dir: File): String {
        ensureLoaded(dir)
        val gStats = stats(dir)
        val aStats = com.kingzcheung.xime.association.PersonalAssociationBooster.stats(dir)
        val (assocBoosts, assocPenalties) = com.kingzcheung.xime.association.PersonalAssociationBooster.reportLines(dir, limit = 8)
        return buildString {
            appendLine("=== 滑行与联想本机自递进报告 ===")
            appendLine("滑行统计：已校准 ${gStats.calibratedKeys} 键 · 短语 ${gStats.phraseCount} 条 · 混淆 ${gStats.confusionCount} 组 · 轨迹样本 ${gStats.sampleCount} 条")
            appendLine("联想统计：正强化搭配 ${aStats.boostCount} 条 · 误选降权 ${aStats.penaltyCount} 条 · 累计事件 ${aStats.totalEvents} 次")
            appendLine()
            appendLine("【1. 指腹落点偏移（单位：键宽，按样本数排序）】")
            val flatOffsets = offsets.entries.flatMap { (bucket, map) ->
                map.entries.map { (ch, off) -> Triple(bucket, ch, off) }
            }.sortedByDescending { it.third.count }.take(12)
            if (flatOffsets.isEmpty()) {
                appendLine("暂无键位偏移记录（多确认几次滑行上屏后自动出现）")
            } else {
                flatOffsets.forEach { (bucket, ch, off) ->
                    val bName = if (bucket == "l") "横" else "竖"
                    val dirX = if (abs(off.dx) < 0.01f) "居中" else if (off.dx > 0f) "偏右${"%.2f".format(off.dx)}" else "偏左${"%.2f".format(-off.dx)}"
                    val dirY = if (abs(off.dy) < 0.01f) "居中" else if (off.dy > 0f) "偏下${"%.2f".format(off.dy)}" else "偏上${"%.2f".format(-off.dy)}"
                    appendLine("  [$bName] '$ch': $dirX, $dirY (${off.count}次)")
                }
            }
            appendLine()
            appendLine("【2. 滑行混淆纠正（坏码 → 真码）】")
            val topConf = confusions.entries.sortedByDescending { it.value }.take(8)
            if (topConf.isEmpty()) appendLine("  暂无记录")
            else topConf.forEach { (k, c) ->
                val parts = k.split('\t')
                if (parts.size >= 3) appendLine("  ${parts[1]} → ${parts[2]} (${c}次)")
            }
            appendLine()
            appendLine("【3. 个人高频整句/词组热库】")
            val topPhrases = phrases.values.sortedWith(compareByDescending<PhraseEntry> { it.freq }.thenByDescending { it.lastUsedMs }).take(8)
            if (topPhrases.isEmpty()) appendLine("  暂无记录")
            else topPhrases.forEach { p -> appendLine("  ${p.text} [${p.code}] (×${p.freq})") }
            appendLine()
            appendLine("【4. 联想自递进提权搭配】")
            if (assocBoosts.isEmpty()) appendLine("  暂无记录")
            else assocBoosts.forEach { appendLine("  $it") }
            if (assocPenalties.isNotEmpty()) {
                appendLine()
                appendLine("【5. 联想误选 / 局部改错降权】")
                assocPenalties.forEach { appendLine("  $it") }
            }
        }.trimEnd()
    }

    /**
     * 导出完整本机校准备份压缩包（.zip），包含全部 7 份滑行与联想原始配置/样本文件及清单。
     */
    @Synchronized
    fun exportFullBackupZip(dir: File, outputFile: File): ExportSummary {
        ensureLoaded(dir)
        com.kingzcheung.xime.association.PersonalAssociationBooster.ensureLoaded(dir)
        val rawNames = listOf(
            OFFSETS_FILE,
            PHRASES_FILE,
            CONFUSIONS_FILE,
            SAMPLES_FILE,
            PARAMS_FILE,
            "personal_assoc_boosts.tsv",
            "personal_assoc_penalties.tsv",
        )
        outputFile.parentFile?.mkdirs()
        val tmp = File(outputFile.parentFile, "${outputFile.name}.tmp")
        var fileCount = 0
        java.util.zip.ZipOutputStream(tmp.outputStream().buffered()).use { zip ->
            val manifest = buildDiagnosticReport(dir) + "\n"
            zip.putNextEntry(java.util.zip.ZipEntry("manifest_report.txt"))
            zip.write(manifest.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            for (name in rawNames) {
                val src = File(dir, name)
                if (!src.isFile || src.length() <= 0L) continue
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                src.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                fileCount++
            }
        }
        if (!tmp.renameTo(outputFile)) {
            outputFile.delete()
            tmp.renameTo(outputFile)
        }
        val totalRecords = offsets.values.sumOf { it.size } + phrases.size + confusions.size + samples.size +
            com.kingzcheung.xime.association.PersonalAssociationBooster.stats(dir).let { it.boostCount + it.penaltyCount }
        return ExportSummary(outputFile, totalRecords, fileCount)
    }

    /**
     * 导出严格脱敏的滑行轨迹训练集（.jsonl.gz）：剔除所有汉字原文、个人短语与联想记录，
     * 仅保留归一化点序 (xs/ys/ts)、方向桶及纠错编码信息 (bad_code -> good_code, source)。
     */
    @Synchronized
    fun exportSanitizedSamplesJsonlGz(dir: File, outputFile: File): ExportSummary {
        ensureLoaded(dir)
        outputFile.parentFile?.mkdirs()
        val tmp = File(outputFile.parentFile, "${outputFile.name}.tmp")
        var count = 0
        java.util.zip.GZIPOutputStream(tmp.outputStream().buffered()).bufferedWriter(Charsets.UTF_8).use { writer ->
            for (s in samples) {
                val cleanGood = s.code.replace("'", "").lowercase().filter { it in 'a'..'z' }
                if (cleanGood.length < 2 || s.normXs.size < 4 || s.normXs.size != s.normYs.size) continue
                val cleanBad = s.badCode.replace("'", "").lowercase().filter { it in 'a'..'z' }
                val line = buildString(256) {
                    append("{\"ver\":1,\"schema\":\"").append(escapeJson(s.schema))
                    append("\",\"bucket\":\"").append(escapeJson(s.bucket))
                    append("\",\"good_code\":\"").append(cleanGood)
                    if (cleanBad.isNotEmpty()) append("\",\"bad_code\":\"").append(cleanBad)
                    append("\",\"source\":\"").append(escapeJson(s.source))
                    append("\",\"xs\":[")
                    s.normXs.forEachIndexed { idx, v -> if (idx > 0) append(','); append(v) }
                    append("],\"ys\":[")
                    s.normYs.forEachIndexed { idx, v -> if (idx > 0) append(','); append(v) }
                    append("],\"ts\":[")
                    s.normTs.forEachIndexed { idx, v -> if (idx > 0) append(','); append(v) }
                    append("]}")
                }
                writer.write(line)
                writer.newLine()
                count++
            }
        }
        if (!tmp.renameTo(outputFile)) {
            outputFile.delete()
            tmp.renameTo(outputFile)
        }
        return ExportSummary(outputFile, count, if (count > 0) 1 else 0)
    }

    private fun escapeJson(value: String): String = buildString(value.length) {
        for (ch in value) when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(ch)
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
