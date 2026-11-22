package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Lock
import androidx.compose.material3.MaterialTheme
import com.kingzcheung.xime.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.keyboard.KeyboardPanelLaunch
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.keyboard.rememberFloatingGeometryLocked

internal fun keyboardPanelTitle(panel: KeyboardPanelLaunch.Panel) = when (panel) {
    KeyboardPanelLaunch.Panel.RESIZE -> "键盘尺寸与位置"
}

@Composable
internal fun KeyboardPanelSettingsContent(panel: KeyboardPanelLaunch.Panel, onBack: () -> Unit) {
    KeyboardSettingsScaffold(keyboardPanelTitle(panel), onBack, preview = true, editPanel = panel) { modifier ->
        val context = LocalContext.current
        SettingsToggleItem(Icons.TwoTone.Lock, "锁定悬浮键盘位置和大小",
            "锁定后不能移动或缩放，长按底部横条解锁",
            checked = rememberFloatingGeometryLocked(),
            onCheckedChange = { SettingsPreferences.setFloatingGeometryLocked(context, it) })
        Text(
            "点「调整尺寸」后拖动边框，调好点「确认」。",
            modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
