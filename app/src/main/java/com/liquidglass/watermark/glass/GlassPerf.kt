package com.liquidglass.watermark.glass

/**
 * v15.16 任务3：玻璃渲染栈的轻量级计数器。
 *
 * 只做计数，不参与渲染决策，零视觉影响。用于证明「每帧全量重录离屏层」这一卡顿根因
 * 是否被消除：修复前每个玻璃控件的 shadow / highlight / innerShadow / track 捕获层
 * 都会在**每一帧**无条件 `GraphicsLayer.record(...)`；修复后只在输入真正变化时重录。
 *
 * 读取方式：`adb shell am start -n com.liquidglass.watermark/.MainActivity --es ljgAutoTest "glassperf"`
 * （见 MainActivity.runAutoTest），或直接看 logcat tag `GLASSPERF`。
 */
object GlassPerf {

    @Volatile var shadowRecords: Long = 0L
        private set
    @Volatile var shadowSkips: Long = 0L
        private set
    @Volatile var highlightRecords: Long = 0L
        private set
    @Volatile var highlightSkips: Long = 0L
        private set
    @Volatile var innerShadowRecords: Long = 0L
        private set
    @Volatile var innerShadowSkips: Long = 0L
        private set
    @Volatile var trackRecords: Long = 0L
        private set
    @Volatile var trackSkips: Long = 0L
        private set
    @Volatile var backdropRecords: Long = 0L
        private set
    @Volatile var sceneRecords: Long = 0L
        private set
    @Volatile var sceneSkips: Long = 0L
        private set

    internal fun onShadowRecord() { shadowRecords++ }
    internal fun onShadowSkip() { shadowSkips++ }
    internal fun onHighlightRecord() { highlightRecords++ }
    internal fun onHighlightSkip() { highlightSkips++ }
    internal fun onInnerShadowRecord() { innerShadowRecords++ }
    internal fun onInnerShadowSkip() { innerShadowSkips++ }
    internal fun onTrackRecord() { trackRecords++ }
    internal fun onTrackSkip() { trackSkips++ }
    internal fun onBackdropRecord() { backdropRecords++ }
    internal fun onSceneRecord() { sceneRecords++ }
    internal fun onSceneSkip() { sceneSkips++ }

    fun reset() {
        shadowRecords = 0L; shadowSkips = 0L
        highlightRecords = 0L; highlightSkips = 0L
        innerShadowRecords = 0L; innerShadowSkips = 0L
        trackRecords = 0L; trackSkips = 0L
        backdropRecords = 0L; sceneRecords = 0L; sceneSkips = 0L
    }

    /** 单行摘要，便于 logcat / 报告引用。 */
    fun snapshot(): String = buildString {
        append("scene=").append(sceneRecords).append('/').append(sceneSkips)
        append(" backdrop=").append(backdropRecords)
        append(" shadow=").append(shadowRecords).append('/').append(shadowSkips)
        append(" highlight=").append(highlightRecords).append('/').append(highlightSkips)
        append(" innerShadow=").append(innerShadowRecords).append('/').append(innerShadowSkips)
        append(" track=").append(trackRecords).append('/').append(trackSkips)
    }
}
