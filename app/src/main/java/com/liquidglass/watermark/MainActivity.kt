package com.liquidglass.watermark

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.media.MediaFormat
import android.media.MediaScannerConnection
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.liquidglass.watermark.ui.EditorScreen
import com.liquidglass.watermark.ui.LiquidGlassTheme
import com.liquidglass.watermark.ui.applyLiquidGlassStyle
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    var rev by mutableStateOf(0)

    private val _progressPct = mutableStateOf(-1)
    var progressPct: Int
        get() = _progressPct.value
        set(v) {
            val wasIdle = _progressPct.value < 0
            if (wasIdle && v >= 0) {
                preview?.captureExportSnapshot()
                EditorState.captureExportParams()
            }
            _progressPct.value = v
            EditorState.isExporting = v >= 0

            if (v < 0) {
                preview?.clearExportSnapshot()
                EditorState.releaseExportParams()
            }
        }
    var preview: WatermarkEditorView? = null

    @Volatile private var pendingImageUri: Uri? = null

    @Volatile private var pendingLogoPick = false

    @Volatile private var mpSlot = 0
    @Volatile var mpImage: ByteArray? = null
    @Volatile var mpVideoUri: Uri? = null
    var showGifPanel by mutableStateOf(false)
    var showMotionPhotoPanel by mutableStateOf(false)
    var mpImageSet by mutableStateOf(false)
    var mpVideoSet by mutableStateOf(false)

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var prefs: android.content.SharedPreferences
    private var vivoTipShown = false

    override fun attachBaseContext(newBase: Context) {
        val p = newBase.getSharedPreferences(EditorState.PREFS, MODE_PRIVATE)
        val lang = p.getString("lang", "zh") ?: "zh"
        val cfg = Configuration(newBase.resources.configuration)
        cfg.setLocale(Locale(lang))
        super.attachBaseContext(newBase.createConfigurationContext(cfg))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        getSharedPreferences("crash", MODE_PRIVATE).edit().putInt("count", 0).apply()
        App.showCrashLog(this)
        DebugLog.init(this)
        prefs = getSharedPreferences(EditorState.PREFS, MODE_PRIVATE)
        EditorState.attach(this)
        EditorState.restore(this)

        setDarkMode(com.liquidglass.watermark.darkModeState)

        applyApiMode()

        for (pi in 0 until EditorState.PLAN_COUNT) {
            val pp = EditorState.planParamsAt(pi)
            val b2 = DeviceNames.brand()

            if (pp.model.isBlank()) pp.model = EditorState.defaultModelName()
            if (pp.modelCode.isBlank()) pp.modelCode = EditorState.defaultModelCode()
            if (pp.dateText.isEmpty()) pp.dateText = EditorState.nowString()
            if (pp.logoBrand == EditorState.BRAND_NONE) {
                val autoLogo2 = when (b2.lowercase()) {
                    in setOf("vivo", "iqoo", "oppo", "oneplus", "realme") -> EditorState.BRAND_VIVO
                    in setOf("xiaomi", "redmi", "poco") -> EditorState.BRAND_XIAOMI
                    "huawei", "honor" -> EditorState.BRAND_HUAWEI
                    else -> -1
                }
                if (autoLogo2 >= 0) pp.logoBrand = autoLogo2
            }
        }
        val p = EditorState.params
        val b = DeviceNames.brand()

        if (!EditorState.modelCustom && p.model.isBlank()) p.model = EditorState.defaultModelName()
        if (!EditorState.modelCodeCustom && p.modelCode.isBlank()) p.modelCode = EditorState.defaultModelCode()
        if (p.dateText.isEmpty()) p.dateText = EditorState.nowString()

        if (p.logoBrand == EditorState.BRAND_NONE) {
            val autoLogo = when (b.lowercase()) {
                in setOf("vivo", "iqoo", "oppo", "oneplus", "realme") -> EditorState.BRAND_VIVO
                in setOf("xiaomi", "redmi", "poco") -> EditorState.BRAND_XIAOMI
                "huawei", "honor" -> EditorState.BRAND_HUAWEI
                else -> -1
            }
            if (autoLogo >= 0) p.logoBrand = autoLogo
        }
        setContent {
            com.liquidglass.watermark.ui.ProvideAdaptiveUiScale {
                LiquidGlassTheme { EditorScreen(this) }
            }
        }
        ui.postDelayed(tick, 1000)

        //   adb shell am start -n com.liquidglass.watermark/.MainActivity --es ljgAutoTest "logo:/sdcard/logo.png"
        //   adb shell am start ... --es ljgAutoTest "fluid:1" / "fluid:0"

        intent.getStringExtra("ljgAutoTest")?.let { at ->
            ui.postDelayed({ runAutoTest(at) }, 1500L)
        }
    }

    private fun runAutoTest(spec: String) {
        Thread {
            try {
                DebugLog.log("AUTOTEST", "触发: $spec")
                when {

                    spec.startsWith("font:") -> {
                        val v = spec.substringAfter("font:").trim().toIntOrNull() ?: 0
                        val mode = if (v == 1) FONT_MODE_SYSTEM else FONT_MODE_DEFAULT
                        EditorState.params.fontMode = mode
                        val name = if (mode == FONT_MODE_SYSTEM) "系统字体" else "默认字体"
                        DebugLog.log("AUTOTEST", "字体模式已切换 fontMode=$mode($name)")
                        runOnUiThread { bump() }
                    }

                    spec.startsWith("planswitch:") -> {
                        val v = spec.substringAfter("planswitch:").trim().toIntOrNull() ?: 0
                        val idx = if (v == 1) EditorState.PLAN_LIGHT else EditorState.PLAN_CLASSIC
                        val before = EditorState.params.fontMode
                        EditorState.switchPlan(this, idx)
                        val after = EditorState.params.fontMode
                        DebugLog.log("AUTOTEST", "方案切换 idx=$idx 前fontMode=$before 后fontMode=$after")
                    }
                    spec.startsWith("logo2:") -> {

                        val f = File(getExternalFilesDir(null), "auto_logo2.png")
                        if (f.exists()) {
                            handleCustomLogo(android.net.Uri.fromFile(f))
                        } else {
                            DebugLog.log("AUTOTEST", "无 auto_logo2.png（请推送至 ${f.absolutePath}）")
                        }
                    }
                    spec.startsWith("logo:") -> {

                        android.util.Log.i("LGW-AUTO", "hook logo: exists 检查前 dir=${getExternalFilesDir(null)}")
                        val f = File(getExternalFilesDir(null), "auto_logo.png")
                        val ex = f.exists()
                        android.util.Log.i("LGW-AUTO", "hook exists=$ex path=${f.absolutePath}")
                        if (ex) {

                            android.util.Log.i("LGW-AUTO", "hook 调用 handleCustomLogo")
                            handleCustomLogo(android.net.Uri.fromFile(f))
                            android.util.Log.i("LGW-AUTO", "hook handleCustomLogo 返回")
                        } else {
                            DebugLog.log("AUTOTEST", "无 auto_logo.png（请推送至 ${f.absolutePath}）")
                        }
                    }
                    spec == "fluid:1" -> {
                        EditorState.params.fluidEnabled = true
                        EditorState.persist(this)
                        DebugLog.log("AUTOTEST", "fluid=1（速度 ${EditorState.params.fluidSpeed}）")
                        runOnUiThread { refreshHard() }
                    }
                    spec == "fluid:0" -> {
                        EditorState.params.fluidEnabled = false
                        EditorState.persist(this)
                        DebugLog.log("AUTOTEST", "fluid=0")
                        runOnUiThread { refreshHard() }
                    }

                    spec == "refrproof" -> {
                        val bw = 240; val bh = 152
                        val bg = android.graphics.Bitmap.createBitmap(bw, bh, android.graphics.Bitmap.Config.ARGB_8888)
                        val px0 = IntArray(bw * bh)
                        for (j in 0 until bh) for (i in 0 until bw) {
                            px0[j * bw + i] = if ((((i + j) / 9) % 2) == 0) 0xFFD8D8D8.toInt() else 0xFF202090.toInt()
                        }
                        bg.setPixels(px0, 0, bw, 0, 0, bw, bh)
                        val r = 40f
                        fun rimBand(i: Int, j: Int): Boolean {

                            val gl = 44f; val gt = 24f; val gw = 152f; val gh = 104f; val rr = 30f
                            val hx = gw / 2f; val hy = gh / 2f
                            val qx = Math.abs(i - (gl + gw / 2f)) - (hx - rr)
                            val qy = Math.abs(j - (gt + gh / 2f)) - (hy - rr)
                            val ox = Math.max(qx, 0f); val oy = Math.max(qy, 0f)

                            val sd = Math.sqrt((ox * ox + oy * oy).toDouble()).toFloat() - rr +
                                Math.min(Math.max(qx, qy), 0f)
                            return sd < -0.5f && sd > -14f
                        }
                        fun run2(model: Int, refr: Int): Pair<Float, Float> {
                            EditorState.params.refractionModel = model

                            val gl = 44; val gt = 24; val gw = 152; val gh = 104
                            val out = LiquidGlassEngine.compose(bg, gl, gt, gw, gh, 30f, 0,
                                0.5f, refr / 100f, 0f, 0f, 0f, false, 0f,
                                EditorState.HL_PLAIN, 0f, 0f, 1f,
                                0x00000000, 0f, 0f, 1f,
                                reuseOut = null)
                            val op = IntArray(bw * bh)
                            out?.getPixels(op, 0, bw, 0, 0, bw, bh)
                            out?.recycle()
                            var rimHit = 0; var rimN = 0; var intHit = 0; var intN = 0
                            for (j in 0 until bh) for (i in 0 until bw) {
                                val idx = j * bw + i
                                val a = (op[idx] ushr 24) and 0xFF
                                if (a < 32) continue
                                val b0 = px0[idx]; val b1 = op[idx]
                                val d = Math.abs(((b1 shr 16) and 0xFF) - ((b0 shr 16) and 0xFF)) +
                                    Math.abs(((b1 shr 8) and 0xFF) - ((b0 shr 8) and 0xFF)) +
                                    Math.abs((b1 and 0xFF) - (b0 and 0xFF))
                                if (rimBand(i, j)) { rimN++; if (d > 96f) rimHit++ } else { intN++; if (d > 96f) intHit++ }
                            }
                            return (if (rimN > 0) rimHit.toFloat() / rimN else 0f) to
                                (if (intN > 0) intHit.toFloat() / intN else 0f)
                        }
                        val ctrl = run2(0, 0)
                        val leg = run2(0, 100)
                        val m1 = run2(1, 100)
                        DebugLog.log("REFRPROOF",
                            "rim翻转率 对照=${"%.3f".format(ctrl.first)} 旧模型=${"%.3f".format(leg.first)} 高度场=${"%.3f".format(m1.first)} | " +
                            "内部翻转率 对照=${"%.3f".format(ctrl.second)} 旧=${"%.3f".format(leg.second)} 高度场=${"%.3f".format(m1.second)}")
                        runCatching { bg.recycle() }
                    }

                    spec == "dumpglass" -> {
                        val bw = 240; val bh = 152
                        val bg = android.graphics.Bitmap.createBitmap(bw, bh, android.graphics.Bitmap.Config.ARGB_8888)
                        val px0 = IntArray(bw * bh)
                        for (j in 0 until bh) for (i in 0 until bw) {
                            px0[j * bw + i] = if ((((i + j) / 9) % 2) == 0) 0xFFD8D8D8.toInt() else 0xFF202090.toInt()
                        }
                        bg.setPixels(px0, 0, bw, 0, 0, bw, bh)

                        val probe = IntArray(bw * bh)
                        bg.getPixels(probe, 0, bw, 0, 0, bw, bh)
                        DebugLog.log("DUMP", "bg(100,70)=#%08X bg(80,60)=#%08X bg(20,20)=#%08X".format(
                            probe[70 * bw + 100], probe[60 * bw + 80], probe[20 * bw + 20]))
                        val out = LiquidGlassEngine.compose(bg, 44, 24, 152, 104, 30f, 0,
                            0.5f, 0f, 0f, 0f, 0f, false, 0f,
                            EditorState.HL_PLAIN, 0f, 0f, 1f,
                            0x00000000, 0f, 0f, 1f,
                            reuseOut = null)
                        val op = IntArray(bw * bh)
                        out?.getPixels(op, 0, bw, 0, 0, bw, bh)
                        out?.recycle()

                        val pts = listOf(80 to 60, 100 to 70, 120 to 75, 150 to 80, 90 to 90, 50 to 30, 60 to 100)
                        for ((i, j) in pts) {
                            val idx = j * bw + i
                            DebugLog.log("DUMP", "($i,$j) src=#%08X out=#%08X a=%d".format(px0[idx], op[idx], (op[idx] ushr 24) and 0xFF))
                        }

                        DebugLog.log("DUMP", "outside(20,20) src=#%08X".format(px0[20 * bw + 20]))
                        runCatching { bg.recycle() }
                    }

                    //   adb shell am start -n com.liquidglass.watermark/.MainActivity \
                    //       --es ljgAutoTest "glassperf"
                    spec == "glassperf" -> {
                        DebugLog.log("GLASSPERF", "reset")
                        com.liquidglass.watermark.glass.GlassPerf.reset()

                        for (i in 1..20) {
                            val tick = i
                            ui.postDelayed({
                                val snap = com.liquidglass.watermark.glass.GlassPerf.snapshot()
                                if (snap.any { c -> c in '1'..'9' }) {
                                    DebugLog.log("GLASSPERF", "t=${tick * 2}s $snap")
                                }
                            }, i * 2_000L)
                        }
                    }

                    //   adb shell am start -n com.liquidglass.watermark/.MainActivity \
                    //       --es ljgAutoTest "bench:5,14,15,16,30,60,300"

                    spec.startsWith("bench") -> {
                        val body = spec.substringAfter("bench", "").trimStart(':', '-')
                        val parts = body.split(':')
                        fun parseList(s: String): List<Int> =
                            s.split(',', ' ').mapNotNull { it.trim().toIntOrNull() }
                        val durs = parseList(parts.getOrElse(0) { "" })
                            .filter { it in 1..3_600 }
                            .ifEmpty { listOf(5, 14, 15, 16, 30, 60, 300) }
                        val fps = parseList(parts.getOrElse(1) { "" })
                            .filter { it in 1..240 }
                            .ifEmpty { listOf(30) }
                        DebugLog.log("AUTOTEST", "bench 开始 时长=$durs fps=$fps")
                        val bf = ExportBench.run(this, preview, durs, fps)
                        DebugLog.log("AUTOTEST", "bench 结束 ${bf?.absolutePath ?: "FAILED"}")
                    }

                    spec == "exportgpu" || spec == "exportgpu_cut" -> {
                        val f = File(getExternalFilesDir(null), "auto_test.mp4")
                        if (!f.exists()) {
                            DebugLog.log("AUTOTEST", "无 auto_test.mp4（推送到 ${f.absolutePath}）")
                        } else {
                            val bytes = runCatching { f.readBytes() }.getOrNull()
                            if (bytes == null || bytes.size < 1024) {
                                DebugLog.log("AUTOTEST", "auto_test.mp4 读取失败")
                            } else {

                                val cutMs = if (spec == "exportgpu_cut") 5_000L else 0L
                                DebugLog.log("AUTOTEST", "exportgpu 源 ${bytes.size}B 开始 cut=${cutMs}ms")
                                val outF = Mp4Exporter.export(this, bytes, 0,
                                    onFrame = { frame ->
                                        val o = android.graphics.Bitmap.createBitmap(frame.width, frame.height,
                                            android.graphics.Bitmap.Config.ARGB_8888)
                                        val c2 = android.graphics.Canvas(o)
                                        WatermarkRenderer.render(c2, frame,
                                            android.graphics.RectF(0f, 0f, frame.width.toFloat(), frame.height.toFloat()),
                                            EditorState.renderParams(), resources.displayMetrics.density,
                                            WatermarkRenderer.unitForPhoto(frame.width.toFloat(), frame.height.toFloat(), resources.displayMetrics.density),
                                            null, 288, photoPreRotated = true)
                                        o
                                    },
                                    onProgress = { p -> if (p % 25 == 0) DebugLog.log("AUTOTEST", "exportgpu 进度 $p") },
                                    prefixFrame = null, prefixDurationMs = 0,
                                    srcUri = null,
                                    forceVideoRotation = -1,
                                    watermarkRenderer = preview,
                                    includeAudio = false,
                                    isCancelled = { false },
                                    maxDurationMs = cutMs
                                )
                                DebugLog.log("AUTOTEST",
                                    "exportgpu 结果: ${if (outF != null) "OK ${outF.length()}B fail=${Mp4Exporter.lastFailReason}" else "FAIL: ${Mp4Exporter.lastFailReason}"}")

                                if (outF != null) {
                                    runCatching {
                                        val keep = File(getExternalFilesDir(null), "exportgpu_out.mp4")
                                        outF.copyTo(keep, overwrite = true)
                                        DebugLog.log("AUTOTEST", "exportgpu 产物保留 ${keep.absolutePath} ${keep.length()}B")
                                    }
                                }
                                runCatching { outF?.delete() }
                            }
                        }
                    }

                    spec == "motioncut" -> {
                        val f = File(getExternalFilesDir(null), "auto_test.mp4")
                        if (!f.exists()) {
                            DebugLog.log("AUTOTEST", "无 auto_test.mp4（推送到 ${f.absolutePath}）")
                        } else {
                            mpSlot = 3
                            mpImage = null; mpImageSet = false
                            mpVideoUri = android.net.Uri.fromFile(f); mpVideoSet = true

                            val cover = runCatching {
                                val mmr = android.media.MediaMetadataRetriever()
                                try {
                                    mmr.setDataSource(this, mpVideoUri)
                                    mmr.getFrameAtTime(0)
                                } finally { runCatching { mmr.release() } }
                            }.getOrNull()
                            if (cover != null) {
                                val bos = java.io.ByteArrayOutputStream()
                                cover.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, bos)
                                mpImage = bos.toByteArray(); mpImageSet = true
                                EditorState.motionCoverBitmap?.let { if (!it.isRecycled) runCatching { it.recycle() } }
                                EditorState.motionCoverBitmap = cover
                                EditorState.previewBitmap?.let { if (!it.isRecycled) runCatching { it.recycle() } }
                                EditorState.previewBitmap = cover
                                EditorState.isDemo = false
                            }
                            DebugLog.log("AUTOTEST", "motioncut 注入 mpVideoUri=${mpVideoUri} cover=${cover != null} 开始 composeMotionPhoto")

                            runOnUiThread { composeMotionPhoto() }
                        }
                    }
                    else -> DebugLog.log("AUTOTEST", "未知指令: $spec")
                }
            } catch (t: Throwable) {
                DebugLog.log("AUTOTEST", "异常 ${t.javaClass.simpleName}: ${t.message}")
            }
        }.start()
    }

    private val heavy = Runnable {
        EditorState.persist(this)
    }
    fun bump() {
        rev++
        preview?.invalidate()
        ui.removeCallbacks(heavy)
        ui.postDelayed(heavy, 80)

        if (EditorState.liveSeconds) {
            ui.removeCallbacks(tick)
            ui.postDelayed(tick, 0)
        }
    }

    fun bumpLight() {
        rev++
        preview?.invalidate()
        ui.removeCallbacks(heavy)
        ui.postDelayed({ EditorState.persist(this) }, 200)
    }
    private fun refreshHard() {
        rev++
        preview?.invalidateBlur()
        EditorState.persist(this)
    }

    fun applyApiMode() {
        val mode = EditorState.params.apiMode

        EditorState.saveApiModeGlobal(mode)
        val sdk = android.os.Build.VERSION.SDK_INT

        val emulator = com.liquidglass.watermark.glass.isEmulator()
        val useSw = when (mode) {
            API_HIGH -> false
            API_LOW -> true
            else -> sdk < 31 || emulator
        }
        com.liquidglass.watermark.glass.backdrops.softwareBlurEnabled = useSw
        EditorState.params.softwareBlur = useSw
    }

    fun setAppLocale(lang: String) {
        prefs.edit().putString("lang", lang).apply()
        recreate()
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(heavy)
        ui.removeCallbacks(tick)
        EditorState.persist(this)
    }

    override fun onResume() {
        super.onResume()

        ui.removeCallbacks(tick)
        ui.postDelayed(tick, 1000)
    }

    override fun onDestroy() {
        super.onDestroy()

        if (EditorState.isExporting || exporting.get()) requestCancelExport()
        ui.removeCallbacks(heavy)
        ui.removeCallbacks(tick)

        val oldPv = EditorState.previewBitmap
        EditorState.previewBitmap = null
        if (oldPv != null && !oldPv.isRecycled) runCatching { oldPv.recycle() }
        EditorState.clearMotion()
        EditorState.isGif = false; EditorState.gifMovie = null; EditorState.gifBytes = null
        EditorState.webpBytes = null; EditorState.sourceUri = null
        EditorState.isDemo = true

        preview = null

        if (EditorState.isExporting || exporting.get()) {
            DebugLog.log("LIFECYCLE", "onDestroy 时导出仍在进行，跳过 clearBrandCache 以防回收竞态")
        } else {
            WatermarkRenderer.clearBrandCache()
        }
    }

    private val tick = object : Runnable {
        override fun run() {

            if (EditorState.liveSeconds) {
                EditorState.params.dateText = EditorState.nowString()
                rev++
                preview?.invalidate()
                ui.postDelayed(this, 1000)
            }
        }
    }

    fun applyPreset(i: Int) {
        val p = EditorState.params
        when (i) {
            0 -> {
                p.shape = EditorState.SHAPE_CAPSULE
                p.blur = 18; p.refractH = 22; p.refractA = 24; p.chromatic = 12; p.dispersion = 18
                p.depthEffect = true; p.vibrancy = 40
                p.highlightStyle = EditorState.HL_DEFAULT; p.specular = 55; p.hlAngle = 45f; p.falloff = 1.1f
                p.tintColor = Color.WHITE; p.tintStrength = 0
                p.innerShadow = 26; p.shadow = 54; p.edgeLight = 66; p.opacity = 62
                p.textColor = Color.WHITE; p.layoutMode = EditorState.LAYOUT_CLASSIC
                p.logoStyle = EditorState.LOGO_DOT
            }
            1 -> {
                p.shape = EditorState.SHAPE_CAPSULE
                p.blur = 20; p.refractH = 24; p.refractA = 22; p.chromatic = 14; p.dispersion = 22
                p.depthEffect = true; p.vibrancy = 55
                p.highlightStyle = EditorState.HL_DEFAULT; p.specular = 58; p.hlAngle = 45f; p.falloff = 1.05f
                p.tintColor = Color.WHITE; p.tintStrength = 0
                p.innerShadow = 22; p.shadow = 38; p.edgeLight = 62; p.opacity = 96
                p.textColor = Color.WHITE; p.layoutMode = EditorState.LAYOUT_CLASSIC
                p.logoStyle = EditorState.LOGO_DOT
            }
            2 -> {
                p.shape = EditorState.SHAPE_CAPSULE
                p.blur = 16; p.refractH = 26; p.refractA = 24; p.chromatic = 20; p.dispersion = 24
                p.depthEffect = true; p.vibrancy = 55
                p.highlightStyle = EditorState.HL_AMBIENT; p.specular = 52; p.hlAngle = -45f; p.falloff = 1f
                p.tintColor = Color.parseColor("#FFCFE4FF"); p.tintStrength = 0
                p.innerShadow = 24; p.shadow = 36; p.edgeLight = 52; p.opacity = 96
                p.textColor = Color.WHITE; p.layoutMode = EditorState.LAYOUT_STACK
                p.logoStyle = EditorState.LOGO_NONE
            }
            3 -> {
                p.shape = EditorState.SHAPE_CAPSULE
                p.blur = 20; p.refractH = 16; p.refractA = 14; p.chromatic = 0; p.dispersion = 0
                p.depthEffect = true; p.vibrancy = 50
                p.highlightStyle = EditorState.HL_PLAIN; p.specular = 60; p.hlAngle = 45f; p.falloff = 1f
                p.tintColor = Color.WHITE; p.tintStrength = 0
                p.innerShadow = 20; p.shadow = 34; p.edgeLight = 50; p.opacity = 96
                p.textColor = Color.WHITE; p.layoutMode = EditorState.LAYOUT_SINGLE
                p.logoStyle = EditorState.LOGO_CAMERA
            }
            4 -> {
                p.shape = EditorState.SHAPE_RECT
                p.blur = 12; p.refractH = 8; p.refractA = 8; p.chromatic = 0; p.dispersion = 0
                p.depthEffect = false; p.vibrancy = 20
                p.highlightStyle = EditorState.HL_DEFAULT; p.specular = 30; p.hlAngle = 60f; p.falloff = 1.3f
                p.tintColor = Color.BLACK; p.tintStrength = 0
                p.innerShadow = 50; p.shadow = 50; p.edgeLight = 35; p.opacity = 96
                p.textColor = Color.parseColor("#FFF6E8"); p.layoutMode = EditorState.LAYOUT_CLASSIC
                p.logoStyle = EditorState.LOGO_CAMERA
            }
            5 -> {
                p.shape = EditorState.SHAPE_CAPSULE
                p.blur = 8; p.refractH = 34; p.refractA = 32; p.chromatic = 18; p.dispersion = 32
                p.depthEffect = true; p.vibrancy = 35
                p.highlightStyle = EditorState.HL_DEFAULT; p.specular = 72; p.hlAngle = 45f; p.falloff = 0.9f
                p.tintColor = Color.WHITE; p.tintStrength = 0
                p.innerShadow = 14; p.shadow = 28; p.edgeLight = 78; p.opacity = 96
                p.textColor = Color.WHITE; p.layoutMode = EditorState.LAYOUT_CLASSIC
                p.logoStyle = EditorState.LOGO_NONE
            }
            6 -> {
                p.shape = EditorState.SHAPE_ELLIPSE
                p.cornerPct = 100
                p.blur = 10; p.refractH = 26; p.refractA = 28; p.chromatic = 14; p.dispersion = 16
                p.depthEffect = true; p.vibrancy = 45
                p.highlightStyle = EditorState.HL_DEFAULT; p.specular = 65; p.hlAngle = 45f; p.falloff = 1f
                p.tintColor = Color.WHITE; p.tintStrength = 0
                p.innerShadow = 20; p.shadow = 30; p.edgeLight = 60; p.opacity = 82
                p.textColor = Color.WHITE; p.layoutMode = EditorState.LAYOUT_CLASSIC
                p.logoStyle = EditorState.LOGO_CAMERA
                p.model = ""; p.modelCode = ""; p.dateText = ""
                p.logoAlign = 2
                p.relX = 0.85f; p.relY = 0.8f
                p.scalePct = 65; p.wmLengthPct = 0.01f
                p.borderWidth = 2.5f; p.borderAlpha = 220
            }
        }
        refreshHard()
    }

    fun rotatePhoto() {
        EditorState.photoRotation = (EditorState.photoRotation + 90) % 360
        refreshHard()
    }

    private val mediaPicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            onPicked(r)
        }

    private val galleryPicker =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri == null) { return@registerForActivityResult }
            handlePickedUri(uri)
        }

    private val galleryMultiPicker =
        registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(2)) { uris ->
            onPickedMulti(uris)
        }

    private val docMultiPicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            if (r.resultCode != RESULT_OK) return@registerForActivityResult
            val uris = ArrayList<Uri>()
            val clip = r.data?.clipData
            if (clip != null) for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
            r.data?.data?.let { if (uris.isEmpty()) uris.add(it) }
            onPickedMulti(uris)
        }

    fun pickPairMedia() {
        if (EditorState.isExporting) { toast(getString(R.string.export_busy)); return }
        val ok = runCatching {
            docMultiPicker.launch(
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
            )
        }.onFailure { android.util.Log.w("LGW", "pickPairMedia launch failed", it) }.isSuccess
        if (ok) return

        runCatching {
            galleryMultiPicker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
            )
        }.onFailure { pickMedia() }
    }

    fun pickMediaFromGallery() {
        if (EditorState.isExporting) { toast(getString(R.string.export_busy)); return }
        maybeTipVivo()

        val multiOk = runCatching {
            galleryMultiPicker.launch(
                PickVisualMediaRequest(
                    ActivityResultContracts.PickVisualMedia.ImageAndVideo
                )
            )
        }.isSuccess
        if (multiOk) return
        val launched = runCatching {
            galleryPicker.launch(
                PickVisualMediaRequest(
                    ActivityResultContracts.PickVisualMedia.ImageAndVideo
                )
            )
        }.isSuccess
        if (!launched) {

            pickMedia()
        }
    }

    private val previewPicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val uri = r.data?.data ?: return@registerForActivityResult
            val bmp = BitmapUtils.loadPreviewCustom(this, uri)
            if (bmp == null) {
                toast(getString(R.string.pick_failed))
                return@registerForActivityResult
            }
            EditorState.setPreviewCustom(bmp)
            preview?.invalidateBlur()
            toast(getString(R.string.preview_custom_set))
        }

    fun pickPreviewImage() {
        val open = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        val launched = runCatching {
            previewPicker.launch(Intent.createChooser(open, getString(R.string.preview_custom_title)))
        }.isSuccess
        if (!launched) {
            runCatching {
                previewPicker.launch(Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE)
                })
            }
        }
    }

    private val presetImportPicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val uri = r.data?.data ?: return@registerForActivityResult
            val text = runCatching {
                contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            }.getOrNull()
            val ok = text != null && PresetIO.import(this, text)
            toast(getString(if (ok) R.string.preset_import_ok else R.string.preset_import_fail))
            if (ok) { preview?.invalidateBlur(); bump() }
        }

    fun pickPreset() {
        val open = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        val launched = runCatching {
            presetImportPicker.launch(Intent.createChooser(open, getString(R.string.preset_import)))
        }.isSuccess
        if (!launched) {
            runCatching {
                presetImportPicker.launch(Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE)
                })
            }
        }
    }

    fun sharePreset() {
        val ok = runCatching {
            val f = PresetIO.shareFile(this)
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.fileprovider", f)
            val i = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(i, getString(R.string.preset_share)))
        }.isSuccess
        if (!ok) toast(getString(R.string.preset_share_fail))
    }

    fun pickMedia() {
        maybeTipVivo()
        val mimes = arrayOf(
            "image/*", "video/mp4", "video/quicktime", "video/3gpp",
            "video/webm", "video/x-matroska"
        )
        val open = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, mimes)
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        val launched = runCatching {
            mediaPicker.launch(Intent.createChooser(open, getString(R.string.open_photo)))
        }.isSuccess
        if (!launched) {
            val fb = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, mimes)
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            runCatching { mediaPicker.launch(fb) }
        }
    }

    fun pickVideo() {

        val mimes = arrayOf(
            "video/mp4", "video/quicktime", "video/3gpp",
            "video/webm", "video/x-matroska"
        )
        val open = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, mimes)
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        val launched = runCatching {
            mediaPicker.launch(Intent.createChooser(open, getString(R.string.pick_video)))
        }.isSuccess
        if (!launched) {
            val fb = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, mimes)
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            runCatching { mediaPicker.launch(fb) }
        }
    }

    fun pickMotionPhotoImage() {
        mpSlot = 1
        pickMedia()
    }

    fun pickMotionPhotoVideo() {
        mpSlot = 2
        pickVideo()
    }

    fun openGifPanel() {
        EditorState.motionExportFormat = "gif"
        EditorState.persist(this)
        EditorState.bumpExportBar()
        showMotionPhotoPanel = false
        showGifPanel = true
    }

    fun pickVideoToGif() {
        mpSlot = 0
        EditorState.motionExportFormat = "gif"
        pickVideo()
    }

    fun exportGifFromPanel() {
        showGifPanel = false
        doSave()
    }

    fun pickWholeMotionPhoto() {
        mpSlot = 3
        pickMedia()
    }

    fun clearMotionCover() {
        mpImage = null
        mpImageSet = false
        toast(getString(R.string.cover_cleared))
    }

    fun clearMotionVideo() {
        mpVideoUri = null
        mpVideoSet = false
        toast(getString(R.string.video_cleared))
    }

    fun deleteImportedVideo() {
        val hasMotion = EditorState.isGif || EditorState.motionMp4 != null ||
            (EditorState.motionFrames?.size ?: 0) >= 2 || mpVideoSet || mpImageSet
        if (!hasMotion) { toast(getString(R.string.no_video_to_delete)); return }

        if (EditorState.motionFrames?.contains(EditorState.previewBitmap) == true ||
            EditorState.previewBitmap === EditorState.motionCoverBitmap || EditorState.isGif) {
            EditorState.previewBitmap?.let { if (!it.isRecycled) runCatching { it.recycle() } }
            EditorState.previewBitmap = null
        }
        EditorState.clearMotion()
        EditorState.isGif = false; EditorState.gifBytes = null
        EditorState.webpBytes = null; EditorState.sourceUri = null
        EditorState.motionDecodeFailed = false
        mpImage = null; mpVideoUri = null
        mpImageSet = false; mpVideoSet = false
        preview?.invalidate()
        toast(getString(R.string.video_deleted))
    }

    fun deleteImportedImage() {
        val hasStill = EditorState.previewBitmap != null && !EditorState.isDemo &&
            !EditorState.isGif && EditorState.motionMp4 == null &&
            EditorState.motionUri == null && (EditorState.motionFrames?.size ?: 0) < 2
        if (!hasStill) { toast(getString(R.string.no_image_to_delete)); return }
        val old = EditorState.previewBitmap
        EditorState.previewBitmap = null
        if (old != null && !old.isRecycled) runCatching { old.recycle() }
        EditorState.isDemo = false
        EditorState.sourceUri = null
        preview?.invalidate()
        preview?.invalidateBlur()
        toast(getString(R.string.image_deleted))
    }

    fun composeMotionPhoto() {
        if (EditorState.isExporting) { toast(getString(R.string.export_busy)); return }

        if (!ensureStoragePermission { composeMotionPhoto() }) return
        if (!exporting.compareAndSet(false, true)) { toast(getString(R.string.export_busy)); return }
        try {

            val vDur = videoDurationMs(
                EditorState.motionUri ?: mpVideoUri.takeIf { EditorState.motionMp4 == null },
                EditorState.motionMp4)

            EditorState.sanitizeGifParams()
            val safeVDur = vDur.takeIf { it > 0L } ?: 0L
            val maxStart = (safeVDur - 1L).coerceAtLeast(0L)
            val mStart = if (EditorState.motionTrimCustomized)
                EditorState.motionTrimStartMs.coerceIn(0L, maxStart) else 0L
            val mDur = if (EditorState.motionTrimCustomized) {
                if (safeVDur > 0L)
                    EditorState.motionTrimDurationMs.coerceIn(100L, (safeVDur - mStart).coerceAtLeast(100L))
                else EditorState.motionTrimDurationMs
            } else if (safeVDur > 5_000L) 5_000L else 0L
            if (mDur > 0 && safeVDur > mDur) {
                toast(getString(R.string.motion_cut_5s, safeVDur / 1000))
                DebugLog.log("COMPOSE_MOTION",
                    "视频 ${safeVDur}ms → 动照片段 起点=${mStart}ms 长度=${mDur}ms")
            }
            composeMotionPhotoNow(mStart, mDur)
        } finally { exporting.set(false) }
    }

    private fun composeMotionPhotoNow(motionStartMs: Long = 0L, motionMaxMs: Long = 0L) {

        val mainMp4 = EditorState.motionMp4
        val mainCover = EditorState.motionCoverBitmap
            ?: EditorState.previewBitmap?.takeIf { !it.isRecycled && EditorState.isDemo.not() }
        val manualImg = mpImage
        val manualVid = mpVideoUri
        if (mainMp4 == null && manualImg == null && manualVid == null) {
            toast(getString(R.string.mp_missing)); return
        }
        progressPct = 0
        showMotionPhotoPanel = false
        Thread {
            try {
                publishProgress(2)

                var coverBmp: android.graphics.Bitmap
                var coverBytes: ByteArray? = null
                if (manualImg != null) {
                    coverBytes = manualImg
                    coverBmp = runCatching {
                        decodeCoverSafely(manualImg)
                    }.getOrNull() ?: error(getString(R.string.mp_fail))
                } else if (mainCover != null && !mainCover.isRecycled) {
                    coverBmp = mainCover
                } else {
                    error(getString(R.string.mp_missing))
                }
                if (coverBmp.width < 2 || coverBmp.height < 2) {
                    if (coverBytes != null) coverBmp.recycle()
                    error(getString(R.string.mp_fail))
                }

                val vidRotInfo = runCatching {
                    val mmr = android.media.MediaMetadataRetriever()
                    try {
                        val manualVidUri = manualVid
                        if (mainMp4 != null && mainMp4!!.size > 4096) {
                            val tf = File.createTempFile("lgw_rot", ".mp4", cacheDir)
                            try {
                                tf.writeBytes(mainMp4!!)
                                mmr.setDataSource(tf.absolutePath)
                                val rot = mmr.extractMetadata(
                                    android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toInt() ?: 0
                                val w = mmr.extractMetadata(
                                    android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: 0
                                val h = mmr.extractMetadata(
                                    android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: 0
                                Triple(rot, w, h)
                            } finally { runCatching { tf.delete() } }
                        } else if (manualVidUri != null) {

                            mmr.setDataSource(this, manualVidUri)
                            val rot = mmr.extractMetadata(
                                android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toInt() ?: 0
                            val w = mmr.extractMetadata(
                                android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: 0
                            val h = mmr.extractMetadata(
                                android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: 0
                            Triple(rot, w, h)
                        } else Triple(0, 0, 0)
                    } finally { runCatching { mmr.release() } }
                }.getOrDefault(Triple(0, 0, 0))
                val nr = MotionDecoder.normalizeVideoRotation(vidRotInfo.first, vidRotInfo.second, vidRotInfo.third)

                val coverPortrait = coverBmp.height > coverBmp.width

                val forceRot = if (videoDurationMs(manualVid, mainMp4) in 1..15_000L) {
                    MotionDecoder.inferForceRotation(
                        coverPortrait, vidRotInfo.first, vidRotInfo.second, vidRotInfo.third)
                } else -1
                EditorState.motionForceVideoRot = forceRot
                if (coverPortrait) {
                    DebugLog.log("COMPOSE_MOTION",
                        "封面竖屏 → 视频强制 rotation=$forceRot（容器=${vidRotInfo.second}x${vidRotInfo.third} metaRot=${vidRotInfo.first}）")
                } else {

                    val videoPortrait = ((nr % 180 != 0) == (vidRotInfo.second > vidRotInfo.third))
                    if (videoPortrait != coverPortrait) {
                        val m = android.graphics.Matrix().apply {
                            postRotate(if (videoPortrait) 90f else -90f)
                        }
                        val aligned = runCatching {
                            android.graphics.Bitmap.createBitmap(
                                coverBmp, 0, 0, coverBmp.width, coverBmp.height, m, true)
                        }.getOrNull()
                        if (aligned != null && aligned !== coverBmp) {
                            if (coverBytes != null) runCatching { if (!coverBmp.isRecycled) coverBmp.recycle() }
                            coverBmp = aligned
                        }
                        DebugLog.log("COMPOSE_MOTION", "封面方向与视频不一致，已自动对齐（视频旋转=${vidRotInfo.first}° 容器=${vidRotInfo.second}x${vidRotInfo.third} 封面${if (coverPortrait) "竖" else "横"}→${if (videoPortrait) "竖" else "横"}）")
                    }
                }

                val effRot = if (coverPortrait) (if (forceRot >= 0) forceRot else 0) else nr
                val vidDW = if (effRot % 180 != 0) vidRotInfo.third else vidRotInfo.second
                val vidDH = if (effRot % 180 != 0) vidRotInfo.second else vidRotInfo.third
                if (vidDW > 0 && vidDH > 0 && coverBmp.width > 0 && coverBmp.height > 0) {
                    val cr = BitmapUtils.centerCrop(coverBmp, vidDW, vidDH)
                    if (cr !== coverBmp) {
                        if (coverBytes != null) runCatching { if (!coverBmp.isRecycled) coverBmp.recycle() }
                        coverBmp = cr
                    }
                }

                val origLiveId = runCatching {
                    val manualVidUri = manualVid
                    val vidSize = if (manualVidUri != null) {
                        runCatching {
                            contentResolver.openAssetFileDescriptor(manualVidUri, "r")?.use { it.length } ?: -1L
                        }.getOrDefault(-1L)
                    } else -1L
                    val vidBytes = when {
                        mainMp4 != null -> mainMp4
                        manualVidUri != null && vidSize <= 128L * 1024 * 1024 ->
                            BitmapUtils.readBytes(this, manualVidUri)
                        else -> null
                    }
                    if (coverBytes != null && vidBytes != null) {
                        VivoLivePhoto.extractJpgId(coverBytes)?.takeIf { jid ->
                            VivoLivePhoto.extractMp4Id(vidBytes) == jid
                        }
                    } else null
                }.getOrNull()

                val wm = android.graphics.Bitmap.createBitmap(coverBmp.width, coverBmp.height,
                    android.graphics.Bitmap.Config.ARGB_8888)
                val c = android.graphics.Canvas(wm)
                val dst = android.graphics.RectF(0f, 0f, coverBmp.width.toFloat(), coverBmp.height.toFloat())
                val density = resources.displayMetrics.density
                val unit = WatermarkRenderer.unitForPhoto(coverBmp.width.toFloat(), coverBmp.height.toFloat(), density)
                WatermarkRenderer.render(c, coverBmp, dst, EditorState.renderParams(), density, unit, null, 1600)
                val jpeg = java.io.ByteArrayOutputStream()
                wm.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, jpeg)
                if (coverBytes != null) coverBmp.recycle()
                wm.recycle()
                publishProgress(30)

                val manualVidUri = manualVid
                val vidSize2 = if (manualVidUri != null) {
                    runCatching {
                        contentResolver.openAssetFileDescriptor(manualVidUri, "r")?.use { it.length } ?: -1L
                    }.getOrDefault(-1L)
                } else -1L
                val useUri = manualVidUri != null && vidSize2 > 128L * 1024 * 1024
                val mp4 = if (useUri) null
                    else mainMp4
                        ?: runCatching { BitmapUtils.readBytes(this, manualVid!!) }.getOrNull()
                if (mp4 == null && manualVidUri == null && mainMp4 == null)
                    error(getString(R.string.mp_fail))
                var forceStaticFallback = false
                val wmFile = Mp4Exporter.export(
                    this, mp4 ?: ByteArray(0), 0,
                    onFrame = { frame ->
                        val out = android.graphics.Bitmap.createBitmap(frame.width, frame.height,
                            android.graphics.Bitmap.Config.ARGB_8888)
                        val fc = android.graphics.Canvas(out)
                        val fd = android.graphics.RectF(0f, 0f, frame.width.toFloat(), frame.height.toFloat())
                        val fUnit = WatermarkRenderer.unitForPhoto(frame.width.toFloat(), frame.height.toFloat(), density)

                        // Match the shared preview/export glass-effect sampling policy on every tier.
                        val videoEdge = preview?.motionExportMaxEdge ?: 480
                        WatermarkRenderer.render(fc, frame, fd, EditorState.renderParams(), density, fUnit, null, videoEdge,
                            photoPreRotated = true)
                        out
                    },
                    onProgress = { p -> publishProgress(30 + (p * 55) / 100) },
                    prefixFrame = null, prefixDurationMs = 0,
                    srcUri = if (useUri) manualVidUri else null,
                    forceVideoRotation = EditorState.motionForceVideoRot,

                    watermarkRenderer = if (EditorState.params.exportMode == 2) null else preview,

                    includeAudio = false,

                    maxDurationMs = motionMaxMs,
                    startPtsUs = motionStartMs * 1000L
                ) ?: run {
                    // If watermark transcoding is unavailable, retain the source clip with the
                    // watermarked still cover instead of returning no deliverable at all.
                    forceStaticFallback = true
                    val sourceCopy = File.createTempFile("lgw_source_fallback_", ".mp4", cacheDir)
                    try {
                        if (mp4 != null && mp4.isNotEmpty()) {
                            sourceCopy.writeBytes(mp4)
                        } else {
                            val uri = manualVidUri ?: error("原始视频不可用")
                            contentResolver.openInputStream(uri)?.use { input ->
                                sourceCopy.outputStream().use { output -> input.copyTo(output) }
                            } ?: error("无法读取原始视频")
                        }
                        if (sourceCopy.length() <= 0L) error("原始视频为空")
                        DebugLog.log("COMPOSE_MOTION", "视频水印转码失败，回退保存普通封面+原视频: ${Mp4Exporter.lastFailReason}")
                        sourceCopy
                    } catch (t: Throwable) {
                        runCatching { sourceCopy.delete() }
                        throw IllegalStateException("视频转码失败且无法保留原视频：${t.message}", t)
                    }
                }
                publishProgress(88)

                var savedName: String? = null
                var usedStaticFallback = forceStaticFallback
                try {
                    val jpegBytes = jpeg.toByteArray()
                    savedName = if (forceStaticFallback) {
                        MotionPhotoWriter.saveStaticAndVideoToGallery(this, jpegBytes, wmFile)
                    } else try {
                        if (EditorState.motionPhotoFormat == 1) {
                            MotionPhotoWriter.composeGoogleToGallery(this, jpegBytes, wmFile)
                        } else {
                            MotionPhotoWriter.composeToGallery(this, jpegBytes, wmFile, livePhotoId = origLiveId)
                        }
                    } catch (motionPhotoError: Throwable) {
                        DebugLog.log("WRITER", "动态照片封装失败，保存普通封面+视频：${motionPhotoError.message}")
                        usedStaticFallback = true
                        MotionPhotoWriter.saveStaticAndVideoToGallery(this, jpegBytes, wmFile)
                    }
                } finally {

                    runCatching { wmFile.delete() }
                }
                publishProgress(100)
                ui.postDelayed({
                    progressPct = -1
                    clearExportCacheLight()

                    mpImage = null
                    mpVideoUri = null
                    mpImageSet = false
                    mpVideoSet = false
                    val savedDescription = if (usedStaticFallback)
                        "静态封面 + 独立视频：$savedName" else savedName
                    toast(getString(R.string.mp_saved, savedDescription))
                }, 250)
            } catch (e: Throwable) {
                DebugLog.logThrowable("COMPOSE_MOTION", e)
                ui.post { progressPct = -1; toast((e.message ?: getString(R.string.mp_fail))) }
            }
        }.start()
    }

    private fun decodeCoverSafely(bytes: ByteArray): android.graphics.Bitmap? =
        BitmapUtils.decodeCover(bytes)

    fun pickCustomLogo() {
        pendingLogoPick = true
        val open = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        val launched = runCatching {
            mediaPicker.launch(Intent.createChooser(open, getString(R.string.pick_custom_logo)))
        }.isSuccess
        if (!launched) {
            val fb = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            runCatching { mediaPicker.launch(fb) }
        }
    }

    private fun handleCustomLogo(uri: android.net.Uri) {
        Thread {
            android.util.Log.i("LGW-AUTO", "handleCustomLogo 线程开始 uri=$uri")
            DebugLog.log("LOGO", "STEP0 线程开始 uri=$uri")

            var savedDst: String? = null
            val err: String? = runCatching {

                val dst = File(filesDir, "custom_logo_${System.currentTimeMillis()}.png")

                val srcFile = File(filesDir, "custom_logo_src.${System.currentTimeMillis()}")
                DebugLog.log("LOGO", "STEP1 复制开始")
                contentResolver.openInputStream(uri)?.use { ins ->
                    srcFile.outputStream().use { outs -> ins.copyTo(outs) }
                } ?: return@runCatching "读取相册文件失败"
                DebugLog.log("LOGO", "STEP2 复制完成 size=${srcFile.length()}")

                val opts = android.graphics.BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                android.graphics.BitmapFactory.decodeFile(srcFile.absolutePath, opts)
                if (opts.outWidth <= 0 || opts.outHeight <= 0) {
                    runCatching { srcFile.delete() }
                    return@runCatching "图片格式无法解码"
                }
                DebugLog.log("LOGO", "STEP3 尺寸 ${opts.outWidth}x${opts.outHeight} sample=${run { var s = 1; while (opts.outWidth / s > 512 || opts.outHeight / s > 512) s *= 2; s }}")
                var sample = 1
                while (opts.outWidth / sample > 512 || opts.outHeight / sample > 512) sample *= 2
                opts.inJustDecodeBounds = false
                opts.inSampleSize = sample
                val bmp = android.graphics.BitmapFactory.decodeFile(srcFile.absolutePath, opts)
                    ?: run { runCatching { srcFile.delete() }; return@runCatching "图片解码失败" }
                DebugLog.log("LOGO", "STEP4 解码成功 ${bmp.width}x${bmp.height}")

                val deg = runCatching {
                    val ei = android.media.ExifInterface(srcFile.absolutePath)
                    when (ei.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION,
                        android.media.ExifInterface.ORIENTATION_NORMAL)) {
                        android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                        android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                        android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                        else -> 0f
                    }
                }.getOrDefault(0f)

                var cur: android.graphics.Bitmap = bmp
                if (deg != 0f) {
                    val m = android.graphics.Matrix().apply { postRotate(deg) }
                    val rotated = runCatching {
                        android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                    }.getOrNull()
                    if (rotated != null && rotated !== bmp) {
                        runCatching { bmp.recycle() }
                        cur = rotated
                    }
                }
                val maxDim = maxOf(cur.width, cur.height)
                if (maxDim > 512) {
                    val scale = 512f / maxDim
                    val sb = runCatching {
                        android.graphics.Bitmap.createScaledBitmap(cur,
                            (cur.width * scale).toInt().coerceAtLeast(1),
                            (cur.height * scale).toInt().coerceAtLeast(1), true)
                    }.getOrNull()
                    if (sb != null) { runCatching { cur.recycle() }; cur = sb }
                }

                val tmpPng = File(filesDir, "custom_logo.tmp.png")
                DebugLog.log("LOGO", "STEP5 PNG 编码开始")
                tmpPng.outputStream().use { outs -> cur.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, outs) }
                DebugLog.log("LOGO", "STEP6 PNG 编码完成 size=${tmpPng.length()}")
                runCatching { if (!cur.isRecycled) cur.recycle() }
                if (!tmpPng.renameTo(dst)) {

                    runCatching { tmpPng.copyTo(dst, overwrite = true) }
                    runCatching { tmpPng.delete() }
                }
                runCatching { srcFile.delete() }

                val v = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(dst.absolutePath, v)
                if (v.outWidth <= 0 || v.outHeight <= 0) return@runCatching "PNG 归一化失败"
                DebugLog.log("LOGO", "STEP7 完成 dst=${dst.absolutePath} ${v.outWidth}x${v.outHeight}")
                EditorState.params.logoBrand = EditorState.BRAND_CUSTOM
                EditorState.params.customLogoPath = dst.absolutePath
                EditorState.params.brandScale = EditorState.brandScales[EditorState.BRAND_CUSTOM] ?: 1f
                savedDst = dst.absolutePath
                null
            }.getOrElse { "导入失败：${it.message ?: ""}" }
            runOnUiThread {
                if (err == null) {

                    savedDst?.let { EditorState.params.customLogoPath = it }
                    WatermarkRenderer.clearBrandCache()
                    refreshHard()

                    val check = WatermarkRenderer.debugSelfTestLogo()

                    DebugLog.log("LOGO", "导入成功（自检: $check）path=${EditorState.params.customLogoPath}")
                    toast(getString(R.string.custom_logo_set) + "（自检: $check）")

                    EditorState.params.customLogos.add(EditorState.CustomLogoEntry(
                        "自定义${EditorState.params.customLogos.size + 1}",
                        EditorState.params.customLogoPath,
                        EditorState.params.logoShape,
                        EditorState.params.logoCrop))
                    EditorState.persist(this)

                    DebugLog.log("LOGO", "ISOLATE count=${EditorState.params.customLogos.size} " +
                        "paths=${EditorState.params.customLogos.joinToString("|") { it.path }}")
                    showLogoNameDialog()
                } else {
                    DebugLog.log("LOGO", "导入失败：$err")
                    toast(getString(R.string.custom_logo_fail) + "（$err）")
                }
            }
        }.start()
    }

    private fun showLogoNameDialog() {

        val shapeOpts = arrayOf(
            getString(R.string.logo_shape_square),
            getString(R.string.logo_shape_rect),
            getString(R.string.logo_shape_circle),
            getString(R.string.logo_shape_none))
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.logo_shape_title))
            .setItems(shapeOpts) { _, which ->
                EditorState.params.logoShape = when (which) {
                    0 -> 1; 1 -> 2; 2 -> 3; else -> 0
                }
                EditorState.params.logoCrop = which != 3

                val cl = EditorState.params.customLogos
                if (cl.isNotEmpty()) {
                    val last = cl[cl.size - 1]
                    last.shape = EditorState.params.logoShape
                    last.crop = EditorState.params.logoCrop
                }
                EditorState.persist(this)
                WatermarkRenderer.clearBrandCache()
                refreshHard()
                showLogoNameInput()
            }
            .setNegativeButton(getString(R.string.cancel_btn), null)
            .show().applyLiquidGlassStyle()
    }

    private fun showLogoNameInput() {
        val et = android.widget.EditText(this)
        et.setText(EditorState.params.customLogoName.ifBlank { getString(R.string.custom_logo_default_name) })
        et.setSelectAllOnFocus(true)
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.logo_name_title))
            .setMessage(getString(R.string.logo_name_hint))
            .setView(et)
            .setPositiveButton(getString(R.string.ok_btn)) { _, _ ->
                EditorState.params.customLogoName = et.text.toString().trim()

                val cl = EditorState.params.customLogos
                if (cl.isNotEmpty()) cl[cl.size - 1].name = EditorState.params.customLogoName
                EditorState.persist(this)
                WatermarkRenderer.clearBrandCache()
                refreshHard()
                toast(getString(R.string.custom_logo_saved))
            }
            .setNegativeButton(getString(R.string.cancel_btn), null)
            .show().applyLiquidGlassStyle()
    }

    fun deleteCustomLogo() {
        if (EditorState.params.customLogoPath.isEmpty()) return
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.logo_delete_title))
            .setMessage(getString(R.string.logo_delete_confirm) +
                (EditorState.params.customLogoName.takeIf { it.isNotBlank() }?.let { "（$it）" } ?: ""))
            .setPositiveButton(getString(R.string.delete_btn)) { _, _ ->
                runCatching { File(EditorState.params.customLogoPath).delete() }

                val activePath = EditorState.params.customLogoPath
                EditorState.params.customLogos.removeAll { it.path == activePath }
                EditorState.params.customLogoPath = ""
                EditorState.params.customLogoName = ""
                EditorState.params.logoBrand = EditorState.BRAND_NONE

                EditorState.params.logoShape = 0
                EditorState.params.logoCrop = true
                EditorState.brandScales[EditorState.BRAND_CUSTOM] = 1f
                EditorState.persist(this)
                WatermarkRenderer.clearBrandCache()
                refreshHard()
                toast(getString(R.string.logo_deleted))
            }
            .setNegativeButton(getString(R.string.cancel_btn), null)
            .show().applyLiquidGlassStyle()
    }

    fun deleteCustomLogoEntry(entry: EditorState.CustomLogoEntry) {
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.logo_delete_title))
            .setMessage(getString(R.string.logo_delete_confirm) + "（${entry.name}）")
            .setPositiveButton(getString(R.string.delete_btn)) { _, _ ->
                runCatching { File(entry.path).delete() }
                EditorState.params.customLogos.removeAll { it.path == entry.path }

                if (EditorState.params.customLogoPath == entry.path) {
                    EditorState.params.customLogoPath = ""
                    EditorState.params.customLogoName = ""
                    EditorState.params.logoBrand = EditorState.BRAND_NONE

                    EditorState.params.logoShape = 0
                    EditorState.params.logoCrop = true
                    EditorState.brandScales[EditorState.BRAND_CUSTOM] = 1f
                }
                EditorState.persist(this)
                WatermarkRenderer.clearBrandCache()
                refreshHard()
                toast(getString(R.string.logo_deleted))
            }
            .setNegativeButton(getString(R.string.cancel_btn), null)
            .show().applyLiquidGlassStyle()
    }
    fun showCustomLogosDialog() {
        val cl = EditorState.params.customLogos
        val items = ArrayList<String>()
        cl.forEachIndexed { i, e -> items.add("${if (EditorState.params.customLogoPath == e.path) "● " else ""}${e.name}") }
        items.add(getString(R.string.custom_logo_import_new))
        items.add(getString(R.string.custom_logo_delete_active))
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.logo_name_title))
            .setItems(items.toTypedArray()) { _, which ->
                when {
                    which < cl.size -> switchCustomLogo(cl[which])
                    which == cl.size -> pickCustomLogo()
                    else -> deleteCustomLogo()
                }
            }
            .setNegativeButton(getString(R.string.cancel_btn), null)
            .show().applyLiquidGlassStyle()
    }

    fun switchCustomLogo(entry: EditorState.CustomLogoEntry) {
        EditorState.params.customLogoPath = entry.path
        EditorState.params.customLogoName = entry.name
        EditorState.params.logoShape = entry.shape
        EditorState.params.logoCrop = entry.crop
        EditorState.params.logoBrand = EditorState.BRAND_CUSTOM
        EditorState.params.brandScale = EditorState.brandScales[EditorState.BRAND_CUSTOM] ?: 1f
        EditorState.persist(this)
        WatermarkRenderer.clearBrandCache()
        refreshHard()
        DebugLog.log("LOGO", "切换到自定义logo ${entry.name} path=${entry.path}")
    }

    private fun importWholeMotionPhoto(uri: android.net.Uri) {
        val name = runCatching {
            contentResolver.query(uri,
                arrayOf(android.provider.MediaStore.MediaColumns.DISPLAY_NAME,
                    android.provider.MediaStore.MediaColumns.MIME_TYPE),
                null, null, null)
        }.getOrNull()?.use { c -> if (c.moveToFirst()) c.getString(0) to c.getString(1) else (null to null) }
        val mime = name?.second ?: contentResolver.getType(uri)
        if (mime?.startsWith("video/") == true) {

            mpVideoUri = uri; mpVideoSet = true
            Thread {
                val cover = runCatching {
                    val mmr = android.media.MediaMetadataRetriever()
                    try {
                        mmr.setDataSource(this, uri)
                        mmr.getFrameAtTime(0)
                    } finally { runCatching { mmr.release() } }
                }.getOrNull()
                runOnUiThread {
                    if (cover != null) {

                        val bos = java.io.ByteArrayOutputStream()
                        cover.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, bos)
                        mpImage = bos.toByteArray(); mpImageSet = true
                        EditorState.motionCoverBitmap?.let { if (!it.isRecycled) runCatching { it.recycle() } }
                        EditorState.motionCoverBitmap = cover
                        EditorState.previewBitmap?.let { if (!it.isRecycled) runCatching { it.recycle() } }
                        EditorState.previewBitmap = cover
                        EditorState.isDemo = false
                        refreshHard()
                        toast(getString(R.string.mp_import_ok_both))
                        return@runOnUiThread
                    }
                    toast(getString(R.string.mp_video_ok))
                }
            }.start()
        } else {

            Thread {
                val b = runCatching { BitmapUtils.readBytes(this, uri) }.getOrNull()
                runOnUiThread {
                    if (b == null) { toast(getString(R.string.pick_failed)); return@runOnUiThread }
                    mpImage = b; mpImageSet = true
                    val src = runCatching { BitmapUtils.decodeCover(b) }.getOrNull()
                    if (src != null) {
                        EditorState.motionCoverBitmap?.let { if (!it.isRecycled) runCatching { it.recycle() } }
                        EditorState.motionCoverBitmap = src
                        EditorState.previewBitmap?.let { if (!it.isRecycled) runCatching { it.recycle() } }
                        EditorState.previewBitmap = src
                        EditorState.isDemo = false
                        refreshHard()
                    }
                    val base = name?.first?.substringBeforeLast('.') ?: ""
                    val pair = if (base.isBlank()) null else runCatching {
                        contentResolver.query(
                            android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                            arrayOf(android.provider.MediaStore.Video.Media.DATA),
                            android.provider.MediaStore.Video.Media.DISPLAY_NAME + " LIKE ?",
                            arrayOf("$base.mp4"), null)
                    }.getOrNull()?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                    if (pair != null) {
                        mpVideoUri = android.net.Uri.fromFile(File(pair)); mpVideoSet = true
                        toast(getString(R.string.mp_import_ok_both))
                    } else {
                        toast(getString(R.string.mp_image_ok))
                    }
                }
            }.start()
        }
    }

    private fun maybeTipVivo() {
        if (vivoTipShown) return
        val mk = Build.MANUFACTURER.lowercase(Locale.US)
        if (mk.contains("vivo") || mk.contains("iqoo")) {
            vivoTipShown = true
            toast(getString(R.string.vivo_hint))
        }
    }

    private fun isCnmManufacturer(): Boolean {
        val mk = Build.MANUFACTURER.lowercase(Locale.US)
        return mk.contains("vivo") || mk.contains("iqoo") || mk.contains("oppo") ||
            mk.contains("oneplus") || mk.contains("xiaomi") || mk.contains("redmi") ||
            mk.contains("huawei") || mk.contains("honor") || mk.contains("realme") ||
            mk.contains("meizu")
    }

    /** URI-backed import keeps long videos out of the Java heap. Preview loops the first five seconds. */
    private fun importVideoUri(uri: Uri) {
        if (EditorState.isExporting) return
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val isGifExport = EditorState.motionExportFormat == "gif"
        val selectedCover = EditorState.motionCoverBitmap
            ?: EditorState.previewBitmap?.takeIf { pendingImageUri != null && !EditorState.isDemo && !it.isRecycled }

        val previewMaxMs = if (isGifExport) {

            if (EditorState.gifTrimCustomized)
                (EditorState.gifTrimStartMs + EditorState.gifTrimDurationMs).coerceAtLeast(1L)
            else VideoGifTimeline.MAX_DURATION_MS.toLong()
        } else 0L
        Thread {
            val duration = videoDurationMs(uri, null)

            val localFile = java.io.File(cacheDir, "lgw_media_${System.currentTimeMillis()}.mp4")
            val localOk = if (uri.scheme.equals("file", true)) {

                EditorState.motionLocalPath = uri.path
                true
            } else {
                val copied = MotionDecoder.copyUriToFile(this, uri, localFile)
                if (copied && localFile.length() > 0) {
                    EditorState.motionLocalPath = localFile.absolutePath
                    true
                } else {
                    runCatching { localFile.delete() }

                    DebugLog.log("IMPORT", "视频复制失败：${MotionDecoder.lastFailReason}")
                    false
                }
            }
            if (!localOk) {
                runOnUiThread { toast(getString(R.string.video_decode_fail) + "（${MotionDecoder.lastFailReason}）") }
                return@Thread
            }
            val localUri = android.net.Uri.fromFile(
                java.io.File(EditorState.motionLocalPath!!))
            var decoded = runCatching {
                MotionDecoder.decode(this, ByteArray(0), 720, 12, 60, 30_000,
                    minFrames = 1, srcUri = localUri, maxDurationMs = previewMaxMs)
            }.getOrNull()
            if (decoded == null || decoded.frames.isEmpty()) {
                runOnUiThread { toast(getString(R.string.video_decode_fail) + diag()) }
                return@Thread
            }
            var forcedRotation = -1
            if (selectedCover != null && !selectedCover.isRecycled && duration in 1..15_000L &&
                decoded.rotation == 0 && decoded.frames[0].width > decoded.frames[0].height &&
                selectedCover.height > selectedCover.width) {
                val corrected = runCatching {
                    MotionDecoder.decode(this, ByteArray(0), 720, 12, 60, 30_000,
                        minFrames = 1, srcUri = localUri, rotationOverride = 90,
                        maxDurationMs = previewMaxMs)
                }.getOrNull()
                if (corrected != null && corrected.frames.isNotEmpty()) {
                    decoded.frames.forEach { runCatching { it.recycle() } }
                    decoded = corrected
                    forcedRotation = 90
                }
            }
            val frames = decoded
            runOnUiThread {
                // Copy the cover before clearMotion recycles the preceding video's owned buffers.
                val coverCopy = selectedCover?.takeIf { !it.isRecycled }
                    ?.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                val oldStill = EditorState.previewBitmap
                EditorState.clearMotion()
                EditorState.isGif = false; EditorState.gifMovie = null; EditorState.gifBytes = null
                EditorState.photoRotation = 0
                EditorState.motionDecodeFailed = false
                EditorState.motionUri = uri
                EditorState.motionForceVideoRot = forcedRotation
                EditorState.motionFrames = frames.frames.toMutableList()
                EditorState.motionDelays = frames.delaysMs
                EditorState.motionCoverBitmap = coverCopy
                // Video preview always starts with video frame 0; cover belongs to the still photo only.
                EditorState.previewBitmap = frames.frames[0]
                if (oldStill != null && !oldStill.isRecycled)
                    runCatching { oldStill.recycle() }
                EditorState.sourceUri = uri; EditorState.isDemo = false
                mpVideoUri = uri; mpVideoSet = true
                refreshHard()
                toast(if (isGifExport && duration > previewMaxMs && !EditorState.gifTrimCustomized)
                    "视频超过五秒，GIF 默认截取前五秒（可在 GIF 面板调整）" else getString(R.string.video_loaded))
            }
        }.start()
    }

    private fun onPicked(r: ActivityResult) {
        if (EditorState.isExporting) { toast(getString(R.string.export_busy)); return }
        if (r.resultCode != RESULT_OK) return
        val uri = r.data?.data
        if (uri == null) {

            toast(getString(R.string.pick_failed))
            return
        }
        handlePickedUri(uri)
    }

    private fun onPickedMulti(uris: List<Uri>) {
        if (EditorState.isExporting) { toast(getString(R.string.export_busy)); return }
        val list = uris.distinct()
        if (list.isEmpty()) return
        if (list.size == 1) { handlePickedUri(list[0]); return }
        val pairs = list.map { u ->
            u to (runCatching { contentResolver.getType(u) }.getOrNull()?.startsWith("video/") == true)
        }
        val imgUri = pairs.firstOrNull { !it.second }?.first
        val vidUri = pairs.firstOrNull { it.second }?.first
        if (imgUri != null && vidUri != null) {
            pairMotionMedia(imgUri, vidUri)
        } else {
            toast("动态照片需要「1 张照片 + 1 段视频」，已先导入第一个素材")
            handlePickedUri(list[0])
        }
    }

    private fun pairMotionMedia(imageUri: Uri, videoUri: Uri) {
        Thread {
            val bytes = runCatching { BitmapUtils.readBytes(this, imageUri) }.getOrNull()
            val cover = bytes?.let { runCatching { BitmapUtils.decodeCover(it) }.getOrNull() }
            runOnUiThread {
                if (bytes == null || cover == null) {
                    toast("封面照片读取失败，请重试")
                    return@runOnUiThread
                }
                mpImage = bytes
                mpImageSet = true
                EditorState.motionCoverBitmap?.let { if (!it.isRecycled) runCatching { it.recycle() } }
                EditorState.motionCoverBitmap = cover
                EditorState.previewBitmap?.let { if (!it.isRecycled) runCatching { it.recycle() } }
                EditorState.previewBitmap = cover
                EditorState.isDemo = false
                DebugLog.log("PAIR", "已配对封面 ${cover.width}x${cover.height}，开始导入视频")
                refreshHard()

                importVideoUri(videoUri)
                showMotionPhotoPanel = true
                toast("已配对为动态照片（封面 + 视频），点「合成」即可导出")
            }
        }.start()
    }

    private fun handlePickedUri(uri: Uri) {

        if (pendingLogoPick) {
            pendingLogoPick = false
            handleCustomLogo(uri)
            return
        }

        if (mpSlot != 0) {
            val slot = mpSlot
            mpSlot = 0
            if (slot == 3) {

                importWholeMotionPhoto(uri)
            } else if (slot == 1) {

                Thread {
                    val b = runCatching { BitmapUtils.readBytes(this, uri) }.getOrNull()
                    runOnUiThread {
                        if (b == null) { toast(getString(R.string.pick_failed)); return@runOnUiThread }
                        mpImage = b
                        mpImageSet = true

                        val src = runCatching { BitmapUtils.decodeCover(b) }.getOrNull()
                        if (src != null) {
                            EditorState.motionCoverBitmap?.let { if (!it.isRecycled) runCatching { it.recycle() } }
                            EditorState.motionCoverBitmap = src
                            EditorState.previewBitmap?.let { if (!it.isRecycled) runCatching { it.recycle() } }
                            EditorState.previewBitmap = src
                            EditorState.isDemo = false
                            refreshHard()
                        }
                        toast(getString(R.string.mp_image_ok))
                    }
                }.start()
            } else {
                importVideoUri(uri)
            }
            return
        }
        val mime = runCatching { contentResolver.getType(uri) }.getOrNull()
        val signature = runCatching {
            contentResolver.openInputStream(uri)?.use { stream ->
                val head = ByteArray(12)
                val count = stream.read(head)
                String(head, 0, count.coerceAtLeast(0), Charsets.ISO_8859_1)
            }
        }.getOrNull().orEmpty()
        val animatedImage = signature.startsWith("GIF8") ||
            (signature.startsWith("RIFF") && signature.endsWith("WEBP"))
        if (mime?.startsWith("video/") == true && !animatedImage) { importVideoUri(uri); return }
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        Thread {

            EditorState.photoRotation = 0
            EditorState.motionDecodeFailed = false
            val bytesR = runCatching { BitmapUtils.readBytes(this, uri) }
            if (bytesR.isFailure) {
                runOnUiThread { toast(bytesR.exceptionOrNull()?.message ?: "图片读取失败，请换一张试试") }
                return@Thread
            }
            val bytes = bytesR.getOrThrow()

            if (BitmapUtils.looksLikeGif(this, uri, bytes)) {
                val gifR = runCatching { BitmapUtils.decodeGif(bytes) }
                gifR.onSuccess { gi ->
                    runOnUiThread {
                        pendingImageUri = null
                        EditorState.clearMotion()
                        EditorState.gifMovie = gi.movie; EditorState.isGif = true
                        EditorState.gifBytes = bytes
                        EditorState.gifDuration = gi.duration

                        EditorState.previewBitmap?.let { if (!it.isRecycled) runCatching { it.recycle() } }
                        EditorState.previewBitmap = null
                        EditorState.sourceUri = uri; EditorState.isDemo = false
                        if (EditorState.params.dateText.isEmpty())
                            EditorState.params.dateText = EditorState.nowString()
                        refreshHard()
                        toast(getString(R.string.gif_loaded))
                    }
                }.onFailure { e -> runOnUiThread { toast(e.message ?: "动图读取失败，请换一张试试") } }
                return@Thread
            }

            if (WebPDecoder.looksLikeAnimatedWebP(bytes) || mime?.contains("webp", ignoreCase = true) == true) {
                val frames = ArrayList<android.graphics.Bitmap>()
                val delays = ArrayList<Int>()
                WebPDecoder.decodeAsync(
                    Handler(Looper.getMainLooper()), this, bytes, 480, 90, 12_000,
                    onFrame = { bmp, d -> frames.add(bmp); delays.add(d) },
                    onDone = { ok ->
                        if (ok && frames.size >= 2) {
                            val oldPv = EditorState.previewBitmap
                            EditorState.isGif = false; EditorState.gifMovie = null; EditorState.gifBytes = null
                            EditorState.clearMotion()
                            EditorState.webpBytes = bytes
                            EditorState.motionFrames = frames
                            EditorState.motionDelays = IntArray(delays.size) { delays[it] }
                            EditorState.previewBitmap = frames[0]
                            if (oldPv != null && oldPv !== frames[0] && !oldPv.isRecycled)
                                runCatching { oldPv.recycle() }
                            EditorState.sourceUri = uri; EditorState.isDemo = false
                            if (EditorState.params.dateText.isEmpty())
                                EditorState.params.dateText = EditorState.nowString()
                            refreshHard()
                            toast(getString(R.string.webp_loaded))
                        } else {
                            frames.forEach { runCatching { if (!it.isRecycled) it.recycle() } }

                            fallbackStaticFromWebP(bytes, uri)
                        }
                    })
                return@Thread
            }

            val embeddedMp4 = if (mime?.startsWith("video/") == true || MotionPhoto.looksLikeMp4(bytes)) null
            else runCatching { MotionPhoto.extractMp4(bytes) }.getOrNull()?.takeIf { it.size > 4096 }
            if (mime?.startsWith("video/") == true || MotionPhoto.looksLikeMp4(bytes) || embeddedMp4 != null) {
                val srcBytes = embeddedMp4 ?: bytes
                EditorState.motionMp4 = srcBytes
                val vr = runCatching { MotionDecoder.decode(this, srcBytes, 720, 12, 60,
                    maxDurationMs = 5000L) }
                vr.onSuccess { fr ->
                    if (fr == null || fr.frames.size < 2) {
                        DebugLog.log("DECODE",
                            "选视频解码失败 size=${srcBytes.size} mime=$mime reason=${MotionDecoder.lastFailReason}")
                        runOnUiThread { toast(getString(R.string.video_decode_fail) + diag()) }
                        return@Thread
                    }

                    var cover: android.graphics.Bitmap? = if (embeddedMp4 != null)
                        runCatching { BitmapUtils.decodeCover(bytes) }.getOrNull()
                        else if (pendingImageUri != null && EditorState.previewBitmap?.isRecycled == false && !EditorState.isDemo)
                            EditorState.previewBitmap else null
                    if (cover == null) {
                        runCatching {
                            val img = BitmapUtils.findCompanionImage(this, uri)
                            if (img != null) {
                                val b = BitmapUtils.readBytes(this, img) ?: return@runCatching
                                val info = BitmapUtils.decodePreviewBytes(b) ?: return@runCatching
                                pendingImageUri = img
                                cover = info.bitmap
                            }
                        }
                    }
                    val finalCover = cover

                    val effFr: MotionDecoder.Frames = if (
                        fr.rotation == 0 && fr.frames.isNotEmpty() &&
                        fr.frames[0].width > fr.frames[0].height &&
                        finalCover != null && finalCover.height > finalCover.width &&
                        videoDurationMs(null, srcBytes) in 1..15_000L
                    ) {
                        EditorState.motionForceVideoRot = 90
                        val re = runCatching {
                            MotionDecoder.decode(this, srcBytes, 720, 12, 60, rotationOverride = 90,
                                maxDurationMs = 5000L)
                        }.getOrNull()
                        if (re != null && re.frames.size >= 2 && re.frames[0].height > re.frames[0].width) {

                            fr.frames.forEach { runCatching { if (!it.isRecycled) it.recycle() } }
                            re
                        } else {

                            val rotFrames = ArrayList<android.graphics.Bitmap>(fr.frames.size)
                            for (b in fr.frames) {
                                val rb = runCatching {
                                    val m = android.graphics.Matrix().apply { postRotate(90f) }
                                    android.graphics.Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
                                }.getOrNull()
                                if (rb != null) { rotFrames.add(rb); runCatching { if (!b.isRecycled) b.recycle() } }
                                else rotFrames.add(b)
                            }
                            if (rotFrames.isNotEmpty())
                                MotionDecoder.Frames(rotFrames, fr.delaysMs, fr.srcH, fr.srcW, 90)
                            else fr
                        }
                    } else {
                        EditorState.motionForceVideoRot = -1
                        fr
                    }
                    val detectedForce = EditorState.motionForceVideoRot
                    runOnUiThread {
                        val coverCopy = finalCover?.takeIf { !it.isRecycled }
                            ?.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                        val oldPv = EditorState.previewBitmap
                        EditorState.isGif = false; EditorState.gifMovie = null; EditorState.gifBytes = null
                        EditorState.clearMotion()
                        EditorState.motionMp4 = srcBytes
                        EditorState.motionForceVideoRot = detectedForce
                        EditorState.motionFrames = effFr.frames.toMutableList()
                        EditorState.motionDelays = effFr.delaysMs
                        EditorState.motionCoverBitmap = coverCopy
                        EditorState.previewBitmap = effFr.frames[0]
                        if (oldPv != null && !oldPv.isRecycled && oldPv !== EditorState.previewBitmap)
                            runCatching { oldPv.recycle() }
                        EditorState.sourceUri = uri; EditorState.isDemo = false
                        refreshHard()
                        toast(getString(if (coverCopy != null) R.string.motion_paired else R.string.video_loaded))
                    }
                }.onFailure { e ->
                    DebugLog.log("DECODE",
                        "选视频解码异常 size=${srcBytes.size} mime=$mime err=${e.message} reason=${MotionDecoder.lastFailReason}")
                    runOnUiThread { toast((e.message ?: getString(R.string.video_decode_fail)) + diag()) }
                }
                return@Thread
            }
            val result = runCatching { BitmapUtils.decodePreviewBytes(bytes) }
            result.onSuccess { info ->
                runOnUiThread {
                    pendingImageUri = uri
                    EditorState.isGif = false; EditorState.gifMovie = null; EditorState.gifBytes = null
                    EditorState.clearMotion()

                    EditorState.previewBitmap?.let { if (!it.isRecycled) runCatching { it.recycle() } }
                    EditorState.previewBitmap = info.bitmap
                    EditorState.sourceUri = uri; EditorState.isDemo = false

                    if (!EditorState.modelCustom) info.model?.takeIf { it.isNotBlank() }?.let {
                        val mk = info.make?.trim()?.replaceFirstChar { c -> c.uppercase() }
                        EditorState.params.model =
                            if (!mk.isNullOrBlank() && !it.contains(mk, ignoreCase = true))
                                "$mk $it".trim() else it.trim()
                    }
                    info.shotTime?.let { EditorState.params.dateText = it }

                    if (EditorState.params.geoMode == EditorState.GEO_EXIF) {
                        (runCatching { GeoUtils.readExifGeo(this, uri) }.getOrNull())?.let {
                            EditorState.params.geoText = it
                        }
                    }
                    refreshHard()

                    if (BitmapUtils.isOtherAnimated(this, uri))
                        tryWebpDecodeAfterStatic(bytes, uri)
                }
                val mp4 = runCatching { MotionPhoto.extractMp4(bytes) }.getOrNull()
                if (mp4 != null) {
                    loadMotion(mp4, bytes)
                } else {

                    val companion = runCatching { BitmapUtils.findCompanionVideo(this, uri) }.getOrNull()
                    if (companion != null) {
                        val vBytes = runCatching { BitmapUtils.readBytes(this, companion) }.getOrNull()
                        if (vBytes != null) loadMotion(vBytes)
                    } else if (isCnmManufacturer()) {

                        val vidCount = runCatching {
                            contentResolver.query(
                                android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                                arrayOf(android.provider.MediaStore.Video.Media._ID), null, null, null
                            )?.use { it.count } ?: -1
                        }.getOrElse { -1 }
                        DebugLog.log("PAIR", "自动配对失败 uri=$uri 系统视频条目数=$vidCount")
                        runOnUiThread { toast(getString(R.string.vivo_pair_hint)) }
                    }
                }
            }.onFailure { e ->
                runOnUiThread { toast(e.message ?: "图片读取失败，请换一张试试") }
            }
        }.start()
    }

    private fun diag(): String {
        val r = MotionDecoder.lastFailReason
        return if (r.isBlank()) "" else "\n(" + r + ")"
    }

    fun readExifGeoFromSource() {
        val uri = EditorState.sourceUri ?: pendingImageUri
        Thread {
            val ll = runCatching { GeoUtils.readExifLatLng(this, uri) }.getOrNull()
            if (ll == null) {
                runOnUiThread { toast(getString(R.string.geo_no_gps)) }
                return@Thread
            }
            val place = runCatching { GeoUtils.reverseGeocode(this, ll.first, ll.second) }.getOrNull()
            runOnUiThread {
                val p = EditorState.params
                p.geoMode = EditorState.GEO_EXIF
                p.geoLat = ll.first; p.geoLng = ll.second
                p.geoText = if (p.geoStyle == EditorState.GEO_STYLE_PLACE && !place.isNullOrBlank())
                    place else GeoUtils.formatCoord(ll.first, ll.second)
                bump()
                toast(getString(R.string.geo_exif_ok))
            }
        }.start()
    }

    fun useCurrentLocation() {
        val hasFine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        val hasCoarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) {

            pendingPermAction = { useCurrentLocation() }
            permLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            return
        }
        Thread {
            val loc = runCatching { GeoUtils.lastKnown(this) }.getOrNull()
            if (loc == null) {
                runOnUiThread { toast(getString(R.string.geo_no_loc)) }
                return@Thread
            }

            val place = runCatching { GeoUtils.reverseGeocode(this, loc.latitude, loc.longitude) }.getOrNull()
            runOnUiThread {
                val p = EditorState.params
                p.geoMode = EditorState.GEO_CURRENT
                p.geoLat = loc.latitude; p.geoLng = loc.longitude
                p.geoText = if (p.geoStyle == EditorState.GEO_STYLE_PLACE && !place.isNullOrBlank())
                    place else GeoUtils.formatCoord(loc.latitude, loc.longitude)
                bump()
                toast(if (!place.isNullOrBlank()) getString(R.string.geo_place_ok, place)
                      else getString(R.string.geo_current_ok))
            }
        }.start()
    }

    fun applyGeoStyle(style: Int) {
        val p = EditorState.params
        p.geoStyle = style
        if (p.geoMode == EditorState.GEO_OFF || p.geoMode == EditorState.GEO_CUSTOM) { bump(); return }
        if (p.geoLat == 0.0 && p.geoLng == 0.0) {

            useCurrentLocation()
            return
        }
        if (style == EditorState.GEO_STYLE_COORD) {
            p.geoText = GeoUtils.formatCoord(p.geoLat, p.geoLng)
            bump()
            return
        }
        val lat = p.geoLat; val lng = p.geoLng
        Thread {
            val place = runCatching { GeoUtils.reverseGeocode(this, lat, lng) }.getOrNull()
            runOnUiThread {
                p.geoText = place ?: GeoUtils.formatCoord(lat, lng)
                bump()
                if (place.isNullOrBlank()) toast(getString(R.string.geo_place_fail))
            }
        }.start()
    }

    fun clearGeo() {
        EditorState.params.geoMode = EditorState.GEO_OFF
        EditorState.params.geoText = ""
        bump()
    }

    private fun fallbackStaticFromWebP(bytes: ByteArray, uri: Uri?) {        val info = runCatching { BitmapUtils.decodePreviewBytes(bytes) }.getOrNull()
        if (info == null) {
            runOnUiThread { toast(getString(R.string.webp_decode_fail)) }
            return
        }
        runOnUiThread {
            pendingImageUri = uri
            EditorState.isGif = false; EditorState.gifMovie = null; EditorState.gifBytes = null
            EditorState.clearMotion()
            EditorState.previewBitmap?.let { if (!it.isRecycled) runCatching { it.recycle() } }
            EditorState.previewBitmap = info.bitmap
            EditorState.sourceUri = uri; EditorState.isDemo = false

            if (!EditorState.modelCustom) info.model?.takeIf { it.isNotBlank() }?.let {
                val mk = info.make?.trim()?.replaceFirstChar { c -> c.uppercase() }
                EditorState.params.model =
                    if (!mk.isNullOrBlank() && !it.contains(mk, ignoreCase = true))
                        "$mk $it".trim() else it.trim()
            }
            info.shotTime?.let { EditorState.params.dateText = it }

            if (EditorState.params.geoMode == EditorState.GEO_EXIF) {
                (runCatching { GeoUtils.readExifGeo(this, uri) }.getOrNull())?.let {
                    EditorState.params.geoText = it
                }
            }
            refreshHard()
            toast(getString(R.string.webp_decode_fail))
        }
    }

    private fun tryWebpDecodeAfterStatic(bytes: ByteArray, uri: Uri?) {
        val frames = ArrayList<android.graphics.Bitmap>()
        val delays = ArrayList<Int>()
        WebPDecoder.decodeAsync(
            Handler(Looper.getMainLooper()), this, bytes, 480, 90, 12_000,
            onFrame = { bmp, d -> frames.add(bmp); delays.add(d) },
            onDone = { ok ->
                if (ok && frames.size >= 2) {
                    val oldPv = EditorState.previewBitmap
                    EditorState.isGif = false; EditorState.gifMovie = null; EditorState.gifBytes = null
                    EditorState.clearMotion()
                    EditorState.webpBytes = bytes
                    EditorState.motionFrames = frames
                    EditorState.motionDelays = IntArray(delays.size) { delays[it] }
                    EditorState.previewBitmap = frames[0]
                    if (oldPv != null && oldPv !== frames[0] && !oldPv.isRecycled)
                        runCatching { oldPv.recycle() }
                    EditorState.sourceUri = uri
                    refreshHard()
                    toast(getString(R.string.webp_loaded))
                } else {
                    frames.forEach { runCatching { if (!it.isRecycled) it.recycle() } }

                }
            })
    }

    private fun loadMotion(mp4: ByteArray, whole: ByteArray? = null) {
        EditorState.motionMp4 = mp4
        var fr = runCatching { MotionDecoder.decode(this, mp4, 720, 12, 60, maxDurationMs = 5000L) }.getOrNull()

        if (fr == null && whole != null && whole !== mp4) {
            fr = runCatching { MotionDecoder.decode(this, whole, 720, 12, 60, maxDurationMs = 5000L) }.getOrNull()
        }

        val cover = EditorState.previewBitmap
        if (fr != null && fr.frames.isNotEmpty() && fr.rotation == 0 &&
            fr.frames[0].width > fr.frames[0].height &&
            cover != null && !cover.isRecycled && cover.height > cover.width &&
            videoDurationMs(null, mp4) in 1..15_000L) {
            EditorState.motionForceVideoRot = 90
            val oldFr = fr
            val re = runCatching { MotionDecoder.decode(this, mp4, 720, 12, 60, maxDurationMs = 5000L,
                rotationOverride = 90) }.getOrNull()
                ?: if (whole != null && whole !== mp4) runCatching {
                    MotionDecoder.decode(this, whole, 720, 12, 60, rotationOverride = 90, maxDurationMs = 5000L)
                }.getOrNull() else null
            if (re != null) {

                if (oldFr != null) oldFr.frames.forEach { runCatching { if (!it.isRecycled) it.recycle() } }
                fr = re
            } else {

                EditorState.motionForceVideoRot = 90
                val rotFrames = ArrayList<android.graphics.Bitmap>(fr.frames.size)
                for (b in fr.frames) {
                    val rb = runCatching {
                        val m = android.graphics.Matrix().apply { postRotate(90f) }
                        android.graphics.Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
                    }.getOrNull()
                    if (rb != null) { rotFrames.add(rb); runCatching { if (!b.isRecycled) b.recycle() } }
                    else rotFrames.add(b)
                }
                if (rotFrames.isNotEmpty())
                    fr = MotionDecoder.Frames(rotFrames, fr.delaysMs, fr.srcH, fr.srcW, 90)
            }

        } else {
            EditorState.motionForceVideoRot = -1
        }
        val detectedForce = EditorState.motionForceVideoRot
        if (fr != null && fr.frames.size >= 2) runOnUiThread {
            EditorState.isGif = false; EditorState.clearMotion()
            EditorState.motionForceVideoRot = detectedForce
            EditorState.motionMp4 = mp4
            EditorState.motionFrames = fr.frames.toMutableList()
            EditorState.motionDelays = fr.delaysMs
            refreshHard()
            toast(getString(R.string.motion_loaded))
        } else runOnUiThread {
            EditorState.motionDecodeFailed = true
            DebugLog.log("DECODE", "解码失败 大小=${mp4.size}B reason=${MotionDecoder.lastFailReason}")
            toast(getString(R.string.motion_decode_fail_hint) + diag())
        }
    }

    private var pendingPermAction: (() -> Unit)? = null
    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val action = pendingPermAction
            pendingPermAction = null
            if (granted) action?.invoke() else toast(getString(R.string.need_permission))
        }

    private fun ensureStoragePermission(action: () -> Unit): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            pendingPermAction = action
            permLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return false
        }
        return true
    }

    private fun publishProgress(v: Int) { runOnUiThread { progressPct = v } }

    private fun clearExportCache(): Long {
        if (!EditorState.params.autoClean) return -1L
        return doClearCache()
    }

    private fun clearExportCacheLight(): Long {
        var freed = 0L
        runCatching {
            cacheDir.listFiles()?.forEach { f ->
                val n = f.name
                if (n.startsWith("lgw_mux_") || n.startsWith("lgw_export_") ||
                    n.startsWith("mp_") || n.startsWith("lgw_")) {
                    freed += f.length()
                    runCatching { f.delete() }
                }
            }
        }
        return freed
    }

    private fun doClearCache(): Long {
        var freed = 0L
        runCatching {
            cacheDir.listFiles()?.forEach { f ->
                val n = f.name
                if (n.startsWith("lgw_mux_") || n.startsWith("lgw_export_") ||
                    n.startsWith("mp_") || n.startsWith("lgw_")) {
                    freed += f.length()
                    runCatching { f.delete() }
                }
            }
        }
        return freed
    }

    fun cleanCacheNow(onDone: ((Long) -> Unit)? = null) {
        Thread {
            val freed = doClearCache()
            ui.post { onDone?.invoke(freed) }
        }.start()
    }

    private val exporting = java.util.concurrent.atomic.AtomicBoolean(false)

    fun onFluidToggle(on: Boolean) {
        EditorState.params.fluidEnabled = on
        EditorState.persist(this)
        preview?.invalidateBlur()
        preview?.invalidate()
        bump()
    }

    private val cancelExportFlag = java.util.concurrent.atomic.AtomicBoolean(false)
    private class ExportCancelledException : RuntimeException("导出已取消")
    fun requestCancelExport() {
        cancelExportFlag.set(true)

        MotionDecoder.requestExternalCancel()
    }
    private fun checkCancelled() {
        if (cancelExportFlag.get()) throw ExportCancelledException()
    }

    fun doSave() {

        if (progressPct >= 0 || EditorState.isExporting) {
            toast(getString(R.string.export_busy)); return
        }
        if (!ensureStoragePermission { doSave() }) return
        if (!exporting.compareAndSet(false, true)) { toast(getString(R.string.export_busy)); return }
        try { doSaveNow() } finally { exporting.set(false) }
    }

    private fun doSaveNow() {
        cancelExportFlag.set(false)
        progressPct = 0
        when {
            EditorState.isGif || EditorState.gifBytes != null -> {

                if (EditorState.motionExportFormat != "gif") {
                    progressPct = -1
                    toast(getString(R.string.save_fail, "动图素材仅支持 GIF 导出，请切换为 GIF 或改选视频"))
                    return
                }
                doSaveGif(); return
            }

            EditorState.motionMp4 != null || EditorState.motionUri != null ||
                EditorState.webpBytes != null || (EditorState.motionFrames?.size ?: 0) >= 2 -> {
                doSaveMotion(); return
            }
        }
        publishProgress(5)
        val ed = preview; val uri = EditorState.sourceUri
        Thread {
            try {
                publishProgress(15)
                val full = if (uri != null) {
                    runCatching { BitmapUtils.decodeFull(this, uri) }
                        .getOrElse { ed?.sourceForExport() ?: error("源图不可用") }
                } else ed?.sourceForExport() ?: error("请先选择照片")
                if (full.isRecycled) error("源图不可用，请重新选择照片")
                publishProgress(45)
                val out = ed?.export(full) ?: error("预览未就绪，请稍候再试")
                publishProgress(78)
                val path = BitmapUtils.saveToGallery(this, out)
                publishProgress(100)
                if (uri != null && full !== EditorState.previewBitmap) full.recycle()
                out.recycle()
                ui.postDelayed({
                    progressPct = -1
                    if (EditorState.motionDecodeFailed)
                        toast(getString(R.string.motion_decode_fail_saved_static, path))
                    else {
                        clearExportCache()
                        toast(getString(R.string.saved_ok, path))
                    }
                }, 250)
            } catch (e: Throwable) {
                DebugLog.logThrowable("SAVE_IMAGE", e)
                ui.post {
                    progressPct = -1
                    toast(getString(R.string.save_fail, e.message ?: ""))
                }
            }
        }.start()
    }

    private fun doSaveGif() {
        Thread {
            try {
                val bytes = EditorState.gifBytes
                    ?: EditorState.sourceUri?.let { BitmapUtils.readBytes(this, it) }
                    ?: error("请先选择一张动图")
                val gi = BitmapUtils.decodeGif(bytes)
                val movie = gi.movie

                EditorState.sanitizeGifParams()
                val maxEdge = EditorState.gifEdgePx
                val sc = minOf(1f, maxEdge.toFloat() / maxOf(gi.w, gi.h).toFloat())
                val rot = ((EditorState.photoRotation % 360) + 360) % 360
                val rawW = maxOf(1, (gi.w * sc).toInt())
                val rawH = maxOf(1, (gi.h * sc).toInt())
                val outW = if (rot % 180 != 0) rawH else rawW
                val outH = if (rot % 180 != 0) rawW else rawH
                val dur = gi.duration.coerceAtLeast(1)

                val timeline = BitmapUtils.gifTimeline(bytes)
                val frameTimes: IntArray
                val frameCs: IntArray
                if (timeline != null) {
                    val n = timeline.size.coerceAtMost(600)
                    val st = IntArray(n); val cs = IntArray(n)
                    var acc = 0
                    for (i in 0 until n) {
                        st[i] = acc
                        val d = if (timeline[i] > 0) timeline[i] else 100
                        cs[i] = (d / 10f).roundToInt().coerceIn(1, 65535)
                        acc += d
                    }
                    frameTimes = st; frameCs = cs
                } else {

                    val dt = (1000f / 20f).toInt().coerceAtLeast(10)
                    val n = (((dur + dt - 1) / dt).coerceAtMost(600)).coerceAtLeast(1)
                    frameTimes = IntArray(n) { it * dt }
                    frameCs = IntArray(n) { (dt / 10f).roundToInt().coerceIn(1, 100) }
                }
                val frames = frameTimes.size
                val ed = preview ?: run {
                    ui.post { progressPct = -1; toast(getString(R.string.save_fail, "预览未就绪")) }
                    return@Thread
                }
                publishProgress(6)
                val quant = GifQuant.Quantizer()
                for (i in 0 until frames) {
                    checkCancelled()
                    EditorState.exportTimeSec = frameTimes[i] / 1000f
                    val quantScale = minOf(1f, 360f / maxOf(outW, outH))
                    quant.feed(ed.renderGifFrame(movie, frameTimes[i],
                        (outW * quantScale).roundToInt().coerceAtLeast(1),
                        (outH * quantScale).roundToInt().coerceAtLeast(1)))
                    publishProgress(6 + (30 * (i + 1) / frames))
                }
                val palette = quant.build(256)
                val enc = GifEncoder(outW, outH, true)
                for (i in 0 until frames) {
                    checkCancelled()

                    EditorState.exportTimeSec = frameTimes[i] / 1000f
                    val fr = ed.renderGifFrame(movie, frameTimes[i], outW, outH)

                    enc.addFrame(palette.toIndices(fr, dither = false), palette.rgb, frameCs[i])
                    publishProgress(40 + (55 * (i + 1) / frames))
                }
                val gifBytes = enc.finish()
                publishProgress(98)
                val path = BitmapUtils.saveGifToGallery(this, gifBytes)
                publishProgress(100)
                ui.postDelayed({
                    clearExportCache()
                    progressPct = -1; toast(getString(R.string.saved_ok, path))
                }, 250)
            } catch (e: Throwable) {
                DebugLog.logThrowable("SAVE_GIF", e)
                val logPath = DebugLog.exportToDownload()
                ui.post {
                    progressPct = -1
                    toast(getString(R.string.save_fail, e.message ?: "") +
                        (logPath?.let { "\n完整日志：$it" } ?: ""))
                }
            }
        }.start()
    }

    private fun exportWebpToGif(wp: ByteArray) {
        val ed = preview ?: run {
            ui.post { progressPct = -1; toast(getString(R.string.save_fail, "预览未就绪")) }
            return
        }
        publishProgress(12)
        val rot = ((EditorState.photoRotation % 360) + 360) % 360
        val rotW = rot % 180 != 0
        val gifMaxFrames = 3600
        var outW = 0; var outH = 0
        var decoded = 0
        var quantTimeMs = 0L
        EditorState.sanitizeGifParams()
        val wpMaxEdge = EditorState.gifEdgePx
        val quant = GifQuant.Quantizer()
        val mainH = Handler(Looper.getMainLooper())
        val finishFail = { msg: String ->
            ui.post { progressPct = -1; toast(getString(R.string.save_fail, msg)) }
        }
        WebPDecoder.decodeAsync(mainH, this, wp, wpMaxEdge, gifMaxFrames, 120_000,
            onFrame = { bmp, delay ->
                checkCancelled()
                if (outW == 0) {
                    val rw = bmp.width.coerceAtLeast(1); val rh = bmp.height.coerceAtLeast(1)
                    outW = if (rotW) rh else rw
                    outH = if (rotW) rw else rh
                }
                EditorState.exportTimeSec = quantTimeMs / 1000f
                quantTimeMs += delay.coerceAtLeast(1)
                val quantScale = minOf(1f, 360f / maxOf(outW, outH))
                quant.feed(ed.renderMotionFrame(bmp,
                    (outW * quantScale).roundToInt().coerceAtLeast(1),
                    (outH * quantScale).roundToInt().coerceAtLeast(1)))
                bmp.recycle()
                decoded++
                publishProgress(12 + (26 * decoded / gifMaxFrames).coerceAtMost(38))
            },
            onDone = { ok ->
                if (cancelExportFlag.get()) {
                    runCatching { clearExportCacheLight() }
                    ui.post { progressPct = -1; toast(getString(R.string.export_cancelled)) }
                    return@decodeAsync
                }
                if (!ok || decoded < 1) { finishFail(getString(R.string.webp_decode_fail)); return@decodeAsync }
                if (decoded >= gifMaxFrames)
                    ui.post { toast(getString(R.string.gif_truncated)) }
                val palette = quant.build(256)
                val enc = GifEncoder(outW, outH, true)
                var d2 = 0
                var encTimeMs = 0L
                WebPDecoder.decodeAsync(mainH, this, wp, wpMaxEdge, gifMaxFrames, 120_000,
                    onFrame = { bmp2, delay2 ->
                        checkCancelled()

                        EditorState.exportTimeSec = encTimeMs / 1000f
                        encTimeMs += delay2.coerceAtLeast(1)
                        val fr = ed.renderMotionFrame(bmp2, outW, outH)
                        enc.addFrame(palette.toIndices(fr, dither = false), palette.rgb,
                            (delay2 / 10f).roundToInt().coerceIn(1, 100))

                        bmp2.recycle()
                        d2++

                        publishProgress((40 + 55 * d2 / decoded.coerceAtLeast(1)).coerceAtMost(95))
                    },
                    onDone = { ok2 ->
                        if (cancelExportFlag.get()) {
                            runCatching { clearExportCacheLight() }
                            ui.post { progressPct = -1; toast(getString(R.string.export_cancelled)) }
                            return@decodeAsync
                        }
                        if (!ok2 || d2 < 1) { finishFail(getString(R.string.webp_decode_fail)); return@decodeAsync }
                        val gifBytes = enc.finish()
                        publishProgress(98)
                        val saved = runCatching { BitmapUtils.saveGifToGallery(this, gifBytes) }
                        publishProgress(100)
                        ui.postDelayed({
                            clearExportCacheLight()
                            progressPct = -1
                            saved.onSuccess { toast(getString(R.string.saved_ok, it)) }
                                .onFailure { toast(getString(R.string.save_fail, it.message ?: "")) }
                        }, 250)
                    })
            })
    }

    private fun doSaveMotion() {
        cancelExportFlag.set(false)
        Thread {
            try {
                publishProgress(4)
                val ed = preview ?: run {
                    ui.post { progressPct = -1; toast(getString(R.string.save_fail, "预览未就绪")) }
                    return@Thread
                }
                val mp4 = EditorState.motionMp4
                val webp = EditorState.webpBytes
                val motionUri = EditorState.motionUri
                if (mp4 == null && webp == null && motionUri == null) {

                    ui.post { progressPct = -1; toast(getString(R.string.save_fail, "动图源丢失，请重新选择")) }
                    return@Thread
                }
                if (webp != null) {

                    if (EditorState.motionExportFormat != "gif") {
                        ui.post { progressPct = -1; toast(getString(R.string.save_fail, "动态 WebP 仅支持 GIF 导出，请切换为 GIF 或改选视频")) }
                        return@Thread
                    }
                    val wp = webp!!

                    exportWebpToGif(wp)
                    return@Thread
                }
                val rot = ((EditorState.photoRotation % 360) + 360) % 360

                if (EditorState.motionExportFormat == "mp4" ||
                    EditorState.motionExportFormat == "hevc") {
                    publishProgress(10)
                    val codec = if (EditorState.motionExportFormat == "hevc")
                        MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
                    val outMp4 = Mp4Exporter.export(this, mp4 ?: ByteArray(0), rot, { srcFrame ->
                        checkCancelled()

                        val isRot = rot != 0
                        val frame = if (isRot) {
                            val m = android.graphics.Matrix().apply { postRotate(rot.toFloat()) }
                            android.graphics.Bitmap.createBitmap(
                                srcFrame, 0, 0, srcFrame.width, srcFrame.height, m, true)
                        } else srcFrame
                        val w = frame.width.coerceAtLeast(1); val h = frame.height.coerceAtLeast(1)
                        val out = ed.renderWatermarkOnly(frame, w, h)
                        if (isRot) frame.recycle()
                        out
                    }, { p -> publishProgress(p.coerceIn(0, 100)) },

                        prefixFrame = null,
                        prefixDurationMs = 0, codec = codec, srcUri = motionUri,
                        forceVideoRotation = EditorState.motionForceVideoRot,
                        reuseOutput = true,

                        watermarkRenderer = if (EditorState.params.exportMode == 2) null else ed,
                        isCancelled = { cancelExportFlag.get() })
                    if (outMp4 == null) {
                        if (cancelExportFlag.get()) {
                            runCatching { clearExportCacheLight() }
                            ui.post { progressPct = -1; toast(getString(R.string.export_cancelled)) }
                            return@Thread
                        }
                        error(getString(R.string.motion_decode_fail) +
                            Mp4Exporter.lastFailReason.takeIf { it.isNotBlank() }?.let { "（$it）" } ?: "")
                    }
                    publishProgress(95)

                    val saved = runCatching { saveMp4ToGallery(outMp4) }
                    runCatching { outMp4.delete() }
                    val path = saved.getOrElse { e -> error(e.message ?: "保存失败") }
                    publishProgress(100)
                    ui.postDelayed({
                        clearExportCacheLight()
                    progressPct = -1; toast(getString(R.string.saved_ok, path))
                    }, 250)
                    return@Thread
                }

                var gifOwnLocal = false
                val gifLocal: java.io.File? = when {
                    EditorState.motionLocalPath != null -> {
                        val lp = java.io.File(EditorState.motionLocalPath!!)
                        if (lp.exists() && lp.length() > 0) lp
                        else {
                            DebugLog.log("SAVE", "导入缓存文件丢失 ${EditorState.motionLocalPath}，回退重新复制")
                            EditorState.motionLocalPath = null
                            null
                        }
                    }
                    motionUri != null && motionUri.scheme.equals("file", true) ->
                        java.io.File(motionUri.path ?: motionUri.toString())
                    motionUri != null -> {
                        val t = java.io.File(cacheDir, "lgw_giflocal_${System.currentTimeMillis()}.mp4")
                        if (MotionDecoder.copyUriToFile(this, motionUri, t) && t.length() > 0) {
                            gifOwnLocal = true; t
                        } else { runCatching { t.delete() }; null }
                    }
                    else -> java.io.File(cacheDir, "lgw_giflocal_${System.currentTimeMillis()}.mp4").also {
                        it.writeBytes(mp4 ?: error("视频源丢失")); gifOwnLocal = true }
                }
                if (gifLocal == null) {
                    error(getString(R.string.motion_decode_fail) +
                        "（${MotionDecoder.lastFailReason.takeIf { it.isNotBlank() } ?: "源视频复制失败"}）")
                }
                val gifLocalUri = android.net.Uri.fromFile(gifLocal)
                checkCancelled()

                val probe = runCatching {

                    MotionDecoder.decode(this, ByteArray(0), 8192, 240, 1, 15_000,
                        minFrames = 1, srcUri = gifLocalUri,
                        rotationOverride = EditorState.motionForceVideoRot)
                }.getOrNull()
                if (probe == null || probe.frames.isEmpty()) error(getString(R.string.motion_decode_fail))
                val rawW = probe.frames[0].width.coerceAtLeast(1)
                val rawH = probe.frames[0].height.coerceAtLeast(1)
                probe.frames.forEach { runCatching { it.recycle() } }
                val rotW = rot % 180 != 0

                EditorState.sanitizeGifParams()
                val gifFps = EditorState.gifFps
                val gifMaxEdge = EditorState.gifEdgePx.toFloat()
                val gsc = minOf(1f, gifMaxEdge / maxOf(rawW, rawH).toFloat())
                val scaledW = maxOf(1, (rawW * gsc).toInt())
                val scaledH = maxOf(1, (rawH * gsc).toInt())
                val outW = if (rotW) scaledH else scaledW
                val outH = if (rotW) scaledW else scaledH

                publishProgress(12)

                val sourceGifDurMs = runCatching {
                    val mmr = android.media.MediaMetadataRetriever()
                    try {
                        mmr.setDataSource(gifLocal.absolutePath)
                        mmr.extractMetadata(
                            android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    } finally { runCatching { mmr.release() } }
                }.getOrDefault(0L)

                val srcDurSafe = sourceGifDurMs.takeIf { it > 0L } ?: VideoGifTimeline.MAX_DURATION_MS.toLong()
                val trimStartMs = if (EditorState.gifTrimCustomized)
                    EditorState.gifTrimStartMs.coerceIn(0L, (srcDurSafe - 1L).coerceAtLeast(0L))
                else 0L
                val availMs = (srcDurSafe - trimStartMs).coerceAtLeast(1L)
                val trimDurMs = if (EditorState.gifTrimCustomized)
                    EditorState.gifTrimDurationMs.coerceIn(100L, availMs)
                else minOf(availMs, VideoGifTimeline.MAX_DURATION_MS.toLong())
                val gifDurMs = trimDurMs

                if (!EditorState.gifTrimCustomized && sourceGifDurMs > VideoGifTimeline.MAX_DURATION_MS)
                    ui.post { toast("视频超过五秒，GIF 已自动截取前五秒") }
                val quant = GifQuant.Quantizer()

                val quantScale = minOf(1f, 360f / maxOf(outW, outH))
                val quantW = (outW * quantScale).roundToInt().coerceAtLeast(1)
                val quantH = (outH * quantScale).roundToInt().coerceAtLeast(1)

                var decoded = 0
                var decodedMs = 0L

                val gifMaxFrames = 1_200
                val quantTimeline = VideoGifTimeline(startMs = trimStartMs.toInt(), limitMs = trimDurMs.toInt())

                val gifDecodeTimeoutMs = (300_000L).coerceAtLeast(
                    gifDurMs * 6 + (gifDurMs * gifFps / 1000L) * 150L
                ).coerceAtMost(30 * 60_000L).toInt()
                val ok = MotionDecoder.decodeFrames(

                    this, ByteArray(0), 480, gifFps, gifDecodeTimeoutMs, gifMaxFrames,
                    srcUri = gifLocalUri,
                    rotationOverride = EditorState.motionForceVideoRot,
                    maxDurationMs = gifDurMs,
                    startPtsUs = trimStartMs * 1000L,
                    onFrame = { bmp, delayMs ->
                        checkCancelled()
                        EditorState.exportTimeSec = quantTimeline.sourceTimeMs() / 1000f
                        val delayCs = quantTimeline.takeDelayCs(delayMs)
                        if (delayCs > 0) quant.feed(ed.renderMotionFrame(bmp, quantW, quantH))
                        decoded++
                        decodedMs += delayMs.coerceAtLeast(1)

                        publishProgress(
                            if (gifDurMs > 0) (12 + 26 * decodedMs / gifDurMs).toInt().coerceIn(12, 38)
                            else (12 + 26 * decoded / gifMaxFrames).coerceAtMost(38))
                        true
                    }
                )
                if (!ok || decoded < 1) {
                    if (cancelExportFlag.get()) {
                        runCatching { clearExportCacheLight() }
                        ui.post { progressPct = -1; toast(getString(R.string.export_cancelled)) }
                        return@Thread
                    }
                    error(getString(R.string.motion_decode_fail))
                }
                if (decoded >= gifMaxFrames) {
                    publishProgress(38)
                    ui.post { toast(getString(R.string.gif_truncated)) }
                }
                val palette = quant.build(256)

                val enc = GifEncoder(outW, outH, true)
                var encoded = 0
                val encodeTimeline = VideoGifTimeline(startMs = trimStartMs.toInt(), limitMs = trimDurMs.toInt())
                val encodedOk = MotionDecoder.decodeFrames(

                    this, ByteArray(0), EditorState.gifEdgePx, gifFps, gifDecodeTimeoutMs, gifMaxFrames,
                    srcUri = gifLocalUri,
                    rotationOverride = EditorState.motionForceVideoRot,
                    maxDurationMs = gifDurMs,
                    startPtsUs = trimStartMs * 1000L,
                    onFrame = { bmp, delayMs ->
                        checkCancelled()
                        EditorState.exportTimeSec = encodeTimeline.sourceTimeMs() / 1000f
                        val delayCs = encodeTimeline.takeDelayCs(delayMs)
                        if (delayCs > 0) enc.addFrame(
                            palette.toIndices(ed.renderMotionFrame(bmp, outW, outH), dither = false), palette.rgb, delayCs)
                        encoded++

                        publishProgress((40 + 55 * encoded / decoded.coerceAtLeast(1)).coerceAtMost(95))
                        true
                    }
                )
                checkCancelled()
                if (!encodedOk || encoded == 0) error(getString(R.string.motion_decode_fail))
                val gif = enc.finish()
                publishProgress(98)
                val path = BitmapUtils.saveGifToGallery(this, gif)
                publishProgress(100)
                if (gifOwnLocal) runCatching { gifLocal.delete() }
                ui.postDelayed({
                    clearExportCacheLight()
                    progressPct = -1; toast(getString(R.string.saved_ok, path))
                }, 250)
            } catch (e: Throwable) {
                if (e is ExportCancelledException) {

                    runCatching { clearExportCacheLight() }
                    ui.post { progressPct = -1; toast(getString(R.string.export_cancelled)) }
                    return@Thread
                }
                DebugLog.logThrowable("SAVE_MOTION", e)

                val logPath = DebugLog.exportToDownload()
                ui.post {
                    progressPct = -1
                    toast(getString(R.string.save_fail, e.message ?: "") +
                        (logPath?.let { "\n完整日志：$it" } ?: ""))
                }
            }
        }.start()
    }

    private val durationCache = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun videoDurationMs(uri: android.net.Uri?, bytes: ByteArray?): Long {
        val key = when {
            uri != null -> "u:$uri"
            bytes != null && bytes.size > 4096 -> "b:${bytes.size}:${bytes.contentHashCode()}"
            else -> return -1L
        }
        durationCache[key]?.let { return it }
        val d = runCatching {
            val mmr = android.media.MediaMetadataRetriever()
            try {
                when {
                    uri != null -> {
                        mmr.setDataSource(this, uri)
                        mmr.extractMetadata(
                            android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: -1L
                    }
                    bytes != null && bytes.size > 4096 -> {
                        val tf = java.io.File(cacheDir, "lgw_dur_${System.currentTimeMillis()}.mp4")
                        try {
                            tf.writeBytes(bytes)
                            mmr.setDataSource(tf.absolutePath)
                            mmr.extractMetadata(
                                android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: -1L
                        } finally { runCatching { tf.delete() } }
                    }
                    else -> -1L
                }
            } finally { runCatching { mmr.release() } }
        }.getOrDefault(-1L)
        if (d > 0) {
            if (durationCache.size > 16) durationCache.clear()
            durationCache[key] = d
        }
        return d
    }

    private fun saveMp4ToGallery(src: java.io.File): String {        val resolver = contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val name = "LiquidGlass_${System.currentTimeMillis()}.mp4"
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, "DCIM/LiquidGlass")
                put(android.provider.MediaStore.Video.Media.IS_PENDING, 1)
            }
            val ins = resolver.insert(
                android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("相册写入失败")
            try {
                resolver.openOutputStream(ins)?.use { os ->
                    java.io.FileInputStream(src).use { it.copyTo(os) }
                } ?: error("相册写入失败")
            } finally {

                val pendingDone = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Video.Media.IS_PENDING, 0)
                }
                val updated = runCatching { resolver.update(ins, pendingDone, null, null) > 0 }
                    .getOrDefault(false)
                if (!updated) {

                    DebugLog.log("SAVE", "IS_PENDING 清除失败（update=0）uri=$ins，重试一次")
                    runCatching { resolver.update(ins, pendingDone, null, null) }
                }
            }

            runCatching {
                MediaScannerConnection.scanFile(this, arrayOf(src.absolutePath), arrayOf("video/mp4"), null)
            }
            return "DCIM/LiquidGlass/$name"
        } else {
            val dir = java.io.File(
                android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DCIM), "LiquidGlass")
            if (!dir.exists()) dir.mkdirs()
            val f = java.io.File(dir, "LiquidGlass_${System.currentTimeMillis()}.mp4")
            java.io.FileInputStream(src).use { i -> f.outputStream().use { i.copyTo(it) } }
            MediaScannerConnection.scanFile(this, arrayOf(f.absolutePath), arrayOf("video/mp4"), null)
            return f.absolutePath
        }
    }

    fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    fun gifSourceDurationMs(): Long {
        val d = videoDurationMs(EditorState.motionUri, EditorState.motionMp4)
        return if (d > 0L) d else VideoGifTimeline.MAX_DURATION_MS.toLong()
    }

    fun mpVideoDurationMs(): Long {
        val d = if (mpVideoUri != null) {
            videoDurationMs(mpVideoUri!!, null)
        } else if (EditorState.motionMp4 != null) {
            videoDurationMs(null, EditorState.motionMp4!!)
        } else 0L
        return if (d > 0L) d else VideoGifTimeline.MAX_DURATION_MS.toLong()
    }

    fun resetGifTrim() {
        EditorState.gifTrimStartMs = 0L
        EditorState.gifTrimDurationMs = 5_000L
        EditorState.gifTrimCustomized = false
        EditorState.persist(this)
        EditorState.bumpExportBar()
    }

    fun updateMotionTrim() {
        EditorState.persist(this)
        EditorState.bumpExportBar()
    }

    fun previewGifTrim() {
        if (EditorState.isExporting) { toast(getString(R.string.export_busy)); return }
        val local = EditorState.motionLocalPath?.let { java.io.File(it) }
        val srcUri = when {
            local != null && local.exists() && local.length() > 0 -> android.net.Uri.fromFile(local)
            EditorState.motionUri != null -> EditorState.motionUri
            EditorState.motionMp4 != null -> null
            else -> { toast(getString(R.string.mp_missing)); return }
        }
        val mp4Bytes = if (srcUri == null) (EditorState.motionMp4 ?: ByteArray(0)) else ByteArray(0)
        EditorState.sanitizeGifParams()
        val startMs = EditorState.gifTrimStartMs.coerceAtLeast(0L)
        var durMs = EditorState.gifTrimDurationMs.coerceAtLeast(300L)

        Thread {
            try {
                val tim = VideoGifTimeline(startMs = startMs.toInt(), limitMs = durMs.toInt())
                val frames = runCatching {
                    MotionDecoder.decode(this, mp4Bytes, 720, 12, 120, 60_000,
                        minFrames = 1, srcUri = srcUri,
                        rotationOverride = EditorState.motionForceVideoRot,
                        maxDurationMs = durMs, startPtsUs = startMs * 1000L)
                }.getOrNull()
                if (frames == null || frames.frames.isEmpty()) {
                    ui.post { toast(getString(R.string.video_decode_fail)) }
                    return@Thread
                }

                ui.post {
                    val old = EditorState.previewBitmap
                    EditorState.motionFrames?.forEach { runCatching { if (!it.isRecycled) it.recycle() } }

                    EditorState.motionFrames = frames.frames.toMutableList()
                    EditorState.motionDelays = frames.delaysMs
                    EditorState.motionForceVideoRot = frames.rotation
                    EditorState.previewBitmap = frames.frames.firstOrNull()
                    if (old != null && !old.isRecycled) runCatching { old.recycle() }
                    EditorState.isDemo = false
                    preview?.invalidate()
                    preview?.invalidateBlur()
                    bump()
                    toast(String.format(java.util.Locale.US, "预览片段 %.1fs — %.1fs",
                        startMs / 1000f, (startMs + durMs) / 1000f))
                }
            } catch (t: Throwable) {
                DebugLog.logThrowable("PREVIEW_GIF_TRIM", t)
                ui.post { toast(t.message ?: "片段预览失败") }
            }
        }.start()
    }
}

