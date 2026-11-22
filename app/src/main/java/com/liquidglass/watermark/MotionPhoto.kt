package com.liquidglass.watermark

object MotionPhoto {

    private val VIDEO_BRANDS = setOf(
        "isom", "iso2", "iso3", "iso4", "iso5", "iso6", "isoM", "isml",
        "mp41", "mp42", "mp71", "mmp4", "MP4V", "dash", "avc1", "MSNV", "qt  ",
        "M4V ", "M4A ", "3gp5", "3gp4", "3ge6", "3g2a",

        "hev1", "hvc1", "hevx", "hev2", "shvc", "hevm", "hevs", "av01", "dby1"
    )

    private val IMAGE_BRANDS = setOf(
        "heic", "heix", "hevc", "heim", "heis", "mif1", "msf1",
        "jp2 ", "jpx ", "jpm", "jpeg", "crx ", "avif"
    )

    private val VIDEO_MARKERS = arrayOf("MotionPhoto_Data", "MotionPhoto", "MicroVideo", "GCamera")

    private const val F0: Byte = 0x66 // 'f'
    private const val F1: Byte = 0x74 // 't'
    private const val F2: Byte = 0x79 // 'y'
    private const val F3: Byte = 0x70 // 'p'

    fun extractMp4(bytes: ByteArray): ByteArray? {
        if (bytes.size < 32) return null
        val jpeg = isJpeg(bytes)

        xmpOffset(bytes)?.let { s -> sliceIfVideo(bytes, s, jpeg)?.let { return it } }
        // 1.5) XMP MotionPhotoVideoEnd / VideoEnd absolute offsets:

        xmpEndOffset(bytes)?.let { e ->
            sliceEndVideo(bytes, e, jpeg)?.let { return it }
        }
        // 2) Vendor markers followed by a nearby ftyp (observed containers).
        for (marker in VIDEO_MARKERS) {
            markerFtyp(bytes, marker)?.let { s -> sliceIfVideo(bytes, s, jpeg)?.let { return it } }
        }

        scanVideoFtyp(bytes, jpeg)?.let { return it }

        return tailIsoBmff(bytes)
    }

    private fun isJpeg(b: ByteArray): Boolean =
        b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()

    fun looksLikeMp4(bytes: ByteArray): Boolean {
        if (bytes.size < 16) return false
        if (bytes[4] != F0 || bytes[5] != F1 || bytes[6] != F2 || bytes[7] != F3) return false
        val brand = ascii(bytes, 8, 4)
        return printable(brand) && brand !in IMAGE_BRANDS
    }

    private fun xmpOffset(bytes: ByteArray): Int? {
        val kb = "MicroVideoOffset".toByteArray(Charsets.US_ASCII)
        val idx = indexOf(bytes, kb) ?: return null
        var i = idx + kb.size
        val end = minOf(bytes.size, i + 64)
        while (i < end && !(bytes[i] in '0'.code.toByte()..'9'.code.toByte())) i++
        if (i >= end) return null
        var n = 0L
        while (i < end && bytes[i] in '0'.code.toByte()..'9'.code.toByte()) {
            n = n * 10 + (bytes[i] - '0'.code.toByte()); i++
            if (n > bytes.size) return null
        }
        if (n <= 0L || n >= bytes.size) return null
        return bytes.size - n.toInt()
    }

    private fun xmpEndOffset(bytes: ByteArray): Int? {
        for (key in arrayOf("MotionPhotoVideoEnd", "VideoEnd")) {
            val kb = key.toByteArray(Charsets.US_ASCII)
            val idx = indexOf(bytes, kb) ?: continue
            var i = idx + kb.size
            val end = minOf(bytes.size, i + 64)
            while (i < end && !(bytes[i] in '0'.code.toByte()..'9'.code.toByte())) i++
            if (i >= end) continue
            var n = 0L
            while (i < end && bytes[i] in '0'.code.toByte()..'9'.code.toByte()) {
                n = n * 10 + (bytes[i] - '0'.code.toByte()); i++
                if (n > bytes.size) return null
            }

            if (n in 33..bytes.size) return n.toInt()
        }
        return null
    }

    private fun sliceEndVideo(bytes: ByteArray, endOff: Int, jpeg: Boolean): ByteArray? {
        var best = -1
        var i = endOff - 4
        while (i >= 8) {
            val t = ascii(bytes, i, 4)
            if (t == "moov" || t == "mdat" || t == "ftyp") {
                val s = ((bytes[i - 4].toInt() and 255) shl 24) or
                    ((bytes[i - 3].toInt() and 255) shl 16) or
                    ((bytes[i - 2].toInt() and 255) shl 8) or
                    (bytes[i - 1].toInt() and 255)
                val start = i - 4
                if (s >= 8 && start + s <= endOff) {
                    if (best < 0 || start < best) best = start
                }
            }
            i--
        }
        if (best < 0 || endOff - best < 32) return null
        val seg = bytes.copyOfRange(best, endOff)
        return if (jpeg) {

            if (ascii(seg, 4, 4) == "ftyp" || indexOf(seg, "moov".toByteArray()) != null ||
                indexOf(seg, "mdat".toByteArray()) != null) seg else null
        } else seg
    }

    private fun markerFtyp(bytes: ByteArray, marker: String): Int? {
        val mb = marker.toByteArray(Charsets.US_ASCII)
        val k = indexOf(bytes, mb) ?: return null
        var i = k + mb.size
        val end = minOf(bytes.size - 12, i + 8192)
        while (i <= end) {
            if (bytes[i] == F0 && bytes[i + 1] == F1 && bytes[i + 2] == F2 && bytes[i + 3] == F3) {
                val boxStart = i - 4
                if (boxStart >= 0) return boxStart
            }
            i++
        }
        return null
    }

    private fun scanVideoFtyp(bytes: ByteArray, jpeg: Boolean): ByteArray? {
        val known = ArrayList<Int>()
        val any = ArrayList<Int>()
        var i = 4
        while (i + 12 <= bytes.size - 4) {
            if (bytes[i] == F0 && bytes[i + 1] == F1 && bytes[i + 2] == F2 && bytes[i + 3] == F3) {
                val boxStart = i - 4
                val brand = ascii(bytes, i + 4, 4)
                if (printable(brand) && (jpeg || brand !in IMAGE_BRANDS)) {
                    if (brand in VIDEO_BRANDS) known.add(boxStart) else any.add(boxStart)
                }
                i += 4
            } else i++
        }
        known.sortDescending(); any.sortDescending()
        for (s in known) sliceIfVideo(bytes, s, jpeg)?.let { return it }
        for (s in any) sliceIfVideo(bytes, s, jpeg)?.let { return it }
        return null
    }

    private fun sliceIfVideo(bytes: ByteArray, start: Int, jpeg: Boolean): ByteArray? {
        if (start < 0 || start + 12 > bytes.size) return null
        if (bytes[start + 4] != F0 || bytes[start + 5] != F1 ||
            bytes[start + 6] != F2 || bytes[start + 7] != F3
        ) return null
        val brand = ascii(bytes, start + 8, 4)
        if (!printable(brand)) return null
        if (!jpeg && brand in IMAGE_BRANDS) return null
        if (bytes.size - start < 32) return null
        return bytes.copyOfRange(start, bytes.size)
    }

    private fun tailIsoBmff(bytes: ByteArray): ByteArray? {
        var best = -1
        var i = bytes.size - 4
        while (i >= 8) {
            val t = ascii(bytes, i, 4)
            if (t == "moov" || t == "mdat") {
                val s = ((bytes[i - 4].toInt() and 255) shl 24) or
                    ((bytes[i - 3].toInt() and 255) shl 16) or
                    ((bytes[i - 2].toInt() and 255) shl 8) or
                    (bytes[i - 1].toInt() and 255)
                val start = i - 4
                if (s >= 8 && start + s <= bytes.size) {
                    if (best < 0 || start < best) best = start
                }
            }
            i--
        }
        if (best < 0 || bytes.size - best < 32) return null
        return bytes.copyOfRange(best, bytes.size)
    }

    private fun printable(s: String): Boolean =
        s.length == 4 && s.all { it.code in 0x20..0x7E }

    private fun ascii(b: ByteArray, from: Int, len: Int): String {
        if (from < 0 || from + len > b.size) return ""
        return String(b, from, len, Charsets.US_ASCII)
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int? {
        outer@ for (k in 0..hay.size - needle.size) {
            for (m in needle.indices) if (hay[k + m] != needle[m]) continue@outer
            return k
        }
        return null
    }
}
