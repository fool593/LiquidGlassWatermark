package com.liquidglass.watermark

import android.app.ActivityManager
import android.content.Context

/**
 * Memory-based quality levels for the software watermark renderer and its CPU/GPU export bridge.
 *
 * M3 说明：当前"低内存"与"常规"两档的渲染分辨率参数（previewEdge / animatedPreviewEdge /
 * videoFrameEdge / exportPatchEdge）**完全相同**，唯一差异是 animationIntervalMs（预览调度频率）。
 * 即 meminfo 检测目前只影响预览动画节流，不影响渲染质量（有意为之：跨机型玻璃效果保持一致）。
 * `lowMemory` 字段目前无调用方读取，仅为后续按档位差异化预留——改动前请先确认调用方。
 */
data class WatermarkPerformanceProfile(
    val previewEdge: Int,
    val animatedPreviewEdge: Int,
    val videoFrameEdge: Int,
    val exportPatchEdge: Int,
    val animationIntervalMs: Long,
    val lowMemory: Boolean
)

object WatermarkPerformancePolicy {
    private const val GIB = 1024L * 1024L * 1024L

    fun forContext(context: Context): WatermarkPerformanceProfile {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo()
        runCatching { activityManager?.getMemoryInfo(info) }
        val totalRam = info.totalMem
        val memoryClassMb = runCatching { activityManager?.memoryClass ?: 0 }.getOrDefault(0)
        val lowMemory = activityManager?.isLowRamDevice == true ||
            (totalRam > 0L && totalRam <= 3L * GIB) ||
            (memoryClassMb in 1..192)

        return if (lowMemory) {
            WatermarkPerformanceProfile(
                // Keep the final glass-effect sampling identical across device tiers. Low-RAM
                // devices may schedule preview animation less often, but exports never render
                // with a lower effect resolution.
                previewEdge = 480,
                animatedPreviewEdge = 480,
                videoFrameEdge = 480,
                exportPatchEdge = 480,
                animationIntervalMs = 50L, // 20 Hz preview scheduling target on constrained devices.
                lowMemory = true
            )
        } else {
            WatermarkPerformanceProfile(
                previewEdge = 480,
                animatedPreviewEdge = 480,
                videoFrameEdge = 480,
                exportPatchEdge = 480,
                animationIntervalMs = 40L, // 25 Hz target; still follows the source timestamp.
                lowMemory = false
            )
        }
    }
}
