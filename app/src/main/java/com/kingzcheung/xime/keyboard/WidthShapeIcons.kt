package com.kingzcheung.xime.keyboard

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 全角/半角图标与桌面版（CyIME-Windows 工具栏）一致：半角是月牙，全角是满圆，
 * 同样的 24 视框路径与描边，不用 Material 的宽度图标。
 */
private fun strokeIcon(name: String, strokeWidth: Float = 2f, draw: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).path(
        fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = strokeWidth,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = draw,
    ).build()

/**
 * 半角：以 (12, 12) 为圆心、中心半径 9、线宽 2.0（外径 20 / 内径 16），
 * 与符号图标（Material Outlined Paid）圆圈尺寸与线条粗细完全一致，并与全角图标同圆心对齐。
 */
internal val HalfWidthIcon: ImageVector by lazy {
    strokeIcon("HalfWidth", strokeWidth = 2f) {
        moveTo(13.03f, 3.06f)
        arcTo(9f, 9f, 0f, isMoreThanHalf = true, isPositiveArc = false, x1 = 20.03f, y1 = 16.06f)
        arcTo(9f, 9f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 13.03f, y1 = 3.06f)
        close()
    }
}

/** 全角：直接沿用半角图标的外圆弧与线宽 2.0，补全缺口成完整圆，与符号图标和半角图标完全一致对齐。 */
internal val FullWidthIcon: ImageVector by lazy {
    strokeIcon("FullWidth", strokeWidth = 2f) {
        moveTo(13.03f, 3.06f)
        arcTo(9f, 9f, 0f, isMoreThanHalf = true, isPositiveArc = false, x1 = 20.03f, y1 = 16.06f)
        arcTo(9f, 9f, 0f, isMoreThanHalf = false, isPositiveArc = false, x1 = 13.03f, y1 = 3.06f)
        close()
    }
}
