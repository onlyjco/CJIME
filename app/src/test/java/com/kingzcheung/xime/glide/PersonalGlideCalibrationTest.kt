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
}
