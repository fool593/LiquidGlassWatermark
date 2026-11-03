package com.liquidglass.watermark.glass.internal

import android.graphics.BlurMaskFilter
import android.os.Build
import androidx.compose.ui.graphics.Paint
import com.liquidglass.watermark.glass.RuntimeShader
import com.liquidglass.watermark.glass.asAndroidRuntimeShader

internal fun Paint.blur(radius: Float) {
    this.asFrameworkPaint().maskFilter =
        if (radius > 0f) BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL)
        else null
}

internal fun Paint.setRuntimeShader(runtimeShader: RuntimeShader?) {
    // RuntimeShader 是 API 33 类：即使调用方漏判版本，这里也兜底，低版本绝不触碰该类型
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        this.asFrameworkPaint().shader = runtimeShader?.asAndroidRuntimeShader()
    }
}
