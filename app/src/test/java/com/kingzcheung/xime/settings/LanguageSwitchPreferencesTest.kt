package com.kingzcheung.xime.settings

import org.junit.Assert.*
import org.junit.Test

class LanguageSwitchPreferencesTest {
    private val zh = InputLanguage.CHINESE
    private val en = InputLanguage.ENGLISH
    private val yue = InputLanguage.CANTONESE
    private val all = linkedSetOf(zh, yue, en)

    @Test fun defaultKeepsCurrentNativeLanguageAndEnglishRegardlessOfLanguageOrder() {
        val options = LanguageSwitchOptions()
        assertEquals(en, LanguageSwitchPreferences.target(zh, zh, options, listOf(yue, zh, en), all))
        assertEquals(zh, LanguageSwitchPreferences.target(en, zh, options, listOf(yue, zh, en), all))
        assertEquals(en, LanguageSwitchPreferences.target(yue, yue, options, listOf(zh, en, yue), all))
        assertEquals(yue, LanguageSwitchPreferences.target(en, yue, options, listOf(zh, en, yue), all))
    }

    @Test fun defaultDoesNotEnableADisabledNativeLanguage() {
        assertNull(LanguageSwitchPreferences.target(en, zh, LanguageSwitchOptions(), listOf(en), setOf(en)))
    }

    @Test fun cycleUsesTheSavedLanguageOrderAndWraps() {
        val options = LanguageSwitchOptions(LanguageSwitchMode.CYCLE)
        val order = listOf(en, yue, zh)
        assertEquals(yue, LanguageSwitchPreferences.target(en, zh, options, order, all))
        assertEquals(zh, LanguageSwitchPreferences.target(yue, yue, options, order, all))
        assertEquals(en, LanguageSwitchPreferences.target(zh, zh, options, order, all))
    }

    @Test fun cycleSkipsDisabledOrUnavailableLanguagesAndDuplicateEntries() {
        val options = LanguageSwitchOptions(LanguageSwitchMode.CYCLE)
        assertEquals(en, LanguageSwitchPreferences.target(zh, zh, options, listOf(zh, yue, yue, en), setOf(zh, en)))
        assertNull(LanguageSwitchPreferences.target(en, zh, options, listOf(en), setOf(en)))
    }

    @Test fun specificChineseAndEnglishDoesNotDependOnTheEngineFallback() {
        val options = LanguageSwitchOptions(LanguageSwitchMode.SPECIFIC_ENGLISH, zh)
        assertEquals(zh, LanguageSwitchPreferences.target(en, yue, options, all.toList(), all))
        assertEquals(en, LanguageSwitchPreferences.target(zh, zh, options, all.toList(), all))
        assertEquals(zh, LanguageSwitchPreferences.target(yue, yue, options, all.toList(), all))
    }

    @Test fun specificCantoneseAndEnglishRetainsAnExplicitChoice() {
        val options = LanguageSwitchOptions(LanguageSwitchMode.SPECIFIC_ENGLISH, yue)
        assertEquals(yue, LanguageSwitchPreferences.target(zh, zh, options, all.toList(), all))
        assertEquals(en, LanguageSwitchPreferences.target(yue, yue, options, all.toList(), all))
        assertEquals(yue, LanguageSwitchPreferences.target(en, zh, options, all.toList(), all))
    }

    @Test fun invalidSpecificChoiceFallsBackToAnAvailableNativeLanguage() {
        val options = LanguageSwitchOptions(LanguageSwitchMode.SPECIFIC_ENGLISH, yue)
        assertEquals(zh, LanguageSwitchPreferences.target(en, zh, options, listOf(en, zh), setOf(en, zh)))
        assertNull(LanguageSwitchPreferences.target(en, zh, options, listOf(en), setOf(en)))
    }

    @Test fun specificChoicesListEveryNonEnglishLanguageNotOnlyChinese() {
        val choices = LanguageSwitchPreferences.specificChoices(listOf(zh, en))
        assertFalse(en in choices)
        assertEquals(zh, choices.first())
        assertEquals(InputLanguage.supported.filterNot { it == en }.toSet(), choices.toSet())
        assertTrue(yue in choices)
    }

    @Test fun specificPairSwitchesBetweenTwoLanguagesWithoutEnglish() {
        val options = LanguageSwitchOptions(LanguageSwitchMode.SPECIFIC_PAIR, zh, yue)
        val available = setOf(zh, en, yue)
        val order = listOf(zh, en, yue)
        assertEquals(yue, LanguageSwitchPreferences.target(zh, zh, options, order, available))
        assertEquals(zh, LanguageSwitchPreferences.target(yue, yue, options, order, available))
        // 在英文里按语言键：回到第一种语言。
        assertEquals(zh, LanguageSwitchPreferences.target(en, zh, options, order, available))
    }

    @Test fun specificPairFallsBackToEnglishWhenThePartnerIsMissing() {
        // 没选另一种、另一种没开、或和第一种相同：照「指定语言与英文切换」。
        for (partner in listOf(null, yue, zh)) {
            val options = LanguageSwitchOptions(LanguageSwitchMode.SPECIFIC_PAIR, zh, partner)
            assertEquals("$partner", en, LanguageSwitchPreferences.target(zh, zh, options, listOf(zh, en), setOf(zh, en)))
            assertEquals("$partner", zh, LanguageSwitchPreferences.target(en, zh, options, listOf(zh, en), setOf(zh, en)))
        }
        assertNull(LanguageSwitchPreferences.target(en, zh, LanguageSwitchOptions(LanguageSwitchMode.SPECIFIC_PAIR, zh, yue),
            listOf(en), setOf(en)))
    }

    @Test fun pairChoiceKeepsTheOldStoredModes() {
        // 「当前语言 ↔ 英文」就是原来的默认，存的值不变。
        assertEquals(LanguageSwitchOptions(), LanguageSwitchPreferences.pair(null, en))
        assertEquals(LanguageSwitchOptions(), LanguageSwitchPreferences.pair(null, yue))
        assertEquals(LanguageSwitchOptions(LanguageSwitchMode.SPECIFIC_ENGLISH, yue), LanguageSwitchPreferences.pair(yue, en))
        assertEquals(LanguageSwitchOptions(LanguageSwitchMode.SPECIFIC_ENGLISH, yue), LanguageSwitchPreferences.pair(yue, yue))
        assertEquals(LanguageSwitchOptions(LanguageSwitchMode.SPECIFIC_PAIR, zh, yue), LanguageSwitchPreferences.pair(zh, yue))
    }

    @Test fun switchingLanguageDoesNotOverrideSelectedSchemeOrLayout() {
        val entries = listOf(SchemaInfo("rime_ice", "rime_ice", "", "", ""),
            SchemaInfo("t9_pinyin", "t9_pinyin", "", "", ""),
            SchemaInfo("jyut6ping3", "jyut6ping3", "", "", "", language = InputLanguage.CANTONESE))
        val options = LanguageSwitchOptions(LanguageSwitchMode.SPECIFIC_ENGLISH, yue)
        val target = LanguageSwitchPreferences.target(en, zh, options, all.toList(), all)!!
        assertEquals("jyut6ping3", InputProfileSelection.preferred(entries, target, "jyut6ping3", "t9_pinyin")?.schemaId)
    }
}
