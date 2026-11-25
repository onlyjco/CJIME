package com.kingzcheung.xime.keyboard

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * 任务栏全角开启图标：与 [FullWidthIcon] 同尺寸外圆，圆内居中绘制清晰的「全」字。
 * 卡片形态继续用 [FullWidthIcon]（无「全」字）。
 */
internal val FullWidthToolbarIcon: ImageVector by lazy {
    ImageVector.Builder("FullWidthToolbar", 24.dp, 24.dp, 24f, 24f)
        .path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            // 外圆：与 FullWidthIcon 一致
            moveTo(12f, 3f)
            arcTo(9f, 9f, 0f, isMoreThanHalf = true, isPositiveArc = false, x1 = 12f, y1 = 21f)
            arcTo(9f, 9f, 0f, isMoreThanHalf = true, isPositiveArc = false, x1 = 12f, y1 = 3f)
            close()
        }
        .path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.45f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            // 「人」字顶
            moveTo(12f, 6.7f)
            lineTo(7.6f, 10.9f)
            moveTo(12f, 6.7f)
            lineTo(16.4f, 10.9f)
            // 「王」字三横一竖
            moveTo(9.3f, 11.2f)
            lineTo(14.7f, 11.2f)
            moveTo(9.6f, 13.9f)
            lineTo(14.4f, 13.9f)
            moveTo(8.3f, 16.7f)
            lineTo(15.7f, 16.7f)
            moveTo(12f, 11.2f)
            lineTo(12f, 16.7f)
        }
        .build()
}

/**
 * 声音震动关闭图标：保留与 Material Outlined VolumeUp 相同的喇叭主体，
 * 喇叭前方原本的声波半圆替换为清晰的「X」。
 */
internal val SoundOffXIcon: ImageVector by lazy {
    ImageVector.Builder("SoundOffX", 24.dp, 24.dp, 24f, 24f)
        .path(
            fill = SolidColor(Color.Black),
            pathFillType = PathFillType.NonZero,
        ) {
            // Material Outlined VolumeUp 喇叭轮廓（x=3..12）
            moveTo(7f, 9f)
            lineTo(3f, 9f)
            lineTo(3f, 15f)
            lineTo(7f, 15f)
            lineTo(12f, 20f)
            lineTo(12f, 4f)
            lineTo(7f, 9f)
            close()
            moveTo(10f, 8.83f)
            lineTo(10f, 15.17f)
            lineTo(7.83f, 13f)
            lineTo(5f, 13f)
            lineTo(5f, 11f)
            lineTo(7.83f, 11f)
            lineTo(10f, 8.83f)
            close()
        }
        .path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.9f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            // 声波位置的「X」
            moveTo(15.5f, 9.25f)
            lineTo(21.0f, 14.75f)
            moveTo(21.0f, 9.25f)
            lineTo(15.5f, 14.75f)
        }
        .build()
}

/**
 * 滑行输入关闭图标：仿照隐私模式（VisibilityOff）风格，在滑行轨迹图标上叠加斜线并在交汇处留空。
 */
internal val GlideOffIcon: ImageVector by lazy {
    ImageVector.Builder("GlideOff", 24.dp, 24.dp, 24f, 24f)
        .path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.0f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            // 左上到右上的起笔弧（位于斜线右上侧）
            moveTo(7.2f, 5.2f)
            curveTo(9.4f, 3.8f, 13.8f, 3.8f, 13.8f, 6.3f)
            curveTo(13.8f, 8.0f, 12.1f, 9.2f, 10.6f, 10.2f)
            // 跨过斜线后的中段弧（位于斜线左下侧）
            moveTo(6.9f, 13.0f)
            curveTo(5.2f, 14.4f, 4.8f, 16.5f, 6.2f, 17.8f)
            curveTo(7.8f, 19.2f, 10.7f, 18.4f, 12.9f, 16.7f)
            // 尾段上扬收笔（位于斜线右上侧）
            moveTo(16.3f, 14.1f)
            curveTo(17.8f, 13.4f, 19.3f, 13.6f, 20.2f, 14.7f)
        }
        .path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.0f,
            strokeLineCap = StrokeCap.Round,
        ) {
            // 隐私模式同款斜线
            moveTo(3.0f, 3.0f)
            lineTo(21.0f, 21.0f)
        }
        .build()
}

/**
 * 玻璃效果关闭图标：仿照隐私模式（VisibilityOff）风格，保留 BlurOn 点阵并在对角斜线路径处留空。
 */
internal val GlassOffIcon: ImageVector by lazy {
    val builder = ImageVector.Builder("GlassOff", 24.dp, 24.dp, 24f, 24f)
    fun addDot(cx: Float, cy: Float, r: Float) {
        if (abs(cx - cy) <= 2.4f) return
        builder.path(fill = SolidColor(Color.Black)) {
            moveTo(cx - r, cy)
            arcTo(r, r, 0f, isMoreThanHalf = true, isPositiveArc = false, x1 = cx + r, y1 = cy)
            arcTo(r, r, 0f, isMoreThanHalf = true, isPositiveArc = false, x1 = cx - r, y1 = cy)
            close()
        }
    }
    // Material Outlined BlurOn 的完整点阵
    val coords = listOf(6f, 10f, 14f, 18f)
    for (x in coords) {
        for (y in coords) {
            val isCenter = (x == 10f || x == 14f) && (y == 10f || y == 14f)
            addDot(x, y, if (isCenter) 1.5f else 1.0f)
        }
    }
    for (p in listOf(
        2f to 10f, 2f to 14f, 22f to 10f, 22f to 14f,
        10f to 2f, 14f to 2f, 10f to 22f, 14f to 22f,
    )) {
        addDot(p.first, p.second, 0.55f)
    }
    builder.path(
        fill = null,
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 2.0f,
        strokeLineCap = StrokeCap.Round,
    ) {
        moveTo(3.0f, 3.0f)
        lineTo(21.0f, 21.0f)
    }
    builder.build()
}
