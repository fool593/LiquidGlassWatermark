package com.liquidglass.watermark

import android.app.ActivityManager
import android.content.Context

/** Memory-based quality levels for the software watermark renderer and its CPU/GPU export bridge. */
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
