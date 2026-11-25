package com.kingzcheung.xime.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartPunctuationTest {

    @Test fun detectsDigitAndEnglishContext() {
        assertTrue(SmartPunctuation.isAfterDigitOrEnglish("price 12"))
        assertTrue(SmartPunctuation.isAfterDigitOrEnglish("hello"))
        assertTrue(SmartPunctuation.isAfterDigitOrEnglish("ABC"))
        assertFalse(SmartPunctuation.isAfterDigitOrEnglish("你好"))
        assertFalse(SmartPunctuation.isAfterDigitOrEnglish("12 "))
        assertFalse(SmartPunctuation.isAfterDigitOrEnglish(""))
        assertFalse(SmartPunctuation.isAfterDigitOrEnglish(null))
    }

    @Test fun convertsFullWidthPunctuationToHalfWidth() {
        assertEquals(".", SmartPunctuation.toHalfWidth("。"))
        assertEquals(",", SmartPunctuation.toHalfWidth("，"))
        assertEquals("!", SmartPunctuation.toHalfWidth("！"))
        assertEquals("?", SmartPunctuation.toHalfWidth("？"))
        assertEquals(":", SmartPunctuation.toHalfWidth("："))
        assertEquals(";", SmartPunctuation.toHalfWidth("；"))
        assertEquals("(", SmartPunctuation.toHalfWidth("（"))
        assertEquals(")", SmartPunctuation.toHalfWidth("）"))
        assertEquals("\"", SmartPunctuation.toHalfWidth("“"))
        assertEquals("'", SmartPunctuation.toHalfWidth("’"))
        assertEquals("...", SmartPunctuation.toHalfWidth("……"))
        assertEquals("--", SmartPunctuation.toHalfWidth("——"))
        assertEquals("()", SmartPunctuation.toHalfWidth("（）"))
        assertEquals("中文", SmartPunctuation.toHalfWidth("中文"))
        assertEquals("a", SmartPunctuation.toHalfWidth("a"))
        assertEquals(".", SmartPunctuation.toHalfWidth("."))
    }
}
