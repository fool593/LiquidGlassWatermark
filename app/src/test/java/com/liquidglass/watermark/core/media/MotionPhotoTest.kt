package com.liquidglass.watermark.core.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * Verifies motion-photo read/write without a device: our writer is fed by our reader, and the
 * declared item lengths are checked against the actual byte layout.
 */
class MotionPhotoTest {

    /** Structurally valid JPEG skeleton: SOI, APP0/JFIF, APP1/XMP, SOS, entropy data, EOI. */
    private fun sampleJpeg(withXmp: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte())) // SOI

        val jfif = "JFIF\u0000\u0001\u0002\u0000\u0000\u0001\u0000\u0001\u0000\u0000".toByteArray(StandardCharsets.ISO_8859_1)
        out.write(0xFF); out.write(0xE0)
        val jfifLen = jfif.size + 2
        out.write((jfifLen shr 8) and 0xFF); out.write(jfifLen and 0xFF)
        out.write(jfif, 0, jfif.size)

        if (withXmp) {
            val header = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(StandardCharsets.ISO_8859_1)
            val stale = "<x:xmpmeta>stale</x:xmpmeta>".toByteArray(StandardCharsets.UTF_8)
            val len = header.size + stale.size + 2
            out.write(0xFF); out.write(0xE1)
            out.write((len shr 8) and 0xFF); out.write(len and 0xFF)
            out.write(header, 0, header.size)
            out.write(stale, 0, stale.size)
        }

        out.write(0xFF); out.write(0xDA.toInt()) // SOS
        out.write(byteArrayOf(0x00, 0x08, 0x01, 0x01, 0x00, 0x00, 0x3F, 0x00))
        out.write(ByteArray(40) { (it * 7).toByte() }) // fake entropy-coded data
        out.write(byteArrayOf(0xFF.toByte(), 0xD9.toByte())) // EOI
        return out.toByteArray()
    }

    /** Minimal MP4 header: a `ftyp` box followed by payload. */
    private fun sampleMp4(payloadSize: Int = 2048): ByteArray {
        val body = ByteArray(payloadSize) { (it % 251).toByte() }
        val out = ByteArrayOutputStream()
        val size = 8 + 4 + body.size
        out.write((size shr 24) and 0xFF); out.write((size shr 16) and 0xFF)
        out.write((size shr 8) and 0xFF); out.write(size and 0xFF)
        out.write("ftyp".toByteArray(StandardCharsets.US_ASCII))
        out.write("isom".toByteArray(StandardCharsets.US_ASCII))
        out.write(body, 0, body.size)
        return out.toByteArray()
    }

    @Test
    fun `plain jpeg is not detected as a motion photo`() {
        val jpeg = sampleJpeg()
        assertFalse(MotionPhoto.isMotionPhoto(jpeg))
        val parsed = MotionPhoto.parse(jpeg)
        assertFalse(parsed.hasVideo)
        assertNull(parsed.videoBytes)
        assertArrayEquals(jpeg, parsed.imageBytes)
    }

    @Test
    fun `written motion photo round-trips back to the same image and video`() {
        val jpeg = sampleJpeg()
        val mp4 = sampleMp4()
        val motion = MotionPhoto.buildMotionPhoto(jpeg, mp4, presentationTimestampUs = 123_456L)

        assertTrue(MotionPhoto.isMotionPhoto(motion))

        val parsed = MotionPhoto.parse(motion)
        assertTrue(parsed.hasVideo)
        assertArrayEquals("video payload must survive untouched", mp4, parsed.videoBytes)
        assertEquals(motion.size - mp4.size, parsed.videoOffset)
        assertEquals(123_456L, parsed.presentationTimestampUs)

        // Image bytes differ only by the inserted XMP segment; the scan data must be identical.
        val imageText = String(parsed.imageBytes, 0, 64, StandardCharsets.ISO_8859_1)
        assertTrue("XMP APP1 header missing", imageText.contains("http://ns.adobe.com/xap/1.0/"))
        assertTrue(
            "entropy data must be copied verbatim",
            parsed.imageBytes.takeLast(42) == jpeg.takeLast(42),
        )
    }

    @Test
    fun `declared primary item length equals the actual jpeg length`() {
        val jpeg = sampleJpeg()
        val mp4 = sampleMp4()
        val motion = MotionPhoto.buildMotionPhoto(jpeg, mp4)
        val parsed = MotionPhoto.parse(motion)

        val xmp = String(parsed.imageBytes, 0, minOf(parsed.imageBytes.size, 4096), StandardCharsets.ISO_8859_1)
        val lengths = Regex("Item:Length=\"(\\d+)\"").findAll(xmp).map { it.groupValues[1].toInt() }.toList()
        assertEquals(2, lengths.size)
        assertEquals(
            "Primary length must match the real JPEG size",
            parsed.imageBytes.size,
            lengths[0],
        )
        assertEquals("video length must match the real MP4 size", mp4.size, lengths[1])
    }

    @Test
    fun `an existing xmp segment is replaced rather than duplicated`() {
        val jpeg = sampleJpeg(withXmp = true)
        val motion = MotionPhoto.buildMotionPhoto(jpeg, sampleMp4(512))

        val text = String(motion, 0, minOf(motion.size, 8192), StandardCharsets.ISO_8859_1)
        val occurrences = Regex(Regex.escape("http://ns.adobe.com/xap/1.0/")).findAll(text).count()
        assertEquals("exactly one XMP APP1 segment expected", 1, occurrences)
        assertFalse("stale XMP payload leaked through", text.contains("stale</x:xmpmeta>"))
    }

    @Test
    fun `legacy MicroVideoOffset dialect is read`() {
        val jpeg = sampleJpeg()
        val mp4 = sampleMp4(1024)
        val xmp = (
            "<x:xmpmeta><rdf:RDF><rdf:Description " +
                "xmlns:Camera=\"http://ns.google.com/photos/1.0/camera/\" " +
                "Camera:MicroVideo=\"1\" Camera:MicroVideoOffset=\"${mp4.size}\" " +
                "Camera:MicroVideoPresentationTimestampUs=\"9000\"/></rdf:RDF></x:xmpmeta>"
            ).toByteArray(StandardCharsets.UTF_8)

        val combined = insertXmpForTest(jpeg, xmp) + mp4
        assertTrue(MotionPhoto.isMotionPhoto(combined))
        val parsed = MotionPhoto.parse(combined)
        assertArrayEquals(mp4, parsed.videoBytes)
        assertEquals(9000L, parsed.presentationTimestampUs)
    }

    @Test
    fun `samsung MotionPhoto_Data separator is read`() {
        val jpeg = sampleJpeg()
        val mp4 = sampleMp4(700)
        val combined = jpeg +
            "MotionPhoto_Data".toByteArray(StandardCharsets.US_ASCII) +
            mp4

        assertTrue(MotionPhoto.isMotionPhoto(combined))
        val parsed = MotionPhoto.parse(combined)
        assertTrue(parsed.hasVideo)
        assertArrayEquals(mp4, parsed.videoBytes)
    }

    @Test
    fun `random trailing garbage without markers is treated as a still image`() {
        val jpeg = sampleJpeg()
        val combined = jpeg + ByteArray(4096) { (it * 31).toByte() }
        val parsed = MotionPhoto.parse(combined)
        assertFalse("must not invent a video", parsed.hasVideo)
        assertNotNull(parsed.imageBytes)
    }

    /** Test-only helper mirroring the production APP1 insertion for hand-built fixtures. */
    private fun insertXmpForTest(jpeg: ByteArray, xmp: ByteArray): ByteArray {
        val header = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(StandardCharsets.ISO_8859_1)
        val out = ByteArrayOutputStream()
        out.write(jpeg, 0, 2)
        val segmentLength = header.size + xmp.size + 2
        out.write(0xFF); out.write(0xE1)
        out.write((segmentLength shr 8) and 0xFF); out.write(segmentLength and 0xFF)
        out.write(header, 0, header.size)
        out.write(xmp, 0, xmp.size)
        out.write(jpeg, 2, jpeg.size - 2)
        return out.toByteArray()
    }
}
