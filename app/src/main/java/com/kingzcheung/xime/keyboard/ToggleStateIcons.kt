package com.kingzcheung.xime.keyboard

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BlurOn
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

private fun ImageVector.Builder.copyNodesFrom(group: VectorGroup) {
    for (node in group) {
        when (node) {
            is VectorPath -> addPath(
                pathData = node.pathData,
                pathFillType = node.pathFillType,
                name = node.name,
                fill = node.fill,
                fillAlpha = node.fillAlpha,
                stroke = node.stroke,
                strokeAlpha = node.strokeAlpha,
                strokeLineWidth = node.strokeLineWidth,
                strokeLineCap = node.strokeLineCap,
                strokeLineJoin = node.strokeLineJoin,
                strokeLineMiter = node.strokeLineMiter,
                trimPathStart = node.trimPathStart,
                trimPathEnd = node.trimPathEnd,
                trimPathOffset = node.trimPathOffset,
            )
            is VectorGroup -> group(
                name = node.name,
                rotate = node.rotation,
                pivotX = node.pivotX,
                pivotY = node.pivotY,
                scaleX = node.scaleX,
                scaleY = node.scaleY,
                translationX = node.translationX,
                translationY = node.translationY,
                clipPathData = node.clipPathData,
            ) {
                copyNodesFrom(node)
            }
        }
    }
}

/**
 * 仿照 Material Outlined 隐私模式（VisibilityOff）风格：
 * 直接复用开启态原图标的精确路径，仅在对角斜杠两侧裁剪留白并叠加标准斜杠，
 * 保证斜杠下方的底图与开启态 100% 一致。
 */
private fun slashedIcon(name: String, base: ImageVector): ImageVector {
    val g = 3.1f
    val clip = PathBuilder().apply {
        // 斜线右上方区域（x - y >= g）
        moveTo(g, 0f)
        lineTo(24f, 0f)
        lineTo(24f, 24f - g)
        close()
        // 斜线左下方区域（y - x >= g）
        moveTo(0f, g)
        lineTo(24f - g, 24f)
        lineTo(0f, 24f)
        close()
    }.nodes
    return ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .group(clipPathData = clip) {
            copyNodesFrom(base.root)
        }
        .path(fill = SolidColor(Color.Black)) {
            // Material Outlined 标准斜杠（与 VisibilityOff 同粗细同倾角）
            moveTo(2.1f, 3.51f)
            lineTo(3.51f, 2.1f)
            lineTo(21.9f, 20.49f)
            lineTo(20.49f, 21.9f)
            close()
        }
        .build()
}

/**
 * 任务栏全角开启图标：直接复用原版 [FullWidthIcon] 外圆，仅在圆内居中叠加标准字体轮廓「全」字。
 * 卡片形态继续用原版 [FullWidthIcon]（无「全」字）。
 */
internal val FullWidthToolbarIcon: ImageVector by lazy {
    ImageVector.Builder("FullWidthToolbar", 24.dp, 24.dp, 24f, 24f)
        .apply { copyNodesFrom(FullWidthIcon.root) }
        .addPath(
            pathData = addPathNodes(
                "M7.36 15.55L11.23 15.55L11.23 14.32L8.18 14.32L8.18 13.01L11.23 13.01" +
                    "L11.23 11.97L9.25 11.97L9.25 11.17Q8.58 11.65 7.81 12.11Q7.51 11.69 6.90 10.91" +
                    "Q10.18 9.15 11.34 7.14L13.14 7.14L12.87 7.53Q14.19 9.42 17.10 10.79" +
                    "Q16.56 11.46 16.18 12.00Q15.41 11.59 14.74 11.13L14.74 11.97L12.76 11.97" +
                    "L12.76 13.01L15.84 13.01L15.84 14.32L12.76 14.32L12.76 15.55L16.64 15.55" +
                    "L16.64 16.86L7.36 16.86L7.36 15.55Z" +
                    "M12.00 8.61Q11.16 9.68 9.91 10.66L14.09 10.66Q12.86 9.72 12.00 8.61Z"
            ),
            pathFillType = PathFillType.EvenOdd,
            fill = SolidColor(Color.Black),
        )
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

/** 滑行输入关闭图标：直接基于 [Icons.Outlined.Gesture] 原路径叠加隐私模式同款留白斜杠。 */
internal val GlideOffIcon: ImageVector by lazy {
    slashedIcon("GlideOff", Icons.Outlined.Gesture)
}

/** 玻璃效果关闭图标：直接基于 [Icons.Outlined.BlurOn] 原路径叠加隐私模式同款留白斜杠。 */
internal val GlassOffIcon: ImageVector by lazy {
    slashedIcon("GlassOff", Icons.Outlined.BlurOn)
}
