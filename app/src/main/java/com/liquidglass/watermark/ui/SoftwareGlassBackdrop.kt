package com.liquidglass.watermark.ui

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
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
        scene.softwareImage = blurLayer(scene, 0.5f)
    }
}

@Composable
internal fun PrepareSoftwareGlassBackdropLive(
    backdrop: LayerBackdrop,
    size: IntSize,
    intervalMs: Long = 900L,
    revision: Int = 0,

    isIdle: () -> Boolean = { true },

    blurAmount: () -> Float = { 0.5f },
) {

    val enabled = softwareBlurEnabled || backdrop.forceSoftwareSnapshot
    val currentRevision by rememberUpdatedState(revision)
    val idleCheck by rememberUpdatedState(isIdle)
    val blurState by rememberUpdatedState(blurAmount)

    LaunchedEffect(backdrop, enabled, size) {
        if (!enabled || size.width <= 0 || size.height <= 0) return@LaunchedEffect
        withFrameNanos { }
        withFrameNanos { }
        var interval = intervalMs
        var wasLive = false
        var dirty = true
        var lastRevision = Int.MIN_VALUE

        var lastDark = darkModeState

        var lastBlur = Float.NaN
        while (true) {
            val blurNow = blurState().coerceIn(0f, 1f)
            if (blurNow != lastBlur) {
                lastBlur = blurNow
                dirty = true
            }
            val darkNow = darkModeState
            if (darkNow != lastDark) {
                lastDark = darkNow

                backdrop.softwareImage = null

                delay(420)
                dirty = true
            }
            if (currentRevision != lastRevision) {
                lastRevision = currentRevision
                dirty = true
            }

            if (backdrop.liveMode) {
                wasLive = true
                delay(60)
                continue
            }

            if (wasLive) {
                wasLive = false

                dirty = true
            }

            if (backdrop.capturePaused || !idleCheck()) {
                delay(90)
                continue
            }

            if (!dirty) {
                delay(240)
                continue
            }
            dirty = false
            val started = android.os.SystemClock.elapsedRealtime()
            val image = blurLayer(backdrop, lastBlur)
            val cost = android.os.SystemClock.elapsedRealtime() - started

            if (image != null && !backdrop.liveMode) {
                backdrop.softwareImage = image

                backdrop.snapshotFade = 1f
            }

            interval = if (cost > 150L) 3000L
            else (intervalMs + cost * 10).coerceAtMost(3000L)
            delay(interval)
        }
    }
}

private const val TARGET_LONG = 480

private suspend fun blurLayer(backdrop: LayerBackdrop, blurAmount: Float): ImageBitmap? {
    val captured = runCatching { backdrop.graphicsLayer.toImageBitmap() }.getOrNull()
        ?: return null
    return try {
        withContext(Dispatchers.Default) {
            val original = captured.asAndroidBitmap()
            val scale = minOf(1f, TARGET_LONG / max(original.width, original.height).toFloat())
            val width = (original.width * scale).roundToInt().coerceAtLeast(1)
            val height = (original.height * scale).roundToInt().coerceAtLeast(1)
            val reduced = Bitmap.createScaledBitmap(original, width, height, true)
            val bitmap = reduced.copy(Bitmap.Config.ARGB_8888, true)
            if (reduced !== original) reduced.recycle()

            val radius = (blurAmount.coerceIn(0f, 1f) * minOf(width, height) * 0.048f)
                .roundToInt().coerceIn(12, 24)
            if (radius > 0) {
                val pixels = IntArray(width * height)
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                FastBlur.blur(pixels, width, height, radius)

                val k = 1.12f
                val sat = 1.10f
                for (i in pixels.indices) {
                    val p = pixels[i]
                    val a = p ushr 24
                    var r = (p shr 16) and 0xFF
                    var g = (p shr 8) and 0xFF
                    var b = p and 0xFF
                    r = (((r - 128) * k) + 128).toInt()
                    g = (((g - 128) * k) + 128).toInt()
                    b = (((b - 128) * k) + 128).toInt()

                    val y = (r * 77 + g * 150 + b * 29) shr 8
                    r = (y + (r - y) * sat).toInt()
                    g = (y + (g - y) * sat).toInt()
                    b = (y + (b - y) * sat).toInt()
                    if (r < 0) r = 0 else if (r > 255) r = 255
                    if (g < 0) g = 0 else if (g > 255) g = 255
                    if (b < 0) b = 0 else if (b > 255) b = 255
                    pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }
                bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            }
            bitmap.asImageBitmap()
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}
