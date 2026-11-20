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
/**
 * 照片 / GIF + 液态玻璃水印预览。软件画布渲染，保证 API26-34 阴影/发光效果完全一致。
 * 支持直接拖动水印；GIF 会循环播放并实时叠加水印；导出复用同一渲染器，所见即所得。
 * 所有绘制 / 触摸 / 导出路径均做异常兜底，任何图片都不会导致闪退。
 */
class WatermarkEditorView @JvmOverloads constructor(
    context: Context, attrs: android.util.AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {
    private val photoDst = RectF()
    private val cache = WatermarkRenderer.BlurCache()
    // 逐帧导出专用玻璃缓存（跨帧复用输出位图；每帧 invalidateKey 强制重算玻璃，消除每帧整图分配）
    private val frameCache = WatermarkRenderer.BlurCache()
    private val density = resources.displayMetrics.density
    private val performanceProfile = WatermarkPerformancePolicy.forContext(context)
    val motionExportMaxEdge: Int get() = performanceProfile.videoFrameEdge
    // GIF 预览复用帧
    private var gifFrame: Bitmap? = null
    private var gifCanvas: Canvas? = null
    private var gifW = 0
    private var gifH = 0
    // GIF 导出复用帧（最终输出）
    private var outFrame: Bitmap? = null
    // MP4 逐帧水印复用帧（同一次导出尺寸恒定，避免每帧 new 整帧 ARGB 的 GC 卡顿）
    private var wmOut: Bitmap? = null
    // v15.32 裁剪复用缓冲：按 cropRatio 裁源帧时避免每帧 new Bitmap
    private var cropBuf: Bitmap? = null
    private var maskOut: Bitmap? = null
    // 旋转后的独立源层：必须与 outFrame 分离，否则 Canvas(bmp).drawBitmap(bmp) 自绘导致撕裂
    private var rotatedSrc: Bitmap? = null
    // GIF 源帧中间层：movie.draw 先画到这里（无变换 canvas），再 drawBitmap 旋转缩放
    private var frameRaw: Bitmap? = null
    private var frameRawW = 0
    private var frameRawH = 0
    private val sourcePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    // 纯色占位底（无图案）
    private var placeBmp: Bitmap? = null
    // v15.35：占位底按画布比例生成，避免横屏画布下 3:4 占位图两侧留黑
    private var placeBmpKey = -1
    // v14.05：模糊留边打底（照片 cover 铺满后 1/8 缩小再放大=廉价高斯），
    // 按 photo 位图身份缓存；动画内容（GIF/动图）跳过走普通拉伸兜底
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
        // 关键：软件层渲染，setShadowLayer 外发光在 Android 8/9 也不会失效
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        setBackgroundColor(Color.rgb(10, 10, 18))
    }
    private fun currentPhoto(): Bitmap? {
        val pv = EditorState.previewBitmap
        if (pv != null && !pv.isRecycled) return pv
        // 自定义预览衬底：无用户图时显示用户选的预览背景（水印/玻璃照常渲染）
        val cp = EditorState.previewCustomBitmap
        if (cp != null && !cp.isRecycled) return cp
        return null  // 无用户图：返回 null，由 placeholderPhoto 兜底（纯色衬底渲染水印，不显示示例图片）
    }
    /** 纹理丰富占位底：渐变 + 细密点阵 + 斜向丝带 + 彩斑 —— 无照片时玻璃的模糊/折射/扭曲清晰可见 */
    private fun placeholderPhoto(): Bitmap {
        // v15.35：按当前画布比例出图（横屏 16:9 / 竖屏 9:16 / 自动=视口比例），
        // 占位底与画布同比例 → fit() 后满幅铺满，不会四周留黑。
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
        // ratio = 高/宽：<1 为横屏（宽>高），>1 为竖屏
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
        // 细密点阵（高频细节）：玻璃模糊/扭曲在点阵上清晰可见
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
        // 斜向丝带（边界清晰）：折射/扭曲在丝带边缘可见
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
        // 模糊彩斑：玻璃折射在圆斑边缘可见
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
        // 细网格线：模糊/位移可见
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
        // v15.32 自定义裁剪比例：在等比 fit 基础上，按显示方向比例中心裁剪显示矩形。
        // WatermarkRenderer 侧已用同一 cropRect 采样源图，预览即导出所见，无拉伸。
        val aspect = EditorState.cropAspect((EditorState.params.cropRatio))
        if (aspect > 0f && rect.width() > 1f && rect.height() > 1f) {
            val cur = rect.width() / rect.height()
            if (cur > aspect) {
                // 显示区更宽：裁左右
                val nh = rect.height(); val nw = nh * aspect
                val nx = rect.centerX() - nw / 2f
                rect.set(nx, rect.top, nx + nw, rect.bottom)
            } else {
                // 显示区更高：裁上下
                val nw = rect.width(); val nh = nw / aspect
                val ny = rect.centerY() - nh / 2f
                rect.set(rect.left, ny, rect.right, ny + nh)
            }
        }
    }
    /** 按各帧停留时长循环选择动态照片当前帧 */
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
    /** 准备 GIF 预览帧（最长边 720，复用 bitmap） */
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
    /**
     * 导出期间显示的含水印快照（独立缓冲，与 GPU 导出线程的 wmOut/maskOut 零共享）。
     * 在 isExporting=true 之前调用（UI 线程），渲染一帧与 onDraw 相同路径的预览帧。
     */
    @Volatile var exportSnapshot: Bitmap? = null
        private set

    fun captureExportSnapshot() {
        runCatching {
            val onEmulator = com.liquidglass.watermark.glass.isEmulator()
            val movie = if (EditorState.isGif) EditorState.gifMovie else null
            // 同 onDraw：模拟器上动态照片预览用静态封面，避免解码帧闪绿
            val motion = if (movie == null && !onEmulator) EditorState.motionFrames else null
            val photo = when {
                movie != null -> ensureGifFrame(movie)
                motion != null && motion.isNotEmpty() -> motion[pickMotionIndex()]
                else -> currentPhoto() ?: return
            }
            if (photo.isRecycled || width < 2 || height < 2) return
            fit(photoDst, photo.width, photo.height, width, height)
            // M7：渲染到临时位图，成功后再交换进 exportSnapshot。
            // 若 render 中途抛异常，旧快照（或 null）保持不变，导出期间不会显示"残缺水印"。
            val work = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                val c = Canvas(work)
                c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                // 关键修复：快照必须与正常预览一致用 photoDst（横屏视频 letterbox 居中），
                // 之前误用全幅 dst → 横屏视频被拉伸成竖屏 → 保存途中预览一直显示竖屏拉伸。
                val dst = RectF(photoDst)
                WatermarkRenderer.render(c, photo, dst, EditorState.renderParams(), density,
                    WatermarkRenderer.unitForPhoto(dst.width(), dst.height(), density), null, 480)
                // 渲染成功：原子交换（旧快照仅在确认新图可用后才回收）
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

    /** 模糊留边打底：照片按 cover 比例裁进 1/8 小图，绘制时放大到全幅 = 高糊无硬边背景。
     *  竖图（左右留边）与横图（上下留边）统一走此路径；静态图缓存命中零开销。 */
    private fun obtainBackdrop(photo: Bitmap): Bitmap? {
        if (backdropFor === photo && backdropGeneration == photo.generationId &&
            backdropBmp != null && !backdropBmp!!.isRecycled) return backdropBmp
        backdropFor = photo
        backdropGeneration = photo.generationId
        runCatching {
            val vw = width.coerceAtLeast(1); val vh = height.coerceAtLeast(1)
            val bw = (vw / 8).coerceAtLeast(8); val bh = (vh / 8).coerceAtLeast(8)
            if (photo.width <= 0 || photo.height <= 0) return null
            // cover：小图按照片比例放大到覆盖 bw×bh（居中裁切，不失真）
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

    /** 预览背景铺底（v14.06 整体重写核心）：当前画面 cover 铺满 + 高糊，旋转跟随内容方向；
     *  任意比例的横/竖/超宽照片与视频都无黑边、无硬切、无接缝。 */
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
        // 内容旋转 90/180/270 时背景同向旋转，并放大到旋转后仍覆盖全幅
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
        // 衬底随深浅模式：深色=深蓝灰、浅色=浅灰，避免浅色模式下照片四周留深色边（显示问题）
        runCatching {
            val want = if (com.liquidglass.watermark.darkModeState) Color.rgb(10, 10, 18)
            else Color.rgb(228, 230, 236)
            val cur = (background as? android.graphics.drawable.ColorDrawable)?.color
            if (cur != want) setBackgroundColor(want)
        }
        try {
            // 模拟器（SwiftShader/软解码器）对视频帧解码异常，逐帧播放会整片闪绿幕；
            // 故模拟器上预览不播放动态照片视频帧，改显示静态封面（currentPhoto=封面），避免闪绿。
            // 真机走正常逐帧预览；导出仍用 motionMp4 独立重新解码，不受此限制。
            val onEmulator = com.liquidglass.watermark.glass.isEmulator()
            val movie = if (EditorState.isGif) EditorState.gifMovie else null
            val motion = if (movie == null && !onEmulator) EditorState.motionFrames else null
            val animated = movie != null || (motion != null && motion.size >= 2)
            val photo = when {
                movie != null -> ensureGifFrame(movie)
                motion != null && motion.isNotEmpty() -> motion[pickMotionIndex()]
                else -> currentPhoto() ?: placeholderPhoto()  // 无用户图：纯色衬底，水印照常预览
            }
            if (photo.isRecycled) return
            if (!animated) EditorState.mediaPreviewTimeSec = playbackMs(photo) / 1000f
            fit(photoDst, photo.width, photo.height, width, height)
            // 导出期间冻结水印重渲染：导出线程正按当前参数并发渲染水印纹理（mask/fg），
            // 预览再渲染会读写共享 EditorState.params（model/dateText 临时改写+恢复）造成竞态，
            // 可能让导出的 mask/fg 内容错乱（水印消失）。导出中预览显示【导出开始前捕获的
            // 含水印快照】（静态、独立缓冲），用户看到水印仍在，而非裸源图。
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
            // 静态预览用较高分辨率玻璃（与导出观感一致，避免"预览糊/导出清晰"）；
            // 动图/视频每帧重建，取较低值保证原帧率流畅
            // 预览用低分辨率做玻璃模糊（人眼对小尺寸模糊不敏感），大幅降 CPU：滑块/拖动不再卡顿；
            // 导出仍走全分辨率（export 单独 1600、逐帧 720），清晰度不受影响。
            val previewEdge = if (animated) performanceProfile.animatedPreviewEdge
                else performanceProfile.previewEdge
            // 拖动中（lite=true）：不降级为"普通状态"，而是降采样渲染（分辨率减半，玻璃效果全保留，
            // 速度约 ×4 防连续大滑动阻塞主线程黑屏）；松手后恢复全分辨率完整渲染。
            // 另有 800ms 无输入超时兜底复位（防松手事件丢失长期停在降采样）
            var interacting = EditorState.sliderInteracting
            if (interacting && System.currentTimeMillis() - EditorState.lastSliderMoveMs > 800L) {
                EditorState.sliderInteracting = false
                interacting = false
                DebugLog.log("RENDER", "滑块交互超时复位：恢复完整渲染")
            }
            val effEdge = if (interacting) (previewEdge / 2).coerceAtLeast(160) else previewEdge
            // ── v14.06 预览框整体绘制（照片/视频/GIF/动图统一一条路径，任意比例适配）──
            //  ① 背景：v15.31 取消"模糊铺满"（旧版把画面模糊拉伸填满四周，
            //     竖屏左右 / 横屏上下会出现一片虚化色块，用户反馈为"多余的东西"）。
            //     现在画布只呈现媒体本身，四周是干净的衬底色，所见即所得。
            //  ② 内容：WatermarkRenderer.render 按正确比例绘制（含照片旋转/玻璃/文字）。
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
            // 任何意外都不闪退，但记录堆栈便于定位"滑块改了水印不更新"的静默失败
            Log.e("LiquidGlass", "onDraw render failed", t)
            // 兜底修复：异常时优先画【含水印快照】（当前参数最后一次成功渲染），
            // 不再画裸原图——拖动滑块高频渲染压力大时渲染异常，旧逻辑兜底成裸图 =
            // 用户看到"水印突然透明/消失"，松手后恢复（反复反馈的根因）。
            // 快照不存在才退而画原图，绝不黑屏。
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
        // 导出期间冻结水印位置（不消费事件），防止拖动改动正在导出的帧；底部导航是 Compose 独立层，不受影响
        if (EditorState.isExporting) return false
        if (width == 0 || height == 0 || photoDst.width() <= 1f || photoDst.height() <= 1f)
            return false
        return try {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // 命中判定：只有按下点落在水印条（外扩一点容错边距）上才开始拖动；
                    // 点预览框其它地方水印纹丝不动（不再点哪跳哪）。
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
    /** 当前实际用于绘制的位图（用户图），无图时返回 null（调用方提示选择照片） */
    fun sourceForExport(): Bitmap? = currentPhoto()
    /** 全分辨率合成静态图（必须在后台线程调用）。照片旋转90/270时输出尺寸自动互换。 */
    fun export(full: Bitmap): Bitmap {
        check(!full.isRecycled) { "source bitmap recycled" }
        // v15.32 自定义裁剪：先在【原始方向】按 cropRatio 中心裁剪，再旋转渲染。
        // 用 CropMath 保证输出尺寸 = 裁后尺寸，预览/导出一致（cropApplied=true 通知渲染层不再二次裁）。
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
    /** 确保 frameRaw 尺寸匹配（movie 原始帧尺寸），无变换 canvas 供 movie.draw 使用 */
    private fun ensureFrameRaw(w: Int, h: Int) {
        if (frameRaw == null || frameRawW != w || frameRawH != h || frameRaw!!.isRecycled) {
            runCatching { frameRaw?.recycle() }
            frameRaw = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1),
                Bitmap.Config.ARGB_8888)
            frameRawW = w; frameRawH = h
        }
    }
    /**
     * 统一旋转+缩放绘制源图到输出 canvas。
     * 输出尺寸 outW×outH 已在调用方按 rot 互换；canvas 4 步标准矩阵，
     * 用原生 drawBitmap（100% 正确应用变换），不依赖 Movie.draw 对变换的支持。
     */
    private fun drawRotatedSource(c: Canvas, src: Bitmap, outW: Int, outH: Int) {
        val rot = ((EditorState.photoRotation % 360) + 360) % 360
        if (rot == 0) {
            // 无旋转：直接缩放到输出
            c.drawBitmap(src, null,
                android.graphics.Rect(0, 0, outW, outH), sourcePaint)
            return
        }
        val srcW = src.width; val srcH = src.height
        c.save()
        c.translate(outW / 2f, outH / 2f)
        c.rotate(rot.toFloat())
        // 旋转 90/270 后源宽高互换，缩放比对应互换
        val sx = if (rot % 180 != 0) outH.toFloat() / srcW else outW.toFloat() / srcW
        val sy = if (rot % 180 != 0) outW.toFloat() / srcH else outH.toFloat() / srcH
        c.scale(sx, sy)
        c.translate(-srcW / 2f, -srcH / 2f)
        c.drawBitmap(src, 0f, 0f, sourcePaint)
        c.restore()
    }
    /**
     * v15.32 裁剪源帧到显示比例（复用 cropBuf 防每帧分配）。返回裁后 bitmap；
     * 未启用裁剪时原样返回 src（不拷贝）。
     */
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
        // 从源复制裁剪区域到 cropBuf
        val c = Canvas(cb)
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val srcRect = android.graphics.Rect(left, top, left + w, top + h)
        val dstRect = android.graphics.Rect(0, 0, w, h)
        c.drawBitmap(src, srcRect, dstRect, sourcePaint)
        return cb
    }
    /** 确保独立源层尺寸匹配（旋转缩放后的源图，必须与输出 outFrame 分离防自绘撕裂） */
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
    /**
     * 渲染 GIF 的某一帧并叠加水印（必须在后台线程调用）。
     * 输出帧尺寸 outW×outH，水印相对画面比例与预览一致。返回复用 bitmap，调用方用完不要回收。
     */
    fun renderGifFrame(movie: Movie, timeMs: Int, outW: Int, outH: Int): Bitmap {
        if (outFrame == null || outFrame!!.width != outW || outFrame!!.height != outH) {
            runCatching { outFrame?.recycle() }
            outFrame = Bitmap.createBitmap(outW.coerceAtLeast(1), outH.coerceAtLeast(1),
                Bitmap.Config.ARGB_8888)
        }
        val srcW = movie.width().coerceAtLeast(1)
        val srcH = movie.height().coerceAtLeast(1)
        // 1. movie 画到无变换中间层 frameRaw（Movie.draw 在变换 canvas 上会撕裂）
        ensureFrameRaw(srcW, srcH)
        val raw = frameRaw!!
        val rawC = Canvas(raw)
        rawC.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        movie.setTime(timeMs.coerceIn(0, EditorState.gifDuration.coerceAtLeast(1)))
        movie.draw(rawC, 0f, 0f)
        // v15.32：GIF 源帧按 cropRatio 中心裁剪后再旋转缩放（预览所见即导出）
        val work = cropFrame(raw)
        // 2. 旋转缩放画到独立源层 rotatedSrc（不能直接画进 outFrame，否则下一步 render 自绘撕裂）
        val src = ensureRotated(outW, outH)
        drawRotatedSource(Canvas(src), work, outW, outH)
        if (work !== raw) { /* cropBuf 复用缓存，不回收 */ }
        // 3. render 把独立源层 src 画到输出 outFrame 并叠加水印（src≠outFrame，无自绘）
        val bmp = outFrame!!
        val c = Canvas(bmp)
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val dst = RectF(0f, 0f, outW.toFloat(), outH.toFloat())
        val unit = WatermarkRenderer.unitForPhoto(outW.toFloat(), outH.toFloat(), density)
        // 逐帧导出：帧内容逐帧变 → 强制重算玻璃（key 命中会返回旧帧玻璃块），缓存跨帧复用位图省分配；
        // maxEdge 480：玻璃是模糊背景，半分辨率采样视觉无损，速度约 ×2（文字/logo 独立绘制保持全分辨率）
        frameCache.invalidateKey()
        // skipShadow=true：动图/GIF 逐帧导出为透明帧，关闭黑色外阴影（消除黑光晕 + 满足"字体不能有阴影"）
        WatermarkRenderer.render(c, src, dst, EditorState.renderParams(), density, unit, frameCache,
            performanceProfile.videoFrameEdge, photoPreRotated = true,
            skipShadow = true, cropApplied = EditorState.cropAspect(EditorState.params.cropRatio) > 0f)  // 逐帧导出：src 已旋转就绪
        return bmp
    }
    /**
     * 渲染动态照片的某一帧（已是 Bitmap）并叠加水印（后台线程调用）。
     * 输出 outW×outH，水印相对画面比例与预览一致；返回复用 bitmap，调用方用完不要回收。
     */
    fun renderMotionFrame(srcFrame: Bitmap, outW: Int, outH: Int): Bitmap {
        if (outFrame == null || outFrame!!.width != outW || outFrame!!.height != outH) {
            runCatching { outFrame?.recycle() }
            outFrame = Bitmap.createBitmap(outW.coerceAtLeast(1), outH.coerceAtLeast(1),
                Bitmap.Config.ARGB_8888)
        }
        // v15.32：先按 cropRatio 裁源帧（若未裁剪原样返回），再旋转缩放绘制
        val work = cropFrame(srcFrame)
        // 旋转缩放画到独立源层，再 render 到输出（防自绘撕裂）
        val src = ensureRotated(outW, outH)
        drawRotatedSource(Canvas(src), work, outW, outH)
        if (work !== srcFrame) { /* cropBuf 复用，不回收 */ }
        val bmp = outFrame!!
        val c = Canvas(bmp)
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val dst = RectF(0f, 0f, outW.toFloat(), outH.toFloat())
        val unit = WatermarkRenderer.unitForPhoto(outW.toFloat(), outH.toFloat(), density)
        // 逐帧导出：帧内容逐帧变 → 强制重算玻璃（key 命中会返回旧帧玻璃块），缓存跨帧复用位图省分配；
        // maxEdge 480：玻璃是模糊背景，半分辨率采样视觉无损，速度约 ×2（文字/logo 独立绘制保持全分辨率）
        frameCache.invalidateKey()
        // skipShadow=true：动态照片逐帧导出为透明帧，关闭黑色外阴影（消除黑光晕 + 满足"字体不能有阴影"）
        WatermarkRenderer.render(c, src, dst, EditorState.renderParams(), density, unit, frameCache,
            performanceProfile.videoFrameEdge, photoPreRotated = true,
            skipShadow = true, cropApplied = EditorState.cropAspect(EditorState.params.cropRatio) > 0f)  // 逐帧导出：src 已旋转就绪
        return bmp
    }
    /**
     * 帧已按旋转就绪（尺寸=w×h），仅叠加水印并返回新 bitmap（后台线程调用）。
     * 供 MP4 流式导出复用；水印相对比例与预览一致。
     */
    fun renderWatermarkOnly(src: Bitmap, w: Int, h: Int, skipGlassBody: Boolean = false): Bitmap {
        // 复用同尺寸输出（一次导出 w×h 恒定）：消除每帧整帧 ARGB 分配（4K 每帧约 33MB）的 GC 卡顿。
        // 调用方（Mp4Exporter reuseOutput=true）feed 同步拷走 YUV 后不再回收本缓冲，下一帧原地重绘。
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
        // 逐帧导出：强制重算玻璃（视频帧内容逐帧变），跨帧复用玻璃输出位图省分配
        frameCache.invalidateKey()
        WatermarkRenderer.render(c, src, dst, EditorState.renderParams(), density, unit, frameCache,
            performanceProfile.videoFrameEdge,
            photoPreRotated = true, skipGlassBody = skipGlassBody)  // GPU 前景纹理跳过玻璃本体（黑色残影→黑底），模糊背景由着色器提供
        return out
    }

    /** GPU 蒙版：玻璃条形状+阴影的 alpha 蒙版（形状内白色，阴影灰色渐变），着色器据此识别玻璃区域 */
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
        // v14.67 关键修复：距离场【不再写进 mask 蓝通道】——真机 Adreno 上 mask alpha 读回为 0 时
        // 着色器用 max(r,g,b) 兜底识别玻璃区，蓝色被占用会使边缘区（蓝=0）识别失败 →
        // 玻璃边缘/高光/折射全丢（真机"磨砂贴图、无折射无高光"根因）。距离场改走独立纹理 sDist。
        return out
    }

    // v14.67：独立低分辨率"到边缘距离场"位图（几何缓存，导出期间零开销），
    // GPU 高度场/斯涅尔数据源——不污染 mask 的 RGB 局部 Y 渐变
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
                px[i++] = 0xFF000000.toInt() or (v shl 16) or (v shl 8) or v   // 灰度：距离场
            }
        }
        bmp.setPixels(px, 0, dw, 0, 0, dw, dh)
        return bmp
    }

    fun invalidateBlur() { cache.reset(); invalidate() }
    // v14.26 流体预览：液态玻璃扭曲随时间流动 → 视图持续重绘（25fps，仅挂载时）。
    // 停用/离开视图自动停止，不占后台资源。
    private val animHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val animTick = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow || !isEnabled) return
            // v14.29：仅流体开关开启时持续重绘（液态流动）；v14.32：关闭后停止循环（不空转 25Hz）
            // v14.96：呼吸开启且强度>0 时同样持续重绘（扭曲振幅随时间起伏，需时钟驱动）
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
        // v15.28：导出线程持有本 View 引用并按帧调用 renderWatermarkOnly/renderMotionFrame，
        // 这些方法读同一批复用位图（wmOut/outFrame/gifFrame/rotatedSrc 等）。Activity 重建期间
        // onDetachedFromWindow 若在导出中无条件 recycle，导出线程下一帧就会写到已回收位图
        // （IllegalStateException 被外层 catch 静默吞掉 → 导出突然失败）。导出进行中跳过回收，
        // 缓冲区随进程释放（导出结束由调用方 clearExportCache 兜底），避免竞态。
        if (EditorState.isExporting) return
        cache.reset()
        runCatching {
            gifFrame?.recycle(); outFrame?.recycle(); wmOut?.recycle(); maskOut?.recycle()
            frameRaw?.recycle(); rotatedSrc?.recycle()
            placeBmp?.recycle(); exportSnapshot?.recycle(); backdropBmp?.recycle()
            // v15.24：距离场位图原先漏回收，View detach 后残留一张 256×h 的 ARGB_8888
            distFieldBmp?.recycle()
        }
        gifFrame = null; outFrame = null; wmOut = null; maskOut = null
        frameRaw = null; rotatedSrc = null; gifCanvas = null; placeBmp = null; exportSnapshot = null
        backdropBmp = null; backdropFor = null
        distFieldBmp = null; distFieldKey = ""
    }
}
