package com.liquidglass.watermark

/**
 * 视频 → GIF 导出的统一时间策略（量化 / 编码两遍共用），保证输出与源帧时间严格一致。
 *
 * v15.28：支持【时间轴裁剪】——[startMs] 为片段起点（0=片头），[limitMs] 为片段时长。
 * 裁剪后仅输出 [startMs, startMs+limitMs) 区间内的帧；GIF 帧延迟仍从片段内 0 起算
 * （GIF 播放器不会因裁剪起点而额外等待，第一帧立即显示）。
 *
 * 兼容性：startMs=0 时行为与旧版逐字节一致（elapsedMs 从 0 累计、帧延迟取实际帧间隔）。
 */
class VideoGifTimeline(
    private val startMs: Int = 0,
    private val limitMs: Int = MAX_DURATION_MS
) {
    companion object { const val MAX_DURATION_MS = 5_000 }

    /** 已输出的片段内毫秒（相对片段起点；仅起点偏移与旧版行为差异，见类注释） */
    var elapsedMs: Int = 0
        private set
    private var emittedCs = 0
    val hasRemaining: Boolean get() = elapsedMs < limitMs

    /** 当前帧对应的【源视频播放时间】（毫秒）：片段起点 + 片段内进度。
     *  供渲染层计算呼吸/流体动画——导出动画必须跟随源媒体时间，而非片段内偏移。 */
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