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

object ExportBench {

    private data class Probe(val pct: Int, val elapsedMs: Long, val heapMb: Int)

    private data class Sample(val path: String, val ms: Long, val frames: Int,
                              val cpuMs: Long, val readKb: Long, val writeKb: Long,
                              val peakHeapMb: Int, val probes: List<Probe>)

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

            val inFmt = runCatching { codec.inputFormat }.getOrNull()
            val strideY = runCatching { inFmt?.getInteger(MediaFormat.KEY_STRIDE) }.getOrNull()?.takeIf { it > 0 } ?: w
            val sliceH = runCatching { inFmt?.getInteger(MediaFormat.KEY_SLICE_HEIGHT) }.getOrNull()?.takeIf { it > 0 } ?: h
            val ySize = strideY * sliceH
            val uvSize = strideY * (sliceH / 2)
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
                            val rowBase = y * strideY
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
                                val o = ySize + y * strideY + x * 2
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

            else { val n = countFrames(out.absolutePath); runCatching { out.delete() }; n.takeIf { it > 0 } ?: r.frames }
        }?.let { s ->

            val t0 = s.probes.firstOrNull()?.elapsedMs ?: 0L
            s.copy(probes = s.probes.map { Probe(it.pct, it.elapsedMs - t0 + 0, it.heapMb) })
        }
    }

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

    fun run(ctx: Context, renderer: WatermarkEditorView?, durationsSec: List<Int>,
            fpsList: List<Int> = listOf(30)): File? {
        if (renderer == null) {
            DebugLog.log("BENCH", "renderer 为空，基准中止")
            return null
        }
        val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "bench").apply { mkdirs() }
        val csv = File(dir, "bench_${System.currentTimeMillis()}.csv")
        csv.writeText("phase,durationSec,fps,path,exportMs,frames,msPerFrame,speedupX,cpuMs,readKB,writeKB,heapMB,probes\n")
        DebugLog.log("BENCH", "开始 时长=$durationsSec 帧率=$fpsList 设备=API${android.os.Build.VERSION.SDK_INT} 模型=${android.os.Build.MODEL}")
        for (fps in fpsList) {
            for (sec in durationsSec) {
                val sample = File(dir, "bench_sample_${sec}s_${fps}fps.mp4")
                if (!makeSample(ctx, sec, sample, fps = fps)) {
                    csv.appendText("generate,$sec,$fps,-,FAILED,0,0,0,0,0,0,0,\n"); continue
                }
                val paths: List<Pair<String, () -> Sample?>> = listOf(
                    "gpu" to { measureGpu(ctx, renderer, sample, true, "${sec}s@${fps}fps/gpu") },
                    "gpuraw" to { measureGpu(ctx, renderer, sample, false, "${sec}s@${fps}fps/gpuraw") },
                    "cpu" to { measureCpu(ctx, renderer, sample, "${sec}s@${fps}fps/cpu") }
                )
                for ((label, fn) in paths) {
                    val s = fn()
                    if (s == null) {
                        csv.appendText("export,$sec,$fps,$label,FAILED,0,0,0,0,0,0,0,\n"); continue
                    }
                    val perFrame = if (s.frames > 0) "%.2f".format(s.ms.toDouble() / s.frames) else "0"

                    val speedup = if (sec > 0 && s.ms > 0) "%.2f".format(sec * 1000.0 / s.ms) else "0"
                    val probeStr = s.probes.joinToString(" ") { "${it.pct}:${it.elapsedMs}" }.replace(",", ";")
                    csv.appendText("export,$sec,$fps,$label,${s.ms},${s.frames},$perFrame,$speedup," +
                        "${s.cpuMs},${s.readKb},${s.writeKb},${s.peakHeapMb},\"$probeStr\"\n")
                    DebugLog.log("BENCH",
                        "RESULT sec=${sec}s fps=$fps path=$label exportMs=${s.ms} frames=${s.frames} " +
                            "msPerFrame=$perFrame speedup=${speedup}x " +
                            "cpuMs=${s.cpuMs} readKB=${s.readKb} writeKB=${s.writeKb} heapMB=${s.peakHeapMb}")

                    val pts = s.probes.map { it.pct to it.elapsedMs }.distinctBy { it.first }
                    val sb = StringBuilder()
                    for (i in 1 until pts.size) {
                        val a = pts[i - 1]; val b = pts[i]
                        val dp = b.first - a.first
                        if (dp > 0) sb.append("${a.first}-${b.first}:${(b.second - a.second) / dp} ")
                    }
                    DebugLog.log("BENCH", "SEG sec=${sec}s fps=$fps path=$label $sb")
                }
            }
        }
        DebugLog.log("BENCH", "完成 → ${csv.absolutePath}")
        return csv
    }
}
