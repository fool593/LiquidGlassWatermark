package com.liquidglass.watermark.core.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the arithmetic that drives a GIF export.
 *
 * The exporter itself cannot run here: it needs a `Context`, a `MediaMetadataRetriever` and real
 * `Bitmap`s, and `unitTests.isReturnDefaultValues = true` only stubs the framework — a `Bitmap` call
 * still throws. The sampling, scaling and timing functions are therefore extracted as pure top-level
 * functions (and `internal` rather than `private`) precisely so the parts that are easy to get wrong
 * — even dimensions, frame caps, GIF centisecond quantisation — are testable on a plain JVM.
 */
class GifExporterTest {

    // ------------------------------------------------------------------ scaling

    @Test
    fun `scaling leaves a clip that already fits untouched`() {
        assertEquals(320 to 240, scaledToMaxWidth(320, 240, 480))
    }

    @Test
    fun `scaling preserves the aspect ratio at the capped width`() {
        assertEquals(480 to 270, scaledToMaxWidth(1920, 1080, 480))
    }

    @Test
    fun `scaling keeps both dimensions even`() {
        val (width, height) = scaledToMaxWidth(1921, 1081, 480)
        assertEquals(0, width % 2)
        assertEquals(0, height % 2)
    }

    @Test
    fun `scaling never collapses an extreme aspect ratio to zero`() {
        val (width, height) = scaledToMaxWidth(4000, 2, 480)
        assertEquals(480, width)
        assertEquals(2, height)
    }

    @Test
    fun `scaling rejects nonsense input instead of throwing`() {
        assertEquals(2 to 2, scaledToMaxWidth(0, 1080, 480))
        assertEquals(2 to 2, scaledToMaxWidth(1920, 1080, 0))
    }

    @Test
    fun `scaling floors odd dimensions that already fit`() {
        // 481 x 271 would be an odd-sized GIF. GIF handles odd sizes, but H.264-style even-only
        // consumers do not, and the shared helper is used by both paths.
        assertEquals(480 to 270, scaledToMaxWidth(481, 271, 480))
        assertEquals(320 to 240, scaledToMaxWidth(321, 241, 480))
    }

    // ------------------------------------------------------------------ timing

    @Test
    fun `frame delay is quantised to gif centiseconds`() {
        // 12 fps -> 83.3 ms -> 8 cs, which is the smallest exact representation of 12 fps.
        assertEquals(8, frameDelayCentis(12.0))
        assertEquals(10, frameDelayCentis(10.0))
        assertEquals(4, frameDelayCentis(25.0))
        assertEquals(3, frameDelayCentis(30.0))
    }

    @Test
    fun `frame delay never drops below the encoder floor`() {
        // Anything at or above 50 fps would round to 1 cs, which players read as "as fast as
        // possible" and which GifEncoder clamps to 2 anyway.
        assertEquals(2, frameDelayCentis(50.0))
        assertEquals(2, frameDelayCentis(120.0))
        assertEquals(2, frameDelayCentis(1000.0))
    }

    @Test
    fun `frame delay falls back to the default for a nonsense rate`() {
        assertEquals(frameDelayCentis(GIF_DEFAULT_FPS), frameDelayCentis(0.0))
        assertEquals(frameDelayCentis(GIF_DEFAULT_FPS), frameDelayCentis(-5.0))
    }

    @Test
    fun `frame count is the duration divided by the frame interval`() {
        assertEquals(24, gifFrameCount(2_000L, 12.0, MAX_FRAMES))
        assertEquals(12, gifFrameCount(1_000L, 12.0, MAX_FRAMES))
    }

    @Test
    fun `frame count is capped by the memory guard`() {
        // A 60 s clip at 12 fps would be 720 frames — far past the cap.
        assertEquals(MAX_FRAMES, gifFrameCount(60_000L, 12.0, MAX_FRAMES))
        assertEquals(5, gifFrameCount(60_000L, 12.0, 5))
    }

    @Test
    fun `frame count never reports zero for a playable clip`() {
        assertEquals(1, gifFrameCount(1L, 12.0, MAX_FRAMES))
    }

    // ------------------------------------------------------------------ sampling schedule

    @Test
    fun `sample schedule stays inside the clip`() {
        val durationMillis = 1_000L
        val times = frameSampleTimesUs(durationMillis, 12.0, MAX_FRAMES)

        // 1000 ms at 83.3 ms per frame: 12 samples, no sample landing on the clip's end (a decoder
        // asked for a frame at the exact end usually returns nothing).
        assertEquals(12, times.size)
        assertEquals(0L, times.first())
        assertTrue(
            "last sample ${times.last()}us must stay inside ${durationMillis}ms",
            times.last() < durationMillis * 1_000L,
        )
        // Floating point truncation to whole milliseconds may cost at most 1 ms per interval.
        for (index in 1 until times.size) {
            val gapMillis = (times[index] - times[index - 1]) / 1_000L
            assertTrue("gap of ${gapMillis}ms is outside the 1 ms rounding tolerance", gapMillis in 82L..84L)
        }
    }

    @Test
    fun `sample schedule is truncated by the frame cap`() {
        val times = frameSampleTimesUs(60_000L, 12.0, 10)

        assertEquals(10, times.size)
        // The 10th sample is 9 truncated intervals in (9 x 83 ms), and the cap stops it there even
        // though the clip is a minute long.
        assertEquals(0L, times.first())
        assertTrue("unexpected last sample ${times.last()}", times.last() in 740_000L..750_000L)
    }

    @Test
    fun `sample schedule stays bounded for a very long clip`() {
        val times = frameSampleTimesUs(3_600_000L, 12.0, 150)

        assertEquals(150, times.size)
        assertTrue("the schedule must not grow with the clip", times.last() <= 3_600_000_000L)
    }

    @Test
    fun `sample schedule always yields at least one frame`() {
        assertEquals(1, frameSampleTimesUs(1L, 12.0, MAX_FRAMES).size)
        assertEquals(0L, frameSampleTimesUs(1L, 12.0, MAX_FRAMES).first())
    }

    @Test
    fun `sample schedule agrees with the reported frame count when uncapped`() {
        // Progress is reported against the schedule length while the plan advertises frameCount, so
        // the two must not disagree or the progress bar would finish early or stall. They can only
        // be compared below the cap, where both are derived from the same duration and interval.
        val durationMillis = 5_000L
        assertEquals(
            gifFrameCount(durationMillis, 12.0, MAX_FRAMES),
            frameSampleTimesUs(durationMillis, 12.0, MAX_FRAMES).size,
        )
    }

    @Test
    fun `sample schedule is empty for unusable input`() {
        assertEquals(0, frameSampleTimesUs(0L, 12.0, MAX_FRAMES).size)
        assertEquals(0, frameSampleTimesUs(-1L, 12.0, MAX_FRAMES).size)
        assertEquals(0, frameSampleTimesUs(1_000L, 0.0, MAX_FRAMES).size)
        assertEquals(0, frameSampleTimesUs(1_000L, 12.0, 0).size)
    }

    // ------------------------------------------------------------------ plan value semantics

    @Test
    fun `frame plan compares by content`() {
        val first = GifFramePlan(frameSampleTimesUs(1_000L, 12.0, MAX_FRAMES), 8, 13)
        val same = GifFramePlan(frameSampleTimesUs(1_000L, 12.0, MAX_FRAMES), 8, 13)
        val other = GifFramePlan(frameSampleTimesUs(1_000L, 12.0, MAX_FRAMES), 10, 13)

        assertEquals(first, same)
        assertEquals(first.hashCode(), same.hashCode())
        assertNotEquals(first, other)
    }

    @Test
    fun `frame plan records the delay the encoder will be given`() {
        val plan = GifFramePlan(frameSampleTimesUs(1_000L, 12.0, MAX_FRAMES), frameDelayCentis(12.0), 13)
        // GifEncoder.addFrame takes centiseconds and clamps to >= 2; the plan must match that unit
        // exactly or the exported GIF would play at a different speed than the UI advertises.
        assertEquals(8, plan.frameDelayCentis)
    }
}
