package com.liquidglass.watermark.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import com.liquidglass.watermark.darkModeState

/**
 * 主题对齐 ADB 工具箱实拍风格：深蓝黑带微光背景、iOS 蓝主色、细亮边通透玻璃、
 * 绿色开关 / 橙色次要 / 红色危险。
 */
object LG {
    val Blue = Color(0xFF0A84FF)
    val BlueDeep = Color(0xFF0066D6)
    val Green = Color(0xFF30D158)
    val Orange = Color(0xFFFF9F0A)
    val Red = Color(0xFFFF453A)
    // 兼容旧命名
    val Cyan = Blue
    val CyanDeep = BlueDeep

    val Bg = Color(0xFF070B14)
    val BgGlow = Color(0xFF111C33)
    val BgGlow2 = Color(0xFF0A1120)
    val Surface = Color(0xFF0D1422)
    val TextMain = Color(0xFFF5F7FA)
    val TextSub = Color(0xFF949CAE)
    val CardStroke = Color.White.copy(alpha = 0.30f)

    val Swatches = listOf(
        Color(0xFFFFFFFF), Color(0xFFFFF6E8), Color(0xFF111111),
        Color(0xFF4C8DFF), Color(0xFFFFD28A), Color(0xFFFF8FB0),
        Color(0xFF8FF0C8), Color(0xFFB79CFF)
    )
}

private val GlassColorScheme = darkColorScheme(
    primary = LG.Blue,
    onPrimary = Color.White,
    secondary = LG.Orange,
    tertiary = LG.Red,
    background = LG.Bg,
    onBackground = LG.TextMain,
    surface = LG.Surface,
    onSurface = LG.TextMain,
    surfaceVariant = Color(0xFF161E2F),
    onSurfaceVariant = LG.TextSub
)

private val LightGlassColorScheme = lightColorScheme(
    primary = LG.BlueDeep,
    onPrimary = Color.White,
    secondary = Color(0xFFB96600),
    tertiary = LG.Red,
    background = Color(0xFFF0F3FA),
    onBackground = Color(0xFF1A1D24),
    surface = Color(0xFFE7EDF7),
    onSurface = Color(0xFF1A1D24),
    surfaceVariant = Color(0xFFDCE4F1),
    onSurfaceVariant = Color(0xFF526079)
)

/**
 * v14.87 液态玻璃 UI 背景：彩色渐变（深蓝→紫→青）+ 光晕——玻璃面板悬浮其上（透出色彩）
 *
 * v15.16 任务3 卡顿修复：
 * - 修复前 `liquidBackground()` 每次重组都会新建 2~5 个 Brush（verticalGradient / radialGradient /
 *   3 个径向光晕），Brush 对象不相等 → `Modifier.background(brush)` 的 modifier 元素每帧被判定
 *   为变化 → 触发重新绘制。而 `bump()` 在拖动滑块时每帧调用一次（70 处调用点），
 *   于是全屏背景每帧都在重绘（含 3 个 `radius = size.width * 0.95f` 的全屏径向渐变圆）。
 * - 修复后：所有 Brush 都 `remember` 缓存，只有 dark 模式切换或尺寸变化时才重建。
 *   绘制指令与颜色值完全不变，视觉逐像素一致。
 */
fun Modifier.liquidBackground(): Modifier {
    val dark = darkModeState
    return this
        .then(rememberLiquidBackgroundBrush(dark))
        .then(rememberLiquidGlowBrush(dark))
        .drawBehind {
            // A spatially varying scene gives every lens something to refract. Recording
            // only this layer avoids sampling labels or recursively capturing glass itself.
            val centers = liquidGlowCenters(size.width, size.height)
            val radius = size.width * 0.95f
            val brushes = liquidGlowBrushes(size.width, size.height, dark)
            for (i in centers.indices) {
                drawCircle(
                    brush = brushes[i],
                    radius = radius,
                    center = centers[i]
                )
            }
        }
        .then(Modifier.fillMaxSize())
}

private val LiquidGlowColors = listOf(Color(0xFF558AFF), Color(0xFFAA6FE5), Color(0xFF3FC9CB))

private fun liquidGlowCenters(width: Float, height: Float): List<Offset> = listOf(
    Offset(width * 0.12f, height * 0.2f),
    Offset(width * 0.9f, height * 0.57f),
    Offset(width * 0.25f, height * 0.94f)
)

/**
 * 3 个全屏半径径向渐变 Brush 的缓存：键为 (宽, 高, dark)。
 * 尺寸不变时零分配；旋转/分屏导致尺寸变化时重建一次。
 */
// L5：原实现把 (宽,高,dark) 哈希成一个 Long 作缓存键，理论上存在碰撞（碰撞会复用错误尺寸的
// Brush → 视觉错位）。改为直接按三个字段精确比较，杜绝碰撞，开销可忽略。
private var glowBrushW: Float = Float.NaN
private var glowBrushH: Float = Float.NaN
private var glowBrushDark: Boolean = false
private var glowBrushes: List<Brush> = emptyList()

private fun liquidGlowBrushes(width: Float, height: Float, dark: Boolean): List<Brush> {
    if (glowBrushes.isNotEmpty() && width == glowBrushW && height == glowBrushH && dark == glowBrushDark) {
        return glowBrushes
    }
    val centers = liquidGlowCenters(width, height)
    val radius = width * 0.95f
    val alpha = if (dark) 0.22f else 0.14f
    glowBrushes = centers.mapIndexed { i, center ->
        Brush.radialGradient(
            listOf(LiquidGlowColors[i].copy(alpha = alpha), Color.Transparent),
            center = center,
            radius = radius
        )
    }
    glowBrushW = width; glowBrushH = height; glowBrushDark = dark
    return glowBrushes
}

private var liquidBgBrushKey: Int = Int.MIN_VALUE
private var liquidBgBrush: Brush? = null

private fun rememberLiquidBackgroundBrush(dark: Boolean): Modifier {
    val key = if (dark) 1 else 0
    if (key != liquidBgBrushKey) {
        liquidBgBrush =
            if (dark) Brush.verticalGradient(
                0f to Color(0xFF0B1322),
                0.4f to Color(0xFF141A3A),
                0.7f to Color(0xFF1A1440),
                1f to Color(0xFF0E2A3A)
            ) else Brush.verticalGradient(
                0f to Color(0xFFF2F4FB),
                0.45f to Color(0xFFE8ECFA),
                0.75f to Color(0xFFF6EBFA),
                1f to Color(0xFFE3F0F6)
            )
        liquidBgBrushKey = key
    }
    return Modifier.background(liquidBgBrush!!)
}

private var liquidGlowOverlayKey: Int = Int.MIN_VALUE
private var liquidGlowOverlay: Modifier = Modifier

private fun rememberLiquidGlowBrush(dark: Boolean): Modifier {
    if (!dark) return Modifier
    val key = 1
    if (key != liquidGlowOverlayKey) {
        liquidGlowOverlay = Modifier.background(
            Brush.radialGradient(
                colors = listOf(LG.BgGlow.copy(alpha = 0.55f), LG.BgGlow2.copy(alpha = 0.25f), Color.Transparent),
                radius = 1200f
            )
        )
        liquidGlowOverlayKey = key
    }
    return liquidGlowOverlay
}

@Composable
fun LiquidGlassTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkModeState) GlassColorScheme else LightGlassColorScheme,
        typography = MaterialTheme.typography,
        content = content
    )
}
