package com.liquidglass.watermark.glass

import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.compose.ui.geometry.Size

private var emulatorCached: Boolean? = null
internal fun isEmulator(): Boolean {
    emulatorCached?.let { return it }
    val fingerprint = Build.FINGERPRINT.orEmpty().lowercase()
    val model = Build.MODEL.orEmpty().lowercase()
    val brand = Build.BRAND.orEmpty().lowercase()
    val device = Build.DEVICE.orEmpty().lowercase()
    val product = Build.PRODUCT.orEmpty().lowercase()
    val manufacturer = Build.MANUFACTURER.orEmpty().lowercase()
    val buildKeys = Build.getRadioVersion().orEmpty() + fingerprint
    val hit = fingerprint.contains("generic") || fingerprint.contains("emulator") ||
            fingerprint.contains("goldfish") || fingerprint.contains("sdk_gphone") ||
            model.contains("emulator") || model.contains("google_sdk") ||
            model.contains("android sdk") || brand.startsWith("generic") ||
            device.contains("generic") || device.contains("emulator") ||
            device.contains("goldfish") || product.contains("sdk") ||
            product.contains("emulator") || product.contains("generic") ||
            manufacturer.contains("genymotion") || buildKeys.contains("test-keys")
    val result = if (hit) {
        true
    } else {

        try {
            val cls = Class.forName("android.os.SystemProperties")
            val get = cls.getMethod("get", String::class.java, String::class.java)
            "1" == get.invoke(null, "ro.kernel.qemu", "0") as String
        } catch (_: Throwable) { false }
    }
    emulatorCached = result
    return result
}

@ChecksSdkIntAtLeast(Build.VERSION_CODES.S)
fun isRenderEffectSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !isEmulator()

@ChecksSdkIntAtLeast(Build.VERSION_CODES.TIRAMISU)
fun isRuntimeShaderSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !isEmulator()

/** Size.Unspecified throws from width/height; validate its sentinel before access. */
internal fun Size.hasFiniteArea(): Boolean = this != Size.Unspecified &&
    width.isFinite() && height.isFinite() && width > 0f && height > 0f
