package com.kingzcheung.xime.ui.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material3.Icon
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** 固定40dp占位和32dp圆形底板，切换图标不会挤动工具栏。 */
@Composable
internal fun KeyboardToolbarButton(
    onClick: () -> Unit,
    background: Color,
    modifier: Modifier = Modifier,
    outline: Color = Color.Transparent,
    /** 平时也在的实色底（不随按下淡出）；默认没有，只给候选栏两端的返回和展开按钮用。 */
    restingFill: Color = Color.Transparent,
    /** 在工具栏里占等宽一格：整格可点，底色为与其他工具图标同尺寸的胶囊。 */
    slotWidth: androidx.compose.ui.unit.Dp? = null,
    /** 胶囊改成这个圆角（与键盘卡片同心）；null 时是两头全圆的胶囊。 */
    cornerRadius: androidx.compose.ui.unit.Dp? = null,
    /** 按格子宽度连续地从圆变成胶囊（不像工具栏那样在 40dp 处跳变），最宽 52dp。 */
    continuousPill: Boolean = false,
    content: @Composable () -> Unit,
) {
    // 去掉按下亮底与 300ms 渐隐，仅保留平时底色（如候选栏两端按钮的 restingFill）。
    // 直接使用带形状的 background 替代 .clip(shape)，避免创建隔离的子 GraphicsLayer/RenderNode 阻断父层 glassTextHalo 重录。
    val source = remember { MutableInteractionSource() }
    if (slotWidth != null) {
        val pill = if (continuousPill) edgePillSize(slotWidth) else toolbarPillSize(slotWidth)
        val shape = cornerRadius?.let { androidx.compose.foundation.shape.RoundedCornerShape(it) } ?: CircleShape
        val disc = if (restingFill.alpha > 0f) Modifier.background(restingFill, shape) else Modifier
        Box(modifier.width(slotWidth).height(40.dp).clickable(source, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center) {
            Box(Modifier.size(pill).then(disc),
                contentAlignment = Alignment.Center) { content() }
        }
        return
    }
    val disc = if (restingFill.alpha > 0f) Modifier.background(restingFill, CircleShape) else Modifier
    Box(modifier.size(40.dp).clickable(source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Box(Modifier.size(32.dp).then(disc), contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
internal fun KeyboardBackButton(
    onBack: () -> Unit,
    background: Color,
    foreground: Color,
    modifier: Modifier = Modifier,
    label: String = "返回",
    slotWidth: androidx.compose.ui.unit.Dp? = null,
    restingFill: Color = Color.Transparent,
    cornerRadius: androidx.compose.ui.unit.Dp? = null,
    continuousPill: Boolean = false,
) {
    KeyboardToolbarButton(onBack, background, modifier, slotWidth = slotWidth, restingFill = restingFill,
        cornerRadius = cornerRadius, continuousPill = continuousPill) {
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = label,
            tint = foreground, modifier = Modifier.size(24.dp))
    }
}

/**
 * 候选栏两端按钮：格子窄时是 32dp 圆（更窄的悬浮小窗跟着缩，最小 28dp），
 * 格子变宽时宽度随之连续增加成胶囊，最宽 52dp，不多占候选的地方（用户 10-20）。
 */
internal fun edgePillSize(slot: androidx.compose.ui.unit.Dp): androidx.compose.ui.unit.DpSize {
    val width = slot - 12.dp
    if (width <= 32.dp) {
        val side = width.coerceAtLeast(28.dp)
        return androidx.compose.ui.unit.DpSize(side, side)
    }
    return androidx.compose.ui.unit.DpSize(width.coerceAtMost(52.dp), 32.dp)
}
