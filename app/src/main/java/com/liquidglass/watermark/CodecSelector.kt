package com.liquidglass.watermark

import android.media.MediaCodec
import android.media.MediaCodecList

/**
 * 统一解码器自动匹配引擎（面向全安卓、不同机型自动择优）。
 *
 * 解码器来源天然分两类，本引擎按"速度 > 兼容"自动分层：
 * 1. 系统硬件解码器（厂商提供，机型自动最优）：createDecoderByType 默认实例 +
 *    MediaCodecList 枚举到的厂商硬解（qcom/mtk/exynos/...）——最快、走 GPU 零拷贝导出。
 * 2. 开源解码器（AOSP Codec2：c2.android.*；开源软解：OMX.google.*）——全格式兜底、
 *    稳定必能跑完，覆盖系统硬解不支持的编码。
 *
 * 选择原则：默认实例（系统按机型自动选最优）→ 候选链顺序由调用方 `preferHardwareFirst` 决定：
 *   - preferHardwareFirst=true（GPU 导出）→ 厂商硬解优先；
 *   - preferHardwareFirst=false（逐帧解码兜底）→ `videoDecoderCandidates` 返回的开源软解优先序
 *     （c2.android / OMX.google 排最前，硬解僵死时软解必能兜底）。
 * 全部候选只允许【明确支持源 MIME】的 decoder：video/avc 源绝不尝试 AV1/H263/HEVC/VP8/VP9
 * 等不匹配 MIME 的 decoder（浪费 + 污染失败链）；srcMime 未知时保守放行全部。
 */
object CodecSelector {

    /** 视频 MIME → 支持的解码器候选（名称列表，已按开源软解优先排序——硬解僵死时软解必能兜底）。 */
    fun videoDecoderCandidates(srcMime: String?): List<String> {
        val out = ArrayList<String>(16)
        runCatching {
            val list = MediaCodecList(MediaCodecList.ALL_CODECS)
            for (ci in list.codecInfos) {
                if (ci.isEncoder) continue
                for (t in ci.supportedTypes) {
                    if (!t.startsWith("video/")) continue
                    if (srcMime == null || t == srcMime) { out.add(ci.name); break }
                }
            }
        }
        return out.sortedBy {
            val n = it.lowercase()
            when {
                "c2.android" in n || "omx.google" in n || "soft" in n -> 0
                "c2." in n || "omx." in n -> 1
                else -> 2
            }
        }
    }

    /** 厂商硬件解码器候选（排除开源软解，走 GPU 路径时硬解 Surface 输出才稳定）。 */
    fun hardwareDecoderCandidates(srcMime: String?): List<String> {
        return videoDecoderCandidates(srcMime).filter { n ->
            val l = n.lowercase()
            "c2.android" !in l && "omx.google" !in l && "soft" !in l
        }
    }

    /**
     * 创建解码器。codecName 为 null/空 → 系统默认实例（createDecoderByType，
     * Android 系统按当前机型自动选择最优硬件解码器）；否则按名称创建。
     */
    fun createDecoder(srcMime: String?, codecName: String?): MediaCodec? {
        return if (codecName.isNullOrEmpty()) {
            if (srcMime == null) null
            else runCatching { MediaCodec.createDecoderByType(srcMime) }.getOrNull()
        } else {
            runCatching { MediaCodec.createByCodecName(codecName) }.getOrNull()
        }
    }

    /**
     * 尝试创建一个"可配置成功"的解码器：默认实例失败时按候选链兜底。
     * @return 已创建且 configure 成功的解码器（调用方负责 start/release）
     */
    fun createConfiguredDecoder(
        srcMime: String?, format: android.media.MediaFormat,
        surface: android.view.Surface?, flags: Int,
        preferHardwareFirst: Boolean = false, maxCandidates: Int = 3,
    ): MediaCodec? {
        // 1. 默认实例（系统按机型自动选最优；Surface 模式优先硬解）
        val def = createDecoder(srcMime, null)
        if (def != null) {
            val ok = runCatching { def.configure(format, surface, null, flags); true }.getOrDefault(false)
            if (ok) return def
            runCatching { def.release() }
        }
        // 2. 候选链：硬解优先（GPU 路径）或开源软解优先（buffer 兜底）逐个尝试
        val all = videoDecoderCandidates(srcMime)
        val pool = if (preferHardwareFirst) {
            hardwareDecoderCandidates(srcMime) + all.filter { it !in hardwareDecoderCandidates(srcMime) }
        } else all
        var tried = 0
        for (cand in pool) {
            if (tried >= maxCandidates) break
            val d = createDecoder(srcMime, cand) ?: continue
            tried++
            val ok = runCatching { d.configure(format, surface, null, flags); true }.getOrDefault(false)
            if (ok) {
                DebugLog.log("DECODE", "默认解码器不可用，候选兜底命中 ${d.name}")
                return d
            }
            runCatching { d.release() }
        }
        return null
    }
}
