package com.liquidglass.watermark

import android.media.MediaCodec
import android.media.MediaCodecList

object CodecSelector {

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

    fun hardwareDecoderCandidates(srcMime: String?): List<String> {
        return videoDecoderCandidates(srcMime).filter { n ->
            val l = n.lowercase()
            "c2.android" !in l && "omx.google" !in l && "soft" !in l
        }
    }

    fun createDecoder(srcMime: String?, codecName: String?): MediaCodec? {
        return if (codecName.isNullOrEmpty()) {
            if (srcMime == null) null
            else runCatching { MediaCodec.createDecoderByType(srcMime) }.getOrNull()
        } else {
            runCatching { MediaCodec.createByCodecName(codecName) }.getOrNull()
        }
    }

    fun createConfiguredDecoder(
        srcMime: String?, format: android.media.MediaFormat,
        surface: android.view.Surface?, flags: Int,
        preferHardwareFirst: Boolean = false, maxCandidates: Int = 3,
    ): MediaCodec? {

        val def = createDecoder(srcMime, null)
        if (def != null) {
            val ok = runCatching { def.configure(format, surface, null, flags); true }.getOrDefault(false)
            if (ok) return def
            runCatching { def.release() }
        }

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
