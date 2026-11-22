package com.liquidglass.watermark.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.text.font.FontFamily
import com.liquidglass.watermark.FontSupport
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import com.liquidglass.watermark.darkModeState

object LG {
    val Blue = Color(0xFF0A84FF)
    val BlueDeep = Color(0xFF0066D6)
    val Green = Color(0xFF30D158)
    val Orange = Color(0xFFFF9F0A)
    val Red = Color(0xFFFF453A)

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

private val UiFontFamily: FontFamily by lazy { FontFamily(FontSupport.factory(false)) }

@Composable
fun LiquidGlassTheme(content: @Composable () -> Unit) {

    val uiStyle = LocalTextStyle.current.copy(fontFamily = UiFontFamily)
    MaterialTheme(
        colorScheme = if (darkModeState) GlassColorScheme else LightGlassColorScheme,
        typography = MaterialTheme.typography,
        content = {
            CompositionLocalProvider(LocalTextStyle provides uiStyle, content = content)
        }
    )
}
