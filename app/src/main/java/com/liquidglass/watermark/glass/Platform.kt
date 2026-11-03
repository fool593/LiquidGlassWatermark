package com.liquidglass.watermark.glass

import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.compose.ui.geometry.Size

@ChecksSdkIntAtLeast(Build.VERSION_CODES.S)
fun isRenderEffectSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

@ChecksSdkIntAtLeast(Build.VERSION_CODES.TIRAMISU)
fun isRuntimeShaderSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

/** Size.Unspecified throws from width/height; validate its sentinel before access. */
internal fun Size.hasFiniteArea(): Boolean = this != Size.Unspecified &&
    width.isFinite() && height.isFinite() && width > 0f && height > 0f
