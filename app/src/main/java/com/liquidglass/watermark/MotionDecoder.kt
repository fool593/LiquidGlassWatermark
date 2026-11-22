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

object MotionDecoder {

    @Volatile var lastFailReason: String = ""

    internal fun copyUriToFile(ctx: Context, uri: android.net.Uri, dest: File): Boolean {
        return try {

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

    private fun rotateIfNeeded(src: Bitmap, rotation: Int): Bitmap {
        if (rotation == 0) return src
        val m = Matrix().apply { postRotate(rotation.toFloat()) }
        val rb = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        if (rb !== src) runCatching { src.recycle() }
        return rb
    }

    fun inferForceRotation(coverPortrait: Boolean, vidRot: Int, vw: Int, vh: Int): Int {
        if (!coverPortrait) return -1
        val r = ((vidRot % 360) + 360) % 360
        return when {
            vh > vw -> -1
            r % 180 != 0 -> r
            else -> 90
        }
    }

    class Frames(
        val frames: List<Bitmap>,

        val delaysMs: IntArray,
        val srcW: Int,
        val srcH: Int,
        val rotation: Int
    )

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
        maxDurationMs: Long = 0L,

        startPtsUs: Long = 0L
    ): Frames? {
        abortFlag = false
        return try {
            decodeWithFallback(ctx, mp4, maxEdge, targetFps, maxFrames, timeoutMs, minFrames, null, rotationOverride, srcUri, maxDurationMs, startPtsUs)
        } catch (e: ExternalCancelException) { null }
    }

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
        maxDurationMs: Long = 0L,

        startPtsUs: Long = 0L
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

            if (pendingTimeUs < startPtsUs.coerceAtLeast(0L)) {
                if (!frame.isRecycled) frame.recycle()
                return true
            }
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
                }, rotationOverride, srcUri, maxDurationMs, startPtsUs
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
        maxDurationMs: Long = 0L,
        startPtsUs: Long = 0L
    ): Frames? {

        lastRunReachedEnd = false
        externalCancel = false
        abortFlag = false
        val tAll = System.currentTimeMillis()
        val deadline = System.currentTimeMillis() + timeoutMs
        fun remaining(): Int =
            (deadline - System.currentTimeMillis()).coerceIn(1L, timeoutMs.toLong()).toInt()

        val attempt = DecodeAttempt(maxDurationMs.coerceAtLeast(0L) * 1000L).also {
            it.startUs = startPtsUs.coerceAtLeast(0L)
        }

        val r0 = System.currentTimeMillis()
        val r1 = decodeImpl(ctx, mp4, maxEdge, targetFps, maxFrames, remaining(), minFrames, null, onFrame, rotationOverride, srcUri, attempt)
        DebugLog.log("DEC", "解码层1 默认硬解 ${if (r1 != null) "成功" else "失败"} 耗时=${System.currentTimeMillis() - r0}ms")
        if (r1 != null) { lastFailReason = ""; lastRunReachedEnd = true; return r1 }
        if (aborted()) return null

        val wantMime = attempt.srcMime
        val tried = HashSet<String>()

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

        if (System.currentTimeMillis() >= deadline) return null
        val r3 = decodeByRetriever(ctx, mp4, maxEdge, targetFps, maxFrames, remaining(), minFrames, onFrame, srcUri, rotationOverride, attempt)
        DebugLog.log("DEC", "解码层末级 retriever ${if (r3 != null) "成功" else "失败"} 累计耗时=${System.currentTimeMillis() - tAll}ms")
        if (r3 != null) { lastFailReason = ""; lastRunReachedEnd = true; return r3 }
        if (aborted()) return null
        lastFailReason += "，系统抽帧也失败"
        return null
    }

    private fun aborted(): Boolean {
        val a = abortFlag
        return a
    }
    @Volatile private var abortFlag = false

    @Volatile private var externalCancel = false

    fun requestExternalCancel() {
        externalCancel = true
        abortFlag = true
    }
    private class ExternalCancelException : RuntimeException("导出已取消")
    private fun checkExternalCancel() {
        if (externalCancel) throw ExternalCancelException()
    }

    @Volatile var lastRunReachedEnd: Boolean = false

    // Callback-only source clock, isolated from concurrent preview/export threads.
    private val frameClockUs = ThreadLocal.withInitial { 0L }
    private val frameEndUs = ThreadLocal.withInitial { 0L }
    val currentFrameTimeUs: Long get() = frameClockUs.get()

    private class DecodeAttempt(val maxDurationUs: Long = 0L) {

        @Volatile var startUs: Long = 0L

        @Volatile var sharedLocalPath: String? = null
        @Volatile var resumeUs: Long = -1L
        @Volatile var sourceOriginUs: Long = 0L

        @Volatile var srcFps: Int = 0

        @Volatile var srcMime: String? = null

        @Volatile var surfaceFailed: Boolean = false
    }

    private val threadArgb = ThreadLocal.withInitial { IntArray(0) }
    private val threadYuv = ThreadLocal.withInitial { arrayOf(ByteArray(0), ByteArray(0)) }

    @Volatile private var loggedFlexibleNv12 = false
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

    private fun decodeByRetriever(
        ctx: Context, mp4: ByteArray, maxEdge: Int, targetFps: Int, maxFrames: Int,
        timeoutMs: Int, minFrames: Int, onFrame: ((Bitmap, Int) -> Boolean)?,
        srcUri: android.net.Uri? = null, rotationOverride: Int = -1,
        attempt: DecodeAttempt = DecodeAttempt()
    ): Frames? {
        var tmp: File? = null
        var mmr: MediaMetadataRetriever? = null

        val out = ArrayList<Bitmap>()

        var pendingQueue: java.util.concurrent.PriorityBlockingQueue<RetFrame>? = null
        try {
            mmr = MediaMetadataRetriever()
            if (srcUri != null && srcUri.scheme.equals("file", ignoreCase = true)) {

                val f = File(srcUri.path ?: srcUri.toString())
                if (!f.exists()) return null
                mmr.setDataSource(f.absolutePath)
            } else if (srcUri != null) {

                var ok = runCatching { mmr.setDataSource(ctx, srcUri) }.isSuccess
                if (!ok) {
                    ok = runCatching {
                        ctx.contentResolver.openFileDescriptor(srcUri, "r")?.use { pfd ->
                            mmr.setDataSource(pfd.fileDescriptor)
                        } != null
                    }.getOrDefault(false)
                }
                if (!ok) {

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

            val segStartUs = attempt.startUs.coerceAtLeast(0L)
            val segEndUs = if (attempt.maxDurationUs > 0L)
                minOf(sourceDurationUs, segStartUs + attempt.maxDurationUs) else sourceDurationUs
            val durUs = (segEndUs - segStartUs).coerceAtLeast(0L)
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

            var rotation = if (rotationOverride >= 0) rotationOverride else rotation0
            var rotationResolved = rotationOverride >= 0

            var fps = attempt.srcFps.takeIf { it in 1..240 }
                ?: runCatching {
                    mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toInt()
                }.getOrNull()?.takeIf { it in 1..60 }
                ?: targetFps.coerceIn(1, 240)
            fps = minOf(fps, targetFps.coerceIn(1, 240))
            val intervalUs = 1_000_000L / fps
            val nFrames = ((durUs + intervalUs - 1) / intervalUs).toInt().coerceIn(1, maxFrames)

            val resumeFromUs = if (onFrame != null && attempt.resumeUs >= 0L)
                attempt.resumeUs - attempt.sourceOriginUs else -1L

            val startI = maxOf(
                if (resumeFromUs > 0L) ((resumeFromUs + intervalUs) / intervalUs).toInt().coerceAtLeast(0) else 0,
                (segStartUs / intervalUs.coerceAtLeast(1L)).toInt())
            DebugLog.log("DEC", "兜底retriever durUs=$durUs segStart=${segStartUs/1000}ms fps=$fps nFrames=$nFrames startI=$startI w=$w h=$h rotation=$rotation")
            val s = maxEdge.toFloat() / maxOf(w, h).toFloat()
            val dw = (w * minOf(1f, s)).toInt().coerceAtLeast(1)
            val dh = (h * minOf(1f, s)).toInt().coerceAtLeast(1)
            val delayMs = (intervalUs / 1000L).toInt().coerceIn(1, 60_000)

            val pq = java.util.concurrent.PriorityBlockingQueue<RetFrame>(
                16, Comparator.comparingInt { it.seq })
            pendingQueue = pq
            val workers = 2
            val remainFrames = (nFrames - startI).coerceAtLeast(1)
            val chunk = (remainFrames + workers - 1) / workers

            val decodeBudgetMs = (remainFrames * 625L / workers + 90_000L)
                .coerceAtLeast(240_000L).coerceAtMost(900_000L)
            val deadline = System.currentTimeMillis() + decodeBudgetMs
            val doneCount = java.util.concurrent.atomic.AtomicInteger(0)
            val abortP = java.util.concurrent.atomic.AtomicBoolean(false)

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

                            for (retry in 0..2) {
                                if (abortP.get() || externalCancel ||
                                    System.currentTimeMillis() > deadline) break
                                runCatching {

                                    bmp = if (android.os.Build.VERSION.SDK_INT >= 27) {
                                        mmrW!!.getScaledFrameAtTime(i * intervalUs,
                                            MediaMetadataRetriever.OPTION_CLOSEST, dw, dh)
                                    } else {
                                        mmrW!!.getFrameAtTime(i * intervalUs,
                                            MediaMetadataRetriever.OPTION_CLOSEST)?.let { f ->
                                            if (f.width == dw && f.height == dh) f
                                            else android.graphics.Bitmap
                                                .createScaledBitmap(f, dw, dh, true)
                                                .also { if (it !== f) f.recycle() }
                                        }
                                    }
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
                if (f.seq != nextSeq) { pq.put(f); continue }
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
                    frameEndUs.set(segEndUs)
                    frameClockUs.set(f.seq * intervalUs)
                    val boundedDelay = minOf(delayMs.toLong(), ((segEndUs - f.seq * intervalUs + 999L) / 1000L).coerceAtLeast(1L)).toInt()
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
                    frameEndUs.set(segEndUs)
                    frameClockUs.set(f.seq * intervalUs)
                    val boundedDelay = minOf(delayMs.toLong(), ((segEndUs - f.seq * intervalUs + 999L) / 1000L).coerceAtLeast(1L)).toInt()
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

                minOf(delayMs.toLong(), ((segEndUs - (startI + i) * intervalUs + 999L) / 1000L).coerceAtLeast(1L)).toInt()
            }, w, h, rotation)
        } catch (t: Throwable) {

            out.forEach { b -> runCatching { b.recycle() } }
            if (t is ExternalCancelException) throw t
            return null
        } finally {
            runCatching { mmr?.release() }
            runCatching { tmp?.delete() }

            pendingQueue?.let { q ->
                var f = q.poll()
                while (f != null) {
                    f.bmp?.let { b -> if (!b.isRecycled) runCatching { b.recycle() } }
                    f = q.poll()
                }
            }
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

        var imageHandlerThread: android.os.HandlerThread? = null
        var imageHandler: android.os.Handler? = null
        val surfaceFrameQueue = java.util.concurrent.LinkedBlockingQueue<android.media.Image>(8)

        val out = ArrayList<Bitmap>()
        val pts = ArrayList<Long>()
        try {

            extractor = MediaExtractor()
            if (srcUri != null && srcUri.scheme.equals("file", ignoreCase = true)) {

                val f = File(srcUri.path ?: srcUri.toString())
                if (!f.exists()) return null
                extractor.setDataSource(f.absolutePath)
            } else if (srcUri != null) {

                val shared = attempt.sharedLocalPath
                if (shared != null) {
                    val sf = File(shared)
                    if (sf.exists() && sf.length() > 0) {
                        extractor.setDataSource(shared)
                        DebugLog.log("DEC", "复用共享源文件 ${sf.length() / 1024}KB（避免重复拷贝）")
                    } else {
                        attempt.sharedLocalPath = null
                        val t = File(ctx.cacheDir, "lgw_src_${System.currentTimeMillis()}.mp4")
                        tmp = t
                        val copied = runCatching { copyUriToFile(ctx, srcUri, t); t.length() > 0 }.getOrDefault(false)
                        if (!copied || t.length() <= 0) {
                            runCatching { t.delete() }; tmp = null
                            runCatching { extractor.setDataSource(ctx, srcUri, null) }
                        } else {
                            attempt.sharedLocalPath = t.absolutePath
                            DebugLog.log("DEC", "srcUri 落盘 ${t.length()/1024}KB（链内共享）")
                            extractor.setDataSource(t.absolutePath)
                        }
                    }
                } else {
                    val t = File(ctx.cacheDir, "lgw_src_${System.currentTimeMillis()}.mp4")
                    tmp = t
                    val copied = runCatching { copyUriToFile(ctx, srcUri, t); t.length() > 0 }.getOrDefault(false)
                    if (!copied || t.length() <= 0) {

                        DebugLog.log("DEC", "srcUri 落盘失败，回退流式（可能截断）")
                        runCatching { t.delete() }; tmp = null
                        runCatching { extractor.setDataSource(ctx, srcUri, null) }
                    } else {
                        attempt.sharedLocalPath = t.absolutePath
                        DebugLog.log("DEC", "srcUri 落盘 ${t.length()/1024}KB 本地解码")
                        extractor.setDataSource(t.absolutePath)
                    }
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
            attempt.srcMime = mime

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

                DebugLog.log("DEC", "解码开始 codec=${codecName ?: "默认"} mime=$mime ${fw}x$fh fps=$ffps dur=${fdur / 1000}ms profile=$fprof std=$fstd tr=$ftr 文件=${srcBytes / 1024}KB")
            }

            var rawRotation = if (rotationOverride >= 0) rotationOverride else {
                runCatching { format.getInteger(MediaFormat.KEY_ROTATION) }.getOrDefault(0)
            }
            if (rawRotation == 0 && rotationOverride < 0) {
                rawRotation = runCatching {
                    val mmr = MediaMetadataRetriever()
                    try {
                        if (srcUri != null) mmr.setDataSource(ctx, srcUri)
                        else mmr.setDataSource(tmp!!.absolutePath)

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

            var rotation = rawRotation
            var rotationResolved = rotationOverride >= 0

            extractor.selectTrack(track)
            attempt.sourceOriginUs = extractor.sampleTime.coerceAtLeast(0L)
            // Apply rotation to pixels ourselves, identically for ImageReader and YUV.
            format.setInteger(MediaFormat.KEY_ROTATION, 0)

            val resumeFromUs = if (onFrame != null) attempt.resumeUs else -1L

            val startGateUs = attempt.sourceOriginUs + attempt.startUs.coerceAtLeast(0L)
            val seekPointUs = maxOf(resumeFromUs, startGateUs)
            if (seekPointUs > 0L) extractor.seekTo(
                (seekPointUs - 100_000L).coerceAtLeast(0L), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            codec = CodecSelector.createDecoder(mime, codecName)
            if (codec == null) return null

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

                    runCatching { codec?.release() }
                    codec = CodecSelector.createDecoder(mime, codecName)
                }
            }
            DebugLog.log("DEC", "解码器 ${codecName ?: "默认"} Surface=$useSurface coded=${codedW}x${codedH} maxEdge=$maxEdge surfaceFailed=${attempt.surfaceFailed} onFrame=${onFrame != null}")
            if (!useSurface) {

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

            val c = codec ?: return null

            val srcFps = runCatching { format.getInteger(MediaFormat.KEY_FRAME_RATE) }.getOrDefault(0)
            if (srcFps in 1..240) attempt.srcFps = srcFps
            val effFps = minOf(targetFps.coerceIn(1, 240), if (srcFps in 1..240) srcFps else targetFps.coerceIn(1, 240))
            val frameIntervalUs = 1_000_000L / effFps
            val info = MediaCodec.BufferInfo()
            var inputEos = false
            var outputEos = false
            var stalled = false
            var lastKeptUs = Long.MIN_VALUE
            var kept = 0
            var nextSampleUs = startGateUs
            var guard = 0
            var lastOutputMs = System.currentTimeMillis()

            var surfaceNullFrames = 0

            var gapSum = 0L; var gapN = 0
            val deadline = System.currentTimeMillis() + timeoutMs

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
                            if (g > 0 && g < 5_000L) { gapSum += g; gapN++ }
                        }
                        lastOutputMs = outWall
                        val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0

                        if (info.presentationTimeUs < startGateUs) {
                            c.releaseOutputBuffer(outIndex, false)
                            if (eos) outputEos = true
                            continue
                        }

                        val relPlayUs = (info.presentationTimeUs - startGateUs).coerceAtLeast(0L)
                        val reachedLimit = attempt.maxDurationUs > 0L && relPlayUs >= attempt.maxDurationUs
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

                                    c.releaseOutputBuffer(outIndex, true)

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

                                        if (attempt.surfaceFailed.not()) {
                                            DebugLog.log("DEC", "Surface Image 处理异常 ${ie.javaClass.simpleName}: ${ie.message}，本层回退 buffer")
                                            attempt.surfaceFailed = true
                                        }
                                        surfaceNullFrames++
                                        null
                                    } finally { runCatching { img.close() } }
                                } else {
                                    var fb: Bitmap? = null

                                    val img = runCatching { codec?.getOutputImage(outIndex) }.getOrNull()
                                if (img != null) {
                                    try {

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

                                if (useSurface && attempt.surfaceFailed.not()) {
                                    DebugLog.log("DEC", "Surface 取帧外层异常 ${e.javaClass.simpleName}: ${e.message}，本层回退")
                                    attempt.surfaceFailed = true
                                }
                            }.getOrNull()
                            if (bmp != null) {
                                if (useSurface) surfaceNullFrames = 0

                                nextSampleUs = startGateUs +
                                    ((relPlayUs + 500L) / frameIntervalUs + 1L) * frameIntervalUs
                                if (onFrame != null) {

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

                                    frameClockUs.set((info.presentationTimeUs - attempt.sourceOriginUs).coerceAtLeast(0L))
                                    val boundedDelay = if (attempt.maxDurationUs > 0L)
                                        minOf(delayMs.toLong(), ((attempt.maxDurationUs - relPlayUs + 999L) / 1000L).coerceAtLeast(1L)).toInt()
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

                        if ((onFrame == null && out.size >= maxFrames) ||
                            (onFrame != null && kept >= maxFrames)) outputEos = true
                    }
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {

                        val silent = System.currentTimeMillis() - lastOutputMs
                        val avgGap = if (gapN > 0) gapSum / gapN else 0L
                        val stallLimit = maxOf(5_000L, avgGap * 30)
                        if (silent > stallLimit) {
                            stalled = true
                            DebugLog.log("DEC", "解码停滞 ${silent}ms 无输出（阈值 ${stallLimit}ms avgGap=${avgGap}ms）kept=$kept lastPts=${if (lastKeptUs == Long.MIN_VALUE) -1 else lastKeptUs}us inputEos=$inputEos")
                        }
                    }
                    else -> {  }
                }
            }

            if (onFrame != null) {
                DebugLog.log("DEC", "硬解结束 kept=$kept 帧 最后PTS=${lastKeptUs}us (${lastKeptUs/1000}ms) outputEos=$outputEos")
                if (outputEos && kept == 0) {

                    DebugLog.log("DEC", "kept=0 outputEos=true：0 帧无效，判失败交兜底（防假成功）")
                    return null
                }
                if (outputEos) return Frames(emptyList(), IntArray(0), 0, 0, rotation)
                // An attempt that delivered no frames must not mark PTS=0 consumed.
                if (lastKeptUs != Long.MIN_VALUE) attempt.resumeUs = lastKeptUs
                return null
            }

            if (!outputEos && out.size < maxFrames) {
                DebugLog.log("DEC", "硬解残段 out=${out.size} 未到EOS，判失败")
                out.forEach { runCatching { it.recycle() } }
                return null
            }
            if (out.size < minFrames.coerceAtLeast(1)) { out.forEach { runCatching { it.recycle() } }; return null }

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

                    ((attempt.maxDurationUs - (pickedPts[i] - startGateUs) + 999L) / 1000L).coerceAtLeast(1L)
                    else 60_000L
                delays[i] = minOf(d.coerceAtLeast(1).toLong(), remainingMs).toInt()
            }

            picked.toHashSet().let { keep ->
                out.forEachIndexed { idx, b -> if (idx !in keep) runCatching { b.recycle() } }
            }
            val first = frames[0]
            return Frames(frames, delays, first.width, first.height, rotation)
        } catch (t: Throwable) {

            out.forEach { b -> runCatching { b.recycle() } }
            if (t is ExternalCancelException) throw t

            DebugLog.log("DEC", "解码异常 codec=${codecName ?: "默认"}: ${t.javaClass.simpleName}: ${t.message}")

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

        val cropX = image.cropRect.left
        val cropY = image.cropRect.top
        val baseEdge = maxOf(cropW, cropH)

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

                val v = if (uvPix >= 2 && vBuf === uBuf)
                    (uBuf.get(uvIdx + 1).toInt() and 255)
                else (vBuf.get(vRowBase + ((cropX + sx) / 2) * vPix).toInt() and 255)
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

    internal fun yuvBufferToScaledBitmap(
        buf: java.nio.ByteBuffer, fmt: MediaFormat, maxEdge: Int, rotation: Int
    ): Bitmap? {
        val cf = runCatching { fmt.getInteger(MediaFormat.KEY_COLOR_FORMAT) }.getOrDefault(-1)
        val semi = cf == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar ||
            cf == 22 ||
            cf == 2135033992
        val planar = cf == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar

        if (cf == 2135033992 && !loggedFlexibleNv12) {
            loggedFlexibleNv12 = true
            DebugLog.log("DEC", "COLOR_FormatYUV420Flexible 按 NV12 布局试解：布局由解码器决定，" +
                "个别设备可能出现色彩异常（自检失败请优先怀疑此处）")
        }
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
