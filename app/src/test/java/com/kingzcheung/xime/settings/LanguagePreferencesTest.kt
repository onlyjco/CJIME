package com.kingzcheung.xime.settings

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class LanguagePreferencesTest {
    @Before fun registerCantonese() {
        InputProfiles.updateInstalled(listOf(SchemaMeta("jyut6ping3", "粤拼", language = InputLanguage.CANTONESE)))
    }
    @After fun clearInstalled() {
        InputProfiles.updateInstalled(emptyList())
    }
    @Test fun disabledChineseChoosesEnabledCantoneseNativeSchema() {
        assertEquals("jyut6ping3", LanguagePreferences.nativeSchema(
            "rime_ice", listOf("rime_ice", "jyut6ping3"),
            setOf(InputLanguage.CANTONESE, InputLanguage.ENGLISH)))
        assertEquals("jyut6ping3", LanguagePreferences.nativeSchema(
            "jyut6ping3", listOf("rime_ice", "jyut6ping3"),
            setOf(InputLanguage.CANTONESE, InputLanguage.ENGLISH)))
    }

    @Test fun enablingACantoneseSchemaEnablesCantonese() {
        val fresh = setOf(InputLanguage.CHINESE, InputLanguage.ENGLISH)
        assertEquals(fresh + InputLanguage.CANTONESE, LanguagePreferences.withSchemaLanguage(fresh, "jyut6ping3"))
        // A Chinese schema or an already enabled language changes nothing.
        assertEquals(fresh, LanguagePreferences.withSchemaLanguage(fresh, "pinyin_14jian"))
    }

    @Test fun englishOnlyKeepsRealNativeFallback() {
        assertEquals("rime_ice", LanguagePreferences.nativeSchema(
            InputModes.ENGLISH, listOf("rime_ice", "jyut6ping3"), setOf(InputLanguage.ENGLISH)))
        assertEquals("rime_ice", LanguagePreferences.nativeSchema(
            "rime_ice", emptyList(), setOf(InputLanguage.ENGLISH)))
    }
}
