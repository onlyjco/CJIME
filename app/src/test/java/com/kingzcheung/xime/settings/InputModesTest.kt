package com.kingzcheung.xime.settings

import org.junit.Assert.*
import org.junit.Test

class InputModesTest {
    private val modes = listOf("t9_pinyin", "rime_ice", "double_pinyin_flypy").map { SchemaInfo(it, it, "", "", "") } +
        listOf(
            SchemaInfo("jyutping", "粤拼", "", "", "", language = InputLanguage.CANTONESE),
            SchemaInfo("jyutping_t9", "粤拼九键", "", "", "", language = InputLanguage.CANTONESE),
        )

    @Test fun chineseModesIncludeShuangpinButNeverCantoneseOrEnglish() {
        assertEquals(listOf("t9_pinyin", "rime_ice", "double_pinyin_flypy"),
            InputModes.inCurrentLanguage(modes, "double_pinyin_flypy").map { it.schemaId })
    }
    @Test fun cantoneseAndEnglishPanelsOnlyShowTheirOwnModes() {
        assertEquals(listOf("jyutping", "jyutping_t9"),
            InputModes.inCurrentLanguage(modes, "jyutping_t9").map { it.schemaId })
        assertEquals(listOf(InputModes.ENGLISH),
            InputModes.inCurrentLanguage(modes, InputModes.ENGLISH).map { it.schemaId })
    }
    @Test fun languageMenuRestoresEachLanguageModeFromEnglish() {
        val choices = InputModes.languageChoices(modes, InputModes.ENGLISH,
            mapOf(InputLanguage.CHINESE to "double_pinyin_flypy", InputLanguage.CANTONESE to "jyutping_t9"))
        assertEquals(listOf("中文", "英文", "粤语"), choices.map { it.name })
        assertEquals(listOf("double_pinyin_flypy", InputModes.ENGLISH, "jyutping_t9"), choices.map { it.schemaId })
    }
    @Test fun addressFieldRemembersItsChineseLayoutWithoutChangingTheSavedProfile() {
        val saved = mapOf(InputLanguage.CHINESE to "rime_ice")
        val local = InputModes.languageChoices(modes, InputModes.ENGLISH, emptyMap(),
            selectedProfiles = saved, fieldNativeModeId = "t9_pinyin")
        assertEquals("t9_pinyin", local.first { it.language == InputLanguage.CHINESE }.schemaId)
        val ordinary = InputModes.languageChoices(modes, InputModes.ENGLISH, emptyMap(), selectedProfiles = saved)
        assertEquals("rime_ice", ordinary.first { it.language == InputLanguage.CHINESE }.schemaId)
    }
    @Test fun currentModeWinsAndRemovedModeFallsBackWithinItsLanguage() {
        val choices = InputModes.languageChoices(modes, "rime_ice",
            mapOf(InputLanguage.CHINESE to "t9_pinyin", InputLanguage.CANTONESE to "removed"))
        assertEquals(listOf("rime_ice", InputModes.ENGLISH, "jyutping"), choices.map { it.schemaId })
    }
    @Test fun newCantoneseModesCanDeclareTheirLanguageWithoutChangingTheMenu() {
        val extra = SchemaInfo("yale", "耶鲁粤拼", "", "", "", language = InputLanguage.CANTONESE)
        assertTrue(InputModes.inCurrentLanguage(modes + extra, "jyutping").contains(extra))
    }
    @Test fun reorderingChineseModesPreservesCantoneseAndEnglishSlots() {
        val current = listOf("t9_pinyin", "jyutping", "rime_ice", InputModes.ENGLISH, "jyutping_t9")
        assertEquals(listOf("rime_ice", "jyutping", "t9_pinyin", InputModes.ENGLISH, "jyutping_t9"),
            InputModes.mergeOrder(current, listOf("rime_ice", "t9_pinyin")))
    }

    @Test fun independentLanguageOrderKeepsTheRememberedInputMode() {
        val order = InputModes.languageOrder(listOf("en", "yue", "yue", "unknown"))
        assertEquals(listOf(InputLanguage.ENGLISH, InputLanguage.CANTONESE, InputLanguage.CHINESE), order)
        val choices = InputModes.languageChoices(modes, "rime_ice", mapOf(InputLanguage.CANTONESE to "jyutping_t9"), order)
        assertEquals(listOf(InputModes.ENGLISH, "jyutping_t9", "rime_ice"), choices.map { it.schemaId })
        assertEquals(listOf("rime_ice", "t9_pinyin"), InputModes.available(modes, InputModes.defaultModeOrder).take(2).map { it.schemaId })
        assertEquals("t9_pinyin", InputModes.available(modes, listOf("t9_pinyin", "rime_ice")).first().schemaId)
    }

    @Test fun cantoneseComesAfterEnglishByDefaultAndWhenSwitchedOnLater() {
        assertEquals(listOf(InputLanguage.CHINESE, InputLanguage.ENGLISH, InputLanguage.CANTONESE),
            InputModes.languageOrder(emptyList()))
        assertEquals(listOf("zh", "en", "yue"), InputModes.orderWithLanguageLast(listOf("zh", "yue", "en"), InputLanguage.CANTONESE))
    }

    @Test fun letterArrangementsGoLastUnlessTheUserOrderedThem() {
        val ids = listOf("pinyin_colemak", "rime_ice", QwjrtkLayout.ID, "cyime_cangjie", "custom_pinyin_0123",
            "t9_pinyin", "pinyin_typewise")
        val schemas = ids.map { SchemaInfo(it, it, "", "", "") } +
            listOf(SchemaInfo("jyutping", "粤拼", "", "", "", language = InputLanguage.CANTONESE))
        assertEquals(listOf("rime_ice", "t9_pinyin", InputModes.ENGLISH, "cyime_cangjie",
            "jyutping", "pinyin_colemak", QwjrtkLayout.ID, "custom_pinyin_0123", "pinyin_typewise"),
            InputModes.available(schemas, InputModes.defaultModeOrder).map { it.schemaId })
        assertEquals(ids + "jyutping" + InputModes.ENGLISH, InputModes.available(schemas, ids).map { it.schemaId })
    }

    @Test fun englishIsAvailableWithNoEnabledSchemas() {
        assertEquals(listOf(InputModes.english), InputModes.available(emptyList()))
    }
    @Test fun repeatedNormalizationKeepsOnePermanentEnglishEntry() {
        val yue = SchemaInfo("jyutping", "粤拼", "", "", "", language = InputLanguage.CANTONESE)
        val available = InputModes.available(listOf(yue, InputModes.english, yue))
        assertEquals(listOf(yue, InputModes.english), InputModes.available(available))
    }
    @Test fun tapAndMenuUseTheSameEnglishSelectionIdentity() {
        assertEquals(InputModes.ENGLISH, InputModes.selectedId("rime_ice", true))
        assertEquals("rime_ice", InputModes.selectedId("rime_ice", false))
    }
    @Test fun customOrderSurvivesNormalizationAndNewSchemasAppend() {
        val yue = SchemaInfo("jyutping", "粤拼", "", "", "", language = InputLanguage.CANTONESE)
        val chinese = SchemaInfo("t9", "拼音", "", "", "")
        val modes = InputModes.available(listOf(chinese, yue, chinese), listOf(InputModes.ENGLISH, "jyutping", "missing"))
        assertEquals(listOf(InputModes.ENGLISH, "jyutping", "t9"), modes.map { it.schemaId })
        assertEquals(modes, InputModes.available(modes))
    }

    @Test fun everyLanguageListSharesOneOrder() {
        val zh = InputLanguage.CHINESE; val en = InputLanguage.ENGLISH; val yue = InputLanguage.CANTONESE
        assertEquals(listOf(zh, yue, en), InputLanguage.supported)
        assertEquals(listOf(zh, yue, en), InputLanguage.speech)
        val fresh = InputModes.languageRank(emptyList())
        assertEquals(listOf(zh, en, yue, InputLanguage.UNSPECIFIED),
            listOf(InputLanguage.UNSPECIFIED, yue, en, zh).sortedBy(fresh))
        val dragged = InputModes.languageRank(listOf("yue", "zh"))
        assertEquals(listOf(yue, zh, en), listOf(en, zh, yue).sortedBy(dragged))
        assertEquals(listOf("中文", "英文", "粤语"), InputModes.languageChoices(modes, "rime_ice", emptyMap()).map { it.name })
    }
}
