package com.liquidglass.watermark

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import java.nio.ByteBuffer

/**
 * 动态 WebP 解码（API28+，ImageDecoder + AnimatedImageDrawable 抓帧）。
 * 兼容性最好：不依赖第三方库，Android 9 起的系统解码器原生支持 animated WebP。
 * 动画推进依赖主线程 Choreographer，故必须在传入的 handler（通常主线程）上执行；
 * 每帧经 onFrame 回调（可流式消费，由调用方负责 recycle），onDone 必定被调用一次。
 */
object WebPDecoder {

    /** 探测 animated WebP：RIFF/WEBP + VP8X 动画标志（bit 0x02）或 ANIM chunk */
    fun looksLikeAnimatedWebP(bytes: ByteArray): Boolean {
        if (bytes.size < 30) return false
        if (bytes[0] != 0x52.toByte() || bytes[1] != 0x49.toByte() ||
            bytes[2] != 0x46.toByte() || bytes[3] != 0x46.toByte()) return false
        if (bytes[8] != 0x57.toByte() || bytes[9] != 0x45.toByte() ||
            bytes[10] != 0x42.toByte() || bytes[11] != 0x50.toByte()) return false
        var off = 12
        while (off + 8 <= bytes.size) {
            val four = String(bytes, off, 4, Charsets.US_ASCII)
            val size = (bytes[off + 4].toInt() and 255) or
                ((bytes[off + 5].toInt() and 255) shl 8) or
                ((bytes[off + 6].toInt() and 255) shl 16) or
                ((bytes[off + 7].toInt() and 255) shl 24)
            if (size < 0 || off.toLong() + 8L + size > bytes.size.toLong()) return false
            when (four) {
                "VP8X" -> {
                    // VP8X payload: 8 字节 chunk 头后第 1 字节为 flags（bit1=animation，FFmpeg VP8X_FLAG_ANIMATION=0x02）
                    if (off + 9 <= bytes.size && (bytes[off + 8].toInt() and 0x02) != 0) return true
                    // animation 位没设不代表一定静态：非标准工具可能漏标 flags 但仍有 ANIM/ANMF，继续扫描
                }
                "ANIM", "ANMF" -> return true   // 动画控制块或帧数据块 → 动态 WebP
                "VP8 ", "VP8L" -> return false  // 出现首帧 chunk 而无 VP8X 动画标志/ANIM → 静态
            }
            off += 8 + size + (size and 1)
        }
        return false
    }

    /**
     * 异步解码动态 WebP。
     * @param handler 解码与回调所在线程（动画需主线程，通常传 Handler(Looper.getMainLooper())）
     * @param maxEdge 最长边上限（预览 480；导出受内存限制可取 960）
     * @param maxFrames 最多帧数
     * @param timeoutMs 总超时
     * @param onFrame 每帧回调 (bitmap, delayMs)，主线程；bitmap 由调用方 recycle（流式）
     * @param onDone 结束回调 (ok)，主线程；ok=至少产出 1 帧
     */
    fun decodeAsync(
        handler: Handler,
        ctx: Context,
        bytes: ByteArray,
        maxEdge: Int,
        maxFrames: Int,
        timeoutMs: Int,
        onFrame: (Bitmap, Int) -> Unit,
        onDone: (Boolean) -> Unit
    ) {
        // Dedicated Handler identity prevents cleanup from cancelling unrelated UI work.
        val decodeHandler = Handler(handler.looper)
        decodeHandler.post {
            var drawable: AnimatedImageDrawable? = null
            var finished = false
            var firstFrame: Bitmap? = null
            var frameBitmap: Bitmap? = null
            var lastTs = 0L
            var frameCount = 0
            val deadline = System.currentTimeMillis() + timeoutMs
            val finish: (Boolean) -> Unit = { ok ->
                if (!finished) {
                    finished = true
                    runCatching { drawable?.stop() }
                    decodeHandler.removeCallbacksAndMessages(null)
                    drawable?.callback = null
                    firstFrame?.let { if (!it.isRecycled) it.recycle() }; firstFrame = null
                    frameBitmap?.let { if (!it.isRecycled) it.recycle() }; frameBitmap = null
                    onDone(ok)
                }
            }
            try {
                val src = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
                val d = ImageDecoder.decodeDrawable(src) { decoder, info, _ ->
                    val maxSide = maxOf(info.size.width, info.size.height)
                    var ss = 1
                    while (maxSide / (ss * 2) >= maxEdge.coerceAtLeast(1) && ss < 64) ss *= 2
                    if (ss > 1) decoder.setTargetSampleSize(ss)
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
                if (d !is AnimatedImageDrawable) { finish(false); return@post }
                drawable = d
                val w = d.intrinsicWidth.coerceAtLeast(1)
                val h = d.intrinsicHeight.coerceAtLeast(1)
                val frameBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                frameBitmap = frameBmp
                val canvas = Canvas(frameBmp)
                d.setRepeatCount(2) // 播两遍，便于识别回环（回到首帧即完整一遍）
                d.callback = object : Drawable.Callback {
                    override fun invalidateDrawable(who: Drawable) {
                        if (finished) return
                        if (System.currentTimeMillis() > deadline || frameCount >= maxFrames) {
                            finish(frameCount > 0); return
                        }
                        canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
                        d.draw(canvas)
                        val now = System.currentTimeMillis()
                        val delay = if (lastTs == 0L) 100 else (now - lastTs).toInt().coerceIn(16, 500)
                        val f = frameBmp.copy(Bitmap.Config.ARGB_8888, false)
                        val first = firstFrame
                        if (first != null && samePixels(f, first)) {
                            // 回到首帧：动画已完整播一遍，丢弃重复帧并结束
                            f.recycle(); finish(frameCount > 0); return
                        }
                        if (first == null) firstFrame = f.copy(Bitmap.Config.ARGB_8888, false)
                        lastTs = now
                        frameCount++
                        onFrame(f, delay)
                        if (frameCount >= maxFrames) finish(true)
                    }

                    override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
                        decodeHandler.postAtTime(what, `when`)
                    }
                    override fun unscheduleDrawable(who: Drawable, what: Runnable) {
                        decodeHandler.removeCallbacks(what)
                    }
                }
                // 首帧：未启动时 draw 即首帧
                canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
                d.draw(canvas)
                val f0 = frameBmp.copy(Bitmap.Config.ARGB_8888, false)
                firstFrame = f0.copy(Bitmap.Config.ARGB_8888, false)
                lastTs = System.currentTimeMillis()
                frameCount++
                onFrame(f0, 100)
                if (frameCount >= maxFrames) { finish(true); return@post }
                d.start()
                // 超时兜底
                decodeHandler.postDelayed({ finish(frameCount > 0) }, timeoutMs.toLong() + 500)
            } catch (t: Throwable) {
                finish(frameCount > 0)
            }
        }
    }

    /** 49 点抽样比较两帧是否相同（判定动画回环） */
    private fun samePixels(a: Bitmap, b: Bitmap): Boolean {
        if (a.width != b.width || a.height != b.height) return false
        for (gy in 0 until 7) {
            for (gx in 0 until 7) {
                val x = (a.width - 1) * gx / 6
                val y = (a.height - 1) * gy / 6
                if (a.getPixel(x, y) != b.getPixel(x, y)) return false
            }
        }
        return true
    }
}
