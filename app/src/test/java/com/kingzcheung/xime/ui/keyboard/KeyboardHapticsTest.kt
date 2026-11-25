package com.kingzcheung.xime.ui.keyboard

import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.kingzcheung.xime.settings.SettingsPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class KeyboardHapticsTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        SettingsPreferences.getPrefsPublic(context).edit().clear().commit()
    }

    @Test
    fun soundAndVibrationOffDisablesKeyboardHaptics() {
        SettingsPreferences.setSoundAndVibrationEnabled(context, false)
        assertFalse(isKeyboardHapticAllowed(context))
    }

    @Test
    fun enabledModeAllowsKeyboardHapticsEvenWhenSystemOff() {
        Settings.System.putInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 0)
        SettingsPreferences.setHapticMode(context, "enabled")
        assertTrue(isKeyboardHapticAllowed(context))
    }

    @Test
    fun followingSystemRespectsSystemHapticSetting() {
        SettingsPreferences.setHapticMode(context, "following_system")
        Settings.System.putInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 0)
        assertFalse(isKeyboardHapticAllowed(context))
        Settings.System.putInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1)
        assertTrue(isKeyboardHapticAllowed(context))
    }
}
