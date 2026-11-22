package com.kingzcheung.xime.ui.keyboard

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * 按住空格说话，往右上滑到悬浮的锁按钮上松手 = 锁定：手指离开也一直听，点一下空格结束并上屏
 * （用户 11-20：只用长按空格的人也要能连续语音）。往正上方滑仍是取消。
 *
 * 锁是键区右上角单独浮起来的圆按钮（用户 11-21，#531：原来画在候选栏最右一格，和那一格的按钮叠在一起）。
 * 只在按住说话时出现，不挡触摸、不占布局；键盘很宽（平板、横屏）时不放到最右边，离空格右端最多 [SpaceVoiceLockMetrics.reach]。
 */
@Stable
internal class SpaceVoiceLockState {
    /** 键区（放锁按钮的那一层）在 Compose 根里的位置。 */
    var host: Rect = Rect.Zero
    /** 空格在 Compose 根里的位置。 */
    var space: Rect = Rect.Zero
    /** 按钮尺寸（像素）；键盘外没量过时为 null，不能锁定。 */
    var metrics: SpaceVoiceLockMetrics? = null
    /** 这次长按开了麦、手指还按着：锁按钮浮出来。 */
    var holding by mutableStateOf(false)
    /** 手指在锁按钮上：松手就锁定。 */
    var armed by mutableStateOf(false)
    /** 已锁定：手指离开也在听，空格写「点按结束」。 */
    var locked by mutableStateOf(false)
    /** 手指在根坐标里的位置：到位时按钮往手指那边吸一点。只在画的时候读，不触发重组。 */
    var finger by mutableStateOf(Offset.Unspecified)

    /**
     * 松手就锁定：锁按钮浮着（麦克风真开着）且手指到位。锁按钮画「到位」的样子、空格写「松手锁定」、
     * 松手时真的锁定，三处都看这一个条件，动画到了就一定锁得上（用户 11-22：动画到头了松手没锁上）。
     */
    val lockReady: Boolean get() = holding && armed

    fun zoneContains(pointInRoot: Offset): Boolean =
        metrics?.let { spaceVoiceLockZoneContains(host, space, it, pointInRoot) } == true
}

/** 键盘外（设置页预览、测试）没有提供时为 null：长按空格说话照旧，只是没有锁定。 */
internal val LocalSpaceVoiceLock = staticCompositionLocalOf<SpaceVoiceLockState?> { null }

/**
 * 锁按钮的尺寸（像素）。[radius] 按钮半径；[margin] 离键区顶边、右边；[reach] 键盘很宽时按钮离空格右端最远多少；
 * [slop] 判定比按钮大出多少（手指不用正好落在按钮里）。
 */
internal data class SpaceVoiceLockMetrics(val radius: Float, val margin: Float, val reach: Float, val slop: Float)

/**
 * 锁按钮圆心（根坐标）：键区右上角往里 [SpaceVoiceLockMetrics.margin]；键盘很宽时往左收到空格右端外 reach 处，
 * 不至于要滑过半个平板。键区太窄放不下时为 null（不出锁）。
 */
internal fun spaceVoiceLockCenter(host: Rect, space: Rect, m: SpaceVoiceLockMetrics): Offset? {
    val edge = m.margin + m.radius
    if (host.width < 2f * edge || host.height <= 0f) return null
    val maxX = host.right - edge
    val x = if (space.width > 0f) minOf(maxX, space.right + m.reach).coerceAtLeast(host.left + edge) else maxX
    return Offset(x, host.top + minOf(edge, host.height / 2f))
}

/**
 * 锁定区：锁按钮（大出 slop）以及它右边、上边（手指冲过头、冲出键盘顶边也算）。
 * 往正上方滑（取消）碰不到：按钮总在空格右端的右上方。
 */
internal fun spaceVoiceLockZoneContains(host: Rect, space: Rect, m: SpaceVoiceLockMetrics, point: Offset): Boolean {
    val c = spaceVoiceLockCenter(host, space, m) ?: return false
    val reach = m.radius + m.slop
    return point.x >= c.x - reach && point.y <= c.y + reach
}

/**
 * 这一下手指算不算在锁上：在锁定区里就算；松手那一下手指离屏时坐标常会跳一点，之前已经到位的照旧算到位，
 * 不因为抬手那一下偏出去就锁不上。
 */
internal fun spaceVoiceLockArmed(inZone: Boolean, released: Boolean, wasArmed: Boolean): Boolean =
    inZone || (released && wasArmed)

/** 锁图标往上挪多少（占图标边长）：锁身在下、锁梁细，按外框居中时看着偏下，按视觉重心往上提一点。 */
internal const val SPACE_VOICE_LOCK_ICON_LIFT = 0.06f

/** 上滑取消：手指高过空格顶边一个键高（[yInKey] 是空格自己的坐标），在锁定区里时除外。 */
internal fun spaceVoiceCancelArmed(lockArmed: Boolean, yInKey: Float, keyHeight: Float): Boolean =
    !lockArmed && yInKey < -keyHeight

/** 到位时按钮往手指那边吸多少（像素，最多 [max]）。 */
internal fun spaceVoiceLockPull(center: Offset, finger: Offset, max: Float): Offset {
    if (!finger.isSpecified) return Offset.Zero
    val d = finger - center
    val len = d.getDistance()
    return if (len <= max || len == 0f) d else d * (max / len)
}

/**
 * 锁按钮这一层：铺满键区、不接触摸，只在按住说话时画出一个圆按钮。
 * 浮出：从小到大弹出、往上浮一点；手指到位：涨满强调色、锁变白、放大一圈并往手指吸，外面一圈光晕。
 */
@Composable
internal fun SpaceVoiceLockLayer(state: SpaceVoiceLockState?, accent: Color, surface: Color, modifier: Modifier = Modifier) {
    if (state == null) return
    val density = LocalDensity.current
    val metrics = remember(density) {
        with(density) { SpaceVoiceLockMetrics(radius = 26.dp.toPx(), margin = 12.dp.toPx(), reach = 168.dp.toPx(), slop = 24.dp.toPx()) }
    }
    state.metrics = metrics
    val lock = rememberVectorPainter(Icons.Default.Lock)
    val shown by animateFloatAsState(if (state.holding) 1f else 0f,
        if (state.holding) spring(dampingRatio = 0.62f, stiffness = 520f) else tween(140, easing = FastOutSlowInEasing),
        label = "voiceLockShown")
    val armedAnim by animateFloatAsState(if (state.lockReady) 1f else 0f,
        spring(dampingRatio = 0.55f, stiffness = 700f), label = "voiceLockArmed")
    // 锁上的那一下：按钮留住半秒，外框亮一圈白边闪一下再淡出（用户 11-22：锁定的反馈不够显著）。
    val flash = remember { Animatable(0f) }
    var flashing by remember { mutableStateOf(false) }
    LaunchedEffect(state.locked) {
        if (!state.locked) { flash.snapTo(0f); flashing = false; return@LaunchedEffect }
        flashing = true
        try {
            flash.snapTo(0f)
            flash.animateTo(1f, tween(SPACE_VOICE_LOCK_FLASH_IN_MS, easing = FastOutSlowInEasing))
            flash.animateTo(0f, tween(SPACE_VOICE_LOCK_FLASH_OUT_MS, easing = FastOutSlowInEasing))
        } finally { flashing = false }
    }
    val idleFill = lerp(surface.copy(alpha = 1f), accent, 0.12f)
    Box(modifier.onGloballyPositioned { state.host = it.boundsInRoot() }.drawBehind {
        if (!flashing && shown <= 0.01f) return@drawBehind
        val c0 = spaceVoiceLockCenter(state.host, state.space, metrics) ?: return@drawBehind
        val visible = if (flashing) 1f else shown
        val alpha = visible.coerceIn(0f, 1f)
        val armed = if (flashing) 1f else armedAnim
        val pull = spaceVoiceLockPull(c0, state.finger, 8.dp.toPx()) * armed
        val r = metrics.radius * (0.6f + 0.4f * visible) * (1f + 0.12f * armed)
        // 光晕连同吸过去的那几 dp 都不出键区（键区外是别的应用，画出去会被截掉一块）。
        val glow = r * 1.25f
        val raw = c0 - state.host.topLeft + pull + Offset(0f, (1f - alpha) * 8.dp.toPx())
        val center = Offset(raw.x.coerceIn(glow, maxOf(glow, size.width - glow)), raw.y.coerceIn(glow, maxOf(glow, size.height - glow)))
        // 柔和的投影：两层淡黑圆往下错开一点。
        drawCircle(Color.Black.copy(alpha = 0.10f * alpha), r + 3.dp.toPx(), center + Offset(0f, 2.5f.dp.toPx()))
        drawCircle(Color.Black.copy(alpha = 0.16f * alpha), r + 1.dp.toPx(), center + Offset(0f, 1.dp.toPx()))
        if (armed > 0f) drawCircle(accent.copy(alpha = 0.22f * armed * alpha), glow, center)
        drawCircle(lerp(idleFill, accent, armed).copy(alpha = alpha), r, center)
        drawCircle(accent.copy(alpha = 0.55f * (1f - armed) * alpha), r, center, style = Stroke(1.5f.dp.toPx()))
        val f = flash.value
        if (f > 0f) {
            // 锁上了：外框闪一下。外面一圈强调色粗边往外扩（扩到键区边为止，不被截掉），按钮边上一圈白边亮起；
            // 浅色、深色主题都看得见（白边压在强调色上，强调色边压在键盘底色上）。
            val room = minOf(center.x, size.width - center.x, center.y, size.height - center.y)
            val stroke = 3.dp.toPx()
            val ring = minOf(r + 2.dp.toPx() + 6.dp.toPx() * f, room - stroke / 2f)
            drawCircle(accent.copy(alpha = 0.9f * f), ring, center, style = Stroke(stroke))
            drawCircle(Color.White.copy(alpha = 0.95f * f), r - 1.5f.dp.toPx(), center, style = Stroke(2.dp.toPx()))
            drawCircle(Color.White.copy(alpha = 0.16f * f), r, center)
        }
        val icon = r * 0.95f
        translate(center.x - icon / 2f, center.y - icon / 2f - icon * SPACE_VOICE_LOCK_ICON_LIFT) {
            with(lock) { draw(Size(icon, icon), alpha = alpha, colorFilter = ColorFilter.tint(lerp(accent, Color.White, armed))) }
        }
    }) {
        // 测试用：按住说话时有这个节点。
        if (state.holding) Box(Modifier.testTag("space-voice-lock-button"))
        if (flashing) Box(Modifier.testTag("space-voice-lock-flash"))
    }
}

private const val SPACE_VOICE_LOCK_FLASH_IN_MS = 110
private const val SPACE_VOICE_LOCK_FLASH_OUT_MS = 380
