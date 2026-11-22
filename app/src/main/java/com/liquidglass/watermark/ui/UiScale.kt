
//

//

package com.liquidglass.watermark.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

fun uiScaleForWidth(widthDp: Int): Float = when {
    widthDp >= 1000 -> 1.45f
    widthDp >= 840 -> 1.30f
    widthDp >= 720 -> 1.20f
    widthDp >= 600 -> 1.12f
    widthDp >= 480 -> 1.04f
    widthDp >= 420 -> 1.00f
    widthDp >= 380 -> 0.96f
    widthDp >= 340 -> 0.92f
    else -> 0.88f
}

@Composable
fun ProvideAdaptiveUiScale(content: @Composable () -> Unit) {
    val sys = LocalDensity.current
    val conf = LocalConfiguration.current
    val scale = uiScaleForWidth(conf.screenWidthDp)

    val fontScale = sys.fontScale.coerceIn(0.92f, 1.06f)
    CompositionLocalProvider(
        LocalDensity provides Density(
            density = sys.density * scale,
            fontScale = fontScale
        )
    ) {
        content()
    }
}
