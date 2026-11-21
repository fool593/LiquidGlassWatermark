package com.liquidglass.watermark.ui

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntSize
import com.liquidglass.watermark.FastBlur
import com.liquidglass.watermark.darkModeState
import com.liquidglass.watermark.glass.backdrops.LayerBackdrop
import com.liquidglass.watermark.glass.backdrops.softwareBlurEnabled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/** Blur a capture of the actual static scene once per theme/size, never per control/frame. */
@Composable
internal fun PrepareSoftwareGlassBackdrop(scene: LayerBackdrop, size: IntSize) {
    val enabled = softwareBlurEnabled
    val dark = darkModeState
    LaunchedEffect(scene, enabled, dark, size) {
        scene.softwareImage = null
        if (!enabled || size.width <= 0 || size.height <= 0) return@LaunchedEffect
        // Wait until layerBackdrop has recorded the new theme at its measured size.
        withFrameNanos { }
        withFrameNanos { }
        scene.softwareImage = blurLayer(scene)
    }
}

/**
 * 会动的软模糊取样：每 [intervalMs] 毫秒重新抓一次目标图层，降采样 + FastBlur 后写回
 * [backdrop].softwareImage。
 *
 * 用途：底部 Dock 取样的对象是参数面板（不是静态背景），面板会随滚动 / 切换页签变化。
 * 只抓一次会拿到旧内容；每帧抓会非常贵。折中成 0.5s 一轮，低版本（无 RenderEffect）
 * 上胶囊里看到的仍是**真模糊**的后面内容，而不是 1:1 清晰的第二层 UI。
 */
@Composable
internal fun PrepareSoftwareGlassBackdropLive(
    backdrop: LayerBackdrop,
    size: IntSize,
    intervalMs: Long = 450L
) {
    val enabled = softwareBlurEnabled
    LaunchedEffect(backdrop, enabled, size) {
        backdrop.softwareImage = null
        if (!enabled || size.width <= 0 || size.height <= 0) return@LaunchedEffect
        withFrameNanos { }
        withFrameNanos { }
        while (true) {
            val image = blurLayer(backdrop)
            if (image != null) backdrop.softwareImage = image
            delay(intervalMs)
        }
    }
}

/** 抓一帧 [backdrop] 记录层 → 最长边缩到 480 → FastBlur。失败返回 null（不影响渲染）。 */
private suspend fun blurLayer(backdrop: LayerBackdrop): ImageBitmap? {
    val captured = runCatching { backdrop.graphicsLayer.toImageBitmap() }.getOrNull()
        ?: return null
    return try {
        withContext(Dispatchers.Default) {
            val original = captured.asAndroidBitmap()
            val scale = minOf(1f, 480f / max(original.width, original.height))
            val width = (original.width * scale).roundToInt().coerceAtLeast(1)
            val height = (original.height * scale).roundToInt().coerceAtLeast(1)
            val reduced = Bitmap.createScaledBitmap(original, width, height, true)
            val bitmap = reduced.copy(Bitmap.Config.ARGB_8888, true)
            if (reduced !== original) reduced.recycle()
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            FastBlur.blur(pixels, width, height, 3)
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            bitmap.asImageBitmap()
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}
