package com.kingzcheung.xime.glide

import com.kingzcheung.xime.keyboard.LetterKeyRect
import com.kingzcheung.xime.settings.DoublePinyinScheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PersonalGlideCalibrationTest {
    private val unit = 60f
    private val height = 80f
    private val keys: Map<Char, LetterKeyRect> = buildMap {
        listOf("qwertyuiop" to 0f, "asdfghjkl" to 0.5f, "zxcvbnm" to 1.5f).forEachIndexed { r, (row, inset) ->
            row.forEachIndexed { idx, c -> put(c, LetterKeyRect((inset + idx) * unit, r * height, unit, height)) }
        }
    }
    private val keyboard = GlideLexicon.keyboard(keys)
    private val flypy = DoublePinyinScheme.BUILTINS.first { it.id == "double_pinyin_flypy" }
    private val entries: List<List<String>> by lazy {
        File("src/main/assets/glide_pinyin.txt").readLines().filter { it.isNotBlank() }.map { it.split(' ') }
    }
    private val syllables: List<String> by lazy { glideSyllables(entries, flypy) }
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("personal_glide_test_").toFile()
        PersonalGlideCalibration.clear(tempDir)
    }

    @After
    fun tearDown() {
        PersonalGlideCalibration.clear(tempDir)
        tempDir.deleteRecursively()
    }

    private fun trace(code: String, dxBiasUnits: Float = 0f, dyBiasUnits: Float = 0f): Triple<FloatArray, FloatArray, LongArray> {
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        for (i in 0 until code.length - 1) {
            val a = keyboard.centers[code[i]]!!
            val b = keyboard.centers[code[i + 1]]!!
            for (step in 0 until 8) {
                val t = step / 8f
                xs += a.first + (b.first - a.first) * t + dxBiasUnits * unit
                ys += a.second + (b.second - a.second) * t + dyBiasUnits * unit
            }
        }
        val last = keyboard.centers[code.last()]!!
        xs += last.first + dxBiasUnits * unit
        ys += last.second + dyBiasUnits * unit
        val ts = LongArray(xs.size) { it * 16L }
        return Triple(xs.toFloatArray(), ys.toFloatArray(), ts)
    }

    @Test
    fun emaOffsetsShiftCentersTowardConsistentUserBias() {
        val code = "wmjbtxwqkdmy"
        val (xs, ys, ts) = trace(code, dxBiasUnits = 0.34f, dyBiasUnits = 0.22f)
        repeat(10) { idx ->
            val session = idx.toLong() + 1L
            PersonalGlideCalibration.noteDispatched(session, flypy.id, "p", keyboard, xs, ys, ts, code)
            PersonalGlideCalibration.onCommitted(tempDir, session, flypy.id, code, "我们今天去看电影")
        }

        val calibrated = PersonalGlideCalibration.applyOffsets(tempDir, "p", keyboard)
        val wBefore = keyboard.centers['w']!!
        val wAfter = calibrated.centers['w']!!
        assertTrue(
            "Expected x center of 'w' to shift right (before=${wBefore.first}, after=${wAfter.first})",
            wAfter.first > wBefore.first + unit * 0.05f,
        )
        assertTrue(
            "Expected y center of 'w' to shift down (before=${wBefore.second}, after=${wAfter.second})",
            wAfter.second > wBefore.second + unit * 0.03f,
        )

        val stats = PersonalGlideCalibration.stats(tempDir)
        assertTrue(stats.calibratedKeys >= 6)
        assertTrue(stats.phraseCount >= 1)
        assertEquals(10, stats.sampleCount)
    }

    @Test
    fun alternativeAndDeleteRetypeFeedbackAdjustsCandidateCostDelta() {
        val badCode = "nhvj"
        val goodCode = "nhuj"
        val (xs, ys, ts) = trace(goodCode)

        // 1. 候选栏改选备选
        PersonalGlideCalibration.noteDispatched(1L, flypy.id, "p", keyboard, xs, ys, ts, badCode)
        PersonalGlideCalibration.onAlternativeChosen(tempDir, 1L, flypy.id, goodCode, "你好世界")

        // 2. 回删后重打上屏
        PersonalGlideCalibration.noteDispatched(2L, flypy.id, "p", keyboard, xs, ys, ts, badCode)
        PersonalGlideCalibration.noteGlideDeleted(2L)
        PersonalGlideCalibration.noteManualTypingAfterGlide(2L)
        PersonalGlideCalibration.onCommitted(tempDir, 2L, flypy.id, goodCode, "你好世界")

        val pool = listOf(badCode, goodCode)
        val deltaGood = PersonalGlideCalibration.candidateCostDelta(tempDir, flypy.id, goodCode, pool)
        val deltaBad = PersonalGlideCalibration.candidateCostDelta(tempDir, flypy.id, badCode, pool)

        assertTrue("Expected chosen code delta < 0, was $deltaGood", deltaGood < 0f)
        assertTrue("Expected rejected code delta > 0, was $deltaBad", deltaBad > 0f)
        assertTrue(goodCode in PersonalGlideCalibration.hotPhraseCodes(tempDir, flypy.id))
        assertEquals("你好世界", PersonalGlideCalibration.phraseTextOf(tempDir, flypy.id, goodCode))
    }

    @Test
    fun tuneFromHistoryRunsSafelyWithEnoughSamplesAndClearResetsState() {
        val sampleCodes = listOf(
            "wmjb", "wmtx", "xwqq", "kdmy", "nhuj", "djhh", "mttq",
            "ggww", "jbhb", "vjgf", "zztb", "xpxx", "yyll", "kkxd",
        )
        sampleCodes.forEachIndexed { idx, code ->
            val (xs, ys, ts) = trace(code)
            val session = 100L + idx
            PersonalGlideCalibration.noteDispatched(session, flypy.id, "p", keyboard, xs, ys, ts, code)
            PersonalGlideCalibration.onCommitted(tempDir, session, flypy.id, code, "样本$idx")
        }

        val result = PersonalGlideCalibration.tuneFromHistory(tempDir, flypy.id, keyboard, syllables)
        assertEquals(sampleCodes.size, result.sampleCount)
        assertTrue(result.afterTop1 >= result.beforeTop1)

        PersonalGlideCalibration.clear(tempDir)
        val cleared = PersonalGlideCalibration.stats(tempDir)
        assertEquals(0, cleared.calibratedKeys)
        assertEquals(0, cleared.phraseCount)
        assertEquals(0, cleared.sampleCount)
        assertEquals(0, cleared.confusionCount)
        assertFalse(File(tempDir, "personal_glide_offsets.tsv").exists())
    }

    @Test
    fun severeDeviationDeleteAndManualRetypeStillCalibratesOriginalTrace() {
        val trueCode = "wmjbtxwqkdmy"
        val badDecode = "wmjbtk" // 严重偏离且长度差达 6 个键（> 3）
        val (xs, ys, ts) = trace(trueCode, dxBiasUnits = 0.30f, dyBiasUnits = 0.0f)

        PersonalGlideCalibration.noteDispatched(55L, flypy.id, "p", keyboard, xs, ys, ts, badDecode)
        PersonalGlideCalibration.noteGlideDeleted(55L)
        PersonalGlideCalibration.noteManualTypingAfterGlide(55L)
        PersonalGlideCalibration.onCommitted(tempDir, 55L, flypy.id, trueCode, "我们今天下午去看电影")

        val stats = PersonalGlideCalibration.stats(tempDir)
        assertTrue("Expected severe-deviation delete+retype to calibrate keys, got ${stats.calibratedKeys}",
            stats.calibratedKeys >= 6)
        assertEquals(1, stats.confusionCount)
        assertEquals(1, stats.sampleCount)
    }

    @Test
    fun sentenceEditFeedbackUpdatesCalibrationAndDiagnosticReportRenders() {
        val initialCode = "wmjbtxwqkdmi"
        val (xs, ys, ts) = trace("wmjbtxwqkdmy")
        PersonalGlideCalibration.noteDispatched(77L, flypy.id, "p", keyboard, xs, ys, ts, initialCode)
        PersonalGlideCalibration.onSentenceEditApplied(
            dir = tempDir,
            sessionId = 77L,
            schemaKey = flypy.id,
            sentenceBefore = "我们今天下午去看电迷",
            badPartCode = "dmi",
            goodPartCode = "dmy",
            sentenceAfter = "我们今天下午去看电影",
        )
        com.kingzcheung.xime.association.PersonalAssociationBooster.recordContinuation(tempDir, "去看", "电影")

        val report = PersonalGlideCalibration.buildDiagnosticReport(tempDir)
        assertTrue(report.contains("滑行与联想本机自递进报告"))
        assertTrue(report.contains("wmjbtxwqkdmi → wmjbtxwqkdmy"))
        assertTrue(report.contains("我们今天下午去看电影"))
        assertTrue(report.contains("去看 → 电影"))
    }

    @Test
    fun exportFullBackupZipAndSanitizedSamplesJsonlGzPreserveTimingWithoutLeakingText() {
        val code = "wmjbtxwqkdmy"
        val (baseXs, baseYs, _) = trace(code)
        // 构造超过 160 点的长句轨迹，并在中段注入明显减速停顿，验证重采样后时间节奏未丢失。
        val totalPts = 210
        val xs = FloatArray(totalPts)
        val ys = FloatArray(totalPts)
        val ts = LongArray(totalPts)
        var t = 0L
        for (i in 0 until totalPts) {
            val pos = i * (baseXs.size - 1).toFloat() / (totalPts - 1)
            val lo = pos.toInt().coerceAtMost(baseXs.size - 2)
            val frac = pos - lo
            xs[i] = baseXs[lo] + (baseXs[lo + 1] - baseXs[lo]) * frac
            ys[i] = baseYs[lo] + (baseYs[lo + 1] - baseYs[lo]) * frac
            if (i > 0) {
                t += if (i in 95..115) 95L else 10L
            }
            ts[i] = t
        }

        PersonalGlideCalibration.noteDispatched(88L, flypy.id, "p", keyboard, xs, ys, ts, "wmjbtk")
        PersonalGlideCalibration.noteGlideDeleted(88L)
        PersonalGlideCalibration.noteManualTypingAfterGlide(88L)
        PersonalGlideCalibration.onCommitted(tempDir, 88L, flypy.id, code, "我们今天下午去看电影")
        com.kingzcheung.xime.association.PersonalAssociationBooster.recordContinuation(tempDir, "去看", "电影")

        val gzFile = File(tempDir, "sanitized.jsonl.gz")
        val gzSummary = PersonalGlideCalibration.exportSanitizedSamplesJsonlGz(tempDir, gzFile)
        assertEquals(1, gzSummary.recordCount)
        assertTrue(gzFile.exists() && gzFile.length() > 0L)

        val jsonLines = java.util.zip.GZIPInputStream(gzFile.inputStream()).bufferedReader(Charsets.UTF_8).readLines()
        assertEquals(1, jsonLines.size)
        val line = jsonLines.first()
        assertTrue(line.contains("\"good_code\":\"$code\""))
        assertTrue(line.contains("\"bad_code\":\"wmjbtk\""))
        assertTrue(line.contains("\"source\":\"delete_retype\""))
        assertFalse("Sanitized export must never contain Chinese text", line.contains("电影") || line.contains("我们"))

        // 解析 exported ts，核对停顿区与快速区时间步长差异被真实保留（未被均值线性抹平）。
        val tsMatch = Regex("\"ts\":\\[([^\\]]+)]").find(line)!!
        val exportedTs = tsMatch.groupValues[1].split(',').map { it.toLong() }
        assertEquals(160, exportedTs.size)
        val stepGaps = exportedTs.zipWithNext { a, b -> b - a }
        val minGap = stepGaps.min()
        val maxGap = stepGaps.max()
        assertTrue("Expected pause timing variation preserved (max=$maxGap, min=$minGap)", maxGap > minGap * 3)

        val zipFile = File(tempDir, "backup.zip")
        val zipSummary = PersonalGlideCalibration.exportFullBackupZip(tempDir, zipFile)
        assertTrue(zipSummary.fileCount >= 4)
        val entryNames = buildSet {
            java.util.zip.ZipInputStream(zipFile.inputStream()).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    add(entry.name)
                }
            }
        }
        assertTrue("manifest_report.txt" in entryNames)
        assertTrue("personal_glide_samples.tsv" in entryNames)
        assertTrue("personal_glide_confusions.tsv" in entryNames)
        assertTrue("personal_assoc_boosts.tsv" in entryNames)
    }

    @Test
    fun tailStrokeMismatchAndHugeLengthGapConfusionAreHandledCleanly() {
        // 1. 连滑多段后一次性上屏：末笔 xigt 不该把整句“输入习惯”当成自己对应文字。
        val (xsTail, ysTail, tsTail) = trace("xigt")
        PersonalGlideCalibration.noteDispatched(200L, flypy.id, "p", keyboard, xsTail, ysTail, tsTail, "xigt")
        PersonalGlideCalibration.onCommitted(tempDir, 200L, flypy.id, "uuru'xigt", "输入习惯")
        assertEquals("习惯", PersonalGlideCalibration.phraseTextOf(tempDir, flypy.id, "xigt"))
        assertEquals("输入习惯", PersonalGlideCalibration.phraseTextOf(tempDir, flypy.id, "uuruxigt"))

        // 2. 删除 2 键短码后新滑 12 键整句：绝不生成“2键 → 12键”虚假混淆对。
        val (xs2, ys2, ts2) = trace("yl")
        PersonalGlideCalibration.noteDispatched(201L, flypy.id, "p", keyboard, xs2, ys2, ts2, "yl")
        PersonalGlideCalibration.noteGlideDeleted(201L)
        PersonalGlideCalibration.onCommitted(tempDir, 201L, flypy.id, "wmjbtxwqkdmy", "我们今天下午去看电影")
        assertEquals(0, PersonalGlideCalibration.stats(tempDir).confusionCount)

        // 3. 连续滑出两句再先后上屏时，两条轨迹样本都应被保留（不被第二句覆盖丢弃）。
        PersonalGlideCalibration.clear(tempDir)
        val (xsA, ysA, tsA) = trace("vegewuju")
        val (xsB, ysB, tsB) = trace("yswkti")
        PersonalGlideCalibration.noteDispatched(202L, flypy.id, "p", keyboard, xsA, ysA, tsA, "vegewuju")
        PersonalGlideCalibration.noteDispatched(202L, flypy.id, "p", keyboard, xsB, ysB, tsB, "yswkti")
        PersonalGlideCalibration.onCommitted(tempDir, 202L, flypy.id, "vegewuju", "这个数据")
        PersonalGlideCalibration.onCommitted(tempDir, 202L, flypy.id, "yswkti", "有问题")
        assertEquals(2, PersonalGlideCalibration.stats(tempDir).sampleCount)
    }
}
