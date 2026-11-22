package com.liquidglass.watermark
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Movie
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

var darkModeState by mutableStateOf(true)
    private set

fun setDarkMode(v: Boolean) {
    darkModeState = v
    EditorState.params.darkMode = v
    EditorState.saveDarkGlobal(v)
}

const val API_DEFAULT = 0

const val FONT_MODE_DEFAULT = 0

const val FONT_MODE_SYSTEM = 1

const val FONT_MODE_SERIF = 2

const val FONT_MODE_MONO = 3

const val FONT_MODE_MAX = FONT_MODE_MONO

    const val API_HIGH = 1
const val API_LOW = 2

object EditorState {

    const val CROP_ORIGINAL = 0
    const val CROP_16_9 = 1     // 16:9
    const val CROP_4_3 = 2      // 4:3
    const val CROP_3_2 = 3      // 3:2
    const val CROP_1_1 = 4      // 1:1
    const val CROP_9_16 = 5     // 9:16
    const val CROP_3_4 = 6      // 3:4
    const val CROP_2_3 = 7      // 2:3

    @JvmStatic fun cropAspect(crop: Int): Float = when (crop) {
        CROP_16_9 -> 16f / 9f
        CROP_4_3 -> 4f / 3f
        CROP_3_2 -> 3f / 2f
        CROP_1_1 -> 1f
        CROP_9_16 -> 9f / 16f
        CROP_3_4 -> 3f / 4f
        CROP_2_3 -> 2f / 3f
        else -> 0f
    }

    const val GEO_OFF = 0
    const val GEO_EXIF = 1
    const val GEO_CURRENT = 2
    const val GEO_CUSTOM = 3

    const val GEO_STYLE_PLACE = 0
    const val GEO_STYLE_COORD = 1

    const val CANVAS_AUTO = 0
    const val CANVAS_LANDSCAPE = 1
    const val CANVAS_PORTRAIT = 2

    @JvmField var canvasOrientRev: Int = 0

    const val PREFS = "lgw_prefs"

    @JvmField var modelCustom: Boolean = false
    @JvmField var modelCodeCustom: Boolean = false

    @Volatile private var appCtx: Context? = null
    fun attach(ctx: Context) { if (appCtx == null) appCtx = ctx.applicationContext }

    @JvmField var gifFps: Int = GIF_FPS_DEFAULT

    @JvmField var gifEdgePx: Int = GIF_EDGE_DEFAULT

    @JvmField var gifTrimStartMs: Long = 0L

    @JvmField var gifTrimDurationMs: Long = 5_000L

    @JvmField var gifTrimCustomized: Boolean = false

    @JvmField var motionTrimStartMs: Long = 0L

    @JvmField var motionTrimDurationMs: Long = 5_000L

    @JvmField var motionTrimCustomized: Boolean = false
    private val GIF_FPS_VALUES = intArrayOf(15, 25, 30)
    fun sanitizeGifParams() {
        if (gifFps !in GIF_FPS_VALUES) gifFps = GIF_FPS_DEFAULT
        if (gifEdgePx != 480 && gifEdgePx != 720) gifEdgePx = GIF_EDGE_DEFAULT
        if (gifTrimStartMs < 0L) gifTrimStartMs = 0L
        if (gifTrimDurationMs < 1L) gifTrimDurationMs = 5_000L
        if (motionTrimStartMs < 0L) motionTrimStartMs = 0L
        if (motionTrimDurationMs < 1L) motionTrimDurationMs = 5_000L
    }

    fun brandLogoWide(brand: Int): Boolean {
        val asset = BRAND_ASSETS.getOrNull(brand) ?: return false
        val ctx = appCtx ?: return false
        return runCatching {
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.assets.open(asset).use { android.graphics.BitmapFactory.decodeStream(it, null, o) }
            o.outWidth > 0 && o.outHeight > 0 && o.outWidth > o.outHeight * 1.5f
        }.getOrDefault(false)
    }

    fun saveDarkGlobal(v: Boolean) {
        val c = appCtx ?: return
        runCatching { c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean("globalDark", v).apply() }
    }

    fun saveApiModeGlobal(v: Int) {
        val c = appCtx ?: return
        runCatching { c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt("apiModeGlobal", v).apply() }
    }

    const val PARAMS_VERSION = 6

    const val GIF_FPS_DEFAULT = 25
    const val GIF_EDGE_DEFAULT = 720

    const val PLAN_VERSION = 5

    const val SHAPE_ROUNDED = 0
    const val SHAPE_CAPSULE = 1
    const val SHAPE_ELLIPSE = 2
    const val SHAPE_RECT = 3

    const val HL_PLAIN = 0
    const val HL_DEFAULT = 1
    const val HL_AMBIENT = 2

    const val GLASS_TIER_HIGH = 0
    const val GLASS_TIER_LOW = 1

    const val LAYOUT_CLASSIC = 0
    const val LAYOUT_SINGLE = 1
    const val LAYOUT_STACK = 2
    const val LAYOUT_SPLIT = 3

    const val LOGO_DOT = 0
    const val LOGO_CAMERA = 1
    const val LOGO_NONE = 2

    const val BRAND_NONE = -1
    const val BRAND_LEICA = 0
    const val BRAND_ZEISS = 1
    const val BRAND_HASSELBLAD = 2
    const val BRAND_HUAWEI = 3
    const val BRAND_XIAOMI = 4
    const val BRAND_VIVO = 5
    const val BRAND_CUSTOM = 6
    const val BRAND_XMAGE = 7
    const val BRAND_ARRI = 8
    const val BRAND_SONY = 9
    const val BRAND_SAMSUNG = 10
    const val BRAND_APPLE = 11
    const val BRAND_GOOGLE = 12
    const val BRAND_OPPO = 13
    const val BRAND_ONEPLUS = 14
    const val BRAND_HONOR = 15
    const val BRAND_REALME = 16
    const val BRAND_CANON = 17
    const val BRAND_NIKON = 18
    const val BRAND_FUJI = 19
    const val BRAND_RICOH = 20
    const val BRAND_APPLE_W = 21
    val BRAND_NAMES = arrayOf(
        "徕卡", "蔡司", "哈苏", "华为", "小米", "vivo", "自定义",
        "XMAGE", "ARRI", "索尼", "三星", "苹果", "谷歌", "OPPO", "一加", "荣耀", "真我",
        "佳能", "尼康", "富士", "理光", "苹果白"
    )
    val BRAND_ASSETS = arrayOf(
        "brands/leica.png", "brands/zeiss.png", "brands/hasselblad.png",
        "brands/huawei.png", "brands/xiaomi.png", "brands/vivo.png", null,
        "brands/xmage.png", "brands/arri.png", "brands/sony.png", "brands/samsung.png",
        "brands/apple.png", "brands/google.png", "brands/oppo.png", "brands/oneplus.png",
        "brands/honor.png", "brands/realme.png", "brands/canon.png", "brands/nikon.png",
        "brands/fuji.png", "brands/ricoh.png", "brands/apple_w.png"
    )

    class CustomLogoEntry(
        @JvmField var name: String,
        @JvmField var path: String,
        @JvmField var shape: Int,
        @JvmField var crop: Boolean
    )

    class Params {

        @JvmField var shape: Int = SHAPE_CAPSULE

        @JvmField var cornerPct: Int = 75
        @JvmField var padHDp: Float = 15f
        @JvmField var padVDp: Float = 9f
        @JvmField var scalePct: Int = 100

        @JvmField var wmLengthPct: Float = 1f
        @JvmField var rotation: Int = 0

        @JvmField var blur: Int = 8
        @JvmField var refractH: Int = 50
        @JvmField var refractA: Int = 55

        @JvmField var warp: Int = 25
        @JvmField var edgeWarp: Int = 0
        @JvmField var innerWarp: Int = 0
        @JvmField var chromatic: Int = 35
        @JvmField var depthEffect: Boolean = true
        @JvmField var vibrancy: Int = 65
        @JvmField var specular: Int = 55
        @JvmField var highlightStyle: Int = HL_DEFAULT
        @JvmField var hlAngle: Float = 45f
        @JvmField var falloff: Float = 1.1f
        @JvmField var tintColor: Int = 0xFFFFFFFF.toInt()
        @JvmField var tintStrength: Int = 0
        @JvmField var innerShadow: Int = 26
        @JvmField var shadow: Int = 72

        @JvmField var dispersion: Int = 18
        @JvmField var edgeLight: Int = 50
        @JvmField var opacity: Int = 62
        @JvmField var glassContrast: Float = 1.1f

        @JvmField var glassTier: Int = GLASS_TIER_HIGH

        @JvmField var scaleComp: Int = 100

        @JvmField var ios27Style: Boolean = false

        @JvmField var model: String = ""
        @JvmField var modelCode: String = ""
        @JvmField var dateText: String = ""
        @JvmField var titleSp: Float = 14.5f
        @JvmField var subSp: Float = 10f

        @JvmField var cropRatio: Int = CROP_ORIGINAL

        @JvmField var geoMode: Int = GEO_OFF

        @JvmField var geoText: String = ""

        @JvmField var geoLat: Double = 0.0
        @JvmField var geoLng: Double = 0.0

        @JvmField var geoStyle: Int = GEO_STYLE_PLACE

        @JvmField var geoAboveTime: Boolean = false

        @JvmField var canvasOrient: Int = CANVAS_AUTO

        @JvmField var fontMode: Int = FONT_MODE_DEFAULT
        @JvmField var textColor: Int = 0xFFFFFFFF.toInt()
        @JvmField var showDevice: Boolean = true
        @JvmField var showTime: Boolean = true
        @JvmField var fontContrast: Boolean = true

        @JvmField var fontTitleScale: Float = 1f

        @JvmField var fontCodeScale: Float = 1f

        @JvmField var fontTimeScale: Float = 1f

        @JvmField var fontGeoScale: Float = 1f

        @JvmField var fontTitleShift: Float = 0f

        @JvmField var fontCodeShift: Float = 0f

        @JvmField var fontTimeShift: Float = 0f

        @JvmField var fontGeoShift: Float = 0f

        @JvmField var fontTimeDy: Float = 0f
        @JvmField var fontGeoDy: Float = 0f

        @JvmField var fontCodeDy: Float = 0f

        @JvmField var fontBlockShift: Float = 0f

        @JvmField var fontLineGap: Float = 0f

        @JvmField var fontLetterSpacing: Float = 0f

        @JvmField var fontShadow: Boolean = true

        @JvmField var autoTz: Boolean = true
        @JvmField var borderWidth: Float = 1f
        @JvmField var borderAlpha: Int = 0
        @JvmField var layoutMode: Int = LAYOUT_CLASSIC
        @JvmField var logoStyle: Int = LOGO_DOT

        @JvmField var logoBrand: Int = BRAND_NONE

        @JvmField var logoAlign: Int = 0

        @JvmField var customLogoPath: String = ""

        @JvmField var customLogoName: String = ""

        @JvmField var customLogos: ArrayList<CustomLogoEntry> = ArrayList()

        @JvmField var logoShape: Int = 0

        @JvmField var logoCrop: Boolean = true

        @JvmField var brandScale: Float = 1f

        @JvmField var logoStretch: Float = 1f
        @JvmField var darkMode: Boolean = true
        @JvmField var softwareBlur: Boolean = true
        @JvmField var apiMode: Int = API_DEFAULT

        @JvmField var relX: Float = 0.5f
        @JvmField var relY: Float = 0.9f
        @JvmField var autoClean: Boolean = true

        @JvmField var exportMode: Int = 0

        @JvmField var refractionModel: Int = 0

        @JvmField var fluidEnabled: Boolean = false
        @JvmField var fluidSpeed: Int = 30

        @JvmField var breathEnabled: Boolean = false
        @JvmField var breathStrength: Int = 50

        fun copyFrom(s: Params) {
            if (this === s) return
            shape=s.shape; cornerPct=s.cornerPct; padHDp=s.padHDp; padVDp=s.padVDp
            scalePct=s.scalePct; wmLengthPct=s.wmLengthPct; rotation=s.rotation
            blur=s.blur; refractH=s.refractH; refractA=s.refractA
            warp = s.warp; deriveWarp(); chromatic=s.chromatic
            depthEffect=s.depthEffect; vibrancy=s.vibrancy; specular=s.specular
            highlightStyle=s.highlightStyle; hlAngle=s.hlAngle; falloff=s.falloff
            tintColor=s.tintColor; tintStrength=s.tintStrength; innerShadow=s.innerShadow; shadow=s.shadow
            dispersion=s.dispersion; edgeLight=s.edgeLight; opacity=s.opacity; glassContrast=s.glassContrast
            glassTier=s.glassTier; scaleComp=s.scaleComp
            ios27Style=s.ios27Style
            fluidEnabled=s.fluidEnabled; fluidSpeed=s.fluidSpeed
            refractionModel=s.refractionModel
            breathEnabled=s.breathEnabled; breathStrength=s.breathStrength
            exportMode=s.exportMode
            model=s.model; modelCode=s.modelCode; dateText=s.dateText
            titleSp=s.titleSp; subSp=s.subSp; textColor=s.textColor
            showDevice=s.showDevice; showTime=s.showTime; fontContrast=s.fontContrast
            fontTitleScale=s.fontTitleScale; fontCodeScale=s.fontCodeScale
            fontTimeScale=s.fontTimeScale; fontGeoScale=s.fontGeoScale
            fontTitleShift=s.fontTitleShift; fontCodeShift=s.fontCodeShift
            fontTimeShift=s.fontTimeShift; fontGeoShift=s.fontGeoShift
            fontTimeDy=s.fontTimeDy; fontGeoDy=s.fontGeoDy; fontCodeDy=s.fontCodeDy
            fontBlockShift=s.fontBlockShift; fontLineGap=s.fontLineGap
            fontLetterSpacing=s.fontLetterSpacing; fontShadow=s.fontShadow; autoTz=s.autoTz
            fontMode=s.fontMode
            borderWidth=s.borderWidth; borderAlpha=s.borderAlpha
            layoutMode=s.layoutMode; logoStyle=s.logoStyle; logoBrand=s.logoBrand; logoAlign=s.logoAlign
            customLogoPath=s.customLogoPath; customLogoName=s.customLogoName; brandScale=s.brandScale; logoStretch=s.logoStretch
            logoShape=s.logoShape; logoCrop=s.logoCrop
            // Keep per-frame render snapshots allocation-free after warmup while retaining
            // detached CustomLogoEntry objects (the source and destination never share entries).
            while (customLogos.size > s.customLogos.size) customLogos.removeAt(customLogos.lastIndex)
            for (i in s.customLogos.indices) {
                val src = s.customLogos[i]
                if (i < customLogos.size) {
                    val dst = customLogos[i]
                    dst.name = src.name; dst.path = src.path; dst.shape = src.shape; dst.crop = src.crop
                } else {
                    customLogos.add(CustomLogoEntry(src.name, src.path, src.shape, src.crop))
                }
            }
            darkMode=s.darkMode; softwareBlur=s.softwareBlur; apiMode=s.apiMode
            relX=s.relX; relY=s.relY; autoClean=s.autoClean

            cropRatio=s.cropRatio; geoMode=s.geoMode; geoText=s.geoText
            geoLat=s.geoLat; geoLng=s.geoLng; geoStyle=s.geoStyle
            geoAboveTime=s.geoAboveTime
            canvasOrient=s.canvasOrient
        }

        fun deriveWarp() {
            val w = warp.coerceIn(0, 100)
            edgeWarp = EditorState.warpEdge(w)
            innerWarp = EditorState.warpInner(w)
        }

        /** Reject non-finite presets and keep render geometry inside supported limits. */
        fun sanitizeInPlace() {
            fun finite(value: Float, default: Float, min: Float, max: Float): Float =
                if (value.isFinite()) value.coerceIn(min, max) else default
            padHDp = finite(padHDp, 15f, 0f, 100f)
            padVDp = finite(padVDp, 9f, 0f, 100f)
            wmLengthPct = finite(wmLengthPct, 1f, 0f, 5f)
            hlAngle = finite(hlAngle, 45f, -360f, 360f)
            falloff = finite(falloff, 1.1f, 0.1f, 8f)
            glassContrast = finite(glassContrast, 1.1f, 0.5f, 2f)
            titleSp = finite(titleSp, 14.5f, 1f, 200f)
            subSp = finite(subSp, 10f, 1f, 200f)
            borderWidth = finite(borderWidth, 1f, 0f, 20f)
            brandScale = finite(brandScale, 1f, 0.05f, 10f)
            logoStretch = finite(logoStretch, 1f, 0.1f, 10f)
            relX = finite(relX, 0.5f, 0f, 1f)
            relY = finite(relY, 0.9f, 0f, 1f)
            shape = shape.coerceIn(SHAPE_ROUNDED, SHAPE_RECT)
            cornerPct = cornerPct.coerceIn(0, 100)
            scalePct = scalePct.coerceIn(WatermarkScale.MIN_PERCENT, WatermarkScale.MAX_PERCENT)
            rotation = ((rotation % 360) + 360) % 360
            blur = blur.coerceIn(0, 100)
            refractH = refractH.coerceIn(0, 100); refractA = refractA.coerceIn(0, 100)
            warp = warp.coerceIn(0, 100); deriveWarp()
            chromatic = chromatic.coerceIn(0, 100); vibrancy = vibrancy.coerceIn(0, 100)
            specular = specular.coerceIn(0, 100); highlightStyle = highlightStyle.coerceIn(0, 2)
            tintStrength = tintStrength.coerceIn(0, 100); innerShadow = innerShadow.coerceIn(0, 100)
            shadow = shadow.coerceIn(0, 100); dispersion = dispersion.coerceIn(0, 100)
            edgeLight = edgeLight.coerceIn(0, 100); opacity = opacity.coerceIn(0, 100)
            borderAlpha = borderAlpha.coerceIn(0, 255); layoutMode = layoutMode.coerceIn(0, 3)
            logoStyle = logoStyle.coerceIn(0, 2); logoAlign = logoAlign.coerceIn(0, 2)
            logoShape = logoShape.coerceIn(0, 3); logoBrand = logoBrand.coerceIn(-1, BRAND_ASSETS.lastIndex)
            refractionModel = refractionModel.coerceIn(0, 1); exportMode = exportMode.coerceIn(0, 2)
            glassTier = glassTier.coerceIn(GLASS_TIER_HIGH, GLASS_TIER_LOW); scaleComp = scaleComp.coerceIn(0, 100)
            fontMode = fontMode.coerceIn(FONT_MODE_DEFAULT, FONT_MODE_MAX)
            fluidSpeed = fluidSpeed.coerceIn(1, 100); breathStrength = breathStrength.coerceIn(0, 100)
            cropRatio = cropRatio.coerceIn(CROP_ORIGINAL, CROP_2_3)   // v15.32
            geoMode = geoMode.coerceIn(GEO_OFF, GEO_CUSTOM)            // v15.32
            fontTitleScale = fontTitleScale.coerceIn(0.5f, 2.5f)
            fontCodeScale = fontCodeScale.coerceIn(0.5f, 2.5f)
            fontTimeScale = fontTimeScale.coerceIn(0.5f, 2.5f)
            fontGeoScale = fontGeoScale.coerceIn(0.5f, 2.5f)
            fontTitleShift = fontTitleShift.coerceIn(-60f, 60f)
            fontCodeShift = fontCodeShift.coerceIn(-60f, 60f)
            fontTimeShift = fontTimeShift.coerceIn(-60f, 60f)
            fontGeoShift = fontGeoShift.coerceIn(-60f, 60f)
            fontBlockShift = fontBlockShift.coerceIn(-60f, 60f)
            fontLineGap = fontLineGap.coerceIn(-8f, 24f)
            fontLetterSpacing = fontLetterSpacing.coerceIn(0f, 0.6f)
            fontTimeDy = fontTimeDy.coerceIn(-60f, 60f)
            fontGeoDy = fontGeoDy.coerceIn(-60f, 60f)
            fontCodeDy = fontCodeDy.coerceIn(-60f, 60f)
            geoStyle = geoStyle.coerceIn(GEO_STYLE_PLACE, GEO_STYLE_COORD)
            canvasOrient = canvasOrient.coerceIn(CANVAS_AUTO, CANVAS_PORTRAIT)
            if (geoLat !in -90.0..90.0) geoLat = 0.0
            if (geoLng !in -180.0..180.0) geoLng = 0.0
        }

        fun effective(): Params = this
    }

    @Volatile private var exportParams: Params? = null
    private val renderParamsLocal = ThreadLocal<Params>()
    /** Freeze editor values once; preview and export never share mutable rendering state. */
    fun captureExportParams() {
        exportParams = Params().apply { copyFrom(params); sanitizeInPlace() }
        exportTimeSec = if (!isGif && motionMp4 == null && motionUri == null && webpBytes == null)
            mediaPreviewTimeSec else 0f
    }
    fun releaseExportParams() { exportParams = null }
    /**
     * Reusable, thread-local draw snapshot. Callers must consume it synchronously and must not
     * retain it across later renderParams() calls on the same thread.
     */
    @JvmStatic fun renderParams(): Params {
        val snapshot = renderParamsLocal.get() ?: Params().also(renderParamsLocal::set)
        snapshot.copyFrom(exportParams ?: params)
        snapshot.sanitizeInPlace()
        return snapshot
    }

    const val PLAN_CLASSIC = 0
    const val PLAN_LIGHT = 1
    const val PLAN_COUNT = 2
    @JvmField var activePlan: Int = PLAN_LIGHT

    var params: Params = Params()

    @JvmField val brandScales = mutableMapOf<Int, Float>()

    private val planParams = arrayOf(Params(), Params()).also { arr ->

        arr[0].cornerPct = 55

        arr[1].layoutMode = LAYOUT_SPLIT
        arr[1].cornerPct = 75
        arr[1].titleSp = 34f; arr[1].subSp = 22f
        arr[1].wmLengthPct = 0f
        arr[1].relX = 0.5f; arr[1].relY = 0.55f
    }

    @JvmStatic fun planParamsAt(i: Int): Params = planParams[i.coerceIn(0, PLAN_COUNT - 1)]

    fun warpEdge(w: Int): Int =
        (smoothstep01(w.coerceIn(0, 100) / 100f, 0f, 0.7f) * 100f)
            .roundToInt().coerceIn(0, 100)

    fun warpInner(w: Int): Int =
        (smoothstep01(w.coerceIn(0, 100) / 100f, 0.30f, 1f) * 88f)
            .roundToInt().coerceIn(0, 100)

    private fun smoothstep01(x: Float, e0: Float, e1: Float): Float {
        if (e1 - e0 < 1e-6f) return if (x < e0) 0f else 1f
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    @Volatile private var previewBitmapField: Bitmap? = null

    var previewBitmap: Bitmap?
        get() = previewBitmapField
        set(value) {
            if (previewBitmapField === value) return
            previewBitmapField = value
            mediaRev++
        }

    var mediaRev by mutableStateOf(0)
        private set
    fun bumpMedia() { mediaRev++ }

    var exportBarRev by mutableStateOf(0)
        private set
    fun bumpExportBar() { exportBarRev++ }

    var dockGlassRev by mutableStateOf(0)
        private set
    @JvmField var dockFrost: Int = 40
    @JvmField var dockBlur: Int = 50

    @JvmField var dockRefractH: Int = 45
    @JvmField var dockRefract: Int = 55
    @JvmField var dockTint: Int = 35
    @JvmField var dockRim: Int = 50
    @JvmField var dockAlpha: Int = 55
    @JvmField var dockDisperse: Int = 25
    @JvmField var dockFlow: Int = 0
    fun bumpDockGlass() { dockGlassRev++ }
    fun persistDockGlass(ctx: android.content.Context) {
        runCatching {
            ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit()
                .putInt("dockFrost", dockFrost)
                .putInt("dockBlur", dockBlur)
                .putInt("dockRefractH", dockRefractH)
                .putInt("dockRefract", dockRefract)
                .putInt("dockTint", dockTint)
                .putInt("dockRim", dockRim)
                .putInt("dockAlpha", dockAlpha)
                .putInt("dockDisperse", dockDisperse)
                .putInt("dockFlow", dockFlow)
                .apply()
        }
    }

    @Volatile @JvmField var previewCustomBitmap: Bitmap? = null
    fun setPreviewCustom(b: Bitmap?) {
        if (previewCustomBitmap === b) return
        val old = previewCustomBitmap
        previewCustomBitmap = b
        runCatching { old?.let { if (!it.isRecycled && it !== b) it.recycle() } }
    }
    @JvmField var sourceUri: android.net.Uri? = null
    @Volatile @JvmField var isDemo: Boolean = true

    @Volatile @JvmField var isExporting: Boolean = false

    @Volatile @JvmField var exportTimeSec: Float = 0f
    @Volatile @JvmField var mediaPreviewTimeSec: Float = 0f

    @Volatile @JvmField var sliderInteracting: Boolean = false

    @Volatile @JvmField var lastSliderMoveMs: Long = 0L
    @JvmField var liveSeconds: Boolean = false

    @JvmField var photoRotation: Int = 0

    @Volatile @JvmField var motionDecodeFailed: Boolean = false

    @Volatile @JvmField var gifMovie: Movie? = null
    @Volatile @JvmField var isGif: Boolean = false
    @JvmField var gifDuration: Int = 0
    @Volatile @JvmField var gifBytes: ByteArray? = null

    @Volatile @JvmField var motionFrames: MutableList<android.graphics.Bitmap>? = null
    @Volatile @JvmField var motionDelays: IntArray? = null

    @Volatile @JvmField var motionMp4: ByteArray? = null

    @Volatile @JvmField var motionForceVideoRot: Int = -1

    @Volatile @JvmField var motionUri: android.net.Uri? = null

    @Volatile @JvmField var motionLocalPath: String? = null

    @Volatile @JvmField var webpBytes: ByteArray? = null

    @Volatile @JvmField var motionCoverBitmap: android.graphics.Bitmap? = null

    var motionExportFormat by mutableStateOf("mp4")
    /** Motion-photo container choice: 0=observed vivo-style pair; 1=Google XMP single-JPEG format. Gallery support is device-specific. */
    var motionPhotoFormat by mutableStateOf(1)
    fun clearMotion() {
        mediaPreviewTimeSec = 0f

        if (motionFrames?.contains(previewBitmap) == true || previewBitmap === motionCoverBitmap) {
            previewBitmap = null
        }
        motionFrames?.forEach { runCatching { if (!it.isRecycled) it.recycle() } }
        motionFrames = null; motionDelays = null; motionMp4 = null; motionUri = null; webpBytes = null

        motionLocalPath?.let { p -> runCatching { java.io.File(p).delete() } }
        motionLocalPath = null
        motionForceVideoRot = -1
        runCatching { motionCoverBitmap?.let { if (!it.isRecycled) it.recycle() } }
        motionCoverBitmap = null

        bumpMedia()
    }

    fun nowString(): String {
        val sys = Locale.getDefault()
        val zh = sys.language.startsWith("zh")
        val fmt = if (params.autoTz) {
            if (zh) "yyyy-MM-dd HH:mm:ss" else "MMM d, yyyy h:mm:ss a"
        } else "yyyy-MM-dd HH:mm:ss"
        val locale = if (zh) Locale.CHINA else Locale.US
        return SimpleDateFormat(fmt, locale).format(Date())
    }
    fun defaultModelName(): String = DeviceNames.marketName()

    fun defaultModelCode(): String = DeviceNames.modelCode().ifBlank {
        DeviceNames.brand().ifBlank { "My Phone" }
    }

    private fun planKey(k: String): String = "plan${activePlan}_$k"

    fun switchPlan(ctx: Context, idx: Int) {
        if (idx == activePlan || idx !in 0 until PLAN_COUNT) return
        persist(ctx)

        if (idx == PLAN_LIGHT) {
            val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (sp.getInt("plan2Ver", 0) < PLAN_VERSION) resetPlan2(ctx)
        }
        activePlan = idx

        runCatching {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("activePlan", idx).apply()
        }
        params = planParams[idx]
        restore(ctx)
    }

    fun resetPlan(ctx: Context, idx: Int) {
        try {
            val pi = idx.coerceIn(0, PLAN_COUNT - 1)
            val d = Params()
            if (pi == PLAN_CLASSIC) {

                d.cornerPct = 55
            } else {

                d.layoutMode = LAYOUT_SPLIT
                d.cornerPct = 75
                d.titleSp = 34f; d.subSp = 22f
                d.wmLengthPct = 0f
                d.relX = 0.5f; d.relY = 0.55f
            }
            d.model = defaultModelName(); d.modelCode = defaultModelCode(); d.dateText = nowString()

            planParams[pi].copyFrom(d)

            planParams[pi].darkMode = darkModeState

            planParams[pi].fontMode = params.fontMode

            val e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            fun pk(k: String) = "plan${pi}_$k"
            e.putInt("saved", 10); e.putInt("pver", PARAMS_VERSION); e.putInt("planVer", PLAN_VERSION)
            if (pi == PLAN_LIGHT) e.putInt("plan2Ver", PLAN_VERSION)
            e.putInt(pk("shape"), d.shape); e.putInt(pk("cornerPct"), d.cornerPct)
            e.putFloat(pk("padH"), d.padHDp); e.putFloat(pk("padV"), d.padVDp)
            e.putInt(pk("scalePct"), d.scalePct); e.putInt(pk("rotation"), d.rotation)
            e.putFloat(pk("wmLengthPct"), d.wmLengthPct)
            e.putInt(pk("blur"), d.blur); e.putInt(pk("refractH"), d.refractH); e.putInt(pk("refractA"), d.refractA)
            e.putInt(pk("warp"), d.warp)
            e.putInt(pk("refractionModel"), d.refractionModel)
            e.putInt(pk("chromatic"), d.chromatic); e.putInt(pk("edgeWarp"), d.edgeWarp); e.putInt(pk("innerWarp"), d.innerWarp); e.putBoolean(pk("depthEffect"), d.depthEffect)
            e.putInt(pk("vibrancy"), d.vibrancy); e.putInt(pk("specular"), d.specular)
            e.putInt(pk("hlStyle"), d.highlightStyle); e.putFloat(pk("hlAngle"), d.hlAngle); e.putFloat(pk("falloff"), d.falloff)
            e.putInt(pk("tintColor"), d.tintColor); e.putInt(pk("tintStrength"), d.tintStrength)
            e.putInt(pk("innerShadow"), d.innerShadow); e.putInt(pk("shadow"), d.shadow)
            e.putInt(pk("dispersion"), d.dispersion); e.putInt(pk("edgeLight"), d.edgeLight); e.putInt(pk("opacity"), d.opacity)
            e.putFloat(pk("glassContrast"), d.glassContrast)
            e.putInt(pk("glassTier"), d.glassTier); e.putInt(pk("scaleComp"), d.scaleComp)
            e.putFloat(pk("titleSp"), d.titleSp); e.putFloat(pk("subSp"), d.subSp)
            e.putInt(pk("textColor"), d.textColor); e.putInt(pk("layoutMode"), d.layoutMode)
            e.putInt(pk("logoStyle"), d.logoStyle); e.putInt(pk("logoBrand"), d.logoBrand); e.putInt(pk("logoAlign"), d.logoAlign)
            e.putString(pk("customLogoPath"), d.customLogoPath)

            e.putString(pk("customLogoName"), d.customLogoName)
            e.putInt(pk("logoShape"), d.logoShape); e.putBoolean(pk("logoCrop"), d.logoCrop)
            e.putString(pk("customLogos"), d.customLogos.joinToString("\u0001") { e0 ->
                "${e0.name}\u0002${e0.path}\u0002${e0.shape}\u0002${e0.crop}"
            })
            e.putFloat(pk("brandScale"), d.brandScale); e.putFloat(pk("logoStretch"), d.logoStretch)
            e.putBoolean(pk("showDevice"), d.showDevice); e.putBoolean(pk("showTime"), d.showTime); e.putBoolean(pk("fontContrast"), d.fontContrast)
            e.putFloat(pk("fontTitleScale"), d.fontTitleScale); e.putFloat(pk("fontCodeScale"), d.fontCodeScale)
            e.putFloat(pk("fontTimeScale"), d.fontTimeScale); e.putFloat(pk("fontGeoScale"), d.fontGeoScale)
            e.putFloat(pk("fontTitleShift"), d.fontTitleShift); e.putFloat(pk("fontCodeShift"), d.fontCodeShift)
            e.putFloat(pk("fontTimeShift"), d.fontTimeShift); e.putFloat(pk("fontGeoShift"), d.fontGeoShift)
            e.putFloat(pk("fontTimeDy"), d.fontTimeDy); e.putFloat(pk("fontGeoDy"), d.fontGeoDy)
            e.putFloat(pk("fontBlockShift"), d.fontBlockShift); e.putFloat(pk("fontLineGap"), d.fontLineGap)
            e.putFloat(pk("fontLetterSpacing"), d.fontLetterSpacing)
            e.putBoolean(pk("fontShadow"), d.fontShadow); e.putBoolean(pk("autoTz"), d.autoTz)
            e.putFloat(pk("borderW"), d.borderWidth); e.putInt(pk("borderA"), d.borderAlpha)
            e.putFloat(pk("relX"), d.relX); e.putFloat(pk("relY"), d.relY)
            e.putBoolean(pk("darkMode"), d.darkMode); e.putBoolean(pk("softwareBlur"), d.softwareBlur)
            e.putInt(pk("apiMode"), d.apiMode)
            e.putBoolean(pk("breathEnabled"), d.breathEnabled); e.putInt(pk("breathStrength"), d.breathStrength)
            e.putString(pk("model"), d.model); e.putString(pk("modelCode"), d.modelCode)
            e.putBoolean(pk("modelCustom"), false); e.putBoolean(pk("modelCodeCustom"), false)
            if (pi == activePlan) { modelCustom = false; modelCodeCustom = false }
            e.putBoolean(pk("autoClean"), d.autoClean)
            e.putBoolean(pk("fluidEnabled"), d.fluidEnabled); e.putInt(pk("fluidSpeed"), d.fluidSpeed)
            e.putInt(pk("exportMode"), d.exportMode)

            e.putInt(pk("cropRatio"), d.cropRatio)
            e.putInt(pk("geoMode"), d.geoMode)
            e.putString(pk("geoText"), d.geoText)
            e.putFloat(pk("geoLat"), d.geoLat.toFloat()); e.putFloat(pk("geoLng"), d.geoLng.toFloat())
            e.putInt(pk("geoStyle"), d.geoStyle); e.putBoolean(pk("geoAboveTime"), d.geoAboveTime)
            e.putInt(pk("canvasOrient"), d.canvasOrient)
            e.apply()
        } catch (_: Throwable) {}
    }

    fun resetPlan2(ctx: Context) = resetPlan(ctx, PLAN_LIGHT)
    fun persist(ctx: Context) {
        try {
            val p = params
            val e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            e.putInt("saved", 10)
            e.putInt("pver", PARAMS_VERSION)
            e.putInt("planVer", PLAN_VERSION)
            e.putInt("activePlan", activePlan)
            e.putInt(planKey("shape"), p.shape); e.putInt(planKey("cornerPct"), p.cornerPct)
            e.putFloat(planKey("padH"), p.padHDp); e.putFloat(planKey("padV"), p.padVDp)
            e.putInt(planKey("scalePct"), p.scalePct); e.putInt(planKey("rotation"), p.rotation)
            e.putFloat(planKey("wmLengthPct"), p.wmLengthPct)
            e.putInt(planKey("blur"), p.blur); e.putInt(planKey("refractH"), p.refractH); e.putInt(planKey("refractA"), p.refractA)
            e.putInt(planKey("warp"), p.warp)
            e.putInt(planKey("refractionModel"), p.refractionModel)
            e.putInt(planKey("chromatic"), p.chromatic); e.putInt(planKey("edgeWarp"), p.edgeWarp); e.putInt(planKey("innerWarp"), p.innerWarp); e.putBoolean(planKey("depthEffect"), p.depthEffect)
            e.putInt(planKey("vibrancy"), p.vibrancy); e.putInt(planKey("specular"), p.specular)
            e.putInt(planKey("hlStyle"), p.highlightStyle); e.putFloat(planKey("hlAngle"), p.hlAngle); e.putFloat(planKey("falloff"), p.falloff)
            e.putInt(planKey("tintColor"), p.tintColor); e.putInt(planKey("tintStrength"), p.tintStrength)
            e.putInt(planKey("innerShadow"), p.innerShadow); e.putInt(planKey("shadow"), p.shadow)
            e.putInt(planKey("dispersion"), p.dispersion); e.putInt(planKey("edgeLight"), p.edgeLight); e.putInt(planKey("opacity"), p.opacity)
            e.putFloat(planKey("glassContrast"), p.glassContrast)
            e.putInt(planKey("glassTier"), p.glassTier); e.putInt(planKey("scaleComp"), p.scaleComp)
            e.putBoolean(planKey("ios27Style"), p.ios27Style)   // v15.28
            e.putFloat(planKey("titleSp"), p.titleSp); e.putFloat(planKey("subSp"), p.subSp)
            e.putInt(planKey("textColor"), p.textColor); e.putInt(planKey("layoutMode"), p.layoutMode)
            e.putInt(planKey("logoStyle"), p.logoStyle)
            e.putInt(planKey("logoBrand"), p.logoBrand)
            e.putInt(planKey("logoAlign"), p.logoAlign)
            e.putString(planKey("customLogoPath"), p.customLogoPath)
            e.putString(planKey("customLogoName"), p.customLogoName)
            e.putInt(planKey("logoShape"), p.logoShape)
            e.putBoolean(planKey("logoCrop"), p.logoCrop)
            e.putString(planKey("customLogos"), p.customLogos.joinToString("\u0001") { e0 ->
                "${e0.name}\u0002${e0.path}\u0002${e0.shape}\u0002${e0.crop}"
            })
            e.putFloat(planKey("brandScale"), p.brandScale)
            e.putBoolean(planKey("showDevice"), p.showDevice); e.putBoolean(planKey("showTime"), p.showTime)
            e.putBoolean(planKey("fontContrast"), p.fontContrast)

            e.putFloat(planKey("fontTitleScale"), p.fontTitleScale)
            e.putFloat(planKey("fontCodeScale"), p.fontCodeScale)
            e.putFloat(planKey("fontTimeScale"), p.fontTimeScale)
            e.putFloat(planKey("fontGeoScale"), p.fontGeoScale)
            e.putFloat(planKey("fontTitleShift"), p.fontTitleShift)
            e.putFloat(planKey("fontCodeShift"), p.fontCodeShift)
            e.putFloat(planKey("fontTimeShift"), p.fontTimeShift)
            e.putFloat(planKey("fontGeoShift"), p.fontGeoShift)
            e.putFloat(planKey("fontTimeDy"), p.fontTimeDy)
            e.putFloat(planKey("fontGeoDy"), p.fontGeoDy)
            e.putFloat(planKey("fontCodeDy"), p.fontCodeDy)
            e.putFloat(planKey("fontBlockShift"), p.fontBlockShift)
            e.putFloat(planKey("fontLineGap"), p.fontLineGap)
            e.putFloat(planKey("fontLetterSpacing"), p.fontLetterSpacing)
            e.putBoolean(planKey("fontShadow"), p.fontShadow)
            e.putBoolean(planKey("autoTz"), p.autoTz)
            e.putFloat(planKey("borderW"), p.borderWidth); e.putInt(planKey("borderA"), p.borderAlpha)
            e.putFloat(planKey("relX"), p.relX); e.putFloat(planKey("relY"), p.relY)
            e.putBoolean(planKey("darkMode"), p.darkMode)
            e.putBoolean(planKey("softwareBlur"), p.softwareBlur)

            e.putInt("fontMode", p.fontMode)
            e.putInt(planKey("apiMode"), p.apiMode)
            e.putInt("apiModeGlobal", p.apiMode)
            e.putString(planKey("model"), p.model)
            e.putString(planKey("modelCode"), p.modelCode)
            e.putBoolean(planKey("modelCustom"), modelCustom)
            e.putBoolean(planKey("modelCodeCustom"), modelCodeCustom)
            e.putBoolean(planKey("autoClean"), p.autoClean)
            e.putBoolean(planKey("fluidEnabled"), p.fluidEnabled)
            e.putInt(planKey("fluidSpeed"), p.fluidSpeed)
            e.putBoolean(planKey("breathEnabled"), p.breathEnabled)
            e.putInt(planKey("breathStrength"), p.breathStrength)
            e.putInt(planKey("exportMode"), p.exportMode)

            e.putInt(planKey("cropRatio"), p.cropRatio)
            e.putInt(planKey("geoMode"), p.geoMode)
            e.putString(planKey("geoText"), p.geoText)
            e.putFloat(planKey("geoLat"), p.geoLat.toFloat()); e.putFloat(planKey("geoLng"), p.geoLng.toFloat())
            e.putInt(planKey("geoStyle"), p.geoStyle)
            e.putBoolean(planKey("geoAboveTime"), p.geoAboveTime)
            e.putInt(planKey("canvasOrient"), p.canvasOrient)

            e.putInt("gifFps", gifFps)
            e.putInt("gifEdgePx", gifEdgePx)
            e.putLong("gifTrimStartMs", gifTrimStartMs)
            e.putLong("gifTrimDurationMs", gifTrimDurationMs)
            e.putBoolean("gifTrimCustomized", gifTrimCustomized)

            e.putLong("motionTrimStartMs", motionTrimStartMs)
            e.putLong("motionTrimDurationMs", motionTrimDurationMs)
            e.putBoolean("motionTrimCustomized", motionTrimCustomized)

            for ((bid, v) in brandScales) e.putFloat("brandScale_$bid", v)
            e.putBoolean("live", liveSeconds)
            e.putString("motionExportFormat", motionExportFormat)
            e.putInt("motionPhotoFormat", motionPhotoFormat)
            e.apply()
        } catch (_: Throwable) {}
    }
    fun restore(ctx: Context): Boolean {
        return try {
            val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

            val savedVer = sp.getInt("saved", 0)
            DebugLog.log("BOOT", "restore saved=$savedVer pver=${sp.getInt("pver", 0)} " +
                "activePlan=${sp.getInt("activePlan", PLAN_LIGHT)}")
            if (savedVer < 9) return false

            activePlan = sp.getInt("activePlan", PLAN_LIGHT).coerceIn(0, PLAN_COUNT - 1)
            params = planParams[activePlan]
            appCtx = ctx.applicationContext
            modelCustom = sp.getBoolean(planKey("modelCustom"), false)
            modelCodeCustom = sp.getBoolean(planKey("modelCodeCustom"), false)

            darkModeState = sp.getBoolean("globalDark", sp.getBoolean(planKey("darkMode"), true))

            dockFrost = sp.getInt("dockFrost", dockFrost).coerceIn(0, 100)
            dockBlur = sp.getInt("dockBlur", dockBlur).coerceIn(0, 100)
            dockRefractH = sp.getInt("dockRefractH", dockRefractH).coerceIn(0, 100)
            dockRefract = sp.getInt("dockRefract", dockRefract).coerceIn(0, 100)
            dockTint = sp.getInt("dockTint", dockTint).coerceIn(0, 100)
            dockRim = sp.getInt("dockRim", dockRim).coerceIn(0, 100)
            dockAlpha = sp.getInt("dockAlpha", dockAlpha).coerceIn(0, 100)
            dockDisperse = sp.getInt("dockDisperse", dockDisperse).coerceIn(0, 100)
            dockFlow = sp.getInt("dockFlow", dockFlow).coerceIn(0, 100)

            val freshLook = sp.getInt("pver", 0) < PARAMS_VERSION
            val p = params

            if (sp.getInt("plan2Ver", 0) < PLAN_VERSION) resetPlan2(ctx)
            if (!freshLook) p.shape = sp.getInt(planKey("shape"), p.shape)
            p.cornerPct = sp.getInt(planKey("cornerPct"), p.cornerPct)
            p.padHDp = sp.getFloat(planKey("padH"), p.padHDp); p.padVDp = sp.getFloat(planKey("padV"), p.padVDp)
            p.scalePct = sp.getInt(planKey("scalePct"), p.scalePct)
            p.rotation = sp.getInt(planKey("rotation"), p.rotation)
            val rawLen = sp.getFloat(planKey("wmLengthPct"), p.wmLengthPct)

            p.wmLengthPct = if (activePlan == PLAN_LIGHT) rawLen.coerceIn(0f, 1f) else rawLen.coerceIn(0.01f, 20f)
            if (!freshLook) p.blur = sp.getInt(planKey("blur"), p.blur)

            if (!freshLook) {
                p.refractH = sp.getInt(planKey("refractH"), p.refractH)
                p.refractA = sp.getInt(planKey("refractA"), p.refractA)
                p.chromatic = sp.getInt(planKey("chromatic"), p.chromatic)

                val legacyEdge = sp.getInt(planKey("edgeWarp"), -1)
                val legacyInner = sp.getInt(planKey("innerWarp"), -1)
                p.warp = sp.getInt(planKey("warp"), -1).let {
                    if (it in 0..100) it
                    else maxOf(
                        if (legacyEdge in 0..100) legacyEdge else p.edgeWarp,
                        if (legacyInner in 0..100) legacyInner else p.innerWarp)
                }.coerceIn(0, 100)
                p.vibrancy = sp.getInt(planKey("vibrancy"), p.vibrancy)
                p.specular = sp.getInt(planKey("specular"), p.specular)
                p.tintStrength = sp.getInt(planKey("tintStrength"), p.tintStrength).coerceIn(0, 100)
                p.innerShadow = sp.getInt(planKey("innerShadow"), p.innerShadow)
                p.shadow = sp.getInt(planKey("shadow"), p.shadow)
                p.dispersion = sp.getInt(planKey("dispersion"), p.dispersion)
                p.edgeLight = sp.getInt(planKey("edgeLight"), p.edgeLight)
                p.glassContrast = sp.getFloat(planKey("glassContrast"), p.glassContrast).coerceIn(0.5f, 2f)

                p.opacity = sp.getInt(planKey("opacity"), p.opacity).coerceIn(0, 100)
            }
            p.deriveWarp()
            p.refractionModel = sp.getInt(planKey("refractionModel"), p.refractionModel).coerceIn(0, 1)
            p.depthEffect = sp.getBoolean(planKey("depthEffect"), p.depthEffect)
            p.highlightStyle = sp.getInt(planKey("hlStyle"), p.highlightStyle)
            p.hlAngle = sp.getFloat(planKey("hlAngle"), p.hlAngle); p.falloff = sp.getFloat(planKey("falloff"), p.falloff)
            p.tintColor = if (freshLook) 0xFFFFFFFF.toInt()
                          else sp.getInt(planKey("tintColor"), p.tintColor)
            p.titleSp = sp.getFloat(planKey("titleSp"), p.titleSp)
            p.subSp = sp.getFloat(planKey("subSp"), p.subSp)
            p.textColor = if (freshLook) 0xFFFFFFFF.toInt()
                          else sp.getInt(planKey("textColor"), p.textColor)

            p.layoutMode = if (activePlan == PLAN_LIGHT) LAYOUT_SPLIT
                           else sp.getInt(planKey("layoutMode"), p.layoutMode)
            p.logoStyle = sp.getInt(planKey("logoStyle"), p.logoStyle)
            p.logoBrand = sp.getInt(planKey("logoBrand"), p.logoBrand)
            p.logoAlign = sp.getInt(planKey("logoAlign"), p.logoAlign)
            sp.getString(planKey("customLogoPath"), null)?.let { p.customLogoPath = it }
            p.customLogoName = sp.getString(planKey("customLogoName"), "") ?: ""
            p.logoShape = sp.getInt(planKey("logoShape"), p.logoShape).coerceIn(0, 3)
            p.logoCrop = sp.getBoolean(planKey("logoCrop"), p.logoCrop)
            p.customLogos.clear()
            sp.getString(planKey("customLogos"), "")?.split("\u0001")?.forEach { s0 ->
                val part = s0.split("\u0002")
                if (part.size == 4) {
                    p.customLogos.add(CustomLogoEntry(
                        part[0], part[1], part[2].toIntOrNull()?.coerceIn(0, 3) ?: 0, part[3] == "true"))
                }
            }

            p.customLogos.removeAll { it.path.endsWith("/custom_logo.png") }

            brandScales.clear()
            for (bid in BRAND_NAMES.indices) {
                val v = sp.getFloat("brandScale_$bid", 1f)
                if (v != 1f) brandScales[bid] = v
            }
            p.brandScale = brandScales[p.logoBrand]
                ?: sp.getFloat(planKey("brandScale"), 1f).also { if (it != 1f) brandScales[p.logoBrand] = it }
            p.logoStretch = sp.getFloat(planKey("logoStretch"), 1f)
            p.showDevice = sp.getBoolean(planKey("showDevice"), p.showDevice)
            p.showTime = sp.getBoolean(planKey("showTime"), p.showTime)
            p.fontContrast = sp.getBoolean(planKey("fontContrast"), p.fontContrast)
            p.borderWidth = sp.getFloat(planKey("borderW"), p.borderWidth)

            p.borderAlpha = if (freshLook) 0
                            else sp.getInt(planKey("borderA"), p.borderAlpha).coerceIn(0, 255)

            p.glassTier = sp.getInt(planKey("glassTier"), p.glassTier).coerceIn(0, 1)
            p.scaleComp = sp.getInt(planKey("scaleComp"), p.scaleComp).coerceIn(0, 100)

            p.ios27Style = sp.getBoolean(planKey("ios27Style"), false)
            p.relX = sp.getFloat(planKey("relX"), p.relX).coerceIn(0f, 1f)
            p.relY = sp.getFloat(planKey("relY"), p.relY).coerceIn(0f, 1f)

            p.darkMode = darkModeState

            p.fontMode = sp.getInt("fontMode", p.fontMode).coerceIn(FONT_MODE_DEFAULT, FONT_MODE_MAX)
            p.softwareBlur = sp.getBoolean(planKey("softwareBlur"), p.softwareBlur)
            p.apiMode = sp.getInt("apiModeGlobal", sp.getInt(planKey("apiMode"), p.apiMode)).coerceIn(0, 2)
            sp.getString(planKey("model"), null)?.let { p.model = it }
            sp.getString(planKey("modelCode"), null)?.let { p.modelCode = it }
            p.autoClean = sp.getBoolean(planKey("autoClean"), p.autoClean)
            p.fluidEnabled = sp.getBoolean(planKey("fluidEnabled"), p.fluidEnabled)
            p.fluidSpeed = sp.getInt(planKey("fluidSpeed"), p.fluidSpeed).coerceIn(1, 100)
            p.breathEnabled = sp.getBoolean(planKey("breathEnabled"), p.breathEnabled)
            p.breathStrength = sp.getInt(planKey("breathStrength"), p.breathStrength).coerceIn(0, 100)
            p.exportMode = sp.getInt(planKey("exportMode"), p.exportMode).coerceIn(0, 2)

            p.cropRatio = sp.getInt(planKey("cropRatio"), p.cropRatio).coerceIn(CROP_ORIGINAL, CROP_2_3)
            p.fontTitleScale = sp.getFloat(planKey("fontTitleScale"), p.fontTitleScale)
            p.fontCodeScale = sp.getFloat(planKey("fontCodeScale"), p.fontCodeScale)
            p.fontTimeScale = sp.getFloat(planKey("fontTimeScale"), p.fontTimeScale)
            p.fontGeoScale = sp.getFloat(planKey("fontGeoScale"), p.fontGeoScale)
            p.fontTitleShift = sp.getFloat(planKey("fontTitleShift"), p.fontTitleShift)
            p.fontCodeShift = sp.getFloat(planKey("fontCodeShift"), p.fontCodeShift)
            p.fontTimeShift = sp.getFloat(planKey("fontTimeShift"), p.fontTimeShift)
            p.fontGeoShift = sp.getFloat(planKey("fontGeoShift"), p.fontGeoShift)
            p.fontTimeDy = sp.getFloat(planKey("fontTimeDy"), p.fontTimeDy)
            p.fontCodeDy = sp.getFloat(planKey("fontCodeDy"), p.fontCodeDy)
            p.fontGeoDy = sp.getFloat(planKey("fontGeoDy"), p.fontGeoDy)
            p.fontBlockShift = sp.getFloat(planKey("fontBlockShift"), p.fontBlockShift)
            p.fontLineGap = sp.getFloat(planKey("fontLineGap"), p.fontLineGap)
            p.fontLetterSpacing = sp.getFloat(planKey("fontLetterSpacing"), p.fontLetterSpacing)
            p.fontShadow = sp.getBoolean(planKey("fontShadow"), p.fontShadow)
            p.autoTz = sp.getBoolean(planKey("autoTz"), p.autoTz)
            p.geoMode = sp.getInt(planKey("geoMode"), p.geoMode).coerceIn(GEO_OFF, GEO_CUSTOM)
            p.geoText = sp.getString(planKey("geoText"), "") ?: ""
            p.geoLat = sp.getFloat(planKey("geoLat"), 0f).toDouble()
            p.geoLng = sp.getFloat(planKey("geoLng"), 0f).toDouble()
            p.geoStyle = sp.getInt(planKey("geoStyle"), p.geoStyle).coerceIn(GEO_STYLE_PLACE, GEO_STYLE_COORD)
            p.geoAboveTime = sp.getBoolean(planKey("geoAboveTime"), p.geoAboveTime)
            p.canvasOrient = sp.getInt(planKey("canvasOrient"), p.canvasOrient).coerceIn(CANVAS_AUTO, CANVAS_PORTRAIT)
            if (!modelCustom && p.model.isBlank()) p.model = defaultModelName()
            if (!modelCodeCustom && p.modelCode.isBlank()) p.modelCode = defaultModelCode()
            if (p.dateText.isBlank()) p.dateText = nowString()
            liveSeconds = sp.getBoolean("live", false)
            motionExportFormat = sp.getString("motionExportFormat", "mp4") ?: "mp4"
            motionPhotoFormat = sp.getInt("motionPhotoFormat", 1).coerceIn(0, 1)

            gifFps = sp.getInt("gifFps", GIF_FPS_DEFAULT)
            gifEdgePx = sp.getInt("gifEdgePx", GIF_EDGE_DEFAULT)
            gifTrimStartMs = sp.getLong("gifTrimStartMs", 0L)
            gifTrimDurationMs = sp.getLong("gifTrimDurationMs", 5_000L)
            gifTrimCustomized = sp.getBoolean("gifTrimCustomized", false)

            motionTrimStartMs = sp.getLong("motionTrimStartMs", 0L)
            motionTrimDurationMs = sp.getLong("motionTrimDurationMs", 5_000L)
            motionTrimCustomized = sp.getBoolean("motionTrimCustomized", false)
            sanitizeGifParams()
            p.sanitizeInPlace()

            com.liquidglass.watermark.glass.GlassQuality.tier = p.glassTier
            true
        } catch (_: Throwable) { false }
    }
}
