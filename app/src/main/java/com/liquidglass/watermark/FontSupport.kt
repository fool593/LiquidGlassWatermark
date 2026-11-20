package com.liquidglass.watermark

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import java.io.File

/**
 * v15.37 字体判定与隔离。
 *
 * 背景：国产 ROM（vivo/OPPO/小米…）允许通过「主题商店」替换系统字体。被替换后，
 * `Typeface.SANS_SERIF` 与 `Typeface.DEFAULT` 都会解析到**同一套主题字体**，
 * 于是「默认字体 / 系统字体」切换在画面上完全看不出差别（用户实测"切换不了"），
 * 同时整个 App 的界面文字也被主题字体接管，字形被压窄 → 界面看着"变扁变长"。
 *
 * 规则（用户口径）：
 *  - 默认字体 = 出厂字库（直接读 /system/fonts 下的原生字体文件，主题只做 overlay，不覆盖源文件）
 *  - 系统字体 = 当前系统实际生效字体（Typeface.DEFAULT，主题替换后即为第三方字体）
 *  - 系统字体与默认字体一致（没被主题换过）→ 两者等价，无需特别处理
 *  - 系统字体与默认字体不同（被主题换过）→ 选中「系统字体」时才加载这套第三方字体
 */
object FontSupport {

    private val factoryNormal: Typeface by lazy { loadFactory(bold = false) }
    private val factoryBold: Typeface by lazy { loadFactory(bold = true) }

    /** 出厂默认字体（拉丁+中文都用系统原生字库，不受主题字体 overlay 影响） */
    fun factory(bold: Boolean): Typeface = if (bold) factoryBold else factoryNormal

    /** 系统当前生效字体（主题字体已生效时就是第三方字体） */
    fun system(bold: Boolean): Typeface =
        runCatching {
            Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }.getOrDefault(factory(bold))

    @Volatile private var themed: Boolean? = null

    /** 系统字体是否已被第三方主题替换（结果缓存，首次调用做一次像素比对） */
    fun isSystemThemed(): Boolean {
        themed?.let { return it }
        val v = runCatching { !sameGlyphs(factory(false), system(false)) }.getOrDefault(false)
        themed = v
        return v
    }

    /** 供设置面板展示当前判定结果 */
    fun describe(): String = if (isSystemThemed()) "系统字体已由主题替换" else "系统字体=出厂字体"

    private fun loadFactory(bold: Boolean): Typeface {
        // 出厂字库候选：汉字优先（覆盖中英），再退拉丁字库；这些文件即使主题换字也始终存在
        val candidates = if (bold) listOf(
            "/system/fonts/NotoSansCJK-Bold.ttc",
            "/system/fonts/NotoSansCJKsc-Bold.otf",
            "/system/fonts/NotoSansSC-Bold.otf",
            "/system/fonts/Roboto-Bold.ttf",
            "/system/fonts/DroidSans-Bold.ttf"
        ) else listOf(
            "/system/fonts/NotoSansCJK-Regular.ttc",
            "/system/fonts/NotoSansCJKsc-Regular.otf",
            "/system/fonts/NotoSansSC-Regular.otf",
            "/system/fonts/Roboto-Regular.ttf",
            "/system/fonts/DroidSans.ttf"
        )
        for (path in candidates) {
            if (!File(path).exists()) continue
            runCatching { Typeface.createFromFile(path) }.getOrNull()?.let { return it }
        }
        // 兜底：仍走 sans-serif（可能被主题改写，但至少不会崩）
        return Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    /** 用同一段文字分别用两个字库渲染成小位图，逐像素比较 —— 字形不同即说明系统字体被替换 */
    private fun sameGlyphs(a: Typeface, b: Typeface): Boolean {
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
            c.drawText("永Aa8g", 2f, 32f, pt)
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
