package com.liquidglass.watermark

/**
 * Android-side motion-photo input parser. Pure byte parsing, so it can run in JVM tests without Android APIs.
 *
 * Handles JPEG files that declare or contain an appended ISO-BMFF video, plus observed vendor
 * markers. Parsing a container does not imply that the source or generated format is accepted by
 * every OEM gallery.
 *
 * 关键点：JPEG 自身绝不可能出现 ISO-BMFF 的 “ftyp” 盒，因此在 JPEG 文件里扫到的 ftyp
 * 必是内嵌视频容器——哪怕它的 major brand 是 heic/hevc/mif1（HEVC 视频常见）也必须采纳，
 * 不能当成“图片 brand”排除，否则会漏掉 vivo 这类把 HEVC 流嵌进 JPG 的动态照片。
 *
 * 只有当主文件本身就是 HEIF（开头即 ftyp 盒）时，才需要排除最外层那个封面 ftyp。
 *
 * 另一种“照片 + 独立视频”双文件：图片里没有视频段，扫不到属正常，由用户直接选配套视频，
 * 在 MainActivity 走“视频即动态源”路径，不经过本类。
 */
object MotionPhoto {

    // 明确已知的视频 ftyp brand（强信号，优先采用）
    private val VIDEO_BRANDS = setOf(
        "isom", "iso2", "iso3", "iso4", "iso5", "iso6", "isoM", "isml",
        "mp41", "mp42", "mp71", "mmp4", "MP4V", "dash", "avc1", "MSNV", "qt  ",
        "M4V ", "M4A ", "3gp5", "3gp4", "3ge6", "3g2a",
        // HEVC/H.265 与 AV1 容器 brand（vivo / 三星动态照片常用）
        "hev1", "hvc1", "hevx", "hev2", "shvc", "hevm", "hevs", "av01", "dby1"
    )
    // 仅当“主文件本身是 HEIF”时才需要排除的封面 brand；JPEG 内嵌路径不使用它
    private val IMAGE_BRANDS = setOf(
        "heic", "heix", "hevc", "heim", "heis", "mif1", "msf1",
        "jp2 ", "jpx ", "jpm", "jpeg", "crx ", "avif"
    )

    // 厂商写在视频段前的 ASCII 标记，命中后在其后就近找 ftyp
    private val VIDEO_MARKERS = arrayOf("MotionPhoto_Data", "MotionPhoto", "MicroVideo", "GCamera")

    private const val F0: Byte = 0x66 // 'f'
    private const val F1: Byte = 0x74 // 't'
    private const val F2: Byte = 0x79 // 'y'
    private const val F3: Byte = 0x70 // 'p'


    /** 返回内嵌 MP4 字节（ftyp 盒起点到文件尾）；不是单文件动态照片返回 null。 */
    fun extractMp4(bytes: ByteArray): ByteArray? {
        if (bytes.size < 32) return null
        val jpeg = isJpeg(bytes)
        // 1) XMP 里直接给出偏移（Google MicroVideoOffset 等）
        xmpOffset(bytes)?.let { s -> sliceIfVideo(bytes, s, jpeg)?.let { return it } }
        // 1.5) XMP MotionPhotoVideoEnd / VideoEnd absolute offsets:
        //      只写视频结束绝对偏移，无 MicroVideoOffset；从 End 前找 ISO-BMFF 盒起点切 [start, end]
        xmpEndOffset(bytes)?.let { e ->
            sliceEndVideo(bytes, e, jpeg)?.let { return it }
        }
        // 2) Vendor markers followed by a nearby ftyp (observed containers).
        for (marker in VIDEO_MARKERS) {
            markerFtyp(bytes, marker)?.let { s -> sliceIfVideo(bytes, s, jpeg)?.let { return it } }
        }
        // 3) 全文件扫描 ftyp（JPEG 不排除 HEVC brand）
        scanVideoFtyp(bytes, jpeg)?.let { return it }
        // 4) 无 ftyp 兜底：部分厂商（vivo 等）内嵌 MP4 段省略 ftyp 盒，
        //    从文件尾向前找 moov/mdat 盒起点（size 字段有效），切出连续尾段
        return tailIsoBmff(bytes)
    }

    private fun isJpeg(b: ByteArray): Boolean =
        b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()

    /** 内容级 MP4 探测：文件以 ISO-BMFF ftyp 开头且 brand 不是图片格式（mime 缺失时的视频兜底） */
    fun looksLikeMp4(bytes: ByteArray): Boolean {
        if (bytes.size < 16) return false
        if (bytes[4] != F0 || bytes[5] != F1 || bytes[6] != F2 || bytes[7] != F3) return false
        val brand = ascii(bytes, 8, 4)
        return printable(brand) && brand !in IMAGE_BRANDS
    }

    /** 解析 XMP 的 MicroVideoOffset（距文件尾字节数，Google 旧规范） */
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

    /** 解析新标准 XMP 的 MotionPhotoVideoEnd / VideoEnd：视频结束的绝对偏移（Android 12+ 相机等） */
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
            // End 是绝对偏移：必须落在文件内且离文件头足够远（32 字节以上才像视频段）
            if (n in 33..bytes.size) return n.toInt()
        }
        return null
    }

    /** 按视频结束偏移切片：在 [0, endOff) 内找最后一个合法的 ISO-BMFF 盒起点（ftyp/moov/mdat，size 有效），
     *  切出 [start, endOff)。JPEG 场景排除纯图片品牌 ftyp，防把 JPEG 段误当视频。 */
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
            // JPEG 场景要求切片起点是 ftyp 或内部有 moov/mdat（尾部含完整视频盒）；
            // 纯 JPEG 尾部二进制随机命中 "moov/mdat" 的模式概率极低，且 32 字节下限已挡小片段
            if (ascii(seg, 4, 4) == "ftyp" || indexOf(seg, "moov".toByteArray()) != null ||
                indexOf(seg, "mdat".toByteArray()) != null) seg else null
        } else seg
    }

    /** 找到厂商标记后，在其后 8KB 内找第一个 ftyp 盒起点 */
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

    /**
     * 扫描所有 ftyp 盒：已知视频 brand 优先、偏移靠后优先；
     * JPEG 主图不排除任何可打印 brand（HEVC 也采纳）；HEIF 主图才排除封面 brand。
     */
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

    /** 无 ftyp 时的兜底：从文件尾向前找 moov/mdat 盒（big-endian size 有效），切出连续尾段 */
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
