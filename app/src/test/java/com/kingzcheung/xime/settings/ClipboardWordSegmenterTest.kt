package com.kingzcheung.xime.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class ClipboardWordSegmenterTest {
    @Test fun splitsChineseEnglishAndDigitsCharacterByCharacter() {
        val text = "今天abc12"
        val tokens = ClipboardWordSegmenter.split(text)
        assertEquals(listOf("今", "天", "a", "b", "c", "1", "2"), tokens)
        assertEquals(text, tokens.joinToString(""))
    }

    @Test fun preservesSpacesNewlinesCrlfAndSurrogatePairs() {
        val text = "你好 \n\r\n😀𠀀！"
        val tokens = ClipboardWordSegmenter.split(text)
        assertEquals(listOf("你", "好", " ", "\n", "\r\n", "😀", "𠀀", "！"), tokens)
        assertEquals(text, tokens.joinToString(""))
    }

    @Test fun emptyInputReturnsEmptyList() {
        assertEquals(emptyList<String>(), ClipboardWordSegmenter.split(""))
    }
}
