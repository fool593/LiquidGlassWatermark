package com.liquidglass.watermark

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import java.io.File

object FontSupport {

    private val factoryNormal: Typeface? by lazy { pickFactory(bold = false) }
    private val factoryBold: Typeface? by lazy { pickFactory(bold = true) }

    @Volatile var factoryNormalFile: String = "未解析"
        private set
    @Volatile var factoryBoldFile: String = "未解析"
        private set

    fun factory(bold: Boolean): Typeface =
        (if (bold) factoryBold else factoryNormal)
            ?: Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)

    fun system(bold: Boolean): Typeface =
        runCatching {
            Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }.getOrDefault(factory(bold))

    @Volatile private var themed: Boolean? = null

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

    fun describe(): String =
        if (isSystemThemed()) "系统字体与出厂字体不同" else "系统字体=出厂字体"

    fun status(): String =
        "factory=${factoryNormalFile} themed=${isSystemThemed()} " +
            "differ=${runCatching { !sameGlyphs(factory(false), system(false), "璃Aa8g") }.getOrDefault(false)}"

    private fun pickFactory(bold: Boolean): Typeface? {
        for (path in candidates(bold)) {
            val tf = runCatching { Typeface.createFromFile(path) }.getOrNull() ?: continue
            if (!rendersGlyphs(tf)) continue
            if (bold) factoryBoldFile = path else factoryNormalFile = path
            return tf
        }
        if (bold) factoryBoldFile = "回退 sans-serif" else factoryNormalFile = "回退 sans-serif"
        return null
    }

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
