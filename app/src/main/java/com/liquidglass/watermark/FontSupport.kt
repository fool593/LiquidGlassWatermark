package com.liquidglass.watermark

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import java.io.File

/**
 * 字体判定与隔离（v15.39 重写）。
 *
 * 背景：国产 ROM（vivo/OPPO/小米…）允许通过「主题商店」替换系统字体。被替换后，
 * `Typeface.SANS_SERIF` 与 `Typeface.DEFAULT` 都会解析到**同一套主题字体**，
 * 于是「默认字体 / 系统字体」切换在画面上完全看不出差别（用户反馈"切换不了"）。
 *
 * 规则（用户口径）：
 *  - 默认字体 = 出厂字库：直接读 /system/fonts 下的原生字体文件（主题只做 overlay，不替换源文件）
 *  - 系统字体 = 当前系统实际生效字体（Typeface.DEFAULT，主题替换后即为第三方字体）
 *  - 两者一致（没被主题换过）→ 等价，不必特别处理
 *  - 两者不同（被主题换过）→ 选「系统字体」时才加载这套第三方字体
 *
 * v15.39 关键修正：出厂字库不再写死 5 个文件名（很多 ROM 上就是这几个名字对不上，
 * 于是 loadFactory 一路回落到 SANS_SERIF —— 而 SANS_SERIF 已被主题接管，
 * 结果「默认字体」和「系统字体」又变成同一套字，切换看似无效）。现在改为：
 *   1. 扫描 /system/fonts 真实存在的文件，按「CJK 优先 → 拉丁兜底」挑选；
 *   2. 逐个 `Typeface.createFromFile` 试加载，校验能渲染出字形（黑像素占比 > 阈值）才采用；
 *   3. 全部失败才回落 SANS_SERIF，并在 [status] 里如实标注（绝不再假装"默认字体"）。
 * 所有结果缓存，切换字体不重复扫盘。
 */
object FontSupport {

    /** 出厂字形（normal / bold），懒加载；加载失败为 null */
    private val factoryNormal: Typeface? by lazy { pickFactory(bold = false) }
    private val factoryBold: Typeface? by lazy { pickFactory(bold = true) }

    /** 出厂字体当前选中的文件路径（供状态展示 / 诊断） */
    @Volatile var factoryNormalFile: String = "未解析"
        private set
    @Volatile var factoryBoldFile: String = "未解析"
        private set

    /** 出厂默认字体（拉丁+中文都用系统原生字库，不受主题字体 overlay 影响） */
    fun factory(bold: Boolean): Typeface =
        (if (bold) factoryBold else factoryNormal)
            ?: Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)

    /** 系统当前生效字体（主题字体已生效时就是第三方字体） */
    fun system(bold: Boolean): Typeface =
        runCatching {
            Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }.getOrDefault(factory(bold))

    @Volatile private var themed: Boolean? = null

    /**
     * 系统字体是否已被第三方主题替换。
     *
     * 判定不能拿「出厂 CJK 字库」直接和 DEFAULT 比：屏幕上中英混排时，
     * DEFAULT 的拉丁部分是 Roboto、汉字部分才是 NotoSansCJK，和单独的 CJK 文件永远不等，
     * 于是任何机器都会被误报成"主题替换"（旧版就是这样，连 AOSP 模拟器都误报）。
     * 这里按字符集分开比：
     *   拉丁样本 "Aa8g12"  ↔ 出厂 Roboto/Droid（未被换主题时应当完全一致）
     *   汉字样本 "永漢"     ↔ 出厂 NotoSansCJK
     * 任一组不一致 → 说明 DEFAULT 被主题字体接管。
     */
    fun isSystemThemed(): Boolean {
        themed?.let { return it }
        val v = runCatching {
            val sys = system(false)
            val latinRef = stockLatin(bold = false)?.let { !sameGlyphs(sys, it, "Aa8g12") } ?: false
            val cjkRef = !sameGlyphs(sys, factoryNormal ?: sys, "永漢")
            latinRef || cjkRef
        }.getOrDefault(false)
        themed = v
        return v
    }

    /** 出厂拉丁字库（Roboto / Droid），主题换字时该文件不会被改动 */
    private fun stockLatin(bold: Boolean): Typeface? {
        val names = if (bold) listOf("Roboto-Bold.ttf", "DroidSans-Bold.ttf")
        else listOf("Roboto-Regular.ttf", "DroidSans.ttf")
        for (n in names) {
            val f = File("/system/fonts/$n")
            if (!f.exists()) continue
            runCatching { Typeface.createFromFile(f) }.getOrNull()?.let { return it }
        }
        return null
    }

    /** 供设置面板 / 日志展示当前判定结果（不臆断是否"主题替换"，只如实说两者是否同字） */
    fun describe(): String =
        if (isSystemThemed()) "系统字体与出厂字体不同" else "系统字体=出厂字体"

    /** 一行诊断：出厂文件 / 是否被主题替换 / 实际是否可区分 */
    fun status(): String =
        "factory=${factoryNormalFile} themed=${isSystemThemed()} " +
            "differ=${runCatching { !sameGlyphs(factory(false), system(false), "璃Aa8g") }.getOrDefault(false)}"

    private fun pickFactory(bold: Boolean): Typeface? {
        for (path in candidates(bold)) {
            val tf = runCatching { Typeface.createFromFile(path) }.getOrNull() ?: continue
            if (!rendersGlyphs(tf)) continue          // 文件能解析但画不出字（损坏/缺表）→ 丢掉
            if (bold) factoryBoldFile = path else factoryNormalFile = path
            return tf
        }
        if (bold) factoryBoldFile = "回退 sans-serif" else factoryNormalFile = "回退 sans-serif"
        return null
    }

    /** 扫描 /system/fonts 得到候选列表：CJK 常规/粗体优先，其次拉丁，最后 Droid */
    private fun candidates(bold: Boolean): List<String> {
        val dir = File("/system/fonts")
        val all = runCatching { dir.listFiles()?.map { it.name } ?: emptyList() }.getOrDefault(emptyList())
        val cjk = all.filter {
            (it.startsWith("NotoSansCJK") || it.startsWith("NotoSansSC") || it.startsWith("NotoSerifCJK") ||
                it.startsWith("NotoSansMonoCJK") || it.startsWith("SourceHanSans") || it.startsWith("MiSans") ||
                it.startsWith("HarmonyOS") || it.startsWith("DroidSansFallback"))
        }
        fun hasBoldTag(n: String) = n.contains("Bold", true) || n.contains("-Bold", true)
        val cjkPick = cjk.filter { hasBoldTag(it) == bold }.ifEmpty { cjk }
        val latin = all.filter {
            (it.startsWith("Roboto-") || it.startsWith("DroidSans")) &&
                !it.contains("Mono", true) && !it.contains("Italic", true)
        }
        val latinPick = latin.filter { hasBoldTag(it) == bold }.ifEmpty { latin }
        // 明确写死的路径作为兜底（部分 ROM 目录列表受限）
        val hard = if (bold) listOf(
            "/system/fonts/NotoSansCJK-Bold.ttc",
            "/system/fonts/NotoSansSC-Bold.otf",
            "/system/fonts/Roboto-Bold.ttf",
            "/system/fonts/DroidSans-Bold.ttf"
        ) else listOf(
            "/system/fonts/NotoSansCJK-Regular.ttc",
            "/system/fonts/NotoSansSC-Regular.otf",
            "/system/fonts/Roboto-Regular.ttf",
            "/system/fonts/DroidSans.ttf"
        )
        return (cjkPick + hard + latinPick)
            .map { if (it.startsWith("/")) it else "/system/fonts/$it" }
            .distinct()
            .filter { runCatching { File(it).exists() }.getOrDefault(false) }
    }

    /** 该 Typeface 是否真能画出字（防止加载出空字形导致"文字消失"） */
    private fun rendersGlyphs(tf: Typeface): Boolean {
        val bmp = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFF000000.toInt())
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = tf
            textSize = 24f
            color = 0xFFFFFFFF.toInt()
        }
        c.drawText("永Aa8g", 2f, 26f, p)
        var lit = 0
        val px = IntArray(64 * 32)
        bmp.getPixels(px, 0, 64, 0, 0, 64, 32)
        bmp.recycle()
        for (v in px) if ((v and 0x00FFFFFF) != 0) lit++
        return lit > 40
    }

    /** 用同一段文字分别用两个字库渲染成小位图，逐像素比较 —— 字形不同即说明两者不是同一套字 */
    private fun sameGlyphs(a: Typeface, b: Typeface, sample: String = "永Aa8g"): Boolean {
        val size = 96
        val h = 44
        fun render(tf: Typeface): IntArray {
            val bmp = Bitmap.createBitmap(size, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(0xFF000000.toInt())
            val pt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.typeface = tf
                textSize = 26f
                color = 0xFFFFFFFF.toInt()
            }
            c.drawText(sample, 2f, 32f, pt)
            val px = IntArray(size * h)
            bmp.getPixels(px, 0, size, 0, 0, size, h)
            bmp.recycle()
            return px
        }
        val p1 = render(a)
        val p2 = render(b)
        if (p1.size != p2.size) return false
        for (i in p1.indices) if (p1[i] != p2[i]) return false
        return true
    }
}
