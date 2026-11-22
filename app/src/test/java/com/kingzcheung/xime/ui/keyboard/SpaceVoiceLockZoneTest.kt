package com.kingzcheung.xime.ui.keyboard

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 锁按钮：键区右上角浮起来的圆按钮；键盘很宽时离空格不太远；按钮本身和它右边、上边算锁定，往正上方滑不算。 */
class SpaceVoiceLockZoneTest {
    // 半径 70、离边 25、离空格最远 420、判定大出 60（约 2.6 倍密度的 26/10/168/24 dp）。
    private val m = SpaceVoiceLockMetrics(radius = 70f, margin = 25f, reach = 420f, slop = 60f)
    // 手机竖屏：键区 1080×800，从根坐标 (0, 700) 开始；空格在下面中间。
    private val phone = Rect(0f, 700f, 1080f, 1500f)
    private val phoneSpace = Rect(380f, 1340f, 700f, 1490f)

    @Test fun `on a phone the button sits in the top-right corner of the keys`() {
        assertEquals(Offset(985f, 795f), spaceVoiceLockCenter(phone, phoneSpace, m))
    }

    @Test fun `on a wide keyboard it stays within reach of the space bar`() {
        val tablet = Rect(0f, 900f, 2560f, 1700f)
        val space = Rect(900f, 1540f, 1600f, 1690f)
        assertEquals(Offset(2020f, 995f), spaceVoiceLockCenter(tablet, space, m))
    }

    @Test fun `floating or split keyboards keep it inside their own key area`() {
        val floating = Rect(300f, 400f, 900f, 900f)
        val c = spaceVoiceLockCenter(floating, Rect(450f, 800f, 750f, 890f), m)!!
        assertEquals(805f, c.x, 0f)
        assertTrue(c.x + m.radius <= floating.right && c.y - m.radius >= floating.top)
    }

    @Test fun `a very short key area keeps the button vertically inside`() {
        val short = Rect(0f, 0f, 1080f, 120f)
        assertEquals(60f, spaceVoiceLockCenter(short, Rect.Zero, m)!!.y, 0f)
    }

    @Test fun `nothing measured yet or too narrow means no lock`() {
        assertNull(spaceVoiceLockCenter(Rect.Zero, Rect.Zero, m))
        assertNull(spaceVoiceLockCenter(Rect(0f, 0f, 150f, 800f), Rect.Zero, m))
        assertFalse(spaceVoiceLockZoneContains(Rect.Zero, Rect.Zero, m, Offset(0f, 0f)))
    }

    @Test fun `the button and overshooting above or to the right lock`() {
        assertTrue("on the button", spaceVoiceLockZoneContains(phone, phoneSpace, m, Offset(985f, 795f)))
        assertTrue("just outside its lower-left edge", spaceVoiceLockZoneContains(phone, phoneSpace, m, Offset(870f, 910f)))
        assertTrue("above the keyboard", spaceVoiceLockZoneContains(phone, phoneSpace, m, Offset(1000f, 600f)))
        assertTrue("past the right edge", spaceVoiceLockZoneContains(phone, phoneSpace, m, Offset(1100f, 760f)))
    }

    @Test fun `straight up from the space bar and the keys below the button do not`() {
        assertFalse("straight up", spaceVoiceLockZoneContains(phone, phoneSpace, m, Offset(540f, 760f)))
        assertFalse("below the button", spaceVoiceLockZoneContains(phone, phoneSpace, m, Offset(985f, 1000f)))
    }

    @Test fun `when armed the button leans toward the finger at most a little`() {
        assertEquals(Offset(3f, 4f), spaceVoiceLockPull(Offset(0f, 0f), Offset(3f, 4f), 20f))
        assertEquals(Offset(12f, 16f), spaceVoiceLockPull(Offset(0f, 0f), Offset(30f, 40f), 20f))
        assertEquals(Offset.Zero, spaceVoiceLockPull(Offset(0f, 0f), Offset.Unspecified, 20f))
    }

    @Test fun `swipe-up cancel needs a full key height above the space and never fires on the lock`() {
        assertFalse("half a key up", spaceVoiceCancelArmed(lockArmed = false, yInKey = -50f, keyHeight = 100f))
        assertFalse("exactly on the line", spaceVoiceCancelArmed(lockArmed = false, yInKey = -100f, keyHeight = 100f))
        assertTrue("past the line", spaceVoiceCancelArmed(lockArmed = false, yInKey = -130f, keyHeight = 100f))
        assertFalse("past the line but on the lock", spaceVoiceCancelArmed(lockArmed = true, yInKey = -130f, keyHeight = 100f))
    }

    @Test fun `lifting off keeps an armed lock armed, but never arms by itself`() {
        assertTrue("in the zone", spaceVoiceLockArmed(inZone = true, released = false, wasArmed = false))
        assertFalse("moved out while still down", spaceVoiceLockArmed(inZone = false, released = false, wasArmed = true))
        assertTrue("lift-off jitter out of the zone", spaceVoiceLockArmed(inZone = false, released = true, wasArmed = true))
        assertFalse("released outside, never armed", spaceVoiceLockArmed(inZone = false, released = true, wasArmed = false))
    }

    @Test fun `the button looks armed exactly when letting go would lock`() {
        val state = SpaceVoiceLockState()
        state.armed = true
        assertFalse("microphone not on yet: no button, no lock", state.lockReady)
        state.holding = true
        assertTrue(state.lockReady)
        state.armed = false
        assertFalse(state.lockReady)
    }
}
