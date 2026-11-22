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

object WebPDecoder {

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

                    if (off + 9 <= bytes.size && (bytes[off + 8].toInt() and 0x02) != 0) return true

                }
                "ANIM", "ANMF" -> return true
                "VP8 ", "VP8L" -> return false
            }
            off += 8 + size + (size and 1)
        }
        return false
    }

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
            var loopBackStreak = 0
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

            if (android.os.Build.VERSION.SDK_INT < 28) { finish(false); return@post }
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
                d.setRepeatCount(2)
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
                            loopBackStreak++
                            if (loopBackStreak >= 2) {
                                f.recycle(); finish(frameCount > 0); return
                            }
                        } else {
                            loopBackStreak = 0
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

                canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
                d.draw(canvas)
                val f0 = frameBmp.copy(Bitmap.Config.ARGB_8888, false)
                firstFrame = f0.copy(Bitmap.Config.ARGB_8888, false)
                lastTs = System.currentTimeMillis()
                frameCount++
                onFrame(f0, 100)
                if (frameCount >= maxFrames) { finish(true); return@post }
                d.start()

                decodeHandler.postDelayed({ finish(frameCount > 0) }, timeoutMs.toLong() + 500)
            } catch (t: Throwable) {
                finish(frameCount > 0)
            }
        }
    }

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
