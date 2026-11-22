package com.liquidglass.watermark
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import kotlin.math.min

object GifQuant {
    private const val LEVELS = 64
    private const val TOTAL = LEVELS * LEVELS * LEVELS // 262144

    private val pool: java.util.concurrent.ThreadPoolExecutor by lazy {
        val n = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
        java.util.concurrent.ThreadPoolExecutor(
            n, n, 30L, java.util.concurrent.TimeUnit.SECONDS,
            java.util.concurrent.LinkedBlockingQueue(),
            java.util.concurrent.Executors.defaultThreadFactory()
        ).apply { allowCoreThreadTimeOut(true) }
    }

    private const val PAR_ROWS = 8
    private const val PAR_PIXELS = 64 * 1024
    private fun keyOf(r: Int, g: Int, b: Int): Int =
        ((r shr 2) shl 12) or ((g shr 2) shl 6) or (b shr 2)
    class Quantizer {
        private val hist = IntArray(TOTAL)

        private val sumR = LongArray(TOTAL)
        private val sumG = LongArray(TOTAL)
        private val sumB = LongArray(TOTAL)

        private var pxBuf = IntArray(0)
        private fun pxOf(bmp: Bitmap): IntArray {
            val w = bmp.width; val h = bmp.height
            val n = w * h
            if (pxBuf.size < n) pxBuf = IntArray(n)
            bmp.getPixels(pxBuf, 0, w, 0, 0, w, h)
            return pxBuf
        }
        fun feed(bmp: Bitmap) {
            val px = pxOf(bmp)
            for (i in 0 until bmp.width * bmp.height) {
                val c = px[i]

                if (c ushr 24 < 128) continue
                val k = keyOf(c ushr 16 and 0xFF, c ushr 8 and 0xFF, c and 0xFF)
                hist[k]++
                sumR[k] += (c ushr 16 and 0xFF).toLong()
                sumG[k] += (c ushr 8 and 0xFF).toLong()
                sumB[k] += (c and 0xFF).toLong()
            }
        }

        fun build(maxColors: Int = 256): Palette {
            val active = ArrayList<Int>(4096)
            for (k in 0 until TOTAL) if (hist[k] > 0) active.add(k)

            if (active.isEmpty()) active.add(0)
            active.sortByDescending { hist[it] }
            val m = minOf(maxColors.coerceIn(2, 256), active.size + 1).coerceAtLeast(2)
            val keys = IntArray(m)
            val rgb = IntArray(m)
            keys[0] = 0; rgb[0] = 0xFF000000.toInt()
            for (i in 1 until m) {
                val k = active[i - 1]; keys[i] = k

                val cnt = hist[k].toLong().coerceAtLeast(1)
                val cr = (sumR[k] / cnt).toInt()
                val cg = (sumG[k] / cnt).toInt()
                val cb = (sumB[k] / cnt).toInt()
                rgb[i] = (0xFF shl 24) or (cr shl 16) or (cg shl 8) or cb
            }
            val pr = IntArray(m); val pg = IntArray(m); val pb = IntArray(m)
            for (i in 0 until m) {
                pr[i] = keys[i] shr 12 and 63; pg[i] = keys[i] shr 6 and 63; pb[i] = keys[i] and 63
            }
            val map = ByteArray(TOTAL) { 1 }
            for (k in 0 until TOTAL) {
                val r = k shr 12 and 63; val g = k shr 6 and 63; val b = k and 63
                var best = 1; var bd = Int.MAX_VALUE
                var i = 1
                while (i < m) {
                    val dr = r - pr[i]; val dg = g - pg[i]; val dn = b - pb[i]
                    val d = dr * dr + dg * dg + dn * dn
                    if (d < bd) { bd = d; best = i; if (d == 0) break }
                    i++
                }
                map[k] = best.toByte()
            }
            return Palette(rgb, map, pr, pg, pb)
        }
    }
    class Palette(val rgb: IntArray, private val map: ByteArray,
                  private val pr: IntArray, private val pg: IntArray, private val pb: IntArray) {
        private var pxBuf = IntArray(0)

        fun toIndices(bmp: Bitmap, dither: Boolean = true): ByteArray {
            val w = bmp.width; val h = bmp.height
            val n = w * h
            if (pxBuf.size < n) pxBuf = IntArray(n)
            bmp.getPixels(pxBuf, 0, w, 0, 0, w, h)
            val out = ByteArray(n)

            if (!dither) {
                var i = 0
                while (i < n) {
                    val c = pxBuf[i]
                    if (c ushr 24 < 128) { out[i] = 0 } else {
                        val k = ((c ushr 18 and 63) shl 12) or ((c ushr 10 and 63) shl 6) or (c ushr 2 and 63)
                        out[i] = map[k]
                    }
                    i++
                }
                return out
            }

            val w2 = w + 2
            val eR = IntArray(2 * w2); val eG = IntArray(2 * w2); val eB = IntArray(2 * w2)
            var cur = 0
            for (j in 0 until h) {
                val next = cur xor 1
                val rowBase = j * w
                for (i in 0 until w) {
                    val c = pxBuf[rowBase + i]
                    if (c ushr 24 < 128) { out[rowBase + i] = 0; continue }
                    val ri = cur * w2 + i + 1
                    val rq = ((c ushr 16 and 0xFF) + (eR[ri] shr 8)).coerceIn(0, 255)
                    val gq = ((c ushr 8 and 0xFF) + (eG[ri] shr 8)).coerceIn(0, 255)
                    val bq = ((c and 0xFF) + (eB[ri] shr 8)).coerceIn(0, 255)
                    val idx = map[((rq shr 2) shl 12) or
                        ((gq shr 2) shl 6) or (bq shr 2)].toInt() and 0xFF
                    out[rowBase + i] = idx.toByte()

                    val er = rq - (rgb[idx] ushr 16 and 0xFF)
                    val eg = gq - (rgb[idx] ushr 8 and 0xFF)
                    val eb = bq - (rgb[idx] and 0xFF)
                    eR[ri + 1] += er * 112  // 7/16 * 256
                    eG[ri + 1] += eg * 112
                    eB[ri + 1] += eb * 112
                    val n0 = next * w2 + i
                    eR[n0] += er * 48      // 3/16 * 256
                    eG[n0] += eg * 48
                    eB[n0] += eb * 48
                    eR[n0 + 1] += er * 80  // 5/16 * 256
                    eG[n0 + 1] += eg * 80
                    eB[n0 + 1] += eb * 80
                    eR[n0 + 2] += er * 16  // 1/16 * 256
                    eG[n0 + 2] += eg * 16
                    eB[n0 + 2] += eb * 16
                }

                for (i in 0 until w2) {
                    eR[cur * w2 + i] = 0; eG[cur * w2 + i] = 0; eB[cur * w2 + i] = 0
                }
                cur = next
            }
            return out
        }
    }
}
class GifEncoder(private val width: Int, private val height: Int, loop: Boolean = true) {
    private val out = ByteArrayOutputStream(64 * 1024)

    private val lzwArr = IntArray(1 shl 20)
    private val lzwGen = IntArray(1 shl 20)
    private var lzwCurGen = 1
    init {
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))

        writeShort(width); writeShort(height)
        out.write(0); out.write(0); out.write(0)
        if (loop) {
            out.write(0x21); out.write(0xFF); out.write(11)
            out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
            out.write(3); out.write(1); writeShort(0); out.write(0)
        }
    }
    private fun writeShort(v: Int) { out.write(v and 0xFF); out.write(v ushr 8 and 0xFF) }

    fun addFrame(indices: ByteArray, palette: IntArray, frameDelayCs: Int) {
        val w = width; val h = height
        require(w > 0 && h > 0 && indices.size == w * h) { "GIF frame dimensions do not match" }
        require(palette.size in 2..256) { "GIF palette must contain 2..256 colors" }

        out.write(0x21); out.write(0xF9); out.write(4)
        out.write(0x09)
        writeShort(frameDelayCs.coerceIn(1, 1000))
        out.write(0); out.write(0)

        out.write(0x2C); writeShort(0); writeShort(0); writeShort(w); writeShort(h)

        var colors = 2
        var nField = 1
        while (colors < palette.size) { colors *= 2; nField++ }
        out.write(0x80 or (nField - 1))
        var i = 0
        while (i < colors) {
            if (i < palette.size) {
                val c = palette[i]
                out.write(c ushr 16 and 0xFF); out.write(c ushr 8 and 0xFF); out.write(c and 0xFF)
            } else {
                out.write(0); out.write(0); out.write(0)
            }
            i++
        }
        lzw(indices, nField)
    }
    private fun lzw(px: ByteArray, paletteBits: Int) {

        val minCode = paletteBits.coerceIn(2, 8)
        out.write(minCode)
        val clear = 1 shl minCode
        val end = clear + 1
        val body = ByteArrayOutputStream(px.size * 3 / 2 + 64)
        var codeSize = minCode + 1
        var nextCode = end + 1

        lzwCurGen++
        var thisGen = lzwCurGen
        var bitBuf = 0; var bitNum = 0
        fun emit(code: Int) {
            bitBuf = bitBuf or (code shl bitNum); bitNum += codeSize
            while (bitNum >= 8) {
                body.write(bitBuf and 0xFF); bitBuf = bitBuf ushr 8; bitNum -= 8
            }
        }
        emit(clear)
        var prefix = px[0].toInt() and 0xFF
        for (idx in 1 until px.size) {
            val k = px[idx].toInt() and 0xFF
            val key = (prefix shl 8) or k
            if (lzwGen[key] == thisGen) {
                prefix = lzwArr[key]
            } else {
                emit(prefix)
                if (nextCode < 4096) {
                    lzwArr[key] = nextCode
                    lzwGen[key] = thisGen

                    if (nextCode == (1 shl codeSize) && codeSize < 12) codeSize++
                    nextCode++
                } else {
                    emit(clear)

                    lzwCurGen++; thisGen = lzwCurGen
                    codeSize = minCode + 1; nextCode = end + 1
                }
                prefix = k
            }
        }
        emit(prefix); emit(end)
        if (bitNum > 0) body.write(bitBuf and 0xFF)
        val data = body.toByteArray()
        var p = 0
        while (p < data.size) {
            val n = minOf(255, data.size - p)
            out.write(n); out.write(data, p, n); p += n
        }
        out.write(0)
    }
    fun finish(): ByteArray {
        out.write(0x3B)
        return out.toByteArray()
    }
}
