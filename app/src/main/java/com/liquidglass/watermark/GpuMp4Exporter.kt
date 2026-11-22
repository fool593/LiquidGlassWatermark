package com.liquidglass.watermark

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Build
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Surface video pipeline: MediaCodec decoder -> OES texture -> native Bitmap transfer
 * -> shared preview material renderer -> reused GL texture -> Surface encoder.
 * The CPU material stage intentionally matches still/GIF preview; failed transfers
 * return null so the caller can retry using the same renderer with buffer decoding.
 */
object GpuMp4Exporter {

    private const val EGL_RECORDABLE_ANDROID = 0x3142
    private const val EGL_OPENGL_ES2_BIT = 4

    @Volatile private var maskBBox: IntArray? = null

    data class Result(val file: File, val durationMs: Long, val frames: Int)

    private class WatermarkMissingException(msg: String) : RuntimeException(msg)

    private fun createBestEncoder(mime: String): MediaCodec {
        val needSurface = true
        val hwNames = runCatching {
            android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS).codecInfos
                .filter { it.isEncoder && mime in it.supportedTypes }
                .filter { ci ->
                    if (android.os.Build.VERSION.SDK_INT >= 29) ci.isHardwareAccelerated
                    else !(ci.name.contains("google", true) || ci.name.contains("c2.android", true))
                }
                .filter { ci ->
                    !needSurface || (runCatching {
                        ci.getCapabilitiesForType(mime).colorFormats.contains(
                            android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    }.getOrDefault(false))
                }
                .map { it.name }
        }.getOrDefault(emptyList())
        for (name in hwNames) {
            val mc = runCatching { MediaCodec.createByCodecName(name) }.getOrNull()
            if (mc != null) {
                DebugLog.log("CAPS", "选用硬件编码器 $name（API=${android.os.Build.VERSION.SDK_INT}）")
                return mc
            }
        }
        DebugLog.log("CAPS", "无可用硬件编码器，回退系统默认实例（API=${android.os.Build.VERSION.SDK_INT}）")
        return MediaCodec.createEncoderByType(mime)
    }

    /** Bitrate floor preserves at least the source track bitrate during watermark re-encoding. */
    private fun bitrateFor(encW: Int, encH: Int, fps: Int, mime: String, sourceBitrate: Int): Int {
        val base = if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) 0.12 else 0.20
        val target = (encW.toDouble() * encH * fps * base)
            .toLong().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return maxOf(target, sourceBitrate, 3_000_000)
    }

    fun export(
        ctx: Context,
        srcFile: File,
        outFile: File,
        encW: Int,
        encH: Int,
        fps: Int,
        rotation: Int,
        codec: String,
        watermarkRenderer: WatermarkEditorView,
        onProgress: (Int) -> Unit,
        isCancelled: () -> Boolean = { false },

        cpuWatermark: Boolean = true,

        includeAudio: Boolean = true,

        maxDurationMs: Long = 0L,

        startPtsUs: Long = 0L
    ): Result? {
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var inputSurface: InputSurface? = null
        var outputSurface: OutputSurface? = null
        var textureRender: TextureRender? = null
        var watermarkTex = 0
        var maskTex = 0
        var distTex = 0
        var distFallbackTex = 0
        var audioExt: MediaExtractor? = null

        var tmpOut: File? = null
        var committed = false
        try {

            extractor = MediaExtractor()
            extractor.setDataSource(srcFile.absolutePath)
            var trackIndex = -1
            var trackFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) { trackIndex = i; trackFormat = f; break }
            }
            if (trackIndex < 0 || trackFormat == null) {
                DebugLog.log("GPU", "无视频轨道")
                return null
            }
            val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: return null
            val sourceBitrate = runCatching { trackFormat.getInteger(MediaFormat.KEY_BIT_RATE) }
                .getOrDefault(0)

            var durUs = runCatching { trackFormat.getLong(MediaFormat.KEY_DURATION) }.getOrDefault(0L)
            if (durUs <= 0) {
                durUs = runCatching {
                    val mmr = android.media.MediaMetadataRetriever()
                    try {
                        mmr.setDataSource(srcFile.absolutePath)
                        (mmr.extractMetadata(
                            android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
                        )?.toLong() ?: 0L) * 1000L
                    } finally { runCatching { mmr.release() } }
                }.getOrDefault(0L)
                if (durUs > 0) DebugLog.log("GPU", "轨道无 duration，已用元数据回退 durUs=$durUs")
            }
            // Source PTS remain authoritative for variable-rate footage.
            val timeline = VideoTimeline(fps)
            extractor.selectTrack(trackIndex)

            val startPtsUsEff = startPtsUs.coerceAtLeast(0L)
            if (startPtsUsEff > 0L) {
                extractor.seekTo((startPtsUsEff - 100_000L).coerceAtLeast(0L),
                    MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                DebugLog.log("GPU", "剪辑起点 seek=${startPtsUsEff / 1000}ms")
            }

            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            var audioMuxTrack = -1
            var audioEos = false
            val audioBuf = ByteBuffer.allocateDirect(512 * 1024).order(ByteOrder.nativeOrder())
            if (includeAudio) {
                for (i in 0 until extractor.trackCount) {
                    val f = extractor.getTrackFormat(i)
                    val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("audio/")) { audioTrackIndex = i; audioFormat = f; break }
                }
                if (audioTrackIndex >= 0 && audioFormat != null) {
                    audioExt = MediaExtractor().also {
                        it.setDataSource(srcFile.absolutePath)
                        it.selectTrack(audioTrackIndex)
                    }
                    val amime = audioFormat.getString(MediaFormat.KEY_MIME)
                    val sr = runCatching { audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrDefault(0)
                    val ch = runCatching { audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(0)
                    DebugLog.log("AUDIO", "源音频轨道 idx=$audioTrackIndex mime=$amime sr=$sr ch=$ch → 直通")
                } else {
                    DebugLog.log("AUDIO", "源无音频轨道")
                }
            } else {
                DebugLog.log("AUDIO", "本次导出不需要音频（动态照片路径）")
            }

            val realCodec = if (codec == MediaFormat.MIMETYPE_VIDEO_HEVC &&
                runCatching {
                    android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)
                        .findEncoderForFormat(MediaFormat.createVideoFormat(codec, 2, 2))
                }.getOrNull() != null
            ) codec else MediaFormat.MIMETYPE_VIDEO_AVC

            encoder = createBestEncoder(realCodec)

            runCatching {
                val ci = android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS).codecInfos.firstOrNull {
                    it.isEncoder && realCodec in it.supportedTypes
                }
                val hw = if (android.os.Build.VERSION.SDK_INT >= 29)
                    (ci?.isHardwareAccelerated ?: false)
                else !(ci?.name?.contains("google", true) ?: true)
                val cap = ci?.getCapabilitiesForType(realCodec)
                val surfaceOk = cap?.colorFormats?.contains(
                    android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) ?: false
                DebugLog.log("CAPS", "API=${android.os.Build.VERSION.SDK_INT} " +
                    "encoder=${ci?.name ?: "默认"} hw=$hw surfaceIn=$surfaceOk " +
                    "codec=$realCodec ${encW}x${encH}@${fps} bitrate=${bitrateFor(encW, encH, fps, realCodec, sourceBitrate) / 1000}kbps")
            }
            val efmt = MediaFormat.createVideoFormat(realCodec, encW, encH)
            efmt.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            efmt.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            efmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

            val bitrate = bitrateFor(encW, encH, fps, realCodec, sourceBitrate)
                .coerceIn(3_000_000, 60_000_000)
            efmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            // RGB surfaces carry decoder dataspace. Match the source primaries;
            // forcing BT.601 for BT.709 footage changes saturation and hue.
            val sourceStandard = runCatching {
                trackFormat.getInteger(MediaFormat.KEY_COLOR_STANDARD)
            }.getOrDefault(if (encW >= 1280 || encH >= 720)
                MediaFormat.COLOR_STANDARD_BT709 else MediaFormat.COLOR_STANDARD_BT601_NTSC)
            efmt.setInteger(MediaFormat.KEY_COLOR_STANDARD, sourceStandard)
            efmt.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            efmt.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            encoder.configure(efmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val encoderInputSurface = encoder.createInputSurface()
            inputSurface = InputSurface(encoderInputSurface)
            inputSurface.makeCurrent()
            encoder.start()

            outputSurface = OutputSurface(encW, encH)
            textureRender = TextureRender(enableLegacyShader = !cpuWatermark)

            runCatching { textureRender.setRotation(rotation) }

            // Our copy pass applies source and user rotation exactly once.
            trackFormat.setInteger(MediaFormat.KEY_ROTATION, 0)
            decoder = CodecSelector.createConfiguredDecoder(
                mime, trackFormat, outputSurface.surface, 0, preferHardwareFirst = true, maxCandidates = 3)
            if (decoder == null) {
                DebugLog.log("DECODE", "GPU路径 无可配置解码器 mime=$mime（默认+候选全失败）")
                return null
            }
            DebugLog.log("DECODE", "GPU路径 选中解码器=${decoder.name} mime=$mime")
            decoder.start()

            // ── 6. Muxer ──
            tmpOut = File(outFile.parentFile ?: ctx.cacheDir, outFile.name + ".tmp")
            runCatching { tmpOut?.delete() }
            muxer = MediaMuxer(tmpOut!!.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var muxTrack = -1
            var muxStarted = false

            val bufferInfo = MediaCodec.BufferInfo()
            var inputEos = false
            var outputEos = false
            var encoderEos = false
            var framesEncoded = 0
            var lastPtsUs = 0L
            var lastProgress = -1

            val deadline = System.currentTimeMillis() +
                maxOf(10 * 60_000L, if (durUs > 0) durUs / 1000L * 8L else 0L)
            val startTime = System.currentTimeMillis()

            val firstFrameDeadline = startTime + 5000

            maskBBox = intArrayOf(0, 0, encW, encH)

            var tDecodeWait = 0L; var tCapture = 0L; var tCpuRender = 0L
            var tUpload = 0L; var tDraw = 0L; var tSwap = 0L; var tLoopStart = 0L

            while (!outputEos && System.currentTimeMillis() < deadline) {
                if (framesEncoded == 0 && System.currentTimeMillis() > firstFrameDeadline) {
                    throw RuntimeException("首帧 5 秒未渲染（GPU=${inputSurface?.gpuInfo?.take(60)}），放弃 GPU 路径")
                }

                if (isCancelled()) {
                    DebugLog.log("GPU", "用户取消导出（已编码 $framesEncoded 帧）")

                    runCatching { tmpOut?.delete() }
                    return null
                }

                if (!inputEos) {
                    val inIdx = decoder.dequeueInputBuffer(10000)
                    if (inIdx >= 0) {
                        val ib = decoder.getInputBuffer(inIdx)

                        if (ib == null) continue
                        ib.clear()
                        val sampleSize = extractor.readSampleData(ib, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEos = true
                        } else {
                            val pts = extractor.sampleTime
                            decoder.queueInputBuffer(inIdx, 0, sampleSize, pts, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = decoder.dequeueOutputBuffer(bufferInfo, 10000)
                when {
                    outIdx >= 0 -> {
                        val eos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        if (eos && bufferInfo.size == 0) {

                            decoder.releaseOutputBuffer(outIdx, false)
                            runCatching { encoder.signalEndOfInputStream() }
                            outputEos = true
                            DebugLog.log("GPU", "解码 EOS 已编码=$framesEncoded 末帧PTS=${lastPtsUs / 1000}ms")
                        } else if (isConfig) {

                            decoder.releaseOutputBuffer(outIdx, false)
                        } else {

                            if (startPtsUsEff > 0 && bufferInfo.presentationTimeUs < startPtsUsEff) {
                                decoder.releaseOutputBuffer(outIdx, false)
                            } else if (maxDurationMs > 0 &&
                                (bufferInfo.presentationTimeUs - startPtsUsEff) >= maxDurationMs * 1000L) {

                                decoder.releaseOutputBuffer(outIdx, false)
                                runCatching { encoder.signalEndOfInputStream() }
                                outputEos = true
                                DebugLog.log("GPU", "时长截断 max=${maxDurationMs}ms 于 PTS=${bufferInfo.presentationTimeUs / 1000}ms 起点=${startPtsUsEff / 1000}ms")
                            } else {

                            decoder.releaseOutputBuffer(outIdx, true)

                            val diagDec0 = System.nanoTime()
                            outputSurface.awaitNewImage()

                            tDecodeWait += System.nanoTime() - diagDec0
                            if (!outputSurface.lastAwaitOk) {

                                outputSurface.awaitNewImage()
                                if (!outputSurface.lastAwaitOk) {
                                    DebugLog.log("GPU", "帧获取失败×2，放弃 GPU 路径回退逐帧（防快进）")
                                    runCatching { tmpOut?.delete() }
                                    return null
                                }
                            }

                            inputSurface.makeCurrent()
                            textureRender.setTextureTransform(outputSurface.texMatrix)
                            val outPtsUs = timeline.next(bufferInfo.presentationTimeUs - startPtsUsEff)
                            EditorState.exportTimeSec = outPtsUs / 1_000_000f

                            val bb = maskBBox
                            val barH = if (bb != null && bb.size >= 4 && bb[3] > bb[1])
                                (bb[3] - bb[1]).toFloat() else encH * 0.10f
                            if (cpuWatermark) {

                                val t0 = System.nanoTime()
                                val raw = textureRender.captureVideo2d(outputSurface.textureId, encW, encH)
                                val t1 = System.nanoTime()
                                if (raw != null) {
                                    // Animation follows source media time, regardless of export speed.
                                    EditorState.exportTimeSec = outPtsUs / 1_000_000f
                                    val wm = watermarkRenderer.renderWatermarkOnly(raw, encW, encH)
                                    val t2 = System.nanoTime()
                                    textureRender.uploadBlit(wm, encW, encH)
                                    val t3 = System.nanoTime()
                                    textureRender.drawFrameBlit(encW, encH)
                                    val t4 = System.nanoTime()
                                    tCapture += t1 - t0; tCpuRender += t2 - t1
                                    tUpload += t3 - t2; tDraw += t4 - t3

                                } else {
                                    throw IllegalStateException("视频帧读回失败，回退同引擎逐帧路径")
                                }
                            }
                            // Encoder and animation use the same normalized source timestamp.
                            inputSurface.setPresentationTime(outPtsUs * 1000L)
                            val tSwap0 = System.nanoTime()
                            val swapped = inputSurface.swapBuffers()
                            tSwap += System.nanoTime() - tSwap0
                            if (framesEncoded < 10) {
                                DebugLog.log("GPU", "帧[$framesEncoded] pts=${outPtsUs}us size=${bufferInfo.size} flags=${bufferInfo.flags} swap=$swapped")
                            }
                            check(swapped) { "编码器表面提交失败" }
                            lastPtsUs = outPtsUs
                            framesEncoded++
                            if (eos) { encoder.signalEndOfInputStream(); outputEos = true }

                            if (durUs > 0) {
                                val pct = (12 + 78 * bufferInfo.presentationTimeUs / durUs).toInt().coerceIn(12, 90)
                                if (pct != lastProgress) { onProgress(pct); lastProgress = pct }
                            }
                            }
                        }
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {

                    }
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {

                    }
                }

                val mmux = muxer
                if (mmux != null) {
                    var encDone = false
                    while (!encDone) {
                        val encIdx = encoder.dequeueOutputBuffer(bufferInfo, 0)
                        when {
                            encIdx >= 0 -> {
                                val encData = encoder.getOutputBuffer(encIdx)
                                if (muxStarted && encData != null && bufferInfo.size > 0) {
                                    encData.position(bufferInfo.offset)
                                    encData.limit(bufferInfo.offset + bufferInfo.size)
                                    mmux.writeSampleData(muxTrack, encData, bufferInfo)
                                }
                                encoder.releaseOutputBuffer(encIdx, false)
                                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                    encoderEos = true
                                    encDone = true
                                }
                            }
                            encIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {

                                if (!muxStarted) {
                                    muxTrack = mmux.addTrack(encoder.outputFormat)

                                    if (audioExt != null && audioFormat != null) {
                                        audioMuxTrack = runCatching { mmux.addTrack(audioFormat) }.getOrElse {
                                            DebugLog.log("AUDIO", "音频直通 addTrack 失败（容器不支持 ${audioFormat?.getString(MediaFormat.KEY_MIME)}：${it.message}），视频单独封装")
                                            -1
                                        }
                                    }
                                    mmux.start()
                                    muxStarted = true
                                    DebugLog.log("GPU", "muxer 启动 vTrack=$muxTrack aTrack=$audioMuxTrack")
                                }
                            }
                            else -> encDone = true
                        }
                    }
                }

                val aex = audioExt
                if (aex != null && audioMuxTrack >= 0 && muxStarted && !audioEos) {
                    try {
                        while (!audioEos) {
                            val at = aex.sampleTime
                            if (aex.sampleTrackIndex < 0) { audioEos = true; break }

                            if (at < startPtsUsEff) { aex.advance(); continue }
                            if (timeline.audioTimeUs(at) < 0L) { aex.advance(); continue }
                            if (timeline.audioTimeUs(at) > lastPtsUs + 200_000L) break
                            audioBuf.clear()
                            val n = aex.readSampleData(audioBuf, 0)
                            if (n < 0) { audioEos = true; break }
                            val ab = MediaCodec.BufferInfo()
                            ab.set(0, n, timeline.audioTimeUs(at), 0)
                            mmux.writeSampleData(audioMuxTrack, audioBuf, ab)
                            aex.advance()
                        }
                    } catch (t: Throwable) {
                        DebugLog.log("AUDIO", "音频直通写失败（${t.javaClass.simpleName}: ${t.message}），停止音频写入")
                        audioEos = true
                    }
                }
            }

            check(outputEos) { "视频导出超时，拒绝保存未完成视频" }
            val mmux2 = muxer
            if (mmux2 != null) {
                var drain = !encoderEos
                val drainDeadline = System.currentTimeMillis() + 30_000L
                while (drain && System.currentTimeMillis() < drainDeadline) {
                    if (isCancelled()) return null
                    val encIdx = encoder.dequeueOutputBuffer(bufferInfo, 10000)
                    when {
                        encIdx >= 0 -> {
                            val encData = encoder.getOutputBuffer(encIdx)
                            if (muxStarted && encData != null && bufferInfo.size > 0) {
                                encData.position(bufferInfo.offset)
                                encData.limit(bufferInfo.offset + bufferInfo.size)
                                mmux2.writeSampleData(muxTrack, encData, bufferInfo)
                            }
                            encoder.releaseOutputBuffer(encIdx, false)
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                encoderEos = true; drain = false
                            }
                        }
                        encIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (!muxStarted) {
                                muxTrack = mmux2.addTrack(encoder.outputFormat)
                                if (audioExt != null && audioFormat != null) {
                                    audioMuxTrack = runCatching { mmux2.addTrack(audioFormat) }.getOrElse { -1 }
                                }
                                mmux2.start()
                                muxStarted = true
                            }
                        }
                        else -> Unit // Delayed encoder output may need several polls.
                    }
                }
            }

            check(encoderEos) { "编码器未完成尾帧输出" }

            val aex2 = audioExt
            val amux = muxer
            val audioCapUs = lastPtsUs + 200_000L
            if (aex2 != null && audioMuxTrack >= 0 && amux != null && !audioEos) {
                try {
                    while (true) {
                        val at = aex2.sampleTime
                        if (aex2.sampleTrackIndex < 0) break
                        if (isCancelled()) return null
                        if (timeline.audioTimeUs(at) < 0L) { aex2.advance(); continue }
                        if (timeline.audioTimeUs(at) > audioCapUs) {
                            DebugLog.log("AUDIO", "音频收尾排空到视频末帧+200ms 为止（丢弃尾部 " +
                                "${(timeline.audioTimeUs(at) - audioCapUs) / 1000}ms，防绿幕）")
                            break
                        }
                        audioBuf.clear()
                        val n = aex2.readSampleData(audioBuf, 0)
                        if (n < 0) break
                        val ab = MediaCodec.BufferInfo()
                        ab.set(0, n, timeline.audioTimeUs(at), 0)
                        amux.writeSampleData(audioMuxTrack, audioBuf, ab)
                        aex2.advance()
                    }
                    audioEos = true
                } catch (t: Throwable) {
                    DebugLog.log("AUDIO", "音频收尾排空失败（${t.javaClass.simpleName}: ${t.message}）")
                    audioEos = true
                }
            }

            onProgress(95)
            val mmux = muxer
            mmux?.stop()
            mmux?.release()
            muxer = null
            if (!muxStarted) {
                DebugLog.log("GPU", "muxer 未启动（编码器无输出格式），判定失败")
                runCatching { tmpOut?.delete() }
                return null
            }

            if (!tmpOut!!.exists() || tmpOut!!.length() < 1024) {
                DebugLog.log("GPU", "输出文件过小 ${tmpOut!!.length()}，判定失败")
                runCatching { tmpOut?.delete() }
                return null
            }

            val outDurMs = runCatching {
                val mmr = android.media.MediaMetadataRetriever()
                try {
                    mmr.setDataSource(tmpOut!!.absolutePath)
                    mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
                } finally { runCatching { mmr.release() } }
            }.getOrDefault(0L)
            val srcDurMs = durUs / 1000

            val expectDurMs = if (maxDurationMs > 0) maxDurationMs else srcDurMs
            if (outDurMs <= 0 || (srcDurMs > 0 && kotlin.math.abs(outDurMs - expectDurMs) > maxOf(250L, 2000L / fps.coerceAtLeast(1)))) {
                DebugLog.log("GPU", "输出时长异常 out=${outDurMs}ms src=${srcDurMs}ms expect=${expectDurMs}ms frames=$framesEncoded，判定封装失败")
                runCatching { tmpOut?.delete() }
                return null
            }

            val outVideoDurMs = videoTrackDurationMs(tmpOut!!)
            if (expectDurMs > 0 && outVideoDurMs > 0 &&
                outVideoDurMs < expectDurMs - maxOf(500L, expectDurMs / 10L)) {
                DebugLog.log("GPU", "视频轨截断 outVideo=${outVideoDurMs}ms expect=${expectDurMs}ms " +
                    "容器=${outDurMs}ms frames=$framesEncoded（音频撑起了容器时长），判定失败回退逐帧")
                runCatching { tmpOut?.delete() }
                return null
            }

            val expectFrames = if (expectDurMs > 0) (expectDurMs / 1000f * fps).toInt() else 0
            if (expectFrames >= 10 && framesEncoded < expectFrames / 2) {
                DebugLog.log("GPU", "编码帧数不足 out=${framesEncoded}帧 期望≈${expectFrames}帧 " +
                    "（帧管道在开头几帧后断流，视频轨被截断），判定失败回退逐帧路径")
                runCatching { tmpOut?.delete() }
                return null
            }
            DebugLog.log("GPU", "时长校验 容器=${outDurMs}ms 视频轨=${outVideoDurMs}ms 期望=${expectDurMs}ms frames=$framesEncoded")

            val inHasAudio = audioExt != null
            var outHasAudio = false
            runCatching {
                val v = MediaExtractor()
                try {
                    v.setDataSource(tmpOut!!.absolutePath)
                    for (i in 0 until v.trackCount) {
                        val m = v.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                        if (m.startsWith("audio/")) { outHasAudio = true; break }
                    }
                } finally { runCatching { v.release() } }
            }
            if (inHasAudio && !outHasAudio) {
                DebugLog.log("AUDIO", "EXPORT_AUDIO_LOST：源有音频但输出无音频，判定导出失败（回退逐帧路径）")
                runCatching { tmpOut?.delete() }
                return null
            }
            DebugLog.log("AUDIO", "音频验证：输入=${if (inHasAudio) "有" else "无"} 输出=${if (outHasAudio) "有" else "无"} ✓")

            var watermarkMissing = false
            try {
                verifyWatermarkOnOutput(tmpOut!!, srcFile, maskBBox, encW, encH, maxDurationMs)
            } catch (e: WatermarkMissingException) {

                watermarkMissing = true
                DebugLog.log("GPU", "水印验证失败（水印未真实写入：${e.message}）→ 删除坏输出，回退逐帧路径")
            } catch (t: Throwable) {

                DebugLog.log("GPU", "水印验证器异常（${t.javaClass.simpleName}: ${t.message}），跳过验证保留输出")
            }
            if (watermarkMissing) {
                runCatching { tmpOut?.delete() }
                return null
            }

            if (!tmpOut!!.renameTo(outFile)) {
                tmpOut!!.copyTo(outFile, overwrite = true)
                tmpOut!!.delete()
            }
            val elapsed = System.currentTimeMillis() - startTime
            DebugLog.log("GPU", "导出成功 frames=$framesEncoded 输出时长=${outDurMs}ms 源时长=${srcDurMs}ms 耗时=${elapsed}ms")

            run {
                val totalNs = (tCapture + tCpuRender + tUpload + tDraw + tSwap).coerceAtLeast(1L)
                fun pct(v: Long) = "%.1f".format(100.0 * v / totalNs)
                val n = framesEncoded.coerceAtLeast(1)
                fun perFrame(v: Long) = "%.1f".format(v / 1_000_000.0 / n)
                val decMs = tDecodeWait / 1_000_000
                val wallAll = elapsed.coerceAtLeast(1L)
                DebugLog.log("PROFILE",
                    "GPU阶段剖析 frames=$framesEncoded 各阶段累计ms: " +
                    "解码等待=$decMs 读回=${tCapture / 1_000_000}(${pct(tCapture)}%) " +
                    "CPU渲染=${tCpuRender / 1_000_000}(${pct(tCpuRender)}%) " +
                    "上传=${tUpload / 1_000_000}(${pct(tUpload)}%) " +
                    "绘制=${tDraw / 1_000_000}(${pct(tDraw)}%) " +
                    "交换=${tSwap / 1_000_000}(${pct(tSwap)}%) " +
                    "| 总墙钟=${elapsed}ms 帧均=" + (elapsed.toDouble() / n).toInt() + "ms/帧")

                DebugLog.log("DIAG1",
                    "每帧均耗ms: 解码等待=${perFrame(tDecodeWait)} 读回=${perFrame(tCapture)} " +
                    "水印渲染=${perFrame(tCpuRender)} 上传=${perFrame(tUpload)} " +
                    "绘制=${perFrame(tDraw)} 编码交换=${perFrame(tSwap)} " +
                    "| 总=${"%.1f".format(elapsed.toDouble() / n)}ms/帧 " +
                    "| 平均fps=${"%.2f".format(n * 1000.0 / wallAll)} " +
                    "| 占墙钟%: 解码等待=${"%.1f".format(100.0 * tDecodeWait / 1_000_000 / wallAll)} " +
                    "读回=${"%.1f".format(100.0 * tCapture / 1_000_000 / wallAll)} " +
                    "水印渲染=${"%.1f".format(100.0 * tCpuRender / 1_000_000 / wallAll)} " +
                    "编码交换=${"%.1f".format(100.0 * tSwap / 1_000_000 / wallAll)}")
            }
            committed = true
            return Result(outFile, outDurMs, framesEncoded)
        } catch (t: Throwable) {
            DebugLog.log("GPU", "导出异常 ${t.javaClass.simpleName}: ${t.message}")
            try { t.printStackTrace() } catch (_: Throwable) {}

            runCatching { tmpOut?.delete() }
            return null
        } finally {
            if (!committed) runCatching { tmpOut?.delete() }
            runCatching { if (watermarkTex != 0) {
                val arr = intArrayOf(watermarkTex); GLES20.glDeleteTextures(1, arr, 0)
            } }
            runCatching { if (maskTex != 0) {
                val arr = intArrayOf(maskTex); GLES20.glDeleteTextures(1, arr, 0)
            } }
            runCatching { if (distTex != 0) {
                val arr = intArrayOf(distTex); GLES20.glDeleteTextures(1, arr, 0)
            } }
            runCatching { if (distFallbackTex != 0) {
                val arr = intArrayOf(distFallbackTex); GLES20.glDeleteTextures(1, arr, 0)
            } }
            runCatching { textureRender?.release() }
            runCatching { outputSurface?.release() }
            runCatching { inputSurface?.release() }
            runCatching { decoder?.stop() }; runCatching { decoder?.release() }
            runCatching { encoder?.stop() }; runCatching { encoder?.release() }
            runCatching { muxer?.release() }
            runCatching { extractor?.release() }
            runCatching { audioExt?.release() }
        }
    }

    private fun videoTrackDurationMs(f: File): Long = runCatching {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(f.absolutePath)
            var result = 0L
            for (i in 0 until ex.trackCount) {
                val fmt = ex.getTrackFormat(i)
                val m = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (!m.startsWith("video/")) continue
                result = runCatching { fmt.getLong(MediaFormat.KEY_DURATION) }.getOrDefault(0L) / 1000L
                break
            }
            result
        } finally {
            runCatching { ex.release() }
        }
    }.getOrDefault(0L)

    private fun verifyWatermarkOnOutput(outFile: File, srcFile: File, bbox: IntArray?, w: Int, h: Int, maxDurationMs: Long = 0L) {
        if (bbox == null) throw RuntimeException("水印蒙版 bbox 缺失")
        val x0 = bbox[0]; val y0 = bbox[1]; val x1 = bbox[2]; val y1 = bbox[3]
        if (x1 - x0 < 8 || y1 - y0 < 8) throw RuntimeException("水印蒙版区域过小")
        val mmr = android.media.MediaMetadataRetriever()
        var outBmp: Bitmap? = null
        var srcBmp: Bitmap? = null
        try {
            mmr.setDataSource(outFile.absolutePath)
            val outDur = runCatching {
                mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            }.getOrDefault(0L)
            outBmp = mmr.getFrameAtTime((outDur ?: 0L) / 2 * 1000,
                android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: throw RuntimeException("回读输出帧失败")

            val srcDur = runCatching {
                val r = android.media.MediaMetadataRetriever()
                try {
                    r.setDataSource(srcFile.absolutePath)
                    r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                } finally { runCatching { r.release() } }
            }.getOrDefault(0L)
            if (srcDur > 0) {
                val rs = android.media.MediaMetadataRetriever()
                try {
                rs.setDataSource(srcFile.absolutePath)

                val srcSeekMs = if (maxDurationMs > 0L) (outDur ?: 0L) / 2 else srcDur / 2
                srcBmp = rs.getFrameAtTime(srcSeekMs * 1000,
                    android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } finally { runCatching { rs.release() } }
            }
            try {

                val sx = outBmp.width.toFloat() / w
                val sy = outBmp.height.toFloat() / h
                val ix0 = (x0 + (x1 - x0) * 0.05f) * sx; val iy0 = (y0 + (y1 - y0) * 0.05f) * sy
                val ix1 = (x1 - (x1 - x0) * 0.05f) * sx; val iy1 = (y1 - (y1 - y0) * 0.05f) * sy

                if (srcBmp != null) {

                    val ssx = srcBmp.width.toFloat() / w
                    val ssy = srcBmp.height.toFloat() / h
                    val sIx0 = (x0 + (x1 - x0) * 0.05f) * ssx; val sIy0 = (y0 + (y1 - y0) * 0.05f) * ssy
                    val sIx1 = (x1 - (x1 - x0) * 0.05f) * ssx; val sIy1 = (y1 - (y1 - y0) * 0.05f) * ssy
                    val (wmDiff, wmDelta, wmFrac) = regionDiff(outBmp, srcBmp, ix0, iy0, ix1, iy1, sIx0, sIy0, sIx1, sIy1)
                    val bgDiff = kotlin.math.max(
                        regionDiff(outBmp, srcBmp,
                            0f, 0f, outBmp.width * 0.5f, outBmp.height * 0.25f,
                            0f, 0f, srcBmp.width * 0.5f, srcBmp.height * 0.25f).first,
                        regionDiff(outBmp, srcBmp,
                            outBmp.width * 0.6f, outBmp.height * 0.7f, outBmp.width * 0.95f, outBmp.height * 0.95f,
                            srcBmp.width * 0.6f, srcBmp.height * 0.7f, srcBmp.width * 0.95f, srcBmp.height * 0.95f).first)
                    val bg = kotlin.math.max(bgDiff, 6f)

                    val pass = wmDiff >= bg * 1.15f + 8f || kotlin.math.abs(wmDelta) >= 6f || wmFrac > 0.03f
                    DebugLog.log("GPU", "水印验证 out=${outBmp.width}x${outBmp.height} src=${srcBmp.width}x${srcBmp.height} " +
                        "wmDiff=$wmDiff bgDiff=$bgDiff wmΔL=$wmDelta wmFrac=$wmFrac bbox=[$x0,$y0,$x1,$y1] " +
                        if (pass) "通过" else "失败")

                    if (!pass) throw WatermarkMissingException(
                        "水印变化度不足：wmDiff=$wmDiff bgDiff=$bgDiff wmΔL=$wmDelta wmFrac=$wmFrac")
                } else {

                    val stdIn = regionLumStd(outBmp, ix0, iy0, ix1, iy1)
                    val stdOut = kotlin.math.max(
                        regionLumStd(outBmp, 0f, 0f, outBmp.width * 0.5f, outBmp.height * 0.25f),
                        regionLumStd(outBmp, outBmp.width * 0.6f, outBmp.height * 0.7f,
                            outBmp.width * 0.95f, outBmp.height * 0.95f))
                    if (stdIn < 3f || stdOut < 3f) {

                        DebugLog.log("GPU", "水印验证失败（降级 std）：内部 std=$stdIn 外部=$stdOut bbox=[$x0,$y0,$x1,$y1]（区域异常，输出保留）")
                    } else {
                        DebugLog.log("GPU", "水印验证通过（降级 std）：内部 std=$stdIn 外部=$stdOut bbox=[$x0,$y0,$x1,$y1]")
                    }
                }
            } finally {
                runCatching { outBmp?.recycle() }; runCatching { srcBmp?.recycle() }
            }
        } finally { runCatching { mmr.release() } }
    }

    private fun regionDiff(bmpA: Bitmap, bmpB: Bitmap,
                           ax0f: Float, ay0f: Float, ax1f: Float, ay1f: Float,
                           bx0f: Float, by0f: Float, bx1f: Float, by1f: Float): Triple<Float, Float, Float> {
        val ax0 = ax0f.toInt().coerceIn(0, bmpA.width - 1); val ay0 = ay0f.toInt().coerceIn(0, bmpA.height - 1)
        val ax1 = ax1f.toInt().coerceIn(ax0 + 1, bmpA.width); val ay1 = ay1f.toInt().coerceIn(ay0 + 1, bmpA.height)
        val bx0 = bx0f.toInt().coerceIn(0, bmpB.width - 1); val by0 = by0f.toInt().coerceIn(0, bmpB.height - 1)
        val bx1 = bx1f.toInt().coerceIn(bx0 + 1, bmpB.width); val by1 = by1f.toInt().coerceIn(by0 + 1, bmpB.height)
        val sx = kotlin.math.max(1, ((ax1 - ax0) / 40).toInt())
        val sy = kotlin.math.max(1, ((ay1 - ay0) / 40).toInt())
        var sumAbs = 0.0; var sumDelta = 0.0; var n = 0; var nChanged = 0
        val rowA = IntArray(bmpA.width); val rowB = IntArray(bmpB.width)
        for (yy in 0 until ((ay1 - ay0 + sy - 1) / sy)) {
            val ay = ay0 + yy * sy
            val by = by0 + ((ay - ay0).toLong() * (by1 - by0) / (ay1 - ay0)).toInt().coerceIn(by0, by1 - 1)

            try {
                bmpA.getPixels(rowA, 0, bmpA.width, 0, ay, bmpA.width, 1)
                bmpB.getPixels(rowB, 0, bmpB.width, 0, by, bmpB.width, 1)
            } catch (_: Throwable) { continue }
            for (xx in 0 until ((ax1 - ax0 + sx - 1) / sx)) {
                val ax = ax0 + xx * sx
                val bx = bx0 + ((ax - ax0).toLong() * (bx1 - bx0) / (ax1 - ax0)).toInt().coerceIn(bx0, bx1 - 1)
                val ca = rowA[ax]; val cb = rowB[bx]
                val la = (((ca shr 16) and 0xFF) + ((ca shr 8) and 0xFF) + (ca and 0xFF)) / 3.0
                val lb = (((cb shr 16) and 0xFF) + ((cb shr 8) and 0xFF) + (cb and 0xFF)) / 3.0
                val d = la - lb
                sumAbs += kotlin.math.abs(d); sumDelta += d; n++
                if (kotlin.math.abs(d) > 20.0) nChanged++
            }
        }
        if (n < 4) return Triple(0f, 0f, 0f)
        return Triple((sumAbs / n).toFloat(), (sumDelta / n).toFloat(),
            nChanged.toFloat() / n)
    }

    private fun regionLumStd(bmp: Bitmap, x0f: Float, y0f: Float, x1f: Float, y1f: Float): Float {
        val x0 = x0f.toInt().coerceIn(0, bmp.width - 1)
        val y0 = y0f.toInt().coerceIn(0, bmp.height - 1)
        val x1 = x1f.toInt().coerceIn(x0 + 1, bmp.width)
        val y1 = y1f.toInt().coerceIn(y0 + 1, bmp.height)
        val sx = kotlin.math.max(1, ((x1 - x0) / 40).toInt())
        val sy = kotlin.math.max(1, ((y1 - y0) / 40).toInt())
        var sum = 0.0; var sumSq = 0.0; var n = 0
        val row = IntArray(bmp.width)
        for (yy in y0 until y1 step sy) {

            try {
                bmp.getPixels(row, 0, bmp.width, 0, yy, bmp.width, 1)
            } catch (_: Throwable) { continue }
            for (xx in x0 until x1 step sx) {
                val c = row[xx]
                val lum = (((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF)) / 3.0
                sum += lum; sumSq += lum * lum; n++
            }
        }
        if (n < 4) return 0f
        val mean = sum / n
        return kotlin.math.sqrt(kotlin.math.max(0.0, sumSq / n - mean * mean)).toFloat()
    }

    // ══════════════════════════════════════════════════════════════

    // ══════════════════════════════════════════════════════════════
    private class InputSurface(private val surface: Surface) {
        private var eglDisplay: EGLDisplay? = null
        private var eglContext: EGLContext? = null
        private var eglSurface: EGLSurface? = null

        var gpuInfo: String = ""
            private set

        init {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
                throw RuntimeException("eglInitialize 失败")
            }
            // Try RGBA8888 configurations only. RGB565 fallbacks visibly quantize every exported
            // pixel; if 8-bit RGBA surface output is unavailable, the caller uses the CPU path.
            val configCandidates = arrayOf(
                // RGBA8888 + recordable.
                intArrayOf(
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
                    EGL_RECORDABLE_ANDROID, 1,
                    EGL14.EGL_NONE
                ),
                // RGBA8888 without the optional recordable attribute for permissive drivers.
                intArrayOf(
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_NONE
                )
            )
            var chosenConfig: EGLConfig? = null
            var chosenIdx = -1
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            for ((idx, attribs) in configCandidates.withIndex()) {
                if (EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, numConfigs, 0)
                    && numConfigs[0] > 0 && configs[0] != null) {
                    chosenConfig = configs[0]; chosenIdx = idx; break
                }
            }
            if (chosenConfig == null) {
                throw RuntimeException("所有 EGL 配置均不支持（该 GPU 无可用配置）")
            }
            val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            eglContext = EGL14.eglCreateContext(eglDisplay, chosenConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
            if (eglContext == null || eglContext == EGL14.EGL_NO_CONTEXT) {
                throw RuntimeException("eglCreateContext 失败（EGL 错误=0x${Integer.toHexString(EGL14.eglGetError())}）")
            }
            val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, chosenConfig, surface, surfaceAttribs, 0)
            if (eglSurface == null || eglSurface == EGL14.EGL_NO_SURFACE) {
                throw RuntimeException("eglCreateWindowSurface 失败（EGL 错误=0x${Integer.toHexString(EGL14.eglGetError())}）")
            }
            makeCurrent()

            gpuInfo = try {
                val renderer = GLES20.glGetString(GLES20.GL_RENDERER) ?: "?"
                val glVer = GLES20.glGetString(GLES20.GL_VERSION) ?: "?"
                val exts = GLES20.glGetString(GLES20.GL_EXTENSIONS) ?: ""
                "EGLcfg=$chosenIdx renderer=$renderer gl=$glVer exts=${exts.take(120)}"
            } catch (_: Throwable) { "EGLcfg=$chosenIdx (GL info unavailable)" }
            DebugLog.log("GPU", "EGL 初始化: $gpuInfo")
        }

        fun makeCurrent() {
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
        }

        fun swapBuffers(): Boolean = EGL14.eglSwapBuffers(eglDisplay, eglSurface)

        fun setPresentationTime(nsecs: Long) {

            try {
                val ok = EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, nsecs)
                if (!ok) DebugLog.log("GPU", "eglPresentationTimeANDROID=false nsecs=$nsecs（PTS 可能未生效→绿幕/快进）")
            } catch (t: Throwable) {
                DebugLog.log("GPU", "eglPresentationTimeANDROID 异常 ${t.javaClass.simpleName}: ${t.message}（PTS 未生效→绿幕/快进）")
            }
        }

        fun release() {
            runCatching { EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT) }
            runCatching { EGL14.eglDestroySurface(eglDisplay, eglSurface) }
            runCatching { EGL14.eglDestroyContext(eglDisplay, eglContext) }
            runCatching { EGL14.eglTerminate(eglDisplay) }
            runCatching { surface.release() }
        }
    }

    // ══════════════════════════════════════════════════════════════

    // ══════════════════════════════════════════════════════════════
    private class OutputSurface(width: Int, height: Int) {
        private var surfaceTexture: SurfaceTexture? = null
        private val callbackThread = android.os.HandlerThread("lgw-oes-frames").apply { start() }
        var surface: Surface? = null
            private set
        private val textureIdArr = IntArray(1)
        val textureId: Int get() = textureIdArr[0]
        val texMatrix = FloatArray(16)
        private val frameSync = Object()
        @Volatile private var frameAvailable = false

        private var lastTimestamp = -1L
        private var consecutiveTimeouts = 0
        private val maxConsecutiveTimeouts = 10
        var lastAwaitOk = false
            private set

        init {
            GLES20.glGenTextures(1, textureIdArr, 0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureIdArr[0])
            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR.toFloat())
            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE.toFloat())
            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE.toFloat())
            surfaceTexture = SurfaceTexture(textureIdArr[0])
            surfaceTexture!!.setDefaultBufferSize(width, height)
            surfaceTexture!!.setOnFrameAvailableListener({
                synchronized(frameSync) { frameAvailable = true; frameSync.notifyAll() }
            }, android.os.Handler(callbackThread.looper))
            surface = Surface(surfaceTexture)
        }

        fun awaitNewImage() {
            val got = synchronized(frameSync) {
                if (!frameAvailable) {
                    try { frameSync.wait(2000) } catch (_: InterruptedException) { }
                }
                val ok = frameAvailable
                frameAvailable = false
                ok
            }
            if (!got) {
                consecutiveTimeouts++
                lastAwaitOk = false
                DebugLog.log("GPU", "SurfaceTexture 帧超时 ($consecutiveTimeouts/$maxConsecutiveTimeouts)")
                if (consecutiveTimeouts >= maxConsecutiveTimeouts) {
                    throw RuntimeException("SurfaceTexture 连续 $maxConsecutiveTimeouts 次无帧，放弃 GPU 路径")
                }
                return
            }
            val st = surfaceTexture ?: run { lastAwaitOk = false; return }
            st.updateTexImage()
            st.getTransformMatrix(texMatrix)
            val ts = st.timestamp
            if (ts == lastTimestamp) {

                lastAwaitOk = false
                return
            }
            lastTimestamp = ts
            consecutiveTimeouts = 0
            lastAwaitOk = true
        }

        fun release() {
            runCatching { surface?.release() }
            runCatching { surfaceTexture?.setOnFrameAvailableListener(null) }
            runCatching { surfaceTexture?.release() }
            runCatching { callbackThread.quitSafely() }
            runCatching { GLES20.glDeleteTextures(1, textureIdArr, 0) }
        }
    }

    // ══════════════════════════════════════════════════════════════

    // ══════════════════════════════════════════════════════════════
    private class TextureRender(enableLegacyShader: Boolean) {
        private var program = 0
        private var positionLoc = 0
        private var texCoordLoc = 0
        private var videoTexLoc = 0
        private var maskTexLoc = 0
        private var fgTexLoc = 0
        private var distTexLoc = 0
        private var resolutionLoc = 0
        private var blurLoc = 0
        private var warpLoc = 0
        private var contrastLoc = 0
        private var tintLoc = 0
        private var tintStrLoc = 0
        private var chromaticLoc = 0
        private var opacityLoc = 0
        private var shadowLoc = 0
        private var barHLoc = 0
        private var edgeWarpLoc = 0
        private var innerWarpLoc = 0
        private var timeLoc = 0
        private var hlAngleLoc = 0
        private var falloffLoc = 0
        private var specularLoc = 0
        private var innerShadowLoc = 0
        private var snellPxLoc = 0
        private var snellKLoc = 0
        private var uniformLogged = false

        private var copyProgram = 0
        private var copyPositionLoc = 0
        private var copyTexCoordLoc = 0
        private var copyVideoLoc = 0
        private var copyMatrixLoc = 0
        private val sourceMatrix = FloatArray(16).apply { android.opengl.Matrix.setIdentityM(this, 0) }
        fun setTextureTransform(matrix: FloatArray) { matrix.copyInto(sourceMatrix) }

        private var fbo = 0
        private var video2dTex = 0
        private var video2dW = 0
        private var video2dH = 0
        private val vertexBuffer: FloatBuffer
        private val texCoordBuffer: FloatBuffer

        private val copyTexCoordBuffer: FloatBuffer

        private var blitTex = 0
        private var blitW = 0
        private var blitH = 0
        private var blitProgram = 0
        private var blitPosLoc = 0
        private var blitTexCoordLoc = 0
        private var blitTexLoc = 0

        private val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord.xy;
            }
        """

        private val COPY_FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sVideo;
            uniform mat4 uTexMatrix;
            void main() {
                vec2 sourceCoord = (uTexMatrix * vec4(vTexCoord.x, 1.0 - vTexCoord.y, 0.0, 1.0)).xy;
                gl_FragColor = texture2D(sVideo, sourceCoord);
            }
        """

        private val BLIT_FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D sTex;
            void main() {
                gl_FragColor = texture2D(sTex, vTexCoord);
            }
        """

        private val FRAGMENT_SHADER = """
            precision highp float;
            float clamp01(float x) { return clamp(x, 0.0, 1.0); }
            varying vec2 vTexCoord;
            uniform sampler2D sVideo;   // 视频帧已由 Pass1 拷入 2D 纹理（OES 偏移采样在 Adreno 不可靠）
            uniform sampler2D sMask;
            uniform sampler2D sFg;
            uniform sampler2D sDist;   // v14.67/68 到边缘距离场（高度场/斯涅尔/菲涅尔数据源）
            uniform vec2 uResolution;
            uniform float uBlur;       // 模糊 0-100
            uniform float uWarp;       // 统一扭曲 0-1（边缘波浪+内部涟漪，对齐预览引擎单滑块）
            uniform float uBarH;       // v14.13 水印条高度（像素，bbox 高）：扭曲位移按条尺寸缩放
            uniform float uEdgeWarp;   // v14.22 派生 edgeWarp/100（引擎 edgeIrregAmp 系数用）
            uniform float uInnerWarp;  // v14.22 派生 innerWarp/100（引擎 innerAmp 系数用）
            uniform float uTime;        // v14.41 流体相位（弧度，流体开关开启时随实时时钟推进）
            uniform float uHlAngle;    // v14.42 高光角度（弧度）
            uniform float uFalloff;    // v14.42 高光衰减幂
            uniform float uSpecular;   // v14.42 高光强度
            uniform float uInnerShadow;// v14.42 内阴影强度
            uniform float uSnellPx;   // v14.64 高度场/斯涅尔 折射位移尺度(px)
            uniform float uSnellK;    // v14.64 法线强度系数
            uniform float uContrast;   // 玻璃对比度 0.5-2.0
            uniform vec4 uTint;        // 染色颜色
            uniform float uTintStr;    // 染色强度 0-1
            uniform float uChromatic;  // 色散 0-100
            uniform float uOpacity;    // 玻璃整体不透明度 0-100
            uniform float uShadow;     // 外投影强度 0-100（v14.09：蒙版阴影区压暗视频，对齐预览黑软影）

            vec4 blur17(vec2 uv, vec2 texel, float radius) {
                vec2 o = texel * radius;
                vec4 sum = vec4(0.0);
                sum += texture2D(sVideo, uv + vec2(-3.0, -3.0) * o) * 0.036;
                sum += texture2D(sVideo, uv + vec2( 0.0, -3.0) * o) * 0.036;
                sum += texture2D(sVideo, uv + vec2( 3.0, -3.0) * o) * 0.036;
                sum += texture2D(sVideo, uv + vec2(-3.0,  0.0) * o) * 0.036;
                sum += texture2D(sVideo, uv + vec2( 3.0,  0.0) * o) * 0.036;
                sum += texture2D(sVideo, uv + vec2(-3.0,  3.0) * o) * 0.036;
                sum += texture2D(sVideo, uv + vec2( 0.0,  3.0) * o) * 0.036;
                sum += texture2D(sVideo, uv + vec2( 3.0,  3.0) * o) * 0.036;
                sum += texture2D(sVideo, uv + vec2(-2.0, -2.0) * o) * 0.06;
                sum += texture2D(sVideo, uv + vec2( 0.0, -2.0) * o) * 0.06;
                sum += texture2D(sVideo, uv + vec2( 2.0, -2.0) * o) * 0.06;
                sum += texture2D(sVideo, uv + vec2(-2.0,  0.0) * o) * 0.06;
                sum += texture2D(sVideo, uv + vec2( 2.0,  0.0) * o) * 0.06;
                sum += texture2D(sVideo, uv + vec2(-2.0,  2.0) * o) * 0.06;
                sum += texture2D(sVideo, uv + vec2( 0.0,  2.0) * o) * 0.06;
                sum += texture2D(sVideo, uv + vec2( 2.0,  2.0) * o) * 0.06;
                sum += texture2D(sVideo, uv + vec2(-1.0, -1.0) * o) * 0.044;
                sum += texture2D(sVideo, uv + vec2( 1.0, -1.0) * o) * 0.044;
                sum += texture2D(sVideo, uv + vec2(-1.0,  1.0) * o) * 0.044;
                sum += texture2D(sVideo, uv + vec2( 1.0,  1.0) * o) * 0.044;
                sum += texture2D(sVideo, uv) * 0.056;
                return sum;
            }

            void main() {
                vec2 texel = 1.0 / uResolution;
                vec4 mask = texture2D(sMask, vTexCoord);
                vec4 fg = texture2D(sFg, vTexCoord);

                float maskA = max(mask.a, max(mask.r, max(mask.g, mask.b)));
                float body = smoothstep(0.5, 1.0, maskA);

                float warpN = clamp(uWarp, 0.0, 1.0);
                float tPh = uTime;   // 流体相位（弧度；流体关闭=0）
                vec2 vw = vTexCoord;
                if (warpN > 0.002) {
                    float barH = clamp(uBarH, 4.0, 256.0);
                    float band = smoothstep(0.15, 0.45, maskA) *
                                 (1.0 - smoothstep(0.50, 0.88, maskA));
                    float wob = sin(vTexCoord.x * 320.0 + 2.1 + tPh) *
                                sin(vTexCoord.y * 220.0 + 1.3 + tPh * 0.7);
                    float edgeAmpPx = barH * 0.11 * uEdgeWarp;   // 引擎 edgeIrregAmp 同式
                    vw += vec2(wob * edgeAmpPx, wob * edgeAmpPx * 1.6) * texel * band;
                    float innerM = body * smoothstep(0.02, 0.20, maskA);
                    vec2 rip = vec2(
                        sin(vTexCoord.y * 96.0 + 1.7 + tPh * 1.5) * 0.5 + sin(vTexCoord.y * 47.0 + 4.2 + tPh) * 0.5,
                        sin(vTexCoord.x * 88.0 + 2.4 + tPh * 1.2) * 0.5 + sin(vTexCoord.x * 41.0 + 0.9 + tPh * 0.8) * 0.5);
                    vw += rip * (barH * 0.22 * uInnerWarp) * texel * innerM;
                }
                vec4 video = texture2D(sVideo, vw);

                float blurN = uBlur / 100.0;
                float radius = 1.0 + blurN * 52.0;          // 1~53 texel（17 抽头半径放宽补偿平滑度）
                float strength = blurN * 1.0;               // 0~1 满强度
                vec4 blur = blur17(vw, texel, radius);
                vec4 glassBg = mix(video, blur, strength);

                float insdB = clamp(texture2D(sDist, vTexCoord).r, 0.0, 1.0);
                if (body > 0.05 && insdB > 0.012) {
                    vec2 tstep = texel * 3.0;
                    float gx = texture2D(sDist, vTexCoord + vec2(tstep.x, 0.0)).r -
                               texture2D(sDist, vTexCoord - vec2(tstep.x, 0.0)).r;
                    float gy = texture2D(sDist, vTexCoord + vec2(0.0, tstep.y)).r -
                               texture2D(sDist, vTexCoord - vec2(0.0, tstep.y)).r;
                    float h = sqrt(max(0.0, 2.0 * insdB - insdB * insdB));
                    vec2 slopeN = vec2(gx, gy);
                    float slopeLen = length(slopeN) + 0.001;
                    vec2 normal = slopeN / slopeLen;
                    float dh = (1.0 - insdB) / max(0.03, h);
                    float edgePush = clamp(dh * 1.2, 0.0, 4.0);        // 边缘压缩（棱边最强）
                    float interiorPull = (0.18 + 0.50 * h) * insdB;    // 内部向中心放大（线可见弯曲）
                    float dispPx = (edgePush + interiorPull) * uSnellPx;
                    vec2 snellOff = normal * dispPx * texel;
                    vec4 refracted = texture2D(sVideo, vTexCoord + snellOff);
                    float refrLum = dot(refracted.rgb, vec3(0.299, 0.587, 0.114));
                    float baseLum = dot(glassBg.rgb, vec3(0.299, 0.587, 0.114));
                    glassBg.rgb += (refrLum - baseLum) * vec3(0.85) * body;
                    float rimFres = exp(-insdB * 14.0) * body;
                    vec2 lD = vec2(cos(uHlAngle), sin(uHlAngle));
                    float lit = max(dot(normal, lD), 0.0);
                    glassBg.rgb += vec3(0.42) * rimFres * (0.45 + 0.55 * lit);
                }

                float cf2 = uChromatic / 100.0;
                if (cf2 > 0.01) {
                    float ly2 = clamp(mask.g, 0.03, 1.0);
                    float lx2 = clamp((vTexCoord.x - 0.5) * 2.0 + 0.5, 0.1, 0.9);
                    float dispInt = cf2 * (lx2 * ly2) * body;
                    float maxDispPx = max(1.5, uBarH * 0.018);
                    float dpx = dispInt * maxDispPx;
                    vec4 srcR = texture2D(sVideo, vw + vec2(dpx * texel.x, 0.0));
                    vec4 srcB = texture2D(sVideo, vw - vec2(dpx * texel.x, 0.0));
                    glassBg.r = mix(glassBg.r, srcR.r * 1.12, dispInt);
                    glassBg.b = mix(glassBg.b, srcB.b * 1.12, dispInt);
                }

                glassBg.rgb = (glassBg.rgb - 0.5) * uContrast + 0.5;

                glassBg.rgb = mix(glassBg.rgb, uTint.rgb, uTintStr);

                float gy2 = clamp(mask.g, 0.0, 1.0);
                float lyc = gy2 * 2.0 - 1.0;                                   // 局部Y -1..1（引擎 v）
                float lux = clamp((vTexCoord.x - 0.5) * 2.0, -1.0, 1.0);        // 局部X 近似（引擎 u）
                float hlC = cos(uHlAngle); float hlS = sin(uHlAngle);
                float dirLight = clamp(-lux * hlC - lyc * hlS, -1.0, 1.0) * 0.5 + 0.5;
                float vert2 = 0.55 + 0.45 * dirLight;
                float fo2 = clamp(uFalloff, 0.5, 3.0);
                float liftV = clamp(pow(vert2, fo2) * uSpecular * 0.12, 0.0, 0.24);
                glassBg.rgb += (1.0 - glassBg.rgb) * liftV * body;
                glassBg.rgb = min(glassBg.rgb, vec3(0.94));
                float insd = clamp((maskA - 0.55) * 6.0, 0.0, 1.0);             // 0=边界 1=内侧
                float ringW2 = uBarH * 0.022;
                float tRing = clamp(insd * (1.0 / clamp(ringW2 * 6.0, 0.01, 60.0)), 0.0, 1.0);
                float ringIn2 = clamp01(1.0 - tRing) * clamp01(tRing * 6.0);
                float ringOut2 = clamp01((0.55 - maskA) * 4.0);
                float lensBorder = clamp(ringIn2 + ringOut2 * 0.3, 0.0, 1.0);
                float rimDir = (lux * hlC + lyc * hlS);
                if (rimDir > 0.0) {
                    lensBorder *= pow(clamp(rimDir, 0.0, 1.0), fo2);
                } else {
                    lensBorder = 0.0;
                }
                float ring2 = lensBorder * uSpecular * 0.22;   // v14.51：亮环 0.30→0.22（不刺眼）
                glassBg.rgb += vec3(ring2);

                float edgeI = 1.0 - smoothstep(0.0, 0.10, insd);
                glassBg.rgb *= (1.0 - uInnerShadow * edgeI * 0.42 * body);

                float shapeW = body;
                vec4 base = mix(video, glassBg, shapeW * (uOpacity / 100.0));

                float shadowI = clamp(maskA, 0.0, 1.0) * (1.0 - shapeW);
                base.rgb *= (1.0 - shadowI * (0.55 + 0.45 * (uShadow / 100.0)));

                float fgA = clamp(fg.a, 0.0, 1.0);
                fgA = max(fgA, max(fg.r, max(fg.g, fg.b)));
                gl_FragColor = mix(base, fg, clamp(fgA, 0.0, 1.0));
            }
        """

        private val vertices = floatArrayOf(
            -1f, -1f, 0f, 1f,
             1f, -1f, 1f, 1f,
            -1f,  1f, 0f, 0f,
             1f,  1f, 1f, 0f
        )

        init {
            vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer()
            vertexBuffer.put(vertices).position(0)

            val texCoords = floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)
            texCoordBuffer = ByteBuffer.allocateDirect(texCoords.size * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer()
            texCoordBuffer.put(texCoords).position(0)

            val copyTexCoords = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
            copyTexCoordBuffer = ByteBuffer.allocateDirect(copyTexCoords.size * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer()
            copyTexCoordBuffer.put(copyTexCoords).position(0)

            if (enableLegacyShader) {
            program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
            positionLoc = GLES20.glGetAttribLocation(program, "aPosition")
            texCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
            videoTexLoc = GLES20.glGetUniformLocation(program, "sVideo")
            maskTexLoc = GLES20.glGetUniformLocation(program, "sMask")
            fgTexLoc = GLES20.glGetUniformLocation(program, "sFg")
            distTexLoc = GLES20.glGetUniformLocation(program, "sDist")
            resolutionLoc = GLES20.glGetUniformLocation(program, "uResolution")
            blurLoc = GLES20.glGetUniformLocation(program, "uBlur")
            warpLoc = GLES20.glGetUniformLocation(program, "uWarp")
            contrastLoc = GLES20.glGetUniformLocation(program, "uContrast")
            tintLoc = GLES20.glGetUniformLocation(program, "uTint")
            tintStrLoc = GLES20.glGetUniformLocation(program, "uTintStr")
            chromaticLoc = GLES20.glGetUniformLocation(program, "uChromatic")
            opacityLoc = GLES20.glGetUniformLocation(program, "uOpacity")
            shadowLoc = GLES20.glGetUniformLocation(program, "uShadow")
            barHLoc = GLES20.glGetUniformLocation(program, "uBarH")
            edgeWarpLoc = GLES20.glGetUniformLocation(program, "uEdgeWarp")
            innerWarpLoc = GLES20.glGetUniformLocation(program, "uInnerWarp")
            timeLoc = GLES20.glGetUniformLocation(program, "uTime")
            hlAngleLoc = GLES20.glGetUniformLocation(program, "uHlAngle")
            falloffLoc = GLES20.glGetUniformLocation(program, "uFalloff")
            specularLoc = GLES20.glGetUniformLocation(program, "uSpecular")
            innerShadowLoc = GLES20.glGetUniformLocation(program, "uInnerShadow")
            snellPxLoc = GLES20.glGetUniformLocation(program, "uSnellPx")
            snellKLoc = GLES20.glGetUniformLocation(program, "uSnellK")

            }

            copyProgram = createProgram(VERTEX_SHADER, COPY_FRAGMENT_SHADER)
            copyPositionLoc = GLES20.glGetAttribLocation(copyProgram, "aPosition")
            copyTexCoordLoc = GLES20.glGetAttribLocation(copyProgram, "aTexCoord")
            copyVideoLoc = GLES20.glGetUniformLocation(copyProgram, "sVideo")
            copyMatrixLoc = GLES20.glGetUniformLocation(copyProgram, "uTexMatrix")

            blitProgram = createProgram(VERTEX_SHADER, BLIT_FRAGMENT_SHADER)
            blitPosLoc = GLES20.glGetAttribLocation(blitProgram, "aPosition")
            blitTexCoordLoc = GLES20.glGetAttribLocation(blitProgram, "aTexCoord")
            blitTexLoc = GLES20.glGetUniformLocation(blitProgram, "sTex")
        }

        private fun ensureVideo2d(w: Int, h: Int) {
            if (fbo == 0) {
                val fbos = IntArray(1)
                GLES20.glGenFramebuffers(1, fbos, 0)
                fbo = fbos[0]
                val texs = IntArray(1)
                GLES20.glGenTextures(1, texs, 0)
                video2dTex = texs[0]
            }
            if (video2dTex != 0 && (video2dW != w || video2dH != h)) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, video2dTex)
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
                video2dW = w; video2dH = h
            }
        }

        private var capBuf: ByteBuffer? = null
        private var capBmp: Bitmap? = null
        fun captureVideo2d(videoTex: Int, w: Int, h: Int): Bitmap? {
            ensureVideo2d(w, h)
            if (fbo == 0 || video2dTex == 0) return null
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, video2dTex, 0)
            if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                return null
            }
            GLES20.glViewport(0, 0, w, h)
            GLES20.glUseProgram(copyProgram)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTex)
            GLES20.glUniform1i(copyVideoLoc, 0)
            GLES20.glUniformMatrix4fv(copyMatrixLoc, 1, false, sourceMatrix, 0)
            GLES20.glDisable(GLES20.GL_BLEND)
            drawQuad(copyPositionLoc, copyTexCoordLoc, copyTexCoordBuffer)

            if (capBuf == null || capBuf!!.capacity() < w * h * 4) {
                capBuf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
            }
            val buf = capBuf!!
            buf.clear()
            GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            if (GLES20.glGetError() != GLES20.GL_NO_ERROR) return null
            if (capBmp == null || capBmp!!.width != w || capBmp!!.height != h) {
                capBmp?.recycle()
                capBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            }
            buf.position(0)
            // Native RGBA copy avoids four interpreted reads/shifts per pixel.
            capBmp!!.copyPixelsFromBuffer(buf)
            return capBmp
        }

        fun uploadBlit(bmp: Bitmap, w: Int, h: Int) {
            ensureBlit(w, h)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, blitTex)
            // Reuse allocated texture storage and upload native Bitmap pixels.
            GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bmp)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        }

        fun drawFrameBlit(w: Int, h: Int) {
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glViewport(0, 0, w, h)
            GLES20.glUseProgram(blitProgram)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, blitTex)
            GLES20.glUniform1i(blitTexLoc, 0)
            drawQuad(blitPosLoc, blitTexCoordLoc, texCoordBuffer)
        }

        private fun ensureBlit(w: Int, h: Int) {
            if (blitTex == 0) {
                val ts = IntArray(1)
                GLES20.glGenTextures(1, ts, 0)
                blitTex = ts[0]
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, blitTex)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            }
            if (blitW != w || blitH != h) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, blitTex)
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
                blitW = w; blitH = h
            }
        }

        private fun drawQuad(posLoc: Int, texLoc: Int, texBuf: FloatBuffer) {
            vertexBuffer.position(0)
            GLES20.glEnableVertexAttribArray(posLoc)
            GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)
            texBuf.position(0)
            GLES20.glEnableVertexAttribArray(texLoc)
            GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT, false, 8, texBuf)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(posLoc)
            GLES20.glDisableVertexAttribArray(texLoc)
        }

        fun setRotation(rot: Int) {
            val r = ((rot % 360) + 360) % 360
            val tc = when (r) {
                90 -> floatArrayOf(0f, 1f, 0f, 0f, 1f, 1f, 1f, 0f)
                180 -> floatArrayOf(1f, 1f, 0f, 1f, 1f, 0f, 0f, 0f)
                270 -> floatArrayOf(1f, 0f, 1f, 1f, 0f, 0f, 0f, 1f)
                else -> floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
            }
            copyTexCoordBuffer.clear()
            copyTexCoordBuffer.put(tc).position(0)
            DebugLog.log("GPU", "旋转像素 rot=$r 拷贝texcoord=[${tc.joinToString(",")}]")
        }

        fun release() {
            capBmp?.let { if (!it.isRecycled) it.recycle() }; capBmp = null
            capBuf = null
            if (program != 0) GLES20.glDeleteProgram(program)
            if (copyProgram != 0) GLES20.glDeleteProgram(copyProgram)
            if (blitProgram != 0) GLES20.glDeleteProgram(blitProgram)
            if (fbo != 0) {
                val fbos = IntArray(1); fbos[0] = fbo
                GLES20.glDeleteFramebuffers(1, fbos, 0)
            }
            if (video2dTex != 0) {
                val texs = IntArray(1); texs[0] = video2dTex
                GLES20.glDeleteTextures(1, texs, 0)
            }
            if (blitTex != 0) {
                val texs = IntArray(1); texs[0] = blitTex
                GLES20.glDeleteTextures(1, texs, 0)
            }
        }

        private fun createProgram(vertexSource: String, fragmentSource: String): Int {
            val vs = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
            val fs = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
            val prog = GLES20.glCreateProgram()
            GLES20.glAttachShader(prog, vs)
            GLES20.glAttachShader(prog, fs)
            GLES20.glLinkProgram(prog)
            GLES20.glDeleteShader(vs)
            GLES20.glDeleteShader(fs)
            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] != GLES20.GL_TRUE) {
                val info = GLES20.glGetProgramInfoLog(prog)
                DebugLog.log("GPU", "GL program link 失败: $info")
                GLES20.glDeleteProgram(prog)

                throw RuntimeException("GL program 编译失败: $info")
            }
            return prog
        }

        private fun loadShader(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val compiled = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
            if (compiled[0] == 0) {
                val info = GLES20.glGetShaderInfoLog(shader)
                DebugLog.log("GPU", "GL shader 编译失败: $info")
                GLES20.glDeleteShader(shader)

                throw RuntimeException("GL shader 编译失败: $info")
            }
            return shader
        }
    }
}
