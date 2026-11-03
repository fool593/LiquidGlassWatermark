package com.liquidglass.watermark.glass

import androidx.compose.ui.graphics.Shape

/**
 * Global glass quality tier.
 *
 * - [HIGH]: AGSL RuntimeShader lens (normal-based UV refraction + optional dispersion),
 *   real Gaussian [android.graphics.RenderEffect] blur, full highlight/shadow passes.
 * - [LOW]: no AGSL. The lens degrades to a *software* refraction approximation:
 *   normal-based radial magnification of the recorded backdrop (UV pull toward the shape
 *   centre) plus a compressed bevel ring at the border band. Blur/highlight/shadow are
 *   kept. It never degrades to plain alpha blending.
 */
object GlassQuality {
    const val HIGH = 0
    const val LOW = 1

    /** Set by the host (settings / device probe). Defaults to the full quality path. */
    @Volatile
    var tier: Int = HIGH

    fun isLow(): Boolean = tier == LOW
}

/**
 * Software lens descriptor used when the AGSL refraction shader is unavailable (low tier or
 * unsupported GPU). Consumed by [com.liquidglass.watermark.glass.backdrops.LayerBackdrop],
 * which draws the recorded backdrop through a magnifying transform clipped to the shape so
 * the border band still bends the background instead of flattening to a plain alpha tint.
 *
 * @param heightPx bevel band width in device pixels (the "edge thickness" of the glass)
 * @param amountPx peak UV displacement in device pixels at the outer edge
 * @param shape    glass outline; the transform is clipped to it
 */
class SoftwareLens(
    val heightPx: Float,
    val amountPx: Float,
    val shape: Shape
) {
    /** Interior magnification ratio derived from the displacement, clamped to stay sane. */
    fun magnification(shortSide: Float): Float {
        if (shortSide <= 1f || !amountPx.isFinite()) return 0f
        return (amountPx / shortSide).coerceIn(0f, 0.30f)
    }

    /** Bevel band width clamped so a tiny control still keeps a visible rim. */
    fun band(shortSide: Float): Float =
        (heightPx * 0.55f).coerceIn(1f, shortSide * 0.35f)
}
