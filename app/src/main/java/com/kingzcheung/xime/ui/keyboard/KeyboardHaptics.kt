package com.kingzcheung.xime.ui.keyboard

import android.content.Context
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import com.kingzcheung.xime.settings.SettingsPreferences

/**
 * 统一的键盘触觉开关判断：遵守「声音振动」快捷开关和设置页「触觉反馈模式」（关闭 / 跟随系统 / 启用）。
 * 所有键盘手势、长按、拖拽、锁定、取消等直接调系统触觉的入口都必须经过这里，避免关闭声音振动后仍震动。
 */
internal fun isKeyboardHapticAllowed(context: Context): Boolean {
    return when (SettingsPreferences.getHapticMode(context)) {
        "disabled" -> false
        "enabled" -> true
        else -> try {
            Settings.System.getInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 1
        } catch (_: Exception) {
            true
        }
    }
}

/** 受「声音振动 / 触觉反馈模式」约束的 View 触觉反馈。 */
internal fun View.performKeyboardHaptic(feedbackConstant: Int): Boolean {
    if (!isKeyboardHapticAllowed(context)) return false
    var flags = HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
    if (SettingsPreferences.getHapticMode(context) == "enabled") {
        @Suppress("DEPRECATION")
        flags = flags or HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
    }
    return performHapticFeedback(feedbackConstant, flags)
}

/** 受「声音振动 / 触觉反馈模式」约束的 Compose HapticFeedback 触觉反馈。 */
internal fun HapticFeedback.performKeyboardHaptic(context: Context, type: HapticFeedbackType) {
    if (!isKeyboardHapticAllowed(context)) return
    performHapticFeedback(type)
}
