package com.liquidglass.watermark

import kotlin.math.roundToInt

/**
 * 全版本通用的纯像素模糊：三轮滑动窗口盒模糊近似高斯模糊。
 * 只用 IntArray 算术，不依赖 RenderScript / RuntimeShader 等任何受版本限制的 API，
 * 因此 Android 8.0(API26) 到 Android 14(API34) 渲染结果完全一致，绝不失效。
 */
object FastBlur {
    private val scratch = ThreadLocal<IntArray>()

    /** 原地模糊 ARGB 像素数组 */
    fun blur(pixels: IntArray, w: Int, h: Int, radius: Int) {
        require(w >= 0 && h >= 0 && w.toLong() * h <= pixels.size)
        if (w <= 2 || h <= 2 || radius < 1) return
        // 三轮盒模糊等价高斯，盒半径换算使视觉半径约等于 radius
        val box = (radius / 1.5f).roundToInt().coerceIn(1, maxOf(w, h))
        val count = w * h
        val tmp = scratch.get()?.takeIf { it.size >= count }
            ?: IntArray(count).also(scratch::set)
        repeat(3) {
            boxH(pixels, tmp, w, h, box)
            boxV(tmp, pixels, w, h, box)
        }
    }

    private fun cha(c: Int) = c ushr 24
    private fun chr(c: Int) = (c shr 16) and 0xFF
    private fun chg(c: Int) = (c shr 8) and 0xFF
    private fun chb(c: Int) = c and 0xFF

    private fun boxH(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val win = r * 2 + 1
        for (y in 0 until h) {
            val row = y * w
            var a = 0; var rr = 0; var gg = 0; var bb = 0
            for (x in -r..r) {
                val c = src[row + x.coerceIn(0, w - 1)]
                a += cha(c); rr += chr(c); gg += chg(c); bb += chb(c)
            }
            for (x in 0 until w) {
                dst[row + x] = ((a / win) shl 24) or
                        ((rr / win) shl 16) or ((gg / win) shl 8) or (bb / win)
                val co = src[row + (x - r).coerceIn(0, w - 1)]
                val ci = src[row + (x + r + 1).coerceIn(0, w - 1)]
                a += cha(ci) - cha(co)
                rr += chr(ci) - chr(co)
                gg += chg(ci) - chg(co)
                bb += chb(ci) - chb(co)
            }
        }
    }

    private fun boxV(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val win = r * 2 + 1
        for (x in 0 until w) {
            var a = 0; var rr = 0; var gg = 0; var bb = 0
            for (y in -r..r) {
                val c = src[x + y.coerceIn(0, h - 1) * w]
                a += cha(c); rr += chr(c); gg += chg(c); bb += chb(c)
            }
            for (y in 0 until h) {
                dst[x + y * w] = ((a / win) shl 24) or
                        ((rr / win) shl 16) or ((gg / win) shl 8) or (bb / win)
                val co = src[x + (y - r).coerceIn(0, h - 1) * w]
                val ci = src[x + (y + r + 1).coerceIn(0, h - 1) * w]
                a += cha(ci) - cha(co)
                rr += chr(ci) - chr(co)
                gg += chg(ci) - chg(co)
                bb += chb(ci) - chb(co)
            }
        }
    }
}
