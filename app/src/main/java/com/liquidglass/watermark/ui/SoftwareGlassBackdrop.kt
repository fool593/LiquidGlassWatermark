package com.liquidglass.watermark.ui

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntSize
import com.liquidglass.watermark.FastBlur
import com.liquidglass.watermark.darkModeState
import com.liquidglass.watermark.glass.backdrops.LayerBackdrop
import com.liquidglass.watermark.glass.backdrops.softwareBlurEnabled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
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
        val captured = runCatching { scene.graphicsLayer.toImageBitmap() }.getOrNull()
            ?: return@LaunchedEffect
        val image = try { withContext(Dispatchers.Default) {
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
        } } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        scene.softwareImage = image
    }
}
