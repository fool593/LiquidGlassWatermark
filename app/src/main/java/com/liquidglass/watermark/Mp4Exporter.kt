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

/**
 * 流式 MP4 导出：动态照片源 MP4 → 逐帧解码（全分辨率）→ 画水印 → H.264 编码 → MP4 封装。
 * 分辨率/帧率/时长完全跟随源（不重采样、不设上限），帧不整体缓存（每帧处理完即弃），
 * 因此超长/超高分辨率视频也不会 OOM。仅用 API26+ 原生 MediaExtractor/MediaCodec/MediaMuxer。
 *
 * 编码器容错（核心）：候选 = 默认实例 + 系统软件编码器（任何设备必有、绝不卡死）+ 硬件编码器最多 2 个。
 * 不再用 dequeueInputBuffer 做"启动探测"（部分 ROM 上该调用无视 timeout 参数永久阻塞——进度条卡 10% 的
 * 已知死点）。真死锁由 feed 每帧 15s 超时兜底，超时即判该编码器死亡 → 自动换下一个候选重新完整导出，
 * 绝不把"编码器输入阻塞"直接甩给用户（此前：默认编码器配置成功但入队即死 → 一次失败就整体失败）。
 */
object Mp4Exporter {

    /** 最近一次导出失败原因（供 UI 带出诊断，避免"解码失败"干巴巴） */
    @Volatile var lastFailReason: String = ""

    /** 编码器运行时死亡（configure/start 成功但入队无响应）：外层捕获后换下一个候选重来 */
    private class EncoderDeadException(reason: String) : Exception(reason)

    /**
     * @param userRot 用户照片旋转角（0/90/180/270），输出尺寸在视频旋转基础上再叠加上去
     * @param onFrame 每帧回调：解码出的全分辨率帧（已含视频元数据旋转），返回与编码尺寸一致的
     *                Bitmap（含水印）；Mp4Exporter 负责回收入参与返回值
     * @param prefixFrame 可选前缀帧（动态照片的图片封面），会在视频帧之前编码 prefixDurationMs 毫秒
     * @param codec 编码器 MIME：默认 H.264（兼容性最好）；传 HEVC 且设备支持时用 H.265（同画质体积减半）
     * @return 成功返回输出 MP4 文件；失败返回 null（不抛）
     */
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
        // true=回调返回的水印帧由调用方自行复用管理（逐帧同尺寸、零每帧整帧分配），编码器不再回收；
        // false=回调每帧新建 bitmap，编码器用完回收（vivo 双文件路径）。
        reuseOutput: Boolean = false,
        // GPU 零拷贝加速：传入水印渲染器时优先走 GPU 路径（硬解→OpenGL水印→硬编，全程零拷贝），
        // 失败自动回退逐帧 Bitmap 路径。null=跳过 GPU 路径。
        watermarkRenderer: WatermarkEditorView? = null,
        // 取消回调：返回 true 时 GPU 导出循环立即中止；逐帧路径由调用方在 onFrame 里检查
        isCancelled: () -> Boolean = { false },
        // v14.08 音频直通开关：普通视频导出=true（源有音频必须保留，音画同步）；
        // 动态照片视频=false（Google/vivo 动照片标准视频轨不含音频，避免破坏生态识别）
        includeAudio: Boolean = true,
        // v15.10 动态照片 5 秒截断：>0 时源帧 PTS 达到该毫秒数即停止编码（输出=前 maxDurationMs）。
        // GPU 与逐帧两条路径均生效；截断是有意行为，跳过"解码未完成/时长不足"失败判定
        maxDurationMs: Long = 0L
    ): File? {
        lastFailReason = ""
        // ---- 源文件统一落盘【只做一次】：probe / 正式解码 / mmr 全程复用同一个本地文件 ----
        // 旧逻辑 srcUri 在每个 fallback 候选里都重新复制一遍（probe + 十几个解码器 = 十几次完整
        // 复制），慢源直接卡死且此阶段无进度更新 → "卡 10%"。
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
        // 本地 File Uri：MotionDecoder 见 file scheme 直接读、不再复制
        val localUri = android.net.Uri.fromFile(srcLocal)
        val src: String = srcLocal.absolutePath

        // 源视频探测：只读轨道格式（本地文件不会挂起），拿帧率与旋转
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
        // 回收 probe 解码出的全部帧（maxFrames=1 通常仅一帧，逐个回收杜绝极端情况下的位图泄漏）
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
        // 输出尺寸：probe 成功时帧已按权威 rotation 旋转，fW/fH 即最终输出方向尺寸，直接采用，
        // 绝不能再 normalize/交换——否则竖屏帧 h>w 会把 rotation 误清零，正式解码拿到 rotationOverride=0
        // 不旋转 → 横屏帧被拉伸进竖屏编码器（内容横置+比例变形的根因）。
        // 仅 probe 失败（fW/fH 为未旋转原始像素）时才按 rotation 交换兜底。
        var encW: Int
        var encH: Int
        if (probeFrame != null) {
            encW = fW; encH = fH
        } else {
            // probe 失败兜底：fW/fH 为未旋转原始像素。优先采用调用方强制 rotation（动态照片从封面推断），
            // 否则用轨道元数据；再按 rotation 交换编码器尺寸
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

        // 编码器：默认 H.264；HEVC 仅在设备支持时生效
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
                    // isHardwareAccelerated 为 API29+；API28 按编码器名启发式判定（google/c2.android 为软解）
                    val hw = if (Build.VERSION.SDK_INT >= 29) ci.isHardwareAccelerated
                    else !(ci.name.contains("google", true) || ci.name.contains("c2.android", true))
                    allEnc.add(ci.name to hw)
                }
            }
        }
        // 候选排序：系统默认实例若是硬件加速（createEncoderByType 通常返回厂商推荐最优编码器）排第一；
        // 其余硬件编码器其次；默认实例若为软解则与软解一起垫底。确保"最快又最稳"的编码器最先被使用。
        // 仅探测默认实例名称：创建后立即 release（旧代码只创建不释放，每次导出泄漏一个 native 编码器）
        val defProbe = runCatching { MediaCodec.createEncoderByType(realCodec) }.getOrNull()
        val defName = defProbe?.name
        runCatching { defProbe?.release() }
        val defIsHw = allEnc.firstOrNull { it.first == defName }?.second
            ?: (defName != null && !defName.contains("google", true) &&
                !defName.contains("c2.android", true))
        val candidates = ArrayList<String>()
        if (defIsHw) candidates.add("")  // 默认实例（硬解）第一
        // 硬件编码器（厂商硬解：qcom/mtk/exynos/hi/arm/renesas 等，排除 google/c2.android 软解）
        val hwNames = allEnc.filter { it.second &&
            !it.first.contains("google", true) &&
            !it.first.contains("c2.android", true) }
        for ((name, _) in hwNames) {
            if (candidates.size >= 3) break
            if (name !in candidates && !(defIsHw && name == defName)) candidates.add(name)
        }
        // 默认实例（若已是硬解候选则跳过；软解默认实例兜底仍可用）
        if ("" !in candidates && !defIsHw) candidates.add("")
        // 软解兜底（最慢，仅当硬解全死时用）
        allEnc.filter { !it.second || it.first.contains("google", true) || it.first.contains("c2.android", true) }
            .forEach { if (it.first !in candidates && it.first != defName) candidates.add(it.first) }
        if (candidates.size > 6) candidates.subList(6, candidates.size).clear()

        // Surface decode/encode path, with the same CPU material renderer used in preview.
        // Native pixel transfer and reused buffers avoid per-pixel Kotlin copies.
        // A failed Surface path falls back to the same material renderer below.
        if (watermarkRenderer != null && prefixFrame == null) {
            onProgress(8)
            val gpuOut = File(ctx.cacheDir, "lgw_gpu_${System.currentTimeMillis()}.mp4")
            // v14.54：快速模式失败必须显性化——记录原因+toast，禁止静默回退（用户会以为"快速没用"）
            var gpuFailReason: String? = null
            val t0 = System.currentTimeMillis()
            val gpuResult = runCatching {
                // v14.73 预览=导出的结构性保证：快速/混合 一律用【预览同引擎】渲染水印（cpuWatermark=true），
                // GPU 只做硬解+硬编（真 GPU）。水印画面与预览是同一个 WatermarkRenderer.render + 同一份
                // 参数——构造上不可能不同（不再有 shader 近似路径）。mode 2=纯CPU 时 renderer=null 不进此块。
                GpuMp4Exporter.export(ctx, srcLocal, gpuOut, encW, encH, fps, (rotation + ur) % 360, realCodec,
                    watermarkRenderer, onProgress, isCancelled,
                    cpuWatermark = true, includeAudio = includeAudio,
                    maxDurationMs = maxDurationMs)
            }.onFailure { e -> gpuFailReason = "${e.javaClass.simpleName}: ${e.message}" }.getOrNull()
            if (gpuResult != null && gpuOut.exists() && gpuOut.length() > 1024) {
                val ms = (System.currentTimeMillis() - t0).coerceAtLeast(1)
                DebugLog.log("ENC", "GPU 导出成功 frames=${gpuResult.frames} 时长=${gpuResult.durationMs}ms 耗时=${ms}ms 速率=${gpuResult.frames * 1000 / ms}fps")
                if (ownLocal) runCatching { srcLocal.delete() }
                onProgress(100)
                return gpuOut
            }
            // 用户取消：立即返回 null，不回退逐帧路径（否则取消无效，继续导出）
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
            // v14.78 完全修复（真机日志锁定）：GPU 失败→一律回退【同一引擎】逐帧路径
            // （=预览同函数渲染，非降质）——导出绝不因 GPU 加速失败而失败
            DebugLog.log("ENC", "回退逐帧 Bitmap 路径（同引擎=预览观感，原因: $reason）")
            runCatching { gpuOut.delete() }
            onProgress(12)
        }

        // 逐个候选完整导出：某编码器运行时死亡（feed 15s 无输入槽）→ 抛 EncoderDeadException → 换下一个。
        // 每个候选输出文件独立命名，避免残留串扰。
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
                    includeAudio, maxDurationMs, srcBitrate)
                if (ownLocal) runCatching { srcLocal.delete() }
                return r
            } catch (e: EncoderDeadException) {
                lastDead = e.message ?: "编码器输入阻塞"
                DebugLog.log("ENC", "编码器死亡（$triedN/${candidates.size}）cand=${if (cand.isEmpty()) "默认" else cand}：$lastDead，换下一个")
                runCatching { outFile.delete() }
            } catch (t: Throwable) {
                // 非编码器死亡的异常（视频损坏/解码失败等）：重试其他编码器无意义，直接失败
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

    /** 用单个候选编码器完整导出；编码器入队超时抛 [EncoderDeadException] 供外层换候选 */
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
        // v15.10 动态照片 5 秒截断（逐帧路径）：>0 时源帧 PTS 达到该毫秒数即停止编码
        maxDurationMs: Long = 0L,
        sourceBitrate: Int = 0
    ): File? {
        var encoder: MediaCodec? = null
        var mux: MediaMuxer? = null
        var durEstMs = 0L
        var audioExt: MediaExtractor? = null  // v14.08 音频直通：函数级声明，finally 统一释放
        // 导出线程降一级调度：不抢主线程 UI（治导出时手机卡/烫），又不至于明显拖慢编码
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_LESS_FAVORABLE) }
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
                // NV12(SemiPlanar) 优先：高通/联发科主流编码器最通用。
                // 仅当编码器【只】支持 planar 时才用 I420
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
                // argbToYuv420 below uses BT.601 limited-range coefficients.
                efmt.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT601_NTSC)
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

            // 原子替换：先写 .tmp，完整编码+flush+muxer finalize 且通过完整性验证后才 rename 到最终 outFile。
            // 任何中途失败都删 .tmp，绝不把残缺 MP4 当成成功文件留给用户。
            val tmpOut = File(outFile.parentFile ?: ctx.cacheDir, outFile.name + ".tmp")
            runCatching { tmpOut.delete() }
            mux = MediaMuxer(tmpOut.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val mmux = mux
            var muxStarted = false
            var muxTrack = -1
            val encInfo = MediaCodec.BufferInfo()

            // v14.08 音频直通：源音频 sample 原样写入输出（视频重新编码≠丢原声）。
            // 输出视频 PTS = prefixPts + 源帧 PTS；音频写 PTS = 源音频 PTS + prefixPts（与封面段对齐）。
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
            val audioBuf = ByteBuffer.allocateDirect(512 * 1024).order(ByteOrder.nativeOrder())
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

            // 音频直通写入（按视频 PTS 对齐，不超前缓冲；复用 audioBuf 零分配）
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
                        if (audioPts > upToUs + 200_000L) return   // 超前视频 >200ms 则暂停
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

            // 收尾模式（endOfStream=true）下必须完整收到 EOS：TRY_AGAIN 有界等待，防尾帧丢失截断。
            // 关键：收尾阶段 dequeue 超时用短值（50ms）而非 10s——否则 HEVC 编码器 drain 时
            // 每次 TRY_AGAIN 空等 10s，累计到 300 次≈50 分钟，表现为导出卡在 82% 不动。
            fun drainEncoder(endOfStream: Boolean): Boolean {
                // v14.08：每次 drain 先按当前视频 PTS 推进音频直通（音画同步）
                if (muxStarted && audioExt != null && audioMuxTrack >= 0 && !audioEos) drainAudio(videoPts)
                var idleTries = 0
                val drainStart = System.currentTimeMillis()
                val drainTimeout = if (endOfStream) 50_000L else 10_000L
                val maxIdle = if (endOfStream) 400 else Int.MAX_VALUE  // 收尾 400×50ms=20s 兜底
                while (true) {
                    // wall-clock 强制收尾：部分 HEVC 编码器 queue EOS 后持续输出帧却永不置
                    // BUFFER_FLAG_END_OF_STREAM，idleTries 不累积会死循环卡 82%。
                    // 15 秒 wall-clock 到点强制结束（宁可少尾帧也不出文件卡死）。
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

            // 入队一帧到编码器；输入缓冲满属正常等待：持续排水 + 阻塞等待，每帧 15s 上限。
            // 15s 无响应 = 该编码器实际死亡（configure 成功但入队即死）→ 抛 EncoderDeadException 换候选。
            fun feed(bmp: Bitmap, ptsUs: Long) {
                check(bmp.width == encW && bmp.height == encH) {
                    "水印帧尺寸 ${bmp.width}x${bmp.height} 与输出 ${encW}x${encH} 不一致，拒绝缩放帧"
                }
                val src = bmp
                val yuv = argbToYuv420(src, encW, encH, encPlanar)
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
                ib.clear(); ib.put(yuv)
                enc.queueInputBuffer(idx, 0, yuv.size, ptsUs.coerceAtLeast(0), 0)
            }

            // 前缀帧（图片封面）：先于视频帧编码 prefixDurationMs 毫秒，pts 从 0 连续
            val prefixTotal = if (prefixFrame != null)
                ((prefixDurationMs * fps + 999) / 1000).coerceAtLeast(1) else 0
            val prefixIntervalUs = 1_000_000L / fps
            prefixPts = 0L
            if (prefixFrame != null) {
                var prefixDone = 0
                while (prefixDone < prefixTotal && System.currentTimeMillis() < deadline) {
                    // v14.96 呼吸/流体帧时间：前缀帧按输出 PTS（微秒→秒），渲染层呼吸随其起伏
                    EditorState.exportTimeSec = prefixPts / 1_000_000f
                    val watermarked = onFrame(prefixFrame)
                    feed(watermarked, prefixPts)
                    if (watermarked !== prefixFrame && !reuseOutput) runCatching { watermarked.recycle() }
                    prefixPts += prefixIntervalUs
                    prefixDone++
                    drainEncoder(false)
                }
            }

            // 视频帧：唯一解码路径 = MotionDecoder.decodeFrames（预览同链：默认→枚举→Retriever 三层兜底）。
            // v14.11 诊断：逐帧路径确认玻璃参数确实进入渲染（首帧打印一次）
            DebugLog.log("RENDER", "逐帧导出参数快照: warp=${EditorState.params.warp} " +
                "edgeWarp=${EditorState.params.edgeWarp} innerWarp=${EditorState.params.innerWarp} " +
                "blur=${EditorState.params.blur} refractA=${EditorState.params.refractA} " +
                "chromatic=${EditorState.params.chromatic} opacity=${EditorState.params.opacity} " +
                "shadow=${EditorState.params.shadow} maxEdge=480")
            // 已按源元数据旋转；用户旋转由调用方 onFrame 叠加（与静态导出一致）。
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
            // 解码超时按源时长+帧数动态放大：软解高帧率大分辨率（如 60fps 2340x1080）约 100ms/帧，
            // 1980 帧需 2-4 分钟；旧公式 durEst×4+180s 只有 312s，软解候选在预算内跑不完 → 层层失败全灭。
            // 新公式：max(300s, 时长×6 + 预估帧数×150ms)，给软解留足余量；真正的防卡死由解码器层帧循环 guard 负责。
            val decodeTimeoutMs = (300_000L).coerceAtLeast(
                durEstMs * 6 + totalFramesEst.toLong() * 150L
            // v14.82 长视频：30min 封顶会截断长视频解码（视频半截）→ 放宽到 2h
            ).coerceAtMost(120 * 60_000L).toInt()
            // 视频帧 PTS 起点：前缀封面之后（无前缀时为 0）
            videoPts = prefixPts
            var lastUiPush = 0L
            val ok = MotionDecoder.decodeFrames(
                ctx, mp4, 8192, 240, decodeTimeoutMs, Int.MAX_VALUE,
                rotationOverride = rotation,
                srcUri = srcUri,
                // v15.10 动态照片 5 秒截断（逐帧路径）：解码层优雅停在 maxDurationMs（ok=true 不判失败）
                maxDurationMs = maxDurationMs,
                onFrame = { bmp, delayMs ->
                    videoPts = prefixPts + MotionDecoder.currentFrameTimeUs
                    // v14.96 呼吸/流体帧时间：视频播放 PTS（微秒→秒）——呼吸按真实播放节奏走，
                    // 与导出速度快慢无关（v14.80 曾用墙钟导致导出加速，这里统一用视频时间）
                    EditorState.exportTimeSec = videoPts / 1_000_000f
                    val watermarked = onFrame(bmp)
                try {
                    // PTS 严格跟随源帧真实间隔（delayMs 为相邻保留帧的 PTS 差），
                    // 不再用 fi*固定帧间隔——VFR/帧率元数据不准时也能保证输出时长==源时长，杜绝 4s/5s 波动
                    feed(watermarked, videoPts)
                } catch (e: EncoderDeadException) {
                    if (watermarked !== bmp) runCatching { bmp.recycle() }
                    if (!reuseOutput) runCatching { watermarked.recycle() }
                    throw e
                }
                // M6 复核结论：这一行并非死代码——推进到"下一帧预期 PTS"后，本回调内下方
                // decodedMs（进度）随即读取它，循环结束后 decodedEndMs（完整性自检）也读它。
                // 删除会改变进度推进与解码末帧范围判定，故保留并在此澄清。
                // Last-frame extent is used only for completeness/progress checks.
                videoPts += delayMs.coerceAtLeast(1) * 1000L
                if (watermarked !== bmp) runCatching { bmp.recycle() }
                // reuseOutput=true 时 watermarked 是调用方的逐帧复用缓冲，禁止回收（feed 已同步拷走 YUV）
                if (!reuseOutput) watermarked.recycle()
                fi++
                framesDone = fi
                // 进度按真实 PTS 推进（12→90，与 probe 后的 12 衔接不倒退），不按帧数：
                // 高帧率视频帧快速涌出时旧帧数公式会瞬间虚高，解码器一停就表现为"进度卡死"。
                // PTS 进度停滞位置真实，续解恢复后继续爬。节流 ~250ms 防 Compose 高频重组。
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
            // 解码必须跑到片源结尾：硬解停滞且所有 fallback（其他解码器/抽帧）都没跑完全片时，
            // 直接判失败——绝不进封装写出"只有前几秒"的残片（旧逻辑残段自洽通过完整性自检）
            if (!MotionDecoder.lastRunReachedEnd) {
                lastFailReason = "解码未完成：在 ${videoPts / 1000}ms 处停滞，系统解码器与抽帧均未跑完全片"
                return null
            }

            // 收尾：拿到一个输入缓冲发 EOS，再完整 drain（防尾帧丢失被截断）。
            // 注意：不能在 queue EOS 之前调 drainEncoder(true)——那时收不到 EOS 标志，只会空转
            // 300×10ms=3s 才超时返回（每段导出白等 3 秒的卡顿来源）。
            // 收尾用独立 30s 时钟：不能复用编码开始时的固定 120s deadline，否则长视频编码超 120s 后
            // 此处条件恒为 false → 拿不到 EOS 槽 → 误报"收尾超时"导致整段导出失败。
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
            // v14.08：视频编码收尾后排空剩余音频尾巴，再 stop muxer
            // v15.17：排空加上限（视频末帧 +200ms）。无上限排空会在视频轨提前结束时把整段
            // 剩余音频写进容器，容器时长被音频撑满 → 播放器放完视频帧后整段绿幕。
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
            // 完整性自检：读 .tmp 输出的真实 duration，与【实际解码喂入的最后一帧 PTS】比较。
            // 不能用 MediaMetadataRetriever 的源 duration（vivo 动态照片该字段虚高会误判）。
            // 输出比真实解码终点短 800ms 以上 = 真截断 → 删 .tmp 报错，绝不把残缺 MP4 当成功文件。
            val decodedEndMs = videoPts / 1000L
            var outDur = -1L
            // v15.17：原来只在 decodedEndMs > 1000 时才校验——"只解码到 0.5 秒"这种最严重的
            // 截断反而整段跳过校验直接放行。改为只要有源时长就必须校验。
            if (durEstMs > 1000L && tmpOut.exists()) {
                outDur = runCatching {
                    val r = android.media.MediaMetadataRetriever()
                    try { r.setDataSource(tmpOut.absolutePath)
                        r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: -1L
                    } finally { runCatching { r.release() } }
                }.getOrDefault(-1L)
                DebugLog.log("ENC", "时长自检 元数据=$durEstMs ms 解码终点=${decodedEndMs}ms 输出=${outDur}ms 帧=$framesDone")
                val coveragePct = (100.0 * decodedEndMs / durEstMs.coerceAtLeast(1)).toInt()
                // v15.17 修复（用户反馈"导出视频播一会儿变绿幕"）：
                // 旧校验只有 "outDur >= decodedEndMs - 800"，而 decodedEndMs 本身就是解码停下的
                // 位置——解码提前中断时这条恒成立；容器时长又被音频轨撑满（音频直通 + 尾部无上限
                // 排空）→ "视频轨只有开头几帧、音频全量"的坏文件被判成功，播放器放完开头就没有
                // 可解码帧，剩余整段绿幕。增加覆盖率校验：实际解码终点必须覆盖预期时长。
                val expectEndMs = if (maxDurationMs > 0) minOf(durEstMs, maxDurationMs) else durEstMs
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
            // v14.08 音频完整性验证：输入有音频而输出无音频 = EXPORT_AUDIO_LOST → 判定导出失败
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
            // 原子替换：验证通过才 rename .tmp → 最终 outFile
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

    /**
     * ARGB → YUV420。planar=true → I420（YY..UU..VV）；false → NV12（YY..UVUV）。
     * 布局与编码器实际支持的 COLOR_FormatYUV420Planar / SemiPlanar 对齐。
     * 性能：RGB→YUV 系数查表（每像素 8 次查表+加法，替代 ~15 次乘法），比逐像素浮点/整数乘算快 3-5 倍。
     */
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
    // C5 修复：Mp4Exporter 是 object（单例），原来的可变单例缓冲 pxBuf/yuvBuf 在
    // "用户点保存导出" 与 "adb 触发 ExportBench.measureCpu 后台导出" 并发时会互相踩踏 →
    // YUV 数据错帧 → 输出花屏。改用 ThreadLocal，每个导出线程独占一份，性能与原实现一致。
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

    private fun argbToYuv420(bmp: Bitmap, w: Int, h: Int, planar: Boolean): ByteArray {
        val n = w * h
        // withInitial 保证非空；!! 仅用于把平台类型收窄为非空 IntArray，消除可空告警。
        var px = pxBufLocal.get()!!
        if (px.size < n) { px = IntArray(n); pxBufLocal.set(px) }
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val ySize = n
        val uvSize = ySize / 4
        val need = ySize + uvSize * 2
        var out = yuvBufLocal.get()!!
        if (out.size < need) { out = ByteArray(need); yuvBufLocal.set(out) }
        var yi = 0; var ui = ySize; var vi = ySize + uvSize
        val rT = yR; val gT = yG; val bT = yB
        val uRT = uR; val uGT = uG; val uBT = uB
        val vRT = vR; val vGT = vG; val vBT = vB
        // v15.15 定点优化：把"是否偶数行"的判定从逐像素内层循环提到逐行外层（行判定每行算一次），
        // 奇数行走只算 Y 的紧凑循环（省掉每个像素一次 j%2 与 i%2 分支）；偶数行再按 planar 拆两种
        // 步进取样循环（把 if(planar) 从逐像素提到逐行）。纯循环改写，输出字节与旧实现逐字节一致。
        for (j in 0 until h) {
            val rowBase = j * w
            if (j and 1 != 0) {
                // 奇数行：只写 Y，不采样 UV
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

    /** 设备是否支持 HEVC/H.265 硬件编码（决定 UI 是否显示 HEVC 导出选项） */
    fun hasHevcEncoder(): Boolean = try {
        MediaCodecList(MediaCodecList.REGULAR_CODECS)
            .findEncoderForFormat(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, 2, 2)) != null
    } catch (_: Throwable) { false }
}
