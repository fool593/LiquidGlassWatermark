package com.liquidglass.watermark

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object CropMath {

    fun centerCropRect(srcW: Int, srcH: Int, aspect: Float): RectF {
        if (aspect <= 0f || srcW <= 0 || srcH <= 0) return RectF(0f, 0f, srcW.toFloat(), srcH.toFloat())
        val srcAspect = srcW.toFloat() / srcH
        return if (srcAspect > aspect) {

            val cropW = srcH.toFloat() * aspect
            val left = (srcW - cropW) / 2f
            RectF(left, 0f, left + cropW, srcH.toFloat())
        } else {

            val cropH = srcW.toFloat() / aspect
            val top = (srcH - cropH) / 2f
            RectF(0f, top, srcW.toFloat(), top + cropH)
        }
    }

    fun rawAspect(displayAspect: Float, rotationDeg: Int): Float {
        if (displayAspect <= 0f) return 0f
        val rot = ((rotationDeg % 360) + 360) % 360
        return if (rot % 180 != 0) 1f / displayAspect else displayAspect
    }

    fun cropBitmap(src: Bitmap, rect: RectF): Bitmap {
        val left = rect.left.roundToInt().coerceIn(0, src.width - 1)
        val top = rect.top.roundToInt().coerceIn(0, src.height - 1)
        val w = rect.width().roundToInt().coerceIn(1, src.width - left)
        val h = rect.height().roundToInt().coerceIn(1, src.height - top)
        if (left == 0 && top == 0 && w == src.width && h == src.height) return src
        return Bitmap.createBitmap(src, left, top, w, h)
    }

    fun outputSize(srcW: Int, srcH: Int, rotationDeg: Int, aspect: Float): Pair<Int, Int> {
        if (aspect <= 0f) {
            val rot = ((rotationDeg % 360) + 360) % 360
            return if (rot % 180 != 0) srcH to srcW else srcW to srcH
        }
        val raw = rawAspect(aspect, rotationDeg)
        val rect = centerCropRect(srcW, srcH, raw)
        val cropW = rect.width().roundToInt().coerceAtLeast(1)
        val cropH = rect.height().roundToInt().coerceAtLeast(1)
        val rot = ((rotationDeg % 360) + 360) % 360
        return if (rot % 180 != 0) cropH to cropW else cropW to cropH
    }

    fun cropToAspect(src: Bitmap, rotationDeg: Int, aspect: Float): Bitmap {
        val raw = rawAspect(aspect, rotationDeg)
        val rect = centerCropRect(src.width, src.height, raw)
        return cropBitmap(src, rect)
    }

    fun sized(srcW: Int, srcH: Int, rotationDeg: Int, aspect: Float, maxEdge: Int):
            Pair<Pair<Int, Int>, Pair<Int, Int>> {
        val raw = rawAspect(aspect, rotationDeg)

        val rect = centerCropRect(srcW, srcH, raw)
        val cw = rect.width().roundToInt().coerceAtLeast(1)
        val ch = rect.height().roundToInt().coerceAtLeast(1)

        val sc = if (maxEdge > 0) minOf(1f, maxEdge.toFloat() / maxOf(cw, ch)) else 1f
        val sw = (cw * sc).roundToInt().coerceAtLeast(1)
        val sh = (ch * sc).roundToInt().coerceAtLeast(1)

        val rot = ((rotationDeg % 360) + 360) % 360
        return if (rot % 180 != 0)
            (cw to ch) to (sh to sw)
        else
            (cw to ch) to (sw to sh)
    }
}