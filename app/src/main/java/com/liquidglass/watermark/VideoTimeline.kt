package com.liquidglass.watermark

/** Source timestamps remain authoritative, including variable-rate footage. */
internal class VideoTimeline(fps: Int) {
    private val fallbackIntervalUs = 1_000_000L / fps.coerceIn(1, 240)
    var originUs: Long = Long.MIN_VALUE
        private set
    private var lastUs = -1L

    fun next(sourcePtsUs: Long): Long {
        if (originUs == Long.MIN_VALUE) originUs = sourcePtsUs.coerceAtLeast(0L)
        val relativeUs = (sourcePtsUs - originUs).coerceAtLeast(0L)
        val outputUs = if (relativeUs > lastUs) relativeUs
            else lastUs + fallbackIntervalUs
        lastUs = outputUs
        return outputUs
    }

    fun audioTimeUs(sourcePtsUs: Long): Long =
        sourcePtsUs - originUs.takeUnless { it == Long.MIN_VALUE }.let { it ?: 0L }
}
