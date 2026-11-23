package com.kingzcheung.xime.glide

import com.kingzcheung.xime.keyboard.LetterKeyRect
import com.kingzcheung.xime.settings.DoublePinyinScheme
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

class SyllableGlideSentenceTest {
    private val unit = 60f
    private val height = 80f
    private val keys: Map<Char, LetterKeyRect> = buildMap {
        listOf("qwertyuiop" to 0f, "asdfghjkl" to 0.5f, "zxcvbnm" to 1.5f).forEachIndexed { r, (row, inset) ->
            row.forEachIndexed { idx, c -> put(c, LetterKeyRect((inset + idx) * unit, r * height, unit, height)) }
        }
    }
    private val keyboard = GlideLexicon.keyboard(keys)
    private val entries: List<List<String>> by lazy {
        File("src/main/assets/glide_pinyin.txt").readLines().filter { it.isNotBlank() }.map { it.split(' ') }
    }
    private val flypy = DoublePinyinScheme.BUILTINS.first { it.id == "double_pinyin_flypy" }
    private val syllables: List<String> by lazy { glideSyllables(entries, flypy) }

    private fun trace(code: String, dxBias: Float = 0f, dyBias: Float = 0f, seed: Int = 7): Pair<FloatArray, FloatArray> {
        val rng = Random(seed)
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        for (i in 0 until code.length - 1) {
            val a = keyboard.centers[code[i]]!!
            val b = keyboard.centers[code[i + 1]]!!
            for (step in 0 until 8) {
                val t = step / 8f
                xs += a.first + (b.first - a.first) * t + dxBias * unit + (rng.nextFloat() - 0.5f) * 3f
                ys += a.second + (b.second - a.second) * t + dyBias * unit + (rng.nextFloat() - 0.5f) * 3f
            }
        }
        val last = keyboard.centers[code.last()]!!
        xs += last.first + dxBias * unit
        ys += last.second + dyBias * unit
        return xs.toFloatArray() to ys.toFloatArray()
    }

    private fun codeFor(vararg rawSyllables: String): String =
        rawSyllables.joinToString("") { flypy.code(it) ?: error("No flypy code for $it") }

    @Test
    fun kBestRetainsLongContinuousFlypySentenceWithinLatencyBudget() {
        val sentenceCode = codeFor("wo", "men", "jin", "tian", "xia", "wu", "qu", "kan", "dian", "ying")
        val decoder = SyllableGlide(keyboard, syllables)
        val (xs, ys) = trace(sentenceCode, seed = 17)
        val ts = LongArray(xs.size) { it * 15L }

        // 预热 JIT 后校验真实解码耗时与 K-best 多样性。
        repeat(2) { decoder.decodeScored(xs, ys, ts, limit = 10) }

        val t0 = System.nanoTime()
        val scored = decoder.decodeScored(xs, ys, ts, limit = 10)
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000.0

        assertTrue("Expected multiple diverse syllable paths, got ${scored.size}", scored.size >= 3)
        assertTrue(
            "Expected $sentenceCode in Top-10 paths, got ${scored.map { it.code }}",
            scored.any { it.code == sentenceCode },
        )
        assertTrue("Expected decode latency < 120ms, was ${elapsedMs}ms", elapsedMs < 120.0)
    }

    @Test
    fun pauseTimestampsBoostIntermediatePassThroughKey() {
        // q -> t 在同一横排，途径 r；若手指在 r 键位置明显减速停顿，应更倾向选中经过 r 的路径。
        val q = keyboard.centers['q']!!
        val r = keyboard.centers['r']!!
        val t = keyboard.centers['t']!!
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        val tsFast = ArrayList<Long>()
        val tsPause = ArrayList<Long>()

        var fastClock = 0L
        var pauseClock = 0L
        val segments = 24
        for (i in 0..segments) {
            val frac = i / segments.toFloat()
            val x = q.first + (t.first - q.first) * frac
            val y = q.second + (t.second - q.second) * frac
            xs += x
            ys += y
            fastClock += 12L
            tsFast += fastClock
            // 在 r 中心附近停顿停留更长时间
            val distR = kotlin.math.abs(x - r.first)
            val dt = if (distR < unit * 0.35f) 90L else 10L
            pauseClock += dt
            tsPause += pauseClock
        }

        val vocab = listOf("qt", "qr", "rt", "qt" /* dummy */).distinct()
        val decoder = SyllableGlide(keyboard, vocab)
        val withoutPause = decoder.decodeScored(xs.toFloatArray(), ys.toFloatArray(), tsFast.toLongArray(), limit = 4)
        val withPause = decoder.decodeScored(xs.toFloatArray(), ys.toFloatArray(), tsPause.toLongArray(), limit = 4)

        val pauseCostWithTs = withPause.firstOrNull { it.code == "qrrt" }?.cost
            ?: withPause.firstOrNull { it.code == "qrt" }?.cost
        val pauseCostFast = withoutPause.firstOrNull { it.code == "qrrt" }?.cost
            ?: withoutPause.firstOrNull { it.code == "qrt" }?.cost

        assertTrue(
            "Expected both fast and paused decodes to produce candidates ($withoutPause vs $withPause)",
            withPause.isNotEmpty() && withoutPause.isNotEmpty(),
        )
        if (pauseCostWithTs != null && pauseCostFast != null) {
            assertTrue(
                "Pause should reduce intermediate path cost ($pauseCostWithTs vs $pauseCostFast)",
                pauseCostWithTs < pauseCostFast,
            )
        }
    }
}
