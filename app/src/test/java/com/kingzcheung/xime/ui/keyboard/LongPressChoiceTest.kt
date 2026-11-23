package com.kingzcheung.xime.ui.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class LongPressChoiceTest {
    private fun route(label: String, accentLetters: String = ""): Pair<String, String> {
        var path = ""
        var value = ""
        commitLongPressChoice(label, { path = "commit"; value = it }, { path = "key"; value = it }, accentLetters)
        return path to value
    }

    @Test
    fun lowercaseLetterJoinsPinyinLikeATap() {
        // h + 长按 g 选 g：应接到拼音里，不能先上屏首选「和」再上屏 g。
        assertEquals("key" to "g", route("g"))
    }

    @Test
    fun otherChoicesStillCommitDirectly() {
        assertEquals("commit" to "G", route("G"))
        assertEquals("commit" to "è", route("è"))
        assertEquals("commit" to "3", route("3"))
    }

    @Test
    fun accentLettersJoinTheWordBeingTyped() {
        assertEquals("key" to "á", route("á", "áéíóúñ"))
        assertEquals("key" to "ñ", route("ñ", "áéíóúñ"))
        assertEquals("commit" to "¿", route("¿", "áéíóúñ"))
        assertEquals("commit" to "Á", route("Á", "áéíóúñ"))
        // 其他键盘照旧直接上屏。
        assertEquals("commit" to "á", route("á"))
    }

    @Test
    fun accentBubbleFollowsShift() {
        val accents = "èéòàùÈÉ"
        // Shift 后气泡显示大写，和键帽一致；没 Shift 照旧小写。
        assertEquals(listOf("È", "É"), listOf("è", "é").map { longPressShownLabel(it, true, accents) })
        assertEquals("è", longPressShownLabel("è", false, accents))
        // 标点、别的字母都不变。
        assertEquals("«", longPressShownLabel("«", true, accents))
        assertEquals("ñ", longPressShownLabel("ñ", true, accents))
        // 英文键盘（没有本语言字母）不改。
        assertEquals("g", longPressShownLabel("g", true, ""))
    }
}
