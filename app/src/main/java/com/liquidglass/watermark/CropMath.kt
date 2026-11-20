package com.liquidglass.watermark

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * v15.32 自定义裁剪比例工具：预览与导出共用同一中心裁剪几何，保证所见即所得。
 *
 * 语义：cropRatio 是【显示方向】的宽高比（横屏照片选 16:9 即 16/9；旋转 90/270 后
 * 输出尺寸互换，但用户选择的"横幅/竖幅"语义不变——内部按旋转后尺寸换算）。
 */
object CropMath {

    /**
     * 返回在 srcW×srcH（未旋转的原始像素）中，按目标宽高比 aspect 的中心裁剪源矩形。
     * aspect<=0 表示不裁剪 → 返回全图。
     * 注意：调用方负责在旋转后按显示方向给 aspect（见 [aspectFor]）。
     */
    fun centerCropRect(srcW: Int, srcH: Int, aspect: Float): RectF {
        if (aspect <= 0f || srcW <= 0 || srcH <= 0) return RectF(0f, 0f, srcW.toFloat(), srcH.toFloat())
        val srcAspect = srcW.toFloat() / srcH
        return if (srcAspect > aspect) {
            // 源更宽：高不变，裁宽
            val cropW = srcH.toFloat() * aspect
            val left = (srcW - cropW) / 2f
            RectF(left, 0f, left + cropW, srcH.toFloat())
        } else {
            // 源更高：宽不变，裁高
            val cropH = srcW.toFloat() / aspect
            val top = (srcH - cropH) / 2f
            RectF(0f, top, srcW.toFloat(), top + cropH)
        }
    }

    /**
     * 按旋转角换算显示方向的比例：
     * 旋转 90/270 时宽高互换，用户选的 16:9（横幅）实际等价的原始像素比例是 9:16
     * （竖源裁成横画幅）。这里把"显示方向 aspect"转换回"原始像素采样 aspect"。
     */
    fun rawAspect(displayAspect: Float, rotationDeg: Int): Float {
        if (displayAspect <= 0f) return 0f
        val rot = ((rotationDeg % 360) + 360) % 360
        return if (rot % 180 != 0) 1f / displayAspect else displayAspect
    }

    /** 中心裁剪 Bitmap（返回新图；原图不回收，调用方自行管理）。 */
    fun cropBitmap(src: Bitmap, rect: RectF): Bitmap {
        val left = rect.left.roundToInt().coerceIn(0, src.width - 1)
        val top = rect.top.roundToInt().coerceIn(0, src.height - 1)
        val w = rect.width().roundToInt().coerceIn(1, src.width - left)
        val h = rect.height().roundToInt().coerceIn(1, src.height - top)
        if (left == 0 && top == 0 && w == src.width && h == src.height) return src
        return Bitmap.createBitmap(src, left, top, w, h)
    }

    /**
     * 导出输出尺寸（宽/高像素）：按显示方向比例中心裁剪。
     * @param srcW/srcH 源像素（未旋转）
     * @param rotationDeg 用户旋转 0/90/180/270
     * @return 裁剪后输出宽高（已含旋转交换）
     */
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

    /** 旋转后目标 Bitmap 中心裁剪（导出静态图便捷入口）。 */
    fun cropToAspect(src: Bitmap, rotationDeg: Int, aspect: Float): Bitmap {
        val raw = rawAspect(aspect, rotationDeg)
        val rect = centerCropRect(src.width, src.height, raw)
        return cropBitmap(src, rect)
    }

    /**
     * 分步尺寸换算（GIF/视频等比例导出用）：
     * 先按显示比例裁源，再按长边上限缩放，最后按旋转交换。
     * @return Triple(裁后源宽, 裁后源高, 输出宽, 输出高) —— 四元组以 Pair<Pair<Int,Int>,Pair<Int,Int>> 返回
     */
    fun sized(srcW: Int, srcH: Int, rotationDeg: Int, aspect: Float, maxEdge: Int):
            Pair<Pair<Int, Int>, Pair<Int, Int>> {
        val raw = rawAspect(aspect, rotationDeg)
        // 1) 裁剪后源尺寸
        val rect = centerCropRect(srcW, srcH, raw)
        val cw = rect.width().roundToInt().coerceAtLeast(1)
        val ch = rect.height().roundToInt().coerceAtLeast(1)
        // 2) 长边上限缩放
        val sc = if (maxEdge > 0) minOf(1f, maxEdge.toFloat() / maxOf(cw, ch)) else 1f
        val sw = (cw * sc).roundToInt().coerceAtLeast(1)
        val sh = (ch * sc).roundToInt().coerceAtLeast(1)
        // 3) 旋转交换
        val rot = ((rotationDeg % 360) + 360) % 360
        return if (rot % 180 != 0)
            (cw to ch) to (sh to sw)
        else
            (cw to ch) to (sw to sh)
    }
}