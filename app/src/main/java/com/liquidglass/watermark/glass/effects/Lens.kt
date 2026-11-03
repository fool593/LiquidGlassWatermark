package com.liquidglass.watermark.glass.effects

import androidx.annotation.FloatRange
import androidx.compose.foundation.shape.AbsoluteRoundedCornerShape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.ui.unit.LayoutDirection
import com.liquidglass.watermark.glass.BackdropEffectScope
import com.liquidglass.watermark.glass.GlassQuality
import com.liquidglass.watermark.glass.SoftwareLens
import com.liquidglass.watermark.glass.internal.RoundedRectRefractionShaderString
import com.liquidglass.watermark.glass.internal.RoundedRectRefractionWithDispersionShaderString
import com.liquidglass.watermark.glass.internal.RuntimeShaderEffect
import com.liquidglass.watermark.glass.isRuntimeShaderSupported
import com.liquidglass.watermark.glass.hasFiniteArea
import com.liquidglass.watermark.LiquidGlassEngine
import kotlin.math.min

/**
 * Refraction lens for a glass surface.
 *
 * High tier: AGSL RuntimeShader doing normal-based UV refraction (plus optional 7-tap
 * dispersion). Low tier / no AGSL: a [SoftwareLens] descriptor is published instead, so the
 * backdrop is still drawn through a magnifying, shape-clipped transform — the optical
 * distortion survives, only the shader-grade dispersion is dropped. This is never replaced
 * by a flat alpha tint.
 */
fun BackdropEffectScope.lens(
    @FloatRange(from = 0.0) refractionHeight: Float,
    @FloatRange(from = 0.0) refractionAmount: Float,
    depthEffect: Boolean = false,
    chromaticAberration: Boolean = false,
    scaleCompensation: Float = 1f
) {
    if (!size.hasFiniteArea()) return
    if (!refractionHeight.isFinite() || !refractionAmount.isFinite() ||
        refractionHeight <= 0f || refractionAmount <= 0f) return

    // Resolution-independent optical scale. A glass surface rendered small (small viewport,
    // scaled-down object, low-density screen) would otherwise keep a fixed dp-sized rim that
    // shrinks into a hairline and effectively disappears. Compensation is inverse to the
    // rendered short side and clamped, so the effect stays visible without ballooning on
    // large surfaces.
    val comp = LiquidGlassEngine.scaleCompensation(
        min(size.width, size.height), scaleCompensation.coerceIn(0f, 1f))
    val height = refractionHeight * comp
    val amount = refractionAmount * comp

    // Low tier or no AGSL: keep refraction as a software transform (never plain alpha).
    if (GlassQuality.isLow() || lensUnsupported || !isRuntimeShaderSupported()) {
        softwareLens = SoftwareLens(
            heightPx = height,
            amountPx = amount,
            shape = shape
        )
        return
    }

    if (padding > 0f) {
        padding = (padding - height).coerceAtLeast(0f)
    }

    try {
        val cornerRadii = cornerRadii
        val effect =
            if (cornerRadii != null) {
                val shader =
                    if (!chromaticAberration) {
                    obtainRuntimeShader(
                        "Refraction",
                        RoundedRectRefractionShaderString
                    )
                } else {
                    obtainRuntimeShader(
                        "RefractionWithDispersion",
                        RoundedRectRefractionWithDispersionShaderString
                    )
                }
            shader.apply {
                setFloatUniform("size", size.width, size.height)
                setFloatUniform("offset", -padding, -padding)
                setFloatUniform("cornerRadii", cornerRadii)
                setFloatUniform("height", height)
                setFloatUniform("amount", -amount)
                setFloatUniform("depthEffect", if (depthEffect) 1f else 0f)
                if (chromaticAberration) {
                    setFloatUniform("chromaticAberration", 1f)
                }
            }
            RuntimeShaderEffect(shader, "content")
        } else {
            throwUnsupportedSDFException()
        }
        effect(effect)
    } catch (_: Throwable) {
        // AGSL/RuntimeShader 在当前设备/模拟器不可用（如 SwiftShader 无 GPU 加速）：
        // 不再静默跳过折射——改为发布软件折射描述，保留模糊的同时仍由法线驱动的 UV 放大
        // 还原边缘弯折（low 档路径）。仅当软件路径也失败时才会退化为纯着色。
        lensUnsupported = true
        softwareLens = SoftwareLens(
            heightPx = height,
            amountPx = amount,
            shape = shape
        )
    }
}

/** 当前设备不支持 AGSL 时置 true，避免每帧重复构造/抛异常（一次性探测） */
private var lensUnsupported = false

private val BackdropEffectScope.cornerRadii: FloatArray?
    get() = when (val shape = shape) {
        is AbsoluteRoundedCornerShape -> {
            val size = size
            val maxRadius = size.minDimension / 2f
            val topLeft = shape.topStart.toPx(size, this)
            val topRight = shape.topEnd.toPx(size, this)
            val bottomRight = shape.bottomEnd.toPx(size, this)
            val bottomLeft = shape.bottomStart.toPx(size, this)
            floatArrayOf(
                topLeft.coerceAtMost(maxRadius),
                topRight.coerceAtMost(maxRadius),
                bottomRight.coerceAtMost(maxRadius),
                bottomLeft.coerceAtMost(maxRadius)
            )
        }

        is CornerBasedShape -> {
            val size = size
            val maxRadius = size.minDimension / 2f
            val isLtr = layoutDirection == LayoutDirection.Ltr
            val topLeft =
                if (isLtr) shape.topStart.toPx(size, this)
                else shape.topEnd.toPx(size, this)
            val topRight =
                if (isLtr) shape.topEnd.toPx(size, this)
                else shape.topStart.toPx(size, this)
            val bottomRight =
                if (isLtr) shape.bottomEnd.toPx(size, this)
                else shape.bottomStart.toPx(size, this)
            val bottomLeft =
                if (isLtr) shape.bottomStart.toPx(size, this)
                else shape.bottomEnd.toPx(size, this)
            floatArrayOf(
                topLeft.coerceAtMost(maxRadius),
                topRight.coerceAtMost(maxRadius),
                bottomRight.coerceAtMost(maxRadius),
                bottomLeft.coerceAtMost(maxRadius)
            )
        }

        else -> null
    }

private fun throwUnsupportedSDFException(): Nothing {
    throw UnsupportedOperationException(
        "Only RoundedRectangularShape or CornerBasedShape is supported in lens effects."
    )
}
