package com.liquidglass.watermark

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Build
import java.io.File
import java.nio.ByteBuffer

/**
 * 把动态照片尾部抽出的 MP4 用系统硬件解码器逐帧解码为 Bitmap 序列。
 * 仅使用 API26 全版本可用的 MediaExtractor/MediaCodec，输出统一的 YUV_420_888 → ARGB（纯手写，
 * 不依赖 RenderScript / 不依赖高版本 API）。全程防御：任何异常都返回 null，由调用方回退为静态照片，
 * 绝不闪退。
 */
object MotionDecoder {

    /** 最近一次解码失败的诊断信息（供 UI 提示具体原因，避免"点了没反应"） */
    @Volatile var lastFailReason: String = ""

    /**
     * 流式拷贝 Uri 到文件（10GB 级大视频专用）：不整读内存，分块写盘。
     * @return 拷贝是否成功（目标文件非空）
     */
    internal fun copyUriToFile(ctx: Context, uri: android.net.Uri, dest: File): Boolean {
        return try {
            // 超时兜底：按 60s + 预估大小（每 MB 0.5s）给上限，慢 provider/网盘 U 盘不再无限挂死
            val estLen = runCatching {
                ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L
            }.getOrDefault(0L)
            val copyDeadline = System.currentTimeMillis() +
                (60_000L + estLen.coerceAtLeast(0L) / (1024L * 1024L) * 500L).coerceAtMost(600_000L)
            ctx.contentResolver.openInputStream(uri)?.use { ins ->
                dest.outputStream().use { outs ->
                    val buf = ByteArray(1 shl 20)
                    var n: Int
                    while (ins.read(buf).also { n = it } >= 0) {
                        if (externalCancel) return false
                        outs.write(buf, 0, n)
                        if (n == 0) Thread.sleep(5)
                        if (System.currentTimeMillis() > copyDeadline) {
                            lastFailReason = "视频读取超时"
                            return false
                        }
                    }
                }
                true
            } ?: false
        } catch (t: Throwable) {
            if (t is ExternalCancelException) throw t
            lastFailReason = "视频流式读取失败：${t.message}"
            false
        }
    }

    /** Normalize supported metadata rotation without inferring it from aspect ratio. */
    @Suppress("UNUSED_PARAMETER")
    fun normalizeVideoRotation(rot: Int, w: Int, h: Int): Int =
        (((rot % 360) + 360) % 360).takeIf { it % 90 == 0 } ?: 0

    /** rotation 非 0 时旋转 bitmap 并回收原图；为 0 原样返回（三处解码路径共用，消除重复） */
    private fun rotateIfNeeded(src: Bitmap, rotation: Int): Bitmap {
        if (rotation == 0) return src
        val m = Matrix().apply { postRotate(rotation.toFloat()) }
        val rb = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        if (rb !== src) runCatching { src.recycle() }
        return rb
    }

    /**
     * 动态照片配对时，以封面方向为准推断视频应强制应用的旋转角（MotionPhoto/MP4 导出的 rotationOverride）。
     * @param coverPortrait 封面是否竖屏
     * @param vidRot 视频容器元数据原始 rotation（未归一化，mmr/MediaFormat 读出）
     * @param vw 视频帧像素宽（元数据/首帧）
     * @param vh 视频帧像素高
     * @return 应强制的旋转角（90/270/0），-1 表示交解码器自动探测
     *
     * 三种竖屏来源分别处理，修复"竖拍导入/相册显横屏"：
     *  - 视频像素本身已竖(h>w)，仅 moov 残留 90 标记（vivo 冗余）：不强制，交自动探测清冗余，再转反而横；
     *  - 横像素(w>h)+有效 90/270 元数据（标准竖拍/倒持）：尊重元数据，倒持用 270 而非一律 90；
     *  - 横像素(w>h)+无 rotation（vivo iQOO 动照 MP4）：封面竖则强制 90。
     */
    fun inferForceRotation(coverPortrait: Boolean, vidRot: Int, vw: Int, vh: Int): Int {
        if (!coverPortrait) return -1
        val r = ((vidRot % 360) + 360) % 360
        return when {
            vh > vw -> -1          // 像素已竖：自动探测（normalize 会清掉冗余 90）
            r % 180 != 0 -> r      // 横像素 + 有效旋转：尊重 90/270
            else -> 90             // 横像素无旋转（vivo）：强制竖
        }
    }

    class Frames(
        val frames: List<Bitmap>,
        /** 每帧停留毫秒，长度与 frames 相同 */
        val delaysMs: IntArray,
        val srcW: Int,
        val srcH: Int,
        val rotation: Int
    )

    /**
     * @param maxEdge 输出帧最长边（预览小、导出大），按步长最近邻降采样以省内存
     * @param targetFps 目标帧率上限（导出传 240=跟随源，实际不超过源帧率；尽量保留每一帧）
     * @param maxFrames 最多保留帧数
     * @param timeoutMs 解码总耗时上限（默认 8 秒；全分辨率导出传大值，防 ROM 卡死但不当截断）
     * @param minFrames 最少返回帧数，不足判失败（默认 2 帧才算动图；探测尺寸时可传 1）
     */
    fun decode(
        ctx: Context,
        mp4: ByteArray,
        maxEdge: Int,
        targetFps: Int = 30,
        maxFrames: Int = 120,
        timeoutMs: Int = 8000,
        minFrames: Int = 2,
        rotationOverride: Int = -1,
        srcUri: android.net.Uri? = null,
        maxDurationMs: Long = 0L
    ): Frames? {
        abortFlag = false  // 重置上次导出的中止标记，避免跨次误判"解码失败"
        return try {
            decodeWithFallback(ctx, mp4, maxEdge, targetFps, maxFrames, timeoutMs, minFrames, null, rotationOverride, srcUri, maxDurationMs)
        } catch (e: ExternalCancelException) { null }
    }

    /**
     * 流式逐帧解码（导出 GIF 用）：每帧经 onFrame(bitmap, delayMs) 同步消费后立即回收，
     * 内存峰值只含单帧，彻底规避全分辨率大图多帧 OOM。至少消费到 1 帧才算成功。
     */
    fun decodeFrames(
        ctx: Context,
        mp4: ByteArray,
        maxEdge: Int,
        targetFps: Int,
        timeoutMs: Int,
        maxFrames: Int = Int.MAX_VALUE,
        onFrame: (Bitmap, Int) -> Boolean,
        rotationOverride: Int = -1,
        srcUri: android.net.Uri? = null,
        maxDurationMs: Long = 0L
    ): Boolean {
        abortFlag = false
        var consumed = 0
        var aborted = false
        var pending: Bitmap? = null
        var pendingTimeUs = 0L
        var pendingDelayMs = 1000 / targetFps.coerceIn(1, 240)
        var pendingEndUs = 0L
        fun emitPending(nextTimeUs: Long? = null): Boolean {
            val frame = pending ?: return true
            pending = null
            val endTimeUs = nextTimeUs ?: pendingEndUs.takeIf { it > pendingTimeUs }
                ?: (pendingTimeUs + pendingDelayMs * 1000L)
            val delay = FrameTiming.delayMs(pendingTimeUs, endTimeUs)
            frameClockUs.set(pendingTimeUs)
            return try {
                val keep = onFrame(frame, delay)
                if (keep) consumed++ else { aborted = true; abortFlag = true }
                keep
            } finally { if (!frame.isRecycled) frame.recycle() }
        }
        val ok = try {
            val decoded = decodeWithFallback(
                ctx, mp4, maxEdge, targetFps, maxFrames, timeoutMs, 1,
                { bmp, d ->
                    val sourceTime = currentFrameTimeUs
                    val sourceEnd = frameEndUs.get()
                    if (!emitPending(sourceTime)) {
                        if (!bmp.isRecycled) bmp.recycle()
                        false
                    } else {
                        // Retain one decoded frame until its successor provides its
                        // actual display interval. No bitmap copy is necessary.
                        pending = bmp
                        pendingTimeUs = sourceTime
                        pendingDelayMs = d
                        pendingEndUs = sourceEnd
                        true
                    }
                }, rotationOverride, srcUri, maxDurationMs
            ) != null
            decoded && emitPending()
        } catch (e: ExternalCancelException) { false }
        finally {
            pending?.let { if (!it.isRecycled) it.recycle() }
            pending = null
        }
        if (aborted) {
            if (lastFailReason.isBlank()) lastFailReason = "帧处理中止"
            return false
        }
        return ok && consumed > 0
    }

    private fun decodeWithFallback(
        ctx: Context, mp4: ByteArray, maxEdge: Int, targetFps: Int, maxFrames: Int,
        timeoutMs: Int, minFrames: Int, onFrame: ((Bitmap, Int) -> Boolean)?,
        rotationOverride: Int = -1, srcUri: android.net.Uri? = null,
        maxDurationMs: Long = 0L
    ): Frames? {
        // 总超时预算：所有解码层共享，避免多解码器失败时层层 8 秒把用户等死
        lastRunReachedEnd = false
        externalCancel = false
        abortFlag = false
        val tAll = System.currentTimeMillis()
        val deadline = System.currentTimeMillis() + timeoutMs
        fun remaining(): Int =
            (deadline - System.currentTimeMillis()).coerceIn(1L, timeoutMs.toLong()).toInt()
        // 流式（onFrame 有副作用）：层失败后下层从 attempt.resumeUs seek 续解，帧不重复；
        // 缓冲模式：每层从头解，失败层的局部帧直接丢弃
        val attempt = DecodeAttempt(maxDurationMs.coerceAtLeast(0L) * 1000L)

        // 第 1 层：系统默认解码器（createDecoderByType，硬件优先）
        val r0 = System.currentTimeMillis()
        val r1 = decodeImpl(ctx, mp4, maxEdge, targetFps, maxFrames, remaining(), minFrames, null, onFrame, rotationOverride, srcUri, attempt)
        DebugLog.log("DEC", "解码层1 默认硬解 ${if (r1 != null) "成功" else "失败"} 耗时=${System.currentTimeMillis() - r0}ms")
        if (r1 != null) { lastFailReason = ""; lastRunReachedEnd = true; return r1 }
        if (aborted()) return null

        // Surface 已实证失败：不跳过枚举。每个候选 decodeImpl 会因 attempt.surfaceFailed 自动走
        // buffer 模式（见 decodeImpl useSurface 判定），该设备硬解 Surface/buffer 均 0 帧时，
        // 枚举可命中软解（c2.android）buffer 完整解码。
        // ⚠️ 旧逻辑"跳过枚举直接 retriever"已被日志推翻：v13.78 实测该设备 retriever 抽 1991 帧
        // 27.7s 只抽到 16 帧（≈1.7s/帧，多实例 seek 大文件慢），比 buffer 逐帧更慢且抽不完长视频；
        // MediaMetadataRetriever 只允许兜底封面/少量采样/小规模片段，禁止承担长视频完整导出。
        // 第 2 层起：枚举系统全部视频解码器逐个尝试（厂商硬件解码器 / c2 / OMX 软件解码器全覆盖）。
        // 枚举全部视频解码器，但只尝试【支持源 MIME】的 decoder（attempt.srcMime 由第 1 层
        // decodeImpl 探测写入）：video/avc 源只允许明确支持 video/avc 的 decoder，
        // 禁止把 AV1/H263/HEVC/MPEG4/VP8/VP9/DIVX/VC1/MJPEG 等不匹配 MIME 的 decoder 强行
        // configure（浪费 + 污染失败链）。srcMime 未知时保守不过滤（放行全部，configure 自过滤）。
        val wantMime = attempt.srcMime
        val tried = HashSet<String>()
        // 统一自动匹配引擎枚举（开源 c2.android/OMX.google + 厂商硬解全覆盖，仅放行支持源 MIME 的
        // decoder；已按开源软解优先排序——默认硬解僵死往往是该码流的厂商硬解通病，软解稳定必能跑完）
        val sortedDecoders = CodecSelector.videoDecoderCandidates(wantMime)
        var triedN = 0
        for (name in sortedDecoders) {
            if (name in tried) continue
            tried.add(name); triedN++
            if (System.currentTimeMillis() >= deadline) break
            val r = decodeImpl(ctx, mp4, maxEdge, targetFps, maxFrames, remaining(), minFrames, name, onFrame, rotationOverride, srcUri, attempt)
            if (r != null) { lastFailReason = ""; lastRunReachedEnd = true; return r }
            if (aborted()) return null
        }
        lastFailReason = if (triedN == 0) "默认解码器失败" else "已尝试 $triedN 个解码器均失败"

        // 末层兜底：MediaMetadataRetriever 抽帧（API28+）。不依赖 MediaCodec 输出格式，
        // 兼容性最强（vivo/部分国产 ROM 的 YUV 输出格式问题导致所有 MediaCodec 层失败时仍可用）
        if (System.currentTimeMillis() >= deadline) return null
        val r3 = decodeByRetriever(ctx, mp4, maxEdge, targetFps, maxFrames, remaining(), minFrames, onFrame, srcUri, rotationOverride, attempt)
        DebugLog.log("DEC", "解码层末级 retriever ${if (r3 != null) "成功" else "失败"} 累计耗时=${System.currentTimeMillis() - tAll}ms")
        if (r3 != null) { lastFailReason = ""; lastRunReachedEnd = true; return r3 }
        if (aborted()) return null
        lastFailReason += "，系统抽帧也失败"
        return null
    }

    /** 回调中止标记：onFrame 返回 false 时置位（导出侧"编码器输入阻塞"等），各解码层检查后立即终止 */
    private fun aborted(): Boolean {
        val a = abortFlag
        return a
    }
    @Volatile private var abortFlag = false

    /** 外部取消（导出页"取消导出"按钮）：由 MainActivity.requestCancelExport 置位。
     *  与 abortFlag 的区别：abortFlag 只在 onFrame 返回 false 后置位，而 Surface 0 帧空转 /
     *  retriever worker seek 期间 onFrame 不被调用，旧实现取消无响应。此标志在所有循环 + worker 处检查 */
    @Volatile private var externalCancel = false
    /** 外部取消请求：立即中止全部解码层（含并行 retriever worker） */
    fun requestExternalCancel() {
        externalCancel = true
        abortFlag = true
    }
    private class ExternalCancelException : RuntimeException("导出已取消")
    private fun checkExternalCancel() {
        if (externalCancel) throw ExternalCancelException()
    }

    /** 最近一次解码是否跑到片源结尾（收到 output EOS / 抽帧覆盖全片）；导出完整性校验用，false=残段 */
    @Volatile var lastRunReachedEnd: Boolean = false

    /** 一次 fallback 内各层共享的续解状态：上层已成功消费到 resumeUs，下层 seek 续解，帧不重复回调 */
    // Callback-only source clock, isolated from concurrent preview/export threads.
    private val frameClockUs = ThreadLocal.withInitial { 0L }
    private val frameEndUs = ThreadLocal.withInitial { 0L }
    val currentFrameTimeUs: Long get() = frameClockUs.get()

    private class DecodeAttempt(val maxDurationUs: Long = 0L) {
        @Volatile var resumeUs: Long = -1L
        @Volatile var sourceOriginUs: Long = 0L
        /** 源真实帧率（MediaExtractor 轨道 KEY_FRAME_RATE，比 mmr CAPTURE_FRAMERATE 可信）：
         *  vivo ROM 的 CAPTURE_FRAMERATE 对 60fps 源误报 240，导致 retriever 按 240fps 抽 7962 帧
         *  → 永远抽不完（10-16 分钟白等）。decodeImpl 读到轨道 fps 时写入，retriever 优先取用 */
        @Volatile var srcFps: Int = 0
        /** 源视频轨道 MIME（decodeImpl 探测后写入）：解码器枚举只尝试支持该 MIME 的 decoder，
         *  禁止把 AV1/H263/HEVC/VP8/VP9 等不匹配源 MIME 的 decoder 强行配置（configure 失败浪费
         *  时间并污染失败链） */
        @Volatile var srcMime: String? = null
        /** Surface+ImageReader 模式在本 fallback 内已实证失败（前几帧取不到 Image）：
         *  后续候选一律走 buffer 模式，避免每个候选都在 Surface 上白跑一遍（v13.57 实证：
         *  vivo 硬解 Surface 输出 5.4s 解完全片却 0 帧取到，kept=0 outputEos=true 假成功） */
        @Volatile var surfaceFailed: Boolean = false
    }

    // ---- 性能：线程局部 ARGB 缓冲复用（预览线程与导出线程并发各自持有，杜绝每帧 2MB 分配与 GC）----
    private val threadArgb = ThreadLocal.withInitial { IntArray(0) }
    private val threadYuv = ThreadLocal.withInitial { arrayOf(ByteArray(0), ByteArray(0)) }
    private fun reuseYuvPlane(index: Int, size: Int): ByteArray {
        val planes = threadYuv.get()
        if (planes[index].size < size) planes[index] = ByteArray(size)
        return planes[index]
    }
    private fun reuseArgb(need: Int): IntArray {
        val tl = threadArgb.get()
        if (tl.size < need) { val a = IntArray(need); threadArgb.set(a); return a }
        return tl
    }

    private class RetFrame(val seq: Int, val bmp: android.graphics.Bitmap?, val delayMs: Int)

    /**
     * MediaMetadataRetriever 抽帧：按源时长+帧率估算逐帧抽取（getScaledFrameAtTime，API28+）。
     * 帧率优先取 capture framerate 元数据，缺失时按 targetFps。兜底路径，兼容性优先。
     */
    private fun decodeByRetriever(
        ctx: Context, mp4: ByteArray, maxEdge: Int, targetFps: Int, maxFrames: Int,
        timeoutMs: Int, minFrames: Int, onFrame: ((Bitmap, Int) -> Boolean)?,
        srcUri: android.net.Uri? = null, rotationOverride: Int = -1,
        attempt: DecodeAttempt = DecodeAttempt()
    ): Frames? {
        var tmp: File? = null
        var mmr: MediaMetadataRetriever? = null
        // 提到 try 外：异常退出时 catch 回收已抽帧，避免 native Bitmap 泄漏
        val out = ArrayList<Bitmap>()
        try {
            mmr = MediaMetadataRetriever()
            if (srcUri != null && srcUri.scheme.equals("file", ignoreCase = true)) {
                // 本地文件直读，不复制（导出层统一落盘的文件）
                val f = File(srcUri.path ?: srcUri.toString())
                if (!f.exists()) return null
                mmr.setDataSource(f.absolutePath)
            } else if (srcUri != null) {
                // Uri 直读 → FileDescriptor 流式兜底（大视频不整拷）
                var ok = runCatching { mmr.setDataSource(ctx, srcUri) }.isSuccess
                if (!ok) {
                    ok = runCatching {
                        ctx.contentResolver.openFileDescriptor(srcUri, "r")?.use { pfd ->
                            mmr.setDataSource(pfd.fileDescriptor)
                        } != null
                    }.getOrDefault(false)
                }
                if (!ok) {
                    // 大视频（>128MB）失败时不整拷，直接放弃该兜底层
                    val len = runCatching {
                        ctx.contentResolver.openAssetFileDescriptor(srcUri, "r")?.use { it.length } ?: 0L
                    }.getOrDefault(0L)
                    if (len > 128L * 1024 * 1024) return null
                    val t = File(ctx.cacheDir, "lgw_ret_${System.currentTimeMillis()}.mp4")
                    tmp = t
                    runCatching { copyUriToFile(ctx, srcUri, t) }
                    if (t.length() > 0) mmr.setDataSource(t.absolutePath)
                }
            } else {
                val t = File(ctx.cacheDir, "lgw_ret_${System.currentTimeMillis()}.mp4")
                tmp = t
                t.writeBytes(mp4)
                mmr.setDataSource(t.absolutePath)
            }
            val sourceDurationUs = runCatching {
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLong()?.times(1000L)
            }.getOrNull() ?: 0L
            val durUs = if (attempt.maxDurationUs > 0L)
                minOf(sourceDurationUs, attempt.maxDurationUs) else sourceDurationUs
            if (durUs <= 0) return null
            val rotation0 = runCatching {
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toInt() ?: 0
            }.getOrDefault(0)
            val w = runCatching {
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: 0
            }.getOrDefault(0)
            val h = runCatching {
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: 0
            }.getOrDefault(0)
            if (w <= 0 || h <= 0) return null
            // rotation 归一化延迟到首帧，用帧实际像素尺寸判定（mmr metadata 宽高在部分 ROM 是旋转后尺寸，
            // 与 getScaledFrameAtTime 输出原始像素不一致 → 误清零导致帧不旋转横置）。
            // 调用方显式传入（导出层动态照片强制/已探测权威值）时直接使用，不做首帧重判
            var rotation = if (rotationOverride >= 0) rotationOverride else rotation0
            var rotationResolved = rotationOverride >= 0
            // fps 来源优先级（vivo ROM 坑）：MediaExtractor 轨道帧率（decodeImpl 已写入 attempt.srcFps，
            // 60fps 源实测=60）> mmr CAPTURE_FRAMERATE（vivo 对 60fps 源误报 240）> targetFps。
            // 旧代码直接信 mmr 的 240 → nFrames=7962 → retriever 逐帧 seek 抽不完 → 白等 10-16 分钟。
            var fps = attempt.srcFps.takeIf { it in 1..240 }
                ?: runCatching {
                    mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toInt()
                }.getOrNull()?.takeIf { it in 1..60 } // vivo 误报 240 直接排除
                ?: targetFps.coerceIn(1, 240)
            fps = minOf(fps, targetFps.coerceIn(1, 240))
            val intervalUs = 1_000_000L / fps
            val nFrames = ((durUs + intervalUs - 1) / intervalUs).toInt().coerceIn(1, maxFrames)
            // 续解：前层已消费到 resumeUs，从其后第一个抽帧位开始，不重复回调
            val resumeFromUs = if (onFrame != null && attempt.resumeUs >= 0L)
                attempt.resumeUs - attempt.sourceOriginUs else -1L
            val startI = if (resumeFromUs > 0L)
                ((resumeFromUs + intervalUs) / intervalUs).toInt().coerceAtLeast(0) else 0
            DebugLog.log("DEC", "兜底retriever durUs=$durUs fps=$fps nFrames=$nFrames startI=$startI w=$w h=$h rotation=$rotation")
            val s = maxEdge.toFloat() / maxOf(w, h).toFloat()
            val dw = (w * minOf(1f, s)).toInt().coerceAtLeast(1)
            val dh = (h * minOf(1f, s)).toInt().coerceAtLeast(1)
            val delayMs = (intervalUs / 1000L).toInt().coerceIn(1, 60_000)
            // 并行抽帧（性能核心）：2 个独立 MediaMetadataRetriever 实例分片并行 seek+解码（实例间隔离，
            // 各自线程安全），主线程按帧序号串行消费（onFrame/编码必须保序）。1980 帧 2340x1080
            // 从串行 ~4 分钟降到 ~40-60s。抽帧失败帧以 null sentinel 占位，consumer 跳过保持序号连续。
            // ⚠️ 并行度 6→2 根因修复：实测部分机型（vivo 高通）6 路独立 retriever 同时 seek 同一文件
            // 触发 IO/句柄竞争 → 每帧 5s+ 龟速（日志：69 帧 22s 只抽 3 帧，动图合成失败）。
            // 2 路既保留并行收益又避免竞争；预算公式按 625ms/帧 实测同步放宽。
            // PriorityBlockingQueue 无界 → put 前手动轮询限流（<=16 帧 ≈ 160MB 峰值，防 OOM）。
            val pq = java.util.concurrent.PriorityBlockingQueue<RetFrame>(
                16, Comparator.comparingInt { it.seq })
            val workers = 2
            val remainFrames = (nFrames - startI).coerceAtLeast(1)
            val chunk = (remainFrames + workers - 1) / workers
            // 抽帧预算：vivo 实测全尺寸 getScaledFrameAtTime 串行 625ms/帧（不是 120ms），
            // 2 路并行后 ≈ 312ms/帧；预算 = 帧数×625/2 + 90s 余量，下限 240s，上限 900s
            val decodeBudgetMs = (remainFrames * 625L / workers + 90_000L)
                .coerceAtLeast(240_000L).coerceAtMost(900_000L)
            val deadline = System.currentTimeMillis() + decodeBudgetMs
            val doneCount = java.util.concurrent.atomic.AtomicInteger(0)
            val abortP = java.util.concurrent.atomic.AtomicBoolean(false)
            // worker 数据源：tmp 已落盘用文件路径（最可靠）；否则按调用方式 setDataSource
            val srcFile = tmp?.absolutePath
            val srcUriForWorker = if (srcFile == null) srcUri else null
            for (t in 0 until workers) {
                val wStart = startI + t * chunk
                val wEnd = minOf(nFrames, wStart + chunk)
                if (wStart >= wEnd) { doneCount.incrementAndGet(); continue }
                Thread {
                    var mmrW: android.media.MediaMetadataRetriever? = null
                    try {
                        mmrW = android.media.MediaMetadataRetriever()
                        if (srcFile != null) mmrW.setDataSource(srcFile)
                        else if (srcUriForWorker != null) mmrW.setDataSource(ctx, srcUriForWorker)
                        else return@Thread
                        for (i in wStart until wEnd) {
                            if (abortP.get() || externalCancel ||
                                System.currentTimeMillis() > deadline) break
                            var bmp: android.graphics.Bitmap? = null
                            // 抽帧失败重试 3 次：getScaledFrameAtTime 偶发失败（大文件/内存压力）若直接
                            // 占位跳过会缺帧（输出少于源帧数=残片），必须重试到成功或预算耗尽
                            for (retry in 0..2) {
                                if (abortP.get() || externalCancel ||
                                    System.currentTimeMillis() > deadline) break
                                runCatching {
                                    bmp = mmrW!!.getScaledFrameAtTime(i * intervalUs,
                                        MediaMetadataRetriever.OPTION_CLOSEST, dw, dh)
                                }
                                if (bmp != null) break
                            }
                            while (pq.size >= 16 && !abortP.get() && !externalCancel &&
                                System.currentTimeMillis() < deadline) {
                                try { Thread.sleep(10) } catch (_: InterruptedException) { break }
                            }
                            if (abortP.get() || externalCancel) break
                            pq.put(RetFrame(i, bmp, delayMs))
                        }
                    } catch (_: Throwable) {
                    } finally {
                        runCatching { mmrW?.release() }
                        doneCount.incrementAndGet()
                    }
                }.apply { isDaemon = true }.start()
            }
            var got = 0
            var lastGotUs = resumeFromUs
            var nextSeq = startI
            while (nextSeq < nFrames && !abortP.get() && !externalCancel &&
                System.currentTimeMillis() < deadline) {
                if (got >= maxFrames) { abortP.set(true); break }
                val f = pq.poll(300, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (f == null) {
                    if (doneCount.get() >= workers && pq.isEmpty()) break
                    continue
                }
                if (f.seq != nextSeq) { pq.put(f); continue } // 防御：按 seq 排序 poll 不应乱序
                nextSeq++
                if (f.bmp == null) continue // 该帧抽帧失败，占位跳过
                val rawBmp: Bitmap = f.bmp
                // 首帧：用帧实际像素尺寸 normalize + 自检（consumer 单线程处理，安全）
                if (!rotationResolved) {
                    rotation = normalizeVideoRotation(rotation0, rawBmp.width, rawBmp.height)
                    rotationResolved = true
                }
                // MediaMetadataRetriever already applies container rotation.
                val bmp = rotateIfNeeded(rawBmp, ((rotation - rotation0) % 360 + 360) % 360)
                if (onFrame != null) {
                    frameEndUs.set(durUs)
                    frameClockUs.set(f.seq * intervalUs)
                    val boundedDelay = minOf(delayMs.toLong(), ((durUs - f.seq * intervalUs + 999L) / 1000L).coerceAtLeast(1L)).toInt()
                    if (!onFrame(bmp, boundedDelay)) {
                        runCatching { bmp.recycle() }
                        abortP.set(true)
                        break
                    }
                    // decodeFrames owns the retained frame until the next callback.
                } else {
                    out.add(bmp)
                }
                got++
                lastGotUs = f.seq * intervalUs
            }
            // 正常完成但队列仍有尾帧（worker 结束前的在途帧）：继续消费直到空洞/空/超时
            while (!abortP.get() && !externalCancel && nextSeq < nFrames &&
                System.currentTimeMillis() < deadline) {
                if (got >= maxFrames) break
                val f = pq.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS) ?: break
                if (f.seq != nextSeq) { pq.put(f); break }
                nextSeq++
                if (f.bmp == null) continue
                val rawBmp: Bitmap = f.bmp
                if (!rotationResolved) {
                    rotation = normalizeVideoRotation(rotation0, rawBmp.width, rawBmp.height)
                    rotationResolved = true
                }
                // MediaMetadataRetriever already applies container rotation.
                val bmp = rotateIfNeeded(rawBmp, ((rotation - rotation0) % 360 + 360) % 360)
                if (onFrame != null) {
                    frameEndUs.set(durUs)
                    frameClockUs.set(f.seq * intervalUs)
                    val boundedDelay = minOf(delayMs.toLong(), ((durUs - f.seq * intervalUs + 999L) / 1000L).coerceAtLeast(1L)).toInt()
                    if (!onFrame(bmp, boundedDelay)) {
                        runCatching { bmp.recycle() }
                        abortP.set(true)
                        break
                    }
                    // decodeFrames owns the retained frame until the next callback.
                } else {
                    out.add(bmp)
                }
                got++
                lastGotUs = f.seq * intervalUs
            }
            // 必须覆盖到片源末尾（固定容差 2 帧：抽帧逐点进行、预算按帧数给足，应全覆盖；
            // 不按百分比容差，否则长视频末尾缺数秒仍会判成功）
            val covered = startI + got
            val enough = covered >= nFrames - 2
            DebugLog.log("DEC", "兜底retriever 实际抽 got=$got 帧 覆盖=$covered/$nFrames（并行${workers}路）")
            if (!enough) {
                DebugLog.log("DEC", "兜底retriever 失败：覆盖 $covered/$nFrames（抽帧预算耗尽或抽帧大量失败），resume=${lastGotUs / 1000}ms")
                out.forEach { runCatching { it.recycle() } }
                attempt.resumeUs = lastGotUs
                return null
            }
            if (onFrame != null) return Frames(emptyList(), IntArray(0), 0, 0, rotation)
            if (got < minFrames.coerceAtLeast(1)) {
                out.forEach { runCatching { it.recycle() } }
                return null
            }
            return Frames(out, IntArray(got) { i ->
                minOf(delayMs.toLong(), ((durUs - i * intervalUs + 999L) / 1000L).coerceAtLeast(1L)).toInt()
            }, w, h, rotation)
        } catch (t: Throwable) {
            // 外部取消：回收帧后重抛（不能吞，否则 MainActivity 收不到取消信号、被当失败）
            out.forEach { b -> runCatching { b.recycle() } }
            if (t is ExternalCancelException) throw t
            return null
        } finally {
            runCatching { mmr?.release() }
            runCatching { tmp?.delete() }
        }
    }

    private fun decodeImpl(
        ctx: Context,
        mp4: ByteArray,
        maxEdge: Int,
        targetFps: Int,
        maxFrames: Int,
        timeoutMs: Int,
        minFrames: Int,
        codecName: String?,
        onFrame: ((Bitmap, Int) -> Boolean)?,
        rotationOverride: Int = -1,
        srcUri: android.net.Uri? = null,
        attempt: DecodeAttempt = DecodeAttempt()
    ): Frames? {
        var tmp: File? = null
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        var imageReader: android.media.ImageReader? = null
        // Surface 取帧：OnImageAvailableListener 回调入队（业界标准可靠模式）。
        // 旧模式 releaseOutputBuffer(true) 后同步 acquireNextImage 在部分 ROM（vivo 高通）上因渲染
        // 异步时序竞态导致 img 恒 null 或 acquire 抛异常被外层 runCatching 静默吞掉 → kept=0 无日志。
        // 回调模式：渲染完成时在独立 HandlerThread 入队，主线程 poll 等待，消除时序竞态。
        var imageHandlerThread: android.os.HandlerThread? = null
        var imageHandler: android.os.Handler? = null
        val surfaceFrameQueue = java.util.concurrent.LinkedBlockingQueue<android.media.Image>(8)
        // 提到 try 外：异常退出时 catch 必须能回收已解码帧，否则 native Bitmap 泄漏
        val out = ArrayList<Bitmap>()
        val pts = ArrayList<Long>()
        try {
            // 关键修复（30s→11.4s 截断根因）：content:// 的 MediaExtractor 流式 fd 在部分 ROM/Provider
            // 上只能顺序读到文件前一段（moov 能读出 duration=33s，但 mdat 顺序流在 11.4s 被截断→假 EOF）。
            // 因此只要拿到 srcUri，就先把完整文件复制到 cacheDir 本地，再用本地路径喂 extractor——
            // 本地文件支持随机访问，moov+mdat 完整读取，杜绝流式截断。复制成本一次性，换完整性。
            extractor = MediaExtractor()
            if (srcUri != null && srcUri.scheme.equals("file", ignoreCase = true)) {
                // 已是本地文件（导出层统一落盘后传入）：直接读，绝不重复复制（旧逻辑每个 fallback
                // 候选都复制一遍源文件，是"卡 10%"的 I/O 根因）。tmp 保持 null，不删调用方文件
                val f = File(srcUri.path ?: srcUri.toString())
                if (!f.exists()) return null
                extractor.setDataSource(f.absolutePath)
            } else if (srcUri != null) {
                val t = File(ctx.cacheDir, "lgw_src_${System.currentTimeMillis()}.mp4")
                tmp = t
                val copied = runCatching { copyUriToFile(ctx, srcUri, t); t.length() > 0 }.getOrDefault(false)
                if (!copied || t.length() <= 0) {
                    // 复制失败回退流式（极少数超大文件），但记录日志便于排查
                    DebugLog.log("DEC", "srcUri 落盘失败，回退流式（可能截断）")
                    runCatching { t.delete() }; tmp = null
                    runCatching { extractor.setDataSource(ctx, srcUri, null) }
                } else {
                    DebugLog.log("DEC", "srcUri 落盘 ${t.length()/1024}KB 本地解码")
                    extractor.setDataSource(t.absolutePath)
                }
            } else {
                val t = File(ctx.cacheDir, "lgw_motion_${System.currentTimeMillis()}.mp4")
                tmp = t
                t.writeBytes(mp4)
                extractor.setDataSource(t.absolutePath)
            }
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) { track = i; format = f; break }
            }
            if (track < 0 || format == null) return null
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            attempt.srcMime = mime  // 供解码器枚举按源 MIME 过滤（见 decodeWithFallback）
            // 解码开始：打印所用解码器 + 轨道参数 + 源文件大小（验证落盘完整、定位"全失败"是读取还是解码问题）
            runCatching {
                val fw = format.getInteger(MediaFormat.KEY_WIDTH)
                val fh = format.getInteger(MediaFormat.KEY_HEIGHT)
                val ffps = runCatching { format.getInteger(MediaFormat.KEY_FRAME_RATE) }.getOrDefault(0)
                val fdur = runCatching { format.getLong(MediaFormat.KEY_DURATION) }.getOrDefault(0L)
                val fprof = runCatching { format.getInteger(MediaFormat.KEY_PROFILE) }.getOrDefault(-1)
                val fstd = runCatching { format.getInteger(MediaFormat.KEY_COLOR_STANDARD) }.getOrDefault(-1)
                val ftr = runCatching { format.getInteger(MediaFormat.KEY_COLOR_TRANSFER) }.getOrDefault(-1)
                val srcBytes = tmp?.length()
                    ?: if (srcUri != null && srcUri.scheme.equals("file", true))
                        runCatching { File(srcUri.path ?: "").length() }.getOrDefault(-1L) else -1L
                // profile：HEVC Main10=2（10-bit，c2.android 软解不支持）；color-standard 7=BT2020/HDR
                DebugLog.log("DEC", "解码开始 codec=${codecName ?: "默认"} mime=$mime ${fw}x$fh fps=$ffps dur=${fdur / 1000}ms profile=$fprof std=$fstd tr=$ftr 文件=${srcBytes / 1024}KB")
            }
            // rotation 探测：KEY_ROTATION（API31+）→ mmr 读 moov/tkhd 兜底
            // 注意：format.width/height 在部分 ROM 是旋转后尺寸，与 MediaCodec 输出原始像素不一致，
            // 因此 normalize 延迟到首帧解码后用帧实际像素尺寸判定（见帧循环首帧处理）
            var rawRotation = if (rotationOverride >= 0) rotationOverride else {
                runCatching { format.getInteger(MediaFormat.KEY_ROTATION) }.getOrDefault(0)
            }
            if (rawRotation == 0 && rotationOverride < 0) {
                rawRotation = runCatching {
                    val mmr = MediaMetadataRetriever()
                    try {
                        if (srcUri != null) mmr.setDataSource(ctx, srcUri)
                        else mmr.setDataSource(tmp!!.absolutePath)
                        // mime 对照：mmr 报 video/mp4 是容器级 mime（vivo 正常行为，非解析错误）；
                        // 仅当 mmr 报的是编码级 mime 且与 extractor 不同才报警（真解析差异）
                        val mmrMime = runCatching {
                            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                        }.getOrNull()
                        if (mmrMime != null && mmrMime != "video/mp4" && mmrMime != mime) {
                            DebugLog.log("DEC", "mime 不一致 extractor=$mime mmr=$mmrMime（容器解析差异）")
                        }
                        mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toInt() ?: 0
                    } finally { runCatching { mmr.release() } }
                }.getOrDefault(0)
            }
            // 调用方显式传入（导出层已探测权威值）直接用，不再首帧重判
            var rotation = rawRotation
            var rotationResolved = rotationOverride >= 0

            extractor.selectTrack(track)
            attempt.sourceOriginUs = extractor.sampleTime.coerceAtLeast(0L)
            // Apply rotation to pixels ourselves, identically for ImageReader and YUV.
            format.setInteger(MediaFormat.KEY_ROTATION, 0)
            // 续解：前层已消费到 resumeUs，seek 到其前一个 sync frame 起解，输出按 PTS 去重
            val resumeFromUs = if (onFrame != null) attempt.resumeUs else -1L
            if (resumeFromUs > 0L) extractor.seekTo(resumeFromUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            codec = CodecSelector.createDecoder(mime, codecName)
            if (codec == null) return null
            // ── Surface + ImageReader 模式（性能核心）：解码器直接硬件/驱动合成 RGBA_8888 输出，
            // 免 Java 逐像素 YUV→RGB 转换（2340x1080 每帧约 1.5s → <20ms）。仅当无需缩放
            // （maxEdge >= 源尺寸）时启用；configure(surface) 失败自动回退 buffer 模式（兼容兜底）。
            var useSurface = false
            val codedW = runCatching { format.getInteger(MediaFormat.KEY_WIDTH) }.getOrDefault(0)
            val codedH = runCatching { format.getInteger(MediaFormat.KEY_HEIGHT) }.getOrDefault(0)
            if (codedW in 16..8192 && codedH in 16..8192 && maxEdge >= maxOf(codedW, codedH) &&
                !attempt.surfaceFailed) {
                runCatching {
                    val r = android.media.ImageReader.newInstance(
                        codedW, codedH, android.graphics.PixelFormat.RGBA_8888, 5)
                    codec?.configure(format, r.surface, null, 0)
                    imageReader = r
                    useSurface = true
                    // OnImageAvailableListener 回调入队：渲染完成时异步入队，主线程 poll 消费。
                    // 独立 HandlerThread 避免阻塞解码线程；队列满时丢弃并 close 防泄漏。
                    imageHandlerThread = android.os.HandlerThread("lgw-surface").apply { start() }
                    imageHandler = android.os.Handler(imageHandlerThread!!.looper)
                    r.setOnImageAvailableListener({ reader ->
                        val img = runCatching { reader.acquireNextImage() }.getOrNull()
                        if (img != null && !surfaceFrameQueue.offer(img)) {
                            runCatching { img.close() }
                        }
                    }, imageHandler)
                }.onFailure {
                    runCatching { imageReader?.close() }; imageReader = null
                    runCatching { imageHandlerThread?.quitSafely() }; imageHandlerThread = null; imageHandler = null
                    // configure(surface) 失败：释放后重建，回退 buffer 模式
                    runCatching { codec?.release() }
                    codec = CodecSelector.createDecoder(mime, codecName)
                }
            }
            DebugLog.log("DEC", "解码器 ${codecName ?: "默认"} Surface=$useSurface coded=${codedW}x${codedH} maxEdge=$maxEdge surfaceFailed=${attempt.surfaceFailed} onFrame=${onFrame != null}")
            if (!useSurface) {
                // 统一请求 flexible YUV，保证 getOutputImage / 手动取数一致（buffer 模式专用）
                runCatching {
                    format.setInteger(
                        MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                    )
                }
                if (codec == null) return null
                codec?.configure(format, null, null, 0)
            }
            codec?.start()
            // 局部不可变引用：codec 是 var（configure 回退可能重建），被闭包捕获后 smart cast 失效，
            // 循环内统一用 c（val）规避（Kotlin 编译要求）
            val c = codec ?: return null

            // 读取源真实帧率，导出目标帧率不超过源帧率（避免无意义重复帧），上限 240 跟随源
            val srcFps = runCatching { format.getInteger(MediaFormat.KEY_FRAME_RATE) }.getOrDefault(0)
            if (srcFps in 1..240) attempt.srcFps = srcFps // 供 retriever 兜底层取真实帧率（不信 mmr CAPTURE_FRAMERATE）
            val effFps = minOf(targetFps.coerceIn(1, 240), if (srcFps in 1..240) srcFps else targetFps.coerceIn(1, 240))
            val frameIntervalUs = 1_000_000L / effFps
            val info = MediaCodec.BufferInfo()
            var inputEos = false
            var outputEos = false
            var stalled = false
            var lastKeptUs = Long.MIN_VALUE
            var kept = 0
            var nextSampleUs = attempt.sourceOriginUs
            var guard = 0
            var lastOutputMs = System.currentTimeMillis()
            // Surface 模式取帧失败计数：连续 N 帧取不到 Image → 判定该实例 Surface 输出不可用
            // （v13.57 实证 vivo 硬解 Surface 5.4s 解完全片却 kept=0），全局回退 buffer 模式
            var surfaceNullFrames = 0
            // 停滞检测滑动基线：最近 64 帧输出间隔均值（wall clock）。软解 2340x1080 60fps 每帧
            // 300-600ms 是正常解码速度，固定 3s 阈值会误杀（日志实证 c2.android.avc 解 3 帧后被误判僵死）。
            var gapSum = 0L; var gapN = 0
            val deadline = System.currentTimeMillis() + timeoutMs // 防个别 ROM 卡死；超长导出请传大 timeoutMs

            // 循环上限：仅作死循环兜底（5m 次，覆盖超长视频），时长由调用方 timeoutMs 控制，不截正常视频
            // 轮询超时 100ms（旧 10s）：停滞时不再每次空等 10 秒，自适应阈值判死交 fallback 续解
            // Surface 模式首帧失败立即中断：不再空转解完全片（旧版 11.8s 全片 0 帧 → 用户看到卡 12%）。
            // attempt.surfaceFailed 在首帧 acquire 超时/Image 处理异常时置位，置位后立刻交 retriever 兜底
            while (!outputEos && !stalled && !externalCancel &&
                !(useSurface && attempt.surfaceFailed) &&
                System.currentTimeMillis() < deadline && guard++ < 5_000_000) {
                checkExternalCancel()
                if (!inputEos) {
                    val inIndex = c.dequeueInputBuffer(100)
                    if (inIndex >= 0) {
                        val ib: ByteBuffer? = c.getInputBuffer(inIndex)
                        if (ib == null) continue
                        ib.clear()
                        val sampleSize = extractor.readSampleData(ib, 0)
                        if (sampleSize < 0) {
                            c.queueInputBuffer(
                                inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputEos = true
                        } else {
                            val ptsUs = extractor.sampleTime
                            c.queueInputBuffer(inIndex, 0, sampleSize, ptsUs, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = c.dequeueOutputBuffer(info, 100)
                when {
                    outIndex >= 0 -> {
                        val outWall = System.currentTimeMillis()
                        if (lastOutputMs > 0L && gapN < 64) {
                            val g = outWall - lastOutputMs
                            if (g > 0 && g < 5_000L) { gapSum += g; gapN++ } // 过滤冷启动/异常长间隔
                        }
                        lastOutputMs = outWall // 任何输出都算解码器活性（含去重丢弃帧）
                        val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        val relativeUs = (info.presentationTimeUs - attempt.sourceOriginUs).coerceAtLeast(0L)
                        val reachedLimit = attempt.maxDurationUs > 0L && relativeUs >= attempt.maxDurationUs
                        if (reachedLimit) {
                            c.releaseOutputBuffer(outIndex, false)
                            outputEos = true
                            continue
                        }
                        // MP4 retains every source frame. Bounded GIF extraction samples
                        // an absolute time grid, avoiding 60->20fps rounding artifacts.
                        val retainAll = onFrame != null && attempt.maxDurationUs <= 0L
                        val keepThis = info.presentationTimeUs > resumeFromUs &&
                            (!eos || info.size > 0) &&
                            (retainAll || info.presentationTimeUs + 500L >= nextSampleUs)
                        if ((useSurface || info.size > 0) && keepThis) {
                            val bmp = runCatching {
                                if (useSurface) {
                                    // Surface 模式：releaseOutputBuffer(true) 渲染到 ImageReader → 取 RGBA 帧。
                                    // 免 Java 逐像素 YUV 转换（性能核心）。release(true) 渲染异步：
                                    // acquireNextImage 可能暂时 null，等待重试（最多 ~100ms）防误丢帧。
                                    // 首帧任何失败立即 surfaceFailed 回退 buffer（不等 5 帧、不空耗全片时间），
                                    // 并记录精确原因（此前 copyPixels 异常被外层 runCatching 吞掉且不计数，
                                    // 导致"kept=0 却无失败日志"的诊断盲区）。
                                    c.releaseOutputBuffer(outIndex, true)
                                    // OnImageAvailableListener 回调入队后从队列取：消除 release→acquire
                                    // 时序竞态（vivo 高通上同步 acquire 恒 null/抛异常被静默吞的根因）。
                                    var img = runCatching { surfaceFrameQueue.poll(150, java.util.concurrent.TimeUnit.MILLISECONDS) }.getOrNull()
                                    var tries = 0
                                    while (img == null && tries < 6) {
                                        img = runCatching { surfaceFrameQueue.poll(50, java.util.concurrent.TimeUnit.MILLISECONDS) }.getOrNull()
                                        tries++
                                    }
                                    if (img == null) {
                                        if (attempt.surfaceFailed.not()) {
                                            DebugLog.log("DEC", "Surface 首帧 acquire 失败（重试${tries}次仍 null），本层回退 buffer")
                                            attempt.surfaceFailed = true
                                        }
                                        surfaceNullFrames++
                                        return@runCatching null
                                    }
                                    try {
                                        val iw = img.width; val ih = img.height
                                        val plane = img.planes[0]
                                        val rs = plane.rowStride
                                        val buf = plane.buffer
                                        val cap = iw * ih * 4
                                        if (cap <= 0 || iw <= 0 || ih <= 0) {
                                            if (attempt.surfaceFailed.not()) {
                                                DebugLog.log("DEC", "Surface Image 尺寸异常 iw=$iw ih=$ih，本层回退 buffer")
                                                attempt.surfaceFailed = true
                                            }
                                            surfaceNullFrames++
                                            return@runCatching null
                                        }
                                        val bmp0: Bitmap? = if (rs == iw * 4) {
                                            if (buf.capacity() < cap) {
                                                if (attempt.surfaceFailed.not()) {
                                                    DebugLog.log("DEC", "Surface buffer 容量不足 cap=$cap bufCap=${buf.capacity()}，本层回退 buffer")
                                                    attempt.surfaceFailed = true
                                                }
                                                null
                                            } else {
                                                buf.position(0); buf.limit(cap)
                                                val bb = Bitmap.createBitmap(iw, ih, Bitmap.Config.ARGB_8888)
                                                val copyOk = runCatching { bb.copyPixelsFromBuffer(buf) }.isSuccess
                                                if (!copyOk) {
                                                    if (attempt.surfaceFailed.not()) {
                                                        DebugLog.log("DEC", "Surface copyPixels 异常 cap=$cap bufRem=${runCatching { buf.remaining() }.getOrDefault(-1)}，本层回退 buffer")
                                                        attempt.surfaceFailed = true
                                                    }
                                                    runCatching { bb.recycle() }
                                                    null
                                                } else bb
                                            }
                                        } else {
                                            // 罕见 rowStride 对齐：逐行拼接连续缓冲再拷贝
                                            val contig = java.nio.ByteBuffer.allocate(cap)
                                                .order(java.nio.ByteOrder.nativeOrder())
                                            var ok = true
                                            for (y in 0 until ih) {
                                                if (y * rs + iw * 4 > buf.capacity()) { ok = false; break }
                                                buf.position(y * rs); buf.limit(y * rs + iw * 4)
                                                contig.put(buf)
                                            }
                                            if (!ok) {
                                                if (attempt.surfaceFailed.not()) {
                                                    DebugLog.log("DEC", "Surface rowStride 对齐拼接失败 rs=$rs iw=$iw ih=$ih bufCap=${buf.capacity()}，本层回退 buffer")
                                                    attempt.surfaceFailed = true
                                                }
                                                null
                                            } else {
                                                contig.position(0)
                                                Bitmap.createBitmap(iw, ih, Bitmap.Config.ARGB_8888).also {
                                                    it.copyPixelsFromBuffer(contig)
                                                }
                                            }
                                        }
                                        if (bmp0 != null) {
                                            if (!rotationResolved) {
                                                rotation = normalizeVideoRotation(rawRotation, bmp0.width, bmp0.height)
                                                rotationResolved = true
                                            }
                                            rotateIfNeeded(bmp0, rotation)
                                        } else {
                                            surfaceNullFrames++
                                            null
                                        }
                                    } catch (ie: Throwable) {
                                        // plane.buffer 访问 / copyPixels 之外的任何 Image 处理异常
                                        // （PRIVATE 数据空间 buffer 不可 CPU 读等）：记录精确异常类型
                                        if (attempt.surfaceFailed.not()) {
                                            DebugLog.log("DEC", "Surface Image 处理异常 ${ie.javaClass.simpleName}: ${ie.message}，本层回退 buffer")
                                            attempt.surfaceFailed = true
                                        }
                                        surfaceNullFrames++
                                        null
                                    } finally { runCatching { img.close() } }
                                } else {
                                    var fb: Bitmap? = null
                                    // buffer 模式路径选择（鲁棒顺序）：先试 Image，非 null 走 Image 转换；
                                    // 为 null（buffer 模式解码器、未配 Surface，占绝大多数）自动回退 ByteBuffer。
                                    // 【不要按 outputFormat colorFormat 判断】：configure 强制 Flexible 时
                                    // outputFormat 也报 Flexible，但 buffer 模式 getOutputImage 恒 null——误导向
                                    // Image 路径 = 每帧转换 null = 0 帧导出（v13.53 回归实证）。
                                    val img = runCatching { codec?.getOutputImage(outIndex) }.getOrNull()
                                if (img != null) {
                                    try {
                                        // 首帧：先不旋转拿原始像素尺寸，用帧实际尺寸 normalize + 自检
                                        if (!rotationResolved) {
                                            val raw = yuvImageToScaledBitmap(img, maxEdge, 0, codec?.outputFormat ?: format)
                                            if (raw != null) {
                                                rotation = normalizeVideoRotation(rawRotation, raw.width, raw.height)
                                                rotationResolved = true
                                                fb = rotateIfNeeded(raw, rotation)
                                            }
                                        } else {
                                            fb = yuvImageToScaledBitmap(img, maxEdge, rotation, codec?.outputFormat ?: format)
                                        }
                                    } finally {
                                        runCatching { img.close() }
                                    }
                                } else {
                                    val fmt = runCatching { codec?.outputFormat }.getOrNull()
                                    val b = runCatching { codec?.getOutputBuffer(outIndex) }.getOrNull()
                                    if (fmt != null && b != null) {
                                        if (!rotationResolved) {
                                            val raw = yuvBufferToScaledBitmap(b, fmt, maxEdge, 0)
                                            if (raw != null) {
                                                rotation = normalizeVideoRotation(rawRotation, raw.width, raw.height)
                                                rotationResolved = true
                                                fb = rotateIfNeeded(raw, rotation)
                                            }
                                        } else {
                                            fb = yuvBufferToScaledBitmap(b, fmt, maxEdge, rotation)
                                        }
                                    }
                                }
                                fb
                                }
                            }.onFailure { e ->
                                // 最外层异常兜底：releaseOutputBuffer / poll / Image 处理任何异常都在此可见
                                // （此前被 runCatching 静默吞 → kept=0 却无任何日志的诊断盲区）
                                if (useSurface && attempt.surfaceFailed.not()) {
                                    DebugLog.log("DEC", "Surface 取帧外层异常 ${e.javaClass.simpleName}: ${e.message}，本层回退")
                                    attempt.surfaceFailed = true
                                }
                            }.getOrNull()
                            if (bmp != null) {
                                if (useSurface) surfaceNullFrames = 0 // 取帧成功：重置失败计数
                                nextSampleUs = attempt.sourceOriginUs +
                                    ((relativeUs + 500L) / frameIntervalUs + 1L) * frameIntervalUs
                                if (onFrame != null) {
                                    // 流式：回调携带真实帧延迟（首帧按源帧率近似），消费完立即回收。
                                    // 间隔不人为截断（旧 16..500ms 会让 VFR/低帧率视频累计缩短时长）；
                                    // MP4 用它累加真实 PTS 保证输出时长==源，GIF 侧另有 cs 范围 clamp。
                                    val delayMs = if (lastKeptUs == Long.MIN_VALUE)
                                        if (resumeFromUs > 0L)
                                            ((info.presentationTimeUs - resumeFromUs) / 1000L)
                                                .toInt().coerceIn(1, 60_000)
                                        else (1000 / effFps.coerceAtLeast(1)).coerceAtLeast(1)
                                    else ((info.presentationTimeUs - lastKeptUs) / 1000L)
                                        .toInt().coerceIn(1, 60_000)
                                    val sourceEndUs = runCatching {
                                        format.getLong(MediaFormat.KEY_DURATION)
                                    }.getOrDefault(0L)
                                    frameEndUs.set(if (attempt.maxDurationUs > 0L)
                                        if (sourceEndUs > 0L) minOf(sourceEndUs, attempt.maxDurationUs)
                                        else attempt.maxDurationUs
                                    else sourceEndUs)
                                    frameClockUs.set(relativeUs)
                                    val boundedDelay = if (attempt.maxDurationUs > 0L)
                                        minOf(delayMs.toLong(), ((attempt.maxDurationUs - relativeUs + 999L) / 1000L).coerceAtLeast(1L)).toInt()
                                    else delayMs
                                    if (!onFrame(bmp, boundedDelay)) {
                                        runCatching { bmp.recycle() }
                                        abortFlag = true
                                        if (!useSurface) codec?.releaseOutputBuffer(outIndex, false)
                                        return null
                                    }
                                    // Ownership passes to decodeFrames (one-frame lookahead).
                                    kept++
                                } else {
                                    out.add(bmp); pts.add(info.presentationTimeUs)
                                }
                                lastKeptUs = info.presentationTimeUs
                            }
                        }
                        // Skipped Surface frames must also release their codec buffer.
                        if (!useSurface || !keepThis) codec?.releaseOutputBuffer(outIndex, false)
                        if (eos) outputEos = true
                        // 流式与缓冲模式统一受 maxFrames 上限约束（防极端长视频无界导出）
                        if ((onFrame == null && out.size >= maxFrames) ||
                            (onFrame != null && kept >= maxFrames)) outputEos = true
                    }
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // 停滞检测（自适应阈值）：轮询 100ms；无输出时长 > max(5s, 最近输出间隔均值×30)
                        // = 解码器僵死（高通硬解在特定帧后只 TRY_AGAIN 的症状，日志实证 PTS 5-14s 随机僵死），
                        // 判本层失败交 fallback 续解；不空转到总超时（进度条卡死的根因）。
                        // 下限 5s 而非旧 3s：慢软解（300-600ms/帧）的正常解码间隙不被误杀（旧 3s 误杀实证）。
                        val silent = System.currentTimeMillis() - lastOutputMs
                        val avgGap = if (gapN > 0) gapSum / gapN else 0L
                        val stallLimit = maxOf(5_000L, avgGap * 30)
                        if (silent > stallLimit) {
                            stalled = true
                            DebugLog.log("DEC", "解码停滞 ${silent}ms 无输出（阈值 ${stallLimit}ms avgGap=${avgGap}ms）kept=$kept lastPts=${if (lastKeptUs == Long.MIN_VALUE) -1 else lastKeptUs}us inputEos=$inputEos")
                        }
                    }
                    else -> { /* FORMAT_CHANGED 等忽略，输出尺寸以 Image 为准 */ }
                }
            }
            // 流式模式：只有正常跑到 EOS（含 maxFrames 上限截断）才算成功；
            // 停滞/超时的残段写续解点后返回 null，交下一层 seek 续解，绝不把残段当成功
            if (onFrame != null) {
                DebugLog.log("DEC", "硬解结束 kept=$kept 帧 最后PTS=${lastKeptUs}us (${lastKeptUs/1000}ms) outputEos=$outputEos")
                if (outputEos && kept == 0) {
                    // 0 帧"假成功"：解码器 EOS 正常到达但一帧未采纳（vivo 硬解 Surface/buffer
                    // 输出不稳定的实测症状 kept=0 outputEos=true），绝不能当作成功——交 fallback 续解，
                    // 由 retriever 兜底抽完全片（fps 已按轨道真实值修复）
                    DebugLog.log("DEC", "kept=0 outputEos=true：0 帧无效，判失败交兜底（防假成功）")
                    return null
                }
                if (outputEos) return Frames(emptyList(), IntArray(0), 0, 0, rotation)
                // An attempt that delivered no frames must not mark PTS=0 consumed.
                if (lastKeptUs != Long.MIN_VALUE) attempt.resumeUs = lastKeptUs
                return null
            }
            // 缓冲模式：未跑到 EOS 且未达 maxFrames = 残段（stall/超时），判失败交 fallback
            if (!outputEos && out.size < maxFrames) {
                DebugLog.log("DEC", "硬解残段 out=${out.size} 未到EOS，判失败")
                out.forEach { runCatching { it.recycle() } }
                return null
            }
            if (out.size < minFrames.coerceAtLeast(1)) { out.forEach { runCatching { it.recycle() } }; return null }
            // 超过上限时均匀抽稀
            val picked = if (out.size <= maxFrames) out.indices.toList()
            else IntArray(maxFrames) { (it.toLong() * out.size / maxFrames).toInt() }.toList()
            val frames = picked.map { out[it] }
            val pickedPts = picked.map { pts[it] }
            val delays = IntArray(frames.size)
            for (i in frames.indices) {
                val d = when {
                    i + 1 < pickedPts.size -> ((pickedPts[i + 1] - pickedPts[i]) / 1000L).toInt()
                    i > 0 -> ((pickedPts[i] - pickedPts[i - 1]) / 1000L).toInt()
                    else -> 1000 / effFps.coerceAtLeast(1)
                }
                val remainingMs = if (attempt.maxDurationUs > 0L)
                    ((attempt.maxDurationUs - (pickedPts[i] - attempt.sourceOriginUs) + 999L) / 1000L).coerceAtLeast(1L)
                    else 60_000L
                delays[i] = minOf(d.coerceAtLeast(1).toLong(), remainingMs).toInt()
            }
            // 释放未被选中的帧
            picked.toHashSet().let { keep ->
                out.forEachIndexed { idx, b -> if (idx !in keep) runCatching { b.recycle() } }
            }
            val first = frames[0]
            return Frames(frames, delays, first.width, first.height, rotation)
        } catch (t: Throwable) {
            // 外部取消：回收帧后重抛（不是解码失败，不写失败日志）
            out.forEach { b -> runCatching { b.recycle() } }
            if (t is ExternalCancelException) throw t
            // 记录确切失败原因（codec 创建/configure/start/转换各阶段异常统一在此可见）
            DebugLog.log("DEC", "解码异常 codec=${codecName ?: "默认"}: ${t.javaClass.simpleName}: ${t.message}")
            // 异常退出：回收已解码但无法交付的帧，避免 native Bitmap 泄漏（finally 只释放 codec/extractor）
            return null
        } finally {
            runCatching { imageHandlerThread?.quitSafely() }
            surfaceFrameQueue.forEach { runCatching { it.close() } }
            surfaceFrameQueue.clear()
            runCatching { imageReader?.close() }
            runCatching { codec?.stop() }; runCatching { codec?.release() }
            runCatching { extractor?.release() }; runCatching { tmp?.delete() }
        }
    }

    /**
     * YUV_420_888 Image 按步长最近邻降采样并转 ARGB Bitmap，正确处理 rowStride / pixelStride 与旋转。
     * internal：导出 MP4/GIF 时按全分辨率（maxEdge=8192）复用此转换。
     */
    private fun yuvConverter(format: MediaFormat?, w: Int, h: Int): YuvColorConverter {
        val standard = runCatching { format?.getInteger(MediaFormat.KEY_COLOR_STANDARD) }
            .getOrNull()?.takeIf { it > 0 }
            ?: if (w >= 1280 || h >= 720) MediaFormat.COLOR_STANDARD_BT709
            else MediaFormat.COLOR_STANDARD_BT601_NTSC
        val full = runCatching { format?.getInteger(MediaFormat.KEY_COLOR_RANGE) }
            .getOrNull() == MediaFormat.COLOR_RANGE_FULL
        return YuvColorConverter(standard, full)
    }

    internal fun yuvImageToScaledBitmap(
        image: android.media.Image, maxEdge: Int, rotation: Int, format: MediaFormat? = null
    ): Bitmap? {
        val cropW = image.cropRect.width()
        val cropH = image.cropRect.height()
        if (cropW <= 0 || cropH <= 0) return null
        // cropRect 常带 padding（left/top 非 0），采样必须加上偏移，否则画面错位撕裂
        val cropX = image.cropRect.left
        val cropY = image.cropRect.top
        val baseEdge = maxOf(cropW, cropH)
        // 整数步长（向上取整保证覆盖全帧）：导出全分辨率时 step=1 零缩放损失
        val step = maxOf(1, (baseEdge + maxEdge - 1) / maxEdge)
        val ow = (cropW + step - 1) / step
        val oh = (cropH + step - 1) / step

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuf = yPlane.buffer; val uBuf = uPlane.buffer; val vBuf = vPlane.buffer
        val yRow = yPlane.rowStride
        val uvRow = uPlane.rowStride
        val uvPix = uPlane.pixelStride.coerceAtLeast(1)
        val yPix = yPlane.pixelStride.coerceAtLeast(1)
        val vRow = vPlane.rowStride
        val vPix = vPlane.pixelStride.coerceAtLeast(1)

        val converter = yuvConverter(format, cropW, cropH)
        val argb = reuseArgb(ow * oh)
        var oi = 0
        var sy = 0
        while (sy < cropH) {
            var sx = 0
            val yRowBase = (cropY + sy) * yRow
            val uvY = ((cropY + sy) / 2) * uvRow
            val vRowBase = ((cropY + sy) / 2) * vRow
            while (sx < cropW) {
                val y = yBuf.get(yRowBase + (cropX + sx) * yPix).toInt() and 255
                val uvIdx = uvY + ((cropX + sx) / 2) * uvPix
                val u = uBuf.get(uvIdx).toInt() and 255
                val v = vBuf.get(vRowBase + ((cropX + sx) / 2) * vPix).toInt() and 255
                argb[oi++] = converter.argb(y, u, v)
                sx += step
            }
            sy += step
        }
        var bmp = Bitmap.createBitmap(argb, ow, oh, Bitmap.Config.ARGB_8888)
        if (rotation != 0) {
            val m = Matrix().apply { postRotate(rotation.toFloat()) }
            val rb = Bitmap.createBitmap(bmp, 0, 0, ow, oh, m, true)
            if (rb !== bmp) bmp.recycle()
            bmp = rb
        }
        return bmp
    }

    /**
     * getOutputImage 不可用时的兜底：直接从输出 ByteBuffer 手动解析 YUV420。
     * 覆盖 Planar(I420) / SemiPlanar(NV12) / 高通 QCOM 变体，stride 与 crop 从 outputFormat 读取。
     * 国产 ROM 部分解码器输出非 flexible 格式导致 getOutputImage 返回 null 的场景靠这层出图。
     */
    internal fun yuvBufferToScaledBitmap(
        buf: java.nio.ByteBuffer, fmt: MediaFormat, maxEdge: Int, rotation: Int
    ): Bitmap? {
        val cf = runCatching { fmt.getInteger(MediaFormat.KEY_COLOR_FORMAT) }.getOrDefault(-1)
        val semi = cf == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar ||
            cf == 22 ||
            cf == 2135033992 /* COLOR_FormatYUV420Flexible（Image 不可用时按 NV12 布局试解） */
        val planar = cf == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
        if (!semi && !planar) return null

        val cropL = runCatching { fmt.getInteger("crop-left") }.getOrDefault(0)
        val cropT = runCatching { fmt.getInteger("crop-top") }.getOrDefault(0)
        val cropR = runCatching {
            fmt.getInteger("crop-right")
        }.getOrDefault(runCatching { fmt.getInteger(MediaFormat.KEY_WIDTH) }.getOrDefault(0) - 1)
        val cropB = runCatching {
            fmt.getInteger("crop-bottom")
        }.getOrDefault(runCatching { fmt.getInteger(MediaFormat.KEY_HEIGHT) }.getOrDefault(0) - 1)
        val w = cropR - cropL + 1
        val h = cropB - cropT + 1
        if (w <= 0 || h <= 0) return null

        var stride = runCatching { fmt.getInteger(MediaFormat.KEY_STRIDE) }.getOrDefault(w)
        var sliceH = runCatching { fmt.getInteger(MediaFormat.KEY_SLICE_HEIGHT) }.getOrDefault(h)
        if (stride < w) stride = w
        if (sliceH < h) sliceH = h

        val ySize = stride * sliceH
        val chromaStride = if (semi) stride else (stride + 1) / 2
        val chromaHeight = (sliceH + 1) / 2
        val uvHalf = chromaStride * chromaHeight
        val uvSize = if (semi) uvHalf else uvHalf * 2
        val bytes = buf.duplicate().apply { position(0) }
        if (bytes.remaining() < ySize + uvSize) return null
        val yArr = reuseYuvPlane(0, ySize)
        bytes.get(yArr, 0, ySize)
        val uvArr = reuseYuvPlane(1, uvSize)
        bytes.get(uvArr, 0, uvSize)

        val baseEdge = maxOf(w, h)
        // 整数步长（向上取整保证覆盖全帧）：导出全分辨率时 step=1 零缩放损失
        val step = maxOf(1, (baseEdge + maxEdge - 1) / maxEdge)
        val ow = (w + step - 1) / step
        val oh = (h + step - 1) / step
        val converter = yuvConverter(fmt, w, h)
        val argb = reuseArgb(ow * oh)
        var oi = 0
        var sy = 0
        while (sy < h) {
            var sx = 0
            val yRowBase = (cropT + sy) * stride + cropL
            val uvRowBase = ((cropT + sy) / 2) * chromaStride
            while (sx < w) {
                val y = yArr[yRowBase + sx].toInt() and 255
                val u: Int; val v: Int
                if (semi) {
                    val uv = uvRowBase + ((cropL + sx) / 2) * 2
                    u = uvArr[uv].toInt() and 255
                    v = uvArr[uv + 1].toInt() and 255
                } else {
                    val uh = uvRowBase + (cropL + sx) / 2
                    u = uvArr[uh].toInt() and 255
                    v = uvArr[uvHalf + uh].toInt() and 255
                }
                argb[oi++] = converter.argb(y, u, v)
                sx += step
            }
            sy += step
        }
        var bmp = Bitmap.createBitmap(argb, ow, oh, Bitmap.Config.ARGB_8888)
        if (rotation != 0) {
            val m = Matrix().apply { postRotate(rotation.toFloat()) }
            val rb = Bitmap.createBitmap(bmp, 0, 0, ow, oh, m, true)
            if (rb !== bmp) bmp.recycle()
            bmp = rb
        }
        return bmp
    }
}
