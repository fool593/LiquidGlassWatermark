package com.liquidglass.watermark

import kotlin.math.roundToInt

object FastBlur {
    private val scratch = ThreadLocal<IntArray>()

    fun blur(pixels: IntArray, w: Int, h: Int, radius: Int) {
        require(w >= 0 && h >= 0 && w.toLong() * h <= pixels.size)
        if (w <= 2 || h <= 2 || radius < 1) return

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
