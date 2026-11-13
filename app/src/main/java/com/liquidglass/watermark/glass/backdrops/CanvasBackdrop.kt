package com.liquidglass.watermark.glass.backdrops

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Density
import com.liquidglass.watermark.glass.Backdrop
import com.liquidglass.watermark.glass.SoftwareLens

/** LayerBackdrop consumers share a blurred capture of their actual source scene. */
var softwareBlurEnabled by mutableStateOf(false)

@Composable
fun rememberCanvasBackdrop(onDraw: DrawScope.() -> Unit): Backdrop {
    // A canvas callback must always draw its requested content. The old software
    // path replaced every callback with the same hardcoded dark bitmap.
    return remember(onDraw) { CanvasBackdrop(onDraw) }
}

@Immutable
private class CanvasBackdrop(val onDraw: DrawScope.() -> Unit) : Backdrop {
    override val isCoordinatesDependent: Boolean = false

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
        lens: SoftwareLens?
    ) {
        // A canvas callback supplies its own pixels; a software lens cannot sample it.
        onDraw()
    }
}
