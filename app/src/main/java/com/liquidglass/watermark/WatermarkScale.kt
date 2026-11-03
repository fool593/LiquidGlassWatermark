package com.liquidglass.watermark

import kotlin.math.pow

/** Shared scale mapping for every watermark layer. Values stay in the saved 1x=100% convention. */
object WatermarkScale {
    const val MIN_PERCENT = 35
    const val MAX_PERCENT = 350

    /**
     * A mild perceptual curve makes changes around the normal 100% setting easier to see,
     * while preserving the exact default. Clamping here also protects old presets and imported JSON.
     */
    fun factor(percent: Int): Float =
        (percent.coerceIn(MIN_PERCENT, MAX_PERCENT) / 100f).pow(1.10f).coerceIn(0.30f, 4f)

    /** Small marks get a little more alpha; large marks remain slightly softer than a full-strength overlay. */
    fun opacityMultiplier(scale: Float): Float = when {
        scale < 1f -> 1f + ((1f - scale) * 0.22f).coerceAtMost(0.15f)
        scale > 1f -> 1f - (kotlin.math.ln(scale) * 0.02f).coerceAtMost(0.04f)
        else -> 1f
    }
}
