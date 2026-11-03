package com.liquidglass.watermark

import java.util.concurrent.ThreadLocalRandom

/**
 * vivo/iQOO 动态照片私有配对协议（按真机样本 1:1 仿写）。
 *
 * 样本：IMG_20260915_015441.jpg + IMG_20260915_015441.mp4（iQOO Neo，OriginOS）
 *  - jpg：JPEG EOI 后追加 [4B BE payloadLen(含自身4B)][28B live_photo_id ASCII][4B FF FF FF FF][11B trailer]
 *  - mp4：文件末尾追加 [F8 00][00×16][2F][28B live_photo_id ASCII][4B FF FF FF FF][11B trailer]
 *  - 两个文件必须使用同一个 live_photo_id 与同一个 trailer；vivo 相册靠 ID 一致性配对合并
 *
 * 说明：此为基于真实样本与公开逆向资料的兼容性实现，非 vivo 官方公开规范；
 *      不同 OriginOS/Funtouch OS 版本可能存在差异（以用户手机样本为 Golden Sample）。
 */
object VivoLivePhoto {

    /** 样本尾部固定 11 字节（Golden Sample 原样保留） */
    val TRAILER = byteArrayOf(
        0x1b, 0x2a, 0x39, 0x48, 0x57, 0x66, 0x75,
        0x84.toByte(), 0x93.toByte(), 0xa2.toByte(), 0xb3.toByte()
    )

    /** mp4 尾部前缀（样本原样）：F8 00 + 16 字节 00 + 1 字节长度 2F */
    private val MP4_PREFIX = byteArrayOf(
        0xF8.toByte(), 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x2F
    )

    private const val ID_LEN = 28

    /** 生成 28 位 live_photo_id：13 位毫秒时间戳 + 7 位随机 hex + 8 个 0（样本同构） */
    fun generateId(): String {
        val ts = System.currentTimeMillis().toString()
        val rand = java.lang.Long.toHexString(ThreadLocalRandom.current().nextLong(0x0L, 0x10000000L))
        val pad = "0000000".substring(rand.length) + rand
        return ts + pad + "00000000"
    }

    /** jpg 尾部 payload：[4B BE len=47][ID 28B][FF×4][trailer 11B] */
    fun jpgTail(id: String): ByteArray {
        val body = payloadBody(id)
        val out = ByteArray(4 + body.size)
        out[0] = ((body.size + 4) ushr 24 and 0xFF).toByte()
        out[1] = ((body.size + 4) ushr 16 and 0xFF).toByte()
        out[2] = ((body.size + 4) ushr 8 and 0xFF).toByte()
        out[3] = ((body.size + 4) and 0xFF).toByte()
        System.arraycopy(body, 0, out, 4, body.size)
        return out
    }

    /** mp4 尾部块：[F8 00][00×16][2F][ID 28B][FF×4][trailer 11B] */
    fun mp4Tail(id: String): ByteArray {
        val body = payloadBody(id)
        val out = ByteArray(MP4_PREFIX.size + body.size)
        System.arraycopy(MP4_PREFIX, 0, out, 0, MP4_PREFIX.size)
        System.arraycopy(body, 0, out, MP4_PREFIX.size, body.size)
        return out
    }

    /**
     * 读取侧：从 JPEG 字节提取 vivo live_photo_id。
     * 结构：最后一个 EOI(FF D9) 后应为 [4B BE len][28B ID][FF FF FF FF][11B trailer]。
     * @return 28 位 ID；不符合 vivo 结构返回 null
     */
    fun extractJpgId(jpeg: ByteArray): String? {
        if (jpeg.size < 4 || jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) return null
        // 最后一个 EOI
        var eoi = -1
        for (i in 0 until jpeg.size - 1) {
            if (jpeg[i] == 0xFF.toByte() && jpeg[i + 1] == 0xD9.toByte()) eoi = i
        }
        if (eoi < 0) return null
        val tail = jpeg.copyOfRange(eoi + 2, jpeg.size)
        // [4B BE len][body]：len = 4 + body.size，body = ID + FF×4 + trailer
        if (tail.size < 4) return null
        val len = ((tail[0].toInt() and 0xFF) shl 24) or ((tail[1].toInt() and 0xFF) shl 16) or
            ((tail[2].toInt() and 0xFF) shl 8) or (tail[3].toInt() and 0xFF)
        if (len != tail.size) return null
        val body = tail.copyOfRange(4, tail.size)
        return parseBody(body)
    }

    /**
     * 读取侧：从 MP4 字节提取 vivo live_photo_id。
     * 结构：文件末尾 [F8 00][00×16][2F][28B ID][FF FF FF FF][11B trailer]。
     * @return 28 位 ID；不符合 vivo 结构返回 null
     */
    fun extractMp4Id(mp4: ByteArray): String? {
        if (mp4.size < MP4_PREFIX.size + 43) return null
        // 末尾必须是 MP4_PREFIX + body
        val bodyStart = mp4.size - 43
        val prefix = mp4.copyOfRange(bodyStart - MP4_PREFIX.size, bodyStart)
        if (!prefix.contentEquals(MP4_PREFIX)) return null
        return parseBody(mp4.copyOfRange(bodyStart, mp4.size))
    }

    /** 配对检查：jpg 与 mp4 携带同一个 live_photo_id（单测与合成侧复用） */
    fun isPaired(jpeg: ByteArray, mp4: ByteArray): Boolean {
        val a = extractJpgId(jpeg) ?: return false
        val b = extractMp4Id(mp4) ?: return false
        return a == b
    }

    /** body = [ID 28B][FF×4][trailer 11B]，解析出 ID */
    private fun parseBody(body: ByteArray): String? {
        if (body.size != ID_LEN + 4 + TRAILER.size) return null
        // FF FF FF FF 校验
        if (body[ID_LEN] != 0xFF.toByte() || body[ID_LEN + 1] != 0xFF.toByte() ||
            body[ID_LEN + 2] != 0xFF.toByte() || body[ID_LEN + 3] != 0xFF.toByte()) return null
        // trailer 校验
        for (i in TRAILER.indices) {
            if (body[ID_LEN + 4 + i] != TRAILER[i]) return null
        }
        val id = String(body, 0, ID_LEN, Charsets.US_ASCII)
        return id.takeIf { it.length == ID_LEN && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
    }

    private fun payloadBody(id: String): ByteArray {
        require(id.length == ID_LEN) { "live_photo_id 必须 28 位，实际 ${id.length}" }
        val body = ByteArray(ID_LEN + 4 + TRAILER.size)
        System.arraycopy(id.toByteArray(Charsets.US_ASCII), 0, body, 0, ID_LEN)
        body[ID_LEN] = 0xFF.toByte(); body[ID_LEN + 1] = 0xFF.toByte()
        body[ID_LEN + 2] = 0xFF.toByte(); body[ID_LEN + 3] = 0xFF.toByte()
        System.arraycopy(TRAILER, 0, body, ID_LEN + 4, TRAILER.size)
        return body
    }
}
