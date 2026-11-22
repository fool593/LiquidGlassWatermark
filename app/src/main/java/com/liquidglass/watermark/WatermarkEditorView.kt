package com.liquidglass.watermark
import android.content.Context
import android.graphics.Bitmap
import android.graphics.LinearGradient
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Movie
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import kotlin.math.roundToInt

class WatermarkEditorView @JvmOverloads constructor(
    context: Context, attrs: android.util.AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {
    private val photoDst = RectF()
    private val cache = WatermarkRenderer.BlurCache()

    private val frameCache = WatermarkRenderer.BlurCache()
    private val density = resources.displayMetrics.density
    private val performanceProfile = WatermarkPerformancePolicy.forContext(context)
    val motionExportMaxEdge: Int get() = performanceProfile.videoFrameEdge

    private var gifFrame: Bitmap? = null
    private var gifCanvas: Canvas? = null
    private var gifW = 0
    private var gifH = 0

    private var outFrame: Bitmap? = null

    private var wmOut: Bitmap? = null

    private var cropBuf: Bitmap? = null
    private var maskOut: Bitmap? = null

    private var rotatedSrc: Bitmap? = null

    private var frameRaw: Bitmap? = null
    private var frameRawW = 0
    private var frameRawH = 0
    private val sourcePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)

    private var placeBmp: Bitmap? = null

    private var placeBmpKey = -1

    private var backdropBmp: Bitmap? = null
    private var backdropFor: Bitmap? = null
    private var backdropGeneration = -1
    private var playbackSource: Any? = null
    private var playbackStartedAt = 0L

    private fun playbackMs(source: Any, durationMs: Long = 0L): Long {
        if (playbackSource !== source) {
            playbackSource = source
            playbackStartedAt = SystemClock.uptimeMillis()
        }
        val elapsed = (SystemClock.uptimeMillis() - playbackStartedAt).coerceAtLeast(0L)
        return if (durationMs > 0L) elapsed % durationMs else elapsed
    }
    init {

        setLayerType(LAYER_TYPE_SOFTWARE, null)
        setBackgroundColor(Color.rgb(10, 10, 18))
    }
    private fun currentPhoto(): Bitmap? {
        val pv = EditorState.previewBitmap
        if (pv != null && !pv.isRecycled) return pv

        val cp = EditorState.previewCustomBitmap
        if (cp != null && !cp.isRecycled) return cp
        return null
    }

    private fun placeholderPhoto(): Bitmap {

        val orient = EditorState.params.canvasOrient
        val ratio = when (orient) {
            EditorState.CANVAS_LANDSCAPE -> 9f / 16f
            EditorState.CANVAS_PORTRAIT -> 16f / 9f
            else -> {
                val vw = width; val vh = height
                if (vw > 0 && vh > 0) vh.toFloat() / vw else 4f / 3f
            }
        }
        val key = (ratio * 1000f).toInt()
        if (key != placeBmpKey) {
            placeBmp?.let { if (!it.isRecycled) it.recycle() }
            placeBmp = null
            placeBmpKey = key
        }
        placeBmp?.let { if (!it.isRecycled) return it }
        val long = 1440
        val w: Int; val h: Int

        if (ratio <= 1f) { w = long; h = (long * ratio).toInt().coerceAtLeast(320) }
        else { h = long; w = (long / ratio).toInt().coerceAtLeast(320) }
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(),
                intArrayOf(0xFF3A4A63.toInt(), 0xFF222A38.toInt(), 0xFF4A3A55.toInt()),
                null, Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bg)

        val rnd = java.util.Random(42)
        val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        for (i in 0 until 5000) {
            val x = rnd.nextFloat() * w
            val y = rnd.nextFloat() * h
            val r = 1f + rnd.nextFloat() * 2.2f
            val a = 50 + rnd.nextInt(90)
            dot.color = Color.argb(a, 210 + rnd.nextInt(45), 210 + rnd.nextInt(45), 210 + rnd.nextInt(45))
            c.drawCircle(x, y, r, dot)
        }

        val ribbon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, w.toFloat() * 0.6f, h.toFloat() * 0.4f,
                intArrayOf(0x66FFFFFF.toInt(), 0x00FFFFFF), null, Shader.TileMode.CLAMP)
        }
        for (i in 0 until 7) {
            val x0 = (i * 0.16f - 0.25f) * w
            val y0 = (i * 0.11f) * h
            ribbon.strokeWidth = 46f + i * 10f
            c.drawLine(x0, y0, x0 + w * 0.5f, y0 + h * 0.32f, ribbon)
        }

        val blob = Paint(Paint.ANTI_ALIAS_FLAG)
        val blobs = arrayOf(
            floatArrayOf(0.18f, 0.30f, 0.16f) to 0xFFE8B84A.toInt(),
            floatArrayOf(0.72f, 0.20f, 0.13f) to 0xFF4AC6E8.toInt(),
            floatArrayOf(0.82f, 0.68f, 0.19f) to 0xFFE86A4A.toInt(),
            floatArrayOf(0.22f, 0.78f, 0.15f) to 0xFF7AE84A.toInt())
        for ((pos, col) in blobs) {
            val px = pos[0] * w; val py = pos[1] * h; val pr = pos[2] * w
            blob.shader = RadialGradient(px, py, pr, col, Color.TRANSPARENT, Shader.TileMode.CLAMP)
            c.drawCircle(px, py, pr, blob)
        }

        val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x2EFFFFFF.toInt(); strokeWidth = 3f
        }
        for (i in 1..8) { val y = i * h / 9f; c.drawLine(0f, y, w.toFloat(), y, grid) }
        for (i in 1..6) { val x = i * w / 7f; c.drawLine(x, 0f, x, h.toFloat(), grid) }
        placeBmp = b
        return b
    }
    private fun fit(rect: RectF, bw: Int, bh: Int, vw: Int, vh: Int) {
        if (bw <= 0 || bh <= 0 || vw <= 0 || vh <= 0) { rect.set(0f, 0f, 1f, 1f); return }
        val swap = ((EditorState.photoRotation % 180) + 180) % 180 != 0
        val rw = if (swap) bh else bw
        val rh = if (swap) bw else bh
        val s = minOf(vw.toFloat() / rw, vh.toFloat() / rh)
        val dw = rw * s; val dh = rh * s
        rect.set((vw - dw) / 2f, (vh - dh) / 2f, (vw + dw) / 2f, (vh + dh) / 2f)

        val aspect = EditorState.cropAspect((EditorState.params.cropRatio))
        if (aspect > 0f && rect.width() > 1f && rect.height() > 1f) {
            val cur = rect.width() / rect.height()
            if (cur > aspect) {

                val nh = rect.height(); val nw = nh * aspect
                val nx = rect.centerX() - nw / 2f
                rect.set(nx, rect.top, nx + nw, rect.bottom)
            } else {

                val nw = rect.width(); val nh = nw / aspect
                val ny = rect.centerY() - nh / 2f
                rect.set(rect.left, ny, rect.right, ny + nh)
            }
        }
    }

    private fun pickMotionIndex(): Int {
        val frames = EditorState.motionFrames ?: return 0
        val delays = EditorState.motionDelays
        if (frames.isEmpty()) return 0
        val total = frames.indices.sumOf { (delays?.getOrNull(it) ?: 66).coerceAtLeast(1).toLong() }
        var t = playbackMs(frames, total)
        var frameStart = 0L
        for (i in frames.indices) {
            val delay = (delays?.getOrNull(i) ?: 66).coerceAtLeast(1)
            if (t < delay) {
                EditorState.mediaPreviewTimeSec = frameStart / 1000f
                return i
            }
            t -= delay
            frameStart += delay
        }
        return frames.size - 1
    }

    private fun ensureGifFrame(movie: Movie): Bitmap {
        val srcW = movie.width().coerceAtLeast(1)
        val srcH = movie.height().coerceAtLeast(1)
        val s = minOf(1f, 720f / maxOf(srcW, srcH).toFloat())
        val w = maxOf(1, (srcW * s).toInt())
        val h = maxOf(1, (srcH * s).toInt())
        if (gifFrame == null || gifW != w || gifH != h || gifFrame!!.isRecycled) {
            runCatching { gifFrame?.recycle() }
            gifFrame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            gifCanvas = Canvas(gifFrame!!)
            gifW = w; gifH = h
        }
        val c = gifCanvas!!
        val dur = EditorState.gifDuration.coerceAtLeast(1)
        val playhead = playbackMs(movie, dur.toLong()).toInt()
        EditorState.mediaPreviewTimeSec = playhead / 1000f
        movie.setTime(playhead)
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        c.save()
        c.scale(w.toFloat() / srcW, h.toFloat() / srcH)
        movie.draw(c, 0f, 0f)
        c.restore()
        return gifFrame!!
    }

    @Volatile var exportSnapshot: Bitmap? = null
        private set

    fun captureExportSnapshot() {
        runCatching {
            val onEmulator = com.liquidglass.watermark.glass.isEmulator()
            val movie = if (EditorState.isGif) EditorState.gifMovie else null

            val motion = if (movie == null && !onEmulator) EditorState.motionFrames else null
            val photo = when {
                movie != null -> ensureGifFrame(movie)
                motion != null && motion.isNotEmpty() -> motion[pickMotionIndex()]
                else -> currentPhoto() ?: return
            }
            if (photo.isRecycled || width < 2 || height < 2) return
            fit(photoDst, photo.width, photo.height, width, height)

            val work = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                val c = Canvas(work)
                c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

                val dst = RectF(photoDst)
                WatermarkRenderer.render(c, photo, dst, EditorState.renderParams(), density,
                    WatermarkRenderer.unitForPhoto(dst.width(), dst.height(), density), null, 480)

                val old = exportSnapshot
                exportSnapshot = work
                if (old !== work) runCatching { old?.recycle() }
            } catch (t: Throwable) {
                runCatching { work.recycle() }
                throw t
            }
        }
    }

    fun clearExportSnapshot() {
        val s = exportSnapshot
        exportSnapshot = null
        runCatching { s?.recycle() }
        invalidate()
    }

    private fun obtainBackdrop(photo: Bitmap): Bitmap? {
        if (backdropFor === photo && backdropGeneration == photo.generationId &&
            backdropBmp != null && !backdropBmp!!.isRecycled) return backdropBmp
        backdropFor = photo
        backdropGeneration = photo.generationId
        runCatching {
            val vw = width.coerceAtLeast(1); val vh = height.coerceAtLeast(1)
            val bw = (vw / 8).coerceAtLeast(8); val bh = (vh / 8).coerceAtLeast(8)
            if (photo.width <= 0 || photo.height <= 0) return null

            val s = maxOf(bw.toFloat() / photo.width, bh.toFloat() / photo.height)
            val dw = (photo.width * s).toInt().coerceAtLeast(2)
            val dh = (photo.height * s).toInt().coerceAtLeast(2)
            val b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            c.drawBitmap(photo, null,
                android.graphics.RectF(((bw - dw) / 2f), ((bh - dh) / 2f), (bw + dw) / 2f, (bh + dh) / 2f), null)
            runCatching { backdropBmp?.recycle() }
            backdropBmp = b
        }
        return backdropBmp
    }

    private fun drawBackdrop(canvas: Canvas, src: Bitmap, rotDeg: Float) {
        val bd = obtainBackdrop(src) ?: return
        if (bd.isRecycled) return
        val cp = sourcePaint
        cp.alpha = 255
        val rot = (((rotDeg % 360f) + 360f) % 360f)
        if (rot == 0f) {
            canvas.drawBitmap(bd, null,
                android.graphics.RectF(0f, 0f, width.toFloat(), height.toFloat()), cp)
            return
        }

        canvas.save()
        canvas.rotate(rot, width / 2f, height / 2f)
        val rad = Math.toRadians(rot.toDouble())
        val cos = Math.abs(Math.cos(rad)).toFloat(); val sin = Math.abs(Math.sin(rad)).toFloat()
        val vw = width.toFloat(); val vh = height.toFloat()
        val cw = vw * cos + vh * sin
        val ch = vw * sin + vh * cos
        canvas.drawBitmap(bd, null,
            android.graphics.RectF((vw - cw) / 2f, (vh - ch) / 2f, (vw + cw) / 2f, (vh + ch) / 2f), cp)
        canvas.restore()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        runCatching {
            val want = if (com.liquidglass.watermark.darkModeState) Color.rgb(10, 10, 18)
            else Color.rgb(228, 230, 236)
            val cur = (background as? android.graphics.drawable.ColorDrawable)?.color
            if (cur != want) setBackgroundColor(want)
        }
        try {

            val onEmulator = com.liquidglass.watermark.glass.isEmulator()
            val movie = if (EditorState.isGif) EditorState.gifMovie else null
            val motion = if (movie == null && !onEmulator) EditorState.motionFrames else null
            val animated = movie != null || (motion != null && motion.size >= 2)
            val photo = when {
                movie != null -> ensureGifFrame(movie)
                motion != null && motion.isNotEmpty() -> motion[pickMotionIndex()]
                else -> currentPhoto() ?: placeholderPhoto()
            }
            if (photo.isRecycled) return
            if (!animated) EditorState.mediaPreviewTimeSec = playbackMs(photo) / 1000f
            fit(photoDst, photo.width, photo.height, width, height)

            if (EditorState.isExporting) {
                val bp = sourcePaint
                bp.alpha = 255
                val snap = exportSnapshot
                if (snap != null && !snap.isRecycled && snap.width == width && snap.height == height)
                    canvas.drawBitmap(snap, 0f, 0f, bp)
                else
                    canvas.drawBitmap(photo, null, photoDst, bp)
                return
            }
            if (animated) cache.invalidateKey() // Reuse the patch buffers while replacing its contents.

            val previewEdge = if (animated) performanceProfile.animatedPreviewEdge
                else performanceProfile.previewEdge

            var interacting = EditorState.sliderInteracting
            if (interacting && System.currentTimeMillis() - EditorState.lastSliderMoveMs > 800L) {
                EditorState.sliderInteracting = false
                interacting = false
                DebugLog.log("RENDER", "滑块交互超时复位：恢复完整渲染")
            }
            val effEdge = if (interacting) (previewEdge / 2).coerceAtLeast(160) else previewEdge

            WatermarkRenderer.render(canvas, photo, photoDst, EditorState.renderParams(),
                density, WatermarkRenderer.unitForPhoto(photoDst.width(), photoDst.height(), density),
                cache, effEdge, lite = interacting)
            if (animated) postInvalidateDelayed(performanceProfile.animationIntervalMs)
            else if (EditorState.params.fluidEnabled ||
                (EditorState.params.breathEnabled && EditorState.params.breathStrength > 0)) {
                // Toggles may be enabled after attach; restart without requiring an app reload.
                animHandler.removeCallbacks(animTick)
                animHandler.postDelayed(animTick, performanceProfile.animationIntervalMs)
            }
        } catch (t: Throwable) {

            Log.e("LiquidGlass", "onDraw render failed", t)

            val bp2 = sourcePaint
            bp2.alpha = 255
            val snap2 = exportSnapshot
            if (snap2 != null && !snap2.isRecycled && snap2.width == width && snap2.height == height) {
                canvas.drawBitmap(snap2, 0f, 0f, bp2)
            } else {
                runCatching {
                    val ph = currentPhoto() ?: placeholderPhoto()
                    if (!ph.isRecycled) {
                        fit(photoDst, ph.width, ph.height, width, height)
                        canvas.drawBitmap(ph, null, photoDst, sourcePaint)
                    }
                }
            }
            DebugLog.log("RENDER", "onDraw 异常=${t.javaClass.simpleName}: ${t.message}，已画水印快照兜底")
        }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {

        if (EditorState.isExporting) return false
        if (width == 0 || height == 0 || photoDst.width() <= 1f || photoDst.height() <= 1f)
            return false
        return try {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {

                    val r = WatermarkRenderer.lastWatermarkRect
                    if (r.width() <= 1f || r.height() <= 1f) { dragging = false; return false }
                    val pad = 28f * density
                    val hit = event.x >= r.left - pad && event.x <= r.right + pad &&
                              event.y >= r.top - pad && event.y <= r.bottom + pad
                    dragging = hit
                    if (hit) {
                        val p = EditorState.params
                        p.relX = ((event.x - photoDst.left) / photoDst.width()).coerceIn(0f, 1f)
                        p.relY = ((event.y - photoDst.top) / photoDst.height()).coerceIn(0f, 1f)
                        invalidate()
                    }
                    hit
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging) return false
                    val p = EditorState.params
                    p.relX = ((event.x - photoDst.left) / photoDst.width()).coerceIn(0f, 1f)
                    p.relY = ((event.y - photoDst.top) / photoDst.height()).coerceIn(0f, 1f)
                    invalidate()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { dragging = false; true }
                else -> { dragging = false; super.onTouchEvent(event) }
            }
        } catch (t: Throwable) { dragging = false; false }
    }
    private var dragging = false

    fun sourceForExport(): Bitmap? = currentPhoto()

    fun export(full: Bitmap): Bitmap {
        check(!full.isRecycled) { "source bitmap recycled" }

        val cropAspect = EditorState.cropAspect(EditorState.params.cropRatio)
        val cropped = if (cropAspect > 0f) {
            CropMath.cropToAspect(full, 0, cropAspect)
        } else full
        val rot = ((EditorState.photoRotation % 360) + 360) % 360
        val outW = if (rot % 180 != 0) cropped.height else cropped.width
        val outH = if (rot % 180 != 0) cropped.width else cropped.height
        val source = if (rot == 0) cropped else Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888).also {
            drawRotatedSource(Canvas(it), cropped, outW, outH)
        }
        try {
            val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            val dst = RectF(0f, 0f, outW.toFloat(), outH.toFloat())
            val unit = WatermarkRenderer.unitForPhoto(outW.toFloat(), outH.toFloat(), density)
            // Rotate only the scene. The watermark keeps the same screen-space orientation,
            // normalized position and dimensions as preview, even for 90/270 degree rotations.
            WatermarkRenderer.render(Canvas(out), source, dst, EditorState.renderParams(),
                density, unit, null, 1600, photoPreRotated = true, cropApplied = cropAspect > 0f)
            return out
        } finally {
            if (cropped !== full) cropped.recycle()
            if (source !== cropped) source.recycle()
        }
    }

    private fun ensureFrameRaw(w: Int, h: Int) {
        if (frameRaw == null || frameRawW != w || frameRawH != h || frameRaw!!.isRecycled) {
            runCatching { frameRaw?.recycle() }
            frameRaw = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1),
                Bitmap.Config.ARGB_8888)
            frameRawW = w; frameRawH = h
        }
    }

    private fun drawRotatedSource(c: Canvas, src: Bitmap, outW: Int, outH: Int) {
        val rot = ((EditorState.photoRotation % 360) + 360) % 360
        if (rot == 0) {

            c.drawBitmap(src, null,
                android.graphics.Rect(0, 0, outW, outH), sourcePaint)
            return
        }
        val srcW = src.width; val srcH = src.height
        c.save()
        c.translate(outW / 2f, outH / 2f)
        c.rotate(rot.toFloat())

        val sx = if (rot % 180 != 0) outH.toFloat() / srcW else outW.toFloat() / srcW
        val sy = if (rot % 180 != 0) outW.toFloat() / srcH else outH.toFloat() / srcH
        c.scale(sx, sy)
        c.translate(-srcW / 2f, -srcH / 2f)
        c.drawBitmap(src, 0f, 0f, sourcePaint)
        c.restore()
    }

    private fun cropFrame(src: Bitmap): Bitmap {
        val aspect = EditorState.cropAspect(EditorState.params.cropRatio)
        if (aspect <= 0f) return src
        val rect = CropMath.centerCropRect(src.width, src.height, aspect)
        val left = rect.left.roundToInt().coerceIn(0, src.width - 1)
        val top = rect.top.roundToInt().coerceIn(0, src.height - 1)
        val w = rect.width().roundToInt().coerceIn(1, src.width - left)
        val h = rect.height().roundToInt().coerceIn(1, src.height - top)
        if (left == 0 && top == 0 && w == src.width && h == src.height) return src
        if (cropBuf == null || cropBuf!!.width != w || cropBuf!!.height != h ||
            cropBuf!!.isRecycled) {
            runCatching { cropBuf?.recycle() }
            cropBuf = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1),
                Bitmap.Config.ARGB_8888)
        }
        val cb = cropBuf!!

        val c = Canvas(cb)
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val srcRect = android.graphics.Rect(left, top, left + w, top + h)
        val dstRect = android.graphics.Rect(0, 0, w, h)
        c.drawBitmap(src, srcRect, dstRect, sourcePaint)
        return cb
    }

    private fun ensureRotated(w: Int, h: Int): Bitmap {
        if (rotatedSrc == null || rotatedSrc!!.width != w || rotatedSrc!!.height != h ||
            rotatedSrc!!.isRecycled) {
            runCatching { rotatedSrc?.recycle() }
            rotatedSrc = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1),
                Bitmap.Config.ARGB_8888)
        }
        val b = rotatedSrc!!
        Canvas(b).drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        return b
    }

    fun renderGifFrame(movie: Movie, timeMs: Int, outW: Int, outH: Int): Bitmap {
        if (outFrame == null || outFrame!!.width != outW || outFrame!!.height != outH) {
            runCatching { outFrame?.recycle() }
            outFrame = Bitmap.createBitmap(outW.coerceAtLeast(1), outH.coerceAtLeast(1),
                Bitmap.Config.ARGB_8888)
        }
        val srcW = movie.width().coerceAtLeast(1)
        val srcH = movie.height().coerceAtLeast(1)

        ensureFrameRaw(srcW, srcH)
        val raw = frameRaw!!
        val rawC = Canvas(raw)
        rawC.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        movie.setTime(timeMs.coerceIn(0, EditorState.gifDuration.coerceAtLeast(1)))
        movie.draw(rawC, 0f, 0f)

        val work = cropFrame(raw)

        val src = ensureRotated(outW, outH)
        drawRotatedSource(Canvas(src), work, outW, outH)
        if (work !== raw) {  }

        val bmp = outFrame!!
        val c = Canvas(bmp)
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val dst = RectF(0f, 0f, outW.toFloat(), outH.toFloat())
        val unit = WatermarkRenderer.unitForPhoto(outW.toFloat(), outH.toFloat(), density)

        frameCache.invalidateKey()

        WatermarkRenderer.render(c, src, dst, EditorState.renderParams(), density, unit, frameCache,
            performanceProfile.videoFrameEdge, photoPreRotated = true,
            skipShadow = true, cropApplied = EditorState.cropAspect(EditorState.params.cropRatio) > 0f)
        return bmp
    }

    fun renderMotionFrame(srcFrame: Bitmap, outW: Int, outH: Int): Bitmap {
        if (outFrame == null || outFrame!!.width != outW || outFrame!!.height != outH) {
            runCatching { outFrame?.recycle() }
            outFrame = Bitmap.createBitmap(outW.coerceAtLeast(1), outH.coerceAtLeast(1),
                Bitmap.Config.ARGB_8888)
        }

        val work = cropFrame(srcFrame)

        val src = ensureRotated(outW, outH)
        drawRotatedSource(Canvas(src), work, outW, outH)
        if (work !== srcFrame) {  }
        val bmp = outFrame!!
        val c = Canvas(bmp)
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val dst = RectF(0f, 0f, outW.toFloat(), outH.toFloat())
        val unit = WatermarkRenderer.unitForPhoto(outW.toFloat(), outH.toFloat(), density)

        frameCache.invalidateKey()

        WatermarkRenderer.render(c, src, dst, EditorState.renderParams(), density, unit, frameCache,
            performanceProfile.videoFrameEdge, photoPreRotated = true,
            skipShadow = true, cropApplied = EditorState.cropAspect(EditorState.params.cropRatio) > 0f)
        return bmp
    }

    fun renderWatermarkOnly(src: Bitmap, w: Int, h: Int, skipGlassBody: Boolean = false): Bitmap {

        var out = wmOut
        if (out == null || out.width != w || out.height != h || out.isRecycled) {
            runCatching { out?.recycle() }
            out = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1),
                Bitmap.Config.ARGB_8888)
            wmOut = out
        }
        val c = Canvas(out)
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val dst = RectF(0f, 0f, w.toFloat(), h.toFloat())
        val unit = WatermarkRenderer.unitForPhoto(w.toFloat(), h.toFloat(), density)

        frameCache.invalidateKey()
        WatermarkRenderer.render(c, src, dst, EditorState.renderParams(), density, unit, frameCache,
            performanceProfile.videoFrameEdge,
            photoPreRotated = true, skipGlassBody = skipGlassBody)
        return out
    }

    fun renderMaskOnly(w: Int, h: Int): Bitmap {        var out = maskOut
        if (out == null || out.width != w || out.height != h || out.isRecycled) {
            runCatching { out?.recycle() }
            out = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1),
                Bitmap.Config.ARGB_8888)
            maskOut = out
        }
        val c = Canvas(out)
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val dst = RectF(0f, 0f, w.toFloat(), h.toFloat())
        val unit = WatermarkRenderer.unitForPhoto(w.toFloat(), h.toFloat(), density)
        WatermarkRenderer.render(c, out, dst, EditorState.renderParams(), density, unit, null, 720,
            photoPreRotated = true, maskOnly = true)

        return out
    }

    private var distFieldKey = ""
    private var distFieldBmp: android.graphics.Bitmap? = null
    fun renderDistField(w: Int, h: Int): android.graphics.Bitmap? {
        val bar = WatermarkRenderer.lastWatermarkRect
        if (bar.isEmpty || bar.width() < 2f || bar.height() < 2f) return null
        val rot = EditorState.params.rotation.toFloat()
        val corner = (EditorState.params.cornerPct.coerceIn(0, 100) / 100f) * (bar.height() / 2f)
        val key = "${bar.left.toInt()},${bar.top.toInt()},${bar.width().toInt()},${bar.height().toInt()},$rot,$corner,$w,$h"
        if (key == distFieldKey) return distFieldBmp
        distFieldKey = key
        val dw = 256; val dh = ((256f * h / w).toInt()).coerceAtLeast(32)
        var bmp = distFieldBmp
        if (bmp == null || bmp.width != dw || bmp.height != dh || bmp.isRecycled) {
            runCatching { bmp?.recycle() }
            bmp = android.graphics.Bitmap.createBitmap(dw, dh, android.graphics.Bitmap.Config.ARGB_8888)
            distFieldBmp = bmp
        }
        val hx = bar.width() / 2f; val hy = bar.height() / 2f
        val cx = bar.centerX(); val cy = bar.centerY()
        val minDim = minOf(bar.width(), bar.height())
        val rad = corner.coerceIn(0f, minDim / 2f)
        val maxIn = minDim * 0.5f
        val sx = dw / w.toFloat(); val sy = dh / h.toFloat()
        val px = IntArray(dw * dh)
        val a = Math.toRadians((((rot % 360f) + 360f) % 360f).toDouble())
        val cr = Math.cos(a).toFloat(); val sr = Math.sin(a).toFloat()
        var i = 0
        for (yy in 0 until dh) {
            for (xx in 0 until dw) {
                val px0 = (xx + 0.5f) / sx
                val py0 = (yy + 0.5f) / sy
                val dx = px0 - cx; val dy = py0 - cy
                val lx = dx * cr + dy * sr
                val ly = -dx * sr + dy * cr
                val qx = Math.abs(lx) - (hx - rad)
                val qy = Math.abs(ly) - (hy - rad)
                val ox = Math.max(qx, 0f); val oy = Math.max(qy, 0f)
                val sd = Math.sqrt((ox * ox + oy * oy).toDouble()).toFloat() - rad + Math.min(Math.max(qx, qy), 0f)
                val insd = ((-sd).coerceAtLeast(0f) / maxIn).coerceAtMost(1f)
                val v = (insd * 255f).toInt().coerceIn(0, 255)
                px[i++] = 0xFF000000.toInt() or (v shl 16) or (v shl 8) or v
            }
        }
        bmp.setPixels(px, 0, dw, 0, 0, dw, dh)
        return bmp
    }

    fun invalidateBlur() { cache.reset(); invalidate() }

    private val animHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val animTick = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow || !isEnabled) return

            val p = EditorState.params
            if (p.fluidEnabled || (p.breathEnabled && p.breathStrength > 0)) {
                postInvalidateOnAnimation()
                animHandler.postDelayed(this, performanceProfile.animationIntervalMs)
            }
        }
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animHandler.post(animTick)
    }
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animHandler.removeCallbacks(animTick)

        if (EditorState.isExporting) return
        cache.reset()
        runCatching {
            gifFrame?.recycle(); outFrame?.recycle(); wmOut?.recycle(); maskOut?.recycle()
            frameRaw?.recycle(); rotatedSrc?.recycle()
            placeBmp?.recycle(); exportSnapshot?.recycle(); backdropBmp?.recycle()

            distFieldBmp?.recycle()
        }
        gifFrame = null; outFrame = null; wmOut = null; maskOut = null
        frameRaw = null; rotatedSrc = null; gifCanvas = null; placeBmp = null; exportSnapshot = null
        backdropBmp = null; backdropFor = null
        distFieldBmp = null; distFieldKey = ""
    }
}
