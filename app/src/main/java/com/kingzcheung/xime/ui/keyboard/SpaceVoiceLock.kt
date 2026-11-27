package com.kingzcheung.xime.ui.keyboard

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 按住空格说话时浮出的三圆按钮：左锁（普通锁定）、中叉（取消）、右锁（连续语音锁定）。
 * 左右锁定触发区严格沿按钮圆弧外切延伸至键区顶/侧边，其余上方区域全部归取消按钮。
 */
@Stable
internal class SpaceVoiceLockState {
    /** 键区（放锁按钮的那一层）在 Compose 根里的位置。 */
    var host: Rect = Rect.Zero
    /** 空格在 Compose 根里的位置。 */
    var space: Rect = Rect.Zero
    /** 按钮尺寸（像素）；键盘外没量过时为 null，不能锁定。 */
    var metrics: SpaceVoiceLockMetrics? = null
    /** 这次长按开了麦、手指还按着：三圆按钮显示。 */
    var holding by mutableStateOf(false)
    /** 手指在某一把锁上：松手就锁定。 */
    var armed by mutableStateOf(false)
    /** 手指在哪一把锁上：-1 左（普通锁定）、1 右（连续语音锁定）、0 都不在。 */
    var side by mutableIntStateOf(0)
    /** 这次按住说话开了「上滑取消」：中间出取消按钮。 */
    var cancelShown by mutableStateOf(false)
    /** 手指在取消区：取消按钮涨满。 */
    var cancelArmed by mutableStateOf(false)
    /** 已锁定：手指离开也在听，空格写「点按结束」。 */
    var locked by mutableStateOf(false)

    /** 松手就锁定：锁按钮显示且手指到位。 */
    val lockReady: Boolean get() = holding && armed

    private fun layout(): SpaceVoiceLockLayout? = metrics?.let { spaceVoiceLockLayout(host, space, it) }

    /** 手指在哪一把锁的区域里：-1 左、1 右、0 都不在。 */
    fun zoneSide(pointInRoot: Offset): Int = layout()?.sideAt(pointInRoot) ?: 0

    fun zoneContains(pointInRoot: Offset): Boolean = zoneSide(pointInRoot) != 0

    /** 手指在中间的取消按钮上（按钮大出 slop）。 */
    fun cancelButtonContains(pointInRoot: Offset): Boolean {
        val m = metrics ?: return false
        val c = layout()?.cancel ?: return false
        return (pointInRoot - c).getDistance() <= m.radius + m.slop
    }
}

/** 键盘外（设置页预览、测试）没有提供时为 null：长按空格说话照旧，只是没有锁定。 */
internal val LocalSpaceVoiceLock = staticCompositionLocalOf<SpaceVoiceLockState?> { null }

/**
 * 锁按钮的尺寸（像素）。[radius] 按钮半径；[margin] 离键区顶边、两边；[reach] 键盘很宽时按钮离空格两端最远多少；
 * [slop] 取消按钮外扩容差；[zone] 锁定圆弧最大半径；[gap] 锁定区和取消按钮之间至少留多少。
 */
internal data class SpaceVoiceLockMetrics(
    val radius: Float,
    val margin: Float,
    val reach: Float,
    val slop: Float,
    val zone: Float,
    val gap: Float,
)

/** 两把锁的圆心（根坐标）和按钮半径；取消按钮在两把锁正中间、同一高度。 */
internal class SpaceVoiceLockLayout(val left: Offset, val right: Offset, val zone: Float, val buttonRadius: Float = zone) {
    val cancel: Offset get() = Offset((left.x + right.x) / 2f, left.y)

    fun center(side: Int): Offset = if (side < 0) left else right

    /**
     * 左右锁定触发区：分别以左/右锁按钮自身圆弧为边界，向顶边和外侧边垂直/水平外切延伸。
     * 左锁：x <= left.x + r 且 y <= left.y + r，右下角沿圆弧裁切；
     * 右锁：x >= right.x - r 且 y <= right.y + r，左下角沿圆弧裁切；
     * 其余区域全部让给取消按钮。
     */
    fun sideAt(point: Offset): Int {
        val rSq = buttonRadius * buttonRadius
        val dxL = (point.x - left.x).coerceAtLeast(0f)
        val dyL = (point.y - left.y).coerceAtLeast(0f)
        if (dxL * dxL + dyL * dyL <= rSq) return -1
        val dxR = (right.x - point.x).coerceAtLeast(0f)
        val dyR = (point.y - right.y).coerceAtLeast(0f)
        if (dxR * dxR + dyR * dyR <= rSq) return 1
        return 0
    }
}

/**
 * 两把锁的位置：以空格中心左右对称，各自尽量靠键区两边（往里 margin），但离空格两端不超过 reach；
 * 两边能放的距离不一样时取近的那边，保证对称。键区太窄、空格太靠边放不下两把锁时为 null（不出锁）。
 */
internal fun spaceVoiceLockLayout(host: Rect, space: Rect, m: SpaceVoiceLockMetrics): SpaceVoiceLockLayout? {
    val edge = m.margin + m.radius
    if (host.width < 2f * edge || host.height <= 0f) return null
    val hasSpace = space.width > 0f
    val mid = if (hasSpace) space.center.x.coerceIn(host.left + edge, host.right - edge) else host.center.x
    val right = if (hasSpace) minOf(host.right - edge, space.right + m.reach) else host.right - edge
    val left = if (hasSpace) maxOf(host.left + edge, space.left - m.reach) else host.left + edge
    val d = minOf(right - mid, mid - left)
    if (d < 2f * m.radius + m.gap) return null
    val y = host.top + minOf(edge, host.height / 2f)
    val zone = minOf(m.zone, maxOf(m.radius + m.slop, d - m.radius - m.gap))
    return SpaceVoiceLockLayout(Offset(mid - d, y), Offset(mid + d, y), zone, m.radius)
}

/**
 * 这一下手指算不算在锁上：在锁定区里就算；松手那一下手指离屏时坐标常会跳一点，之前已经到位的照旧算到位。
 */
internal fun spaceVoiceLockArmed(inZone: Boolean, released: Boolean, wasArmed: Boolean): Boolean =
    inZone || (released && wasArmed)

/** 锁图标往上挪多少（占图标边长）：锁身在下、锁梁细，按视觉重心往上提一点。 */
internal const val SPACE_VOICE_LOCK_ICON_LIFT = 0.06f

/**
 * 上滑取消：手指高过空格顶边一个键高（[yInKey] 是空格自己的坐标），或在中间的取消按钮上；在锁定区里时除外。
 */
internal fun spaceVoiceCancelArmed(lockArmed: Boolean, yInKey: Float, keyHeight: Float, onCancelButton: Boolean = false): Boolean =
    !lockArmed && (yInKey < -keyHeight || onCancelButton)

/** 取消按钮的颜色：浅、深主题上都认得出的红。 */
private val SPACE_VOICE_CANCEL_RED = Color(0xFFE5534B)

/**
 * 锁按钮这一层：铺满键区、不接触摸，按住说话时直接显示左锁、中取消、右连续锁（无弹出动画与外圈光晕）。
 * 三个按钮统一沿用取消按钮的 `tween(140)` 放大变色动画。
 */
@Composable
internal fun SpaceVoiceLockLayer(state: SpaceVoiceLockState?, accent: Color, surface: Color, modifier: Modifier = Modifier) {
    if (state == null) return
    val density = LocalDensity.current
    val metrics = remember(density) {
        with(density) {
            SpaceVoiceLockMetrics(
                radius = 26.dp.toPx(), margin = 12.dp.toPx(), reach = 168.dp.toPx(),
                slop = 24.dp.toPx(), zone = 64.dp.toPx(), gap = 8.dp.toPx(),
            )
        }
    }
    state.metrics = metrics
    val lockLeft = rememberVectorPainter(Icons.Default.Lock)
    val lockRight = rememberVectorPainter(Icons.Default.Lock)
    val close = rememberVectorPainter(Icons.Default.Close)
    val textMeasurer = rememberTextMeasurer()
    val continuousLabelLayout = remember(textMeasurer, density) {
        textMeasurer.measure(
            text = "连续",
            style = TextStyle(fontSize = 9.5.sp, fontWeight = FontWeight.Medium, lineHeight = 10.sp),
            maxLines = 1,
        )
    }
    // 三圆统一沿用取消按钮的 tween(140) 选中动画，无弹出动画、无外圈光晕。
    val leftAnim by animateFloatAsState(if (state.lockReady && state.side < 0) 1f else 0f, tween(140), label = "voiceLockLeft")
    val rightAnim by animateFloatAsState(if (state.lockReady && state.side > 0) 1f else 0f, tween(140), label = "voiceLockRight")
    val cancelAnim by animateFloatAsState(if (state.holding && state.cancelShown && state.cancelArmed) 1f else 0f, tween(140), label = "voiceCancelArmed")
    val idleFill = lerp(surface.copy(alpha = 1f), accent, 0.12f)
    val cancelIdle = lerp(surface.copy(alpha = 1f), SPACE_VOICE_CANCEL_RED, 0.12f)
    Box(modifier.onGloballyPositioned { state.host = it.boundsInRoot() }.drawBehind {
        if (!state.holding) return@drawBehind
        val layout = spaceVoiceLockLayout(state.host, state.space, metrics) ?: return@drawBehind
        val origin = state.host.topLeft
        if (state.cancelShown) {
            val scaleFactor = 1f + 0.12f * cancelAnim
            val r = metrics.radius * scaleFactor
            val center = layout.cancel - origin
            drawCircle(lerp(cancelIdle, SPACE_VOICE_CANCEL_RED, cancelAnim), r, center)
            drawCircle(SPACE_VOICE_CANCEL_RED.copy(alpha = 0.6f * (1f - cancelAnim)), r, center, style = Stroke(1.5f.dp.toPx()))
            val baseIcon = metrics.radius * 0.9f
            scale(scaleFactor, pivot = center) {
                translate(center.x - baseIcon / 2f, center.y - baseIcon / 2f) {
                    with(close) { draw(Size(baseIcon, baseIcon), colorFilter = ColorFilter.tint(lerp(SPACE_VOICE_CANCEL_RED, Color.White, cancelAnim))) }
                }
            }
        }
        // 左锁：普通锁定（独立 VectorPainter + 固定 draw Size 配合 scale，避免缩放/共用缓存导致右下角残影）
        run {
            val scaleFactor = 1f + 0.12f * leftAnim
            val r = metrics.radius * scaleFactor
            val center = layout.left - origin
            drawCircle(lerp(idleFill, accent, leftAnim), r, center)
            drawCircle(accent.copy(alpha = 0.55f * (1f - leftAnim)), r, center, style = Stroke(1.5f.dp.toPx()))
            val baseIcon = metrics.radius * 0.95f
            scale(scaleFactor, pivot = center) {
                translate(center.x - baseIcon / 2f, center.y - baseIcon / 2f - baseIcon * SPACE_VOICE_LOCK_ICON_LIFT) {
                    with(lockLeft) { draw(Size(baseIcon, baseIcon), colorFilter = ColorFilter.tint(lerp(accent, Color.White, leftAnim))) }
                }
            }
        }
        // 右锁：连续语音锁定（圆内锁图标下方带「连续」小字）
        run {
            val scaleFactor = 1f + 0.12f * rightAnim
            val r = metrics.radius * scaleFactor
            val center = layout.right - origin
            drawCircle(lerp(idleFill, accent, rightAnim), r, center)
            drawCircle(accent.copy(alpha = 0.55f * (1f - rightAnim)), r, center, style = Stroke(1.5f.dp.toPx()))
            val contentTint = lerp(accent, Color.White, rightAnim)
            val baseIcon = metrics.radius * 0.78f
            val labelW = continuousLabelLayout.size.width.toFloat()
            val labelH = continuousLabelLayout.size.height.toFloat()
            val gap = 1.dp.toPx()
            val totalH = baseIcon + gap + labelH
            val topY = center.y - totalH / 2f - 1.dp.toPx()
            scale(scaleFactor, pivot = center) {
                translate(center.x - baseIcon / 2f, topY) {
                    with(lockRight) { draw(Size(baseIcon, baseIcon), colorFilter = ColorFilter.tint(contentTint)) }
                }
                drawText(
                    textLayoutResult = continuousLabelLayout,
                    color = contentTint,
                    topLeft = Offset(center.x - labelW / 2f, topY + baseIcon + gap),
                )
            }
        }
    }) {
        // 测试用：按住说话时有这些节点。
        if (state.holding) Box(Modifier.testTag("space-voice-lock-button"))
        if (state.holding) Box(Modifier.testTag("space-voice-continuous-lock-button"))
        if (state.holding && state.cancelShown) Box(Modifier.testTag("space-voice-cancel-button"))
    }
}
