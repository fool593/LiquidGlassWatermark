package com.liquidglass.watermark.glass

import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Density

interface Backdrop {

    val isCoordinatesDependent: Boolean

    /**
     * @param lens software refraction descriptor. Non-null means the AGSL lens shader is not
     *   available on this tier/device and the backdrop must be drawn through a magnifying,
     *   shape-clipped transform so the optical distortion survives instead of flattening into
     *   a plain alpha tint.
     */
    fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)? = null,
        lens: SoftwareLens? = null
    )
}
