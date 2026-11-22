package com.liquidglass.watermark

class VideoGifTimeline(
    private val startMs: Int = 0,
    private val limitMs: Int = MAX_DURATION_MS
) {
    companion object { const val MAX_DURATION_MS = 5_000 }

    var elapsedMs: Int = 0
        private set
    private var emittedCs = 0
    val hasRemaining: Boolean get() = elapsedMs < limitMs

    fun sourceTimeMs(): Int = startMs.coerceAtLeast(0) + elapsedMs

    /** GIF uses centiseconds. Round cumulative time so 60fps cannot become 50fps. */
    fun takeDelayCs(sourceDelayMs: Int): Int {
        if (!hasRemaining) return 0
        elapsedMs += minOf(sourceDelayMs.coerceAtLeast(1), limitMs - elapsedMs)
        val endCs = minOf((elapsedMs + 5) / 10, limitMs / 10)
        val delay = (endCs - emittedCs).coerceAtLeast(0)
        emittedCs = endCs
        return delay
    }
}