package com.kingzcheung.xime.handwriting

import com.kingzcheung.xime.settings.InputLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandwritingTemplatesTest {

    @Test fun eachScriptOnlyLearnsItsOwnCharacters() {
        val latin = HandwritingTemplates.Script.LATIN
        assertTrue(latin.accepts("a"))
        assertTrue(latin.accepts("Z"))
        assertTrue(latin.accepts("7"))
        assertFalse(latin.accepts("α"))
        assertFalse(latin.accepts("ab"))
    }

    @Test fun languagesMapToScripts() {
        assertEquals(HandwritingTemplates.Script.LATIN, HandwritingTemplates.Script.forLanguage(InputLanguage.ENGLISH))
        assertEquals(null, HandwritingTemplates.Script.forLanguage(InputLanguage.CHINESE))
    }
}
