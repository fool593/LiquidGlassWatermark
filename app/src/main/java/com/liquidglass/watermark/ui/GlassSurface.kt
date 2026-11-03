package com.liquidglass.watermark.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liquidglass.watermark.darkModeState
import com.liquidglass.watermark.glass.Backdrop
import com.liquidglass.watermark.glass.drawBackdrop
import com.liquidglass.watermark.glass.effects.blur
import com.liquidglass.watermark.glass.effects.lens
import com.liquidglass.watermark.glass.effects.vibrancy
import com.liquidglass.watermark.glass.highlight.Highlight
import com.liquidglass.watermark.glass.shadow.Shadow

internal val LocalGlassBackdrop = staticCompositionLocalOf<Backdrop> {
    error("Glass controls must be placed inside EditorScreen's recorded scene")
}

/** One surface treatment for panels, input fields, chips and dialogs on every API. */
@Composable
internal fun Modifier.glassSurface(
    backdrop: Backdrop = LocalGlassBackdrop.current,
    shape: Shape = RoundedCornerShape(20.dp),
    prominent: Boolean = false
): Modifier {
    val dark = darkModeState
    return drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            blur(if (prominent) 14.dp.toPx() else 6.dp.toPx())
            // v15.16 任务2：量程上调（原 3/5dp），配合 lens() 内置反比尺度补偿，
            // 小视口/低分辨率下也不会退化成一圈细边。
            lens(
                refractionHeight = 6.dp.toPx(),
                refractionAmount = 11.dp.toPx(),
                depthEffect = true,
                scaleCompensation = 1f
            )
        },
        highlight = { Highlight.Default.copy(alpha = if (dark) 0.55f else 0.8f) },
        shadow = { Shadow(radius = if (prominent) 20.dp else 10.dp,
            color = Color.Black.copy(alpha = if (dark) 0.24f else 0.10f)) },
        onDrawSurface = {
            drawRect(Brush.verticalGradient(
                if (dark) listOf(
                    Color.White.copy(alpha = if (prominent) 0.13f else 0.07f),
                    Color(0xFF09101F).copy(alpha = if (prominent) 0.50f else 0.20f)
                ) else listOf(
                    Color.White.copy(alpha = if (prominent) 0.76f else 0.5f),
                    Color(0xFFD9E5F4).copy(alpha = 0.28f)
                )
            ))
        }
    )
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text = text,
        color = if (darkModeState) LG.TextSub else Color(0xFF5A6478),
        fontSize = 12.sp,
        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp, top = 2.dp)
    )
}
