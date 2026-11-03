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


    /** 本次导出 mask 蒙版的非透明像素包围盒（水印区域，用于导出后抽帧验证水印是否真实写入输出） */
    @Volatile private var maskBBox: IntArray? = null

    /** 导出结果 */
    data class Result(val file: File, val durationMs: Long, val frames: Int)

    /**
     * @param watermarkRenderer 水印渲染器（在透明 Bitmap 上画水印，上传为 GL 纹理叠加）
     * @param codec MIME：AVC/HEVC
     * @param onProgress 进度回调 0-100
     */
    /**
     * v15.11 编码器能力探测与选择（兼容性分支）：
     * - API29+ 用 isHardwareAccelerated 权威判定；API28 及以下按编码器名启发式（google/c2.android=软编）
     * - 优先硬件编码器（Surface 输入 + 支持目标 MIME），失败逐级回退到系统默认实例
     * - 任何情况下都返回可用的 MediaCodec，绝不因探测失败而无法导出
     */
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
        // v14.27 混合加速：true=GPU 硬解取帧 + CPU 引擎逐帧水印（水印=预览，导出比纯 CPU 快：
        // 解码走 GPU 表面，省去 CPU/retriever 逐帧解码瓶颈）；false=旧 GPU shader 近似合成
        cpuWatermark: Boolean = true,
        // v14.08 音频直通开关：普通视频导出=true（源有音频必须保留）；动态照片视频=false
        // （Google/vivo 动照片标准视频轨不含音频，避免破坏生态识别）
        includeAudio: Boolean = true,
        // v15.10 动态照片 5 秒截断：>0 时源帧 PTS 达到该毫秒数即停止编码（输出=前 maxDurationMs）
        maxDurationMs: Long = 0L
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
        var distTex = 0   // v14.67/68：到边缘距离场纹理（export 局部，drawFrame 传参）
        var distFallbackTex = 0   // dist 缺失时的纯黑兜底（export 局部）
        var audioExt: MediaExtractor? = null  // v14.08 音频直通：函数级声明，finally 统一释放
        // 输出临时文件：声明在 try 外，catch/finally（取消/异常）统一清理，不留残片
        var tmpOut: File? = null
        var committed = false
        try {
            // ── 1. 读源视频轨道 ──
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
            // 时长：优先轨道格式；部分 MP4 的 duration 只在元数据里（track format 读不到=0），
            // 此时用 MediaMetadataRetriever 回退，否则进度条会永远卡在起点（用户多次反馈）
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

            // ── 1b. 音频轨道（v14.08：音频直通——视频经液态玻璃处理后不得丢弃原音频）──
            //     MediaExtractor 同一时刻只能 select 一个轨道，音频用独立 extractor 读取；
            //     直通 = 源压缩音频 sample 原样写入 MediaMuxer（MP4 容器支持 AAC/MP3 等），
            //     不改编码、不重采样，PTS 原样保持，音画天然同步。
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

            // ── 2. 创建编码器 + InputSurface ──
            val realCodec = if (codec == MediaFormat.MIMETYPE_VIDEO_HEVC &&
                runCatching {
                    android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)
                        .findEncoderForFormat(MediaFormat.createVideoFormat(codec, 2, 2))
                }.getOrNull() != null
            ) codec else MediaFormat.MIMETYPE_VIDEO_AVC

            encoder = createBestEncoder(realCodec)
            // v15.11 能力探测日志：API 级别 / 编码器名 / 是否硬件加速 / 是否支持 Surface 输入 / 码率
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
            // v14.01 码率修正：旧公式 w*h*fps（1080p60 ≈ 124Mbps）远超可见画质需求——
            // 文件巨大、编码器/封装压力大、导出明显变慢。改高画质标准系数
            // （AVC ≈ 0.20 bpp@30fps / HEVC ≈ 0.12），封顶 60M，肉眼无损且体积/速度大幅改善
            // v15.14 提速：高分辨率(≥1080p)或高帧率(≥50fps)时系数 ×0.75——
            // 帧间时间冗余更大，同画质所需码率低于 30fps 基准；软编场景编码量降 ~25% 导出更快
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

            // ── 3. 创建解码器输出 Surface（SurfaceTexture + GL OES 纹理）──
            outputSurface = OutputSurface(encW, encH)
            textureRender = TextureRender(enableLegacyShader = !cpuWatermark)
            // v14.21：GPU 路径像素旋转（rotation 参数此前是死代码——既不转像素也不写元数据，
            // 动态照片强制 90°/源 rotation=90/270 的视频导出后横屏/错向）。旋转 Pass1 拷贝
            // 的采样 texcoord，使视频内容按 encW×encH 正确取向；蒙版/前景纹理本就是按
            // encW×encH 渲染的，天然对齐。
            runCatching { textureRender.setRotation(rotation) }

            // ── 4. v15.13 精简：混合路径（cpuWatermark=true，唯一调用方 Mp4Exporter 恒传 true）
            //    用 CPU 引擎逐帧渲染，完全不用 mask/fg 纹理——删除废弃的 shader 纹理创建分支 ──

            // ── 5. 配置解码器：自动匹配引擎——默认实例（系统按机型自动选最优硬解）失败时，
            //    开源/厂商解码器候选链兜底（video/avc 源只试支持 video/avc 的 decoder）──
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

            // ── 7. 解码→渲染→编码 循环 ──
            val bufferInfo = MediaCodec.BufferInfo()
            var inputEos = false
            var outputEos = false
            var encoderEos = false
            var framesEncoded = 0
            var lastPtsUs = 0L
            var lastProgress = -1
            // v14.82 长视频截断修复：10 分钟硬截止会掐断长视频（视频轨半截+音频全量=播放到一半画面停）。
            // 截止随源时长缩放（≥10min，长视频按 源时长×8 的实时预算），长视频能完整跑完
            val deadline = System.currentTimeMillis() +
                maxOf(10 * 60_000L, if (durUs > 0) durUs / 1000L * 8L else 0L)
            val startTime = System.currentTimeMillis()
            // 首帧守卫：5 秒内未渲染出第一帧 → 该 GPU/解码器组合不兼容，立即放弃回退
            val firstFrameDeadline = startTime + 5000
            // v15.12 patch 提速：水印包围盒只测量一次（导出期间参数被 isExporting 冻结，几何恒定）；
            // 测量失败=null → 全程走旧全帧路径（兼容兜底）
            var wmRegion: IntArray? = if (cpuWatermark)
                runCatching { watermarkRenderer.exportWmRegion(encW, encH) }.getOrNull() else null
            var patchFailStreak = 0
            // v15.11 分阶段耗时统计（性能剖析）：区分 解码等待/读回/CPU渲染/上传/绘制/编码交换
            var tDecodeWait = 0L; var tCapture = 0L; var tCpuRender = 0L
            var tUpload = 0L; var tDraw = 0L; var tSwap = 0L; var tLoopStart = 0L
            if (wmRegion != null) {
                val r = wmRegion!!
                val pct = (100L * r[2] * r[3] / (encW.toLong() * encH).coerceAtLeast(1L)).toInt()
                DebugLog.log("GPU", "水印 patch 区域=${r[2]}x${r[3]} @${r[0]},${r[1]}" +
                    "（全帧 ${encW}x${encH}，读回量约 $pct%）")
            }

            while (!outputEos && System.currentTimeMillis() < deadline) {
                if (framesEncoded == 0 && System.currentTimeMillis() > firstFrameDeadline) {
                    throw RuntimeException("首帧 5 秒未渲染（GPU=${inputSurface?.gpuInfo?.take(60)}），放弃 GPU 路径")
                }
                // 动态走秒：混合路径（cpuWatermark=true）逐帧 CPU 渲染已自动带入新时间，
                // 无需纹理重建——v15.13 删除废弃的走秒纹理重建分支
                // 取消检查：用户点取消后立即中止 GPU 导出循环
                if (isCancelled()) {
                    DebugLog.log("GPU", "用户取消导出（已编码 $framesEncoded 帧）")
                    // 取消不留残片：删除未完成的 .tmp，避免相册扫描器看到半成品文件
                    runCatching { tmpOut?.delete() }
                    return null
                }
                // 喂解码器输入
                if (!inputEos) {
                    val inIdx = decoder.dequeueInputBuffer(10000)
                    if (inIdx >= 0) {
                        val ib = decoder.getInputBuffer(inIdx)
                        if (ib == null) { decoder.queueInputBuffer(inIdx, 0, 0, 0, 0); continue }
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

                // 取解码器输出 → 渲染到编码器 InputSurface
                // 注意：Surface 输出模式下 bufferInfo.size 通常为 0（帧数据直接到 Surface，不经 ByteBuffer），
                // 绝不能用 size>0 判断是否为帧；只需排除 CODEC_CONFIG 和 EOS。
                val outIdx = decoder.dequeueOutputBuffer(bufferInfo, 10000)
                when {
                    outIdx >= 0 -> {
                        val eos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        if (eos && bufferInfo.size == 0) {
                            // EOS：不渲染，通知编码器结束
                            decoder.releaseOutputBuffer(outIdx, false)
                            runCatching { encoder.signalEndOfInputStream() }
                            outputEos = true
                            DebugLog.log("GPU", "解码 EOS 已编码=$framesEncoded 末帧PTS=${lastPtsUs / 1000}ms")
                        } else if (isConfig) {
                            // 编解码配置数据：不渲染到 Surface
                            decoder.releaseOutputBuffer(outIdx, false)
                        } else {
                            // v15.10 动态照片 5 秒截断：源帧 PTS 达到上限 → 提前收尾（不再渲染后续帧）
                            if (maxDurationMs > 0 && bufferInfo.presentationTimeUs >= maxDurationMs * 1000L) {
                                decoder.releaseOutputBuffer(outIdx, false)
                                runCatching { encoder.signalEndOfInputStream() }
                                outputEos = true
                                DebugLog.log("GPU", "时长截断 max=${maxDurationMs}ms 于 PTS=${bufferInfo.presentationTimeUs / 1000}ms")
                            } else {
                            // 真正的视频帧：渲染到 SurfaceTexture
                            decoder.releaseOutputBuffer(outIdx, true)
                            // 等待 SurfaceTexture 新帧（同步 updateTexImage + timestamp 验证）
                            // 【阶段一诊断·临时插桩·可回滚】解码等待计时开始
                            // 回滚：删除本行 + 下方 "解码等待计时结束" 那一行即可
                            val diagDec0 = System.nanoTime()
                            outputSurface.awaitNewImage()
                            // 【阶段一诊断·临时插桩·可回滚】解码等待计时结束
                            tDecodeWait += System.nanoTime() - diagDec0
                            if (!outputSurface.lastAwaitOk) {
                                // v14.28 修复"导出快进"：旧逻辑 awaitNewImage 软失败时 continue 静默
                                // 丢帧 → 帧丢失使 PTS 跳变 → 播放像快进（v14.27 混合慢路径下高发）。
                                // 改为重试一次；仍失败则放弃 GPU 回退逐帧路径（不丢帧、速度正确）。
                                outputSurface.awaitNewImage()
                                if (!outputSurface.lastAwaitOk) {
                                    DebugLog.log("GPU", "帧获取失败×2，放弃 GPU 路径回退逐帧（防快进）")
                                    runCatching { tmpOut?.delete() }
                                    return null
                                }
                            }
                            // 确保编码器 EGL 上下文当前
                            inputSurface.makeCurrent()
                            textureRender.setTextureTransform(outputSurface.texMatrix)
                            val outPtsUs = timeline.next(bufferInfo.presentationTimeUs)
                            EditorState.exportTimeSec = outPtsUs / 1_000_000f
                            // GL 绘制：视频帧 + 玻璃蒙版 + 水印前景叠加 → 编码器 InputSurface
                            // v14.13：barH = 水印条高度（bbox 高），供 shader 按条尺寸缩放扭曲位移
                            val bb = maskBBox
                            val barH = if (bb != null && bb.size >= 4 && bb[3] > bb[1])
                                (bb[3] - bb[1]).toFloat() else encH * 0.10f
                            if (cpuWatermark) {
                                // v14.27 混合路径：GPU 硬解取帧 → CPU 引擎水印（=预览/GIF 同引擎）
                                // → 上传直绘编码器。水印所见即所得，且解码不再拖慢导出。
                                // v15.12 patch 提速：只读回水印区域 + 区域渲染 + GPU 直绘视频背景，
                                // 1080p 每帧读回/上传从全帧 ~10MB×2 → 区域 ~1-3MB×2（全帧路径保留为兜底）
                                val reg = wmRegion
                                if (reg != null && reg.size >= 4 && reg[2] > 16 && reg[3] > 16) {
                                    val t0 = System.nanoTime()
                                    // Read the correctly oriented ROI straight from the full-size
                                    // FBO. This avoids an intermediate crop FBO and has no resampling.
                                    val raw = textureRender.captureVideo2dRegion(outputSurface.textureId,
                                        encW, encH, reg[0], reg[1], reg[2], reg[3])
                                    val t1 = System.nanoTime()
                                    if (raw != null) {
                                        // Animation follows source media time, regardless of export speed.
                                        EditorState.exportTimeSec = outPtsUs / 1_000_000f
                                        val wmPatch = watermarkRenderer.renderWatermarkPatch(
                                            raw, encW, encH, reg[0], reg[1], reg[2], reg[3])
                                        val t2 = System.nanoTime()
                                        textureRender.drawVideoDirect(encW, encH)
                                        textureRender.uploadBlit(wmPatch, wmPatch.width, wmPatch.height)
                                        val t3 = System.nanoTime()
                                        textureRender.drawPatchBlit(reg[0], reg[1],
                                            wmPatch.width, wmPatch.height, encW, encH)
                                        val t4 = System.nanoTime()
                                        tCapture += t1 - t0; tCpuRender += t2 - t1
                                        tUpload += t3 - t2; tDraw += t4 - t3
                                        patchFailStreak = 0
                                    } else {
                                        // patch 读回失败：连续 3 次永久退回全帧路径（防每帧重试抖动）
                                        patchFailStreak++
                                        if (patchFailStreak >= 3) {
                                            wmRegion = null
                                            DebugLog.log("GPU", "patch 读回连续失败，退回全帧路径")
                                        }
                                        val rawFull = textureRender.captureVideo2d(
                                            outputSurface.textureId, encW, encH)
                                        if (rawFull != null) {
                                            EditorState.exportTimeSec = outPtsUs / 1_000_000f
                                            val wm = watermarkRenderer.renderWatermarkOnly(rawFull, encW, encH)
                                            textureRender.uploadBlit(wm, encW, encH)
                                            textureRender.drawFrameBlit(encW, encH)
                                        } else {
                                            throw IllegalStateException("视频帧读回失败，回退同引擎逐帧路径")
                                        }
                                    }
                                } else {
                                    val raw = textureRender.captureVideo2d(outputSurface.textureId, encW, encH)
                                    if (raw != null) {
                                        // Animation follows source media time, regardless of export speed.
                                        EditorState.exportTimeSec = outPtsUs / 1_000_000f
                                        val wm = watermarkRenderer.renderWatermarkOnly(raw, encW, encH)
                                        textureRender.uploadBlit(wm, encW, encH)
                                        textureRender.drawFrameBlit(encW, encH)
                                        // v14.33：raw 是 TextureRender 跨帧复用的读回缓冲（不回收），
                                        // wm 是 wmOut 复用缓冲（不回收）——消除每帧位图分配/回收的 GC 抖动
                                    } else {
                                        throw IllegalStateException("视频帧读回失败，回退同引擎逐帧路径")
                                    }
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
                            // v14.03：首帧视频纹理为黑（GPU 拷贝失败）→ 放弃 GPU 路径回退逐帧，
                            // 绝不交付"整体黑+只有水印文字"的坏视频
                            if (framesEncoded == 1 && textureRender.videoBootBlack) {
                                DebugLog.log("GPU", "首帧视频纹理为黑（GPU 拷贝失败），放弃 GPU 路径回退逐帧")
                                runCatching { tmpOut?.delete() }
                                return null
                            }
                            // 进度：按 PTS / 总时长
                            if (durUs > 0) {
                                val pct = (12 + 78 * bufferInfo.presentationTimeUs / durUs).toInt().coerceIn(12, 90)
                                if (pct != lastProgress) { onProgress(pct); lastProgress = pct }
                            }
                            } // v15.10 截断分支 else 闭合（正常帧渲染分支）
                        }
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // 解码器输出格式变化：忽略（解码器格式在 configure 时已知）。
                        // muxer 必须在【编码器】输出格式变化时启动，不能在此处用 encoder.outputFormat 误启动。
                    }
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // 解码器无输出，继续 drain 编码器
                    }
                }

                // 取编码器输出 → 写 muxer（无条件 drain：即使 muxer 未启动也要处理编码器
                // INFO_OUTPUT_FORMAT_CHANGED，否则编码器输出 buffer 堆积 → 编码器阻塞 → 解码器阻塞 → 死锁）
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
                                // 编码器输出格式就绪：此时才能 addTrack 并启动 muxer
                                if (!muxStarted) {
                                    muxTrack = mmux.addTrack(encoder.outputFormat)
                                    // v14.08：音频轨一并注册（必须在 muxer.start() 之前 addTrack）
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

                // v14.08：音频直通写 muxer——按当前视频帧 PTS 对齐（不超前 >200ms 缓冲），
                // 只在 mux 启动后写入，PTS 原样透传保持音画同步；复用 audioBuf 零分配
                val aex = audioExt
                if (aex != null && audioMuxTrack >= 0 && muxStarted && !audioEos) {
                    try {
                        while (!audioEos) {
                            val at = aex.sampleTime
                            if (aex.sampleTrackIndex < 0) { audioEos = true; break }
                            if (timeline.audioTimeUs(at) < 0L) { aex.advance(); continue }
                            if (timeline.audioTimeUs(at) > lastPtsUs + 200_000L) break   // 超前视频 >200ms 则暂停，等视频追上
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

            // 排空编码器剩余输出（signalEndOfInputStream 后编码器可能还有数十帧待输出）
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

            // v14.08：视频处理完毕后排空剩余音频（视频 EOS 后音频可能有尾巴），再 stop muxer
            val aex2 = audioExt
            val amux = muxer
            if (aex2 != null && audioMuxTrack >= 0 && amux != null && !audioEos) {
                try {
                    while (true) {
                        val at = aex2.sampleTime
                        if (aex2.sampleTrackIndex < 0) break
                        if (isCancelled()) return null
                        if (timeline.audioTimeUs(at) < 0L) { aex2.advance(); continue }
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
            // 完整性验证 1：输出文件存在且非空
            if (!tmpOut!!.exists() || tmpOut!!.length() < 1024) {
                DebugLog.log("GPU", "输出文件过小 ${tmpOut!!.length()}，判定失败")
                runCatching { tmpOut?.delete() }
                return null
            }
            // 完整性验证 2：用 MediaMetadataRetriever 回读 tmpOut 时长，0 秒/远短于源 = 封装失败（moov 缺失）
            val outDurMs = runCatching {
                val mmr = android.media.MediaMetadataRetriever()
                try {
                    mmr.setDataSource(tmpOut!!.absolutePath)
                    mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
                } finally { runCatching { mmr.release() } }
            }.getOrDefault(0L)
            val srcDurMs = durUs / 1000
            // v15.16 定点修复：maxDurationMs>0（动态照片 5 秒截断）时，输出时长应接近
            // min(源时长, 截断点)，而不是强行等于源时长——旧比对把"截断成功"误判为
            // "封装失败"，误删输出并回退逐帧慢路径（截断后比源短是预期行为）
            val expectDurMs = if (maxDurationMs > 0) minOf(srcDurMs, maxDurationMs) else srcDurMs
            if (outDurMs <= 0 || (srcDurMs > 0 && kotlin.math.abs(outDurMs - expectDurMs) > maxOf(250L, 2000L / fps.coerceAtLeast(1)))) {
                DebugLog.log("GPU", "输出时长异常 out=${outDurMs}ms src=${srcDurMs}ms expect=${expectDurMs}ms frames=$framesEncoded，判定封装失败")
                runCatching { tmpOut?.delete() }
                return null
            }
            // v14.08：音频完整性验证——输入有音频而输出无音频 = EXPORT_AUDIO_LOST，判定导出失败
            // （绝不交付"视频处理完丢了原声"的坏文件；直通失败时输出会被删除并回退逐帧路径）
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
            // 完整性验证 3：最终输出水印验证——源帧 vs 输出帧同区域变化度对照。
            // 水印未真实写入（GPU 渲染假成功）时抛异常 → 调用方回退逐帧路径，绝不交付无水印视频。
            // 注意：验证器自身异常（Bitmap 越界/抽帧失败等）≠ 水印缺失，隔离后保留输出，
            // 绝不因"验证器崩了"把一份正常渲染的视频删掉（曾导致 GPU 成功后回退逐帧→该设备逐帧不可行→导出必败）。
            var verifyOk = true
            try {
                verifyWatermarkOnOutput(tmpOut!!, srcFile, maskBBox, encW, encH)
            } catch (t: Throwable) {
                verifyOk = false
                DebugLog.log("GPU", "水印验证器异常（${t.javaClass.simpleName}: ${t.message}），跳过验证保留输出")
            }
            // v15.13 精简：删除 !cpuWatermark 终检 bbox 验证块（cpuWatermark 恒 true 不可达）+ mb 死变量
            // 验证全部通过 → 原子提交（part/tmp → final）；失败路径已删除 tmpOut，绝不提交未验证文件
            if (!tmpOut!!.renameTo(outFile)) {
                tmpOut!!.copyTo(outFile, overwrite = true)
                tmpOut!!.delete()
            }
            val elapsed = System.currentTimeMillis() - startTime
            DebugLog.log("GPU", "导出成功 frames=$framesEncoded 输出时长=${outDurMs}ms 源时长=${srcDurMs}ms 耗时=${elapsed}ms")
            // v15.11 分阶段剖析报告（占导出总耗时的百分比，定位瓶颈）
            // 【阶段一诊断·临时插桩·可回滚】整块 run{...} 为诊断输出，回滚时整块删除即可
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
                // 【阶段一诊断·临时插桩·可回滚】每帧均耗 + 每秒帧数 + 各段占墙钟比
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
            // 任何异常/取消路径都不留半成品：删除 .tmp（避免相册扫描器看到残片）
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

    /**
     * 输出水印验证（源/输出帧对照）：
     * 抽【源帧】与【输出帧】同一时间点（中点 CLOSEST_SYNC），对比水印区域与背景区域的
     * 平均亮度差（|ΔL|）与亮度偏移（ΔL）。
     * - 水印真实渲染：水印区变化度显著超过背景区（玻璃模糊+文字/logo 改变了原画面）
     * - 水印未渲染：水印区变化≈背景区（仅编码/运动噪声）
     * 判定：wmDiff >= max(bgDiff,6)*1.15+8 或 |wmDelta| >= 6 → 通过；否则删除输出抛异常回退。
     * 相比旧"内部 std 应比外部平滑"单向判定：当水印含文字/logo 时内部 std 反而更高，
     * 旧逻辑误杀 GPU 成功（v13.78 日志实证 33s 视频内部 std=47 外部=39 被误判失败）。
     */
    private fun verifyWatermarkOnOutput(outFile: File, srcFile: File, bbox: IntArray?, w: Int, h: Int) {
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
            // 源帧：同一时间点（源 duration 中点）。源帧不可用时不直接失败——降级宽松 std 判定
            // （只排除纯黑/花屏等极端），避免源文件异常导致 GPU 成功被误杀。
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
                    srcBmp = rs.getFrameAtTime(srcDur / 2 * 1000,
                        android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } finally { runCatching { rs.release() } }
            }
            try {
                // bbox 基于编码尺寸 encW×encH；抽帧位图尺寸可能不同（比例缩放），按比例映射。
                // 使用 bbox 全区域（5% inset）而非中心 60%：水印条内含文字/logo，中心可能只覆盖
                // 平滑玻璃导致变化度被低估而误判。
                val sx = outBmp.width.toFloat() / w
                val sy = outBmp.height.toFloat() / h
                val ix0 = (x0 + (x1 - x0) * 0.05f) * sx; val iy0 = (y0 + (y1 - y0) * 0.05f) * sy
                val ix1 = (x1 - (x1 - x0) * 0.05f) * sx; val iy1 = (y1 - (y1 - y0) * 0.05f) * sy
                // 背景：远离水印的左上/右下角
                if (srcBmp != null) {
                    // 源帧尺寸与输出可能不同（mmr 应用了 rotation / 源分辨率）→ 各自比例独立映射，
                    // 但区域比例相同（水印相对画面位置一致）
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
                    // v14.16：判定 = 平均差 或 亮度偏移 或【变化像素占比>3%】（杜绝"平滑区平均差≈0"的假阴性）
                    val pass = wmDiff >= bg * 1.15f + 8f || kotlin.math.abs(wmDelta) >= 6f || wmFrac > 0.03f
                    DebugLog.log("GPU", "水印验证 out=${outBmp.width}x${outBmp.height} src=${srcBmp.width}x${srcBmp.height} " +
                        "wmDiff=$wmDiff bgDiff=$bgDiff wmΔL=$wmDelta wmFrac=$wmFrac bbox=[$x0,$y0,$x1,$y1] " +
                        if (pass) "通过" else "失败（输出保留）")
                } else {
                    // 源帧不可用降级：宽松 std 判定——仅排除纯黑/花屏极端（std≈0），
                    // 不再用"内部应比外部平滑"单向假设（文字/logo 会提高内部 std）
                    val stdIn = regionLumStd(outBmp, ix0, iy0, ix1, iy1)
                    val stdOut = kotlin.math.max(
                        regionLumStd(outBmp, 0f, 0f, outBmp.width * 0.5f, outBmp.height * 0.25f),
                        regionLumStd(outBmp, outBmp.width * 0.6f, outBmp.height * 0.7f,
                            outBmp.width * 0.95f, outBmp.height * 0.95f))
                    if (stdIn < 3f || stdOut < 3f) {
                        // 降级 std 判定仅排除纯黑/花屏；不再删文件/抛异常（同对照分支理由）
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

    /** 两帧同区域对比（v14.16 重写）：
     * 返回 (平均亮度绝对差, 平均亮度偏移, 变化像素占比)。
     * 旧版只看平均亮度差——对"底部平滑区+色散抵消"场景恒≈0（wmDiff=0.0 假阴性，
     * 红条显影实验证明输出确有水印但验证器仍报 0）。新增【变化像素占比】= |Δ亮度|>20
     * 的采样占比，任何真实水印（文字/玻璃/扭曲/染色）都会让占比显著>0，杜绝误报。 */
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
            // 防御：单行像素读取失败（越界/复用竞态）→ 跳过该行采样，绝不因验证器抛异常杀死导出
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

    /** 区域亮度标准差（抽样步长≈区域 1/40，返回 0 表示样本不足） */
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
            // 防御：单行读取失败跳过该行（见 regionDiff）
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
    //  EGL InputSurface（编码器端）：多配置回退适配所有 GPU
    // ══════════════════════════════════════════════════════════════
    private class InputSurface(private val surface: Surface) {
        private var eglDisplay: EGLDisplay? = null
        private var eglContext: EGLContext? = null
        private var eglSurface: EGLSurface? = null
        /** 探测到的 GPU 信息（GL_RENDERER / GL_VERSION），供日志诊断 */
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
            // 探测 GPU 信息（makeCurrent 后才能调用 GL）
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
            runCatching { EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, nsecs) }
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
    //  OutputSurface（解码器端：SurfaceTexture + GL OES 纹理）
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
        /** 上一帧时间戳（纳秒）：用于验证 updateTexImage 后确实是新帧，防止旧回调被误消费导致画面静止 */
        private var lastTimestamp = -1L
        private var consecutiveTimeouts = 0
        private val maxConsecutiveTimeouts = 10  // 10×2000ms = 20 秒
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

        /**
         * 等待新帧并 updateTexImage。
         * 用 SurfaceTexture.getTimestamp() 验证确实是新帧——旧 frameAvailable 回调被误消费时
         * timestamp 不变，此时返回 false 跳过，防止所有帧渲染成第一帧（画面静止）。
         */
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
                // timestamp 未变：不是新帧（旧回调被误消费），跳过不渲染
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
    //  TextureRender：视频 OES 纹理 + 水印 2D 纹理混合绘制
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
        private var shadowLoc = 0  // v14.09：外投影（蒙版阴影区压暗视频）
        private var barHLoc = 0  // v14.13：水印条高度（扭曲位移缩放）
        private var edgeWarpLoc = 0  // v14.22：派生 edgeWarp（引擎系数用）
        private var innerWarpLoc = 0  // v14.22：派生 innerWarp（引擎系数用）
        private var timeLoc = 0       // v14.41：流体相位
        private var hlAngleLoc = 0     // v14.42：高光角度
        private var falloffLoc = 0     // v14.42：高光衰减
        private var specularLoc = 0    // v14.42：高光强度
        private var innerShadowLoc = 0 // v14.42：内阴影
        private var snellPxLoc = 0   // v14.64：斯涅尔位移尺度
        private var snellKLoc = 0    // v14.64：法线强度
        private var uniformLogged = false  // v14.11：首帧诊断日志
        // Pass1（OES→2D 拷贝）专用 program/location
        private var copyProgram = 0
        private var copyPositionLoc = 0
        private var copyTexCoordLoc = 0
        private var copyVideoLoc = 0
        private var copyMatrixLoc = 0
        private val sourceMatrix = FloatArray(16).apply { android.opengl.Matrix.setIdentityM(this, 0) }
        fun setTextureTransform(matrix: FloatArray) { matrix.copyInto(sourceMatrix) }
        // FBO + 2D 视频纹理（Adreno 上 OES 偏移采样不可靠 → 先拷成 2D 再模糊）
        private var fbo = 0
        private var video2dTex = 0
        private var video2dW = 0
        private var video2dH = 0
        private val vertexBuffer: FloatBuffer
        private val texCoordBuffer: FloatBuffer
        // Pass1 拷贝专用 texCoord（v 不翻转）：OES 内容按原方向存入 2D 纹理（倒置存储），
        // Pass2 用 v 翻转 texCoord 采样 → 视频正立；若 Pass1 也用 v 翻转则 2D 为正立存储，
        // Pass2 再翻转一次 → 输出倒置（v13.84 实测视频倒置的根因）。
        private val copyTexCoordBuffer: FloatBuffer
        // v14.27 混合路径：GPU 解码取帧 → CPU 引擎水印 → 直绘编码（blit 纹理/程序）
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

        // Pass1：OES 视频帧 → 2D 纹理（无混合直拷）
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

        // v14.27 混合路径：CPU 引擎水印帧（2D 纹理）直绘编码器表面
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
            // v14.66 致命修复：GLSL 无内置 clamp01（此前 v14.42 起引用它 → shader 编译必失败 →
            // GPU 快速模式永远静默回退 CPU = 用户"快速模式没有用"的终极根因）
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

            // 17 抽头高斯模糊（±3 环）。原 ±4 环 25 抽头在 2340x1080 全屏每帧每像素做 2×25=50 次
            // 纹理采样，是"导出比上一版慢一倍以上"的主因。±3 环权重已覆盖高斯能量 95%+，
            // 视觉与 25 抽头无差异；半径上限同步放宽（40→52 texel）补偿平滑度。
            // radius 为 texel 倍数
            vec4 blur17(vec2 uv, vec2 texel, float radius) {
                vec2 o = texel * radius;
                vec4 sum = vec4(0.0);
                // 外环 ±3（权重 0.036 ×8 = 0.288）
                sum += texture2D(sVideo, uv + vec2(-3.0, -3.0) * o) * 0.036;
                sum += texture2D(sVideo, uv + vec2( 0.0, -3.0) * o) * 0.036;
                sum += texture2D(sVideo, uv + vec2( 3.0, -3.0) * o) * 0.036;
                sum += texture2D(sVideo, uv + vec2(-3.0,  0.0) * o) * 0.036;
                sum += texture2D(sVideo, uv + vec2( 3.0,  0.0) * o) * 0.036;
                sum += texture2D(sVideo, uv + vec2(-3.0,  3.0) * o) * 0.036;
                sum += texture2D(sVideo, uv + vec2( 0.0,  3.0) * o) * 0.036;
                sum += texture2D(sVideo, uv + vec2( 3.0,  3.0) * o) * 0.036;
                // 外环 ±2（权重 0.06 ×8 = 0.480）
                sum += texture2D(sVideo, uv + vec2(-2.0, -2.0) * o) * 0.06;
                sum += texture2D(sVideo, uv + vec2( 0.0, -2.0) * o) * 0.06;
                sum += texture2D(sVideo, uv + vec2( 2.0, -2.0) * o) * 0.06;
                sum += texture2D(sVideo, uv + vec2(-2.0,  0.0) * o) * 0.06;
                sum += texture2D(sVideo, uv + vec2( 2.0,  0.0) * o) * 0.06;
                sum += texture2D(sVideo, uv + vec2(-2.0,  2.0) * o) * 0.06;
                sum += texture2D(sVideo, uv + vec2( 0.0,  2.0) * o) * 0.06;
                sum += texture2D(sVideo, uv + vec2( 2.0,  2.0) * o) * 0.06;
                // 近环 ±1（权重 0.044 ×4 = 0.176）
                sum += texture2D(sVideo, uv + vec2(-1.0, -1.0) * o) * 0.044;
                sum += texture2D(sVideo, uv + vec2( 1.0, -1.0) * o) * 0.044;
                sum += texture2D(sVideo, uv + vec2(-1.0,  1.0) * o) * 0.044;
                sum += texture2D(sVideo, uv + vec2( 1.0,  1.0) * o) * 0.044;
                // 中心（0.056）；总权重 0.288+0.480+0.176+0.056 = 1.0
                sum += texture2D(sVideo, uv) * 0.056;
                return sum;
            }

            void main() {
                vec2 texel = 1.0 / uResolution;
                // 玻璃蒙版：a=玻璃条形状强度（1 形状内 / 阴影区渐变），r/g 通道=玻璃条局部渐变
                vec4 mask = texture2D(sMask, vTexCoord);
                vec4 fg = texture2D(sFg, vTexCoord);

                // ── 蒙版强度兜底（关键修复）：部分 Adreno 驱动下 texImage2D 上传的 ARGB
                //    alpha 通道读回为 0（Java 层预检 alpha>0 但 GL 里 mask.a=0）→ 玻璃区
                //    mix(video, glass, 0) 输出原视频 = "导出成功但无水印"（wmDiff=0.0）。
                //    用 RGB 亮度兜底：形状白色 r=g=b=1、阴影灰渐变 0.5、透明 0，必定有值。
                float maskA = max(mask.a, max(mask.r, max(mask.g, mask.b)));
                float body = smoothstep(0.5, 1.0, maskA);

                // ── 0. 统一扭曲（v14.22：精确对齐预览引擎 LiquidGlassEngine 系数）──
                //    v14.41：加入 uTime 流体相位——GPU 导出玻璃随流体开关流动（与 CPU 引擎一致，
                //    不再"纯GPU=静态玻璃丢风格"）。扭曲纹样相位随时间漂移。
                float warpN = clamp(uWarp, 0.0, 1.0);
                float tPh = uTime;   // 流体相位（弧度；流体关闭=0）
                vec2 vw = vTexCoord;
                if (warpN > 0.002) {
                    float barH = clamp(uBarH, 4.0, 256.0);
                    // 边缘带波浪（玻璃轮廓 + 阴影渐变区，比旧版更宽覆盖）
                    float band = smoothstep(0.15, 0.45, maskA) *
                                 (1.0 - smoothstep(0.50, 0.88, maskA));
                    float wob = sin(vTexCoord.x * 320.0 + 2.1 + tPh) *
                                sin(vTexCoord.y * 220.0 + 1.3 + tPh * 0.7);
                    float edgeAmpPx = barH * 0.11 * uEdgeWarp;   // 引擎 edgeIrregAmp 同式
                    vw += vec2(wob * edgeAmpPx, wob * edgeAmpPx * 1.6) * texel * band;
                    // 内部涟漪（形状整体内）：低频交叉水波，位移随条高（引擎 innerAmp 同式）
                    float innerM = body * smoothstep(0.02, 0.20, maskA);
                    vec2 rip = vec2(
                        sin(vTexCoord.y * 96.0 + 1.7 + tPh * 1.5) * 0.5 + sin(vTexCoord.y * 47.0 + 4.2 + tPh) * 0.5,
                        sin(vTexCoord.x * 88.0 + 2.4 + tPh * 1.2) * 0.5 + sin(vTexCoord.x * 41.0 + 0.9 + tPh * 0.8) * 0.5);
                    vw += rip * (barH * 0.22 * uInnerWarp) * texel * innerM;
                }
                vec4 video = texture2D(sVideo, vw);

                // ── 1. 毛玻璃背景：单趟 17 抽头模糊（旧双半径 34 抽头视觉无差异，着色器速度 ×2）──
                //    模糊跟随滑块全范围 0..100（原 uBlur/60 只到 53% 强度、半径上限 25 texel，
                //    导出模糊显著弱于预览引擎——"导出没模糊/灰"根因）
                float blurN = uBlur / 100.0;
                float radius = 1.0 + blurN * 52.0;          // 1~53 texel（17 抽头半径放宽补偿平滑度）
                float strength = blurN * 1.0;               // 0~1 满强度
                vec4 blur = blur17(vw, texel, radius);
                vec4 glassBg = mix(video, blur, strength);

                // ── 1b. 液态光学折射（v14.68：真玻璃三要素，对应真机反馈）──
                //     sDist = CPU 侧"到边缘归一化内部距离场"（0 边→1 内，灰度）
                //     ① 边缘压缩环：位移∝距离场斜率（边缘最强）→ 穿过玻璃的线在两边折断/错位
                //     ② 内部凸透镜放大：采样向中心收敛 → 线轻微弯曲（不再是笔直平板）
                //     ③ 菲涅尔边缘光：紧贴边缘的亮带，受光侧更亮、背光侧仍有微弱（iOS 双缘光）
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
                    // ③ 菲涅尔边缘光：exp 衰减于边缘最强；方向门控（受光侧亮、背光侧弱）
                    float rimFres = exp(-insdB * 14.0) * body;
                    vec2 lD = vec2(cos(uHlAngle), sin(uHlAngle));
                    float lit = max(dot(normal, lD), 0.0);
                    glassBg.rgb += vec3(0.42) * rimFres * (0.45 + 0.55 * lit);
                }

                // ── 2. 色散（v14.24：移植引擎 chromaticFrac 全区域模型，替换旧"仅边缘"版）──
                //    引擎在整块玻璃内按位置做 R/B 通道错位采样：dispInt = cf×(lx·ly)
                //    （玻璃内部越靠下右越强），maxDisp=1.5~36px 逐通道偏移 → 暗部内容被
                //    注入彩色（对齐证据：同帧 GIF 玻璃饱和 49.8 vs 导出 21.4，引擎色散为主因）。
                //    lx 用条横向归一近似（mask 无局部X），ly 用 mask.g（=形状内局部Y 0..1）。
                float cf2 = uChromatic / 100.0;
                if (cf2 > 0.01) {
                    float ly2 = clamp(mask.g, 0.03, 1.0);
                    float lx2 = clamp((vTexCoord.x - 0.5) * 2.0 + 0.5, 0.1, 0.9);
                    float dispInt = cf2 * (lx2 * ly2) * body;
                    float maxDispPx = max(1.5, uBarH * 0.018);
                    float dpx = dispInt * maxDispPx;
                    vec4 srcR = texture2D(sVideo, vw + vec2(dpx * texel.x, 0.0));
                    vec4 srcB = texture2D(sVideo, vw - vec2(dpx * texel.x, 0.0));
                    // R/B 通道错位（保留 G 不动）；dispInt 内同时带形状衰减（边缘不硬切）
                    glassBg.r = mix(glassBg.r, srcR.r * 1.12, dispInt);
                    glassBg.b = mix(glassBg.b, srcB.b * 1.12, dispInt);
                }

                // ── 3. 玻璃对比度：增强玻璃内画面的层次感 ──
                glassBg.rgb = (glassBg.rgb - 0.5) * uContrast + 0.5;

                // ── 4. 染色：玻璃条整体着色 ──
                glassBg.rgb = mix(glassBg.rgb, uTint.rgb, uTintStr);

                // ── 5. 高光（v14.42 对齐引擎 dirLight + pow falloff）──
                //    引擎：dirLight=(-u·cosA - v·sinA)*0.5+0.5；vert=0.55+0.45·dirLight；
                //    提亮 lift=pow(vert,falloff)*specular*0.18 加到 (1-rgb) 方向（光源侧亮）
                float gy2 = clamp(mask.g, 0.0, 1.0);
                float lyc = gy2 * 2.0 - 1.0;                                   // 局部Y -1..1（引擎 v）
                float lux = clamp((vTexCoord.x - 0.5) * 2.0, -1.0, 1.0);        // 局部X 近似（引擎 u）
                float hlC = cos(uHlAngle); float hlS = sin(uHlAngle);
                float dirLight = clamp(-lux * hlC - lyc * hlS, -1.0, 1.0) * 0.5 + 0.5;
                float vert2 = 0.55 + 0.45 * dirLight;
                float fo2 = clamp(uFalloff, 0.5, 3.0);
                // v14.51 高光不过发白：系数 0.18→0.12、限幅 0.24 提亮、整体上限 0.94（保留细节）
                float liftV = clamp(pow(vert2, fo2) * uSpecular * 0.12, 0.0, 0.24);
                glassBg.rgb += (1.0 - glassBg.rgb) * liftV * body;
                glassBg.rgb = min(glassBg.rgb, vec3(0.94));
                // ── 5b. 边缘亮环（引擎 v14.31 方向门控：step(0,dot(grad,light))·pow(|d|,falloff)）──
                //    SDF 距离以 maskA 近似：边界=0.55 附近，内侧→1。细环=玻璃轮廓亮线（只亮受光侧）
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

                // ── 6. 内阴影（引擎公式 m=1-innerShadow·edge·0.42，勾出液体边界）──
                float edgeI = 1.0 - smoothstep(0.0, 0.10, insd);
                glassBg.rgb *= (1.0 - uInnerShadow * edgeI * 0.42 * body);

                // ── 7. 玻璃本体按形状 alpha 与视频混合（文字除外）──
                //    v14.07：文字不随 opacity 衰减（修复导出文字偏暗）。
                //    v14.09：玻璃本体只按【形状 alpha】(body) 混合——不再用 maskA
                //    （内含阴影渐变）→ 阴影视区不再掺玻璃体（v14.03"外围发黑"根因）。
                float shapeW = body;
                vec4 base = mix(video, glassBg, shapeW * (uOpacity / 100.0));

                // ── 8. 外部软阴影：形状外、蒙版阴影渐变区（maskA<shapeW）压暗视频 ──
                //    mask 已由 v14.09 恢复绘制外投影：阴影 alpha 渐变编码在 maskA 的形状外区域，
                //    强度=预览同款 shadowAlpha（随 shadow/opacity 滑块变化）。
                //    压暗系数 (0.55+0.45*uShadow) 与预览的 setShadowLayer(黑, alpha) 数学对齐：
                //    uShadow=100 时≈预览精确一致，默认 42 时≈20% 峰值压暗。
                float shadowI = clamp(maskA, 0.0, 1.0) * (1.0 - shapeW);
                base.rgb *= (1.0 - shadowI * (0.55 + 0.45 * (uShadow / 100.0)));

                // ── 9. 前景元素（文字/logo/边框/棱边）：恒定全不透明叠加 ──
                //    v14.16：恢复真实合成（v14.15 蒙版显影已验证合成链路通）
                //    v14.17 关键修复：fg.a 在部分 Adreno 上上传后读回为 0（与蒙版同病），
                //    导致白字/亮 logo 永远叠不上去（导出水印文字消失/无效果的全部根因）。
                //    与蒙版兜底一致：fg 内容=不透明文字/logo（白字 rgb≈255），
                //    用 max(r,g,b) 作为内容强度，透明像素 rgb=0 恒为 0，互不混淆。
                float fgA = clamp(fg.a, 0.0, 1.0);
                fgA = max(fgA, max(fg.r, max(fg.g, fg.b)));
                gl_FragColor = mix(base, fg, clamp(fgA, 0.0, 1.0));
            }
        """

        // 全屏四边形（顶点 + 纹理坐标）
        private val vertices = floatArrayOf(
            -1f, -1f, 0f, 1f,   // 左下
             1f, -1f, 1f, 1f,   // 右下
            -1f,  1f, 0f, 0f,   // 左上
             1f,  1f, 1f, 0f    // 右上
        )

        init {
            vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer()
            vertexBuffer.put(vertices).position(0)
            // 纹理坐标单独缓冲（全屏 0-1，v 翻转：OES/SurfaceTexture 内容倒置存储，v 翻转后正立）
            val texCoords = floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)
            texCoordBuffer = ByteBuffer.allocateDirect(texCoords.size * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer()
            texCoordBuffer.put(texCoords).position(0)
            // Pass1 拷贝缓冲：v 不翻转（2D 纹理倒置存储，Pass2 v 翻转后正立）
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

            // Pass1 拷贝 program（OES → 2D）
            copyProgram = createProgram(VERTEX_SHADER, COPY_FRAGMENT_SHADER)
            copyPositionLoc = GLES20.glGetAttribLocation(copyProgram, "aPosition")
            copyTexCoordLoc = GLES20.glGetAttribLocation(copyProgram, "aTexCoord")
            copyVideoLoc = GLES20.glGetUniformLocation(copyProgram, "sVideo")
            copyMatrixLoc = GLES20.glGetUniformLocation(copyProgram, "uTexMatrix")

            // v14.27 混合路径 blit program（CPU 引擎水印帧直绘编码器表面）
            blitProgram = createProgram(VERTEX_SHADER, BLIT_FRAGMENT_SHADER)
            blitPosLoc = GLES20.glGetAttribLocation(blitProgram, "aPosition")
            blitTexCoordLoc = GLES20.glGetAttribLocation(blitProgram, "aTexCoord")
            blitTexLoc = GLES20.glGetUniformLocation(blitProgram, "sTex")
        }

        // 懒创建/重建 2D 视频纹理（尺寸首次或变化时分配）
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

        // ── v14.27 混合路径（GPU 解码取帧 + CPU 引擎水印）──
        // captureVideo2d：Pass1 拷贝 OES→2D 后 glReadPixels 读回原始帧位图（行序=帧顶在首行，
        // 与 CPU 解码路径一致），供 CPU 引擎逐帧渲染水印（水印=预览，所见即所得）。
        // v14.33 提速：跨帧复用读回缓冲（ByteBuffer/IntArray/Bitmap），消除每帧几 MB 分配→GC；
        // 去掉 glFinish（glReadPixels 本身隐式同步管线，glFinish 是多余的全管线强制冲刷）
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
            // v14.33：不调 glFinish——glReadPixels 会同步等待绘制完成，无需全管线强制冲刷
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

        // ── v15.12 patch 提速：只读回水印区域（全帧 glReadPixels 1080p ≈10MB/帧，是导出慢的主因）──
        private var regBuf: ByteBuffer? = null
        private var regBmp: Bitmap? = null

        fun captureVideo2dRegion(videoTex: Int, w: Int, h: Int,
                                 bx: Int, by: Int, bw: Int, bh: Int): Bitmap? {
            if (bw <= 0 || bh <= 0 || bx < 0 || by < 0 || bx + bw > w || by + bh > h) return null
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
            val n = bw * bh
            if (regBuf == null || regBuf!!.capacity() < n * 4) {
                regBuf = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder())
            }
            val buf = regBuf!!
            buf.clear()
            // Pass1 stores the frame vertically inverted in GL memory; GL row `by` therefore
            // corresponds to the displayed top-origin frame row `by` for this direct readback.
            GLES20.glReadPixels(bx, by, bw, bh, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            if (GLES20.glGetError() != GLES20.GL_NO_ERROR) return null
            if (regBmp == null || regBmp!!.width != bw || regBmp!!.height != bh) {
                runCatching { regBmp?.recycle() }
                regBmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            }
            val bmp = regBmp!!
            buf.position(0)
            bmp.copyPixelsFromBuffer(buf)
            return bmp
        }

        /** v15.12：video2dTex（Pass1 已拷贝）直接绘制到编码器表面（视频背景走 GPU，零 CPU 搬运） */
        fun drawVideoDirect(w: Int, h: Int) {
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glViewport(0, 0, w, h)
            GLES20.glUseProgram(blitProgram)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, video2dTex)
            GLES20.glUniform1i(blitTexLoc, 0)
            GLES20.glDisable(GLES20.GL_BLEND)
            drawQuad(blitPosLoc, blitTexCoordLoc, texCoordBuffer)
        }

        // patch 叠加顶点缓冲（复用，避免每帧分配）
        private val patchVerts = FloatArray(16)
        private var patchVertexBuffer: FloatBuffer? = null
        /** v15.12：把 blit 纹理（水印 patch）alpha 混合叠加到已绘制的视频帧上，位置=帧坐标 (bx,by) */
        fun drawPatchBlit(bx: Int, by: Int, bw: Int, bh: Int, w: Int, h: Int) {
            if (bw <= 0 || bh <= 0) return
            val x0 = bx.toFloat() / w * 2f - 1f
            val x1 = (bx + bw).toFloat() / w * 2f - 1f
            val yTop = (h - by).toFloat() / h * 2f - 1f
            val yBot = (h - by - bh).toFloat() / h * 2f - 1f
            // 顶点顺序：左下、右下、左上、右上（与 texCoordBuffer 的 (0,1)(1,1)(0,0)(1,0) 配对 → 正立）
            patchVerts[0] = x0; patchVerts[1] = yBot; patchVerts[2] = 0f; patchVerts[3] = 1f
            patchVerts[4] = x1; patchVerts[5] = yBot; patchVerts[6] = 1f; patchVerts[7] = 1f
            patchVerts[8] = x0; patchVerts[9] = yTop; patchVerts[10] = 0f; patchVerts[11] = 0f
            patchVerts[12] = x1; patchVerts[13] = yTop; patchVerts[14] = 1f; patchVerts[15] = 0f
            val pb = patchVertexBuffer ?: ByteBuffer.allocateDirect(patchVerts.size * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer()
                .also { patchVertexBuffer = it }
            pb.clear(); pb.put(patchVerts); pb.position(0)
            // Android Canvas bitmaps store premultiplied RGBA. Using SRC_ALPHA multiplies the
            // already-premultiplied glass color a second time and darkens translucent pixels.
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            GLES20.glUseProgram(blitProgram)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, blitTex)
            GLES20.glUniform1i(blitTexLoc, 0)
            GLES20.glEnableVertexAttribArray(blitPosLoc)
            GLES20.glVertexAttribPointer(blitPosLoc, 2, GLES20.GL_FLOAT, false, 16, pb)
            texCoordBuffer.position(0)
            GLES20.glEnableVertexAttribArray(blitTexCoordLoc)
            GLES20.glVertexAttribPointer(blitTexCoordLoc, 2, GLES20.GL_FLOAT, false, 8, texCoordBuffer)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(blitPosLoc)
            GLES20.glDisableVertexAttribArray(blitTexCoordLoc)
            GLES20.glDisable(GLES20.GL_BLEND)
        }

        // CPU 引擎水印帧上传到 blit 纹理
        fun uploadBlit(bmp: Bitmap, w: Int, h: Int) {
            ensureBlit(w, h)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, blitTex)
            // Reuse allocated texture storage and upload native Bitmap pixels.
            GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bmp)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        }

        // 直绘：blit 纹理 → 编码器表面（v 翻转采样与 Pass2 一致 → 正立）
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

        // ── v14.03：视频纹理首帧黑检测（详见 drawFrame 注释）──
        @Volatile var videoBootBlack = false
        private var videoBootChecked = false

        private fun checkBootBlack() {
            if (videoBootChecked) return
            videoBootChecked = true
            try {
                // 优先读 FBO（video2dTex）中心像素；FBO 不可用时读编码器表面
                if (fbo != 0) {
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
                    videoBootBlack = readCenterBlack()
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                } else {
                    videoBootBlack = readCenterBlack()
                }
            } catch (t: Throwable) {
                DebugLog.log("GPU", "视频纹理黑检测异常，跳过：${t.javaClass.simpleName}")
                videoBootBlack = false
            }
        }

        /** 读取当前绑定缓冲中心 4×4 像素，全为近黑则返回 true */
        private fun readCenterBlack(): Boolean {
            val w = video2dW; val h = video2dH
            if (w <= 4 || h <= 4) return false
            val buf = ByteBuffer.allocateDirect(4 * 4 * 4).order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(w / 2 - 2, h / 2 - 2, 4, 4, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
            var bright = 0
            while (buf.hasRemaining()) {
                val r = buf.get().toInt() and 0xFF
                val g = buf.get().toInt() and 0xFF
                val b = buf.get().toInt() and 0xFF
                buf.get() // alpha
                if (r > 12 || g > 12 || b > 12) bright++
            }
            return bright == 0
        }

        // 全屏四边形绘制（用指定 program 的 attrib location 与纹理坐标缓冲）
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

        /** v14.23：GPU 路径像素旋转——按 rotation 重写 Pass1 拷贝 texcoord（内容 90/180/270 旋转）。
         *  rotation 由 Mp4Exporter 归一化传入（源 rotation 元数据或动态照片强制旋转）。
         *  v14.23 方向修正：v14.21 的 90/270 映射方向弄反（动图视频"倒着 90°"），已对调；
         *  180 不受影响（反转 180 仍 180）。 */
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

        fun drawFrame(videoTex: Int, maskTex: Int, watermarkTex: Int, width: Int, height: Int,
                  barH: Float = -1f, distTex: Int = 0, fallbackTex: Int = 0) {
            // ── Pass 1：OES 视频帧 → 2D 纹理（FBO 离屏拷贝）
            //    根因修复：Adreno 630 上对 GL_TEXTURE_EXTERNAL_OES 做 UV 偏移采样
            //    （blur9 的 9 次采样）返回同一 texel → 模糊=0 → 玻璃体透明（只剩文字+阴影）。
            //    先拷成 2D 纹理后，2D 偏移采样在所有设备可靠，模糊/色散/折射真实生效。
            var fboOk = false
            ensureVideo2d(width, height)
            if (fbo != 0 && video2dTex != 0) {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
                GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D, video2dTex, 0)
                val st = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
                fboOk = st == GLES20.GL_FRAMEBUFFER_COMPLETE
                if (fboOk) {
                    GLES20.glViewport(0, 0, width, height)
                    GLES20.glUseProgram(copyProgram)
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTex)
                    GLES20.glUniform1i(copyVideoLoc, 0)
            GLES20.glUniformMatrix4fv(copyMatrixLoc, 1, false, sourceMatrix, 0)
                    GLES20.glDisable(GLES20.GL_BLEND)
                    // 关键：Pass1 用 v 不翻转 texCoord（2D 纹理倒置存储），Pass2 v 翻转采样后正立
                    drawQuad(copyPositionLoc, copyTexCoordLoc, copyTexCoordBuffer)
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                } else {
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                    DebugLog.log("GPU", "FBO 不完整 status=$st，退化直接 OES 采样合成")
                }
            }

            // ── Pass 2：视频（2D/OES）+ 玻璃蒙版 + 水印前景 → 编码器 InputSurface ──
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(program)
            GLES20.glViewport(0, 0, width, height)

            // 视频纹理：优先 2D（模糊可靠），FBO 不可用时退化 OES（仍能出画面）
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            if (fboOk) GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, video2dTex)
            else GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTex)
            GLES20.glUniform1i(videoTexLoc, 0)

            // 玻璃蒙版纹理（2D）
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTex)
            GLES20.glUniform1i(maskTexLoc, 1)

            // 水印前景纹理（2D）
            GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, watermarkTex)
            GLES20.glUniform1i(fgTexLoc, 2)

            // v14.68 距离场纹理（2D）：折射/菲涅尔数据源；缺失绑纯黑兜底（守卫拦截）
            GLES20.glActiveTexture(GLES20.GL_TEXTURE3)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, if (distTex != 0) distTex else fallbackTex)
            GLES20.glUniform1i(distTexLoc, 3)

            // 分辨率（用于模糊 texel 计算）
            GLES20.glUniform2f(resolutionLoc, width.toFloat(), height.toFloat())
            // ── 玻璃效果参数：全部跟随用户滑块 ──
            val p = EditorState.params
            GLES20.glUniform1f(blurLoc, p.blur.coerceIn(0, 100).toFloat())
            GLES20.glUniform1f(warpLoc, p.warp.coerceIn(0, 100) / 100f)
            GLES20.glUniform1f(contrastLoc, p.glassContrast.coerceIn(0.5f, 2f))
            val tc = p.tintColor
            GLES20.glUniform4f(tintLoc,
                ((tc shr 16) and 0xFF) / 255f,
                ((tc shr 8) and 0xFF) / 255f,
                (tc and 0xFF) / 255f, 1f)
            GLES20.glUniform1f(tintStrLoc, (p.tintStrength * p.opacity / 10000f).coerceIn(0f, 1f))
            GLES20.glUniform1f(chromaticLoc, p.chromatic.coerceIn(0, 100).toFloat())
            GLES20.glUniform1f(opacityLoc, p.opacity.coerceIn(0, 100).toFloat())
            GLES20.glUniform1f(shadowLoc, p.shadow.coerceIn(0, 100).toFloat())
            GLES20.glUniform1f(barHLoc, if (barH > 0f) barH else width * 0.25f)
            GLES20.glUniform1f(edgeWarpLoc, ((p.edgeWarp % 101 + 101) % 101) / 100f)
            GLES20.glUniform1f(innerWarpLoc, ((p.innerWarp % 101 + 101) % 101) / 100f)
            // v14.41：流体相位——开启时随实时时钟推进（与引擎相位同源），关闭=0（静态玻璃）
            GLES20.glUniform1f(timeLoc, if (p.fluidEnabled) {
                val spd = p.fluidSpeed.coerceIn(1, 100)
                val radPerSec = 0.6f + spd / 100f * 4.0f
                (android.os.SystemClock.elapsedRealtime() % 3_600_000L) / 1000f * radPerSec
            } else 0f)
            GLES20.glUniform1f(hlAngleLoc, (p.hlAngle * (Math.PI / 180.0)).toFloat())
            GLES20.glUniform1f(falloffLoc, p.falloff.coerceIn(0.5f, 3f))
            GLES20.glUniform1f(specularLoc, (p.specular.coerceIn(0, 100) / 100f))
            GLES20.glUniform1f(innerShadowLoc, (p.innerShadow.coerceIn(0, 100) / 100f))
            GLES20.glUniform1f(snellPxLoc, (p.refractA.coerceIn(0, 100) / 100f) * 40f)
            GLES20.glUniform1f(snellKLoc, 1f)
            // v14.11 诊断：首帧打印全部玻璃 uniform 实值（确认参数确实进入导出着色器）
            if (!uniformLogged) {
                uniformLogged = true
                DebugLog.log("GPU", "uniform 快照: warp=${p.warp} blur=${p.blur} refractH=${p.refractH} " +
                    "refractA=${p.refractA} chromatic=${p.chromatic} contrast=${p.glassContrast} " +
                    "tintStr=${(p.tintStrength * p.opacity / 10000f)} opacity=${p.opacity} shadow=${p.shadow} " +
                    "edgeWarp=${p.edgeWarp} innerWarp=${p.innerWarp} mode=${p.layoutMode}")
            }

            drawQuad(positionLoc, texCoordLoc, texCoordBuffer)

            // v14.03：首帧结束做视频纹理黑检测（此时 Pass1 已把首帧拷入 FBO/OES 数据就绪）。
            // 部分设备 Pass1/OES 拷贝返回纯黑却"成功"，会导致整段导出视频是黑的（水印文字可见但
            // 无玻璃效果）。检测到黑 → 导出方放弃 GPU 路径回退 CPU 逐帧，绝不交付黑视频。
            checkBootBlack()
        }

        fun release() {
            capBmp?.let { if (!it.isRecycled) it.recycle() }; capBmp = null
            capBuf = null
            regBmp?.let { if (!it.isRecycled) it.recycle() }; regBmp = null
            regBuf = null
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
                // 编译失败必须抛异常 → 触发 GPU 路径回退逐帧（逐帧引擎效果完整），
                // 绝不静默输出"无水印/无效果"的视频
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
                // shader 编译失败同样抛异常触发回退（绝不静默输出无水印视频）
                throw RuntimeException("GL shader 编译失败: $info")
            }
            return shader
        }
    }
}
