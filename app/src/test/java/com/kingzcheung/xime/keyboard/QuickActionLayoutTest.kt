package com.kingzcheung.xime.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickActionLayoutTest {
    @Test fun existingToolbarIsKeptAndDefaultMenuSkipsItsEntries() {
        val toolbar = listOf("schema", "emoji", "voice", "clipboard")
        val layout = QuickActionLayout.normalize(toolbar, QuickActionLayout.defaultMenu(toolbar))
        assertEquals(toolbar, layout.toolbar)
        assertEquals(listOf("settings", "float", "sound", "glass", "incognito", "full_shape", "floating", "traditional"), layout.menu.filter { it !in ToolbarButton.LAYOUT_GESTURES.map { g -> g.id } })
        // 字幕是插件的按钮：新装的默认菜单里没有，装了字幕插件才放进去。
        assertTrue("caption" !in QuickActionLayout.defaultMenu(emptyList()))
        assertTrue("arrange" !in QuickActionLayout.defaultMenu(emptyList()))
    }

    @Test fun eachEntryLivesInOnePlaceAndSettingsCannotBeRemoved() {
        val layout = QuickActionLayout.normalize(listOf("emoji", "emoji", "glass"), listOf("glass", "voice", "arrange"))
        assertEquals(listOf("emoji", "glass"), layout.toolbar)
        assertEquals(listOf("voice", "settings"), layout.menu)
    }

    /** 菜单编辑只列当前键盘会出现的入口；别的键盘专用的入口保存后仍在原来的区和位置。 */
    @Test fun hiddenEntriesKeepTheirPlaceWhenArrangingInAnotherLayout() {
        val saved = QuickActionLayout(listOf("schema", "emoji"), listOf("settings", "t9_flick", "sound", "flick_disc"))
        val hidden = setOf("t9_flick", "flick_disc")
        val edited = QuickActionLayout(listOf("schema"), listOf("sound", "emoji", "settings"))
        val result = QuickActionLayout.restoreHidden(edited, saved, hidden)
        assertEquals(listOf("schema"), result.toolbar)
        assertEquals(listOf("sound", "t9_flick", "emoji", "flick_disc", "settings"), result.menu)
        // 什么都不动时保存，原样不变
        assertEquals(saved, QuickActionLayout.restoreHidden(
            QuickActionLayout(saved.toolbar, saved.menu - hidden), saved, hidden))
    }

    @Test fun enabledPluginButtonGoesIntoMenu() {
        val plugin = "com.example.plugin:open"
        val menu = listOf("settings", "voice")
        assertEquals(listOf("settings", "voice", plugin),
            QuickActionLayout.withPluginButtons(listOf("emoji"), menu, listOf(plugin)))
        // 已经在工具栏或菜单里的不动，也不重复
        assertEquals(menu, QuickActionLayout.withPluginButtons(listOf(plugin), menu, listOf(plugin)))
        assertEquals(listOf("settings", plugin), QuickActionLayout.withPluginButtons(emptyList(), listOf("settings", plugin), listOf(plugin)))
        assertEquals(listOf("settings", plugin), QuickActionLayout.withPluginButtons(emptyList(), listOf("settings"), listOf(plugin, "")))
    }
}
