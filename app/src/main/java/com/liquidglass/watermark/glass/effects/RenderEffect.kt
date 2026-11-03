package com.liquidglass.watermark.glass.effects

import androidx.compose.ui.graphics.RenderEffect
import com.liquidglass.watermark.glass.BackdropEffectScope
import com.liquidglass.watermark.glass.RuntimeShader
import com.liquidglass.watermark.glass.internal.RuntimeShaderEffect
import com.liquidglass.watermark.glass.internal.chain
import com.liquidglass.watermark.glass.isRenderEffectSupported
import com.liquidglass.watermark.glass.isRuntimeShaderSupported
import org.intellij.lang.annotations.Language
import kotlin.contracts.ExperimentalContracts

fun BackdropEffectScope.effect(effect: RenderEffect) {
    if (!isRenderEffectSupported()) return

    renderEffect = renderEffect.chain(effect)
}

@OptIn(ExperimentalContracts::class)
fun BackdropEffectScope.runtimeShaderEffect(
    key: String,
    @Language("AGSL") shaderString: String,
    uniformShaderName: String,
    block: RuntimeShader.() -> Unit
) {
    if (!isRuntimeShaderSupported()) return

    val effect =
        RuntimeShaderEffect(
            runtimeShader = obtainRuntimeShader(key, shaderString).apply(block),
            uniformShaderName = uniformShaderName
        )
    renderEffect = renderEffect.chain(effect)
}
