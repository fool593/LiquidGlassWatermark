// Adapted from KernelSU manager (tiann/KernelSU) — Apache 2.0.
// Source: manager/app/src/main/java/me/weishu/kernelsu/ui/component/liquid/Lens.kt
// (itself adapted from Kyant0/AndroidLiquidGlass, mirrored from compose-miuix-ui example)
//
// 与工程内 Lens.kt 的差别：KSU 版本把色散强度做成 float uniform（0..1），
// 并把 padding 直接抬到 refractionAmount（透镜外溢区），因此玻璃边缘的色散
// 和放大比例与 KSU 管理器逐参数一致。这里只把 scope API 换成工程内的
// BackdropEffectScope / runtimeShaderEffect，shader 字符串沿用同一份。
package com.liquidglass.watermark.glass.effects

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.ui.unit.LayoutDirection
import com.liquidglass.watermark.glass.BackdropEffectScope
import com.liquidglass.watermark.glass.SoftwareLens
import com.liquidglass.watermark.glass.hasFiniteArea
import com.liquidglass.watermark.glass.internal.RoundedRectRefractionShaderString
import com.liquidglass.watermark.glass.internal.RoundedRectRefractionWithDispersionShaderString
import com.liquidglass.watermark.glass.isRuntimeShaderSupported

fun BackdropEffectScope.ksuLens(
    refractionHeight: Float,
    refractionAmount: Float,
    depthEffect: Boolean = false,
    chromaticAberration: Float = 0f,
) {
    if (!size.hasFiniteArea()) return
    if (refractionHeight <= 0f || refractionAmount <= 0f) return
    if (padding < refractionAmount) {
        padding = refractionAmount
    }
    val radii = roundedRectCornerRadii() ?: return

    // AGSL 不可用（低端 GPU / SwiftShader）：保留软件折射，避免退化为纯色块
    if (!isRuntimeShaderSupported()) {
        softwareLens = SoftwareLens(
            heightPx = refractionHeight,
            amountPx = refractionAmount,
            shape = shape
        )
        return
    }

    val dispersionEnabled = chromaticAberration > 0f
    val shaderString =
        if (dispersionEnabled) RoundedRectRefractionWithDispersionShaderString
        else RoundedRectRefractionShaderString
    val key = if (dispersionEnabled) "KsuLensDispersion" else "KsuLens"

    runtimeShaderEffect(
        key = key,
        shaderString = shaderString,
        uniformShaderName = "content",
    ) {
        setFloatUniform("size", size.width, size.height)
        setFloatUniform("offset", -padding, -padding)
        setFloatUniform("cornerRadii", radii)
        setFloatUniform("refractionHeight", refractionHeight)
        setFloatUniform("refractionAmount", -refractionAmount)
        setFloatUniform("depthEffect", if (depthEffect) 1f else 0f)
        if (dispersionEnabled) {
            setFloatUniform("chromaticAberration", chromaticAberration)
        }
    }
}

private fun BackdropEffectScope.roundedRectCornerRadii(): FloatArray? {
    val cornerShape = shape as? CornerBasedShape ?: return null
    val sizePx = size
    val maxRadius = sizePx.minDimension / 2f
    val isLtr = layoutDirection == LayoutDirection.Ltr
    val topLeft = if (isLtr) cornerShape.topStart.toPx(sizePx, this) else cornerShape.topEnd.toPx(sizePx, this)
    val topRight = if (isLtr) cornerShape.topEnd.toPx(sizePx, this) else cornerShape.topStart.toPx(sizePx, this)
    val bottomRight = if (isLtr) cornerShape.bottomEnd.toPx(sizePx, this) else cornerShape.bottomStart.toPx(sizePx, this)
    val bottomLeft = if (isLtr) cornerShape.bottomStart.toPx(sizePx, this) else cornerShape.bottomEnd.toPx(sizePx, this)
    return floatArrayOf(
        topLeft.coerceAtMost(maxRadius),
        topRight.coerceAtMost(maxRadius),
        bottomRight.coerceAtMost(maxRadius),
        bottomLeft.coerceAtMost(maxRadius),
    )
}
