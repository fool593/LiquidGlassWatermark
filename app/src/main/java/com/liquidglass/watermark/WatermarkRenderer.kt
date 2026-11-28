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
import kotlin.math.abs

object WatermarkRenderer {
    /** Material/text dimensions live in a 393-wide photo coordinate system, not screen dp.
     * The caller still supplies device density for the existing renderer API; this unit cancels
     * it so preview, snapshots, still exports and every video frame share normalized geometry.
     */
    fun unitForPhotoWidth(photoWidthPx: Float, deviceDensity: Float): Float =
        unitForPhoto(photoWidthPx, photoWidthPx, deviceDensity)

    fun unitForPhoto(photoWidthPx: Float, photoHeightPx: Float, deviceDensity: Float): Float {
        val w = photoWidthPx.takeIf { it.isFinite() && it > 0f }
        val h = photoHeightPx.takeIf { it.isFinite() && it > 0f }
        val shortSide = when {
            w != null && h != null -> min(w, h)
            w != null -> w
            h != null -> h
            else -> 393f
        }
        val density = deviceDensity.takeIf { it.isFinite() && it > 0f } ?: 1f
        return shortSide / 393f / density
    }

    private val lastRectLocal = ThreadLocal<RectF>()
    val lastWatermarkRect: RectF
        get() = lastRectLocal.get() ?: RectF().also(lastRectLocal::set)

    private val brandBitmapCache = java.util.concurrent.ConcurrentHashMap<String, Bitmap>()

    private val ctxLocal = ThreadLocal<Ctx>()

    private val ctxFontMode = ThreadLocal<Int>()

    private fun ctxActiveFontMode(): Int = ctxFontMode.get() ?: EditorState.params.fontMode
    private fun ctx(): Ctx {
        val mode = EditorState.params.fontMode
        val c = ctxLocal.get()

        if (c != null && ctxFontMode.get() == mode) return c
        val n = Ctx()
        ctxLocal.set(n)
        ctxFontMode.set(mode)
        return n
    }

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

        val isCustomLogo = asset == null && p.customLogoPath.isNotEmpty()
        val shape = if (isCustomLogo) p.logoShape.coerceIn(0, 3) else 0
        val crop = if (isCustomLogo) p.logoCrop else true
        var shaped: Bitmap? = final_bmp
        runCatching {
            if (final_bmp != null) {
                if (!crop && shape == 0) {

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
        if (shaped == null) shaped = final_bmp
        val trimmed = shaped
        if (trimmed != null) brandBitmapCache[cacheKey] = trimmed
        return trimmed
    }

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

    private val DEFAULT_TYPEFACE_BOLD: Typeface get() = FontSupport.factory(true)
    private val DEFAULT_TYPEFACE_NORMAL: Typeface get() = FontSupport.factory(false)

    private val serifMonoTypefaces = java.util.concurrent.ConcurrentHashMap<Int, Typeface>()

    @Volatile private var systemTypefaceBold: Typeface? = null
    @Volatile private var systemTypefaceNormal: Typeface? = null
    @Volatile private var systemTypefaceResolved = false

    @Volatile var lastResolvedTypefaceName: String = "未解析"
        private set

    fun resolveTypeface(mode: Int, bold: Boolean): Typeface {

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
            lastResolvedTypefaceName = "默认字体(出厂)" + if (bold) "-bold" else "-regular"
            return if (bold) DEFAULT_TYPEFACE_BOLD else DEFAULT_TYPEFACE_NORMAL
        }

        if (!systemTypefaceResolved) {
            synchronized(this) {
                if (!systemTypefaceResolved) {
                    runCatching {
                        systemTypefaceNormal = FontSupport.system(false)
                        systemTypefaceBold = FontSupport.system(true)
                    }
                    systemTypefaceResolved = true
                }
            }
        }
        val st = if (bold) systemTypefaceBold else systemTypefaceNormal
        return if (st != null) {
            lastResolvedTypefaceName = if (FontSupport.isSystemThemed())
                "系统字体(与出厂不同)" + if (bold) "(粗)" else "(常规)"
            else "系统字体(=出厂)" + if (bold) "(粗)" else "(常规)"
            st
        } else {

            lastResolvedTypefaceName = "sans-serif(系统字体不可用，已回退)"
            if (bold) DEFAULT_TYPEFACE_BOLD else DEFAULT_TYPEFACE_NORMAL
        }
    }

    private class Ui(val w: Float, val h: Float, val titleSize: Float, val subSize: Float,
                     val padH: Float, val padV: Float, val sUnit: Float, val gap: Float,
                     val extend: Float = 1f)

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
                   photoScaleX: Float = -1f, photoScaleY: Float = -1f,
                   cropApplied: Boolean = false): Bitmap? {
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
                "${p.shape},${p.cornerPct},${p.refractionModel},$effectScale,$tier,${p.ios27Style},${p.fluidEnabled}"
            if (key == nextKey) bmp?.takeUnless { it.isRecycled }?.let { return it }
            var sample = sharp
            if (sample == null || sample.isRecycled || sample.width != bw || sample.height != bh) {
                sharp?.takeUnless { it.isRecycled }?.recycle()
                sample = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                sharp = sample
            }
            // Map source -> displayed photo -> local patch, retaining the exact transform at edges.
            // CLAMP extends edge pixels into padding without stretching a clipped crop over the bar.

            var sourceRect = RectF(0f, 0f, photo.width.toFloat(), photo.height.toFloat())
            if (!cropApplied) {
                val cropAspect = EditorState.cropAspect(EditorState.params.cropRatio)
                if (cropAspect > 0f) {

                    sourceRect = CropMath.centerCropRect(photo.width, photo.height, cropAspect)
                }
            }
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

            samplingPaint.shader = android.graphics.BitmapShader(photo,
                Shader.TileMode.MIRROR, Shader.TileMode.MIRROR)
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
                effectScale = effectScale, tier = tier,
                ios27 = p.ios27Style,
                fluidActive = p.fluidEnabled)
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

    private fun glassRadius(p: EditorState.Params, w: Float, h: Float): Float {
        val halfH = h / 2f
        return when (p.shape) {
            EditorState.SHAPE_RECT -> 0f
            EditorState.SHAPE_CAPSULE -> min(w, h) / 2f
            EditorState.SHAPE_ELLIPSE -> min(w, h) / 2f
            else -> halfH * (p.cornerPct.coerceIn(0, 100) / 100f)
        }.coerceIn(0f, min(w, h) / 2f)
    }

    private fun hasLogo(p: EditorState.Params): Boolean =
        p.logoStyle != EditorState.LOGO_NONE || p.logoBrand != EditorState.BRAND_NONE

    private fun logoAspect(p: EditorState.Params): Float {
        if (p.logoBrand == EditorState.BRAND_NONE && p.customLogoPath.isEmpty()) return 1f
        val asset = if (p.logoBrand in EditorState.BRAND_ASSETS.indices)
            EditorState.BRAND_ASSETS[p.logoBrand] else null
        val bmp = loadBrandBitmap(p, asset) ?: return 1f
        if (bmp.width <= 0 || bmp.height <= 0) return 1f
        return (bmp.width.toFloat() / bmp.height.toFloat()).coerceIn(0.5f, 6f)
    }

    private fun splitDateText(s: String): Pair<String, String> {
        val t = s.trim()
        if (t.isEmpty()) return "" to ""
        val sp = t.indexOf(' ')
        if (sp > 0) return t.substring(0, sp) to t.substring(sp + 1)
        return t to ""
    }

    private fun dyPxOf(value: Float, lineH: Float): Float =
        (value.coerceIn(-40f, 40f) / 40f) * lineH.coerceAtLeast(1f)

    private fun fitTop(top: Float, blockH: Float, rect: RectF, padV: Float): Float {
        val lo = rect.top + padV
        val hi = rect.bottom - padV - blockH
        if (hi <= lo) return lo
        return top.coerceIn(lo, hi)
    }

    private fun applyTypeface(c: Ctx, p: EditorState.Params) {
        val tfTitle = resolveTypeface(p.fontMode, true)
        val tfSub = resolveTypeface(p.fontMode, false)
        if (c.titlePaint.typeface !== tfTitle) c.titlePaint.typeface = tfTitle
        if (c.subPaint.typeface !== tfSub) c.subPaint.typeface = tfSub
    }

    private fun measure(c: Ctx, p: EditorState.Params, density: Float, sUnit: Float,
                        compact: Float = 1f, extend: Float = 1f): Ui {
        applyTypeface(c, p)

        val titleSize = p.titleSp * density * sUnit * p.fontTitleScale
        val subSize = p.subSp * density * sUnit * p.fontCodeScale
        val timeSize = p.subSp * density * sUnit * p.fontTimeScale
        val geoSize = p.subSp * density * sUnit * p.fontGeoScale
        val lineGapPx = p.fontLineGap * density * sUnit
        val blockShiftPx = p.fontBlockShift * density * sUnit
        val titleShiftPx = p.fontTitleShift * density * sUnit
        val codeShiftPx = p.fontCodeShift * density * sUnit
        val timeShiftPx = p.fontTimeShift * density * sUnit
        val geoShiftPx = p.fontGeoShift * density * sUnit
        val letterSp = p.fontLetterSpacing.coerceIn(0f, 0.6f)
        // Logo, text, padding and separators share the same overall scale. brandScale remains
        // an independent multiplier applied when the logo is drawn inside its safe box.
        val logoUnit = sUnit
        val pixelUnit = density * logoUnit

        var padH = (p.padHDp * density * sUnit * (0.5f + 0.5f * compact) * extend).coerceIn(0f, 2000f * pixelUnit)
        val padV = (p.padVDp * density * sUnit * (0.5f + 0.5f * compact)).coerceIn(0f, 2000f * pixelUnit)
        c.titlePaint.textSize = titleSize
        c.subPaint.textSize = subSize
        c.titlePaint.letterSpacing = letterSp
        c.subPaint.letterSpacing = letterSp
        c.titlePaint.color = p.textColor
        c.subPaint.color = p.textColor
        val nameW = if (p.model.isNotEmpty()) c.titlePaint.measureText(p.model) else 0f
        val codeW = if (p.modelCode.isNotEmpty()) c.subPaint.measureText(p.modelCode) else 0f
        val timeW = if (p.dateText.isNotEmpty()) {
            c.subPaint.textSize = timeSize
            c.subPaint.measureText(p.dateText).also { c.subPaint.textSize = subSize }
        } else 0f

        val geoW = if (p.geoText.isNotEmpty()) {
            c.subPaint.textSize = geoSize
            c.subPaint.measureText(p.geoText).also { c.subPaint.textSize = subSize }
        } else 0f

        val gap = (5f * density * sUnit) * compact
        val titleH = titleSize * 1.22f
        val subH = subSize * 1.25f

        val codeLineH = subH
        val timeLineH = timeSize * 1.25f
        val geoLineH = geoSize * 1.25f

        val dyTimePx = abs(dyPxOf(p.fontTimeDy, timeLineH))
        val dyGeoPx = abs(dyPxOf(p.fontGeoDy, geoLineH))
        val dyCodePx = abs(dyPxOf(p.fontCodeDy, codeLineH))
        val subLines = (if (codeW > 0f) 1 else 0) + (if (timeW > 0f) 1 else 0) + (if (geoW > 0f) 1 else 0)
        val lineGapUnit = subSize * 0.30f + lineGapPx
        val textBlockH = titleH +
            (if (codeW > 0f) lineGapUnit + codeLineH + dyCodePx else 0f) +
            (if (timeW > 0f) lineGapUnit + timeLineH + dyTimePx else 0f) +
            (if (geoW > 0f) lineGapUnit + geoLineH + dyGeoPx else 0f)
        val textW = max(nameW, max(codeW, max(timeW, geoW)))
        val blockShiftAbs = abs(blockShiftPx)

        val asp = if (hasLogo(p)) logoAspect(p) else 1f
        val contentW: Float
        val contentH: Float
        when (p.layoutMode) {
            EditorState.LAYOUT_SINGLE -> {

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

                padH = p.padHDp * density * sUnit * 0.3f
                val t2 = titleSize * 0.38f
                val s2 = subSize * 0.30f
                c.titlePaint.textSize = t2
                val splitNameW = if (p.model.isNotEmpty()) c.titlePaint.measureText(p.model) else 0f
                c.titlePaint.textSize = titleSize
                c.subPaint.textSize = s2

                val splitCodeW = if (p.modelCode.isNotBlank()) c.subPaint.measureText(p.modelCode) else 0f
                val (splitD, splitT) = splitDateText(p.dateText)

                val s2t = timeSize * 0.30f
                c.subPaint.textSize = s2t
                val splitD_W = if (splitD.isNotEmpty()) c.subPaint.measureText(splitD) else 0f
                val splitT_W = if (splitT.isNotEmpty()) c.subPaint.measureText(splitT) else 0f
                c.subPaint.textSize = subSize
                val timeBlockW = max(max(splitD_W, splitT_W), geoW)

                val splitGap = gap * 0.5f
                val logoSize = p.titleSp * density * logoUnit * 0.38f * 1.2f
                val vw = max(1.5f * pixelUnit, 2.2f * density * sUnit)
                var rightW = timeBlockW

                if (hasLogo(p)) {
                    val logoSlot = min(logoSize * asp, logoSize * 2.6f)
                    rightW += logoSlot + splitGap * 2f + vw + splitGap * 2f
                }

                val flexGap = splitGap * 2f * extend.coerceAtLeast(0.2f)
                val leftW = max(splitNameW, splitCodeW)

                val s2tH = s2t * 1.25f
                val dyTpx = dyPxOf(p.fontTimeDy, s2tH)
                val dyGpx = dyPxOf(p.fontGeoDy, geoSize * 1.25f)
                val dyCpx = dyPxOf(p.fontCodeDy, s2 * 1.25f)
                val leftH = t2 + if (splitCodeW > 0f) s2 * 1.25f + abs(dyCpx) else 0f
                val rowGapH = s2t * 0.3f
                val rightH = s2tH * 2f + abs(dyTpx) * 2f +
                    (if (geoW > 0f) rowGapH + geoSize * 1.25f + abs(dyGpx) else 0f)
                contentW = leftW + flexGap + rightW
                contentH = max(max(leftH, rightH),
                    if (hasLogo(p)) logoSize else 0f) * 1.35f
            }
            else -> {

                val logoClassic = p.titleSp * density * logoUnit * 1.65f
                val logoW = if (hasLogo(p)) logoClassic * asp + gap * 2f else 0f
                if (p.logoAlign == 2 && hasLogo(p)) {

                    contentW = logoW
                    contentH = max(logoClassic, textBlockH)
                } else if (p.logoAlign == 1 && hasLogo(p)) {

                    val divW = max(pixelUnit, 1.4f * density * sUnit * extend) + gap * 2f
                    contentW = textW + divW + logoW
                    contentH = max(logoClassic, textBlockH)
                } else {

                    val (dPart, tPart) = splitDateText(p.dateText)
                    val dW = if (dPart.isNotEmpty()) c.subPaint.measureText(dPart) else 0f
                    val tW2 = if (tPart.isNotEmpty()) c.subPaint.measureText(tPart) else 0f
                    val timeBlockW = max(max(dW, tW2), geoW)
                    val leftW = max(nameW, codeW) + abs(blockShiftPx)
                    val divW = if (logoW > 0f) max(pixelUnit, 1.4f * density * sUnit * extend) + gap * 2f else 0f
                    contentW = leftW + gap * 2f + logoW + divW + timeBlockW
                    val leftH = titleH + if (codeW > 0f) lineGapUnit + codeLineH + dyCodePx else 0f

                    val timeH = (if (timeW > 0f) timeLineH * 2f + dyTimePx else 0f) +
                        (if (geoW > 0f) lineGapUnit + geoLineH + dyGeoPx else 0f)
                    contentH = max(max(leftH, timeH), if (logoW > 0f) logoClassic else 0f) * 1.35f
                }
            }
        }
        return Ui(contentW + padH * 2, contentH + padV * 2, titleSize, subSize, padH, padV, sUnit, gap, extend)
    }

    fun render(canvas: Canvas, photo: Bitmap, photoDst: RectF,
               p: EditorState.Params, density: Float, unit: Float,
               cache: BlurCache?, maxBlurEdge: Int, photoPreRotated: Boolean = false,
               maskOnly: Boolean = false, skipGlassBody: Boolean = false,
               lite: Boolean = false,
               skipBackground: Boolean = false, photoScaleX: Float = -1f, photoScaleY: Float = -1f,
               skipShadow: Boolean = false,

               cropApplied: Boolean = false) {
        val c = ctx()
        try {
            // Visibility formatting is local to this thread; never mutate the editor/export snapshot.
            val drawParams = c.renderParams.apply { copyFrom(p) }
        renderInner(c, canvas, photo, photoDst, drawParams, density, unit, cache, maxBlurEdge,
            photoPreRotated, maskOnly, skipGlassBody, lite, skipBackground, photoScaleX, photoScaleY,
            skipShadow, cropApplied)
        } catch (t: Throwable) {

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
                            photoScaleX: Float = -1f, photoScaleY: Float = -1f,
                            skipShadow: Boolean = false,
                            cropApplied: Boolean = false) {
        if (photoDst.width() <= 2f || photoDst.height() <= 2f) return
        if (photo.isRecycled) return
        c.bmpPaint.alpha = 255

        val origModel = p.model
        val origCode = p.modelCode
        val origDate = p.dateText
        try {

            if (!p.showDevice) { p.model = ""; p.modelCode = "" }
            if (!p.showTime) p.dateText = ""
            else p.dateText = c.displayDate(p.dateText, EditorState.liveSeconds)

        if (!EditorState.isExporting) {
            DebugLog.throttleLog(
                "RENDER",
                "渲染 lite=$lite mode=${p.layoutMode} rot=${p.rotation} " +
                "model=\"${p.model}\" code=\"${p.modelCode}\" date=\"${p.dateText}\" " +
                "showDev=${p.showDevice} showTime=${p.showTime} live=${EditorState.liveSeconds} " +
                "dst=${photoDst.width().toInt()}x${photoDst.height().toInt()}", 400L)
        }

        val rot = ((EditorState.photoRotation % 360) + 360) % 360
        if (maskOnly) {

        } else if (skipBackground) {

        } else if (photoPreRotated) {

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

            photoDst.set(cx - dw / 2f, cy - dh / 2f, cx + dw / 2f, cy + dh / 2f)

            val srcDrawW = if (rot % 180 != 0) dh else dw
            val srcDrawH = if (rot % 180 != 0) dw else dh
            val srcDrawRect = RectF(cx - srcDrawW / 2f, cy - srcDrawH / 2f,
                cx + srcDrawW / 2f, cy + srcDrawH / 2f)
            canvas.save()
            canvas.rotate(rot.toFloat(), cx, cy)
            canvas.drawBitmap(photo, null, srcDrawRect, c.bmpPaint)
            canvas.restore()
        }

        // 全局开关：照片已经画完，这里直接收口，水印本体（玻璃 + 文字）一律不画。
        // 预览 / 导出 / 动图 / 快照全部走这条路径，所以关掉后不会再冒出残影。
        if (!p.watermarkOn) return

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

            val naturalW = ui.w
            val maxW = photoDst.width() * 0.99f * scaleFactor
            val t = p.wmLengthPct.coerceIn(0f, 1f)
            val targetSplitW = naturalW + (maxW - naturalW) * t
            if (t > 0.001f && naturalW > 0f) {
                val baseFlex = 5f * density * sUnit
                extend = (1f + (targetSplitW - naturalW) / baseFlex).coerceAtLeast(0.2f)
                ui = measure(c, p, density, sUnit, extend = extend)
            }
        } else if (ui.w < targetW) {

            extend = (targetW / ui.w).coerceIn(1f, 30f)
            ui = measure(c, p, density, sUnit, extend = extend)
            if (ui.w < targetW && ui.w > 0f) {
                extend = (extend * targetW / ui.w).coerceIn(1f, 60f)
                ui = measure(c, p, density, sUnit, extend = extend)
            }
        } else if (ui.w > targetW && p.wmLengthPct < 0.999f) {

            extend = (targetW / ui.w).coerceIn(0.2f, 1f)
            ui = measure(c, p, density, sUnit, extend = extend)
        }

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

        val wmRot = (((p.rotation % 360) + 360) % 360).toFloat()
        val rotRad = Math.toRadians(wmRot.toDouble())
        val cosA = Math.abs(Math.cos(rotRad)).toFloat()
        val sinA = Math.abs(Math.sin(rotRad)).toFloat()
        val aw = ui.w * cosA + ui.h * sinA
        val ah = ui.w * sinA + ui.h * cosA

        if (aw <= photoDst.width()) cx = cx.coerceIn(photoDst.left + aw / 2f, photoDst.right - aw / 2f)
        else cx = photoDst.centerX()
        if (ah <= photoDst.height()) cy = cy.coerceIn(photoDst.top + ah / 2f, photoDst.bottom - ah / 2f)
        else cy = photoDst.centerY()

        val drawRect = RectF(cx - ui.w / 2f, cy - ui.h / 2f, cx + ui.w / 2f, cy + ui.h / 2f)
        c.wmRect.set(drawRect)

        lastWatermarkRect.set(drawRect)
        val sampleRect = RectF(cx - aw / 2f, cy - ah / 2f, cx + aw / 2f, cy + ah / 2f)

        buildGlassPath(c.path, p, drawRect, ui.h / 2f)
        if (wmRot != 0f) c.path.transform(Matrix().apply { setRotate(wmRot, cx, cy) })

        if (p.shadow > 0 && p.opacity > 0 && !skipGlassBody && !skipShadow) {
            c.fillPaint.reset(); c.fillPaint.isAntiAlias = true
            c.fillPaint.style = Paint.Style.FILL; c.fillPaint.color = Color.BLACK

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

        if (maskOnly) {

            c.fillPaint.reset(); c.fillPaint.isAntiAlias = true
            c.fillPaint.style = Paint.Style.FILL
            c.fillPaint.shader = LinearGradient(0f, sampleRect.top, 0f, sampleRect.bottom,
                Color.argb(255, 46, 46, 46), Color.WHITE, Shader.TileMode.CLAMP)
            canvas.drawPath(c.path, c.fillPaint)
            c.fillPaint.shader = null
            return
        }

        if (!skipGlassBody) {
            val ownCache = if (cache == null) BlurCache() else null
            val gc = cache ?: ownCache!!
            val radiusCanvas = glassRadius(p, ui.w, ui.h)
            val t0 = System.currentTimeMillis()
            val glassBmp = gc.obtain(photo, photoDst, sampleRect, p, radiusCanvas, density, sUnit, maxBlurEdge,
                wmRot, drawRect.width(), drawRect.height(),
                if (photoPreRotated) 0 else rot,
                photoScaleX = photoScaleX, photoScaleY = photoScaleY, cropApplied = cropApplied)
            val cost = System.currentTimeMillis() - t0
            if (cost > 120L) DebugLog.throttleLog("RENDER", "玻璃合成耗时=${cost}ms lite=$lite edge=$maxBlurEdge", 1000L)
            if (glassBmp != null && !glassBmp.isRecycled) {

                c.glassPaint.alpha = alpha(p.opacity, contentAlphaScale)
                canvas.drawBitmap(glassBmp, null, gc.patch, c.glassPaint)
            } else {

                c.fillPaint.reset(); c.fillPaint.isAntiAlias = true
                c.fillPaint.style = Paint.Style.FILL
                c.fillPaint.color = Color.argb(modulateOpacity(28, contentAlphaScale), 255, 255, 255)
                canvas.drawPath(c.path, c.fillPaint)
            }
            ownCache?.reset()
        }

        if (p.borderWidth > 0.01f && p.borderAlpha > 0) {
            c.strokePaint.reset(); c.strokePaint.isAntiAlias = true
            c.strokePaint.style = Paint.Style.STROKE
            c.strokePaint.strokeWidth = (p.borderWidth * density * sUnit)
                .coerceAtLeast(0.5f * density * sUnit)
            c.strokePaint.color = Color.argb(modulateOpacity(p.borderAlpha.coerceIn(0, 255), contentAlphaScale),
                255, 255, 255)
            canvas.drawPath(c.path, c.strokePaint)
        }

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
        applyTypeface(c, p)

        val activeMode = ctxActiveFontMode()
        if (!EditorState.isExporting) DebugLog.throttleLog(
            "TEXT",
            "绘制文字 model=\"${p.model}\" code=\"${p.modelCode}\" date=\"${p.dateText}\" " +
            "size=${ui.titleSize.toInt()}/${ui.subSize.toInt()} color=${Integer.toHexString(p.textColor)} " +
            "wmRect=${c.wmRect.width().toInt()}x${c.wmRect.height().toInt()} " +
            "fontMode=$activeMode(${if (activeMode == FONT_MODE_SYSTEM) "系统字体" else "默认字体"}) " +
            "typeface=$lastResolvedTypefaceName fstat=[${FontSupport.status()}]", 1000L)
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

        val tsh = min(ui.titleSize * 0.09f, 4.5f * pixelUnit)

        val shadowColor = if (p.fontContrast) 0xFF000000.toInt() else 0xD8000000.toInt()
        val shDy = min(tsh * 0.7f, 2.6f * pixelUnit)

        fun text2(paint: Paint, text: String, x: Float, y: Float) {
            val base = paint.color
            if (!skipShadow && p.fontShadow) {
                paint.color = shadowColor
                canvas.drawText(text, x + shDy, y + shDy, paint)
            }
            paint.color = base
            canvas.drawText(text, x, y, paint)
        }

        val timeSize = ui.subSize / p.fontCodeScale.coerceAtLeast(0.5f) * p.fontTimeScale
        val geoSize = ui.subSize / p.fontCodeScale.coerceAtLeast(0.5f) * p.fontGeoScale
        val blockShiftPx = p.fontBlockShift * density * ui.sUnit
        val titleShiftPx = p.fontTitleShift * density * ui.sUnit
        val codeShiftPx = p.fontCodeShift * density * ui.sUnit
        val timeShiftPx = p.fontTimeShift * density * ui.sUnit
        val geoShiftPx = p.fontGeoShift * density * ui.sUnit
        val lineGapPx = p.fontLineGap * density * ui.sUnit
        val x0 = c.wmRect.left + ui.padH + blockShiftPx
        val centerY = c.wmRect.centerY()

        val innerH = (ui.h - ui.padV * 2f).coerceAtLeast(1f)

        fun logoDrawSize(base: Float) = (base * p.brandScale).coerceIn(0.5f, innerH)

        val asp = if (hasLogo(p)) logoAspect(p) else 1f
        val gap = ui.gap
        val titleFM = c.titlePaint.fontMetrics
        val subFM = c.subPaint.fontMetrics

        val innerLeft = c.wmRect.left + ui.padH
        val innerRight = c.wmRect.right - ui.padH

        fun drawClamped(paint: Paint, text: String, desiredX: Float, baselineY: Float) {
            if (text.isEmpty()) return
            val w = paint.measureText(text)
            val innerW = (innerRight - innerLeft).coerceAtLeast(1f)
            val usePaint = if (w > innerW && w > 0f) {

                Paint(paint).also { it.textSize = paint.textSize * (innerW / w) * 0.98f }
            } else paint
            val tw = usePaint.measureText(text)
            val x = desiredX.coerceIn(innerLeft, (innerRight - tw).coerceAtLeast(innerLeft))
            text2(usePaint, text, x, baselineY)
        }

        fun drawStack(x: Float) {
            val codeOn = p.modelCode.isNotEmpty()
            val timeOn = p.dateText.isNotEmpty()
            val geoOn = p.geoText.isNotEmpty()
            val titleH = ui.titleSize * 1.22f
            val gapU = ui.subSize * 0.30f + lineGapPx
            val codeH = ui.subSize * 1.25f
            val timeH = timeSize * 1.25f
            val geoH = geoSize * 1.25f
            val dyT = dyPxOf(p.fontTimeDy, timeH)
            val dyG = dyPxOf(p.fontGeoDy, geoH)
            val dyC = dyPxOf(p.fontCodeDy, codeH)

            val blockH = titleH +
                (if (codeOn) gapU + codeH + abs(dyC) else 0f) +
                (if (timeOn) gapU + timeH + abs(dyT) else 0f) +
                (if (geoOn) gapU + geoH + abs(dyG) else 0f)
            var y = fitTop(centerY - blockH / 2f, blockH, c.wmRect, ui.padV)
            if (p.model.isNotEmpty()) {
                drawClamped(c.titlePaint, p.model, x + titleShiftPx, y - titleFM.ascent)
                y += titleH
            }
            if (codeOn) {
                c.subPaint.textSize = ui.subSize
                val fm = c.subPaint.fontMetrics
                drawClamped(c.subPaint, p.modelCode, x + codeShiftPx, y + dyC + gapU - fm.ascent)
                y += gapU + codeH + abs(dyC)
            }

            fun timeLine() {
                c.subPaint.textSize = timeSize
                val fm = c.subPaint.fontMetrics
                drawClamped(c.subPaint, p.dateText, x + timeShiftPx,
                    y + dyT + gapU - fm.ascent)
                y += gapU + timeH + abs(dyT)
            }
            fun geoLine() {
                c.subPaint.textSize = geoSize
                val fm = c.subPaint.fontMetrics
                drawClamped(c.subPaint, p.geoText, x + geoShiftPx,
                    y + dyG + gapU - fm.ascent)
                y += gapU + geoH + abs(dyG)
            }
            if (p.geoAboveTime) {
                if (geoOn) geoLine()
                if (timeOn) timeLine()
            } else {
                if (timeOn) timeLine()
                if (geoOn) geoLine()
            }
            c.subPaint.textSize = ui.subSize
        }

        when (p.layoutMode) {
            EditorState.LAYOUT_SINGLE -> {

                var x = x0
                val logoBase = p.titleSp * density * logoUnit * 1.5f
                if (hasLogo(p)) {

                    val slotW = logoBase * asp + gap * 2f
                    drawLogo(c, canvas, p, x, x + slotW, centerY,
                        logoDrawSize(logoBase), align = -1)
                    x += slotW
                }
                val titleBase = centerY - (titleFM.ascent + titleFM.descent) / 2f
                drawClamped(c.titlePaint, p.model, x + titleShiftPx, titleBase)
            }
            EditorState.LAYOUT_STACK -> drawStack(x0)
            EditorState.LAYOUT_SPLIT -> {

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
                val geoLine = p.geoText.isNotEmpty()

                val s2t = timeSize * 0.30f
                val timeLineH = s2t * 1.25f
                val geoLineH = geoSize * 1.25f
                val rowGap = s2t * 0.3f
                val dyT = dyPxOf(p.fontTimeDy, timeLineH)
                val dyG = dyPxOf(p.fontGeoDy, geoLineH)
                val dyC = dyPxOf(p.fontCodeDy, s2 * 1.25f)

                val timeBlockH = timeLineH * 2f + abs(dyT) * 2f +
                        if (geoLine) rowGap + geoLineH + abs(dyG) else 0f
                c.subPaint.textSize = ui.subSize
                var x = x0

                val codeLineH = s2 * 1.25f
                val leftBlockH = t2 + if (hasCode) codeLineH + abs(dyC) else 0f
                val leftTop = fitTop(centerY - leftBlockH / 2f, leftBlockH, c.wmRect, ui.padV)
                if (p.model.isNotEmpty()) {
                    c.titlePaint.textSize = t2
                    val titleFM2 = c.titlePaint.fontMetrics
                    drawClamped(c.titlePaint, p.model, x + titleShiftPx, leftTop - titleFM2.ascent)
                    c.titlePaint.textSize = ui.titleSize
                }
                if (hasCode) {
                    c.subPaint.textSize = s2
                    val codeFM = c.subPaint.fontMetrics
                    drawClamped(c.subPaint, p.modelCode, x + codeShiftPx,
                        leftTop + t2 + dyC - codeFM.ascent)
                    c.subPaint.textSize = ui.subSize
                }
                x += max(nameW, codeW) + flexGap
                if (hasLogo(p)) {

                    val logoBase = p.titleSp * density * logoUnit * 0.38f * 1.2f
                    val slotW = min(logoBase * asp, logoBase * 2.6f) + splitGap * 2f
                    drawLogo(c, canvas, p,
                        x - flexGap + splitGap, x + min(logoBase * asp, logoBase * 2.6f) + splitGap,
                        centerY, logoDrawSize(logoBase), align = 1)
                    x += slotW

                    val tc2 = p.textColor
                    c.fillPaint.reset(); c.fillPaint.isAntiAlias = true
                    c.fillPaint.color = Color.argb(modulateOpacity(170, contentAlphaScale),
                        Color.red(tc2), Color.green(tc2), Color.blue(tc2))
                    val vw = max(1.5f * pixelUnit, 2.2f * density * ui.sUnit)
                    val vh = min(timeBlockH * 1.1f,
                        (c.wmRect.height() - ui.padV * 2f - pixelUnit * 4f).coerceAtLeast(pixelUnit * 2f))
                    c.tmpRect.set(x, centerY - vh / 2f, x + vw, centerY + vh / 2f)
                    canvas.drawRoundRect(c.tmpRect, vw, vw, c.fillPaint)
                    x += vw + splitGap * 2f
                }

                var ty = fitTop(centerY - timeBlockH / 2f, timeBlockH, c.wmRect, ui.padV)
                fun timeRow(text: String) {
                    if (text.isEmpty()) return
                    c.subPaint.textSize = s2t
                    val fm = c.subPaint.fontMetrics
                    drawClamped(c.subPaint, text, x + timeShiftPx, ty + dyT - fm.ascent)
                    ty += timeLineH + abs(dyT)
                }
                fun geoRow() {
                    if (!geoLine) return
                    c.subPaint.textSize = geoSize
                    val fm = c.subPaint.fontMetrics
                    drawClamped(c.subPaint, p.geoText, x + geoShiftPx,
                        ty + dyG + rowGap - fm.ascent)
                    ty += rowGap + geoLineH + abs(dyG)
                }
                if (p.geoAboveTime) {
                    geoRow(); timeRow(splitD); timeRow(splitT)
                } else {
                    timeRow(splitD); timeRow(splitT); geoRow()
                }
                c.subPaint.textSize = ui.subSize
            }
            else -> {

                val logoBase = p.titleSp * density * logoUnit * 1.65f
                val logoSize = logoDrawSize(logoBase)
                val lineW = max(pixelUnit, 1.4f * density * ui.sUnit * min(ui.extend, 4f))

                val subLines = (if (p.modelCode.isNotEmpty()) 1 else 0) +
                        (if (p.dateText.isNotEmpty()) 1 else 0)
                val blockH = ui.titleSize * 1.22f +
                        if (subLines > 0) ui.subSize * 0.30f * subLines + ui.subSize * 1.25f * subLines else 0f
                val tc = p.textColor
                c.fillPaint.reset(); c.fillPaint.isAntiAlias = true
                c.fillPaint.color = Color.argb(modulateOpacity(100, contentAlphaScale),
                    Color.red(tc), Color.green(tc), Color.blue(tc))

                val sepMaxHalf = (c.wmRect.height() / 2f - ui.padV - pixelUnit * 2f).coerceAtLeast(pixelUnit)
                fun drawDiv(x: Float) {
                    val h = min(blockH * 0.42f, sepMaxHalf)
                    c.tmpRect.set(x, centerY - h, x + lineW, centerY + h)
                    canvas.drawRoundRect(c.tmpRect, lineW, lineW, c.fillPaint)
                }
                when {
                    p.logoAlign == 2 && hasLogo(p) -> {

                        drawLogo(c, canvas, p, x0, c.wmRect.right - ui.padH, centerY, logoSize, align = 0)
                    }
                    p.logoAlign == 1 && hasLogo(p) -> {

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

                        drawLogo(c, canvas, p, x, c.wmRect.right - ui.padH, centerY, logoSize, align = 1)
                    }
                    else -> {

                        val (dPart, tPart) = splitDateText(p.dateText)
                        val geoLine = p.geoText.isNotEmpty()
                        val dyT = dyPxOf(p.fontTimeDy, timeSize * 1.25f)
                        val dyG = dyPxOf(p.fontGeoDy, geoSize * 1.25f)
                        val dyC = dyPxOf(p.fontCodeDy, ui.subSize * 1.25f)
                        val gapU = ui.subSize * 0.30f + lineGapPx

                        val timeH = (if (dPart.isNotEmpty() || tPart.isNotEmpty()) timeSize * 1.25f * 2f + abs(dyT) else 0f) +
                                (if (geoLine) gapU + geoSize * 1.25f + abs(dyG) else 0f)
                        val leftH = ui.titleSize * 1.22f +
                                (if (p.modelCode.isNotEmpty()) gapU + ui.subSize * 1.25f + abs(dyC) else 0f)
                        val blockH2 = max(leftH, timeH)
                        var x = x0

                        var ly = fitTop(centerY - leftH / 2f, leftH, c.wmRect, ui.padV)
                        if (p.model.isNotEmpty()) {
                            drawClamped(c.titlePaint, p.model, x + titleShiftPx, ly - titleFM.ascent)
                            ly += ui.titleSize * 1.22f
                        }
                        if (p.modelCode.isNotEmpty()) {
                            drawClamped(c.subPaint, p.modelCode, x + codeShiftPx, ly + dyC + gapU - subFM.ascent)
                            ly += gapU + ui.subSize * 1.25f + abs(dyC)
                        }
                        x += max(
                            if (p.model.isNotEmpty()) c.titlePaint.measureText(p.model) else 0f,
                            if (p.modelCode.isNotEmpty()) c.subPaint.measureText(p.modelCode) else 0f) + gap * 2f

                        if (hasLogo(p)) {
                            val slotW = logoBase * asp + gap * 2f
                            drawLogo(c, canvas, p, x, x + slotW, centerY, logoSize, align = -1)
                            x += slotW
                        }

                        c.fillPaint.reset(); c.fillPaint.isAntiAlias = true
                        c.fillPaint.color = Color.argb(modulateOpacity(100, contentAlphaScale),
                            Color.red(tc), Color.green(tc), Color.blue(tc))

                        val sepMaxHalf2 = (c.wmRect.height() / 2f - ui.padV - pixelUnit * 2f)
                            .coerceAtLeast(pixelUnit)
                        val sepHalf = min(blockH2 * 0.42f, sepMaxHalf2)
                        c.tmpRect.set(x, centerY - sepHalf, x + lineW, centerY + sepHalf)
                        canvas.drawRoundRect(c.tmpRect, lineW, lineW, c.fillPaint)
                        x += lineW + gap * 2f

                        var ty = fitTop(centerY - timeH / 2f, timeH, c.wmRect, ui.padV)
                        fun drawTimePart(text: String) {
                            if (text.isEmpty()) return
                            c.subPaint.textSize = timeSize
                            val fm = c.subPaint.fontMetrics
                            drawClamped(c.subPaint, text, x + timeShiftPx, ty + dyT - fm.ascent)
                            ty += timeSize * 1.25f + abs(dyT)
                        }
                        fun drawGeoPart() {
                            if (!geoLine) return
                            c.subPaint.textSize = geoSize
                            val fm = c.subPaint.fontMetrics
                            drawClamped(c.subPaint, p.geoText, x + geoShiftPx, ty + dyG + gapU - fm.ascent)
                            ty += gapU + geoSize * 1.25f + abs(dyG)
                        }
                        if (p.geoAboveTime) {
                            drawGeoPart()
                            drawTimePart(dPart); drawTimePart(tPart)
                        } else {
                            drawTimePart(dPart); drawTimePart(tPart)
                            drawGeoPart()
                        }
                        c.subPaint.textSize = ui.subSize
                    }
                }
            }
        }
        c.titlePaint.clearShadowLayer()
        c.subPaint.clearShadowLayer()

        c.titlePaint.letterSpacing = 0f
        c.subPaint.letterSpacing = 0f
    }

    private fun drawLogo(c: Ctx, canvas: Canvas, p: EditorState.Params,
                         leftBound: Float, rightBound: Float, cy: Float, size: Float,
                         align: Int = 0): Float {
        val contentAlphaScale = WatermarkScale.opacityMultiplier(WatermarkScale.factor(p.scalePct))
        val col = modulateColorAlpha(p.textColor, contentAlphaScale)
        val L = leftBound
        val R = rightBound.coerceAtLeast(L + 1f)
        val availW = (R - L).coerceAtLeast(1f)
        val h0 = size.coerceAtLeast(1f)

        val brandAsset = if (p.logoBrand in EditorState.BRAND_ASSETS.indices)
            EditorState.BRAND_ASSETS[p.logoBrand] else null
        val brandBmp = if (p.logoBrand != EditorState.BRAND_NONE) loadBrandBitmap(p, brandAsset) else null
        if (brandBmp != null) {
            val bmpW = brandBmp.width.toFloat().coerceAtLeast(1f)
            val bmpH = brandBmp.height.toFloat().coerceAtLeast(1f)

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

