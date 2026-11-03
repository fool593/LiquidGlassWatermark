package com.liquidglass.watermark

import android.os.Build
import org.json.JSONObject
import java.util.Locale

/**
 * 设备名称识别：水印第一行显示用户看得懂的「手机名称（市场名）」，第二行显示具体「型号代号」。
 * Android 系统只直接提供型号代号（Build.MODEL，如 vivo 的 V1914A），市场名需要靠映射表还原；
 * 查不到时回退 Build.MODEL，保证任何机型都有合理显示，绝不留空。
 *
 * 名称来源（按优先级）：
 * 1) Build.MODEL 本身已是市场名（如 "iQOO Neo7"、"Xiaomi 14"、"Pixel 8"）→ 直接用；
 * 2) 代码内中文映射表（主流机型，中文市场名最准）；
 * 3) assets/device_names.json 全量映射表（47 品牌 9752 标识，覆盖到罕见机型）；
 * 4) 查不到 → "品牌 + 型号代号"。
 */
object DeviceNames {

    /** 第二行：具体型号代号（Build.MODEL 原样，如 V1914A） */
    fun modelCode(): String = (Build.MODEL ?: "").trim()

    /** 厂商品牌名（首字母大写，如 vivo / OPPO / Xiaomi） */
    fun brand(): String {
        val raw = (Build.MANUFACTURER ?: "").trim()
        return raw.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
    }

    // 型号代号（大写去空格）-> 市场名。覆盖主流厂商常见机型，可无限扩充。
    // 注意：此表仅为"纯代号型 Build.MODEL"（如 vivo V2199A、三星 SM-S9180）提供市场名兜底；
    // Build.MODEL 本身已是市场名的机型（如 "iQOO Neo7"、"Xiaomi 14"、"Pixel 8"）无需查表，
    // 直接自动识别显示，不再依赖此表。
    private val MAP = HashMap<String, String>(260)

    private fun put(code: String, name: String) { MAP[code.uppercase(Locale.US).replace(" ", "")] = name }

    init {
        // —— vivo / iQOO ——
        put("V1914A", "iQOO Neo")
        put("V1936A", "iQOO Neo 855")
        put("V1955A", "iQOO 3")
        put("V2024A", "iQOO Neo3")
        put("V2025A", "iQOO Z1")
        put("V2055A", "iQOO 5")
        put("V2081A", "iQOO 7")
        put("V2118A", "iQOO Neo5")
        put("V2171A", "iQOO 8")
        put("V2199A", "iQOO Neo7")
        put("V2231A", "iQOO Neo7 竞速版")
        put("V2301A", "iQOO 11")
        put("V2302A", "iQOO Neo8")
        put("V2303A", "iQOO Neo8 Pro")
        put("V2329A", "iQOO 12")
        put("V2338A", "iQOO 12 Pro")
        put("V2218A", "iQOO Z5")
        put("V2166A", "iQOO Z5x")
        put("V2245A", "iQOO Z6")
        put("V2243A", "iQOO Z7")
        put("V2271A", "iQOO Z8")
        put("V2249A", "iQOO 11S")
        put("V2308A", "iQOO Neo9")
        put("V2307A", "iQOO Neo9 Pro")
        put("V2401A", "iQOO 12S")
        put("V2330A", "iQOO Z9 Turbo")
        put("V1824A", "iQOO")
        put("V1824BA", "iQOO")
        put("V1950A", "vivo X30")
        put("V2001A", "vivo X50")
        put("V2056A", "vivo X60")
        put("V2109A", "vivo X60 Pro")
        put("V2133A", "vivo X70")
        put("V2104A", "vivo X70 Pro")
        put("V2183A", "vivo X80")
        put("V2145A", "vivo X80 Pro")
        put("V2241A", "vivo X80")
        put("V2242A", "vivo X90")
        put("V2217A", "vivo X90 Pro")
        put("V2244A", "vivo X90 Pro+")
        put("V2309A", "vivo X100")
        put("V2306A", "vivo X100 Pro")
        put("V2323A", "vivo X100S")
        put("V2324A", "vivo X200")
        put("V2325A", "vivo X200 Pro")
        put("V2326A", "vivo X200 Pro mini")
        put("V1930A", "vivo Y3")
        put("V2034A", "vivo Y52s")
        put("V2068A", "vivo Y31s")
        put("V2168A", "vivo Y76s")
        put("V2254A", "vivo S15")
        put("V2270A", "vivo S16")
        put("V2318A", "vivo S18")
        put("V2300A", "vivo S19")
        put("V2028A", "vivo X60t")
        // —— Xiaomi / Redmi ——
        put("MI 9", "Xiaomi 9")
        put("MI 10", "Xiaomi 10")
        put("M2001J2C", "Xiaomi 10")
        put("M2102K1C", "Xiaomi 11")
        put("2201123C", "Xiaomi 12")
        put("2211133C", "Xiaomi 13")
        put("23127PN0CC", "Xiaomi 14")
        put("24031PN0DC", "Xiaomi 15")
        put("M2011K2C", "Xiaomi 11 Ultra")
        put("2203121C", "Xiaomi 12S Ultra")
        put("2304FPN6DC", "Xiaomi 13 Ultra")
        put("24030PN60G", "Xiaomi 14 Ultra")
        put("M2007J3SC", "Redmi K30S")
        put("M2012K11AC", "Redmi K40")
        put("22021211RC", "Redmi K50")
        put("22127RK46C", "Redmi K60")
        put("23117RK66C", "Redmi K70")
        put("24069RA21C", "Redmi K70 Ultra")
        put("M1901F9E", "Redmi Note 7")
        put("M2010J19SC", "Redmi Note 9 Pro")
        put("21091116AC", "Redmi Note 11 Pro")
        put("22101316C", "Redmi Note 12 Pro")
        put("23090RA98C", "Redmi Note 13 Pro")
        // —— HUAWEI / HONOR ——
        put("ELS-AN00", "Huawei P40 Pro")
        put("ANA-AN00", "Huawei P40")
        put("TAS-AN00", "Huawei Mate 30")
        put("LIO-AN00", "Huawei Mate 30 Pro")
        put("NOH-AN00", "Huawei Mate 40 Pro")
        put("ALN-AL00", "Huawei Mate 60 Pro")
        put("BAL-AL00", "Huawei P50 Pro")
        put("ADY-AN00", "Huawei Pura 70")
        put("BMH-AN10", "Honor 30")
        put("YOK-AN10", "Honor V40")
        put("ELZ-AN10", "Honor Magic3")
        put("PGT-AN00", "Honor Magic5 Pro")
        put("BVL-AN16", "Honor Magic6 Pro")
        put("ANY-AN00", "Honor X10")
        // —— OPPO / OnePlus / realme ——
        put("PHB110", "OnePlus 11")
        put("PJD110", "OnePlus 12")
        put("PKX110", "OnePlus 13")
        put("KB2000", "OnePlus 8T")
        put("IN2020", "OnePlus 8 Pro")
        put("LE2123", "OnePlus 9 Pro")
        put("NE2210", "OnePlus 10 Pro")
        put("PGEM10", "OPPO Find X6 Pro")
        put("PJE110", "OPPO Find X7")
        put("PZB110", "OPPO Find X8 Pro")
        put("PENM00", "OPPO Reno5 Pro")
        put("PDSM00", "OPPO Reno6 Pro")
        put("RMX3360", "realme GT2 Pro")
        put("RMX3820", "realme GT5 Pro")
        put("RMX2202", "realme Q2 Pro")
        // —— Samsung ——
        put("SM-G9910", "Galaxy S21")
        put("SM-G9980", "Galaxy S21 Ultra")
        put("SM-S9080", "Galaxy S22 Ultra")
        put("SM-S9180", "Galaxy S23 Ultra")
        put("SM-S9280", "Galaxy S24 Ultra")
        put("SM-S9380", "Galaxy S25 Ultra")
        put("SM-F9460", "Galaxy Z Fold5")
        put("SM-F7310", "Galaxy Z Flip5")
    }

    /**
     * Build.MODEL 是否为"纯型号代号"（无人类可读市场名，如 vivo V2199A / 三星 SM-S9180 / OPPO PJD110）。
     * 匹配 → 需要查映射表或回退"品牌+代号"；不匹配 → MODEL 本身已是市场名（如 "iQOO Neo7"），直接用。
     */
    private val MODEL_CODE_RE = Regex(
        """^(V\d{4}[A-Z]{0,2}|SM-[A-Za-z0-9]+|PH[A-Z]{2}\d{3}|P[A-Z]{3}M\d{2}|CPH\d{4}|KB\d{4}|IN\d{4}|LE\d{4}|NE\d{4}|PHB\d{3}|RMX\d{4}|XT\d{4}|LM-\w+|XQ-\w+|[A-Z]{3}-\w+|M\d{4}[A-Z0-9]{2,}|RM\d{4}|M\d{4}I\d{4}|TA-\w+|TE-\w+|RM\d{3,5}|CP\d{4}|IT\d{4}|HI\d{4}|SC-\w+|HU\d{4}|AS\d{4}|AR\d{4}|W-\d{4}|201\d{4}|A\d{4}[A-Z]{2}|K\d{4}|N\d{4}[A-Z0-9]{2,}|T\d{3}[A-Z0-9]{2,}|MZ\d{4}|GM\d{4}|E\d{3}[A-Z0-9]{2,}|230\d{5,}|240\d{5,}|220\d{5,}|210\d{5,}|M20\d{2}[A-Z0-9]{2,})$"""
    )

    // —— assets 全量映射（47 品牌 9752 标识，懒加载一次）——
    @Volatile
    private var assetsMap: Map<String, String>? = null

    private fun assetsMap(): Map<String, String> {
        assetsMap?.let { return it }
        synchronized(this) {
            assetsMap?.let { return it }
            val m = HashMap<String, String>(12000)
            runCatching {
                val ctx = App.appContext
                val text = ctx.assets.open("device_names.json").bufferedReader().use { it.readText() }
                val obj = JSONObject(text)
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    m[k] = obj.getString(k)
                }
            }.onFailure {
                DebugLog.log("DEVICE", "assets 机型表加载失败 ${it.javaClass.simpleName}: ${it.message}")
            }
            assetsMap = m
            return m
        }
    }

    private fun prefixBrand(manufacturer: String, name: String): String {
        val m = manufacturer.trim()
        if (m.isNotBlank() && !name.startsWith(m, ignoreCase = true)) return "$m $name".trim()
        return name
    }

    /**
     * 第一行：手机市场名。自动获取策略（按优先级，准确性最大）：
     * 1) 命中代码内中文表（主流机型最准）；
     * 2) 命中 assets 全量表（9752 标识，覆盖到罕见机型）；
     * 3) Build.MODEL 本身已是市场名（非纯代号形态）→ 直接用；
     * 4) 查不到 → "品牌 + 型号代号"。
     */
    fun marketName(): String {
        val code = modelCode()
        if (code.isBlank()) return brand().ifBlank { "My Phone" }
        val rawM = (Build.MANUFACTURER ?: "").trim()
        val key = code.uppercase(Locale.US).replace(" ", "")
        // 1) 代码内中文映射表（主流机型）
        MAP[key]?.let { name ->
            val r = prefixBrand(rawM, name)
            DebugLog.throttleLog("DEVICE", "机型命中内置表: $code -> $r", 5000L)
            return r
        }
        // 2) assets 全量映射表
        assetsMap()[key]?.let { name ->
            val r = prefixBrand(rawM, name)
            DebugLog.throttleLog("DEVICE", "机型命中 assets 表: $code -> $r", 5000L)
            return r
        }
        // 3) MODEL 已是市场名：直接返回（补品牌前缀去重）
        if (!MODEL_CODE_RE.matches(code)) {
            val r = prefixBrand(rawM, code)
            DebugLog.throttleLog("DEVICE", "机型为市场名直显: $r", 5000L)
            return r
        }
        // 4) 纯代号但查不到 → 品牌 + 代号
        val b = brand()
        val r = if (b.isNotBlank() && !code.contains(b, ignoreCase = true)) "$b $code".trim() else code
        DebugLog.throttleLog("DEVICE", "机型查表未命中回退: $r", 5000L)
        return r
    }
}
