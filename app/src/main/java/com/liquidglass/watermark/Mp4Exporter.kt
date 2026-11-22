package com.liquidglass.watermark

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min
import kotlin.math.roundToInt

object Mp4Exporter {

    @Volatile var lastFailReason: String = ""

    private class EncoderDeadException(reason: String) : Exception(reason)

    fun export(
        ctx: Context,
        mp4: ByteArray,
        userRot: Int,
        onFrame: (Bitmap) -> Bitmap,
        onProgress: (Int) -> Unit,
        prefixFrame: Bitmap? = null,
        prefixDurationMs: Int = 500,
        codec: String = MediaFormat.MIMETYPE_VIDEO_AVC,
        srcUri: android.net.Uri? = null,
        forceVideoRotation: Int = -1,

        reuseOutput: Boolean = false,

        watermarkRenderer: WatermarkEditorView? = null,

        isCancelled: () -> Boolean = { false },

        includeAudio: Boolean = true,

        maxDurationMs: Long = 0L,

        startPtsUs: Long = 0L
    ): File? {
        lastFailReason = ""

        var ownLocal = false
        val srcLocal: File? = try {
            when {
                srcUri != null && srcUri.scheme.equals("file", ignoreCase = true) ->
                    File(srcUri.path ?: srcUri.toString())
                srcUri != null -> {
                    val t = File(ctx.cacheDir, "lgw_local_${System.currentTimeMillis()}.mp4")
                    val ok = MotionDecoder.copyUriToFile(ctx, srcUri, t) && t.length() > 0
                    if (!ok) {
                        runCatching { t.delete() }
                        lastFailReason = MotionDecoder.lastFailReason.takeIf { it.isNotBlank() }
                            ?: "源视频读取失败"
                        return null
                    }
                    ownLocal = true; t
                }
                else -> File(ctx.cacheDir, "lgw_mux_${System.currentTimeMillis()}.mp4").also {
                    it.writeBytes(mp4); ownLocal = true
                }
            }
        } catch (t: Throwable) {
            lastFailReason = "源视频准备失败：${t.message}"
            null
        }
        if (srcLocal == null) return null

        val localUri = android.net.Uri.fromFile(srcLocal)
        val src: String = srcLocal.absolutePath

        var srcFps = 0
        var srcBitrate = 0
        var srcW = 0; var srcH = 0
        var rotation = 0
        runCatching {
            val ex = MediaExtractor()
            try {
                ex.setDataSource(src)
                for (i in 0 until ex.trackCount) {
                    val f = ex.getTrackFormat(i)
                    val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("video/")) {
                        srcFps = runCatching { f.getInteger(MediaFormat.KEY_FRAME_RATE) }.getOrDefault(0)
                        srcBitrate = runCatching { f.getInteger(MediaFormat.KEY_BIT_RATE) }.getOrDefault(0)
                        srcW = runCatching { f.getInteger(MediaFormat.KEY_WIDTH) }.getOrDefault(0)
                        srcH = runCatching { f.getInteger(MediaFormat.KEY_HEIGHT) }.getOrDefault(0)
                        rotation = runCatching { f.getInteger(MediaFormat.KEY_ROTATION) }.getOrDefault(0)
                        break
                    }
                }
            } finally { runCatching { ex.release() } }
        }
        // Container dimensions and rotation avoid decoding a throwaway full frame.
        // Probe only malformed files whose dimensions are missing.
        var fW = srcW; var fH = srcH
        onProgress(10)
        val probe = if (srcW > 0 && srcH > 0) null else runCatching {
            MotionDecoder.decode(ctx, ByteArray(0), 8192, 240, 1, 20_000, 1,
                rotationOverride = forceVideoRotation, srcUri = localUri)
        }.getOrNull()
        val probeFrame = probe?.frames?.firstOrNull()
        if (probeFrame != null) {
            fW = probeFrame.width; fH = probeFrame.height
            rotation = probe.rotation
        }

        probe?.frames?.forEach { runCatching { if (!it.isRecycled) it.recycle() } }
        onProgress(12)
        val fps = when {
            srcFps in 1..240 -> srcFps
            srcFps > 240 -> {
                if (ownLocal) runCatching { srcLocal.delete() }
                lastFailReason = "源视频帧率 ${srcFps} fps 超出当前编码路径支持范围（1–240 fps），已拒绝降帧"
                return null
            }
            else -> estimateSourceFrameRate(srcLocal) ?: run {
                if (ownLocal) runCatching { srcLocal.delete() }
                lastFailReason = "源视频未提供有效帧率，且无法从样本时间戳估算；已拒绝默认改为 30 fps"
                return null
            }
        }
        if (fW <= 0 || fH <= 0) {
            if (ownLocal) srcLocal.delete()
            lastFailReason = "视频尺寸异常"
            return null
        }

        var encW: Int
        var encH: Int
        if (probeFrame != null) {
            encW = fW; encH = fH
        } else {

            if (forceVideoRotation >= 0) rotation = forceVideoRotation
            rotation = MotionDecoder.normalizeVideoRotation(rotation, fW, fH)
            encW = if (rotation % 180 != 0) fH else fW
            encH = if (rotation % 180 != 0) fW else fH
        }
        val ur = ((userRot % 360) + 360) % 360
        if (ur % 180 != 0) { val t = encW; encW = encH; encH = t }
        // Current AVC/HEVC 4:2:0 encoders require even dimensions. Never round up and
        // silently alter the source output size to satisfy that constraint.
        if (encW % 2 != 0 || encH % 2 != 0) {
            if (ownLocal) runCatching { srcLocal.delete() }
            lastFailReason = "源视频尺寸 ${encW}x${encH} 不能由当前 4:2:0 输出路径原尺寸编码"
            return null
        }

        val realCodec = try {
            if (codec == MediaFormat.MIMETYPE_VIDEO_HEVC &&
                MediaCodecList(MediaCodecList.REGULAR_CODECS)
                    .findEncoderForFormat(MediaFormat.createVideoFormat(codec, 2, 2)) != null
            ) codec else MediaFormat.MIMETYPE_VIDEO_AVC
        } catch (_: Throwable) { MediaFormat.MIMETYPE_VIDEO_AVC }

        // Candidates: recommended hardware, other hardware, then software fallback.
        val allEnc = ArrayList<Pair<String, Boolean>>()  // name -> isHardwareAccelerated
        runCatching {
            val list = MediaCodecList(MediaCodecList.ALL_CODECS)
            for (ci in list.codecInfos) {
                if (!ci.isEncoder) continue
                if (realCodec in ci.supportedTypes) {

                    val hw = if (Build.VERSION.SDK_INT >= 29) ci.isHardwareAccelerated
                    else !(ci.name.contains("google", true) || ci.name.contains("c2.android", true))
                    allEnc.add(ci.name to hw)
                }
            }
        }

        val defProbe = runCatching { MediaCodec.createEncoderByType(realCodec) }.getOrNull()
        val defName = defProbe?.name
        runCatching { defProbe?.release() }
        val defIsHw = allEnc.firstOrNull { it.first == defName }?.second
            ?: (defName != null && !defName.contains("google", true) &&
                !defName.contains("c2.android", true))
        val candidates = ArrayList<String>()
        if (defIsHw) candidates.add("")

        val hwNames = allEnc.filter { it.second &&
            !it.first.contains("google", true) &&
            !it.first.contains("c2.android", true) }
        for ((name, _) in hwNames) {
            if (candidates.size >= 3) break
            if (name !in candidates && !(defIsHw && name == defName)) candidates.add(name)
        }

        if ("" !in candidates && !defIsHw) candidates.add("")

        allEnc.filter { !it.second || it.first.contains("google", true) || it.first.contains("c2.android", true) }
            .forEach { if (it.first !in candidates && it.first != defName) candidates.add(it.first) }
        if (candidates.size > 6) candidates.subList(6, candidates.size).clear()

        // Surface decode/encode path, with the same CPU material renderer used in preview.
        // Native pixel transfer and reused buffers avoid per-pixel Kotlin copies.
        // A failed Surface path falls back to the same material renderer below.
        if (watermarkRenderer != null && prefixFrame == null) {
            onProgress(8)
            val gpuOut = File(ctx.cacheDir, "lgw_gpu_${System.currentTimeMillis()}.mp4")

            var gpuFailReason: String? = null
            val t0 = System.currentTimeMillis()
            val gpuResult = runCatching {

                GpuMp4Exporter.export(ctx, srcLocal, gpuOut, encW, encH, fps, (rotation + ur) % 360, realCodec,
                    watermarkRenderer, onProgress, isCancelled,
                    cpuWatermark = true, includeAudio = includeAudio,
                    maxDurationMs = maxDurationMs, startPtsUs = startPtsUs)
            }.onFailure { e -> gpuFailReason = "${e.javaClass.simpleName}: ${e.message}" }.getOrNull()
            if (gpuResult != null && gpuOut.exists() && gpuOut.length() > 1024) {
                val ms = (System.currentTimeMillis() - t0).coerceAtLeast(1)
                DebugLog.log("ENC", "GPU 导出成功 frames=${gpuResult.frames} 时长=${gpuResult.durationMs}ms 耗时=${ms}ms 速率=${gpuResult.frames * 1000 / ms}fps")
                if (ownLocal) runCatching { srcLocal.delete() }
                onProgress(100)
                return gpuOut
            }

            if (isCancelled()) {
                DebugLog.log("ENC", "GPU 导出已取消，不回退")
                runCatching { gpuOut.delete() }
                if (ownLocal) runCatching { srcLocal.delete() }
                return null
            }
            val reason = gpuFailReason ?: "GPU 未启用或输出无效（size=${runCatching { gpuOut.length() }.getOrDefault(-1L)}）"
            DebugLog.log("ENC", "GPU 路径失败/未启用（原因: $reason）")
            runCatching {
                android.widget.Toast.makeText(ctx,
                    "GPU 加速导出失败，已用同引擎逐帧路径：$reason", android.widget.Toast.LENGTH_LONG).show()
            }

            DebugLog.log("ENC", "回退逐帧 Bitmap 路径（同引擎=预览观感，原因: $reason）")
            runCatching { gpuOut.delete() }
            onProgress(12)
        }

        var lastDead = ""
        var triedN = 0
        for (cand in candidates) {
            triedN++
            val outFile = File(ctx.cacheDir, "lgw_export_${System.currentTimeMillis()}_$triedN.mp4")
            try {
                val r = exportWithEncoder(
                    ctx, srcLocal, outFile, cand, realCodec, encW, encH, fps,
                    rotation, onFrame, onProgress,
                    prefixFrame, prefixDurationMs, ByteArray(0), localUri, reuseOutput,
                    includeAudio, maxDurationMs, startPtsUs, srcBitrate)

                if (r == null) {
                    runCatching { outFile.delete() }
                } else {
                    if (ownLocal) runCatching { srcLocal.delete() }
                    return r
                }
            } catch (e: EncoderDeadException) {
                lastDead = e.message ?: "编码器输入阻塞"
                DebugLog.log("ENC", "编码器死亡（$triedN/${candidates.size}）cand=${if (cand.isEmpty()) "默认" else cand}：$lastDead，换下一个")
                runCatching { outFile.delete() }
            } catch (t: Throwable) {

                if (ownLocal) runCatching { srcLocal.delete() }
                lastFailReason = t.message ?: "导出失败"
                runCatching { outFile.delete() }
                return null
            }
        }
        if (ownLocal) runCatching { srcLocal.delete() }
        lastFailReason = "所有编码器均失败（已试 $triedN 个：$lastDead）"
        return null
    }

    private fun exportWithEncoder(
        ctx: Context,
        tmp: File?,
        outFile: File,
        cand: String,
        realCodec: String,
        encW: Int,
        encH: Int,
        fps: Int,
        rotation: Int,
        onFrame: (Bitmap) -> Bitmap,
        onProgress: (Int) -> Unit,
        prefixFrame: Bitmap?,
        prefixDurationMs: Int,
        mp4: ByteArray,
        srcUri: android.net.Uri? = null,
        reuseOutput: Boolean = false,
        includeAudio: Boolean = true,

        maxDurationMs: Long = 0L,

        startPtsUs: Long = 0L,
        sourceBitrate: Int = 0
    ): File? {
        var encoder: MediaCodec? = null
        var mux: MediaMuxer? = null
        var durEstMs = 0L
        var audioExt: MediaExtractor? = null

        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_LESS_FAVORABLE) }

        val std = sourceColorStandard(tmp, encW, encH)
        try {
            val enc = try {
                if (cand.isEmpty()) MediaCodec.createEncoderByType(realCodec)
                else MediaCodec.createByCodecName(cand)
            } catch (t: Throwable) {
                lastFailReason = "创建编码器失败：${t.message}"
                return null
            }
            encoder = enc
            var encPlanar = false
            try {
                val caps = runCatching { enc.codecInfo.getCapabilitiesForType(realCodec) }.getOrNull()
                val formats = caps?.colorFormats?.toList() ?: emptyList()

                val usePlanar = !formats.contains(
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) &&
                    formats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar)
                val efmt = MediaFormat.createVideoFormat(realCodec, encW, encH)
                efmt.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    if (usePlanar) MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
                    else MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
                efmt.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                efmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                // Watermarking requires video re-encoding. Keep the output bitrate at least
                // as high as the source track, with a resolution/FPS-based high-quality floor.
                val hevcEnc = realCodec == MediaFormat.MIMETYPE_VIDEO_HEVC
                val targetBitrate = (encW.toDouble() * encH * fps * (if (hevcEnc) 0.12 else 0.20))
                    .toLong().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                val bitrate = maxOf(targetBitrate, sourceBitrate, 3_000_000)
                efmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)

                efmt.setInteger(MediaFormat.KEY_COLOR_STANDARD, std)
                efmt.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                efmt.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                enc.configure(efmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                enc.start()
                encPlanar = usePlanar
            } catch (t: Throwable) {
                lastFailReason = "编码器配置失败：${t.message}"
                return null
            }
            DebugLog.log("ENC", "编码器 ${if (cand.isEmpty()) "默认" else cand} planar=$encPlanar ${encW}x$encH")

            val tmpOut = File(outFile.parentFile ?: ctx.cacheDir, outFile.name + ".tmp")
            runCatching { tmpOut.delete() }
            mux = MediaMuxer(tmpOut.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val mmux = mux
            var muxStarted = false
            var muxTrack = -1
            val encInfo = MediaCodec.BufferInfo()

            var videoPts = 0L
            var prefixPts = 0L
            var audioFormat: MediaFormat? = null
            var audioMuxTrack = -1
            var audioEos = false
            var srcHasAudio = false
            var sourceOriginUs = 0L
            if (tmp != null) {
                val originExtractor = MediaExtractor()
                try {
                    originExtractor.setDataSource(tmp.absolutePath)
                    for (i in 0 until originExtractor.trackCount) {
                        if (originExtractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                            originExtractor.selectTrack(i)
                            sourceOriginUs = originExtractor.sampleTime.coerceAtLeast(0L)
                            break
                        }
                    }
                } finally { originExtractor.release() }
            }
            val audioBuf0 = ByteBuffer.allocateDirect(512 * 1024).order(ByteOrder.nativeOrder())

            var audioBuf = audioBuf0
            fun ensureAudioBuf(ext: MediaExtractor): ByteBuffer {
                val need = try { ext.sampleSize.toLong() } catch (_: Throwable) { 0L }
                if (need > audioBuf.capacity().toLong()) {
                    audioBuf = ByteBuffer.allocateDirect(need.toInt() + 4096).order(ByteOrder.nativeOrder())
                    DebugLog.log("AUDIO", "audioBuf 扩容到 ${audioBuf.capacity()}B（样本 ${need}B）")
                }
                return audioBuf
            }
            if (includeAudio && tmp != null && tmp.exists()) {
                runCatching {
                    val ae = MediaExtractor()
                    ae.setDataSource(tmp.absolutePath)
                    for (i in 0 until ae.trackCount) {
                        val f = ae.getTrackFormat(i)
                        val m = f.getString(MediaFormat.KEY_MIME) ?: continue
                        if (m.startsWith("audio/")) { audioFormat = f; ae.selectTrack(i); srcHasAudio = true; break }
                    }
                    if (srcHasAudio && audioFormat != null) {
                        audioExt = ae
                        DebugLog.log("AUDIO", "首音频sample pts=${ae.sampleTime}us track=${ae.sampleTrackIndex} 视频起点=${sourceOriginUs}us")
                    } else runCatching { ae.release() }
                }
                val afLog = audioFormat
                if (afLog != null) {
                    DebugLog.log("AUDIO", "逐帧路径 源音频 mime=${afLog.getString(MediaFormat.KEY_MIME)} → 直通")
                } else {
                    DebugLog.log("AUDIO", "逐帧路径 源无音频轨道")
                }
            }

            fun drainAudio(upToUs: Long) {
                val ae = audioExt ?: return
                if (!muxStarted || audioMuxTrack < 0 || audioEos) return
                try {
                    while (true) {
                        val at = ae.sampleTime
                        // Negative AAC pre-roll PTS is valid; track index signals EOS.
                        if (ae.sampleTrackIndex < 0) { audioEos = true; return }
                        val audioPts = at - sourceOriginUs + prefixPts
                        if (audioPts < prefixPts) { ae.advance(); continue }
                        if (audioPts > upToUs + 200_000L) return
                        audioBuf = ensureAudioBuf(ae)
                        audioBuf.clear()
                        val n = ae.readSampleData(audioBuf, 0)
                        if (n < 0) { audioEos = true; return }
                        val ab = MediaCodec.BufferInfo()
                        ab.set(0, n, audioPts, 0)
                        mmux.writeSampleData(audioMuxTrack, audioBuf, ab)
                        ae.advance()
                    }
                } catch (t: Throwable) {
                    DebugLog.log("AUDIO", "音频直通写失败（${t.javaClass.simpleName}: ${t.message}），停止音频")
                    audioEos = true
                }
            }

            fun drainEncoder(endOfStream: Boolean): Boolean {

                if (muxStarted && audioExt != null && audioMuxTrack >= 0 && !audioEos) drainAudio(videoPts)
                var idleTries = 0
                val drainStart = System.currentTimeMillis()
                val drainTimeout = if (endOfStream) 50_000L else 10_000L
                val maxIdle = if (endOfStream) 400 else Int.MAX_VALUE
                while (true) {

                    if (endOfStream && System.currentTimeMillis() - drainStart > 15_000) {
                        DebugLog.log("ENC", "drain 15s wall-clock 强制收尾（未收到 EOS）")
                        return false
                    }
                    val idx = enc.dequeueOutputBuffer(encInfo, drainTimeout)
                    when {
                        idx >= 0 -> {
                            val buf = enc.getOutputBuffer(idx)
                            if (buf != null && encInfo.size > 0 && muxStarted) {
                                buf.position(encInfo.offset)
                                buf.limit(encInfo.offset + encInfo.size)
                                mmux.writeSampleData(muxTrack, buf, encInfo)
                            }
                            enc.releaseOutputBuffer(idx, false)
                            idleTries = 0
                            if (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return true
                        }
                        idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (!muxStarted) {
                                muxTrack = mmux.addTrack(enc.outputFormat)
                                val af = audioFormat
                                if (audioExt != null && af != null) {
                                    audioMuxTrack = runCatching { mmux.addTrack(af) }.getOrElse {
                                        DebugLog.log("AUDIO", "音频直通 addTrack 失败（${af.getString(MediaFormat.KEY_MIME)}：${it.message}），视频单独封装")
                                        -1
                                    }
                                }
                                mmux.start(); muxStarted = true
                            }
                        }
                        else -> {
                            if (!endOfStream) return muxStarted
                            if (++idleTries > maxIdle) return false
                        }
                    }
                }
            }
            val deadline = System.currentTimeMillis() + 120_000

            fun feed(bmp: Bitmap, ptsUs: Long) {
                check(bmp.width == encW && bmp.height == encH) {
                    "水印帧尺寸 ${bmp.width}x${bmp.height} 与输出 ${encW}x${encH} 不一致，拒绝缩放帧"
                }
                val src = bmp
                val yuv = argbToYuv420(src, encW, encH, encPlanar, std)
                val t0 = System.currentTimeMillis()
                var idx = -1
                while (System.currentTimeMillis() - t0 < 15_000) {
                    idx = enc.dequeueInputBuffer(2_000)
                    if (idx >= 0) break
                    drainEncoder(false)
                }
                if (idx < 0) {
                    DebugLog.log("ENC", "编码器输入槽 15s 无响应 encW=$encW encH=$encH fmt=$realCodec planar=$encPlanar")
                    throw EncoderDeadException("编码器输入阻塞")
                }
                val ib = enc.getInputBuffer(idx) ?: throw EncoderDeadException("编码器输入缓冲不可用")

                val need = encW * encH * 3 / 2
                ib.clear(); ib.put(yuv, 0, need)
                enc.queueInputBuffer(idx, 0, need, ptsUs.coerceAtLeast(0), 0)
            }

            val prefixTotal = if (prefixFrame != null)
                ((prefixDurationMs * fps + 999) / 1000).coerceAtLeast(1) else 0
            val prefixIntervalUs = 1_000_000L / fps
            prefixPts = 0L
            if (prefixFrame != null) {
                var prefixDone = 0
                while (prefixDone < prefixTotal && System.currentTimeMillis() < deadline) {

                    EditorState.exportTimeSec = prefixPts / 1_000_000f
                    val watermarked = onFrame(prefixFrame)
                    feed(watermarked, prefixPts)
                    if (watermarked !== prefixFrame && !reuseOutput) runCatching { watermarked.recycle() }
                    prefixPts += prefixIntervalUs
                    prefixDone++
                    drainEncoder(false)
                }
            }

            DebugLog.log("RENDER", "逐帧导出参数快照: warp=${EditorState.params.warp} " +
                "edgeWarp=${EditorState.params.edgeWarp} innerWarp=${EditorState.params.innerWarp} " +
                "blur=${EditorState.params.blur} refractA=${EditorState.params.refractA} " +
                "chromatic=${EditorState.params.chromatic} opacity=${EditorState.params.opacity} " +
                "shadow=${EditorState.params.shadow} maxEdge=480")

            var framesDone = 0
            var fi = 0
            val totalFramesEst = runCatching {
                val mmr = android.media.MediaMetadataRetriever()
                try {
                    if (srcUri != null) mmr.setDataSource(ctx, srcUri)
                    else mmr.setDataSource(tmp!!.absolutePath)
                    val durMs = mmr.extractMetadata(
                        android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull() ?: 0L
                    durEstMs = durMs
                    (durMs * fps / 1000L).toInt()
                } finally { runCatching { mmr.release() } }
            }.getOrDefault(0)

            val decodeTimeoutMs = (300_000L).coerceAtLeast(
                durEstMs * 6 + totalFramesEst.toLong() * 150L

            ).coerceAtMost(120 * 60_000L).toInt()

            videoPts = prefixPts
            var lastUiPush = 0L
            val ok = MotionDecoder.decodeFrames(
                ctx, mp4, 8192, 240, decodeTimeoutMs, Int.MAX_VALUE,
                rotationOverride = rotation,
                srcUri = srcUri,

                maxDurationMs = maxDurationMs,
                startPtsUs = startPtsUs,
                onFrame = { bmp, delayMs ->
                    videoPts = prefixPts + MotionDecoder.currentFrameTimeUs

                    EditorState.exportTimeSec = videoPts / 1_000_000f
                    val watermarked = onFrame(bmp)
                try {

                    feed(watermarked, videoPts)
                } catch (e: EncoderDeadException) {
                    if (watermarked !== bmp) runCatching { bmp.recycle() }
                    if (!reuseOutput) runCatching { watermarked.recycle() }
                    throw e
                }

                // Last-frame extent is used only for completeness/progress checks.
                videoPts += delayMs.coerceAtLeast(1) * 1000L
                if (watermarked !== bmp) runCatching { bmp.recycle() }

                if (!reuseOutput) watermarked.recycle()
                fi++
                framesDone = fi

                val now = System.currentTimeMillis()
                if (now - lastUiPush > 250) {
                    lastUiPush = now
                    val decodedMs = videoPts / 1000L
                    val pct = if (durEstMs > 0)
                        (12 + 78 * decodedMs / durEstMs).toInt().coerceIn(12, 90)
                    else min(90, 12 + fi / 3)
                    onProgress(pct)
                }
                true
                }
            )
            if (!ok || framesDone < 1) {
                lastFailReason = if (lastFailReason.isBlank()) "解码器无有效帧输出" else lastFailReason
                return null
            }

            if (!MotionDecoder.lastRunReachedEnd) {
                lastFailReason = "解码未完成：在 ${videoPts / 1000}ms 处停滞，系统解码器与抽帧均未跑完全片"
                return null
            }

            var eIdx = -1
            val finishDeadline = System.currentTimeMillis() + 30_000
            onProgress(92)
            while (System.currentTimeMillis() < finishDeadline) {
                eIdx = enc.dequeueInputBuffer(50_000)
                if (eIdx >= 0) break
                drainEncoder(false)
            }
            if (eIdx < 0) throw EncoderDeadException("编码器收尾超时")
            enc.queueInputBuffer(eIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            onProgress(95)
            if (!drainEncoder(true)) return null.also { lastFailReason = "编码器收尾排空失败" }
            onProgress(100)

            val audioCapUs = videoPts + 200_000L
            if (audioExt != null && audioMuxTrack >= 0 && !audioEos && muxStarted) {
                var guard = 0
                try {
                    while (guard < 200_000) {
                        val at = audioExt!!.sampleTime
                        if (audioExt!!.sampleTrackIndex < 0) break
                        val audioPts = at - sourceOriginUs + prefixPts
                        if (audioPts < prefixPts) { audioExt!!.advance(); continue }
                        if (audioPts > audioCapUs) {
                            DebugLog.log("AUDIO", "音频收尾排空到视频末帧+200ms 为止（丢弃尾部 " +
                                "${(audioPts - audioCapUs) / 1000}ms，防绿幕）")
                            break
                        }
                        audioBuf = ensureAudioBuf(audioExt!!)
                        audioBuf.clear()
                        val n = audioExt!!.readSampleData(audioBuf, 0)
                        if (n < 0) break
                        val ab = MediaCodec.BufferInfo()
                        ab.set(0, n, audioPts, 0)
                        mmux.writeSampleData(audioMuxTrack, audioBuf, ab)
                        audioExt!!.advance()
                        guard++
                    }
                    audioEos = true
                } catch (t: Throwable) {
                    DebugLog.log("AUDIO", "音频收尾排空失败（${t.javaClass.simpleName}: ${t.message}）")
                    audioEos = true
                }
            }
            mmux.stop()
            mmux.release()
            mux = null

            val decodedEndMs = videoPts / 1000L
            var outDur = -1L

            if (durEstMs > 1000L && tmpOut.exists()) {
                outDur = runCatching {
                    val r = android.media.MediaMetadataRetriever()
                    try { r.setDataSource(tmpOut.absolutePath)
                        r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: -1L
                    } finally { runCatching { r.release() } }
                }.getOrDefault(-1L)
                DebugLog.log("ENC", "时长自检 元数据=$durEstMs ms 解码终点=${decodedEndMs}ms 输出=${outDur}ms 帧=$framesDone")
                val coveragePct = (100.0 * decodedEndMs / durEstMs.coerceAtLeast(1)).toInt()

                val expectEndMs = if (maxDurationMs > 0) maxDurationMs else durEstMs

                val coversSource = expectEndMs <= 0L ||
                    decodedEndMs >= expectEndMs - maxOf(500L, expectEndMs / 10L)
                val verifyOk = outDur >= 0L && outDur >= decodedEndMs - 800L && coversSource
                DebugLog.log("ENC", "EXPORT_VERIFY: sourceDurationMs=$durEstMs outputDurationMs=$outDur decodedEndMs=${decodedEndMs}ms decodedFrames=$framesDone coverage=${coveragePct}% expectEnd=${expectEndMs}ms coversSource=$coversSource validation=${if (verifyOk) "PASS" else "FAIL"}")
                if (!verifyOk) {
                    lastFailReason = if (!coversSource)
                        "视频被截断（只解码到 ${decodedEndMs}ms，源 ${durEstMs}ms，覆盖率 ${coveragePct}%），已删除残缺文件"
                    else
                        "视频被截断（解码终点 ${decodedEndMs}ms，输出仅 ${outDur}ms），已删除残缺文件"
                    runCatching { tmpOut.delete() }
                    return null
                }
            }

            if (srcHasAudio && includeAudio && tmpOut.exists()) {
                val outHasAudio = runCatching {
                    val v = MediaExtractor()
                    try {
                        v.setDataSource(tmpOut.absolutePath)
                        var has = false
                        for (i in 0 until v.trackCount) {
                            val m = v.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                            if (m.startsWith("audio/")) { has = true; break }
                        }
                        has
                    } finally { runCatching { v.release() } }
                }.getOrDefault(false)
                DebugLog.log("AUDIO", "音频验证：源=${if (srcHasAudio) "有" else "无"} 输出=${if (outHasAudio) "有" else "无"}")
                if (!outHasAudio) {
                    lastFailReason = "EXPORT_AUDIO_LOST：源有音频但输出无音频"
                    runCatching { tmpOut.delete() }
                    return null
                }
            }

            if (!tmpOut.exists() || tmpOut.length() <= 0) {
                lastFailReason = "输出文件为空"
                runCatching { tmpOut.delete() }
                return null
            }
            runCatching { outFile.delete() }
            val renamed = tmpOut.renameTo(outFile)
            DebugLog.log("ENC", "导出完成 out=${outFile.name} dur=${outDur}ms frames=$framesDone renamed=$renamed")
            return if (renamed && outFile.exists() && outFile.length() > 0) outFile
            else null.also { lastFailReason = "最终文件替换失败" }
        } finally {
            runCatching { encoder?.stop() }; runCatching { encoder?.release() }
            runCatching { mux?.stop() }; runCatching { mux?.release() }
            runCatching { audioExt?.release() }
        }
    }

    private val yR = IntArray(256); private val yG = IntArray(256); private val yB = IntArray(256)
    private val uR = IntArray(256); private val uG = IntArray(256); private val uB = IntArray(256)
    private val vR = IntArray(256); private val vG = IntArray(256); private val vB = IntArray(256)
    init {
        for (i in 0..255) {
            yR[i] = (66 * i); yG[i] = (129 * i); yB[i] = (25 * i)
            uR[i] = (-38 * i); uG[i] = (-74 * i); uB[i] = (112 * i)
            vR[i] = (112 * i); vG[i] = (-94 * i); vB[i] = (-18 * i)
        }
    }

    private data class YuvMat(
        val yR: IntArray, val yG: IntArray, val yB: IntArray,
        val uR: IntArray, val uG: IntArray, val uB: IntArray,
        val vR: IntArray, val vG: IntArray, val vB: IntArray
    )

    private val mat601 by lazy { YuvMat(yR, yG, yB, uR, uG, uB, vR, vG, vB) }
    private val mat709 by lazy {
        val yR = IntArray(256); val yG = IntArray(256); val yB = IntArray(256)
        val uR = IntArray(256); val uG = IntArray(256); val uB = IntArray(256)
        val vR = IntArray(256); val vG = IntArray(256); val vB = IntArray(256)
        for (i in 0..255) {
            yR[i] = 47 * i; yG[i] = 157 * i; yB[i] = 16 * i
            uR[i] = -29 * i; uG[i] = -99 * i; uB[i] = 128 * i
            vR[i] = 128 * i; vG[i] = -116 * i; vB[i] = -12 * i
        }
        YuvMat(yR, yG, yB, uR, uG, uB, vR, vG, vB)
    }
    private val mat2020 by lazy {
        val yR = IntArray(256); val yG = IntArray(256); val yB = IntArray(256)
        val uR = IntArray(256); val uG = IntArray(256); val uB = IntArray(256)
        val vR = IntArray(256); val vG = IntArray(256); val vB = IntArray(256)
        for (i in 0..255) {
            yR[i] = 58 * i; yG[i] = 149 * i; yB[i] = 13 * i
            uR[i] = -42 * i; uG[i] = -146 * i; uB[i] = 188 * i
            vR[i] = 188 * i; vG[i] = -174 * i; vB[i] = -14 * i
        }
        YuvMat(yR, yG, yB, uR, uG, uB, vR, vG, vB)
    }

    private fun matFor(standard: Int): YuvMat = when (standard) {
        MediaFormat.COLOR_STANDARD_BT709 -> mat709
        MediaFormat.COLOR_STANDARD_BT2020 -> mat2020
        else -> mat601
    }

    private fun sourceColorStandard(tmp: File?, encW: Int, encH: Int): Int {
        val std = runCatching {
            val ex = MediaExtractor()
            try {
                if (tmp != null) ex.setDataSource(tmp.absolutePath) else return@runCatching null
                for (i in 0 until ex.trackCount) {
                    val f = ex.getTrackFormat(i)
                    if (f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                        return@runCatching f.getInteger(MediaFormat.KEY_COLOR_STANDARD)
                    }
                }
                null
            } finally { ex.release() }
        }.getOrNull()
        return std ?: if (encW >= 1280 || encH >= 720)
            MediaFormat.COLOR_STANDARD_BT709 else MediaFormat.COLOR_STANDARD_BT601_NTSC
    }

    private val pxBufLocal = ThreadLocal.withInitial { IntArray(0) }
    private val yuvBufLocal = ThreadLocal.withInitial { ByteArray(0) }

    /** Use source presentation timestamps when the container omits its nominal frame-rate hint. */
    private fun estimateSourceFrameRate(file: File): Int? = runCatching {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val videoTrack = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: return@runCatching null
            extractor.selectTrack(videoTrack)
            val timestamps = ArrayList<Long>(120)
            var minPtsUs = Long.MAX_VALUE
            var maxPtsUs = Long.MIN_VALUE
            while (timestamps.size < 240) {
                val ptsUs = extractor.sampleTime
                if (ptsUs < 0L) break
                timestamps += ptsUs
                minPtsUs = minOf(minPtsUs, ptsUs)
                maxPtsUs = maxOf(maxPtsUs, ptsUs)
                extractor.advance()
                if (timestamps.size >= 2 && maxPtsUs - minPtsUs >= 2_000_000L) break
            }
            val ordered = timestamps.distinct().sorted()
            if (ordered.size < 2) return@runCatching null
            val spanUs = ordered.last() - ordered.first()
            if (spanUs <= 0L) return@runCatching null
            ((ordered.size - 1) * 1_000_000.0 / spanUs).roundToInt().takeIf { it in 1..240 }
        } finally {
            extractor.release()
        }
    }.getOrNull()

    private fun argbToYuv420(bmp: Bitmap, w: Int, h: Int, planar: Boolean,
                              standard: Int = MediaFormat.COLOR_STANDARD_BT601_NTSC): ByteArray {
        val n = w * h

        var px = pxBufLocal.get()!!
        if (px.size < n) { px = IntArray(n); pxBufLocal.set(px) }
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val ySize = n
        val uvSize = ySize / 4
        val need = ySize + uvSize * 2
        var out = yuvBufLocal.get()!!
        if (out.size < need) { out = ByteArray(need); yuvBufLocal.set(out) }
        var yi = 0; var ui = ySize; var vi = ySize + uvSize
        val mat = matFor(standard)
        val rT = mat.yR; val gT = mat.yG; val bT = mat.yB
        val uRT = mat.uR; val uGT = mat.uG; val uBT = mat.uB
        val vRT = mat.vR; val vGT = mat.vG; val vBT = mat.vB

        for (j in 0 until h) {
            val rowBase = j * w
            if (j and 1 != 0) {

                for (i in 0 until w) {
                    val c = px[rowBase + i]
                    out[yi++] = (((rT[(c shr 16) and 0xFF] + gT[(c shr 8) and 0xFF] + bT[c and 0xFF] + 128) shr 8) + 16).toByte()
                }
            } else if (planar) {
                var i = 0
                while (i < w) {
                    val c = px[rowBase + i]
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF
                    out[yi++] = (((rT[r] + gT[g] + bT[b] + 128) shr 8) + 16).toByte()
                    if (i and 1 == 0) {
                        out[ui++] = (((uRT[r] + uGT[g] + uBT[b] + 128) shr 8) + 128).toByte()
                        out[vi++] = (((vRT[r] + vGT[g] + vBT[b] + 128) shr 8) + 128).toByte()
                    }
                    i++
                }
            } else {
                var i = 0
                while (i < w) {
                    val c = px[rowBase + i]
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF
                    out[yi++] = (((rT[r] + gT[g] + bT[b] + 128) shr 8) + 16).toByte()
                    if (i and 1 == 0) {
                        out[ui++] = (((uRT[r] + uGT[g] + uBT[b] + 128) shr 8) + 128).toByte()
                        out[ui++] = (((vRT[r] + vGT[g] + vBT[b] + 128) shr 8) + 128).toByte()
                    }
                    i++
                }
            }
        }
        return out
    }

    fun hasHevcEncoder(): Boolean = try {
        MediaCodecList(MediaCodecList.REGULAR_CODECS)
            .findEncoderForFormat(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, 2, 2)) != null
    } catch (_: Throwable) { false }
}
