package com.liquidglass.watermark

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer

/**
 * v15.16 导出管线基准测试（仅诊断/回归用，不参与业务逻辑）。
 *
 * 目的：在任意视频时长下量化导出耗时、每帧成本、CPU/内存/I/O，
 * 用来实证"超过 15 秒导出变慢"是否存在台阶，以及修复前后对比。
 *
 * 三条路径（与真实用户路径一致）：
 *  - gpu    = GpuMp4Exporter（硬解→CPU 水印→硬编，cpuWatermark=true）——用户实际默认路径
 *  - gpuraw = GpuMp4Exporter + cpuWatermark=false（纯 GPU shader 近似）
 *  - cpu    = Mp4Exporter 逐帧软件渲染
 *
 * 入口（MainActivity.runAutoTest）：
 *   adb shell am start -n com.liquidglass.watermark/.MainActivity --es ljgAutoTest "bench:5,14,15,16,30,60,300"
 *
 * 产物：getExternalFilesDir(null)/bench/bench_<ts>.csv + 每档样片 bench_sample_<n>s.mp4
 */
object ExportBench {

    /** 单档测量的采样点：进度 → 墙钟毫秒（用于定位台阶位置） */
    private data class Probe(val pct: Int, val elapsedMs: Long, val heapMb: Int)

    private data class Sample(val path: String, val ms: Long, val frames: Int,
                              val cpuMs: Long, val readKb: Long, val writeKb: Long,
                              val peakHeapMb: Int, val probes: List<Probe>)

    // ── 进程级计数器（/proc/self）──────────────────────────────────────────
    private fun cpuMs(): Long = runCatching {
        val f = File("/proc/self/stat")
        if (!f.isFile) return 0L
        val s = f.readText()
        val close = s.lastIndexOf(')')
        if (close < 0) return 0L
        val parts = s.substring(close + 2).split(' ')
        val utime = parts[11].toLong()
        val stime = parts[12].toLong()
        (utime + stime) * 1000L / 100L
    }.getOrDefault(0L)

    private fun ioBytes(key: String): Long = runCatching {
        val f = File("/proc/self/io")
        if (!f.isFile) return 0L
        f.readLines().firstOrNull { it.startsWith("$key:") }
            ?.substringAfter(':')?.trim()?.toLong() ?: 0L
    }.getOrDefault(0L)

    private fun heapMb(): Int {
        val rt = Runtime.getRuntime()
        return ((rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)).toInt()
    }

    private fun countFrames(path: String): Int = runCatching {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(path)
            val ti = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: -1
            if (ti < 0) return 0
            ex.selectTrack(ti)
            val b = ByteBuffer.allocateDirect(256 * 1024)
            var n = 0
            while (ex.readSampleData(b, 0) >= 0) { n++; ex.advance() }
            n
        } finally { runCatching { ex.release() } }
    }.getOrDefault(0)

    // ── 样片生成：MediaCodec 编码 N 秒动态图案（帧间必须有差异）──
    fun makeSample(ctx: Context, seconds: Int, out: File, w: Int = 640, h: Int = 360, fps: Int = 30): Boolean {
        if (out.isFile && out.length() > 1024) return true
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        return try {
            val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
                setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val info = MediaCodec.BufferInfo()
            var track = -1
            var started = false
            val total = seconds * fps
            val ySize = w * h
            val uvSize = (w / 2) * (h / 2) * 2
            var frame = 0
            var eos = false
            val deadline = System.currentTimeMillis() + 60_000L + seconds * 600L
            while (!eos && System.currentTimeMillis() < deadline) {
                if (frame < total) {
                    val idx = codec.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        val buf = codec.getInputBuffer(idx)!!
                        buf.clear()
                        val shift = (frame * 3) % w
                        for (y in 0 until h) {
                            val rowBase = y * w
                            val band = ((y + frame * 2) / 24) % 2
                            for (x in 0 until w) {
                                val g = ((x + shift) * 255 / w)
                                val v = if (band == 0) g else 255 - g
                                buf.put(rowBase + x, (40 + v * 3 / 4).toByte())
                            }
                        }
                        for (y in 0 until h / 2) {
                            for (x in 0 until w / 2) {
                                val u = (128 + ((x + shift / 2) % 32) * 2 - 32).toByte()
                                val vv = (128 - ((y + frame) % 32) * 2 + 32).toByte()
                                val o = ySize + y * w + x * 2
                                if (o + 1 < ySize + uvSize) {
                                    buf.put(o, u)
                                    buf.put(o + 1, vv)
                                }
                            }
                        }
                        codec.queueInputBuffer(idx, 0, ySize + uvSize,
                            frame * 1_000_000L / fps, 0)
                        frame++
                    }
                } else {
                    val idx = codec.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        codec.queueInputBuffer(idx, 0, 0, frame * 1_000_000L / fps,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        eos = true
                    }
                }
                while (true) {
                    val oi = codec.dequeueOutputBuffer(info, 0)
                    if (oi == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (!started) { track = muxer.addTrack(codec.outputFormat); muxer.start(); started = true }
                        continue
                    }
                    if (oi < 0) continue
                    val ob = codec.getOutputBuffer(oi)!!
                    if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0
                    if (info.size > 0 && started) {
                        ob.position(info.offset); ob.limit(info.offset + info.size)
                        muxer.writeSampleData(track, ob, info)
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) { eos = true; break }
                }
            }
            true
        } catch (t: Throwable) {
            DebugLog.log("BENCH", "样片生成失败 ${seconds}s: ${t.javaClass.simpleName} ${t.message}")
            false
        } finally {
            runCatching { codec?.stop() }; runCatching { codec?.release() }
            runCatching { muxer?.stop() }; runCatching { muxer?.release() }
        }
    }

    // ── 单档测量：统一计时/CPU/IO 包装 ─────────────────────────────────────
    private inline fun timed(label: String, body: (probes: MutableList<Probe>) -> Int?): Sample? {
        val probes = ArrayList<Probe>()
        val t0 = System.currentTimeMillis()
        val cpu0 = cpuMs()
        val rd0 = ioBytes("read_bytes")
        val wr0 = ioBytes("write_bytes")
        val frames = try {
            body(probes)
        } catch (t: Throwable) {
            DebugLog.log("BENCH", "$label 异常 ${t.javaClass.simpleName}: ${t.message}")
            null
        }
        val ms = System.currentTimeMillis() - t0
        if (frames == null || frames <= 0) {
            DebugLog.log("BENCH", "$label 失败")
            return null
        }
        return Sample(label, ms, frames,
            cpuMs() - cpu0,
            (ioBytes("read_bytes") - rd0) / 1024,
            (ioBytes("write_bytes") - wr0) / 1024,
            heapMb(), probes)
    }

    /** GPU 路径（用户默认导出路径）：硬解 → CPU 水印 → 硬编 */
    private fun measureGpu(ctx: Context, renderer: WatermarkEditorView, sample: File,
                           cpuWatermark: Boolean, label: String): Sample? {
        val out = File(ctx.cacheDir, "bench_gpu_${System.currentTimeMillis()}.mp4")
        return timed(label) { probes ->
            var lastPct = -1
            val r = GpuMp4Exporter.export(
                ctx, sample, out, 640, 360, 30, 0,
                MediaFormat.MIMETYPE_VIDEO_AVC, renderer,
                onProgress = { p ->
                    if (p != lastPct) { lastPct = p; probes.add(Probe(p, System.currentTimeMillis(), heapMb())) }
                },
                isCancelled = { false },
                cpuWatermark = cpuWatermark,
                includeAudio = false
            )
            if (r == null) { DebugLog.log("BENCH", "$label GPU 返回 null（见 GPU 日志）"); null }
            else { runCatching { out.delete() }; countFrames(out.absolutePath).takeIf { it > 0 } ?: r.frames }
        }?.let { s ->
            // GPU 路径的 probes 是绝对时间戳，转成相对起点
            val t0 = s.probes.firstOrNull()?.elapsedMs ?: 0L
            s.copy(probes = s.probes.map { Probe(it.pct, it.elapsedMs - t0 + 0, it.heapMb) })
        }
    }

    /** 纯 CPU 路径：Mp4Exporter 逐帧软件渲染 */
    private fun measureCpu(ctx: Context, renderer: WatermarkEditorView, sample: File,
                           label: String): Sample? = timed(label) { probes ->
        val out = Mp4Exporter.export(
            ctx, ByteArray(0), 0,
            onFrame = { frame -> renderer.renderWatermarkOnly(frame, frame.width, frame.height) },
            onProgress = { p -> probes.add(Probe(p, System.currentTimeMillis(), heapMb())) },
            prefixFrame = null, prefixDurationMs = 0,
            srcUri = Uri.fromFile(sample),
            reuseOutput = true,
            watermarkRenderer = renderer,
            includeAudio = false
        )
        if (out == null) { DebugLog.log("BENCH", "$label CPU 失败 ${Mp4Exporter.lastFailReason}"); null }
        else { val n = countFrames(out.absolutePath); runCatching { out.delete() }; n }
    }?.let { s ->
        val t0 = s.probes.firstOrNull()?.elapsedMs ?: 0L
        s.copy(probes = s.probes.map { Probe(it.pct, it.elapsedMs - t0, it.heapMb) })
    }

    /**
     * 跑一组时长 × 三条导出路径。
     * @return CSV 文件
     */
    fun run(ctx: Context, renderer: WatermarkEditorView?, durationsSec: List<Int>): File? {
        if (renderer == null) {
            DebugLog.log("BENCH", "renderer 为空，基准中止")
            return null
        }
        val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "bench").apply { mkdirs() }
        val csv = File(dir, "bench_${System.currentTimeMillis()}.csv")
        csv.writeText("phase,durationSec,path,exportMs,frames,msPerFrame,cpuMs,readKB,writeKB,heapMB,probes\n")
        DebugLog.log("BENCH", "开始 时长=$durationsSec 设备=API${android.os.Build.VERSION.SDK_INT} 模型=${android.os.Build.MODEL}")
        for (sec in durationsSec) {
            val sample = File(dir, "bench_sample_${sec}s.mp4")
            if (!makeSample(ctx, sec, sample)) {
                csv.appendText("generate,$sec,-,FAILED,0,0,0,0,0,0,\n"); continue
            }
            val paths: List<Pair<String, () -> Sample?>> = listOf(
                "gpu" to { measureGpu(ctx, renderer, sample, true, "${sec}s/gpu") },
                "gpuraw" to { measureGpu(ctx, renderer, sample, false, "${sec}s/gpuraw") },
                "cpu" to { measureCpu(ctx, renderer, sample, "${sec}s/cpu") }
            )
            for ((label, fn) in paths) {
                val s = fn()
                if (s == null) {
                    csv.appendText("export,$sec,$label,FAILED,0,0,0,0,0,0,\n"); continue
                }
                val perFrame = if (s.frames > 0) "%.2f".format(s.ms.toDouble() / s.frames) else "0"
                val probeStr = s.probes.joinToString(" ") { "${it.pct}:${it.elapsedMs}" }.replace(",", ";")
                csv.appendText("export,$sec,$label,${s.ms},${s.frames},$perFrame," +
                    "${s.cpuMs},${s.readKb},${s.writeKb},${s.peakHeapMb},\"$probeStr\"\n")
                DebugLog.log("BENCH",
                    "RESULT sec=$sec path=$label exportMs=${s.ms} frames=${s.frames} msPerFrame=$perFrame " +
                        "cpuMs=${s.cpuMs} readKB=${s.readKb} writeKB=${s.writeKb} heapMB=${s.peakHeapMb}")
                // 台阶定位：按 10 个百分点分桶
                val pts = s.probes.map { it.pct to it.elapsedMs }.distinctBy { it.first }
                val sb = StringBuilder()
                for (i in 1 until pts.size) {
                    val a = pts[i - 1]; val b = pts[i]
                    val dp = b.first - a.first
                    if (dp > 0) sb.append("${a.first}-${b.first}:${(b.second - a.second) / dp} ")
                }
                DebugLog.log("BENCH", "SEG sec=$sec path=$label $sb")
            }
        }
        DebugLog.log("BENCH", "完成 → ${csv.absolutePath}")
        return csv
    }
}
