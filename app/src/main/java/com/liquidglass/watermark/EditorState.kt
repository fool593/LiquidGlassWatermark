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

/** 深色/浅色模式 Compose State（切换后触发 UI 重组，修复"必须再点别的选项才生效"bug） */
var darkModeState by mutableStateOf(true)
    private set
// 深浅模式为跨方案全局设置：同步当前 params（渲染读取）并独立持久化，切方案/重启都保持
fun setDarkMode(v: Boolean) {
    darkModeState = v
    EditorState.params.darkMode = v
    EditorState.saveDarkGlobal(v)
}

/** API 模式：0=按本机自动检测，1=强制高版本API(RenderEffect+AGSL)，2=强制低版本兼容(软件模糊) */
const val API_DEFAULT = 0

// ── v15.15 字体模式 ──
/** 默认字体：应用内置 sans-serif（当前行为，改动前的一致表现） */
const val FONT_MODE_DEFAULT = 0
/** 系统字体：读取设备系统字体族（Android: Typeface.DEFAULT 走系统 Roboto/厂商定制字体） */
const val FONT_MODE_SYSTEM = 1
const val API_HIGH = 1
const val API_LOW = 2
/**
 * 全局编辑状态。预览 View、参数面板、导出合成三处共用同一份数据，
 * 保证「调节 -> 预览 -> 导出」单一数据源，任何调节都不会失效。
 *
 * v3：玻璃参数严格对齐开源库 Kyant0/AndroidLiquidGlass 2.0.0（Backdrop）：
 * blur 毛玻璃强度、refractH/refractA 边缘折射高度/强度、chromatic 色散、
 * vibrancy 玻璃质感(鲜活饱和)、specular 反光度、highlightStyle 反光类型、
 * tint 染色、innerShadow/shadow 内外阴影。AGSL 着色器逐行移植为纯 Kotlin，全版本一致。
 */
object EditorState {
    const val PREFS = "lgw_prefs"
    // 应用 Context：供无 Context 的全局设置（深浅模式）即时落盘；启动时绑定一次
    @Volatile private var appCtx: Context? = null
    fun attach(ctx: Context) { if (appCtx == null) appCtx = ctx.applicationContext }

    /** 当前品牌 logo 是否为横长字母标（宽高比>1.5）：仅这类才显示"加长"滑块；只读图片边界、不解码到内存 */
    fun brandLogoWide(brand: Int): Boolean {
        val asset = BRAND_ASSETS.getOrNull(brand) ?: return false
        val ctx = appCtx ?: return false
        return runCatching {
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.assets.open(asset).use { android.graphics.BitmapFactory.decodeStream(it, null, o) }
            o.outWidth > 0 && o.outHeight > 0 && o.outWidth > o.outHeight * 1.5f
        }.getOrDefault(false)
    }
    /** 深浅模式为跨方案全局设置，独立键持久化（不跟随方案，切方案/退出重进都保持） */
    fun saveDarkGlobal(v: Boolean) {
        val c = appCtx ?: return
        runCatching { c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean("globalDark", v).apply() }
    }
    // 玻璃观感默认值版本：升级后 shape/blur/opacity 等观感项重置为新默认（用户自定义文字/位置保留）
    // v6：v15.5 修复"水印纯黑"——用户设备保存的旧 blur=0（纯通透）在 v15.4 未升级版本号时被读回，
    //     深色照片上玻璃全透=黑；升 6 强制重置玻璃观感 + 文字色/染色回安全默认
    const val PARAMS_VERSION = 6
    // 方案布局版本：升级后方案2 布局/观感重置为新默认（对齐 Photo Watermark 成片），文字/logo 内容保留
    // v3：v12.70 已修"重置立即落盘"但版本号没升，v12.69 已写入 2 → v12.70 不触发重置 → 方案2 仍是旧 CLASSIC 数据。
    // 升到 3 强制再触发一次重置+落盘，让方案2 真正变成 SPLIT 成片布局。
    // v4：v12.74 品牌自动匹配扩展到全部方案，方案2 也有机型/logo/时间；升版强制再重置一次确保旧包数据清干净
    // v5：方案2长度改为 0=自然紧凑/1=满宽线性语义，LOGO 脱离整体缩放；强制重置方案2到新默认
    const val PLAN_VERSION = 5
    // 形状
    const val SHAPE_ROUNDED = 0
    const val SHAPE_CAPSULE = 1
    const val SHAPE_ELLIPSE = 2
    const val SHAPE_RECT = 3
    // 反光类型（对齐 HighlightStyle：Plain 整体 / Default 定向 / Ambient 单侧环境光）
    const val HL_PLAIN = 0
    const val HL_DEFAULT = 1
    const val HL_AMBIENT = 2
    // v15.16 玻璃画质档位（任务2 要求 high/low 两档，low 档不得退化为纯 alpha 混合）
    const val GLASS_TIER_HIGH = 0   // 全量：3 抽样色散 + pow 菲涅尔 + 逐像素 bevel 剖面
    const val GLASS_TIER_LOW = 1    // 降级：保留法线 UV 折射 + 内部放大扭曲，色散/菲涅尔走廉价近似
    // 排版
    const val LAYOUT_CLASSIC = 0  // 标识 + 竖分隔线 + 机型/时间双行
    const val LAYOUT_SINGLE = 1   // 单行胶囊
    const val LAYOUT_STACK = 2    // 双行无标识
    const val LAYOUT_SPLIT = 3    // 左右分栏：机型左 + 参数/时间右（对齐 Photo Watermark 新款浅色成片）
    // 左侧标识
    const val LOGO_DOT = 0
    const val LOGO_CAMERA = 1
    const val LOGO_NONE = 2
    // 品牌 logo 位图（-1=不显示位图，沿用 logoStyle 矢量；>=0 显示品牌位图）
    // 顺序与 BRAND_NAMES / BRAND_ASSETS 一一对应（v12.75 新增 14 个：影像品牌+手机品牌+相机品牌）
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
    const val BRAND_APPLE_W = 21  // 苹果白（官方白标，与黑苹果分开）
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

    /** v14.70 自定义 logo 条目：多 logo 中每个的 名称/文件路径/形状/裁剪（互不覆盖） */
    class CustomLogoEntry(
        @JvmField var name: String,
        @JvmField var path: String,
        @JvmField var shape: Int,
        @JvmField var crop: Boolean
    )

    class Params {
        // ---- 形状 ----
        @JvmField var shape: Int = SHAPE_CAPSULE
        // 新款浅色（Photo Watermark 方案2）：大圆角 120px 观感、低模糊通透、强扭曲液态
        @JvmField var cornerPct: Int = 75      // 圆角比例 0..100（开源 Corner radius 0..1）
        @JvmField var padHDp: Float = 15f
        @JvmField var padVDp: Float = 9f
        @JvmField var scalePct: Int = 100
        /** 水印整体长度倍率（1f=预览框 100% 宽；可 0.01 极小 / 5 超长，文字自适应压缩） */
        @JvmField var wmLengthPct: Float = 1f
        @JvmField var rotation: Int = 0
        // ---- 液态玻璃（v14.97 起为「偏移层」：0=基线观感，拖动在基线上叠加放大，效果可强于默认） ----
        @JvmField var blur: Int = 8          // 模糊 0..60（滑块 0=纯通透；默认 8=轻磨砂——纯通透在深色照片上会呈黑色）
        @JvmField var refractH: Int = 50       // 边缘折射高度 0..100
        @JvmField var refractA: Int = 55       // 边缘折射强度 0..100
        // v14.01：边缘扭曲 + 内部扭曲合并为「统一扭曲」单一滑块——一个连续液态效果。
        // edgeWarp/innerWarp 是派生字段（由 warp 推导），保留仅为旧持久化/旧预设兼容，勿直接改。
        @JvmField var warp: Int = 25           // 统一扭曲 0..100
        @JvmField var edgeWarp: Int = 0        // v14.74 默认归零：真液态折射=高度场边缘压缩（无正弦水波纹）
        @JvmField var innerWarp: Int = 0       // 内部扭曲（由 warp 推导）
        @JvmField var chromatic: Int = 35      // 色散 0..100
        @JvmField var depthEffect: Boolean = true   // 深度折射（透镜向中心汇聚）
        @JvmField var vibrancy: Int = 65       // 玻璃质感/鲜活度 0..100（提饱和提亮，防发脏）
        @JvmField var specular: Int = 55       // 反光度 0..100
        @JvmField var highlightStyle: Int = HL_DEFAULT
        @JvmField var hlAngle: Float = 45f     // 定向反光角度（Default.angle 默认45°）
        @JvmField var falloff: Float = 1.1f    // 反光衰减（Default.falloff 默认1）
        @JvmField var tintColor: Int = 0xFFFFFFFF.toInt()
        @JvmField var tintStrength: Int = 0    // 表面铺色/乳白浓度 0..40
        @JvmField var innerShadow: Int = 26    // 内阴影（边缘压暗）0..100
        @JvmField var shadow: Int = 72         // 外投影 0..100（悬浮高度）
        // ---- 液态玻璃进阶参数（v4.0 新增，全版本可调） ----
        @JvmField var dispersion: Int = 18     // 色散扩散 0..100
        @JvmField var edgeLight: Int = 50      // 边缘光/边缘高光 0..100（菲涅尔/亮环/光斑）
        @JvmField var opacity: Int = 62        // 不透明度 0..100
        @JvmField var glassContrast: Float = 1.1f  // 玻璃对比度 0.5..2.0（默认 1.1 轻度可辨——1.2 会把深色背景继续压暗成"黑水印"）
        // ---- v15.16 画质档位与缩小补偿（任务2） ----
        /** 画质档位：0=high（全量色散/菲涅尔/剖面） 1=low（保留法线UV折射+扭曲，去掉高成本着色）。 */
        @JvmField var glassTier: Int = GLASS_TIER_HIGH
        /** 缩小补偿强度 0..100：0=关闭，100=小尺寸下光学带最多放大到 3.2×。 */
        @JvmField var scaleComp: Int = 100
        // ---- 文字（三行：手机名称 / 型号代号 / 时间） ----
        @JvmField var model: String = ""        // 第一行：手机名称（市场名，如 iQOO Neo，可自定义）
        @JvmField var modelCode: String = ""    // 第二行：型号代号（如 V1914A，可自定义）
        @JvmField var dateText: String = ""     // 第三行：年-月-日 时:分:秒
        @JvmField var titleSp: Float = 14.5f
        @JvmField var subSp: Float = 10f
        /** v15.15 字体模式：0=默认字体（应用内置 sans-serif），1=系统字体（跟随设备系统字体）。
         *  仅影响水印文字字形（typeface），不改字号/颜色/布局/玻璃效果。 */
        @JvmField var fontMode: Int = FONT_MODE_DEFAULT
        @JvmField var textColor: Int = 0xFFFFFFFF.toInt()
        @JvmField var showDevice: Boolean = true
        @JvmField var showTime: Boolean = true
        @JvmField var fontContrast: Boolean = true
        @JvmField var borderWidth: Float = 1f
        @JvmField var borderAlpha: Int = 0   // v12.90：白色描边残留全清（用户截图 8 张均有白框），可在 UI 自行调回
        @JvmField var layoutMode: Int = LAYOUT_CLASSIC
        @JvmField var logoStyle: Int = LOGO_DOT
        /** 品牌 logo 位图：-1=无（用 logoStyle 矢量），>=0 用品牌位图（BRAND_*） */
        @JvmField var logoBrand: Int = BRAND_NONE
        /** logo 位置：0=前（文字左侧） 1=后（文字右侧） 2=居中（纯 logo 水印，隐藏文字） */
        @JvmField var logoAlign: Int = 0
        /** 自定义品牌 logo 文件路径（BRAND_CUSTOM 时生效，绝对路径指向 filesDir）——当前选中的条目 */
        @JvmField var customLogoPath: String = ""
        /** v14.30 自定义 logo 名字（导入后命名；显示在品牌选择器）——当前选中的条目 */
        @JvmField var customLogoName: String = ""
        /** v14.70 多自定义 logo：全部已保存条目（名称/路径/形状/裁剪），可切换、可逐个删除 */
        @JvmField var customLogos: ArrayList<CustomLogoEntry> = ArrayList()
        /** v14.37 自定义 logo 形状：0=按内容自动（透明裁剪）1=正方形（居中裁剪到最小边）2=长方形（内容包围盒）3=圆形（居中裁方+圆形蒙版） */
        @JvmField var logoShape: Int = 0
        /** v14.37 自定义 logo 是否裁剪透明边（true=裁到内容包围盒；false=保留原图整幅/含透明边） */
        @JvmField var logoCrop: Boolean = true
        /** 品牌 logo 大小倍率（1f=默认随文字大小，可调 0.5–2 倍） */
        @JvmField var brandScale: Float = 1f
        /** 横长字母标（小米/华为/一加/OPPO/真我/Google 等）水平加长倍率：1f=原始宽度，>1f 横向拉长，
         *  右对齐时向左延伸、绝不压右侧时间；圆点/几何标（徕卡/蔡司等方形标）不生效。 */
        @JvmField var logoStretch: Float = 1f
        @JvmField var darkMode: Boolean = true   // 深色/浅色模式（true=深色通透玻璃，false=浅色带阴影玻璃）
        @JvmField var softwareBlur: Boolean = false // 软件模糊兼容模式（true=低版本也用FastBlur模拟磨砂玻璃，false=按API自动检测）
        @JvmField var apiMode: Int = API_DEFAULT   // API模式：0=自动 1=强制高版本 2=强制低版本兼容
        // ---- 位置（相对图片显示区域的比例坐标，0.5/0.5 为正中心） ----
        @JvmField var relX: Float = 0.5f
        @JvmField var relY: Float = 0.9f
        @JvmField var autoClean: Boolean = true   // 导出成功后自动清理缓存（临时文件 + 动图帧内存）
        // ---- v14.49 导出模式三段（单选，默认=快速）：0=快速（纯GPU最快，水印含全部特性）
//        1=混合（GPU解码+CPU引擎水印=预览，较快） 2=CPU（纯软件逐帧，水印=预览，最稳） ----
        @JvmField var exportMode: Int = 0
        // ---- v14.61 阶段三原型：折射模型 0=旧 circleMap 剖面 1=高度场+斯涅尔（v14.68 起默认=1，
        //      真液态：边缘压缩环+内部放大+菲涅尔边缘光，对应真机"磨砂贴图"反馈修复） ----
        // v14.99 改回 0：斯涅尔剖面在边缘处位移仅约 20%，实测"完全没有边缘透镜感"（用户截图）；
        // model 0 的 1-(1-t)^1.5 剖面在边缘满位移 = 放大镜式边缘弯折，内部保持平直（用户规格：
        // "内部文字区平直模糊，仅边缘 5-10px 像放大镜扭曲拉扯"）；菲涅尔边缘光已脱离模型门控
        @JvmField var refractionModel: Int = 0
        // ---- v14.29 流体液态玻璃：开关 + 流速（频率）----
        @JvmField var fluidEnabled: Boolean = false   // 胶囊开关：开→重载 App 生效（扭曲相位随时间流动）
        @JvmField var fluidSpeed: Int = 30            // 流速 1..100（相位每秒弧度 = 0.6 + speed/100*4）
        // ---- v14.96 呼吸动效：扭曲振幅随时间呼吸（扭曲→停顿→再扭曲），与流体独立可开 ----
        // 呼吸=振幅包络（非相位）：液体周期性地"鼓起→平静"，比匀速流动更接近真实液态。
        // 预览/导出共用同一包络（导出以视频时间为准，不受导出速度影响）。
        @JvmField var breathEnabled: Boolean = false  // 呼吸开关：开→扭曲振幅随视频时间呼吸起伏
        @JvmField var breathStrength: Int = 50        // 呼吸强度 0..100（越低起伏越浅、停顿越短，0=不呼吸）

        /** 逐字段复制（方案重置时用全新默认 Params 覆盖固定实例，保持 params 引用不变） */
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
            fluidEnabled=s.fluidEnabled; fluidSpeed=s.fluidSpeed
            refractionModel=s.refractionModel
            breathEnabled=s.breathEnabled; breathStrength=s.breathStrength
            exportMode=s.exportMode
            model=s.model; modelCode=s.modelCode; dateText=s.dateText
            titleSp=s.titleSp; subSp=s.subSp; textColor=s.textColor
            showDevice=s.showDevice; showTime=s.showTime; fontContrast=s.fontContrast
            fontMode=s.fontMode   // v15.17：字体模式随 params 复制，预设导入/方案重置不再丢
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
        }

        /** 由统一扭曲 warp 重新推导 edgeWarp/innerWarp（保持三者一致；UI / persist / restore / 预设统一入口） */
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
            blur = blur.coerceIn(0, 60)
            refractH = refractH.coerceIn(0, 100); refractA = refractA.coerceIn(0, 100)
            warp = warp.coerceIn(0, 100); deriveWarp()
            chromatic = chromatic.coerceIn(0, 100); vibrancy = vibrancy.coerceIn(0, 100)
            specular = specular.coerceIn(0, 100); highlightStyle = highlightStyle.coerceIn(0, 2)
            tintStrength = tintStrength.coerceIn(0, 40); innerShadow = innerShadow.coerceIn(0, 100)
            shadow = shadow.coerceIn(0, 100); dispersion = dispersion.coerceIn(0, 100)
            edgeLight = edgeLight.coerceIn(0, 100); opacity = opacity.coerceIn(0, 100)
            borderAlpha = borderAlpha.coerceIn(0, 255); layoutMode = layoutMode.coerceIn(0, 3)
            logoStyle = logoStyle.coerceIn(0, 2); logoAlign = logoAlign.coerceIn(0, 2)
            logoShape = logoShape.coerceIn(0, 3); logoBrand = logoBrand.coerceIn(-1, BRAND_ASSETS.lastIndex)
            refractionModel = refractionModel.coerceIn(0, 1); exportMode = exportMode.coerceIn(0, 2)
            glassTier = glassTier.coerceIn(GLASS_TIER_HIGH, GLASS_TIER_LOW); scaleComp = scaleComp.coerceIn(0, 100)
            fontMode = fontMode.coerceIn(FONT_MODE_DEFAULT, FONT_MODE_SYSTEM)   // v15.17：非法值钳回默认
            fluidSpeed = fluidSpeed.coerceIn(1, 100); breathStrength = breathStrength.coerceIn(0, 100)
        }

        /**
         * v15.2 滑块=实际值直控：0=无效果，默认=中度真玻璃观感（见字段默认值）。
         * 保留 [effective] 接口仅为兼容旧渲染调用点，恒等返回自身。
         */
        fun effective(): Params = this
    }

    /** 渲染用参数（v15.2 直控：与 params 相同）。保留接口避免改 10 处渲染入口。 */
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
    // 方案系统：方案1=经典、方案2=新款浅色，两套参数完全独立（切换互不覆盖）
    // 默认方案2（新款浅色，延续出厂水印）；plans 数组在 init 里按方案设各自默认
    const val PLAN_CLASSIC = 0
    const val PLAN_LIGHT = 1
    const val PLAN_COUNT = 2
    @JvmField var activePlan: Int = PLAN_LIGHT

    var params: Params = Params()  // 当前激活方案的参数（切换方案时重指向）
    /** 每个品牌独立的 logo 大小倍率（brandId → scale，1f=默认；全局跨方案持久化，切换品牌各自记忆） */
    @JvmField val brandScales = mutableMapOf<Int, Float>()
    /** 两套方案独立参数：0=经典、1=新款浅色；各自默认不同，切换互不覆盖 */
    private val planParams = arrayOf(Params(), Params()).also { arr ->
        // 方案1=经典：经典竖排布局；视觉参数走 v14.97 基线+偏移（此处不再设旧视觉默认，避免覆盖基线）
        arr[0].cornerPct = 55
        // 方案2=新款浅色：大圆角 + 左右分栏（机型左+参数/时间右，对齐成片）
        arr[1].layoutMode = LAYOUT_SPLIT
        arr[1].cornerPct = 75
        arr[1].titleSp = 34f; arr[1].subSp = 22f
        arr[1].wmLengthPct = 0f   // 方案2长度语义：0=自然紧凑，1=满宽（线性）
        arr[1].relX = 0.5f; arr[1].relY = 0.55f
    }
    /** 按索引访问方案参数（供 MainActivity 品牌自动匹配应用到全部方案） */
    @JvmStatic fun planParamsAt(i: Int): Params = planParams[i.coerceIn(0, PLAN_COUNT - 1)]

    // ---- 统一扭曲曲线：一条滑块 = 连续液态效果（边缘先起、内部后起，整段行程都有效） ----
    // 边缘扭曲：0→70 快速爬升到满（低档即有清晰可感的液态边界）；70 后保满。
    // 内部扭曲：30 起步淡入，100 达 88（内部满档过猛会把玻璃内文字搅糊，88 封顶保可读）。
    /** 统一扭曲 → 边缘扭曲（0..100） */
    fun warpEdge(w: Int): Int =
        (smoothstep01(w.coerceIn(0, 100) / 100f, 0f, 0.7f) * 100f)
            .roundToInt().coerceIn(0, 100)

    /** 统一扭曲 → 内部扭曲（0..100） */
    fun warpInner(w: Int): Int =
        (smoothstep01(w.coerceIn(0, 100) / 100f, 0.30f, 1f) * 88f)
            .roundToInt().coerceIn(0, 100)

    private fun smoothstep01(x: Float, e0: Float, e1: Float): Float {
        if (e1 - e0 < 1e-6f) return if (x < e0) 0f else 1f
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
    // 运动/动图状态由后台解码线程写入、主线程读取：必须 @Volatile 保证跨线程可见（此前缺内存屏障，极端情况下主线程读到旧值导致"选了视频导出却是静态图"）
    @Volatile @JvmField var previewBitmap: Bitmap? = null
    /** 自定义预览衬底图：无用户照片时用作预览背景（独立于水印对象，更换才变） */
    @Volatile @JvmField var previewCustomBitmap: Bitmap? = null
    fun setPreviewCustom(b: Bitmap?) {
        if (previewCustomBitmap === b) return
        val old = previewCustomBitmap
        previewCustomBitmap = b
        runCatching { old?.let { if (!it.isRecycled && it !== b) it.recycle() } }
    }
    @JvmField var sourceUri: android.net.Uri? = null
    @Volatile @JvmField var isDemo: Boolean = true
    // 导出进行中标志（由 MainActivity.progressPct setter 单一驱动）：锁定水印拖动与所有编辑控件，底部导航不锁
    @Volatile @JvmField var isExporting: Boolean = false
    // v14.96 导出逐帧时间（秒）：导出路径每帧写入"该帧的播放时间"，渲染层据此算呼吸包络——
    // 导出动画以视频时间为准（预览用墙钟），不受导出速度快慢影响（v14.80 曾因用墙钟导出加速而禁流）
    @Volatile @JvmField var exportTimeSec: Float = 0f
    @Volatile @JvmField var mediaPreviewTimeSec: Float = 0f
    // 滑块拖动中标志：拖动期间预览走轻量渲染（跳过重型玻璃引擎合成，防连续大滑动旋转时主线程阻塞导致黑屏）；
    // 松手后由滑块 onDragEnd 复位并触发完整渲染。另有 800ms 无输入超时兜底（onDraw 检测）防松手事件丢失卡死
    @Volatile @JvmField var sliderInteracting: Boolean = false
    // 最近一次滑块输入时间戳（ms）：onDraw 据此判断是否超时复位轻量渲染
    @Volatile @JvmField var lastSliderMoveMs: Long = 0L
    @JvmField var liveSeconds: Boolean = false
    // 照片旋转（0/90/180/270），预览与导出同步，旋转90/270时输出尺寸互换
    @JvmField var photoRotation: Int = 0
    // 动态照片解码失败标志：为 true 时保存会明确提示"已保存为静态图"，不再静默
    @Volatile @JvmField var motionDecodeFailed: Boolean = false
    // ---- GIF 动图状态 ----
    @Volatile @JvmField var gifMovie: Movie? = null
    @Volatile @JvmField var isGif: Boolean = false
    @JvmField var gifDuration: Int = 0
    @Volatile @JvmField var gifBytes: ByteArray? = null
    // ---- 动态照片（Motion Photo）解码后的帧序列 ----
    @Volatile @JvmField var motionFrames: MutableList<android.graphics.Bitmap>? = null
    @Volatile @JvmField var motionDelays: IntArray? = null
    /** 动态照片源 MP4 字节（导出 MP4/GIF 时重新解码用，跟随源分辨率与帧率） */
    @Volatile @JvmField var motionMp4: ByteArray? = null
    /** 动态照片视频强制旋转：-1=自动探测；0/90/180/270=强制。
     *  vivo 动态照片 MP4 像素横屏+无 rotation 元数据，需从封面 JPG 方向推断（JPG 竖屏→强制 90） */
    @Volatile @JvmField var motionForceVideoRot: Int = -1
    /** 大视频源 Uri（>128MB 不整读内存，全链路 Uri 流式解码/导出） */
    @Volatile @JvmField var motionUri: android.net.Uri? = null
    /** 动态 WebP 源字节（导出 GIF 时重新解码用）；换源时由 clearMotion/clearSource 清理 */
    @Volatile @JvmField var webpBytes: ByteArray? = null
    /** 合并场景下的图片封面（先选图片再选视频，或反向配对），导出时作为 MP4 第一帧前缀 */
    @Volatile @JvmField var motionCoverBitmap: android.graphics.Bitmap? = null
    /** 动态照片导出格式：gif / mp4（Compose State，切换后按钮文本即时刷新） */
    var motionExportFormat by mutableStateOf("mp4")
    /** Motion-photo container choice: 0=observed vivo-style pair; 1=Google XMP single-JPEG format. Gallery support is device-specific. */
    var motionPhotoFormat by mutableStateOf(1)
    fun clearMotion() {
        mediaPreviewTimeSec = 0f
        // 若 previewBitmap 指向即将被回收的视频帧/封面，先断开引用，防止后续 createScaledBitmap 用已回收源
        if (motionFrames?.contains(previewBitmap) == true || previewBitmap === motionCoverBitmap) {
            previewBitmap = null
        }
        motionFrames?.forEach { runCatching { if (!it.isRecycled) it.recycle() } }
        motionFrames = null; motionDelays = null; motionMp4 = null; motionUri = null; webpBytes = null
        motionForceVideoRot = -1
        runCatching { motionCoverBitmap?.let { if (!it.isRecycled) it.recycle() } }
        motionCoverBitmap = null
    }
    fun nowString(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
    fun defaultModelName(): String = DeviceNames.marketName()
    fun defaultModelCode(): String = DeviceNames.modelCode()
    // ---------- 参数持久化（位置 / 质感 / 机型，退出重进不丢；方案独立存储） ----------
    /** 方案前缀：同一 key 按 activePlan 分开存储，方案间互不影响 */
    private fun planKey(k: String): String = "plan${activePlan}_$k"
    /** 切换方案：先落盘当前，再载入目标（无历史数据则用该方案默认） */
    fun switchPlan(ctx: Context, idx: Int) {
        if (idx == activePlan || idx !in 0 until PLAN_COUNT) return
        persist(ctx)
        // 切到方案2 前防御性重置：若 plan2Ver 旧（restore 可能因启动时 activePlan=方案1 未重置方案2 数据）
        if (idx == PLAN_LIGHT) {
            val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (sp.getInt("plan2Ver", 0) < PLAN_VERSION) resetPlan2(ctx)
        }
        activePlan = idx
        // 先把 activePlan 落盘（restore 会读回它）；否则 restore 读到 persist 写入的旧方案值，切换被覆盖
        runCatching {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("activePlan", idx).apply()
        }
        params = planParams[idx]
        restore(ctx)
    }

    // 方案2 强制重置为代码新默认并直接落盘到 plan2_ 前缀（不依赖 persist 只写当前方案）
    /** 重置指定方案全部参数为出厂默认（方案间完全独立：重置方案2不影响方案1，反之亦然） */
    fun resetPlan(ctx: Context, idx: Int) {
        try {
            val pi = idx.coerceIn(0, PLAN_COUNT - 1)
            val d = Params()
            if (pi == PLAN_CLASSIC) {
                // 方案1=经典：经典竖排布局；视觉参数=基线（偏移层 0），不设旧视觉默认
                d.cornerPct = 55
            } else {
                // 方案2=新款浅色：左右分栏 + 独立字号
                d.layoutMode = LAYOUT_SPLIT
                d.cornerPct = 75
                d.titleSp = 34f; d.subSp = 22f
                d.wmLengthPct = 0f   // 长度语义 0=自然紧凑，1=满宽
                d.relX = 0.5f; d.relY = 0.55f
            }
            d.model = defaultModelName(); d.modelCode = defaultModelCode(); d.dateText = nowString()
            // 覆盖固定实例（保持 params 引用不变）
            planParams[pi].copyFrom(d)
            // 深浅模式是全局设置（globalDark），不随方案重置跳变
            planParams[pi].darkMode = darkModeState
            // v15.17：字体模式是用户全局偏好（全局键 fontMode），重置方案不覆盖用户所选字体
            planParams[pi].fontMode = params.fontMode
            // 落盘 planX_ 前缀
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
            e.putString(pk("customLogoPath"), d.customLogoPath); e.putFloat(pk("brandScale"), d.brandScale); e.putFloat(pk("logoStretch"), d.logoStretch)
            e.putBoolean(pk("showDevice"), d.showDevice); e.putBoolean(pk("showTime"), d.showTime); e.putBoolean(pk("fontContrast"), d.fontContrast)
            e.putFloat(pk("borderW"), d.borderWidth); e.putInt(pk("borderA"), d.borderAlpha)
            e.putFloat(pk("relX"), d.relX); e.putFloat(pk("relY"), d.relY)
            e.putBoolean(pk("darkMode"), d.darkMode); e.putBoolean(pk("softwareBlur"), d.softwareBlur)
            e.putInt(pk("apiMode"), d.apiMode)
            e.putBoolean(pk("breathEnabled"), d.breathEnabled); e.putInt(pk("breathStrength"), d.breathStrength)
            e.putString(pk("model"), d.model); e.putString(pk("modelCode"), d.modelCode)
            e.putBoolean(pk("autoClean"), d.autoClean)
            e.apply()
        } catch (_: Throwable) {}
    }
    /** 向后兼容：旧调用点 */
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
            e.putFloat(planKey("borderW"), p.borderWidth); e.putInt(planKey("borderA"), p.borderAlpha)
            e.putFloat(planKey("relX"), p.relX); e.putFloat(planKey("relY"), p.relY)
            e.putBoolean(planKey("darkMode"), p.darkMode)
            e.putBoolean(planKey("softwareBlur"), p.softwareBlur)
            // v15.17：字体模式是用户全局偏好（同 globalDark），不随方案分开存、不随方案切换覆盖
            e.putInt("fontMode", p.fontMode)
            e.putInt(planKey("apiMode"), p.apiMode)
            e.putString(planKey("model"), p.model)
            e.putString(planKey("modelCode"), p.modelCode)
            e.putBoolean(planKey("autoClean"), p.autoClean)
            e.putBoolean(planKey("fluidEnabled"), p.fluidEnabled)
            e.putInt(planKey("fluidSpeed"), p.fluidSpeed)
            e.putBoolean(planKey("breathEnabled"), p.breathEnabled)
            e.putInt(planKey("breathStrength"), p.breathStrength)
            e.putInt(planKey("exportMode"), p.exportMode)
            // 各品牌独立 logo 大小（全局持久化，不随方案）
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
            if (sp.getInt("saved", 0) < 9) return false
            // 方案：读全局 activePlan → 当前 params 指向该方案实例 → 读该方案前缀数据
            activePlan = sp.getInt("activePlan", PLAN_LIGHT).coerceIn(0, PLAN_COUNT - 1)
            params = planParams[activePlan]
            appCtx = ctx.applicationContext
            // 深浅模式为全局设置：从独立键恢复（首次升级从旧方案值迁移），不随方案切换改变
            darkModeState = sp.getBoolean("globalDark", sp.getBoolean(planKey("darkMode"), true))
            // 观感默认升级：旧版本不读 shape/blur/opacity，用代码新默认（修复透镜被压淡、形状非胶囊）
            val freshLook = sp.getInt("pver", 0) < PARAMS_VERSION
            val p = params
            // 方案2 布局升级：独立版本号 plan2Ver（与 activePlan 无关）。
            // 旧逻辑用 planFresh（planVer）判断，但 switchPlan 会先 persist 把 planVer 写新 →
            // restore 里 planFresh 恒为 false，方案2 重置永不触发 → 方案2 一直是旧 CLASSIC 数据。
            // resetPlan2 直接向 plan2_ 前缀写默认值并落盘（不依赖 persist 只写当前方案的限制）。
            if (sp.getInt("plan2Ver", 0) < PLAN_VERSION) resetPlan2(ctx)
            if (!freshLook) p.shape = sp.getInt(planKey("shape"), p.shape)
            p.cornerPct = sp.getInt(planKey("cornerPct"), p.cornerPct)
            p.padHDp = sp.getFloat(planKey("padH"), p.padHDp); p.padVDp = sp.getFloat(planKey("padV"), p.padVDp)
            p.scalePct = sp.getInt(planKey("scalePct"), p.scalePct)
            p.rotation = sp.getInt(planKey("rotation"), p.rotation)
            val rawLen = sp.getFloat(planKey("wmLengthPct"), p.wmLengthPct)
            // 方案2长度 0..1（0=自然紧凑，1=满宽）；方案1经典保留倍数
            p.wmLengthPct = if (activePlan == PLAN_LIGHT) rawLen.coerceIn(0f, 1f) else rawLen.coerceIn(0.01f, 20f)
            if (!freshLook) p.blur = sp.getInt(planKey("blur"), p.blur)
            // v14.97：玻璃效果参数改为「基线+偏移」——升级（freshLook）时偏移层强制归 0
            // （进入应用滑块显示 0，观感 = DefaultBase 基线）；仅后续版本读回旧偏移。
            // 布局/大小/位置/文字参数不受影响（下方继续无条件读旧值保留）。
            if (!freshLook) {
                p.refractH = sp.getInt(planKey("refractH"), p.refractH)
                p.refractA = sp.getInt(planKey("refractA"), p.refractA)
                p.chromatic = sp.getInt(planKey("chromatic"), p.chromatic)
                // 统一扭曲：优先读新键 warp；旧数据用「边缘/内部扭曲较大值」迁移，再统一推导保持一致
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
                p.tintStrength = sp.getInt(planKey("tintStrength"), p.tintStrength).coerceIn(0, 40)
                p.innerShadow = sp.getInt(planKey("innerShadow"), p.innerShadow)
                p.shadow = sp.getInt(planKey("shadow"), p.shadow)
                p.dispersion = sp.getInt(planKey("dispersion"), p.dispersion)
                p.edgeLight = sp.getInt(planKey("edgeLight"), p.edgeLight)
                p.glassContrast = sp.getFloat(planKey("glassContrast"), p.glassContrast).coerceIn(0.5f, 2f)

                // 透镜不透明度允许满值（引擎输出是透过的背景而非白膜，通透由 tint=0/轻 blur 保证，不再封顶60）
                p.opacity = sp.getInt(planKey("opacity"), p.opacity).coerceIn(0, 100)
            }
            p.deriveWarp()
            p.refractionModel = sp.getInt(planKey("refractionModel"), p.refractionModel).coerceIn(0, 1)
            p.depthEffect = sp.getBoolean(planKey("depthEffect"), p.depthEffect)
            p.highlightStyle = sp.getInt(planKey("hlStyle"), p.highlightStyle)
            p.hlAngle = sp.getFloat(planKey("hlAngle"), p.hlAngle); p.falloff = sp.getFloat(planKey("falloff"), p.falloff)
            p.tintColor = if (freshLook) 0xFFFFFFFF.toInt()
                          else sp.getInt(planKey("tintColor"), p.tintColor)   // v15.5 升级恢复白色染色，防"黑色染色=黑玻璃"
            p.titleSp = sp.getFloat(planKey("titleSp"), p.titleSp)
            p.subSp = sp.getFloat(planKey("subSp"), p.subSp)
            p.textColor = if (freshLook) 0xFFFFFFFF.toInt()
                          else sp.getInt(planKey("textColor"), p.textColor)   // v15.5 升级恢复白色文字，防"黑色文字=黑水印"
            // 方案2 布局固定为左右分栏（用户硬要求对齐成片），不受旧数据覆盖
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
            // v14.76 迁移：清除旧版共用文件条目（custom_logo.png——多条目同文件=互覆盖/同删的污染数据）
            p.customLogos.removeAll { it.path.endsWith("/custom_logo.png") }
            // 各品牌独立 logo 大小：读回全部品牌 map；当前品牌取 map 值（无则默认1），兼容旧全局 brandScale
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
            // 仅旧版本升级（pver 旧）时清掉历史白框残留；新版本读用户保存的边框透明度（此前每次启动强制清0=用户调节不持久）
            p.borderAlpha = if (freshLook) 0
                            else sp.getInt(planKey("borderA"), p.borderAlpha).coerceIn(0, 255)
            // v15.16 新增能力：画质档位与缩小补偿必须在 freshLook 块外无条件读取，
            // 否则升级用户首启时这两项停留在代码默认（键不存在时行为与旧版一致）。
            p.glassTier = sp.getInt(planKey("glassTier"), p.glassTier).coerceIn(0, 1)
            p.scaleComp = sp.getInt(planKey("scaleComp"), p.scaleComp).coerceIn(0, 100)
            p.relX = sp.getFloat(planKey("relX"), p.relX).coerceIn(0f, 1f)
            p.relY = sp.getFloat(planKey("relY"), p.relY).coerceIn(0f, 1f)
            // 深浅模式全局：restore 不读方案旧值（否则切方案后深浅被方案数据覆盖跳回深色）
            p.darkMode = darkModeState
            // v15.17：字体模式是用户全局偏好（同 darkMode/globalDark），从全局键无条件读回，
            // 切换方案（switchPlan→restore）不会把用户所选字体重置成默认。
            p.fontMode = sp.getInt("fontMode", p.fontMode).coerceIn(FONT_MODE_DEFAULT, FONT_MODE_SYSTEM)
            p.softwareBlur = sp.getBoolean(planKey("softwareBlur"), p.softwareBlur)
            p.apiMode = sp.getInt(planKey("apiMode"), p.apiMode)
            sp.getString(planKey("model"), null)?.let { p.model = it }
            sp.getString(planKey("modelCode"), null)?.let { p.modelCode = it }
            p.autoClean = sp.getBoolean(planKey("autoClean"), p.autoClean)
            p.fluidEnabled = sp.getBoolean(planKey("fluidEnabled"), p.fluidEnabled)
            p.fluidSpeed = sp.getInt(planKey("fluidSpeed"), p.fluidSpeed).coerceIn(1, 100)
            p.breathEnabled = sp.getBoolean(planKey("breathEnabled"), p.breathEnabled)
            p.breathStrength = sp.getInt(planKey("breathStrength"), p.breathStrength).coerceIn(0, 100)
            p.exportMode = sp.getInt(planKey("exportMode"), p.exportMode).coerceIn(0, 2)
            if (p.model.isBlank()) p.model = defaultModelName()
            if (p.modelCode.isBlank()) p.modelCode = defaultModelCode()
            if (p.dateText.isBlank()) p.dateText = nowString()
            liveSeconds = sp.getBoolean("live", false)
            motionExportFormat = sp.getString("motionExportFormat", "mp4") ?: "mp4"
            motionPhotoFormat = sp.getInt("motionPhotoFormat", 1).coerceIn(0, 1)
            p.sanitizeInPlace()
            // v15.16：画质档位是进程级全局状态（glass 包在 Compose 绘制阶段读取，
            // 拿不到 EditorState.params），restore 后必须同步一次，否则重启后
            // 用户选的 low 档在重启前生效、重启后悄悄回到 high。
            com.liquidglass.watermark.glass.GlassQuality.tier = p.glassTier
            true
        } catch (_: Throwable) { false }
    }
}
