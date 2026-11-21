package com.kingzcheung.xime.ui.keyboard

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * 按住空格说话滑到右上角（工具栏语音键的位置，语音键藏起来也一样）松手 = 锁定：手指离开也一直听，
 * 点一下空格结束并上屏（用户 11-20：只用长按空格的人也要能连续语音）。往上滑仍是取消，右上角那一格让给锁定。
 */
@Stable
internal class SpaceVoiceLockState {
    /** 候选栏 / 工具栏那一条在 Compose 根里的位置；锁定区是它右端一格。 */
    var bar: Rect = Rect.Zero
    /** 锁定区至少多宽（像素，跟着候选栏缩放）。 */
    var minZoneWidthPx: Float = 0f
    /** 这次长按开了麦、手指还按着：右上角画出锁。 */
    var holding by mutableStateOf(false)
    /** 手指在右上角：松手就锁定。 */
    var armed by mutableStateOf(false)
    /** 已锁定：手指离开也在听，空格写「点按结束」。 */
    var locked by mutableStateOf(false)

    fun zoneContains(pointInRoot: Offset): Boolean = spaceVoiceLockZoneContains(bar, minZoneWidthPx, pointInRoot)
}

/** 键盘外（设置页预览、测试）没有提供时为 null：长按空格说话照旧，只是没有锁定。 */
internal val LocalSpaceVoiceLock = staticCompositionLocalOf<SpaceVoiceLockState?> { null }

/** 锁定区占候选栏右端的比例：大约一个工具栏按钮宽。 */
internal const val SPACE_VOICE_LOCK_ZONE_FRACTION = 0.16f

/**
 * 右上角锁定区：候选栏右端一格（至少 [minWidthPx] 宽）、候选栏底边以上。
 * 手指冲出键盘顶边、右边也算，不用正好按在那一格里。
 */
internal fun spaceVoiceLockZoneContains(bar: Rect, minWidthPx: Float, point: Offset): Boolean {
    if (bar.width <= 0f || bar.height <= 0f) return false
    val zoneWidth = maxOf(minWidthPx, bar.width * SPACE_VOICE_LOCK_ZONE_FRACTION)
    return point.x >= bar.right - zoneWidth && point.y <= bar.bottom
}

/** 锁那一格的左边（候选栏自己的坐标）。 */
internal fun spaceVoiceLockZoneLeft(barWidth: Float, zoneWidth: Float): Float = (barWidth - zoneWidth).coerceAtLeast(0f)

/** 锁那一格里原来内容的透明度：锁出现多少，底下就淡掉多少。 */
internal fun spaceVoiceLockUnderAlpha(lockShown: Float): Float = (1f - lockShown).coerceIn(0f, 1f)

/** 上滑取消：手指高过空格顶边一个键高（[yInKey] 是空格自己的坐标），右上角锁定区那一格除外。 */
internal fun spaceVoiceCancelArmed(lockArmed: Boolean, yInKey: Float, keyHeight: Float): Boolean =
    !lockArmed && yInKey < -keyHeight

/**
 * 候选栏右端的锁：按住说话时淡出一个浅色圆，手指到位时涨满强调色、锁变白。
 * 那一格原来的东西（▾ 展开、⌄ 收起键盘、候选字）跟着锁出现淡掉，松手再回来，不和锁叠在一起（#531）。
 */
@Composable
internal fun Modifier.spaceVoiceLockTarget(state: SpaceVoiceLockState?, accent: Color): Modifier {
    if (state == null) return this
    val lock = rememberVectorPainter(Icons.Default.Lock)
    val minWidthPx = with(LocalDensity.current) { 56.dp.toPx() }
    val shown by animateFloatAsState(if (state.holding) 1f else 0f, tween(160), label = "voiceLockShown")
    val armed by animateFloatAsState(if (state.holding && state.armed) 1f else 0f, tween(120), label = "voiceLockArmed")
    return this
        .onGloballyPositioned { state.bar = it.boundsInRoot(); state.minZoneWidthPx = minWidthPx }
        .drawWithContent {
            if (shown <= 0f) {
                drawContent()
                return@drawWithContent
            }
            val zoneWidth = maxOf(minWidthPx, size.width * SPACE_VOICE_LOCK_ZONE_FRACTION)
            val zoneLeft = spaceVoiceLockZoneLeft(size.width, zoneWidth)
            clipRect(right = zoneLeft) { this@drawWithContent.drawContent() }
            val under = spaceVoiceLockUnderAlpha(shown)
            if (under > 0f) clipRect(left = zoneLeft) {
                drawIntoCanvas { canvas ->
                    canvas.saveLayer(Rect(zoneLeft, 0f, size.width, size.height), Paint().apply { alpha = under })
                    this@drawWithContent.drawContent()
                    canvas.restore()
                }
            }
            val center = Offset(size.width - zoneWidth / 2f, size.height / 2f)
            val radius = size.height * (0.34f + 0.08f * armed) * shown
            drawCircle(accent.copy(alpha = (0.20f + 0.80f * armed) * shown), radius, center)
            val icon = radius * 1.05f
            translate(center.x - icon / 2f, center.y - icon / 2f) {
                with(lock) {
                    draw(Size(icon, icon), alpha = shown, colorFilter = ColorFilter.tint(lerp(accent, Color.White, armed)))
                }
            }
        }
}
