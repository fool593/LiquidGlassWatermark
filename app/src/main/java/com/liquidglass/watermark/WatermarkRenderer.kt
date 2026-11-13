package com.liquidglass.watermark

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 液态玻璃水印渲染器（v3，玻璃算法对齐开源 Kyant0/AndroidLiquidGlass 2.0.0）。
 * 预览与导出走完全相同的代码路径，仅通过 unit（导出倍率）区分，保证所见即所得。
 * 玻璃本体（模糊/边缘折射/色散/反光/内阴影/染色）全部由 [LiquidGlassEngine] 在源像素空间
 * 一次性合成，API26-34 同一路径；本类只负责几何测量、形状轮廓、外投影、棱光与文字排版。
 * 所有几何运算均做安全兜底，任何图片比例 / 参数组合都不会崩溃。
 *
 * 线程安全（v11.7）：Paint/RectF/Path 非线程安全，导出线程逐帧全分辨率渲染时若与 UI 线程
 * 预览共用同一批对象会互相污染；改为每调用独立 [Ctx]，并移除对象级 @Synchronized，
 * 导出期间 UI 预览不再被长锁阻塞。
 */
object WatermarkRenderer {
    /** Material/text dimensions live in a 393-wide photo coordinate system, not screen dp.
     * The caller still supplies device density for the existing renderer API; this unit cancels
     * it so preview, snapshots, still exports and every video frame share normalized geometry.
     */
    fun unitForPhotoWidth(photoWidthPx: Float, deviceDensity: Float): Float {
        val width = photoWidthPx.takeIf { it.isFinite() && it > 0f } ?: 393f
        val density = deviceDensity.takeIf { it.isFinite() && it > 0f } ?: 1f
        return width / 393f / density
    }

    /** 最近一次渲染的水印条屏幕矩形（只读回传），供 View 命中判定：只有按在水印上才拖动 */
    private val lastRectLocal = ThreadLocal<RectF>()
    val lastWatermarkRect: RectF
        get() = lastRectLocal.get() ?: RectF().also(lastRectLocal::set)

    /** 品牌 logo 位图缓存（assets + 自定义文件），键=asset路径/文件路径；null 值=加载失败 */
    private val brandBitmapCache = java.util.concurrent.ConcurrentHashMap<String, Bitmap>()

    /**
     * 单次渲染的独立绘制状态（线程级复用）：
     * 预览（UI 线程）与导出（后台线程）各持一份，互不干扰；同一线程逐帧导出时
     * 不再每帧 new 一组 Paints/RectF/Path（逐帧 2000 帧 ≈ 2 万次对象分配的 GC 抖动来源）。
     */
    private val ctxLocal = ThreadLocal<Ctx>()
    /** v15.15：记录该线程 Ctx 创建时的字体模式；模式切换后重建 Ctx 使 typeface 即时生效 */
    private val ctxFontMode = ThreadLocal<Int>()
    /** v15.15：当前线程 Ctx 实际生效的字体模式（供诊断日志如实反映） */
    private fun ctxActiveFontMode(): Int = ctxFontMode.get() ?: EditorState.params.fontMode
    private fun ctx(): Ctx {
        val mode = EditorState.params.fontMode
        val c = ctxLocal.get()
        // 字体模式未变 → 复用（保持旧行为，零额外开销）
        if (c != null && ctxFontMode.get() == mode) return c
        val n = Ctx()
        ctxLocal.set(n)
        ctxFontMode.set(mode)
        return n
    }

    /** 按路径加载品牌 logo 位图（assets 或自定义文件），失败返回 null（不崩溃，回落矢量标识）。
     *  v14.40 埋点：每一环失败写日志（decode/EXIF/形状），日志直接定位"自定义 logo 不显示"卡点。 */
    private fun loadBrandBitmap(p: EditorState.Params, asset: String?): Bitmap? {
        val path = when {
            asset != null -> asset
            p.customLogoPath.isNotEmpty() -> p.customLogoPath
            else -> return null
        }
        val cacheKey = if (asset == null) "$path|${p.logoShape}|${p.logoCrop}" else path
        brandBitmapCache[cacheKey]?.takeUnless { it.isRecycled }?.let { return it }
        val bmp = try {
            if (asset != null) {
                App.appContext.assets.open(path).use {
                    val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                    BitmapFactory.decodeStream(it, null, opts)
                }
            } else {
                if (!java.io.File(path).exists()) {
                    DebugLog.log("LOGO", "自定义logo文件不存在: $path")
                    return null
                }
                val opts = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeFile(path, opts)
                val srcW = opts.outWidth; val srcH = opts.outHeight
                if (srcW <= 0 || srcH <= 0) {
                    DebugLog.log("LOGO", "自定义logo解码失败(0x0): $path")
                    return null
                }
                var sample = 1
                while (srcW / sample > 512 || srcH / sample > 512) sample *= 2
                opts.inJustDecodeBounds = false
                opts.inSampleSize = sample
                BitmapFactory.decodeFile(path, opts)
            }
        } catch (t: Throwable) {
            DebugLog.log("LOGO", "自定义logo解码异常 ${t.javaClass.simpleName}: ${t.message}")
            null
        }
        if (bmp == null && asset == null) {
            DebugLog.log("LOGO", "自定义logo解码结果为空（文件在，解码失败）")
            return null
        }
        // v14.30：相册自定义 logo 常带 EXIF 方向（如竖拍照片 EXIF 6/8），decodeFile 不应用 →
        // logo 横躺/倒置，观感就是"录不进去"。读出 Orientation 转正（翻转类少见，仅处理旋转）。
        var final_bmp = bmp
        if (bmp != null && asset == null) {
            val ori = runCatching {
                android.media.ExifInterface(path)
                    .getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION,
                        android.media.ExifInterface.ORIENTATION_NORMAL)
            }.getOrDefault(android.media.ExifInterface.ORIENTATION_NORMAL)
            val deg = when (ori) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (deg != 0f) {
                val rotated = runCatching {
                    val m = android.graphics.Matrix().apply { postRotate(deg) }
                    android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                }.getOrNull()
                if (rotated != null && rotated !== bmp) {
                    runCatching { bmp.recycle() }
                    final_bmp = rotated
                }
            }
        }
        // v14.70 修复"自定义 logo 污染其他 logo 大小比例"：形状/裁剪参数只作用于【自定义】logo，
        // 预设品牌（asset）永远只做内容包围盒裁剪（shape=0），绝不被用户给自定义选的方形/圆形裁剪影响
        val isCustomLogo = asset == null && p.customLogoPath.isNotEmpty()
        val shape = if (isCustomLogo) p.logoShape.coerceIn(0, 3) else 0
        val crop = if (isCustomLogo) p.logoCrop else true
        var shaped: Bitmap? = final_bmp
        runCatching {
            if (final_bmp != null) {
                if (!crop && shape == 0) {
                    // 不裁剪：原图整幅（含透明边）
                } else if (shape == 1 || shape == 3) {
                    val s = minOf(final_bmp.width, final_bmp.height)
                    val x = (final_bmp.width - s) / 2; val y = (final_bmp.height - s) / 2
                    var sq = runCatching {
                        android.graphics.Bitmap.createBitmap(final_bmp, x, y, s, s)
                    }.getOrNull() ?: final_bmp
                    if (sq !== final_bmp) runCatching { final_bmp.recycle() }
                    if (shape == 3) {
                        val out = android.graphics.Bitmap.createBitmap(s, s, android.graphics.Bitmap.Config.ARGB_8888)
                        val c = android.graphics.Canvas(out)
                        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                        c.drawCircle(s / 2f, s / 2f, s / 2f, p)
                        p.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
                        c.drawBitmap(sq, 0f, 0f, p)
                        if (sq !== final_bmp && !sq.isRecycled) runCatching { sq.recycle() }
                        shaped = out
                    } else {
                        shaped = sq
                    }
                } else {
                    shaped = if (crop) trimTransparent(final_bmp) else final_bmp
                    if (shape == 2 && shaped !== final_bmp) runCatching { final_bmp.recycle() }
                }
            }
        }
        if (shaped == null) shaped = final_bmp   // 兜底：任何异常都保证有图
        val trimmed = shaped
        if (trimmed != null) brandBitmapCache[cacheKey] = trimmed
        return trimmed
    }

    /**
     * 去掉 logo 四周透明边距，返回按【内容真实包围盒】裁剪的 bitmap。
     * 根因：所有品牌 png 都是 1024×1024 正方形画布，但小米/华为等图标内容只占中央一小块
     * （小米非透明像素仅 ~7%），旧逻辑按整个正方形算比例与绘制 → 视觉上 logo 缩成玻璃里一个小点。
     * 徕卡/蔡司等内容已占满画布（55%+），裁剪后包围盒≈原尺寸，视觉几乎不变（保持已认可效果）。
     */
    private fun trimTransparent(bmp: Bitmap): Bitmap {
        val w = bmp.width; val h = bmp.height
        if (w <= 2 || h <= 2) return bmp
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        var l = w; var t = h; var r = -1; var b = -1
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                if (px[row + x] ushr 24 != 0) {
                    if (x < l) l = x
                    if (x > r) r = x
                    if (y < t) t = y
                    if (y > b) b = y
                }
            }
        }
        if (r <= l || b <= t) return bmp
        val nw = r - l + 1; val nh = b - t + 1
        if (nw == w && nh == h) return bmp
        return try { Bitmap.createBitmap(bmp, l, t, nw, nh) } catch (_: Throwable) { bmp }
    }

    /** v14.44 自定义 logo 自检：加载并返回 (OK 尺寸 / 具体失败原因)，导入后即时反馈（不再静默失败） */
    fun debugSelfTestLogo(): String {
        val p = EditorState.params
        if (p.customLogoPath.isEmpty()) return "未设置自定义logo路径"
        if (!java.io.File(p.customLogoPath).exists()) return "文件不存在: ${p.customLogoPath}"
        clearBrandCache()
        val asset = if (p.logoBrand in EditorState.BRAND_ASSETS.indices)
            EditorState.BRAND_ASSETS[p.logoBrand] else null
        val bmp = loadBrandBitmap(p, asset)
        return if (bmp != null) "OK ${bmp.width}x${bmp.height}"
        else "解码/形状失败（见 LOGO 日志）"
    }

    /** 清空品牌位图缓存（自定义 logo 替换后调用）。v14.14：逐项回收，避免静态缓存残留 native 内存 */
    fun clearBrandCache() {
        brandBitmapCache.values.forEach { runCatching { it?.let { b -> if (!b.isRecycled) b.recycle() } } }
        brandBitmapCache.clear()
    }

    /** Per-thread render state. Reused so video frames do not allocate Paints/Rects/Params per call. */
    private class Ctx {
        val renderParams = EditorState.Params()
        private var rawDate = ""
        private var cachedDate = ""
        private var cachedLiveSeconds = true
        fun displayDate(value: String, liveSeconds: Boolean): String {
            if (rawDate != value || cachedLiveSeconds != liveSeconds) {
                rawDate = value
                cachedLiveSeconds = liveSeconds
                val suffix = value.length - 3
                cachedDate = if (!liveSeconds && suffix >= 0 && value[suffix] == ':' &&
                    value[suffix + 1] in '0'..'9' && value[suffix + 2] in '0'..'9') {
                    value.substring(0, suffix)
                } else value
            }
            return cachedDate
        }
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        // Bilinear filtering retains smooth coverage when a sampled optical patch is scaled.
        val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG).apply {
            typeface = resolveTypeface(EditorState.params.fontMode, bold = true)
            hinting = Paint.HINTING_OFF
        }
        val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG).apply {
            typeface = resolveTypeface(EditorState.params.fontMode, bold = false)
            hinting = Paint.HINTING_OFF
        }
        val wmRect = RectF()
        val tmpRect = RectF()
        val path = Path()
    }

    // ── v15.15 字体模式解析（含回退，绝不抛异常导致渲染中断）──
    /** 默认字体族（应用内置预设）：与改动前完全一致的 sans-serif */
    private val DEFAULT_TYPEFACE_BOLD: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    private val DEFAULT_TYPEFACE_NORMAL: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    /** v15.17 衬线/等宽字族缓存（键 = mode*2 + bold），避免每帧 create 新 Typeface */
    private val serifMonoTypefaces = java.util.concurrent.ConcurrentHashMap<Int, Typeface>()

    /** 系统字体族缓存（只解析一次；失败置 null 表示不可用） */
    @Volatile private var systemTypefaceBold: Typeface? = null
    @Volatile private var systemTypefaceNormal: Typeface? = null
    @Volatile private var systemTypefaceResolved = false

    /** 上一次解析出的字体名，供诊断日志打印（切换前后对比用） */
    @Volatile var lastResolvedTypefaceName: String = "未解析"
        private set

    /**
     * v15.15 字体模式解析。
     * - FONT_MODE_DEFAULT：应用内置 sans-serif（默认，与旧版一致）
     * - FONT_MODE_SYSTEM：读取设备系统字体族（Android 等价于桌面端的 NSFont/fontconfig 查询）
     *   平台差异说明：Android 无 SystemParametersInfo/注册表/NSFont/fontconfig，
     *   系统字体通过 Typeface.DEFAULT（系统默认字族，厂商定制字体生效）获取。
     * 系统字体不可用 → 自动回退默认字体，绝不中断渲染。
     */
    fun resolveTypeface(mode: Int, bold: Boolean): Typeface {
        // v15.17：衬线 / 等宽是 Android 恒定存在的字族，与 sans-serif 字形差异明显，
        // 让「字体切换」真的能看出变化（此前 默认=sans-serif、系统=Typeface.DEFAULT 在绝大多数
        // 设备上解析到同一字族，切换后画面完全没变 → 用户反馈"字体切换切换不了"）。
        if (mode == FONT_MODE_SERIF || mode == FONT_MODE_MONO) {
            val family = if (mode == FONT_MODE_SERIF) Typeface.SERIF else Typeface.MONOSPACE
            val style = if (bold) Typeface.BOLD else Typeface.NORMAL
            val tf = serifMonoTypefaces.getOrPut(mode * 2 + if (bold) 1 else 0) {
                runCatching { Typeface.create(family, style) }
                    .getOrDefault(if (bold) DEFAULT_TYPEFACE_BOLD else DEFAULT_TYPEFACE_NORMAL)
            }
            lastResolvedTypefaceName = (if (mode == FONT_MODE_SERIF) "serif" else "monospace") +
                if (bold) "-bold" else "-regular"
            return tf
        }
        if (mode != FONT_MODE_SYSTEM) {
            lastResolvedTypefaceName = "sans-serif" + if (bold) "-bold" else "-regular"
            return if (bold) DEFAULT_TYPEFACE_BOLD else DEFAULT_TYPEFACE_NORMAL
        }
        // 系统字体：只解析一次
        if (!systemTypefaceResolved) {
            synchronized(this) {
                if (!systemTypefaceResolved) {
                    runCatching {
                        systemTypefaceNormal = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                        systemTypefaceBold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    }
                    systemTypefaceResolved = true
                }
            }
        }
        val st = if (bold) systemTypefaceBold else systemTypefaceNormal
        return if (st != null) {
            lastResolvedTypefaceName = "系统字体" + if (bold) "(粗)" else "(常规)"
            st
        } else {
            // 回退：系统字体不可用 → 默认字体
            lastResolvedTypefaceName = "sans-serif(系统字体不可用，已回退)"
            if (bold) DEFAULT_TYPEFACE_BOLD else DEFAULT_TYPEFACE_NORMAL
        }
    }

    private class Ui(val w: Float, val h: Float, val titleSize: Float, val subSize: Float,
                     val padH: Float, val padV: Float, val sUnit: Float, val gap: Float,
                     val extend: Float = 1f)

    /**
     * 玻璃合成缓存：玻璃位图只依赖背景子块 + 玻璃参数 + 几何，与文字无关。
     * 走秒（只变时间文字）时可直接复用，拖动/改参数才重算。任何异常都回退 null（不崩）。
     * （对外保留 BlurCache 类名与 reset()，供 WatermarkEditorView 持有。）
     */
    class BlurCache {
        private var key = ""
        private var bmp: Bitmap? = null
        private var sharp: Bitmap? = null
        private val samplingPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val patch = RectF()

        fun obtain(photo: Bitmap, photoDst: RectF, wm: RectF, p: EditorState.Params,
                   radiusCanvas: Float, density: Float, sUnit: Float, maxEdge: Int,
                   rotation: Float = 0f, localW: Float = -1f, localH: Float = -1f,
                   photoRotation: Int = 0,
                   photoScaleX: Float = -1f, photoScaleY: Float = -1f): Bitmap? {
            if (photo.isRecycled || wm.width() <= 1f || wm.height() <= 1f ||
                photoDst.width() <= 0f || photoDst.height() <= 0f) return null
            val shapeW = if (localW > 0f) localW else wm.width()
            val shapeH = if (localH > 0f) localH else wm.height()
            val shortSide = min(shapeW, shapeH)
            // Blur and padding scale with the material, not the number of export pixels.
            val blurCanvas = p.blur.coerceIn(0, 100) / 60f * shortSide * .16f
            val pad = ceil(shortSide * .22f + blurCanvas + 2f)
            patch.set(wm.left - pad, wm.top - pad, wm.right + pad, wm.bottom + pad)
            val sc = min(1f, maxEdge.coerceAtLeast(64).toFloat() / max(patch.width(), patch.height()))
            val bw = max(2, ceil(patch.width() * sc).toInt())
            val bh = max(2, ceil(patch.height() * sc).toInt())
            val sx = bw / patch.width()
            val sy = bh / patch.height()
            val speed = p.fluidSpeed.coerceIn(1, 100)
            val timeSec = (if (EditorState.isExporting) EditorState.exportTimeSec
                else EditorState.mediaPreviewTimeSec).takeIf { it.isFinite() } ?: 0f
            val phase = if (p.fluidEnabled) timeSec * (.6f + speed / 100f * 4f) else 0f
            val breath = if (p.breathEnabled && p.breathStrength > 0) {
                val swell = sin(timeSec * (.5f + speed / 100f * 1.3f)).coerceAtLeast(0f)
                val floor = 1f - .62f * p.breathStrength.coerceIn(0, 100) / 100f
                floor + (1f - floor) * swell * swell
            } else 1f
            // v15.16：缩小补偿——按渲染后形状短边（设备像素）计算光学放大倍率，
            // 使水印被缩小渲染（小视口/小缩放/低分辨率）时仍保留可见的折射环与边缘厚度。
            val renderedShortSide = shortSide * ((sx + sy) * .5f)
            val effectScale = LiquidGlassEngine.scaleCompensation(
                renderedShortSide, p.scaleComp.coerceIn(0, 100) / 100f)
            val tier = if (p.glassTier == EditorState.GLASS_TIER_LOW)
                LiquidGlassEngine.TIER_LOW else LiquidGlassEngine.TIER_HIGH
            // generationId changes when an animated decoder reuses its Bitmap for a new frame.
            val nextKey = "${System.identityHashCode(photo)}:${photo.generationId}|$photoDst|$wm|" +
                "$rotation,$photoRotation,$shapeW,$shapeH,$radiusCanvas,$bw,$bh,$phase,$breath|" +
                "${p.blur},${p.refractH},${p.refractA},${p.edgeWarp},${p.innerWarp},${p.chromatic},${p.dispersion}," +
                "${p.depthEffect},${p.vibrancy},${p.specular},${p.highlightStyle},${p.hlAngle},${p.falloff}," +
                "${p.tintColor},${p.tintStrength},${p.innerShadow},${p.edgeLight},${p.glassContrast}," +
                "${p.shape},${p.cornerPct},${p.refractionModel},$effectScale,$tier"
            if (key == nextKey) bmp?.takeUnless { it.isRecycled }?.let { return it }
            var sample = sharp
            if (sample == null || sample.isRecycled || sample.width != bw || sample.height != bh) {
                sharp?.takeUnless { it.isRecycled }?.recycle()
                sample = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                sharp = sample
            }
            // Map source -> displayed photo -> local patch, retaining the exact transform at edges.
            // CLAMP extends edge pixels into padding without stretching a clipped crop over the bar.
            val sourceRect = RectF(0f, 0f, photo.width.toFloat(), photo.height.toFloat())
            // Patch pixels map back to their full-frame coordinates by the actual X/Y readback
            // ratios. Treating the reduced bitmap size as full-frame coordinates shifts/shrinks
            // the glass sample and makes export differ from the full-frame preview.
            val sourceDst = if (photoScaleX > 0f && photoScaleY > 0f)
                RectF(0f, 0f, photo.width / photoScaleX, photo.height / photoScaleY)
            else RectF(photoDst)
            val rot = ((photoRotation % 360) + 360) % 360
            if (rot % 180 != 0) {
                sourceDst.set(photoDst.centerX() - photoDst.height() / 2f,
                    photoDst.centerY() - photoDst.width() / 2f,
                    photoDst.centerX() + photoDst.height() / 2f,
                    photoDst.centerY() + photoDst.width() / 2f)
            }
            val matrix = Matrix().apply {
                setRectToRect(sourceRect, sourceDst, Matrix.ScaleToFit.FILL)
                if (rot != 0) postRotate(rot.toFloat(), photoDst.centerX(), photoDst.centerY())
                postTranslate(-patch.left, -patch.top)
                postScale(sx, sy)
            }
            samplingPaint.shader = android.graphics.BitmapShader(photo, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                .apply { setLocalMatrix(matrix) }
            Canvas(sample).apply {
                drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
                drawRect(0f, 0f, bw.toFloat(), bh.toFloat(), samplingPaint)
            }
            samplingPaint.shader = null
            val output = LiquidGlassEngine.compose(sample,
                ((wm.left - patch.left) * sx).toInt(), ((wm.top - patch.top) * sy).toInt(),
                (wm.width() * sx).toInt().coerceAtLeast(2), (wm.height() * sy).toInt().coerceAtLeast(2),
                radiusCanvas * (sx + sy) * .5f, (blurCanvas * (sx + sy) * .5f).toInt(),
                p.refractH / 100f, p.refractA / 100f,
                (p.chromatic / 100f * (1f + p.dispersion / 100f)).coerceIn(0f, 2f),
                p.edgeWarp / 100f, p.innerWarp / 100f, p.depthEffect, p.vibrancy / 100f,
                p.highlightStyle, p.specular / 100f, (p.hlAngle * PI / 180f).toFloat(), p.falloff,
                p.tintColor, p.tintStrength / 100f, p.innerShadow / 100f, p.glassContrast,
                (shapeW * sx).toInt().coerceAtLeast(2), (shapeH * sy).toInt().coerceAtLeast(2),
                rotation, bmp, phase, p.refractionModel, breath, p.edgeLight / 100f,
                ellipse = p.shape == EditorState.SHAPE_ELLIPSE,
                effectScale = effectScale, tier = tier)
            if (output != null) {
                if (output !== bmp) bmp?.takeUnless { it.isRecycled }?.recycle()
                bmp = output
                key = nextKey
            }
            return output
        }

        fun invalidateKey() { key = "" }
        fun reset() {
            bmp?.takeUnless { it.isRecycled }?.recycle()
            sharp?.takeUnless { it.isRecycled }?.recycle()
            bmp = null; sharp = null; key = ""
        }
    }
    private fun alpha(pct: Int, scale: Float = 1f): Int =
        (pct / 100f * 255f * scale).toInt().coerceIn(0, 255)

    private fun modulateColorAlpha(color: Int, scale: Float): Int =
        Color.argb((Color.alpha(color) * scale).toInt().coerceIn(0, 255),
            Color.red(color), Color.green(color), Color.blue(color))

    private fun modulateOpacity(alpha: Int, scale: Float): Int =
        (alpha * scale).toInt().coerceIn(0, 255)

    /** 与 buildGlassPath 一致的圆角半径（画布像素） */
    private fun glassRadius(p: EditorState.Params, w: Float, h: Float): Float {
        val halfH = h / 2f
        return when (p.shape) {
            EditorState.SHAPE_RECT -> 0f
            EditorState.SHAPE_CAPSULE -> min(w, h) / 2f
            EditorState.SHAPE_ELLIPSE -> min(w, h) / 2f
            else -> halfH * (p.cornerPct.coerceIn(0, 100) / 100f)
        }.coerceIn(0f, min(w, h) / 2f)
    }

    /** 左侧标识是否显示：品牌位图启用，或 logoStyle 非 NONE（品牌位图独立于矢量 logoStyle） */
    private fun hasLogo(p: EditorState.Params): Boolean =
        p.logoStyle != EditorState.LOGO_NONE || p.logoBrand != EditorState.BRAND_NONE

    /**
     * 品牌位图宽高比（横长字标 Google/OPPO/真我 等 >1，方形相机标 ≈1）。
     * 用于按真实比例预留水平槽位——旧逻辑统一按方形槽 contain，横长字标被槽宽压死高度、
     * 调到最大仍极小。无位图（矢量圆点/相机标）返回 1。clamp 0.5..6 防异常素材。
     */
    private fun logoAspect(p: EditorState.Params): Float {
        if (p.logoBrand == EditorState.BRAND_NONE && p.customLogoPath.isEmpty()) return 1f
        val asset = if (p.logoBrand in EditorState.BRAND_ASSETS.indices)
            EditorState.BRAND_ASSETS[p.logoBrand] else null
        val bmp = loadBrandBitmap(p, asset) ?: return 1f
        if (bmp.width <= 0 || bmp.height <= 0) return 1f
        return (bmp.width.toFloat() / bmp.height.toFloat()).coerceIn(0.5f, 6f)
    }


    /** 拆分时间文本为「年-月-日」与「时:分:秒」两段（无空格则整行归日期段） */
    private fun splitDateText(s: String): Pair<String, String> {
        val t = s.trim()
        if (t.isEmpty()) return "" to ""
        val sp = t.indexOf(' ')
        if (sp > 0) return t.substring(0, sp) to t.substring(sp + 1)
        return t to ""
    }

    private fun measure(c: Ctx, p: EditorState.Params, density: Float, sUnit: Float,
                        compact: Float = 1f, extend: Float = 1f): Ui {
        val titleSize = p.titleSp * density * sUnit
        val subSize = p.subSp * density * sUnit
        // Logo, text, padding and separators share the same overall scale. brandScale remains
        // an independent multiplier applied when the logo is drawn inside its safe box.
        val logoUnit = sUnit
        val pixelUnit = density * logoUnit
        // compact<1 时压缩留白与间隔（长水印优先紧凑排版，字号尽量不缩小）
        // padH 随 extend：纯文字水印（无 logo）靠左右留白撑宽，长度滑块对任何布局都生效
        var padH = (p.padHDp * density * sUnit * (0.5f + 0.5f * compact) * extend).coerceIn(0f, 2000f * pixelUnit)
        val padV = (p.padVDp * density * sUnit * (0.5f + 0.5f * compact)).coerceIn(0f, 2000f * pixelUnit)
        c.titlePaint.textSize = titleSize
        c.subPaint.textSize = subSize
        c.titlePaint.color = p.textColor
        c.subPaint.color = p.textColor   // 与 drawContent 一致全不透明（旧 214 alpha 会让副文字发灰）
        val nameW = if (p.model.isNotEmpty()) c.titlePaint.measureText(p.model) else 0f
        val codeW = if (p.modelCode.isNotEmpty()) c.subPaint.measureText(p.modelCode) else 0f
        val timeW = if (p.dateText.isNotEmpty()) c.subPaint.measureText(p.dateText) else 0f
        // 长度=固定宽度：extend 等比放大 logo/分隔线/左右留白来适配目标宽度（字号不变形、不变小）。
        // gap（元素间隔）不随 extend 放大——否则拉长时元素之间出现空洞（用户历次反馈的"中间大片空白"根因）
        val gap = (5f * density * sUnit) * compact
        val titleH = titleSize * 1.22f
        val subH = subSize * 1.25f
        val subLines = (if (codeW > 0f) 1 else 0) + (if (timeW > 0f) 1 else 0)
        val textBlockH = titleH + if (subLines > 0) subSize * 0.30f * subLines + subH * subLines else 0f
        val textW = max(nameW, max(codeW, timeW))
        // 品牌位图真实宽高比：横长字标按宽矩形预留槽位（方形标≈1，视觉不变）
        val asp = if (hasLogo(p)) logoAspect(p) else 1f
        val contentW: Float
        val contentH: Float
        when (p.layoutMode) {
            EditorState.LAYOUT_SINGLE -> {
                // logo 占位几何用 brandScale=1 基准：玻璃条宽高不随 logo 大小滑块变化；
                // 实际 logo 绘制时才乘 brandScale（在条内独立缩放，见 drawContent）。
                val logoSingle = p.titleSp * density * logoUnit * 1.5f
                val logoW = if (hasLogo(p)) logoSingle * asp + gap * 2f else 0f
                contentW = logoW + nameW
                contentH = max(max(titleSize, subSize),
                    if (hasLogo(p)) logoSingle else 0f) * 1.35f
            }
            EditorState.LAYOUT_STACK -> {
                contentW = textW
                contentH = textBlockH
            }
            EditorState.LAYOUT_SPLIT -> {
                // 方案2（110% 还原参考红米徕卡水印）：机型大字最左 → logo → 短竖线 → 时间两行最右
                // 独立字号基准（机型×0.38、时间=subSize×0.30）。拉长走"两端分布"：
                // 机型固定最左、logo/竖线/时间整体最右，中间用弹性间距 flexGap 撑开（字号/logo 恒定）。
                padH = p.padHDp * density * sUnit * 0.3f
                val t2 = titleSize * 0.38f
                val s2 = subSize * 0.30f
                c.titlePaint.textSize = t2
                val splitNameW = if (p.model.isNotEmpty()) c.titlePaint.measureText(p.model) else 0f
                c.titlePaint.textSize = titleSize
                c.subPaint.textSize = s2
                // v15.18：方案2 也显示第二行型号，宽度/高度计入几何，避免被裁掉
                val splitCodeW = if (p.modelCode.isNotBlank()) c.subPaint.measureText(p.modelCode) else 0f
                val (splitD, splitT) = splitDateText(p.dateText)
                val splitD_W = if (splitD.isNotEmpty()) c.subPaint.measureText(splitD) else 0f
                val splitT_W = if (splitT.isNotEmpty()) c.subPaint.measureText(splitT) else 0f
                c.subPaint.textSize = subSize
                val timeBlockW = max(splitD_W, splitT_W)
                // 右组内部紧凑间距（不随 extend，保持 logo→竖线→时间 紧凑）
                val splitGap = gap * 0.5f
                val logoSize = p.titleSp * density * logoUnit * 0.38f * 1.2f
                val vw = max(1.5f * pixelUnit, 2.2f * density * sUnit)
                var rightW = timeBlockW
                // logo 水平槽位 = 高度基准 × 位图宽高比（横长字标给足宽，方形标不变）。
                // 槽位封顶：横长蔡司/徕卡标 asp 可到 5+，槽位过宽会把竖线/时间挤出条外
                // （用户反馈"logo 跑最右、分隔符和时间没了"）——槽宽上限 = 高度基准 × 2.6，
                // logo 绘制时超宽自动回缩，竖线/时间永远留在条内。
                if (hasLogo(p)) {
                    val logoSlot = min(logoSize * asp, logoSize * 2.6f)
                    rightW += logoSlot + splitGap * 2f + vw + splitGap * 2f
                }
                // 中间弹性间距：extend=1 时=splitGap*2（自然紧凑，与历史视觉一致），拉长线性增大
                val flexGap = splitGap * 2f * extend.coerceAtLeast(0.2f)
                val leftW = max(splitNameW, splitCodeW)
                val leftH = t2 + if (splitCodeW > 0f) s2 * 1.25f else 0f
                contentW = leftW + flexGap + rightW
                contentH = max(max(leftH, s2 * 2f),
                    if (hasLogo(p)) logoSize else 0f) * 1.35f
            }
            else -> {
                // logo 只随品牌大小与分辨率（不随整体缩放/长度放大）；分隔线宽度随长度放大但封顶
                // logo 占位几何用 brandScale=1 基准（玻璃条不随 logo 大小滑块变化）
                val logoClassic = p.titleSp * density * logoUnit * 1.65f
                val logoW = if (hasLogo(p)) logoClassic * asp + gap * 2f else 0f
                if (p.logoAlign == 2 && hasLogo(p)) {
                    // 居中纯 logo：玻璃几何同样固定（brandScale=1 基准），滑块只缩放 logo、不撑大玻璃
                    contentW = logoW
                    contentH = max(logoClassic, textBlockH)
                } else if (p.logoAlign == 1 && hasLogo(p)) {
                    // 后置：文字左 + 分隔线 + logo 右；分隔线宽随 extend 线性增长（不封顶，否则长度滑块拉不到满宽）
                    val divW = max(pixelUnit, 1.4f * density * sUnit * extend) + gap * 2f
                    contentW = textW + divW + logoW
                    contentH = max(logoClassic, textBlockH)
                } else {
                    // 前置（默认，第一布局）：机型最左 + logo + 竖线 + 时间竖排（年最上）
                    val (dPart, tPart) = splitDateText(p.dateText)
                    val dW = if (dPart.isNotEmpty()) c.subPaint.measureText(dPart) else 0f
                    val tW2 = if (tPart.isNotEmpty()) c.subPaint.measureText(tPart) else 0f
                    val timeBlockW = max(dW, tW2)
                    val leftW = max(nameW, codeW)
                    val divW = if (logoW > 0f) max(pixelUnit, 1.4f * density * sUnit * extend) + gap * 2f else 0f
                    contentW = leftW + gap * 2f + logoW + divW + timeBlockW
                    val leftH = titleH + if (codeW > 0f) subSize * 0.30f + subH else 0f
                    val timeH = subSize * 0.30f + subH * 2f
                    contentH = max(max(leftH, timeH), if (logoW > 0f) logoClassic else 0f) * 1.35f
                }
            }
        }
        return Ui(contentW + padH * 2, contentH + padV * 2, titleSize, subSize, padH, padV, sUnit, gap, extend)
    }

    /**
     * @param photoPreRotated 照片是否已由调用方旋转/对齐好（GIF/动图/视频帧导出路径，
     *        帧或画布已按目标方向就绪）。true 时跳过 renderInner 的照片旋转与 photoDst 改写，
     *        否则已旋转的帧会被二次旋转（180° 错位/比例怪异——历史方向 bug 根因）。
     */
    fun render(canvas: Canvas, photo: Bitmap, photoDst: RectF,
               p: EditorState.Params, density: Float, unit: Float,
               cache: BlurCache?, maxBlurEdge: Int, photoPreRotated: Boolean = false,
               maskOnly: Boolean = false, skipGlassBody: Boolean = false,
               lite: Boolean = false,
               skipBackground: Boolean = false, photoScaleX: Float = -1f, photoScaleY: Float = -1f) {
        val c = ctx()
        try {
            // Visibility formatting is local to this thread; never mutate the editor/export snapshot.
            val drawParams = c.renderParams.apply { copyFrom(p) }
            renderInner(c, canvas, photo, photoDst, drawParams, density, unit, cache, maxBlurEdge,
                photoPreRotated, maskOnly, skipGlassBody, lite, skipBackground, photoScaleX, photoScaleY)
        } catch (t: Throwable) {
            // 渲染兜底：至少把原图画出，绝不因水印异常导致闪退（重算 photoDst 防污染矩形）
            runCatching { canvas.drawBitmap(photo, null, photoDst, c.bmpPaint) }
        }
    }

    private fun renderInner(c: Ctx, canvas: Canvas, photo: Bitmap, photoDst: RectF,
                            p: EditorState.Params, density: Float, unit: Float,
                            cache: BlurCache?, maxBlurEdge: Int,
                            photoPreRotated: Boolean = false,
                            maskOnly: Boolean = false,
                            skipGlassBody: Boolean = false,
                            lite: Boolean = false,
                            skipBackground: Boolean = false,
                            photoScaleX: Float = -1f, photoScaleY: Float = -1f) {
        if (photoDst.width() <= 2f || photoDst.height() <= 2f) return
        if (photo.isRecycled) return
        c.bmpPaint.alpha = 255
        // —— 显示开关消费（showDevice/showTime/liveSeconds 此前 Toggle 存在但渲染层未读=死控件）：
        //    临时改写 p.model/p.dateText，measure+drawContent 无需改动；finally 恢复，不污染共享状态 ——
        val origModel = p.model
        val origCode = p.modelCode
        val origDate = p.dateText
        try {
            // v15.18：showDevice 同时隐藏机型名称与型号代号
            if (!p.showDevice) { p.model = ""; p.modelCode = "" }
            if (!p.showTime) p.dateText = ""
            else p.dateText = c.displayDate(p.dateText, EditorState.liveSeconds)
        // —— 渲染诊断日志（节流）：记录实际绘制的水印内容，定位"机型文字消失/错乱"根因 ——
        if (!EditorState.isExporting) {
            DebugLog.throttleLog(
                "RENDER",
                "渲染 lite=$lite mode=${p.layoutMode} rot=${p.rotation} " +
                "model=\"${p.model}\" code=\"${p.modelCode}\" date=\"${p.dateText}\" " +
                "showDev=${p.showDevice} showTime=${p.showTime} live=${EditorState.liveSeconds} " +
                "dst=${photoDst.width().toInt()}x${photoDst.height().toInt()}", 400L)
        }
        // —— 照片旋转：0/90/180/270，旋转后重算显示区域，水印基于旋转后的区域定位 ——
        //    maskOnly 时跳过背景绘制：canvas 与 photo 是同一张复用位图（renderMaskOnly 自绘），
        //    Android 自绘未定义行为会导致 mask 花屏/错乱；mask 只需要形状+阴影，无需背景
        val rot = ((EditorState.photoRotation % 360) + 360) % 360
        if (maskOnly) {
            // 只算 photoDst（水印定位基于它），不画背景
        } else if (skipBackground) {
            // GPU patch 模式：视频背景已由 GPU 直绘到编码器表面，这里仅渲染水印层
        } else if (photoPreRotated) {
            // 帧已按目标方向就绪（调用方完成旋转），仅直绘，禁止二次旋转
            canvas.drawBitmap(photo, null, photoDst, c.bmpPaint)
        } else if (rot == 0) {
            canvas.drawBitmap(photo, null, photoDst, c.bmpPaint)
        } else {
            val pw = photo.width; val ph = photo.height
            val rw = if (rot % 180 != 0) ph else pw
            val rh = if (rot % 180 != 0) pw else ph
            val cx = photoDst.centerX(); val cy = photoDst.centerY()
            val availW = photoDst.width(); val availH = photoDst.height()
            val s = minOf(availW / rw.toFloat(), availH / rh.toFloat())
            val dw = rw * s; val dh = rh * s
            // 旋转后的显示区域（水印基于此区域定位）。
            // L3 契约说明：这里会【原地改写】调用方传入的 photoDst（改为旋转后的显示区域），
            // 调用方不要复用该 RectF 作为"原始未旋转区域"；如需保留原值请自行传入副本。
            photoDst.set(cx - dw / 2f, cy - dh / 2f, cx + dw / 2f, cy + dh / 2f)
            // 旋转坐标系下原始照片的绘制矩形（宽高对应原始比例）
            val srcDrawW = if (rot % 180 != 0) dh else dw
            val srcDrawH = if (rot % 180 != 0) dw else dh
            val srcDrawRect = RectF(cx - srcDrawW / 2f, cy - srcDrawH / 2f,
                cx + srcDrawW / 2f, cy + srcDrawH / 2f)
            canvas.save()
            canvas.rotate(rot.toFloat(), cx, cy)
            canvas.drawBitmap(photo, null, srcDrawRect, c.bmpPaint)
            canvas.restore()
        }

        // —— 两维独立：
        //    缩放（scalePct）= 水印整体几何、字号、logo、线宽与轻微透明度变化；
        //    长度（wmLengthPct）= 条宽目标（1x=预览框宽），拉长/缩短只调整分隔线宽度（封顶）与
        //    左右留白（extend），字号与 logo 恒定 → 不空洞、不变小、logo 始终留在玻璃条内 ——
        val scaleFactor = WatermarkScale.factor(p.scalePct)
        val contentAlphaScale = WatermarkScale.opacityMultiplier(scaleFactor)
        val sUnit = unit * scaleFactor
        // Overall scale applies to both the content and its length target. Otherwise the minimum
        // length stretch keeps the glass bar at the same width while only its text gets smaller.
        val targetW = photoDst.width() * p.wmLengthPct.coerceAtLeast(0.01f) * scaleFactor
        var ui = measure(c, p, density, sUnit)
        if (ui.w <= 0f) return
        var extend = 1f
        if (p.layoutMode == EditorState.LAYOUT_SPLIT) {
            // 方案2长度：滑块 t∈[0,1] 线性映射"自然紧凑 → 满宽(99%)"，整段行程都有效、无死区。
            //  t=0 自然紧凑（机型左/右组右，中间仅基础间距）；t=1 恰好满宽。
            //  拉长只增大中间 flexGap：机型贴最左、logo/竖线/时间整体贴最右且内部贴紧，字号/logo 恒定。
            val naturalW = ui.w
            val maxW = photoDst.width() * 0.99f * scaleFactor
            val t = p.wmLengthPct.coerceIn(0f, 1f)
            val targetSplitW = naturalW + (maxW - naturalW) * t
            if (t > 0.001f && naturalW > 0f) {
                val baseFlex = 5f * density * sUnit // = gap = splitGap*2（extend=1 时中间距）
                extend = (1f + (targetSplitW - naturalW) / baseFlex).coerceAtLeast(0.2f)
                ui = measure(c, p, density, sUnit, extend = extend)
            }
        } else if (ui.w < targetW) {
            // 拉长：extend 迭代到目标宽（一次近似+一次精修，字号/logo 不变；上限放宽到 60 让长度拉得满）
            extend = (targetW / ui.w).coerceIn(1f, 30f)
            ui = measure(c, p, density, sUnit, extend = extend)
            if (ui.w < targetW && ui.w > 0f) {
                extend = (extend * targetW / ui.w).coerceIn(1f, 60f)
                ui = measure(c, p, density, sUnit, extend = extend)
            }
        } else if (ui.w > targetW && p.wmLengthPct < 0.999f) {
            // 仅显式缩短才压缩 extend；wmLengthPct≥1（默认/拉长）时 scalePct 放大后的宽条保留
            extend = (targetW / ui.w).coerceIn(0.2f, 1f)
            ui = measure(c, p, density, sUnit, extend = extend)
        }
        // 水印尺寸封顶画布：scalePct 无限调大时最多占满画面。
        // 方案2 主动拉长到满宽（t=1 → 恰 99% 屏宽）不回缩；仅当字号本身过大、连最紧凑布局都超屏
        // （右组时间会被推出画面，录屏可见残字）才等比缩小防护。其余布局宽高都按 96% 封顶。
        val overH = ui.h > photoDst.height() * 0.96f
        val overW = ui.w > photoDst.width() * 0.99f
        val needScale = if (p.layoutMode == EditorState.LAYOUT_SPLIT) overH || overW
            else (ui.w > photoDst.width() * 0.96f || overH)
        if (needScale) {
            val k = if (p.layoutMode == EditorState.LAYOUT_SPLIT)
                minOf(photoDst.height() * 0.96f / ui.h, photoDst.width() * 0.99f / ui.w)
            else minOf(photoDst.width() * 0.96f / ui.w, photoDst.height() * 0.96f / ui.h)
            ui = measure(c, p, density, sUnit * k, extend = extend)
        }

        var cx = photoDst.left + p.relX.coerceIn(0f, 1f) * photoDst.width()
        var cy = photoDst.top + p.relY.coerceIn(0f, 1f) * photoDst.height()
        // 旋转后屏幕轴对齐包围盒（AABB）：玻璃背景按此轴对齐采样，方向永远与照片一致、
        // 覆盖旋转后玻璃条扫过的全部区域（旧实现采样矩形不随旋转变 + 缓存 key 无 rotation，
        //  旋转后玻璃透出错误方向的旧背景、像静态图，必须拖动位置改 key 才刷新 = 用户反馈的根因）
        val wmRot = (((p.rotation % 360) + 360) % 360).toFloat()
        val rotRad = Math.toRadians(wmRot.toDouble())
        val cosA = Math.abs(Math.cos(rotRad)).toFloat()
        val sinA = Math.abs(Math.sin(rotRad)).toFloat()
        val aw = ui.w * cosA + ui.h * sinA
        val ah = ui.w * sinA + ui.h * cosA
        // 关键：按旋转后 AABB 把水印中心约束在照片/预览框内。拖动点 relX/relY 只标记期望中心，
        // 水印自身有半宽半高，不约束时中心贴边会让整条水印溢出框外（用户反馈"能拖到预览框下方"）。
        // 约束后拖到边缘自动贴边、始终完整可见；水印比画面还大时退化为居中。
        if (aw <= photoDst.width()) cx = cx.coerceIn(photoDst.left + aw / 2f, photoDst.right - aw / 2f)
        else cx = photoDst.centerX()
        if (ah <= photoDst.height()) cy = cy.coerceIn(photoDst.top + ah / 2f, photoDst.bottom - ah / 2f)
        else cy = photoDst.centerY()
        // 局部玻璃矩形（未旋转，中心 cx,cy）：形状/文字布局基准
        val drawRect = RectF(cx - ui.w / 2f, cy - ui.h / 2f, cx + ui.w / 2f, cy + ui.h / 2f)
        c.wmRect.set(drawRect)
        // 回传最近一次水印条屏幕矩形，供 View 做"只有按在水印上才拖动"的命中判定
        lastWatermarkRect.set(drawRect)
        val sampleRect = RectF(cx - aw / 2f, cy - ah / 2f, cx + aw / 2f, cy + ah / 2f)
        // 形状 path 在局部坐标构建后烘焙旋转到屏幕坐标（玻璃位图轴对齐绘制，clip/描边用烘焙 path）
        buildGlassPath(c.path, p, drawRect, ui.h / 2f)
        if (wmRot != 0f) c.path.transform(Matrix().apply { setRotate(wmRot, cx, cy) })

        // 1. 柔和外投影（ponytail: 透明度随 opacity 衰减，opacity=0 时完全不可见，之前阴影不受 opacity 控制）
        // v14.03：GPU 导出前景/蒙版纹理不画黑色外投影——否则会把纯黑阴影写进透明纹理，
        // 着色器叠加后水印条外围一圈发黑（"导出水印周围冒出来的东西"）。
        // v14.09：恢复【蒙版纹理】的外投影（fg 前景纹理仍跳过）：蒙版只画形状+阴影，
        // 着色器改为"形状 alpha 叠玻璃本体 + 阴影区单独压暗视频"（去掉阴影区掺玻璃体的
        // 旧逻辑）→ 导出的软阴影与预览一致，且不再有旧版"外围发黑"。
        if (p.shadow > 0 && p.opacity > 0 && !skipGlassBody) {
            c.fillPaint.reset(); c.fillPaint.isAntiAlias = true
            c.fillPaint.style = Paint.Style.FILL; c.fillPaint.color = Color.BLACK
            // v15.1 投影加强：alpha 系数 0.66→0.78、模糊 0.07+0.20→0.08+0.22——压住"贴纸效应"，
            // 让大水印也明显"浮"在画面上（用户：边缘连一丝阴影都没有）
            val strength = p.shadow.coerceIn(0, 100) / 100f
            val shadowAlpha = alpha(p.shadow,
                .48f * p.opacity.coerceIn(0, 100) / 100f * contentAlphaScale)
            val light = p.hlAngle.takeIf { it.isFinite() } ?: 45f
            val angle = light * PI / 180.0
            val lift = ui.h * (.035f + .10f * strength)
            canvas.save()
            canvas.clipOutPath(c.path)
            // An opaque caster is required. Alpha=1 used to scale the entire shadow to invisibility.
            // Clip away the caster itself, leaving a soft displaced cast shadow and contact shade.
            c.fillPaint.setShadowLayer(ui.h * (.10f + .16f * strength),
                (kotlin.math.cos(angle) * lift).toFloat(),
                (kotlin.math.sin(angle) * lift).toFloat(), Color.argb(shadowAlpha, 0, 0, 0))
            canvas.drawPath(c.path, c.fillPaint)
            c.fillPaint.setShadowLayer(ui.h * .035f, 0f, ui.h * .018f,
                Color.argb((shadowAlpha * .45f).toInt(), 0, 0, 0))
            canvas.drawPath(c.path, c.fillPaint)
            c.fillPaint.clearShadowLayer()
            canvas.restore()
        }

        // ── GPU 蒙版模式：只输出"玻璃条形状 + 阴影"的蒙版。alpha=形状强度（形状内 255，阴影区渐变），
        //    RGB=玻璃条局部 Y（上黑下白，供着色器做反光/内阴影渐变）。着色器用 mask.a 精确识别玻璃
        //    区域 → 用视频模糊做毛玻璃背景，前景元素由另一张纹理叠加。这彻底解决透明底上"玻璃本体
        //    alpha≈0 → 着色器认不出玻璃条 → 导出只有阴影"的问题。
        if (maskOnly) {
            // RGB=局部 Y 渐变（反光/内阴影用）；alpha 由白填充承担（255）
            // 关键：渐变起点用深灰而非纯黑——部分 GPU 上 mask alpha 读回为 0 时，
            // 着色器用 max(r,g,b) 兜底识别玻璃区；纯黑顶边会让"上半玻璃区"识别失败 → 顶端无水印
            c.fillPaint.reset(); c.fillPaint.isAntiAlias = true
            c.fillPaint.style = Paint.Style.FILL
            c.fillPaint.shader = LinearGradient(0f, sampleRect.top, 0f, sampleRect.bottom,
                Color.argb(255, 46, 46, 46), Color.WHITE, Shader.TileMode.CLAMP)
            canvas.drawPath(c.path, c.fillPaint)
            c.fillPaint.shader = null
            return
        }

        // 2. 玻璃本体：开源算法引擎一次性合成（模糊+边缘折射+色散+反光+内阴影+染色）
        //    玻璃位图按屏幕轴对齐 AABB 采样（方向与照片一致），再用烘焙旋转的形状裁剪
        //    GPU 路径（skipGlassBody=true）：玻璃模糊背景由片段着色器对视频帧做高斯模糊，
        //    这里跳过引擎合成——引擎对透明底模糊会产出"带 alpha 的黑色残影"，叠加后玻璃条变黑底。
        //    拖动中（lite=true）：不跳过引擎，只是把渲染分辨率减半（调用方传小 maxBlurEdge），
        //    玻璃模糊/折射/对比度全部保留实时预览，速度约 ×4 防主线程阻塞黑屏；
        //    松手后恢复全分辨率完整渲染。
        if (!skipGlassBody) {
            val ownCache = if (cache == null) BlurCache() else null
            val gc = cache ?: ownCache!!
            val radiusCanvas = glassRadius(p, ui.w, ui.h)
            val t0 = System.currentTimeMillis()
            val glassBmp = gc.obtain(photo, photoDst, sampleRect, p, radiusCanvas, density, sUnit, maxBlurEdge,
                wmRot, drawRect.width(), drawRect.height(),
                if (photoPreRotated) 0 else rot,
                photoScaleX = photoScaleX, photoScaleY = photoScaleY)
            val cost = System.currentTimeMillis() - t0
            if (cost > 120L) DebugLog.throttleLog("RENDER", "玻璃合成耗时=${cost}ms lite=$lite edge=$maxBlurEdge", 1000L)
            if (glassBmp != null && !glassBmp.isRecycled) {
                // 引擎已按旋转形状精确写 alpha（形状外透明），直接轴对齐绘制 AABB 即可，无需再 clip
                c.glassPaint.alpha = alpha(p.opacity, contentAlphaScale)
                canvas.drawBitmap(glassBmp, null, gc.patch, c.glassPaint)
            } else {
                // 引擎兜底：极淡白膜，保证形状可见但不崩
                c.fillPaint.reset(); c.fillPaint.isAntiAlias = true
                c.fillPaint.style = Paint.Style.FILL
                c.fillPaint.color = Color.argb(modulateOpacity(28, contentAlphaScale), 255, 255, 255)
                canvas.drawPath(c.path, c.fillPaint)
            }
            ownCache?.reset()
        }

        // ponytail: 独立边框层（由 borderWidth / borderAlpha 控制，之前这两个滑块完全失效）
        if (p.borderWidth > 0.01f && p.borderAlpha > 0) {
            c.strokePaint.reset(); c.strokePaint.isAntiAlias = true
            c.strokePaint.style = Paint.Style.STROKE
            c.strokePaint.strokeWidth = (p.borderWidth * density * sUnit)
                .coerceAtLeast(0.5f * density * sUnit)
            c.strokePaint.color = Color.argb(modulateOpacity(p.borderAlpha.coerceIn(0, 255), contentAlphaScale),
                255, 255, 255)
            canvas.drawPath(c.path, c.strokePaint)
        }

        // 3. 液态玻璃边界：v14.91 移除 Canvas 白色描边（旧 3a 外发光+3b 细棱边=用户指出的
        // "死白框/扁平描边"）——菲涅尔高光完全由引擎逐像素计算（方向渐变+环境色+微蓝），
        // UI 层不再画任何 stroke，质感全部来自光学模拟
        // 3. 文字与标识：随水印旋转（局部坐标 drawRect）
        canvas.save()
        if (wmRot != 0f) canvas.rotate(wmRot, cx, cy)
        drawContent(c, canvas, p, ui, density, skipGlassBody)
        canvas.restore()
        } finally {
            p.model = origModel
            p.modelCode = origCode
            p.dateText = origDate
        }
    }

    /** 按形状把玻璃轮廓写入 target（椭圆/直角/胶囊/连续圆角），多处复用保证轮廓一致 */
    private fun buildGlassPath(target: Path, p: EditorState.Params, r: RectF, halfH: Float) {
        target.reset()
        when (p.shape) {
            EditorState.SHAPE_ELLIPSE -> target.addOval(r, Path.Direction.CW)
            EditorState.SHAPE_RECT -> target.addRect(r, Path.Direction.CW)
            else -> {
                val rad = when (p.shape) {
                    EditorState.SHAPE_CAPSULE -> min(r.width(), r.height()) / 2f
                    else -> halfH * (p.cornerPct.coerceIn(0, 100) / 100f)
                }.coerceIn(0f, min(r.width(), r.height()) / 2f)
                when {
                    rad <= 0.6f -> target.addRect(r, Path.Direction.CW)
                    rad >= min(r.width(), r.height()) / 2f - 0.6f ->
                        target.addRoundRect(r, rad, rad, Path.Direction.CW)
                    else -> smoothRoundRect(target, r, rad)
                }
            }
        }
    }
    /** iOS 连续圆角（squircle）近似：每角一条三次贝塞尔，起终切线分别垂直/水平，比普通圆弧更润 */
    private fun smoothRoundRect(out: Path, r: RectF, rr: Float) {
        val k = 0.46f
        val L = r.left; val T = r.top; val R = r.right; val B = r.bottom
        out.moveTo(L, T + rr)
        out.cubicTo(L, T + rr * k, L + rr * k, T, L + rr, T)
        out.lineTo(R - rr, T)
        out.cubicTo(R - rr * k, T, R, T + rr * k, R, T + rr)
        out.lineTo(R, B - rr)
        out.cubicTo(R, B - rr * k, R - rr * k, B, R - rr, B)
        out.lineTo(L + rr, B)
        out.cubicTo(L + rr * k, B, L, B - rr * k, L, B - rr)
        out.close()
    }

    private fun drawContent(c: Ctx, canvas: Canvas, p: EditorState.Params, ui: Ui, density: Float,
                            skipShadow: Boolean = false) {
        // —— 文字绘制诊断（独立 tag TEXT，节流）：实锤"字体看不见"是值空、颜色异常、还是被覆盖 ——
        // v15.15 追加 fontMode / 实际解析出的 typeface 名，供字体模式切换前后对比验证
        // v15.16 修复：导出期间不再写（每 ~1s 一次 appendText + SimpleDateFormat + 轮转检查，
        // 在长视频导出中累积成主线程/后台线程的文件 I/O 抖动；预览诊断能力不变）。
        val activeMode = ctxActiveFontMode()
        if (!EditorState.isExporting) DebugLog.throttleLog(
            "TEXT",
            "绘制文字 model=\"${p.model}\" code=\"${p.modelCode}\" date=\"${p.dateText}\" " +
            "size=${ui.titleSize.toInt()}/${ui.subSize.toInt()} color=${Integer.toHexString(p.textColor)} " +
            "wmRect=${c.wmRect.width().toInt()}x${c.wmRect.height().toInt()} " +
            "fontMode=$activeMode(${if (activeMode == FONT_MODE_SYSTEM) "系统字体" else "默认字体"}) " +
            "typeface=$lastResolvedTypefaceName", 1000L)
        // Keep logo geometry on the same overall scale as text and glass; brandScale remains its
        // independent fine control inside the scaled layout box.
        val logoUnit = ui.sUnit
        val pixelUnit = density * logoUnit
        val contentAlphaScale = WatermarkScale.opacityMultiplier(WatermarkScale.factor(p.scalePct))
        val tc = modulateColorAlpha(p.textColor, contentAlphaScale)
        c.titlePaint.textSize = ui.titleSize
        c.subPaint.textSize = ui.subSize
        c.titlePaint.color = tc
        // Subtitle opacity follows the same subtle scale compensation as the title and logo.
        c.subPaint.color = modulateColorAlpha(Color.argb(255, Color.red(tc), Color.green(tc), Color.blue(tc)),
            contentAlphaScale)
        // 文字投影增强：保证玻璃之上依然清晰可读（不遮挡字迹）
        // 阴影用双层绘制（先深色偏移层再主文字层），偏移随字号等比放大但封顶 ≤2.6px
        val tsh = min(ui.titleSize * 0.09f, 4.5f * pixelUnit)
        // ponytail: fontContrast 开关消费——开启时文字阴影用纯黑（浅色背景下文字不被玻璃吃掉）。
        // 文字可读性保底：无论开关，阴影基础始终用更实的深色（0xD8 半透明黑），白字在浅色
        // 通透玻璃上也不会消失（"字体看不见"根因之一：默认白字+浅玻璃+弱阴影=白字被吃掉）
        val shadowColor = if (p.fontContrast) 0xFF000000.toInt() else 0xD8000000.toInt()
        val shDy = min(tsh * 0.7f, 2.6f * pixelUnit)
        // v14.03：GPU 导出前景纹理（skipShadow=true）不画黑色硬影层——否则纯黑偏移层写进透明
        // 前景纹理后，着色器叠加成"文字黑色重影"，导出水印文字显黑（"导出是黑色"根因之一）。
        fun text2(paint: Paint, text: String, x: Float, y: Float) {
            val base = paint.color
            if (!skipShadow) {
                paint.color = shadowColor
                canvas.drawText(text, x + shDy, y + shDy, paint)
            }
            paint.color = base
            canvas.drawText(text, x, y, paint)
        }

        val x0 = c.wmRect.left + ui.padH
        val centerY = c.wmRect.centerY()
        // 玻璃条内容区高度：logo 垂直方向的硬上限（再大就溢出玻璃上下边）
        val innerH = (ui.h - ui.padV * 2f).coerceAtLeast(1f)
        // logo 绘制边长=基准槽位(brandScale=1，决定玻璃几何)×brandScale，垂直 clamp 在玻璃内高：
        // 玻璃/文字几何恒定不随滑块撑大；可缩到极小、最大填满玻璃条高度（不溢出、不与上下元素重叠）。
        fun logoDrawSize(base: Float) = (base * p.brandScale).coerceIn(0.5f, innerH)
        // 品牌位图真实宽高比（横长字标按宽矩形给槽，方形标≈1 视觉不变）
        val asp = if (hasLogo(p)) logoAspect(p) else 1f
        val gap = ui.gap  // 与测量一致：紧凑模式下同步缩小，logo/文字间距随动
        val titleFM = c.titlePaint.fontMetrics
        val subFM = c.subPaint.fontMetrics

        // 三行竖排：名称(粗) / 型号 / 时间，整块在水印内垂直居中
        fun drawStack(x: Float) {
            val subLines = (if (p.modelCode.isNotEmpty()) 1 else 0) +
                    (if (p.dateText.isNotEmpty()) 1 else 0)
            val titleH = ui.titleSize * 1.22f
            val subH = ui.subSize * 1.25f
            val blockH = titleH + if (subLines > 0) ui.subSize * 0.30f * subLines + subH * subLines else 0f
            var y = centerY - blockH / 2f
            if (p.model.isNotEmpty()) {
                text2(c.titlePaint, p.model, x, y - titleFM.ascent)
                y += titleH
            }
            if (p.modelCode.isNotEmpty()) {
                text2(c.subPaint, p.modelCode, x, y - subFM.ascent)
                y += ui.subSize * 0.30f + subH
            }
            if (p.dateText.isNotEmpty()) {
                text2(c.subPaint, p.dateText, x, y - subFM.ascent)
            }
        }

        when (p.layoutMode) {
            EditorState.LAYOUT_SINGLE -> {
                // 单行：只显示手机名称（可带 logo）；logo 恒定尺寸（随缩放不随长度），始终留在条内
                var x = x0
                val logoBase = p.titleSp * density * logoUnit * 1.5f
                if (hasLogo(p)) {
                    // 左对齐安全区间：方形标占 [x,x+logoBase]（与旧版位置一致），横长字标向右延伸，
                    // 右缘留 2gap 隔离机型名；放大只增高、宽度 clamp 在区间内，绝不压机型。
                    val slotW = logoBase * asp + gap * 2f
                    drawLogo(c, canvas, p, x, x + slotW, centerY,
                        logoDrawSize(logoBase), align = -1)
                    x += slotW
                }
                val titleBase = centerY - (titleFM.ascent + titleFM.descent) / 2f
                text2(c.titlePaint, p.model, x, titleBase)
            }
            EditorState.LAYOUT_STACK -> drawStack(x0)
            EditorState.LAYOUT_SPLIT -> {
                // 方案2（110% 还原参考红米徕卡水印）：机型大字最左，logo/竖线/时间整体最右。
                // 右组内部用紧凑 splitGap（不随长度）；两组之间用 flexGap（随 ui.extend 增大）
                // → 拉长时机型贴最左、其余贴最右，中间空白，字号/logo 恒定。
                val splitGap = gap * 0.5f
                val flexGap = splitGap * 2f * ui.extend.coerceAtLeast(0.2f)
                val t2 = ui.titleSize * 0.38f
                val s2 = ui.subSize * 0.30f
                c.titlePaint.textSize = t2
                val nameW = if (p.model.isNotEmpty()) c.titlePaint.measureText(p.model) else 0f
                c.titlePaint.textSize = ui.titleSize
                c.subPaint.textSize = s2
                val codeW = if (p.modelCode.isNotBlank()) c.subPaint.measureText(p.modelCode) else 0f
                val hasCode = codeW > 0f
                val subFM2 = c.subPaint.fontMetrics
                val (splitD, splitT) = splitDateText(p.dateText)
                val timeBlockH = s2 * 2f
                c.subPaint.textSize = ui.subSize
                var x = x0
                // v15.18：左组 = 机型大字 + 型号小字，整块垂直居中。
                //    此前 SPLIT 分支从不绘制 modelCode，默认方案2 因此「只显示名称」。
                val leftBlockH = t2 + if (hasCode) s2 * 1.25f else 0f
                val leftTop = centerY - leftBlockH / 2f
                if (p.model.isNotEmpty()) {
                    c.titlePaint.textSize = t2
                    val titleFM2 = c.titlePaint.fontMetrics
                    text2(c.titlePaint, p.model, x, leftTop - titleFM2.ascent)
                    c.titlePaint.textSize = ui.titleSize
                }
                if (hasCode) {
                    c.subPaint.textSize = s2
                    val codeFM = c.subPaint.fontMetrics
                    text2(c.subPaint, p.modelCode, x, leftTop + t2 - codeFM.ascent)
                    c.subPaint.textSize = ui.subSize
                }
                x += max(nameW, codeW) + flexGap
                if (hasLogo(p)) {
                    // 2. logo 属右组、右对齐竖线（放大只向左扩张吃中间 flexGap，绝不压竖线/时间/机型）
                    // 槽位与 measure 一致封顶（×2.6 上限），横长蔡司标不会把竖线/时间挤出条外
                    val logoBase = p.titleSp * density * logoUnit * 0.38f * 1.2f
                    val slotW = min(logoBase * asp, logoBase * 2.6f) + splitGap * 2f
                    drawLogo(c, canvas, p,
                        x - flexGap + splitGap, x + min(logoBase * asp, logoBase * 2.6f) + splitGap,
                        centerY, logoDrawSize(logoBase), align = 1)
                    x += slotW
                    // 3. 短竖线（竖直分隔线，logo 与时间之间，加粗提亮保证可见）
                    val tc2 = p.textColor
                    c.fillPaint.reset(); c.fillPaint.isAntiAlias = true
                    c.fillPaint.color = Color.argb(modulateOpacity(170, contentAlphaScale),
                        Color.red(tc2), Color.green(tc2), Color.blue(tc2))
                    val vw = max(1.5f * pixelUnit, 2.2f * density * ui.sUnit)
                    val vh = timeBlockH * 1.1f
                    c.tmpRect.set(x, centerY - vh / 2f, x + vw, centerY + vh / 2f)
                    canvas.drawRoundRect(c.tmpRect, vw, vw, c.fillPaint)
                    x += vw + splitGap * 2f
                }
                // 4. 时间两行最右（上行日期、下行时分秒，整块垂直居中，参考图风格）
                c.subPaint.textSize = s2
                val ty0 = centerY - timeBlockH / 2f - subFM2.ascent
                var ty = ty0
                if (splitD.isNotEmpty()) {
                    text2(c.subPaint, splitD, x, ty)
                    ty += s2
                }
                if (splitT.isNotEmpty()) {
                    text2(c.subPaint, splitT, x, ty)
                }
                c.subPaint.textSize = ui.subSize
            }
            else -> {
                // 经典：logo + 竖分隔线 + 三行文字；logoAlign=0 前(左) / 1 后(右) / 2 居中(纯 logo)
                // 槽位基准 logoBase（brandScale=1，与 measure 几何一致）；logoSize 为实际绘制尺寸（条内 clamp）
                val logoBase = p.titleSp * density * logoUnit * 1.65f
                val logoSize = logoDrawSize(logoBase)
                val lineW = max(pixelUnit, 1.4f * density * ui.sUnit * min(ui.extend, 4f))
                // 三行块高用于分隔线高度
                val subLines = (if (p.modelCode.isNotEmpty()) 1 else 0) +
                        (if (p.dateText.isNotEmpty()) 1 else 0)
                val blockH = ui.titleSize * 1.22f +
                        if (subLines > 0) ui.subSize * 0.30f * subLines + ui.subSize * 1.25f * subLines else 0f
                val tc = p.textColor
                c.fillPaint.reset(); c.fillPaint.isAntiAlias = true
                c.fillPaint.color = Color.argb(modulateOpacity(100, contentAlphaScale),
                    Color.red(tc), Color.green(tc), Color.blue(tc))
                fun drawDiv(x: Float) {
                    c.tmpRect.set(x, centerY - blockH * 0.42f, x + lineW, centerY + blockH * 0.42f)
                    canvas.drawRoundRect(c.tmpRect, lineW, lineW, c.fillPaint)
                }
                when {
                    p.logoAlign == 2 && hasLogo(p) -> {
                        // 居中：纯 logo 水印，在整条玻璃内容区水平垂直居中，可自由放大
                        drawLogo(c, canvas, p, x0, c.wmRect.right - ui.padH, centerY, logoSize, align = 0)
                    }
                    p.logoAlign == 1 && hasLogo(p) -> {
                        // 后置：文字左 + 分隔线 + logo 右
                        val textW = max(
                            if (p.model.isNotEmpty()) c.titlePaint.measureText(p.model) else 0f,
                            max(if (p.modelCode.isNotEmpty()) c.subPaint.measureText(p.modelCode) else 0f,
                                if (p.dateText.isNotEmpty()) c.subPaint.measureText(p.dateText) else 0f)
                        )
                        var x = x0
                        drawStack(x)
                        x += textW + gap * 2f
                        drawDiv(x)
                        x += lineW + gap * 2f
                        // logo 为最右元素：右对齐玻璃右内边，放大向左扩张（区间左界=竖线之后），不溢出右边、不压竖线
                        drawLogo(c, canvas, p, x, c.wmRect.right - ui.padH, centerY, logoSize, align = 1)
                    }
                    else -> {
                        // 前置（默认，第一布局）：机型最左 + logo + 竖线 + 时间竖排（年最上）
                        val (dPart, tPart) = splitDateText(p.dateText)
                        val timeH = ui.subSize * 0.30f + ui.subSize * 1.25f * 2f
                        val leftH = ui.titleSize * 1.22f +
                                (if (p.modelCode.isNotEmpty()) ui.subSize * 0.30f + ui.subSize * 1.25f else 0f)
                        val blockH2 = max(leftH, timeH)
                        var x = x0
                        // 机型块（最左）：机型名 + 型号小字，整块垂直居中
                        var ly = centerY - leftH / 2f
                        if (p.model.isNotEmpty()) {
                            text2(c.titlePaint, p.model, x, ly - titleFM.ascent)
                            ly += ui.titleSize * 1.22f
                        }
                        if (p.modelCode.isNotEmpty()) {
                            text2(c.subPaint, p.modelCode, x, ly - subFM.ascent)
                            ly += ui.subSize * 0.30f + ui.subSize * 1.25f
                        }
                        x += max(
                            if (p.model.isNotEmpty()) c.titlePaint.measureText(p.model) else 0f,
                            if (p.modelCode.isNotEmpty()) c.subPaint.measureText(p.modelCode) else 0f) + gap * 2f
                        // logo（竖线旁边，紧贴竖线左侧）：左对齐安全区间，方形标位置与旧版一致，
                        // 横长字标向右延伸但右缘留 2gap，放大只增高、宽度 clamp，绝不越过竖线压时间
                        if (hasLogo(p)) {
                            val slotW = logoBase * asp + gap * 2f
                            drawLogo(c, canvas, p, x, x + slotW, centerY, logoSize, align = -1)
                            x += slotW
                        }
                        // 竖线
                        c.fillPaint.reset(); c.fillPaint.isAntiAlias = true
                        c.fillPaint.color = Color.argb(modulateOpacity(100, contentAlphaScale),
                            Color.red(tc), Color.green(tc), Color.blue(tc))
                        c.tmpRect.set(x, centerY - blockH2 * 0.42f, x + lineW, centerY + blockH2 * 0.42f)
                        canvas.drawRoundRect(c.tmpRect, lineW, lineW, c.fillPaint)
                        x += lineW + gap * 2f
                        // 时间块（最右）：年-月-日 上 / 时:分:秒 下，年最上
                        var ty = centerY - timeH / 2f
                        if (dPart.isNotEmpty()) {
                            text2(c.subPaint, dPart, x, ty - subFM.ascent)
                            ty += ui.subSize * 0.30f + ui.subSize * 1.25f
                        }
                        if (tPart.isNotEmpty()) {
                            text2(c.subPaint, tPart, x, ty - subFM.ascent)
                        }
                    }
                }
            }
        }
        c.titlePaint.clearShadowLayer()
        c.subPaint.clearShadowLayer()
    }

    /**
     * @param size  logo 最大高度（已含 brandScale 并 clamp 到玻璃内高）
     * @param boxW   logo 水平安全宽（默认=size 正方形盒）；横长字标传入"到相邻文字/竖线的可用宽"，
     *               位图按宽高比 contain 进 boxW×size，放大也不会盖住旁边的时间/机型/竖线。
     */
    /**
     * 在水平安全区间 [leftBound, rightBound] 内、以 cy 垂直居中绘制 logo，返回实际占用宽。
     * 高度优先：目标高 = size（已含 brandScale 并 clamp 到玻璃内高），宽按位图真实比例延伸；
     * 仅当宽度超出安全区间时才按宽回缩——横长字标（Google/OPPO/真我）高度能真正顶满、肉眼清晰，
     * 方形徕卡/蔡司视觉与旧版一致。align：-1 左对齐 / 0 居中 / 1 右对齐（放大只向空闲侧扩张，
     * 边界由调用方按相邻文字/竖线/玻璃边缘给出，故永不压时间、机型，永不越界）。
     */
    private fun drawLogo(c: Ctx, canvas: Canvas, p: EditorState.Params,
                         leftBound: Float, rightBound: Float, cy: Float, size: Float,
                         align: Int = 0): Float {
        val contentAlphaScale = WatermarkScale.opacityMultiplier(WatermarkScale.factor(p.scalePct))
        val col = modulateColorAlpha(p.textColor, contentAlphaScale)
        val L = leftBound
        val R = rightBound.coerceAtLeast(L + 1f)
        val availW = (R - L).coerceAtLeast(1f)
        val h0 = size.coerceAtLeast(1f)
        // 品牌位图优先：高度优先等比缩放，超宽才回缩
        val brandAsset = if (p.logoBrand in EditorState.BRAND_ASSETS.indices)
            EditorState.BRAND_ASSETS[p.logoBrand] else null
        val brandBmp = if (p.logoBrand != EditorState.BRAND_NONE) loadBrandBitmap(p, brandAsset) else null
        if (brandBmp != null) {
            val bmpW = brandBmp.width.toFloat().coerceAtLeast(1f)
            val bmpH = brandBmp.height.toFloat().coerceAtLeast(1f)
            // 纯等比：高度=size（已含 brandScale 并 clamp 到玻璃内高 innerH=玻璃顶端），宽按真实比例。
            // 横长字母标（小米/华为/一加/OPPO/真我/Google/三星）右对齐 align=1 时右缘贴右界（与时间/下划线右缘齐平），
            // 放大只让左缘向左扩展、不压右边；垂直居中于 cy，与时间/下划线对齐不歪。
            // 宽度一旦超过本槽位可用宽（会压到左侧机型/时间），按可用宽等比顶满（高度随之回缩，绝不越界）。
            var h = h0
            var w = bmpW * (h / bmpH)
            if (w > availW) { w = availW; h = bmpH * (w / bmpW) }
            val left = when (align) {
                -1 -> L
                1 -> R - w
                else -> L + (availW - w) / 2f
            }
            val oldAlpha = c.bmpPaint.alpha
            c.bmpPaint.alpha = modulateOpacity(oldAlpha, contentAlphaScale)
            canvas.drawBitmap(brandBmp, null,
                android.graphics.RectF(left, cy - h / 2f, left + w, cy + h / 2f), c.bmpPaint)
            c.bmpPaint.alpha = oldAlpha
            return w
        }
        // 矢量标识（圆点/相机标，近似方形）：在区间中心按 h0 绘制
        val cx = (L + R) / 2f
        c.fillPaint.reset(); c.fillPaint.isAntiAlias = true
        c.strokePaint.reset(); c.strokePaint.isAntiAlias = true
        when (p.logoStyle) {
            EditorState.LOGO_CAMERA -> {
                val r = h0 * 0.42f
                c.tmpRect.set(cx - r, cy - r * 0.72f, cx + r, cy + r * 0.72f)
                c.fillPaint.color = Color.argb(modulateOpacity(40, contentAlphaScale),
                    Color.red(col), Color.green(col), Color.blue(col))
                canvas.drawRoundRect(c.tmpRect, r * 0.28f, r * 0.28f, c.fillPaint)
                c.strokePaint.style = Paint.Style.STROKE
                c.strokePaint.strokeWidth = max(0.55f, h0 * 0.07f)
                c.strokePaint.color = Color.argb(modulateOpacity(235, contentAlphaScale),
                    Color.red(col), Color.green(col), Color.blue(col))
                canvas.drawRoundRect(c.tmpRect, r * 0.28f, r * 0.28f, c.strokePaint)
                c.fillPaint.color = Color.argb(modulateOpacity(235, contentAlphaScale),
                    Color.red(col), Color.green(col), Color.blue(col))
                canvas.drawCircle(cx, cy, r * 0.30f, c.fillPaint)
            }
            else -> {
                // 圆点标识用中性白（无色玻璃，不带青）
                c.fillPaint.color = Color.argb(modulateOpacity(224, contentAlphaScale),
                    Color.red(col), Color.green(col), Color.blue(col))
                canvas.drawCircle(cx, cy, h0 * 0.34f, c.fillPaint)
                c.strokePaint.style = Paint.Style.STROKE
                c.strokePaint.strokeWidth = max(0.5f, h0 * 0.05f)
                c.strokePaint.color = Color.argb(modulateOpacity(95, contentAlphaScale),
                    Color.red(col), Color.green(col), Color.blue(col))
                canvas.drawCircle(cx, cy, h0 * 0.46f, c.strokePaint)
            }
        }
        return h0
    }
}
