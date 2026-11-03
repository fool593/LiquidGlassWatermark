package com.liquidglass.watermark
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
/**
 * 零依赖 GIF89a 动图编码器 + 6/6/6 色彩量化器。
 * 两遍流程：第一遍逐帧 [Quantizer.feed] 统计直方图建立全局 256 色调色板，
 * 第二遍逐帧 [Quantizer.toIndices] 转索引，再 [GifEncoder.addFrame] 做 LZW 压缩。
 * 全程纯 Kotlin / 软件运算，API26-34 行为一致，任何异常由调用方兜底。
 */
object GifQuant {
    private const val LEVELS = 64
    private const val TOTAL = LEVELS * LEVELS * LEVELS // 262144
    private fun keyOf(r: Int, g: Int, b: Int): Int =
        ((r shr 2) shl 12) or ((g shr 2) shl 6) or (b shr 2)
    class Quantizer {
        private val hist = IntArray(TOTAL)
        private var fed = 0
        // 复用像素缓冲，避免每帧 new IntArray 造成 GC 压力（行为不变）
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
                // 透明背景不入直方图：否则大量 alpha=0 的像素把调色板污染成黑色，画面发糊
                if (c ushr 24 < 128) continue
                hist[keyOf(c ushr 16 and 0xFF, c ushr 8 and 0xFF, c and 0xFF)]++
            }
            fed++
        }

        /**
         * 建立调色板。索引 0 固定为透明色（背景用），其余按频次降序填充；
         * 颜色映射跳过索引 0，保证任何不透明像素都不会误映射成透明。
         */
        fun build(maxColors: Int = 256): Palette {
            val active = ArrayList<Int>(4096)
            for (k in 0 until TOTAL) if (hist[k] > 0) active.add(k)
            // 兜底：所有帧全透明时 active 为空，下面 active[i-1] 会越界；塞黑色键保证至少 1 个实色
            if (active.isEmpty()) active.add(0)
            active.sortByDescending { hist[it] }
            val m = minOf(maxColors.coerceIn(2, 256), active.size + 1).coerceAtLeast(2)
            val keys = IntArray(m)
            val rgb = IntArray(m)
            keys[0] = 0; rgb[0] = 0xFF000000.toInt() // 透明索引色（任意，显示时透明）
            for (i in 1 until m) {
                val k = active[i - 1]; keys[i] = k
                val r6 = k shr 12 and 63; val g6 = k shr 6 and 63; val b6 = k and 63
                rgb[i] = (0xFF shl 24) or (((r6 shl 2) or 2) shl 16) or
                        (((g6 shl 2) or 2) shl 8) or ((b6 shl 2) or 2)
            }
            val pr = IntArray(m); val pg = IntArray(m); val pb = IntArray(m)
            for (i in 0 until m) {
                pr[i] = keys[i] shr 12 and 63; pg[i] = keys[i] shr 6 and 63; pb[i] = keys[i] and 63
            }
            val map = ByteArray(TOTAL) { 1 } // 默认 1，避免 0
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
        /**
         * 转 8-bit 索引，带 Floyd–Steinberg 误差扩散抖动：
         * 6/6/6 量化 + 无抖动会产生明显色带（渐变区发糊），FS 抖动把量化误差
         * 扩散到相邻像素，视觉上以噪声换平滑，观感接近 256 色真实渐变。
         * 抖动走查表（map）+ 两行滚动误差缓冲，开销约为纯查表的 1.3 倍，不放大内存。
         */
        fun toIndices(bmp: Bitmap, dither: Boolean = true): ByteArray {
            val w = bmp.width; val h = bmp.height
            val n = w * h
            if (pxBuf.size < n) pxBuf = IntArray(n)
            bmp.getPixels(pxBuf, 0, w, 0, 0, w, h)
            val out = ByteArray(n)
            if (!dither) {
                for (i in 0 until n) {
                    val c = pxBuf[i]
                    if (c ushr 24 < 128) { out[i] = 0; continue }
                    val k = ((c ushr 18 and 63) shl 12) or ((c ushr 10 and 63) shl 6) or (c ushr 2 and 63)
                    out[i] = map[k]
                }
                return out
            }
            // FS 抖动：误差按 7/16、3/16、5/16、1/16 扩散到 右/左下/下/右下。
            // 定点化（×256）：误差缓冲与扩散全用 Int 移位，避免每像素 ~20 次浮点运算
            // （1080p 全分辨率 GIF 每帧提速 2-3 倍，误差幅度与浮点一致）
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
                    val er = rq - ((pr[idx] shl 2) or 2)
                    val eg = gq - ((pg[idx] shl 2) or 2)
                    val eb = bq - ((pb[idx] shl 2) or 2)
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
                // 当前行误差已用尽，清零复用
                for (i in 0 until w2) {
                    eR[cur * w2 + i] = 0; eG[cur * w2 + i] = 0; eB[cur * w2 + i] = 0
                }
                cur = next
            }
            return out
        }
    }
}
class GifEncoder(private val width: Int, private val height: Int, private val delayCs: Int, loop: Boolean = true) {
    private val out = ByteArrayOutputStream(64 * 1024)
    // 复用 LZW 字典，避免每帧 new HashMap(5000) 造成 GC 压力（行为不变）
    private val dict = HashMap<Int, Int>(5000)
    init {
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        // 逻辑屏幕描述（使用每帧局部色表，全局色表标志=0）
        writeShort(width); writeShort(height)
        out.write(0); out.write(0); out.write(0)
        if (loop) { // Netscape 循环播放扩展
            out.write(0x21); out.write(0xFF); out.write(11)
            out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
            out.write(3); out.write(1); writeShort(0); out.write(0)
        }
    }
    private fun writeShort(v: Int) { out.write(v and 0xFF); out.write(v ushr 8 and 0xFF) }
    fun addFrame(indices: ByteArray, palette: IntArray) { addFrame(indices, palette, delayCs) }
    /** @param frameDelayCs 本帧停留时间（厘秒，1cs=10ms，最小1以支持高帧率） */
    fun addFrame(indices: ByteArray, palette: IntArray, frameDelayCs: Int) {
        val w = width; val h = height
        require(w > 0 && h > 0 && indices.size == w * h) { "GIF frame dimensions do not match" }
        require(palette.size in 2..256) { "GIF palette must contain 2..256 colors" }
        // 图形控制扩展：packed=1 启用透明色（索引 0 为透明，背景不显示黑色）
        out.write(0x21); out.write(0xF9); out.write(4)
        out.write(1)
        writeShort(frameDelayCs.coerceIn(1, 1000))
        out.write(0); out.write(0)
        // 图像描述符
        out.write(0x2C); writeShort(0); writeShort(0); writeShort(w); writeShort(h)
        // 局部色表
        var colors = 2
        var nField = 1
        while (colors < palette.size) { colors *= 2; nField++ }
        out.write(0x80 or (nField - 1)) // 局部色表标志 + 色表大小
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
        // 色表位数 = LZW 最小码长（256 色→8，128 色→7…），小色表文件更小
        val minCode = paletteBits.coerceIn(2, 8)
        out.write(minCode)
        val clear = 1 shl minCode
        val end = clear + 1
        val body = ByteArrayOutputStream(px.size * 3 / 2 + 64)
        var codeSize = minCode + 1
        var nextCode = end + 1
        fun reset() {
            dict.clear(); codeSize = minCode + 1; nextCode = end + 1
        }
        var bitBuf = 0; var bitNum = 0
        fun emit(code: Int) {
            bitBuf = bitBuf or (code shl bitNum); bitNum += codeSize
            while (bitNum >= 8) {
                body.write(bitBuf and 0xFF); bitBuf = bitBuf ushr 8; bitNum -= 8
            }
        }
        reset(); emit(clear)
        var prefix = px[0].toInt() and 0xFF
        for (idx in 1 until px.size) {
            val k = px[idx].toInt() and 0xFF
            val key = (prefix shl 8) or k
            val found = dict[key]
            if (found != null) {
                prefix = found
            } else {
                emit(prefix)
                if (nextCode < 4096) {
                    dict[key] = nextCode
                    // 刚分配的码达到当前码长上限时升位（必须先判断再自增，否则错位）
                    if (nextCode == (1 shl codeSize) && codeSize < 12) codeSize++
                    nextCode++
                } else {
                    emit(clear); reset()
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
        out.write(0) // 子块结束
    }
    fun finish(): ByteArray {
        out.write(0x3B)
        return out.toByteArray()
    }
}
