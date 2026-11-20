package com.kingzcheung.xime.ui.keyboard

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 长按空格说话：真开了麦，空格上就写「正在听…」并带麦克风；没开起来（总开关关着）就不假装在听。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class SpaceVoiceHoldHintTest {
    @get:Rule val rule = createComposeRule()

    private var listening by mutableStateOf(false)

    private fun setKey(micStarts: Boolean, swipeCancel: Boolean = false) {
        rule.setContent {
            CompositionLocalProvider(
                LocalKeyboardInputPreferences provides KeyboardInputPreferences(spaceHold = SpaceHoldAction.VOICE_TOGGLE,
                    cursorGesture = CursorGestureMode.KEYBOARD, voiceSwipeCancel = swipeCancel),
                LocalKeyboardInputActions provides KeyboardInputActions(
                    onVoiceToggle = { if (micStarts) listening = true },
                    onVoiceHoldEnd = { listening = false },
                    isVoiceMode = listening, voiceSticky = listening),
            ) {
                Box(Modifier.size(240.dp, 64.dp)) {
                    SpaceKeyButton(onClick = {}, backgroundColor = Color.White, textColor = Color.Black,
                        modifier = Modifier.testTag("space"))
                }
            }
        }
    }

    @Test fun `holding space to talk shows that the microphone is listening until release`() {
        setKey(micStarts = true)
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("space").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(100L)
        rule.onNodeWithTag("space-voice-hold-hint").assertDoesNotExist()
        rule.mainClock.advanceTimeBy(500L)
        rule.onNodeWithTag("space-voice-hold-hint").assertTextEquals("正在听…")
        rule.onNodeWithTag("space-voice-hold-mic").assertExists()
        rule.onNodeWithTag("space").performTouchInput { up() }
        rule.mainClock.advanceTimeBy(500L)
        rule.onNodeWithTag("space-voice-hold-hint").assertDoesNotExist()
        rule.onNodeWithTag("space-voice-hold-mic").assertDoesNotExist()
    }

    @Test fun `no listening look when the microphone did not start`() {
        setKey(micStarts = false)
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("space").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(600L)
        rule.onNodeWithTag("space-voice-hold-mic").assertDoesNotExist()
        rule.onNodeWithTag("space").performTouchInput { up() }
    }

    @Test fun `swipe-to-cancel keeps its hint next to the microphone`() {
        setKey(micStarts = true, swipeCancel = true)
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("space").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(600L)
        rule.onNodeWithTag("space-voice-cancel-hint").assertTextEquals("上滑取消")
        rule.onNodeWithTag("space-voice-hold-mic").assertExists()
        rule.onNodeWithTag("space").performTouchInput { up() }
    }

    @Test fun `sliding up warns gradually and only says release-to-cancel past the line`() {
        setKey(micStarts = true, swipeCancel = true)
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("space").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(600L)
        // 往上走了半个键高：删除色只是透出来，字还是「上滑取消」。
        rule.onNodeWithTag("space").performTouchInput { moveTo(androidx.compose.ui.geometry.Offset(centerX, -height * 0.5f)) }
        rule.mainClock.advanceTimeBy(100L)
        rule.onNodeWithTag("space-voice-cancel-hint").assertTextEquals("上滑取消")
        // 过了取消线：「松手取消」。
        rule.onNodeWithTag("space").performTouchInput { moveTo(androidx.compose.ui.geometry.Offset(centerX, -height * 1.5f)) }
        rule.mainClock.advanceTimeBy(200L)
        rule.onNodeWithTag("space-voice-cancel-hint").assertTextEquals("松手取消")
        // 滑回来：恢复，松手照常上屏。
        rule.onNodeWithTag("space").performTouchInput { moveTo(center) }
        rule.mainClock.advanceTimeBy(200L)
        rule.onNodeWithTag("space-voice-cancel-hint").assertTextEquals("上滑取消")
        rule.onNodeWithTag("space").performTouchInput { up() }
    }
}
