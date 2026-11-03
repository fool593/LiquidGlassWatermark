package com.liquidglass.watermark.glass.effects

import androidx.annotation.FloatRange
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import com.liquidglass.watermark.glass.BackdropEffectScope
import com.liquidglass.watermark.glass.isRenderEffectSupported
import com.liquidglass.watermark.glass.hasFiniteArea

fun BackdropEffectScope.blur(
    @FloatRange(from = 0.0) radius: Float,
    edgeTreatment: TileMode = TileMode.Clamp
) {
    if (!size.hasFiniteArea()) return
    if (!isRenderEffectSupported()) return
    if (!radius.isFinite() || radius <= 0f) return

    if (edgeTreatment != TileMode.Clamp || renderEffect != null) {
        if (radius > padding) {
            padding = radius
        }
    }

    renderEffect =
        BlurEffect(
            renderEffect,
            radius,
            radius,
            edgeTreatment
        )
}
