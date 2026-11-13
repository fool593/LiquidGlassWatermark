package com.liquidglass.watermark.core.gif

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trips [GifEncoder] through an independent GIF89a decoder written for these tests.
 *
 * Encoding bugs in LZW (code-width timing, clear-code handling, sub-block framing) produce files
 * that still start with "GIF89a" and therefore look fine to a smoke test, so the only meaningful
 * check is decoding the pixels back out.
 */
class GifEncoderTest {

    private val width = 37
    private val height = 23

    private fun frame(fn: (Int, Int) -> Int): IntArray =
        IntArray(width * height) { i -> fn(i % width, i / width) }

    @Test
    fun `header trailer and dimensions are valid`() {
        val encoder = GifEncoder(width, height)
        encoder.addFrame(frame { x, y -> if ((x + y) % 2 == 0) 0xFF0000 else 0x0000FF }, 10)
        val gif = encoder.finish()

        assertEquals("GIF89a", String(gif, 0, 6, Charsets.US_ASCII))
        assertEquals(0x3B.toByte(), gif[gif.size - 1])
        // Logical screen descriptor, little endian.
        val w = (gif[6].toInt() and 0xFF) or ((gif[7].toInt() and 0xFF) shl 8)
        val h = (gif[8].toInt() and 0xFF) or ((gif[9].toInt() and 0xFF) shl 8)
        assertEquals(width, w)
        assertEquals(height, h)
    }

    @Test
    fun `single flat frame decodes back to the same colour`() {
        val color = 0xFF3366CC.toInt()
        val encoder = GifEncoder(width, height)
        encoder.addFrame(frame { _, _ -> color }, 5)
        val decoded = GifDecoder.decode(encoder.finish())

        assertEquals(1, decoded.frames.size)
        assertEquals(width, decoded.frames[0].width)
        assertEquals(height, decoded.frames[0].height)
        // A single-colour image must survive quantization exactly.
        for (pixel in decoded.frames[0].pixels) {
            assertEquals(color.toLong() and 0xFFFFFF, pixel.toLong() and 0xFFFFFF)
        }
        assertEquals(5, decoded.frames[0].delayCentiseconds)
    }

    @Test
    fun `gradient survives quantization within tolerance`() {
        val encoder = GifEncoder(width, height)
        encoder.addFrame(frame { x, y -> ((x * 255 / width) shl 16) or ((y * 255 / height) shl 8) }, 4)
        val decoded = GifDecoder.decode(encoder.finish())
        val pixels = decoded.frames[0].pixels

        var worst = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val expectedR = x * 255 / width
                val expectedG = y * 255 / height
                val actual = pixels[y * width + x]
                worst = maxOf(
                    worst,
                    kotlin.math.abs(expectedR - ((actual shr 16) and 0xFF)),
                    kotlin.math.abs(expectedG - ((actual shr 8) and 0xFF)),
                )
            }
        }
        // Median cut plus 4x4 ordered dithering keeps error well under one 8-step bucket.
        assertTrue("worst channel error was $worst", worst <= 32)
    }

    @Test
    fun `multi frame gif keeps frame order and count`() {
        val encoder = GifEncoder(width, height)
        val colors = listOf(0xFF0000, 0x00FF00, 0x0000FF)
        colors.forEach { encoder.addFrame(frame { _, _ -> it }, 7) }
        val decoded = GifDecoder.decode(encoder.finish())

        assertEquals(3, decoded.frames.size)
        decoded.frames.forEachIndexed { index, f ->
            val expected = colors[index]
            val actual = f.pixels[0]
            assertEquals(expected.toLong() and 0xFFFFFF, actual.toLong() and 0xFFFFFF)
            assertEquals(7, f.delayCentiseconds)
        }
    }

    @Test
    fun `large random image stresses the lzw dictionary without corruption`() {
        // Forces dictionary growth and code-width increases, then an exact round-trip: the palette is
        // built from colours that the 5-bit histogram represents exactly (multiples of 32/64), and
        // dithering is off, so any difference at all would be an LZW or sub-block framing bug rather
        // than quantization error. A quarter of the frame is one long run so the compressor also has to
        // emit the clear code when its dictionary fills.
        val w = 400
        val h = 300
        val palette = IntArray(256) { i ->
            (((i and 0x07) * 32) shl 16) or
                ((((i shr 3) and 0x07) * 32) shl 8) or
                (((i shr 6) and 0x03) * 64)
        }
        val random = java.util.Random(1234)
        val runLength = w * h / 4
        val argb = IntArray(w * h) { i ->
            val c = if (i < runLength) palette[17] else palette[random.nextInt(palette.size)]
            c or 0xFF000000.toInt()
        }

        val encoder = GifEncoder(width = w, height = h, dither = false)
        encoder.addFrame(argb, 8)
        val decoded = GifDecoder.decode(encoder.finish())

        assertEquals(1, decoded.frames.size)
        assertEquals(w * h, decoded.frames[0].pixels.size)
        var worst = 0
        for (i in argb.indices) {
            val a = argb[i]
            val b = decoded.frames[0].pixels[i]
            worst = maxOf(
                worst,
                kotlin.math.abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)),
                kotlin.math.abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)),
                kotlin.math.abs((a and 0xFF) - (b and 0xFF)),
            )
        }
        assertEquals("LZW round-trip must be lossless for an exact palette", 0, worst)
    }

    @Test
    fun `netscape looping extension is present`() {
        val encoder = GifEncoder(width, height)
        encoder.addFrame(frame { _, _ -> 0x123456 }, 3)
        val gif = encoder.finish()
        val text = String(gif, 0, minOf(gif.size, 256), Charsets.ISO_8859_1)
        assertTrue("missing NETSCAPE2.0 loop block", text.contains("NETSCAPE2.0"))
    }
}

/** Minimal independent GIF89a decoder used only by tests. */
internal object GifDecoder {

    class Frame(val pixels: IntArray, val width: Int, val height: Int, val delayCentiseconds: Int)
    class Result(val frames: List<Frame>)

    fun decode(bytes: ByteArray): Result {
        var p = 0
        require(String(bytes, 0, 6, Charsets.US_ASCII) == "GIF89a") { "not a GIF89a stream" }
        p = 6
        val screenWidth = readShort(bytes, p); p += 2
        val screenHeight = readShort(bytes, p); p += 2
        val packed = bytes[p].toInt() and 0xFF; p += 1
        p += 2 // background colour index, pixel aspect ratio

        var globalTable: IntArray? = null
        if (packed and 0x80 != 0) {
            val size = 1 shl ((packed and 0x07) + 1)
            globalTable = readColorTable(bytes, p, size); p += size * 3
        }

        val frames = mutableListOf<Frame>()
        var delay = 0
        var pendingTransparent = -1

        while (p < bytes.size) {
            when (val block = bytes[p].toInt() and 0xFF) {
                0x3B -> return Result(frames)
                0x21 -> { // extension
                    val label = bytes[p + 1].toInt() and 0xFF
                    p += 2
                    if (label == 0xF9) {
                        // Graphic control extension: [size][flags][delay lo][delay hi][index]
                        // [terminator]. It is a fixed-length block rather than a sub-block chain, so it
                        // must NOT go through skipSubBlocks — but the walk still has to step over the
                        // size byte, the payload and the terminator.
                        val size = bytes[p].toInt() and 0xFF
                        val flags = bytes[p + 1].toInt() and 0xFF
                        delay = readShort(bytes, p + 2)
                        pendingTransparent = if (flags and 0x01 != 0) bytes[p + 4].toInt() and 0xFF else -1
                        p += size + 2
                    } else {
                        p = skipSubBlocks(bytes, p)
                    }
                }
                0x2C -> { // image descriptor
                    val left = readShort(bytes, p + 1)
                    val top = readShort(bytes, p + 3)
                    val w = readShort(bytes, p + 5)
                    val h = readShort(bytes, p + 7)
                    val imagePacked = bytes[p + 9].toInt() and 0xFF
                    p += 10

                    var table = globalTable
                    if (imagePacked and 0x80 != 0) {
                        val size = 1 shl ((imagePacked and 0x07) + 1)
                        table = readColorTable(bytes, p, size); p += size * 3
                    }
                    requireNotNull(table) { "frame has neither local nor global colour table" }

                    val minCodeSize = bytes[p].toInt() and 0xFF
                    p += 1
                    val data = readSubBlocks(bytes, p)
                    p = skipSubBlocks(bytes, p)

                    val lzw = lzwDecode(data, minCodeSize)
                    val target = IntArray(screenWidth * screenHeight) { 0xFF000000.toInt() }
                    for (i in lzw.indices) {
                        val x = left + (i % w)
                        val y = top + (i / w)
                        if (x < screenWidth && y < screenHeight) {
                            val index = lzw[i]
                            target[y * screenWidth + x] =
                                if (index == pendingTransparent) 0x00000000
                                else 0xFF000000.toInt() or table[index % table.size]
                        }
                    }
                    frames.add(Frame(target, w, h, delay))
                    delay = 0
                    pendingTransparent = -1
                }
                else -> return Result(frames)
            }
        }
        return Result(frames)
    }

    private fun readColorTable(bytes: ByteArray, offset: Int, entries: Int): IntArray =
        IntArray(entries) { i ->
            val o = offset + i * 3
            ((bytes[o].toInt() and 0xFF) shl 16) or
                ((bytes[o + 1].toInt() and 0xFF) shl 8) or
                (bytes[o + 2].toInt() and 0xFF)
        }

    private fun readShort(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    /** Returns the index just past the terminating zero-length sub-block. */
    private fun skipSubBlocks(bytes: ByteArray, start: Int): Int {
        var p = start
        while (p < bytes.size) {
            val size = bytes[p].toInt() and 0xFF
            p += 1
            if (size == 0) return p
            p += size
        }
        return p
    }

    private fun readSubBlocks(bytes: ByteArray, start: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var p = start
        while (p < bytes.size) {
            val size = bytes[p].toInt() and 0xFF
            p += 1
            if (size == 0) break
            out.write(bytes, p, size)
            p += size
        }
        return out.toByteArray()
    }

    private fun lzwDecode(data: ByteArray, minCodeSize: Int): IntArray {
        val clearCode = 1 shl minCodeSize
        val endCode = clearCode + 1
        var codeSize = minCodeSize + 1
        var nextCode = endCode + 1

        var dictionaries = ArrayList<IntArray>(4096)
        fun resetDictionary() {
            dictionaries = ArrayList(4096)
            for (i in 0 until clearCode) dictionaries.add(intArrayOf(i))
            dictionaries.add(intArrayOf()) // clear
            dictionaries.add(intArrayOf()) // end
            nextCode = endCode + 1
            codeSize = minCodeSize + 1
        }
        resetDictionary()

        val out = java.io.ByteArrayOutputStream()
        var bitBuffer = 0
        var bitCount = 0
        var previous: IntArray? = null
        var position = 0

        while (position <= data.size) {
            while (bitCount < codeSize) {
                if (position >= data.size) break
                bitBuffer = bitBuffer or ((data[position].toInt() and 0xFF) shl bitCount)
                bitCount += 8
                position++
            }
            if (bitCount < codeSize) break
            val code = bitBuffer and ((1 shl codeSize) - 1)
            bitBuffer = bitBuffer ushr codeSize
            bitCount -= codeSize

            when {
                code == clearCode -> { resetDictionary(); previous = null }
                code == endCode -> return out.toByteArray().toIndices()
                code < dictionaries.size -> {
                    val entry = dictionaries[code]
                    entry.forEach { out.write(it) }
                    if (previous != null && nextCode < 4096) {
                        dictionaries.add(previous + entry[0])
                        nextCode++
                        // The decoder's dictionary trails the encoder's by exactly one entry, because
                        // the encoder creates an entry as soon as it emits a code while the decoder
                        // can only create it after reading the code that follows. The width therefore
                        // has to be widened one entry earlier here than the encoder widens it.
                        if (nextCode == (1 shl codeSize) - 1 && codeSize < 12) codeSize++
                    }
                    previous = entry
                }
                code == nextCode && previous != null -> {
                    val entry = previous + previous[0]
                    entry.forEach { out.write(it) }
                    if (nextCode < 4096) {
                        dictionaries.add(entry)
                        nextCode++
                        if (nextCode == (1 shl codeSize) - 1 && codeSize < 12) codeSize++
                    }
                    previous = entry
                }
                else -> error("invalid LZW code $code (dictionary size ${dictionaries.size})")
            }
        }
        return out.toByteArray().toIndices()
    }

    /** LZW output is a byte stream of palette indices; the caller wants them as unsigned ints. */
    private fun ByteArray.toIndices(): IntArray = IntArray(size) { this[it].toInt() and 0xFF }
}
