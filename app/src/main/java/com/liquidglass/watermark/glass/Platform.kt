package com.liquidglass.watermark.glass

import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.compose.ui.geometry.Size

/**
 * 运行在模拟器（WHPX/SwiftShader 等软件 GPU）时返回 true。
 *
 * 软件 GPU 对 AGSL RuntimeShader / RenderEffect 支持不良，会导致整片闪绿/闪紫——这是
 * 预览期反复出现的老问题。因此在模拟器上，所有玻璃硬件路径必须回退软件实现，且**与用户
 * 选哪个 API 档位（自动/高/低）无关**：即使手动选「高版本API」也强制软件，确保模拟器上
 * 绝不再出现该问题。真机（isEmulator=false）行为完全不变。
 *
 * 结果做一次性缓存，避免被逐帧高频调用时反复走反射。
 */
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
        // 反射读取 ro.kernel.qemu 二次确认（仅在上面的指纹/型号都没命中时才走，性能无忧）
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
