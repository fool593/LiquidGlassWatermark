package com.liquidglass.watermark

import android.os.Build

/**
 * v15.17 ROM 兼容防护：
 * 按「厂商 ROM 家族 + Android 版本」而不是仅按版本号统一处理渲染策略。
 *
 * 背景：HIGH 画质档在 API 33+ 走 AGSL RuntimeShader（glass/effects/Lens.kt）。
 * 部分厂商 GPU 驱动（尤其小米/红米/POCO 澎湃 HyperOS 使用的 Adreno/Mali）对
 * RuntimeShader 编译/执行支持不完整，失败通常是 RenderThread/HWUI 层 native 崩溃
 * （SIGSEGV），Java 的 try/catch 拦不住，表现为「启动即闪退」。
 * 因此在已知风险 ROM 上默认使用 LOW 档（法线 UV 折射 + 扭曲保留、色散/菲涅尔走
 * 廉价近似，不退化 alpha），用户仍可在参数面板手动切回 HIGH。
 *
 * 其它 ROM（华为/荣耀、OPPO/一加/真我、vivo/iQOO、三星、Pixel）保留 HIGH 全量光学，
 * 仅靠「崩溃自愈」兜底（MainActivity.applyRomGuard 读 app_crash/last.txt，若上次
 * 崩溃堆栈含渲染关键字则本次启动自动降 LOW）。
 */
object RomGuard {

    const val OTHER = 0
    const val XIAOMI = 1      // 小米 / 红米 / POCO（澎湃 HyperOS / MIUI）
    const val HUAWEI = 2      // 华为 / 荣耀（鸿蒙 HarmonyOS / EMUI / MagicOS）
    const val OPPO = 3        // OPPO / 一加 / 真我（ColorOS）
    const val VIVO = 4        // vivo / iQOO（OriginOS）
    const val SAMSUNG = 5     // 三星（One UI）
    const val GOOGLE = 6      // Google Pixel（原生 Android）

    /** 识别当前设备所属 ROM 家族（Build.MANUFACTURER 小写匹配） */
    fun family(): Int {
        val m = (Build.MANUFACTURER ?: "").trim().lowercase()
        return when {
            m in setOf("xiaomi", "redmi", "poco") -> XIAOMI
            m in setOf("huawei", "honor") -> HUAWEI
            m in setOf("oppo", "realme", "oneplus") -> OPPO
            m in setOf("vivo", "iqoo") -> VIVO
            m == "samsung" -> SAMSUNG
            m == "google" -> GOOGLE
            else -> OTHER
        }
    }

    /** 人类可读的家族名（日志/报告用） */
    fun familyName(): String = when (family()) {
        XIAOMI -> "小米/红米/POCO（澎湃/MIUI）"
        HUAWEI -> "华为/荣耀（鸿蒙/EMUI/MagicOS）"
        OPPO -> "OPPO/一加/真我（ColorOS）"
        VIVO -> "vivo/iQOO（OriginOS）"
        SAMSUNG -> "三星（One UI）"
        GOOGLE -> "Google（Pixel 原生）"
        else -> "其他（${Build.MANUFACTURER} ${Build.MODEL}）"
    }

    /**
     * 纯函数策略判定（可单测/可模拟）：给定 ROM 家族与 SDK，是否默认 LOW 档。
     * 当前策略：小米系 + API 33+ -> true（澎湃基于 Android 14/15，AGSL 驱动崩溃高发）；
     * 其余默认 false，尊重用户设置并靠崩溃自愈兜底。
     * 后续若某 ROM 出现批量崩溃报告，在此加一条即可（单一策略入口）。
     */
    fun defaultTierLowFor(fam: Int, sdk: Int): Boolean = fam == XIAOMI && sdk >= 33

    /** 当前设备的默认档位判定（读取真实 Build） */
    fun defaultTierLow(sdk: Int): Boolean = defaultTierLowFor(family(), sdk)
}