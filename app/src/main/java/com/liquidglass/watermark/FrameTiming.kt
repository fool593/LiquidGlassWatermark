package com.liquidglass.watermark

internal object FrameTiming {
    // Round endpoints, rather than each interval, so fractional FPS does not drift.
    fun delayMs(startUs: Long, endUs: Long): Int =
        ((endUs + 500L) / 1000L - (startUs + 500L) / 1000L)
            .coerceIn(1L, 60_000L).toInt()
}
