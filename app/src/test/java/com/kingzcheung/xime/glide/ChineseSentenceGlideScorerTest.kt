package com.kingzcheung.xime.glide

import com.kingzcheung.xime.speech.WordLanguageModel
import org.junit.Assert.assertEquals
import org.junit.Test

class ChineseSentenceGlideScorerTest {

    private class FakeWordLm(
        private val words: Map<String, Int>,
        private val unigrams: Map<Int, Double>,
        private val bigrams: Map<Pair<Int, Int>, Double> = emptyMap(),
        private val trigrams: Map<Triple<Int, Int, Int>, Double> = emptyMap(),
    ) : WordLanguageModel {
        override fun id(word: String): Int = words[word] ?: -1

        override fun unigram(id: Int): Double = unigrams[id] ?: -14.0

        override fun bigram(h1: Int, w: Int): Double = bigrams[h1 to w] ?: unigram(w)

        override fun trigram(h2: Int, h1: Int, w: Int): Double =
            trigrams[Triple(h2, h1, w)] ?: bigram(h1, w)
    }

    @Test
    fun rerankPromotesFluentSentenceAndDeduplicatesAlternatives() {
        val lm = FakeWordLm(
            words = mapOf(
                "我们" to 1,
                "今天" to 2,
                "去看" to 3,
                "电影" to 4,
                "卧" to 5,
                "门" to 6,
                "金" to 7,
                "添" to 8,
            ),
            unigrams = mapOf(
                1 to -4.2, 2 to -4.5, 3 to -5.1, 4 to -4.8,
                5 to -9.5, 6 to -9.2, 7 to -9.4, 8 to -9.8,
            ),
            trigrams = mapOf(
                Triple(-1, 1, 2) to -1.2,
                Triple(1, 2, 3) to -1.1,
                Triple(2, 3, 4) to -1.0,
            ),
        )

        val candidates = listOf(
            GlideCandidate(word = "wmjbtkdm", cost = 1.10f, listed = false),
            GlideCandidate(word = "wmjbtxwqkdmy", cost = 1.28f, listed = false),
            GlideCandidate(word = "wmjbtxwqqkdmy", cost = 1.35f, listed = false),
            GlideCandidate(word = "wo", cost = 1.90f, listed = true),
        )

        val previews = mapOf(
            "wmjbtkdm" to ChineseSentenceGlideScorer.SentencePreview("卧门金添"),
            "wmjbtxwqkdmy" to ChineseSentenceGlideScorer.SentencePreview("我们今天去看电影"),
            "wmjbtxwqqkdmy" to ChineseSentenceGlideScorer.SentencePreview("我们今天去看电影"),
            "wo" to ChineseSentenceGlideScorer.SentencePreview("我"),
        )

        val result = ChineseSentenceGlideScorer.rerank(
            candidates = candidates,
            leftContext = null,
            lm = lm,
        ) { code -> previews[code] }

        assertEquals("wmjbtxwqkdmy", result.winnerCode)
        assertEquals("我们今天去看电影", result.winnerText)
        // 第二条与胜出编码映射为相同汉字整句的 wmjbtxwqqkdmy 应被去重过滤，避免候选栏出现重复句。
        assertEquals(listOf("wmjbtkdm", "wo"), result.alternativeCodes)
    }

    @Test
    fun rerankPenalizesUnresolvedAsciiOutputs() {
        val lm = FakeWordLm(
            words = mapOf("你好" to 1, "世界" to 2),
            unigrams = mapOf(1 to -4.0, 2 to -4.2),
            trigrams = mapOf(Triple(-1, 1, 2) to -1.5),
        )
        val candidates = listOf(
            GlideCandidate("nhvj", cost = 0.80f, listed = false),
            GlideCandidate("nhuj", cost = 1.05f, listed = false),
        )
        val result = ChineseSentenceGlideScorer.rerank(candidates, null, lm) { code ->
            when (code) {
                "nhvj" -> ChineseSentenceGlideScorer.SentencePreview("nhvj")
                "nhuj" -> ChineseSentenceGlideScorer.SentencePreview("你好世界")
                else -> null
            }
        }
        assertEquals("nhuj", result.winnerCode)
        assertEquals("你好世界", result.winnerText)
    }
}
