// Dock 图标：v15.35 按 Apple SF Symbols 的笔画语言重绘（细圆头线条、几何留白、无填充）。
// 6 个 glyph 对应 形状 / 玻璃 / 光泽 / 字体 / 位置 / 设置，尺寸 16dp，颜色取 LocalContentColor。
// 玻璃层（普通）取 onSurface，指示器放大副本取 accent —— 与 KSU 的着色规则一致。
package com.liquidglass.watermark.ui.ksu

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

@Composable
fun KsuDockIcon(iconId: Int, modifier: Modifier = Modifier) {
    val tint = LocalContentColor.current
    Canvas(modifier.height(16.dp).fillMaxWidth()) {
        val u = size.minDimension / 16f          // 以 16dp 为设计基准，随可用高度等比缩放
        val stroke = Stroke(width = 1.35f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val cx = size.width / 2f
        val cy = size.height / 2f
        fun p(x: Float, y: Float) = Offset(cx + x * u, cy + y * u)

        when (iconId) {
            // 0 形状：squircle 轮廓 + 右上角一道高光弧（SF "squircle" 观感）
            0 -> {
                drawRoundRect(
                    color = tint,
                    topLeft = p(-5.6f, -5.6f),
                    size = Size(11.2f * u, 11.2f * u),
                    cornerRadius = CornerRadius(3.6f * u),
                    style = stroke
                )
                val arc = Path().apply {
                    moveTo(cx + 0.4f * u, cy - 3.4f * u)
                    quadraticTo(cx + 3.4f * u, cy - 3.4f * u, cx + 3.4f * u, cy - 0.4f * u)
                }
                drawPath(arc, tint, style = Stroke(width = 1.1f * u, cap = StrokeCap.Round))
            }
            // 1 玻璃：水滴（SF "drop"）——上尖下圆，内部一道弧形反光
            1 -> {
                val drop = Path().apply {
                    moveTo(cx, cy - 6.2f * u)
                    cubicTo(
                        cx + 2.4f * u, cy - 3.2f * u,
                        cx + 4.6f * u, cy - 1.2f * u,
                        cx + 4.6f * u, cy + 1.8f * u
                    )
                    cubicTo(
                        cx + 4.6f * u, cy + 4.4f * u,
                        cx + 2.6f * u, cy + 5.8f * u,
                        cx, cy + 5.8f * u
                    )
                    cubicTo(
                        cx - 2.6f * u, cy + 5.8f * u,
                        cx - 4.6f * u, cy + 4.4f * u,
                        cx - 4.6f * u, cy + 1.8f * u
                    )
                    cubicTo(
                        cx - 4.6f * u, cy - 1.2f * u,
                        cx - 2.4f * u, cy - 3.2f * u,
                        cx, cy - 6.2f * u
                    )
                    close()
                }
                drawPath(drop, tint, style = stroke)
                val glint = Path().apply {
                    moveTo(cx - 2.6f * u, cy + 2.2f * u)
                    quadraticTo(cx - 2.0f * u, cy + 3.6f * u, cx - 0.6f * u, cy + 3.9f * u)
                }
                drawPath(glint, tint.copy(alpha = 0.55f), style = Stroke(width = 1f * u, cap = StrokeCap.Round))
            }
            // 2 光泽：SF "sparkles"——一颗大四角星 + 两颗小星
            2 -> {
                fun sparkle(at: Offset, r: Float) {
                    val path = Path().apply {
                        moveTo(at.x, at.y - r)
                        quadraticTo(at.x + r * 0.18f, at.y - r * 0.18f, at.x + r, at.y)
                        quadraticTo(at.x + r * 0.18f, at.y + r * 0.18f, at.x, at.y + r)
                        quadraticTo(at.x - r * 0.18f, at.y + r * 0.18f, at.x - r, at.y)
                        quadraticTo(at.x - r * 0.18f, at.y - r * 0.18f, at.x, at.y - r)
                        close()
                    }
                    drawPath(path, tint, style = Stroke(width = 1.15f * u, cap = StrokeCap.Round))
                }
                sparkle(p(-1.4f, -1.4f), 4.6f * u)
                sparkle(p(4.3f, -4.0f), 2.1f * u)
                sparkle(p(3.9f, 4.1f), 2.4f * u)
            }
            // 3 字体：SF "text.alignleft"——三条左对齐横线（上长下短），比 Aa 在 16dp 下更清晰
            3 -> {
                drawLine(tint, p(-5f, -4.2f), p(5f, -4.2f), 1.35f * u, StrokeCap.Round)
                drawLine(tint, p(-5f, 0f), p(2.4f, 0f), 1.35f * u, StrokeCap.Round)
                drawLine(tint, p(-5f, 4.2f), p(3.8f, 4.2f), 1.35f * u, StrokeCap.Round)
            }
            // 4 位置：SF "mappin"——水滴形图钉 + 中心圆孔
            4 -> {
                val pin = Path().apply {
                    moveTo(cx, cy + 6.0f * u)
                    cubicTo(
                        cx - 1.4f * u, cy + 2.6f * u,
                        cx - 4.3f * u, cy + 0.4f * u,
                        cx - 4.3f * u, cy - 2.0f * u
                    )
                    cubicTo(
                        cx - 4.3f * u, cy - 4.6f * u,
                        cx - 2.3f * u, cy - 6.2f * u,
                        cx, cy - 6.2f * u
                    )
                    cubicTo(
                        cx + 2.3f * u, cy - 6.2f * u,
                        cx + 4.3f * u, cy - 4.6f * u,
                        cx + 4.3f * u, cy - 2.0f * u
                    )
                    cubicTo(
                        cx + 4.3f * u, cy + 0.4f * u,
                        cx + 1.4f * u, cy + 2.6f * u,
                        cx, cy + 6.0f * u
                    )
                    close()
                }
                drawPath(pin, tint, style = stroke)
                drawCircle(tint, radius = 1.75f * u, center = Offset(cx, cy - 2.0f * u),
                    style = Stroke(width = 1.1f * u))
            }
            // 5 设置：SF "gearshape"——齿轮轮廓 + 中心孔
            else -> {
                val teeth = 10
                val outer = 5.9f * u
                val inner = 4.2f * u
                val path = Path()
                for (i in 0 until teeth * 2) {
                    val r = if (i % 2 == 0) outer else inner
                    val ang = Math.toRadians((i * 180.0 / teeth) - 90.0)
                    val x = cx + kotlin.math.cos(ang).toFloat() * r
                    val y = cy + kotlin.math.sin(ang).toFloat() * r
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                drawPath(path, tint, style = stroke)
                drawCircle(tint, radius = 2.1f * u, center = Offset(cx, cy), style = Stroke(width = 1.2f * u))
            }
        }
    }
}
