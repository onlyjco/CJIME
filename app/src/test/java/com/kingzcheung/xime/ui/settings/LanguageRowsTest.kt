package com.kingzcheung.xime.ui.settings

import com.kingzcheung.xime.settings.InputLanguage
import org.junit.Assert.assertEquals
import org.junit.Test

class LanguageRowsTest {
    private val all = InputLanguage.supported.map { it.id }

    @Test
    fun justTurnedOffLanguageAppearsOnce() {
        // 关掉粤语后的那一帧：拖动顺序还没更新，仍含 yue。
        val staleOrder = listOf("zh", "en", "yue")
        val enabled = setOf(InputLanguage.CHINESE, InputLanguage.ENGLISH, InputLanguage.UNSPECIFIED)
        val rows = languageRows(staleOrder, enabled)
        assertEquals(rows.distinct(), rows)
        assertEquals(all.toSet(), rows.toSet())
        assertEquals(listOf("zh", "en", "yue"), rows)
    }

    @Test
    fun everyLanguageTurnedOffOneByOneStaysUnique() {
        val order = listOf("zh", "yue", "en").filter { it in all }
        order.filter { it != "en" }.forEach { off ->
            val enabled = InputLanguage.supported.filter { it.id != off }.toSet()
            val rows = languageRows(order, enabled)
            assertEquals(rows.distinct(), rows)
            assertEquals(off, rows.last())
        }
    }

    @Test
    fun noMoreLanguagesRowWhenOnlySupportedLanguagesRemain() {
        val enabled = setOf(InputLanguage.CHINESE, InputLanguage.ENGLISH)
        val rows = languageRows(listOf("zh", "en"), enabled)
        assertEquals(rows, languageListRows(rows, enabled, moreOpen = false))
    }

    @Test
    fun keepsDragOrderOfEnabledLanguages() {
        val enabled = setOf(InputLanguage.CANTONESE, InputLanguage.CHINESE, InputLanguage.ENGLISH)
        val rows = languageRows(listOf("yue", "en", "zh"), enabled)
        assertEquals(listOf("yue", "en", "zh"), rows.take(3))
    }
}
