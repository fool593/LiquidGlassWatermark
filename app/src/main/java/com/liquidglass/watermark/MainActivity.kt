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

    // ---- Compose 界面状态 ----
    var rev by mutableStateOf(0)              // 全局重组触发器
    // 进度（-1 空闲隐藏）。setter 单一真源同步导出锁：progressPct>=0 即导出中，
    // 水印拖动（View 读 EditorState.isExporting）与所有编辑控件据此锁定，底部导航不受影响。
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
            // 导出开始瞬间：捕获含水印预览快照（独立缓冲，与 GPU 渲染线程零共享），
            // 导出期间 onDraw 显示快照——用户看到水印"还在"（静态），且无竞态花屏
            if (v < 0) {
                preview?.clearExportSnapshot()
                EditorState.releaseExportParams()
            }
        }
    var preview: WatermarkEditorView? = null

    /** 最近一次选择的是静态图片（非 GIF/非视频）：再选视频时合并为动态照片，不覆盖图片 */
    @Volatile private var pendingImageUri: Uri? = null
    /** 下一次选图结果当作自定义品牌 logo 导入（pickCustomLogo 设置） */
    @Volatile private var pendingLogoPick = false
    // ---- 动态照片合成槽位：1=封面图片，2=视频片段；onPicked 据此分流 ----
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

    // ---------- 语言（attachBaseContext 与旧版一致） ----------
    override fun attachBaseContext(newBase: Context) {
        val p = newBase.getSharedPreferences(EditorState.PREFS, MODE_PRIVATE)
        val lang = p.getString("lang", "zh") ?: "zh"
        val cfg = Configuration(newBase.resources.configuration)
        cfg.setLocale(Locale(lang))
        super.attachBaseContext(newBase.createConfigurationContext(cfg))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 启动成功即清零崩溃计数
        getSharedPreferences("crash", MODE_PRIVATE).edit().putInt("count", 0).apply()
        App.showCrashLog(this)
        DebugLog.init(this)
        prefs = getSharedPreferences(EditorState.PREFS, MODE_PRIVATE)
        EditorState.attach(this)
        EditorState.restore(this)
        // 同步深色/浅色模式 Compose State（用全局 darkModeState，不依赖 params.darkMode——
        // 清数据后 restore 失败时 params.darkMode 恒为默认 true，会把 globalDark 覆盖回深色）
        setDarkMode(com.liquidglass.watermark.darkModeState)
        // 应用 API 模式（高/低/自动），同步软件模糊状态
        applyApiMode()
        // 品牌自动匹配应用到全部方案（方案1/方案2 都要有机型/logo/时间，否则切方案2 内容空、看起来"跟以前一样"）
        for (pi in 0 until EditorState.PLAN_COUNT) {
            val pp = EditorState.planParamsAt(pi)
            val b2 = DeviceNames.brand()
            // v15.18：去掉按值猜测的迁移（会误伤用户手填机型），只在空白时补默认
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
        // v15.18：不再按值猜测迁移（会误伤用户自定义机型）；自定义过或已有值都不覆盖
        if (!EditorState.modelCustom && p.model.isBlank()) p.model = EditorState.defaultModelName()
        if (!EditorState.modelCodeCustom && p.modelCode.isBlank()) p.modelCode = EditorState.defaultModelCode()
        if (p.dateText.isEmpty()) p.dateText = EditorState.nowString()
        // 品牌 logo 自动匹配：机型/图标/时间三要素对得上（iQOO→vivo、Redmi→小米、华为/荣耀→华为）
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
        // v14.55 自测钩子（真机/模拟器自动化）：
        //   adb shell am start -n com.liquidglass.watermark/.MainActivity --es ljgAutoTest "logo:/sdcard/logo.png"
        //   adb shell am start ... --es ljgAutoTest "fluid:1" / "fluid:0"
        //   v15.15 字体模式：--es ljgAutoTest "font:0"（默认字体）/ "font:1"（系统字体）
        intent.getStringExtra("ljgAutoTest")?.let { at ->
            ui.postDelayed({ runAutoTest(at) }, 1500L)
        }
    }

    /** v14.55 自测钩子：驱动真实业务链并落日志，供跨版本自动化验证（不弹 UI 干扰） */
    private fun runAutoTest(spec: String) {
        Thread {
            try {
                DebugLog.log("AUTOTEST", "触发: $spec")
                when {
                    // v15.15 字体模式切换（自动化验证入口，等价于设置页胶囊按钮）
                    spec.startsWith("font:") -> {
                        val v = spec.substringAfter("font:").trim().toIntOrNull() ?: 0
                        val mode = if (v == 1) FONT_MODE_SYSTEM else FONT_MODE_DEFAULT
                        EditorState.params.fontMode = mode
                        val name = if (mode == FONT_MODE_SYSTEM) "系统字体" else "默认字体"
                        DebugLog.log("AUTOTEST", "字体模式已切换 fontMode=$mode($name)")
                        runOnUiThread { bump() }
                    }
                    // v15.17 字体修复验证钩子：切换方案（0=经典 / 1=浅色），验证所选字体不被方案覆盖
                    spec.startsWith("planswitch:") -> {
                        val v = spec.substringAfter("planswitch:").trim().toIntOrNull() ?: 0
                        val idx = if (v == 1) EditorState.PLAN_LIGHT else EditorState.PLAN_CLASSIC
                        val before = EditorState.params.fontMode
                        EditorState.switchPlan(this, idx)
                        val after = EditorState.params.fontMode
                        DebugLog.log("AUTOTEST", "方案切换 idx=$idx 前fontMode=$before 后fontMode=$after")
                    }
                    spec.startsWith("logo2:") -> {
                        // v14.75 验证钩子：第二次导入不同文件（auto_logo2.png）→ 实证隔离
                        val f = File(getExternalFilesDir(null), "auto_logo2.png")
                        if (f.exists()) {
                            handleCustomLogo(android.net.Uri.fromFile(f))
                        } else {
                            DebugLog.log("AUTOTEST", "无 auto_logo2.png（请推送至 ${f.absolutePath}）")
                        }
                    }
                    spec.startsWith("logo:") -> {
                        // v14.58 根因修复：不再读 /sdcard（模拟器/FUSE 上 File.exists() 可无限挂起，
                        // 导入线程永远到不了业务链）——改读 App 自有外部目录（DebugLog 已验证可读写）
                        android.util.Log.i("LGW-AUTO", "hook logo: exists 检查前 dir=${getExternalFilesDir(null)}")
                        val f = File(getExternalFilesDir(null), "auto_logo.png")
                        val ex = f.exists()
                        android.util.Log.i("LGW-AUTO", "hook exists=$ex path=${f.absolutePath}")
                        if (ex) {
                            // 走真实 handleCustomLogo 全链（复制→解码→EXIF→PNG→自检→toast+日志）
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
                    // v14.61 阶段三：像素级折射位移自证——合成对角条纹背景（高空间频率），
                    // 折射关(对照)/旧模型(model0)/高度场+斯涅尔(model1) 三路渲染，统计 rim 带内
                    // 位移翻转像素占比。条纹被位移必然翻色→|Δ|大；对照≈0 → 证明"真采样位移"。
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
                            // 与 run2 内嵌几何一致：圆心 (44+76, 24+52)，半径 30
                            val gl = 44f; val gt = 24f; val gw = 152f; val gh = 104f; val rr = 30f
                            val hx = gw / 2f; val hy = gh / 2f
                            val qx = Math.abs(i - (gl + gw / 2f)) - (hx - rr)
                            val qy = Math.abs(j - (gt + gh / 2f)) - (hy - rr)
                            val ox = Math.max(qx, 0f); val oy = Math.max(qy, 0f)
                            // 标准圆角矩形 SDF：length(max(q,0)) − r + min(max(q.x,q.y),0)
                            val sd = Math.sqrt((ox * ox + oy * oy).toDouble()).toFloat() - rr +
                                Math.min(Math.max(qx, qy), 0f)
                            return sd < -0.5f && sd > -14f
                        }
                        fun run2(model: Int, refr: Int): Pair<Float, Float> {
                            EditorState.params.refractionModel = model
                            // 玻璃内嵌（gL/gT/gW/gH 四周留背景）：rim 带位移采样真实条纹而非图像边缘钳制
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
                    // v15.6 调试：dump 引擎输出像素（对照=条纹背景，折射/模糊/色散全关）——定位"水印纯黑"
                    spec == "dumpglass" -> {
                        val bw = 240; val bh = 152
                        val bg = android.graphics.Bitmap.createBitmap(bw, bh, android.graphics.Bitmap.Config.ARGB_8888)
                        val px0 = IntArray(bw * bh)
                        for (j in 0 until bh) for (i in 0 until bw) {
                            px0[j * bw + i] = if ((((i + j) / 9) % 2) == 0) 0xFFD8D8D8.toInt() else 0xFF202090.toInt()
                        }
                        bg.setPixels(px0, 0, bw, 0, 0, bw, bh)
                        // 探针1：compose 前直接读 bg 像素——确认 sharp 输入是否是条纹
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
                        // 玻璃内 (44..196, 24..128)：内部点 + 边缘带点
                        val pts = listOf(80 to 60, 100 to 70, 120 to 75, 150 to 80, 90 to 90, 50 to 30, 60 to 100)
                        for ((i, j) in pts) {
                            val idx = j * bw + i
                            DebugLog.log("DUMP", "($i,$j) src=#%08X out=#%08X a=%d".format(px0[idx], op[idx], (op[idx] ushr 24) and 0xFF))
                        }
                        // 原始背景对照（玻璃外）：(20,20)
                        DebugLog.log("DUMP", "outside(20,20) src=#%08X".format(px0[20 * bw + 20]))
                        runCatching { bg.recycle() }
                    }
                    // v15.16 任务3：离屏层录制/跳过计数（证明"每帧全量重录"已被消除）
                    //   adb shell am start -n com.liquidglass.watermark/.MainActivity \
                    //       --es ljgAutoTest "glassperf"
                    spec == "glassperf" -> {
                        DebugLog.log("GLASSPERF", "reset")
                        com.liquidglass.watermark.glass.GlassPerf.reset()
                        // v15.16：只有真正拖动参数滑块时这些节点才会绘制，所以每 2s 采样一次，
                        // 持续 40s，覆盖"点玻璃页→展开高级参数→滚动→拖动"的整段操作窗口。
                        // 采样到非零值后即可证明「每帧全量重录」是否真的被缓存消除。
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
                    // v15.16 导出管线基准：任意时长 × 两条路径（hybrid / cpu），CSV 落 bench/
                    //   adb shell am start -n com.liquidglass.watermark/.MainActivity \
                    //       --es ljgAutoTest "bench:5,14,15,16,30,60,300"
                    // v15.28 多帧率：第二段冒号后为帧率列表
                    //   --es ljgAutoTest "bench:5,10,15:15,25,30"（5/10/15 秒 × 15/25/30fps）
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
                    // 验证 shader 编译、帧数/时长、结果文件与失败原因（全部落日志）
                    spec == "exportgpu" || spec == "exportgpu_cut" -> {
                        val f = File(getExternalFilesDir(null), "auto_test.mp4")
                        if (!f.exists()) {
                            DebugLog.log("AUTOTEST", "无 auto_test.mp4（推送到 ${f.absolutePath}）")
                        } else {
                            val bytes = runCatching { f.readBytes() }.getOrNull()
                            if (bytes == null || bytes.size < 1024) {
                                DebugLog.log("AUTOTEST", "auto_test.mp4 读取失败")
                            } else {
                                // v15.10 验证钩子：exportgpu_cut 额外验证 5 秒截断（源>5s → 输出仅前 5s）
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
                                    watermarkRenderer = preview,   // GPU 路径（含新高度场/Snell shader）
                                    includeAudio = false,
                                    isCancelled = { false },
                                    maxDurationMs = cutMs
                                )
                                DebugLog.log("AUTOTEST",
                                    "exportgpu 结果: ${if (outF != null) "OK ${outF.length()}B fail=${Mp4Exporter.lastFailReason}" else "FAIL: ${Mp4Exporter.lastFailReason}"}")
                                // 验证用途：产物保留到外部目录（供播放校验），不再即删
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
                    // v15.16 验证钩子：motioncut —— 模拟手动面板（整段动图导入）只填 mpVideoUri、
                    // 主预览无素材的场景，走 composeMotionPhoto 全链路验证 >5s 自动截取前 5 秒
                    spec == "motioncut" -> {
                        val f = File(getExternalFilesDir(null), "auto_test.mp4")
                        if (!f.exists()) {
                            DebugLog.log("AUTOTEST", "无 auto_test.mp4（推送到 ${f.absolutePath}）")
                        } else {
                            mpSlot = 3
                            mpImage = null; mpImageSet = false
                            mpVideoUri = android.net.Uri.fromFile(f); mpVideoSet = true
                            // 同步补首帧封面（等价 importWholeMotionPhoto 的封面槽），否则导出前 mp_missing
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
                            // composeMotionPhoto 内部有 toast（需主线程），而 runAutoTest 跑在工作线程
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


    // 参数调节：滑块拖动只标记重绘；BlurCache 的键覆盖玻璃参数、几何、源图和动画帧，
    // draw 时会自行判断是否需要重算。每次拖动都 reset 会反复释放/新建两张工作位图并制造 GC 抖动。
    // 停手 80ms 落盘。
    private val heavy = Runnable {
        EditorState.persist(this)
    }
    fun bump() {
        rev++
        preview?.invalidate()
        ui.removeCallbacks(heavy)
        ui.postDelayed(heavy, 80)
        // 开启走秒后确保 tick 在跑（tick 空闲时不再自续，见 tick.run）
        if (EditorState.liveSeconds) {
            ui.removeCallbacks(tick)
            ui.postDelayed(tick, 0)
        }
    }
    // 文本输入：不重算模糊，只重绘 + 落盘
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
    /** 应用 API 模式：高=硬件模糊(API31+)，低=软件模糊，默认=按设备自动 */
    fun applyApiMode() {
        val mode = EditorState.params.apiMode
        // v15.18：立即写全局键，避免只靠 bump() 延迟落盘导致重启回落自动档、玻璃观感变淡
        EditorState.saveApiModeGlobal(mode)
        val sdk = android.os.Build.VERSION.SDK_INT
        // 模拟器全局已由 glass.Platform.isEmulator() 把 RenderEffect/RuntimeShader 门控置否、
        // 强制软件路径（与档位无关）；这里再同步软件模糊开关做冗余保险。
        val emulator = com.liquidglass.watermark.glass.isEmulator()
        val useSw = when (mode) {
            API_HIGH -> false
            API_LOW -> true
            else -> sdk < 31 || emulator
        }
        com.liquidglass.watermark.glass.backdrops.softwareBlurEnabled = useSw
        EditorState.params.softwareBlur = useSw
    }

    /** 应用内语言切换：写 prefs 并重建 Activity，attachBaseContext 下次按新语言加载资源 */
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
        // 回到前台才恢复走秒定时器，后台零定时器占用
        ui.removeCallbacks(tick)
        ui.postDelayed(tick, 1000)
    }

    override fun onDestroy() {
        super.onDestroy()
        // 页面销毁：取消进行中的导出，避免导出线程持有 Activity 引用泄漏到任务结束
        // （导出线程最长可达数分钟；Activity 已不可见时继续导出无意义且耗电）
        if (EditorState.isExporting || exporting.get()) requestCancelExport()
        ui.removeCallbacks(heavy)
        ui.removeCallbacks(tick)
        // v14.14 UI 泄漏修复：退出即释放单例持有的媒体位图（预览/动图帧/GIF/封面），
        // 否则进程驻留时这些位图一直占内存（典型 1080p 预览≈6MB，动图帧数百张更高）；
        // 同时避免重开 App 时残留显示上一张图（本应用不跨进程恢复选中照片）。
        val oldPv = EditorState.previewBitmap
        EditorState.previewBitmap = null
        if (oldPv != null && !oldPv.isRecycled) runCatching { oldPv.recycle() }
        EditorState.clearMotion()
        EditorState.isGif = false; EditorState.gifMovie = null; EditorState.gifBytes = null
        EditorState.webpBytes = null; EditorState.sourceUri = null
        EditorState.isDemo = true
        // 断开预览 View 引用，避免持有一整套渲染缓冲/位图直到进程回收
        preview = null
        // L9：若导出线程仍在渲染，clearBrandCache 回收品牌位图会与其 drawLogo 竞态 →
        // 用已 recycle 的 Bitmap 崩溃（窄窗口：恰在导出渲染时销毁 Activity）。此时跳过清理，
        // 位图随进程/单例释放；正常路径（非导出中）照常清理。
        if (EditorState.isExporting || exporting.get()) {
            DebugLog.log("LIFECYCLE", "onDestroy 时导出仍在进行，跳过 clearBrandCache 以防回收竞态")
        } else {
            WatermarkRenderer.clearBrandCache()
        }
    }

    // ---------- 走秒 ----------
    private val tick = object : Runnable {
        override fun run() {
            // 仅走秒开启时持续自续；关闭后不再每秒空转调度，减少前台无效唤醒
            if (EditorState.liveSeconds) {
                EditorState.params.dateText = EditorState.nowString()
                rev++
                preview?.invalidate()
                ui.postDelayed(this, 1000)
            }
        }
    }
    // ---------- 预设（全部基于开源 Backdrop 玻璃参数：blur/refract/chromatic/vibrancy/specular…） ----------
    fun applyPreset(i: Int) {
        val p = EditorState.params
        when (i) {
            0 -> { // iOS 高通透冷玻璃：胶囊+中等磨毛+边缘透镜+顶部冷光+零乳白+色散扩散
                p.shape = EditorState.SHAPE_CAPSULE
                p.blur = 18; p.refractH = 22; p.refractA = 24; p.chromatic = 12; p.dispersion = 18
                p.depthEffect = true; p.vibrancy = 40
                p.highlightStyle = EditorState.HL_DEFAULT; p.specular = 55; p.hlAngle = 45f; p.falloff = 1.1f
                p.tintColor = Color.WHITE; p.tintStrength = 0
                p.innerShadow = 26; p.shadow = 54; p.edgeLight = 66; p.opacity = 62
                p.textColor = Color.WHITE; p.layoutMode = EditorState.LAYOUT_CLASSIC
                p.logoStyle = EditorState.LOGO_DOT
            }
            1 -> { // VIVO OriginOS：胶囊+清透鲜活+微色散扩散+强边缘光+零乳白
                p.shape = EditorState.SHAPE_CAPSULE
                p.blur = 20; p.refractH = 24; p.refractA = 22; p.chromatic = 14; p.dispersion = 22
                p.depthEffect = true; p.vibrancy = 55
                p.highlightStyle = EditorState.HL_DEFAULT; p.specular = 58; p.hlAngle = 45f; p.falloff = 1.05f
                p.tintColor = Color.WHITE; p.tintStrength = 0
                p.innerShadow = 22; p.shadow = 38; p.edgeLight = 62; p.opacity = 96
                p.textColor = Color.WHITE; p.layoutMode = EditorState.LAYOUT_CLASSIC
                p.logoStyle = EditorState.LOGO_DOT
            }
            2 -> { // 小米 HyperOS：胶囊+清透+轻色散+强鲜活+单侧环境光+零乳白
                p.shape = EditorState.SHAPE_CAPSULE
                p.blur = 16; p.refractH = 26; p.refractA = 24; p.chromatic = 20; p.dispersion = 24
                p.depthEffect = true; p.vibrancy = 55
                p.highlightStyle = EditorState.HL_AMBIENT; p.specular = 52; p.hlAngle = -45f; p.falloff = 1f
                p.tintColor = Color.parseColor("#FFCFE4FF"); p.tintStrength = 0
                p.innerShadow = 24; p.shadow = 36; p.edgeLight = 52; p.opacity = 96
                p.textColor = Color.WHITE; p.layoutMode = EditorState.LAYOUT_STACK
                p.logoStyle = EditorState.LOGO_NONE
            }
            3 -> { // 华为 HarmonyOS：胶囊+均匀反光+清透高亮+无色散+零乳白
                p.shape = EditorState.SHAPE_CAPSULE
                p.blur = 20; p.refractH = 16; p.refractA = 14; p.chromatic = 0; p.dispersion = 0
                p.depthEffect = true; p.vibrancy = 50
                p.highlightStyle = EditorState.HL_PLAIN; p.specular = 60; p.hlAngle = 45f; p.falloff = 1f
                p.tintColor = Color.WHITE; p.tintStrength = 0
                p.innerShadow = 20; p.shadow = 34; p.edgeLight = 50; p.opacity = 96
                p.textColor = Color.WHITE; p.layoutMode = EditorState.LAYOUT_SINGLE
                p.logoStyle = EditorState.LOGO_CAMERA
            }
            4 -> { // 徕卡 Leica：直角矩形+低折射+厚重内阴影+暖白文字+弱边缘光+零乳白
                p.shape = EditorState.SHAPE_RECT
                p.blur = 12; p.refractH = 8; p.refractA = 8; p.chromatic = 0; p.dispersion = 0
                p.depthEffect = false; p.vibrancy = 20
                p.highlightStyle = EditorState.HL_DEFAULT; p.specular = 30; p.hlAngle = 60f; p.falloff = 1.3f
                p.tintColor = Color.BLACK; p.tintStrength = 0
                p.innerShadow = 50; p.shadow = 50; p.edgeLight = 35; p.opacity = 96
                p.textColor = Color.parseColor("#FFF6E8"); p.layoutMode = EditorState.LAYOUT_CLASSIC
                p.logoStyle = EditorState.LOGO_CAMERA
            }
            5 -> { // Crystal 水晶通透：胶囊+零乳白+强折射+高反光+锐利棱边+低模糊+强色散扩散
                p.shape = EditorState.SHAPE_CAPSULE
                p.blur = 8; p.refractH = 34; p.refractA = 32; p.chromatic = 18; p.dispersion = 32
                p.depthEffect = true; p.vibrancy = 35
                p.highlightStyle = EditorState.HL_DEFAULT; p.specular = 72; p.hlAngle = 45f; p.falloff = 0.9f
                p.tintColor = Color.WHITE; p.tintStrength = 0
                p.innerShadow = 14; p.shadow = 28; p.edgeLight = 78; p.opacity = 96
                p.textColor = Color.WHITE; p.layoutMode = EditorState.LAYOUT_CLASSIC
                p.logoStyle = EditorState.LOGO_NONE
            }
            6 -> { // vitremark 同款：右下角圆形半透明玻璃 logo 水印（轻模糊+明显描边+低透明度，模仿成片）
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
                p.logoAlign = 2   // 居中：logo 在玻璃条正中（模仿成片圆内居中）
                p.relX = 0.85f; p.relY = 0.8f
                p.scalePct = 65; p.wmLengthPct = 0.01f
                p.borderWidth = 2.5f; p.borderAlpha = 220
            }
        }
        refreshHard()
    }

    /** 照片旋转90°（预览与导出同步，照片和视频帧都会旋转） */
    fun rotatePhoto() {
        EditorState.photoRotation = (EditorState.photoRotation + 90) % 360
        refreshHard()
    }

    // ---------- 选图（图片 / GIF / 视频，兼容 vivo 双文件动态照片） ----------
    private val mediaPicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            onPicked(r)
        }

    // v15.31 系统相册 Photo Picker（真·相册界面：缩略图九宫格，支持图片+视频）。
    // 比 ACTION_OPEN_DOCUMENT 的文件选择器更符合"相册选择"预期；API33+ 原生，
    // 旧版本由 Google Play 服务回退，完全不可用时回退文件选择器。
    private val galleryPicker =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri == null) { return@registerForActivityResult }
            handlePickedUri(uri)
        }

    // v15.37：相册入口支持「一次多选」——用户可直接勾选 1 张照片 + 1 段视频，
    // 由本应用自动配对成动态照片（此前只能单选一个文件，必须绕去「动态照片」面板分两次选，太麻烦）。
    private val galleryMultiPicker =
        registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(2)) { uris ->
            onPickedMulti(uris)
        }

    // v15.37：文档型多选（ACTION_OPEN_DOCUMENT + EXTRA_ALLOW_MULTIPLE）—— 一定支持多选。
    // 实测部分机型/模拟器的 Photo Picker 即便 maxItems=2 仍是「点一下就返回」的单选行为，
    // 用户会以为"还是只能选一个"。这里改用系统文件选择器（可同时勾 1 图 + 1 视频），
    // 完全不依赖各家相册 UI 的多选实现差异。
    private val docMultiPicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            if (r.resultCode != RESULT_OK) return@registerForActivityResult
            val uris = ArrayList<Uri>()
            val clip = r.data?.clipData
            if (clip != null) for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
            r.data?.data?.let { if (uris.isEmpty()) uris.add(it) }
            onPickedMulti(uris)
        }

    /** v15.37：一次多选（1 张照片 + 1 段视频）直接配成动态照片——走系统文件多选，任何机型都支持多选 */
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
        // 极端情况下没有文档选择器：退回 Photo Picker 多选请求
        runCatching {
            galleryMultiPicker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
            )
        }.onFailure { pickMedia() }
    }

    /** v15.31：从系统相册选择照片或视频（画布空态"选择照片或视频"按钮的归一化入口） */
    fun pickMediaFromGallery() {
        if (EditorState.isExporting) { toast(getString(R.string.export_busy)); return }
        maybeTipVivo()
        // v15.37：相册入口默认仍是 Photo Picker（各家相册的九宫格最直观）；
        // 想「一次选 1 图 + 1 视频」配对动态照片，用空态下方的独立"照片+视频配对"入口（pickPairMedia），
        // 那里走文档型多选，绝不依赖各家相册是否实现多选。
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
            // Photo Picker 不可用（极老设备 + 无 Play 服务）：回退文件选择器
            pickMedia()
        }
    }

    // 自定义预览衬底图：只选静态图，独立于主媒体链路，设置后作为无照片时的预览背景
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

    // ---------- 用户预设：导入 JSON 文件 / 分享 JSON 给他人 ----------
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

    /**
     * 导入视频（MP4 等）。
     * v15.30 修复"功能错位"：不再强制把导出格式切到 GIF——用户选 MP4/HEVC 时导入视频
     * 就按所选格式导出；只有从「GIF」工具面板进入（pickVideoToGif / openGifPanel）才用 GIF。
     */
    fun pickVideo() {
        // v15.30：去掉 `motionExportFormat = "gif"` 强切（旧逻辑导致选 MP4 导入视频跳到 GIF）
        // 用 */* + EXTRA_MIME_TYPES 而非 video/*：部分 ROM 对 type=video/* 的选择器返回空结果
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

    /** 动态照片合成：选封面图片（槽位1） */
    fun pickMotionPhotoImage() {
        mpSlot = 1
        pickMedia()
    }

    /** 动态照片合成：选视频片段（槽位2） */
    fun pickMotionPhotoVideo() {
        mpSlot = 2
        pickVideo()
    }

    /** v14.39 视频转动图独立入口：强制 GIF 导出格式后选视频（无需先选封面） */
    /** v15.30：点导出栏「GIF」立即弹出 GIF 工具面板（参数 + 选视频），同时把格式切到 GIF */
    fun openGifPanel() {
        EditorState.motionExportFormat = "gif"
        EditorState.persist(this)
        EditorState.bumpExportBar()
        showMotionPhotoPanel = false
        showGifPanel = true
    }

    /** GIF 工具面板：选视频（显式入口，锁定 GIF 格式） */
    fun pickVideoToGif() {
        mpSlot = 0
        EditorState.motionExportFormat = "gif"
        pickVideo()
    }

    /** GIF 工具面板：已导入视频后导出 GIF（=主保存路径，格式已锁定 gif） */
    fun exportGifFromPanel() {
        showGifPanel = false
        doSave()
    }

    /** v14.38 整段动图导入：选【一个】文件（视频或图片）自动填满封面+视频两槽 */
    fun pickWholeMotionPhoto() {
        mpSlot = 3
        pickMedia()
    }

    /** 动图面板：清空已选封面（保留视频） */
    fun clearMotionCover() {
        mpImage = null
        mpImageSet = false
        toast(getString(R.string.cover_cleared))
    }

    /** 动图面板：清空已选视频片段（保留封面） */
    fun clearMotionVideo() {
        mpVideoUri = null
        mpVideoSet = false
        toast(getString(R.string.video_cleared))
    }

    /** 主界面：删除已导入视频（清空动图源与预览，回到可继续编辑状态，无需退出应用） */
    fun deleteImportedVideo() {
        val hasMotion = EditorState.isGif || EditorState.motionMp4 != null ||
            (EditorState.motionFrames?.size ?: 0) >= 2 || mpVideoSet || mpImageSet
        if (!hasMotion) { toast(getString(R.string.no_video_to_delete)); return }
        // 若当前预览是动图首帧/封面/视频帧，先断开并回收（回到无图状态）
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

    /** 主界面：删除已导入的静态图片（回到无图占位状态，无需退出应用；与删除视频互斥场景） */
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

    /** 合成动态照片：封面加水印 → 视频逐帧加水印转码 → vivo 双文件（jpg+mp4 同名配对）→ 保存相册。
     * 素材来源优先级：主预览已载入的运动内容（选动态照片自动配对）> 手动面板（选封面+视频）。
     * 主预览有内容时直接合成，不强制手动选两个素材。导出互斥，进行中忽略再次点击。
     */
    fun composeMotionPhoto() {
        if (EditorState.isExporting) { toast(getString(R.string.export_busy)); return }
        // v15.24：授权后回到本入口（旧 launcher 回调恒执行 doSave()，合成动作丢失）
        if (!ensureStoragePermission { composeMotionPhoto() }) return
        if (!exporting.compareAndSet(false, true)) { toast(getString(R.string.export_busy)); return }
        try {
            // v15.10 动照片规范：视频超 5 秒【自动截取前 5 秒】生成（不再拒绝）；
            // ≤5 秒走原逻辑；任何时长都必须产出成功结果，提示不替代结果
            // v15.16 修复：手动面板（整段动图导入/只选视频）只写 mpVideoUri，
            // 旧判定只看主预览 motionUri/motionMp4 → 恒 -1 → >5s 视频静默不截断。
            // 现值来源 = 主预览 uri（最权威）> 主预览字节 > 手动面板 uri，与
            // composeMotionPhotoNow 实际消费素材的顺序（mainMp4 优先 manualVid）保持一致。
            val vDur = videoDurationMs(
                EditorState.motionUri ?: mpVideoUri.takeIf { EditorState.motionMp4 == null },
                EditorState.motionMp4)
            // v15.30：动照支持【剪辑时间轴】——未自定义时保持规范（超 5 秒自动截前 5 秒）；
            // 自定义后按用户所选起点 + 片段导出（片段超出源时长自动收窄到片尾）。
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
        // 主预览已载入：视频源 + 封面（面板封面优先，其次当前预览帧）
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
                // 封面位图：手动面板封面 > 主预览封面
                var coverBmp: android.graphics.Bitmap
                var coverBytes: ByteArray? = null  // 手动面板原图字节（ID 提取用）
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
                // 视频方向对齐：读源视频权威 rotation + 容器方向，封面与视频输出方向不一致时旋转封面
                // （vivo 相册播放动态照片按封面比例渲染视频，方向不一致会显示变形/比例怪异）
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
                            // 大视频直接 Uri 探测，不整读内存
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
                // 动态照片方向以封面为准，视频必须跟随封面。按视频"像素方向 + 容器 rotation"区分三种竖屏来源：
                //  像素已竖仅残留 rot 标记 → 不强制（再转反而横）；横像素+有效 rot(90/270，含倒持) → 尊重元数据；
                //  横像素无 rot（vivo iQOO 动照 MP4）→ 强制 90。
                val coverPortrait = coverBmp.height > coverBmp.width
                // 真动态照片判定：视频时长 ≤15s 才做"封面竖屏→视频强制旋转"推断；
                // 长视频（游戏录像/整段视频）封面竖屏时绝不推断旋转（会把横屏视频拉成竖屏）
                val forceRot = if (videoDurationMs(manualVid, mainMp4) in 1..15_000L) {
                    MotionDecoder.inferForceRotation(
                        coverPortrait, vidRotInfo.first, vidRotInfo.second, vidRotInfo.third)
                } else -1
                EditorState.motionForceVideoRot = forceRot
                if (coverPortrait) {
                    DebugLog.log("COMPOSE_MOTION",
                        "封面竖屏 → 视频强制 rotation=$forceRot（容器=${vidRotInfo.second}x${vidRotInfo.third} metaRot=${vidRotInfo.first}）")
                } else {
                    // 封面横屏：走视频方向对齐（标准横屏视频，封面不转）
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
                // 封面比例对齐视频显示比例（center-crop 保持比例不变形，否则 vivo 相册播放变形/比例怪异）。
                // 封面竖屏按推断出的实际旋转算（-1=像素已竖按 0），否则按视频归一化旋转
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
                // 识别是否为 vivo/iQOO 动态照片：解析 jpg/mp4 尾部的 live_photo_id，
                // 配对一致则复用原 ID（尽可能保留原动态照片关联信息）。大视频不整读，跳过 ID 复用
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
                // 封面叠加当前水印（与静态导出同渲染路径，所见即所得）
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
                // 视频逐帧叠加同一水印参数（与封面一致）→ 转码为带水印 MP4。
                // 大视频（>128MB）不整读内存：Uri 流式导出；小视频沿用字节数组
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
                        // 帧已由 Mp4Exporter 按 forceVideoRotation 转正，禁止 renderInner 二次旋转
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
                    // v14.49：mode 0=快速(纯GPU) 1=混合 2=纯CPU(不传渲染器→逐帧软件渲染)
                    watermarkRenderer = if (EditorState.params.exportMode == 2) null else preview,
                    // 动态照片视频轨不含音频（Google/vivo 动照片标准），避免破坏生态识别
                    includeAudio = false,
                    // v15.10 动照片 5 秒截断：超长视频自动只导出前 5 秒（motionMaxMs>0 时生效）
                    // v15.30 剪辑：起点 + 片段长度同时生效（startPtsUs 控制 seek，maxDurationMs 按片段内计时）
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
                // 输出格式分流：0=vivo/iQOO 私有双文件；1=Google Motion Photo XMP 单 JPEG。
                // OEM 相册是否识别须按设备验证；封装失败时保留普通 JPEG 封面与独立 MP4。
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
                    // 无论成功失败都清掉转码临时文件，防缓存堆积
                    runCatching { wmFile.delete() }
                }
                publishProgress(100)
                ui.postDelayed({
                    progressPct = -1
                    clearExportCacheLight()
                    // 重置动照面板：下次打开显示"选封面图片/选视频片段"，不残留已选状态
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

    /**
     * 封面解码兜底链（委托 BitmapUtils.decodeCover）：采样防 OOM + EXIF Orientation 转正
     * （竖屏照片不再横屏）+ ImageDecoder 兜底（HEIF）。失败返回 null。
     */
    private fun decodeCoverSafely(bytes: ByteArray): android.graphics.Bitmap? =
        BitmapUtils.decodeCover(bytes)

    /** 自定义品牌 logo：从相册选一张透明底图片，复制到内部存储供渲染使用 */
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
            // v14.76 隔离构造级修复：savedDst 捕获实际写入的文件（成功块强制用它做 active+条目，
            // 杜绝 append 时读到旧 active 路径）
            var savedDst: String? = null
            val err: String? = runCatching {
                // v14.74 隔离修复：每个自定义 logo 独立文件名（此前共用 custom_logo.png →
                // 后导入覆盖先导入、删一个全没——"没隔离"根因）
                val dst = File(filesDir, "custom_logo_${System.currentTimeMillis()}.png")
                // 1) 复制原始文件到临时位置（保留 EXIF 供转正）
                val srcFile = File(filesDir, "custom_logo_src.${System.currentTimeMillis()}")
                DebugLog.log("LOGO", "STEP1 复制开始")
                contentResolver.openInputStream(uri)?.use { ins ->
                    srcFile.outputStream().use { outs -> ins.copyTo(outs) }
                } ?: return@runCatching "读取相册文件失败"
                DebugLog.log("LOGO", "STEP2 复制完成 size=${srcFile.length()}")
                // 2) 解码（降采样 ≤512 防 OOM）——任何相册格式（JPEG/PNG/WebP/HEIC）都先转成位图
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
                // 3) EXIF 方向转正（v14.30：相册竖拍照片 EXIF 90°→ 直接画横向错位）
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
                // 3) EXIF 方向转正 + 等比缩到 ≤512（任何情况下得到可编码的 final 位图）
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
                // 4) 重编码为 PNG（v14.51 根治：任意格式归一化为 PNG，Android 全版本必然可解码）
                //    v14.53 原子写：先写临时文件再 rename——写中断绝不产生半文件（写坏 dst 会
                //    让下次导入的"旧图覆盖失败/校验通过但解码错"类问题无从排查）
                val tmpPng = File(filesDir, "custom_logo.tmp.png")
                DebugLog.log("LOGO", "STEP5 PNG 编码开始")
                tmpPng.outputStream().use { outs -> cur.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, outs) }
                DebugLog.log("LOGO", "STEP6 PNG 编码完成 size=${tmpPng.length()}")
                runCatching { if (!cur.isRecycled) cur.recycle() }
                if (!tmpPng.renameTo(dst)) {
                    // rename 失败（罕见：跨存储/占用）：回退复制覆盖
                    runCatching { tmpPng.copyTo(dst, overwrite = true) }
                    runCatching { tmpPng.delete() }
                }
                runCatching { srcFile.delete() }
                // 5) 验证归一化后的 PNG 可解码（写入不完整必现）
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
                    // v14.44 重写：导入成功→【立即持久化】（不再依赖命名框确定，取消对话框也不丢）
                    // v14.76：用 savedDst 强制 active 指向本次实际写入的文件（隔离构造级保证）
                    // v14.84 精简：合并双重 persist——下方 append 后的一次写入同时保存 active+条目列表
                    savedDst?.let { EditorState.params.customLogoPath = it }
                    WatermarkRenderer.clearBrandCache()
                    refreshHard()
                    // v14.44 自检：立即实测整条渲染链，成功/失败直接 toast（不再静默）
                    val check = WatermarkRenderer.debugSelfTestLogo()
                    // v14.56：结果同时落日志（自动化验证需要，不再只看 toast）
                    DebugLog.log("LOGO", "导入成功（自检: $check）path=${EditorState.params.customLogoPath}")
                    toast(getString(R.string.custom_logo_set) + "（自检: $check）")
                    // v14.70 多自定义 logo：本次导入追加为一条新条目（保留旧的；每个条目独立 形状/裁剪）
                    // v14.98 修复：条目名用「新鲜占位名（自定义N）」，绝不沿用上一个 logo 的残留名字——
                    // 命名对话框确定后才会替换成用户输入的名字
                    EditorState.params.customLogos.add(EditorState.CustomLogoEntry(
                        "自定义${EditorState.params.customLogos.size + 1}",
                        EditorState.params.customLogoPath,
                        EditorState.params.logoShape,
                        EditorState.params.logoCrop))
                    EditorState.persist(this)
                    // v14.76 隔离实证日志：append 之后打印（条目数 + 各自独立路径）
                    DebugLog.log("LOGO", "ISOLATE count=${EditorState.params.customLogos.size} " +
                        "paths=${EditorState.params.customLogos.joinToString("|") { it.path }}")
                    showLogoNameDialog()
                } else {
                    DebugLog.log("LOGO", "导入失败：$err")
                    toast(getString(R.string.custom_logo_fail) + "（$err）")
                }
            }
        }.start()   // v14.60 致命修复：缺 .start() 线程从不运行——自定义 logo "永远没修好"的真根因
    }

    /** v14.30 导入成功后命名对话框（可自定义名字；确定即保存） */
    private fun showLogoNameDialog() {
        // v14.37：先选形状/裁剪（正方形/长方形/圆形/原图不裁剪），再命名
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
                EditorState.params.logoCrop = which != 3   // 原图→不裁剪
                // v14.98 修复：形状选择同步写回本次导入的条目（此前条目里存的还是导入前的旧形状，
                // 重启/切换后会变回错误形状）；命名对话框里也同步名字
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

    /** 命名输入（在形状选择之后） */
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
                // v14.70：同步更新当前条目的名字（最后追加的那条）
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

    /** v14.30 删除自定义 logo（长按自定义 logo 项触发）：清路径/名字，回落无 logo */
    fun deleteCustomLogo() {
        if (EditorState.params.customLogoPath.isEmpty()) return
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.logo_delete_title))
            .setMessage(getString(R.string.logo_delete_confirm) +
                (EditorState.params.customLogoName.takeIf { it.isNotBlank() }?.let { "（$it）" } ?: ""))
            .setPositiveButton(getString(R.string.delete_btn)) { _, _ ->
                runCatching { File(EditorState.params.customLogoPath).delete() }
                // v14.70：从多条目列表移除当前条目（删一个不影响其他自定义 logo）
                val activePath = EditorState.params.customLogoPath
                EditorState.params.customLogos.removeAll { it.path == activePath }
                EditorState.params.customLogoPath = ""
                EditorState.params.customLogoName = ""
                EditorState.params.logoBrand = EditorState.BRAND_NONE
                // v14.98 修复：删当前 logo 后形状/裁剪一并归位——否则残留的"方框"形状会传染给下一个导入
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

    // v14.74 删除指定自定义条目（预设栏每个自定义方框长按触发）：只删该条目，不影响其他
    fun deleteCustomLogoEntry(entry: EditorState.CustomLogoEntry) {
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.logo_delete_title))
            .setMessage(getString(R.string.logo_delete_confirm) + "（${entry.name}）")
            .setPositiveButton(getString(R.string.delete_btn)) { _, _ ->
                runCatching { File(entry.path).delete() }
                EditorState.params.customLogos.removeAll { it.path == entry.path }
                // 若删的是当前选中项：回落无 logo
                if (EditorState.params.customLogoPath == entry.path) {
                    EditorState.params.customLogoPath = ""
                    EditorState.params.customLogoName = ""
                    EditorState.params.logoBrand = EditorState.BRAND_NONE
                    // v14.98 修复：删当前 logo 后形状/裁剪一并归位，防止残留形状传染下一个导入
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

    /** 切换到指定自定义 logo 条目（独立 形状/裁剪/路径），立即生效 */
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

    /** v14.38 整段动图导入：选【一个】文件自动填满封面+视频两槽（视频→首帧封面；图片→同名 mp4 配对） */
    private fun importWholeMotionPhoto(uri: android.net.Uri) {
        val name = runCatching {
            contentResolver.query(uri,
                arrayOf(android.provider.MediaStore.MediaColumns.DISPLAY_NAME,
                    android.provider.MediaStore.MediaColumns.MIME_TYPE),
                null, null, null)
        }.getOrNull()?.use { c -> if (c.moveToFirst()) c.getString(0) to c.getString(1) else (null to null) }
        val mime = name?.second ?: contentResolver.getType(uri)
        if (mime?.startsWith("video/") == true) {
            // 视频 → 存为动视频 + 首帧做封面
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
                        // 帧位图直接作为封面/预览（视频帧已是正确方向；mpImage 压缩为 JPEG 供合成复用）
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
            // 图片 → 存为封面 + 找同名 .mp4 自动配对（vivo 双文件/下载整段动图）
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

    /** 是否国产双文件动态照片常见厂商（配对失败时给提示，避免静默静态图） */
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
        // v15.30：预览解码上限跟随 GIF 时间轴（起点+片段长度）；非 GIF 不过早截断。
        // 片段起点偏移大于 0 时起点前不预览（导出会 seek，预览保持一致）。
        val previewMaxMs = if (isGifExport) {
            // 时间轴已自定义：从起点解到片段终点；未自定义：默认前 5 秒（兼容旧行为）
            if (EditorState.gifTrimCustomized)
                (EditorState.gifTrimStartMs + EditorState.gifTrimDurationMs).coerceAtLeast(1L)
            else VideoGifTimeline.MAX_DURATION_MS.toLong()
        } else 0L
        Thread {
            val duration = videoDurationMs(uri, null)
            // v15.31：导入时把 content:// 复制【一次】到 cacheDir 并缓存路径 ——
            // 修复 550MB 大视频 GIF 导出"动态片段解码失败"：旧逻辑 GIF 导出再复制第二次
            // （第二次打开 content:// 流失败/超时 → 静默失败且无日志）。
            // 现在预览与导出统一复用这个本地文件（file:// 直读，零重复复制）。
            val localFile = java.io.File(cacheDir, "lgw_media_${System.currentTimeMillis()}.mp4")
            val localOk = if (uri.scheme.equals("file", true)) {
                // 已是本地文件：直接引用，不复制
                EditorState.motionLocalPath = uri.path
                true
            } else {
                val copied = MotionDecoder.copyUriToFile(this, uri, localFile)
                if (copied && localFile.length() > 0) {
                    EditorState.motionLocalPath = localFile.absolutePath
                    true
                } else {
                    runCatching { localFile.delete() }
                    // 复制失败：记录原因不静默
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
            // 部分 ROM 对 createChooser 包装的选择器返回空 data：明确提示，避免"点了没反应"
            toast(getString(R.string.pick_failed))
            return
        }
        handlePickedUri(uri)
    }

    /**
     * v15.31：收到一个被选中的媒体 Uri 后的统一处理。
     * 三个入口共用：
     *  - 旧文件选择器（mediaPicker → onPicked）
     *  - 系统相册 Photo Picker（galleryPicker，见 [pickMediaFromGallery]）
     * 按 mime 分发：视频 → importVideoUri；GIF/动图/图片 → 走 onPicked 的剩余原有逻辑。
     */
    /**
     * v15.37：相册多选结果分发。
     *  - 1 个 → 走原有单素材导入（图片/GIF/视频）
     *  - 1 图 + 1 视频 → 自动配对成动态照片（封面 + 视频），并打开动态照片面板
     *  - 其它组合（2 图 / 2 视频）→ 提示后只导入第一个，避免静默丢文件
     */
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

    /** v15.37：把用户一次选中的「照片 + 视频」直接配对成动态照片（等价手动面板两次选材） */
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
                // 封面就绪后再导入视频：importVideoUri 会把 motionCoverBitmap 作为动态照片封面一起带过去
                importVideoUri(videoUri)
                showMotionPhotoPanel = true
                toast("已配对为动态照片（封面 + 视频），点「合成」即可导出")
            }
        }.start()
    }

    private fun handlePickedUri(uri: Uri) {
        // 自定义品牌 logo 选择：直接进入 logo 导入，不当作主图
        if (pendingLogoPick) {
            pendingLogoPick = false
            handleCustomLogo(uri)
            return
        }
        // 动态照片槽位选择：只存储引用，不进入主编辑流程
        if (mpSlot != 0) {
            val slot = mpSlot
            mpSlot = 0
            if (slot == 3) {
                // v14.38 整段动图导入：选【一个】文件自动填满封面+视频两槽——
                // 视频→用其首帧做封面；图片→找同名 .mp4 自动配对（vivo 双文件/下载整段动图）
                importWholeMotionPhoto(uri)
            } else if (slot == 1) {
                // 封面：读字节（图片较小，合成时需加水印渲染）
                Thread {
                    val b = runCatching { BitmapUtils.readBytes(this, uri) }.getOrNull()
                    runOnUiThread {
                        if (b == null) { toast(getString(R.string.pick_failed)); return@runOnUiThread }
                        mpImage = b
                        mpImageSet = true
                        // 同步主编辑状态：封面作为动图封面与预览，回主界面导出不再退化为静态 JPG
                        // 走 decodeCover：采样防 OOM + EXIF Orientation 转正（竖屏照片不再横屏）
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
            // 选新图时重置照片旋转与动态解码失败标志
            EditorState.photoRotation = 0
            EditorState.motionDecodeFailed = false
            val bytesR = runCatching { BitmapUtils.readBytes(this, uri) }
            if (bytesR.isFailure) {
                runOnUiThread { toast(bytesR.exceptionOrNull()?.message ?: "图片读取失败，请换一张试试") }
                return@Thread
            }
            val bytes = bytesR.getOrThrow()
            // GIF 魔数检查最优先：任何 ROM 把 GIF 报成 video/* 也不会被下面的视频分支误拦
            if (BitmapUtils.looksLikeGif(this, uri, bytes)) {
                val gifR = runCatching { BitmapUtils.decodeGif(bytes) }
                gifR.onSuccess { gi ->
                    runOnUiThread {
                        pendingImageUri = null
                        EditorState.clearMotion()
                        EditorState.gifMovie = gi.movie; EditorState.isGif = true
                        EditorState.gifBytes = bytes
                        EditorState.gifDuration = gi.duration
                        // 换 GIF 源：旧静态预览不再被引用（onDraw 实时读 previewBitmap），立即回收防泄漏
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
            // 动态 WebP：Android 9+ 系统解码器原生支持（AnimatedImageDrawable），抓帧转动图
            // mime 兜底：只要 contentResolver 报 image/webp 就尝试系统解码，不依赖字节探测（非标准工具生成的 WebP 可能 VP8X/ANIM 结构异常）
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
                            EditorState.clearMotion()   // 清旧帧/mp4；webpBytes 由下方重新赋值
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
                            // 系统解码器无法解码该 WebP（非标准/损坏/静态 WebP）：回退静态图，不静默失败
                            fallbackStaticFromWebP(bytes, uri)
                        }
                    })
                return@Thread
            }
            // 视频直选（mime=video/* 或 ftyp 开头）：
            // 另补一类：JPEG 开头、尾部或 XMP 声明内嵌视频的单文件输入（例如 .MP.jpg），
            // looksLikeMp4 只认 ftyp 开头，此类文件会被当静态图——必须抽内嵌视频走动态路径
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
                    // 后台线程确定封面：已有静态图片预览则直接用；
                    // 否则反向在 MediaStore 找同名/时间接近的配套图片做封面（用户可能先选了视频条目）
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
                    // 动态照片视频方向推断：视频无 rotation 且帧横屏 + 封面 JPG 竖屏 → 强制 90 重解
                    // （vivo/iQOO 动态照片 MP4 像素横屏+无 rotation 元数据，标准解码必然横屏，需从封面方向推断）
                    // vivo/iQOO 动照：视频像素横屏+无 rotation、封面竖屏 → 强制 90 重解，
                    // 且重解出的竖屏帧必须真正用于预览/封面（原 frFixed 算了却没用 → 导入仍显横屏、封面变形）
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
                            // 重解成功：横屏旧帧已不再使用，全部回收防 native 泄漏
                            fr.frames.forEach { runCatching { if (!it.isRecycled) it.recycle() } }
                            re
                        } else {
                            // 重解失败（该设备 retriever 慢）：手动旋转预览帧对齐导出方向（rotation=90），
                            // 否则预览横屏/导出竖屏，比例不一致（用户反馈预览比例不对）
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
                    // 换静态图：旧预览不再被引用（onDraw 实时读 previewBitmap），立即回收防泄漏
                    EditorState.previewBitmap?.let { if (!it.isRecycled) runCatching { it.recycle() } }
                    EditorState.previewBitmap = info.bitmap
                    EditorState.sourceUri = uri; EditorState.isDemo = false
                    // v15.18：用户自定义机型后，EXIF 拍摄机型不得覆盖（否则导入照片即还原）
                    if (!EditorState.modelCustom) info.model?.takeIf { it.isNotBlank() }?.let {
                        val mk = info.make?.trim()?.replaceFirstChar { c -> c.uppercase() }
                        EditorState.params.model =
                            if (!mk.isNullOrBlank() && !it.contains(mk, ignoreCase = true))
                                "$mk $it".trim() else it.trim()
                    }
                    info.shotTime?.let { EditorState.params.dateText = it }
                    // v15.32：geoMode=EXIF 时自动从相册原文件读取 GPS 参数
                    if (EditorState.params.geoMode == EditorState.GEO_EXIF) {
                        (runCatching { GeoUtils.readExifGeo(this, uri) }.getOrNull())?.let {
                            EditorState.params.geoText = it
                        }
                    }
                    refreshHard()
                    // 双保险：mime 报 webp 但上方 WebP 分支未走时（mime 变量偶发 null），事后再试一次系统解码；
                    // 成功→替换为动图预览，失败→静默保持静态（不弹旧提示）
                    if (BitmapUtils.isOtherAnimated(this, uri))
                        tryWebpDecodeAfterStatic(bytes, uri)
                }
                val mp4 = runCatching { MotionPhoto.extractMp4(bytes) }.getOrNull()
                if (mp4 != null) {
                    loadMotion(mp4, bytes)
                } else {
                    // vivo/国产双文件动态照片：选择器里静态图与视频是两张，自动找配套视频
                    val companion = runCatching { BitmapUtils.findCompanionVideo(this, uri) }.getOrNull()
                    if (companion != null) {
                        val vBytes = runCatching { BitmapUtils.readBytes(this, companion) }.getOrNull()
                        if (vBytes != null) loadMotion(vBytes)
                    } else if (isCnmManufacturer()) {
                        // 自动配对没找到：写诊断日志（文件名 + 系统视频表条目数），并明确提示可再选"视频"条目
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

    /** 解码失败诊断后缀：显示尝试过的解码方案，避免"点了没反应" */
    private fun diag(): String {
        val r = MotionDecoder.lastFailReason
        return if (r.isBlank()) "" else "\n(" + r + ")"
    }

    // ---------- v15.32 地理位置 ----------
    /** 手动读取当前照片原文件的 EXIF GPS 参数（用户要求：读取相册原文件里的地理参数） */
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

    /** 使用当前设备位置（需位置权限；未授权先请求，授权后继续） */
    fun useCurrentLocation() {
        val hasFine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        val hasCoarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) {
            // permLauncher 只支持单权限：请求 FINE（Android 9- 一并授予 COARSE）
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
            // v15.35：先反查真实地名（省/市/区县/街道），失败才退回经纬度
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

    /** v15.35：在“真实地名 / 经纬度”之间切换，用已缓存的坐标，不必重新定位 */
    fun applyGeoStyle(style: Int) {
        val p = EditorState.params
        p.geoStyle = style
        if (p.geoMode == EditorState.GEO_OFF || p.geoMode == EditorState.GEO_CUSTOM) { bump(); return }
        if (p.geoLat == 0.0 && p.geoLng == 0.0) {
            // 旧版本只存了文本、没有坐标：重新定位一次（按新选的写法填充）
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

    /** 清除地理位置显示（GEO_OFF） */
    fun clearGeo() {
        EditorState.params.geoMode = EditorState.GEO_OFF
        EditorState.params.geoText = ""
        bump()
    }

    /** WebP 系统解码失败后回退静态图：解码首帧做预览，标记源为静态，不弹"已按静态图处理"（已尝试过动图解码） */
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
            // v15.18：WebP 兜底路径同样不得覆盖用户自定义机型
            if (!EditorState.modelCustom) info.model?.takeIf { it.isNotBlank() }?.let {
                val mk = info.make?.trim()?.replaceFirstChar { c -> c.uppercase() }
                EditorState.params.model =
                    if (!mk.isNullOrBlank() && !it.contains(mk, ignoreCase = true))
                        "$mk $it".trim() else it.trim()
            }
            info.shotTime?.let { EditorState.params.dateText = it }
            // v15.32：geoMode=EXIF 时自动从相册原文件读取 GPS 参数
            if (EditorState.params.geoMode == EditorState.GEO_EXIF) {
                (runCatching { GeoUtils.readExifGeo(this, uri) }.getOrNull())?.let {
                    EditorState.params.geoText = it
                }
            }
            refreshHard()
            toast(getString(R.string.webp_decode_fail))
        }
    }

    /** 静态分支兜底：mime 报 webp 但上方 WebP 分支未走时，事后再试一次系统解码；成功→替换为动图预览，失败→静默保持静态 */
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
                    // 静默：保持静态预览，不弹提示
                }
            })
    }

    /** 解码视频字节为动图帧序列：成功→预览+导出用，失败→标记并在保存时明确提示，绝不静默降级 */
    private fun loadMotion(mp4: ByteArray, whole: ByteArray? = null) {
        EditorState.motionMp4 = mp4
        var fr = runCatching { MotionDecoder.decode(this, mp4, 720, 12, 60, maxDurationMs = 5000L) }.getOrNull()
        // 裁剪出的视频段不完整时（部分厂商封装不规范），尝试把整个原文件当视频解码
        if (fr == null && whole != null && whole !== mp4) {
            fr = runCatching { MotionDecoder.decode(this, whole, 720, 12, 60, maxDurationMs = 5000L) }.getOrNull()
        }
        // vivo/iQOO 动态照片视频：像素横屏+无 rotation 元数据（样本实测 1440×1080 无 rotation），
        // 标准解码必然横屏。若封面 JPG 竖屏，则视频应与封面同方向竖屏显示 → 强制 rotation=90 重解
        // 仅限真动态照片（视频时长 ≤15s）：长视频绝不强制旋转（误判会把横屏视频拉成竖屏）
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
                // 重解成功：回收横屏旧帧防 native 泄漏
                if (oldFr != null) oldFr.frames.forEach { runCatching { if (!it.isRecycled) it.recycle() } }
                fr = re
            } else {
                // 重解失败（该设备 retriever 慢/解码失败）：手动旋转预览帧对齐导出方向（rotation=90），
                // 避免"预览横屏/导出竖屏"比例不一致（用户反馈预览比例不对）
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
            // re 为 null 时保留原横屏 fr（至少可预览；导出仍按 motionForceVideoRot=90 竖屏）
        } else {
            EditorState.motionForceVideoRot = -1
        }
        val detectedForce = EditorState.motionForceVideoRot
        if (fr != null && fr.frames.size >= 2) runOnUiThread {
            EditorState.isGif = false; EditorState.clearMotion()
            EditorState.motionForceVideoRot = detectedForce
            EditorState.motionMp4 = mp4  // clearMotion 清了源字节，恢复（doSaveMotion 依赖它导出）
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

    // ---------- 保存 ----------
    // 保存与「合成动态照片」共用同一个权限 launcher：授权后必须回到原入口，
    // 旧实现回调恒执行 doSave()，导致动态照片在 API 26-28 授权后变成普通保存。
    private var pendingPermAction: (() -> Unit)? = null
    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val action = pendingPermAction
            pendingPermAction = null
            if (granted) action?.invoke() else toast(getString(R.string.need_permission))
        }

    /** API 26–28 写公共 DCIM 需要 WRITE_EXTERNAL_STORAGE；false 表示已发起授权、本次不继续。 */
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

    /** 导出成功后自动清理缓存（仅 autoClean 开时）：临时文件 + 动图帧内存；返回释放字节数 */
    private fun clearExportCache(): Long {
        if (!EditorState.params.autoClean) return -1L
        return doClearCache()
    }

    /** 导出成功后的轻清理：只删临时导出文件，保留 motionFrames/封面/源字节。
     *  ⚠️ 旧逻辑成功后 doClearCache 回收 motionFrames → 预览空白 → 用户被迫重新导入素材
     *  （反馈"导出完又自动重新导入"）。素材在下次选新图/新视频时由 clearMotion 释放。 */
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

    /** 实际清理动作（手动/自动共用）；返回释放字节数。
     *  v14.26：只删临时导出文件，【不再回收 motionFrames/当前素材】——
     *  旧逻辑把已载入的视频帧整个回收 → 预览空白、必须重新导入（用户反馈"清理缓存连照片视频一起清"）。
     *  素材内存由下次选取（clearMotion）自然释放。 */
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

    /** 手动清理缓存（按钮）：立即清理（不受自动清理开关限制），回调释放量 */
    fun cleanCacheNow(onDone: ((Long) -> Unit)? = null) {
        Thread {
            val freed = doClearCache()
            ui.post { onDone?.invoke(freed) }
        }.start()
    }


    /** 导出互斥锁：连点保存/动图时忽略第二次，杜绝并发导出竞争（共享 EditorState.motionMp4/帧内存） */
    private val exporting = java.util.concurrent.atomic.AtomicBoolean(false)

    /** v14.29 流体开关回调：开启→保存参数并重载整个应用（流体渲染初始化干净、预览持续流动）；
     *  关闭→无需重载（回到静态玻璃）。 */
    fun onFluidToggle(on: Boolean) {
        EditorState.params.fluidEnabled = on
        EditorState.persist(this)
        preview?.invalidateBlur()
        preview?.invalidate()
        bump()
    }

    /** 取消导出请求标志：UI 取消按钮置位，导出线程在各 onFrame 回调检查并抛异常中断，
     *  临时文件由 clearExportCache 清理（catch 分支区分取消 vs 真实失败） */
    private val cancelExportFlag = java.util.concurrent.atomic.AtomicBoolean(false)
    private class ExportCancelledException : RuntimeException("导出已取消")
    fun requestCancelExport() {
        cancelExportFlag.set(true)
        // 同步通知 MotionDecoder：Surface 0 帧空转 / retriever worker seek 期间 onFrame 不被调用，
        // 仅靠 onFrame 内 checkCancelled 会无响应；必须直接中止解码器循环与并行 worker
        MotionDecoder.requestExternalCancel()
    }
    private fun checkCancelled() {
        if (cancelExportFlag.get()) throw ExportCancelledException()
    }

    fun doSave() {
        // v15.24：save() 原为零调用死代码，顶栏直接调 doSave() 绕过了 API26-28 的
        // WRITE_EXTERNAL_STORAGE 校验 → 老设备保存到公共 DCIM 必失败。校验下沉到所有入口共用的 doSave()。
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
                // v15.24：动图源只在用户明确选 GIF 时走 GIF 编码。GIF 源没有视频轨，
                // 旧逻辑无条件 doSaveGif()，格式条上的 MP4/HEVC 被静默忽略（选了也导出 .gif）。
                // 现在明确提示并停止，绝不产出与所选格式不符的文件。
                if (EditorState.motionExportFormat != "gif") {
                    progressPct = -1
                    toast(getString(R.string.save_fail, "动图素材仅支持 GIF 导出，请切换为 GIF 或改选视频"))
                    return
                }
                doSaveGif(); return
            }
            // 只要有视频源就走动图导出（motionMp4 是权威依据；motionFrames 可能因上次解码失败为 null，
            // 但 doSaveMotion 内部直接用 motionMp4 重新解码，不依赖 motionFrames）
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
                // v15.28：GIF 素材导出同样应用用户自定义分辨率（480p/720p 长边）。
                // 源 GIF 小于目标尺寸时保持源大小不放大；帧率保留源（GIF→GIF 不重采样）。
                EditorState.sanitizeGifParams()
                val maxEdge = EditorState.gifEdgePx
                val sc = minOf(1f, maxEdge.toFloat() / maxOf(gi.w, gi.h).toFloat())
                val rot = ((EditorState.photoRotation % 360) + 360) % 360
                val rawW = maxOf(1, (gi.w * sc).toInt())
                val rawH = maxOf(1, (gi.h * sc).toInt())
                val outW = if (rot % 180 != 0) rawH else rawW
                val outH = if (rot % 180 != 0) rawW else rawH
                val dur = gi.duration.coerceAtLeast(1)
                // 卡：跟随源 GIF 真实帧率（只扫 GCE 块），避免强制 30fps 生成大量重复帧
                // 每帧时间点 = 之前所有帧停留时间累计；停留时间为 0 时按 100ms 处理
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
                    // 解析失败回退：固定 20fps（比旧 30fps 生成的重复帧少 1/3，卡顿明显改善）
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
                    // v14.96 呼吸帧时间：GIF 帧播放时间（ms→秒），呼吸按播放节奏起伏
                    EditorState.exportTimeSec = frameTimes[i] / 1000f
                    val fr = ed.renderGifFrame(movie, frameTimes[i], outW, outH)
                    // v15.21 关闭 FS 抖动：全局 256 色调色板缺中间色时，抖动会把像素推向饱和红/蓝/白等
                    // 远端色去"平均"，在平滑面（皮肤/墙面/垫子）上形成满屏彩色噪点。无抖动=就近映射，
                    // 干净无斑点；6/6/6 量化台阶仅 ~4 级，色带极轻。
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

    /**
     * 动态 WebP → GIF 导出（v15.26：回退为串行实现，修复 v15.25 的队列堆积回归）。
     *
     * v15.25 曾把「渲染 + 量化/索引 + LZW」丢到单线程 HandlerThread，但解码仍由
     * AnimatedImageDrawable 在主线程按源帧率实时推进（Choreographer），两侧没有背压：
     * 第二遍每帧 renderMotionFrame(960px)+toIndices+LZW 一旦慢于帧间隔，解码就会跑在前面，
     * 把大量 960px 解码位图（每张约 3.7MB）堆积在 Handler 队列里 → 内存暴涨 / GC 抖动，
     * 反而更慢，严重时 OOM 或帧错乱（渲染异常）。v15.26 回退为「onFrame 内同步消费」：
     * 每帧渲染/量化/编码后立即 recycle，内存恒定有界，渲染结果与旧版一致。
     *
     * 解码回调仍在主线程（AnimatedImageDrawable 依赖主线程 Choreographer 推进）。
     */
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
        val wpMaxEdge = EditorState.gifEdgePx   // v15.28：动态 WebP → GIF 也应用自定义分辨率
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
                var encTimeMs = 0L   // v14.96 呼吸帧时间累计（WebP 帧播放时间）
                WebPDecoder.decodeAsync(mainH, this, wp, wpMaxEdge, gifMaxFrames, 120_000,
                    onFrame = { bmp2, delay2 ->
                        checkCancelled()
                        // v14.96 呼吸帧时间：累计帧延迟（ms→秒），呼吸按播放节奏起伏
                        EditorState.exportTimeSec = encTimeMs / 1000f
                        encTimeMs += delay2.coerceAtLeast(1)
                        val fr = ed.renderMotionFrame(bmp2, outW, outH)
                        enc.addFrame(palette.toIndices(fr, dither = false), palette.rgb,
                            (delay2 / 10f).roundToInt().coerceIn(1, 100))
                        // fr 是 renderMotionFrame 的 View 内部复用 bitmap，禁止回收（否则下一帧画到 recycled bitmap 崩溃）
                        bmp2.recycle()
                        d2++
                        // 第二遍用第一遍真实帧数做分母，进度不虚高
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
                    // 动图帧序列存在但源字节丢失（异常状态）：明确提示，不静默
                    ui.post { progressPct = -1; toast(getString(R.string.save_fail, "动图源丢失，请重新选择")) }
                    return@Thread
                }
                if (webp != null) {
                    // v15.24：动态 WebP 同样只有 GIF 编码路径，选 MP4/HEVC 不再静默改成 GIF
                    if (EditorState.motionExportFormat != "gif") {
                        ui.post { progressPct = -1; toast(getString(R.string.save_fail, "动态 WebP 仅支持 GIF 导出，请切换为 GIF 或改选视频")) }
                        return@Thread
                    }
                    val wp = webp!!
                    // v15.26：回退 v15.25 的 HandlerThread 方案（无背压导致解码跑在前面、位图堆积
                    // → 内存暴涨/GC 抖动/更慢甚至花屏）。改回 onFrame 内同步消费、每帧即时回收，内存有界。
                    exportWebpToGif(wp)
                    return@Thread
                }
                val rot = ((EditorState.photoRotation % 360) + 360) % 360
                // 导出格式：mp4（H.264 流式，兼容最佳）/ hevc（H.265，设备支持时）/ gif（跟随源帧率）
                if (EditorState.motionExportFormat == "mp4" ||
                    EditorState.motionExportFormat == "hevc") {
                    publishProgress(10)
                    val codec = if (EditorState.motionExportFormat == "hevc")
                        MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
                    val outMp4 = Mp4Exporter.export(this, mp4 ?: ByteArray(0), rot, { srcFrame ->
                        checkCancelled()
                        // 帧已按视频元数据旋转；用户旋转角再叠加，输出尺寸与编码器一致
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
                        // 不插封面帧前缀：输出必须严格等于源视频时长（旧逻辑额外加 500ms 封面导致时长多 0.5s/4s5s 波动）
                        prefixFrame = null,
                        prefixDurationMs = 0, codec = codec, srcUri = motionUri,
                        forceVideoRotation = EditorState.motionForceVideoRot,
                        reuseOutput = true,
                        // v14.49：mode 0=快速(纯GPU) 1=混合 2=纯CPU(不传渲染器→逐帧软件渲染)
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
                    // 无论保存成功与否都删除临时 mp4（原写法保存抛异常时 delete 被跳过 → cacheDir 残留）
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
                // ---- GIF 分支：分辨率/帧率完全跟随源（8192 上限仅为防内存退化），流式两遍防 OOM ----
                // v15.31：优先复用【导入时已缓存的本地文件】（file:// 直读，零二次复制），
                // 避免 550MB 大视频 GIF 导出把 content:// 再复制第二次导致"动态片段解码失败"。
                // 仅当没有缓存路径时才临时复制。
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
                // 先确定源尺寸：全分辨率解 1 帧（本地文件；minFrames=1 允许单帧探测）
                val probe = runCatching {
                    // v15.21：全分辨率探测源尺寸（8192 上限），帧率跟随源（240=不超源帧率）
                    MotionDecoder.decode(this, ByteArray(0), 8192, 240, 1, 15_000,
                        minFrames = 1, srcUri = gifLocalUri,
                        rotationOverride = EditorState.motionForceVideoRot)
                }.getOrNull()
                if (probe == null || probe.frames.isEmpty()) error(getString(R.string.motion_decode_fail))
                val rawW = probe.frames[0].width.coerceAtLeast(1)
                val rawH = probe.frames[0].height.coerceAtLeast(1)
                probe.frames.forEach { runCatching { it.recycle() } }
                val rotW = rot % 180 != 0
                // v15.28 GIF 自定义参数：分辨率（480p/720p 长边）与帧率（15/25/30）由用户选择，
                // 不再"跟随源分辨率/帧率"（旧行为：长边封顶 1080 + 全帧率 240）。源小于目标分辨率时保持源大小。
                EditorState.sanitizeGifParams()
                val gifFps = EditorState.gifFps
                val gifMaxEdge = EditorState.gifEdgePx.toFloat()
                val gsc = minOf(1f, gifMaxEdge / maxOf(rawW, rawH).toFloat())
                val scaledW = maxOf(1, (rawW * gsc).toInt())
                val scaledH = maxOf(1, (rawH * gsc).toInt())
                val outW = if (rotW) scaledH else scaledW
                val outH = if (rotW) scaledW else scaledH

                publishProgress(12)
                // 进度真实化：先探测源视频真实时长（ms），第一遍量化按累计 PTS 推进（GIF 帧率/间隔
                // 随源变化，按帧数/固定上限会虚高或停滞）；第二遍编码按已解码的真实总帧数做分母
                val sourceGifDurMs = runCatching {
                    val mmr = android.media.MediaMetadataRetriever()
                    try {
                        mmr.setDataSource(gifLocal.absolutePath)
                        mmr.extractMetadata(
                            android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    } finally { runCatching { mmr.release() } }
                }.getOrDefault(0L)
                // v15.28 时间轴剪辑：未自定义时保持旧行为（片头起、截取前 5 秒/全片）；
                // 自定义后按用户起点与时长裁剪，超出源时长自动收窄到片尾
                val srcDurSafe = sourceGifDurMs.takeIf { it > 0L } ?: VideoGifTimeline.MAX_DURATION_MS.toLong()
                val trimStartMs = if (EditorState.gifTrimCustomized)
                    EditorState.gifTrimStartMs.coerceIn(0L, (srcDurSafe - 1L).coerceAtLeast(0L))
                else 0L
                val availMs = (srcDurSafe - trimStartMs).coerceAtLeast(1L)
                val trimDurMs = if (EditorState.gifTrimCustomized)
                    EditorState.gifTrimDurationMs.coerceIn(100L, availMs)
                else minOf(availMs, VideoGifTimeline.MAX_DURATION_MS.toLong())
                val gifDurMs = trimDurMs
                // 仅"未自定义 + 源超 5 秒"提示自动截取；用户主动剪辑时不打扰
                if (!EditorState.gifTrimCustomized && sourceGifDurMs > VideoGifTimeline.MAX_DURATION_MS)
                    ui.post { toast("视频超过五秒，GIF 已自动截取前五秒") }
                val quant = GifQuant.Quantizer()
                // 第一遍量化：降采样到 ≤360 宽统计色表（色表对分辨率不敏感，缩略图统计足够，
                // 提速 9-25 倍、不卡顿，观感不变）；第二遍按用户选择的目标分辨率编码
                val quantScale = minOf(1f, 360f / maxOf(outW, outH))
                val quantW = (outW * quantScale).roundToInt().coerceAtLeast(1)
                val quantH = (outH * quantScale).roundToInt().coerceAtLeast(1)
                // renderMotionFrame 返回 View 内部复用 bitmap（勿回收），量化/toIndices 当帧同步读完
                var decoded = 0
                var decodedMs = 0L
                // 帧数硬上限：GIF 单文件有实际极限（1200 帧≈40s@30fps），超长视频截断并提示，防无界导出卡死
                val gifMaxFrames = 1_200
                val quantTimeline = VideoGifTimeline(startMs = trimStartMs.toInt(), limitMs = trimDurMs.toInt())
                // GIF 解码预算按片段时长+帧数放宽（软解 60fps 大分辨率需要分钟级，固定 300s 会掐死）
                val gifDecodeTimeoutMs = (300_000L).coerceAtLeast(
                    gifDurMs * 6 + (gifDurMs * gifFps / 1000L) * 150L
                ).coerceAtMost(30 * 60_000L).toInt()
                val ok = MotionDecoder.decodeFrames(
                    // v15.22 满血速度：第一遍仅统计色表（直方图在 ≤360 宽算，与分辨率无关），
                    // 解码用 480 即可，避免全分辨率解码白耗；第二遍才按目标分辨率编码。
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
                        // 第一遍进度按真实 PTS 累计 / 片段时长推进（12→38）；时长未知时回退帧数估算
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

                // 第二遍：流式再解码，直接编码（每帧延迟=真实帧间隔，厘秒）
                val enc = GifEncoder(outW, outH, true)
                var encoded = 0
                val encodeTimeline = VideoGifTimeline(startMs = trimStartMs.toInt(), limitMs = trimDurMs.toInt())
                val encodedOk = MotionDecoder.decodeFrames(
                    // v15.28：第二遍解码直接出目标分辨率（gifEdgePx），省去全分辨率解码+软缩放
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
                        // 第二遍进度用第一遍已解码的真实总帧数做分母（40→95），帧数不虚高
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
                    // 用户取消导出：不视为失败，清缓存并提示取消，不导出日志
                    runCatching { clearExportCacheLight() }
                    ui.post { progressPct = -1; toast(getString(R.string.export_cancelled)) }
                    return@Thread
                }
                DebugLog.logThrowable("SAVE_MOTION", e)
                // 导出失败：自动把完整日志写到 Download/导出日志/，用户直接拿文件发开发者（截图只有尾部）
                val logPath = DebugLog.exportToDownload()
                ui.post {
                    progressPct = -1
                    toast(getString(R.string.save_fail, e.message ?: "") +
                        (logPath?.let { "\n完整日志：$it" } ?: ""))
                }
            }
        }.start()
    }

    /**
     * 真动态照片判定：视频时长 ≤15s 才算"动态照片视频段"（vivo 动照 3-6s）。
     * 长视频（游戏录像/整段视频）绝不参与"封面竖屏→强制旋转 90"推断——
     * 那是 vivo 动照（封面竖屏+视频横像素无 rotation）的专属修复，
     * 对普通横屏视频误判会把整段视频拉成竖屏（用户"导出拉伸为竖屏"根因）。
     */
    // M4：videoDurationMs 在 composeMotionPhotoNow 中会被调用约 3 次，bytes 场景每次都要把整段
    // MP4 写进 cacheDir 再读元数据，累计 I/O 可观。按 uri / bytes(长度+内容哈希) 缓存结果。
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

    /** 把缓存 MP4 复制进系统相册，返回路径字符串 */
    /** 把缓存 MP4 复制进系统相册，返回路径字符串。
     *  防"导出成功但相册无视频+像自动重启"根因：大视频导出后进程可能被系统回收（LMK），
     *  若 IS_PENDING=1 记录残留 → 相册永不显示。写入完成或失败都立即置 0（finally 兜底），
     *  并显式 MediaScanner 扫描双保险。 */
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
                // 写入完成或失败都立即结束 pending——进程任何时刻被杀都不残留"看不见"的记录
                val pendingDone = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Video.Media.IS_PENDING, 0)
                }
                val updated = runCatching { resolver.update(ins, pendingDone, null, null) > 0 }
                    .getOrDefault(false)
                if (!updated) {
                    // H2 修复：update 返回 0（MediaProvider 未识别/记录被删）时，视频会永远停在
                    // IS_PENDING=1——相册看不到，却仍提示"保存成功"。显式留痕并重试一次。
                    DebugLog.log("SAVE", "IS_PENDING 清除失败（update=0）uri=$ins，重试一次")
                    runCatching { resolver.update(ins, pendingDone, null, null) }
                }
            }
            // 双保险：显式扫描，防部分 ROM 相册不自动刷新
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

    /** v15.28 当前视频源时长（供 GIF 时间轴编辑显示）；探测失败回退 5 秒默认片段上限 */
    fun gifSourceDurationMs(): Long {
        val d = videoDurationMs(EditorState.motionUri, EditorState.motionMp4)
        return if (d > 0L) d else VideoGifTimeline.MAX_DURATION_MS.toLong()
    }

    /** v15.30 动照面板视频时长（供动照时间轴编辑显示）；优先手动选视频，其次主预览预解码 */
    fun mpVideoDurationMs(): Long {
        val d = if (mpVideoUri != null) {
            videoDurationMs(mpVideoUri!!, null)
        } else if (EditorState.motionMp4 != null) {
            videoDurationMs(null, EditorState.motionMp4!!)
        } else 0L
        return if (d > 0L) d else VideoGifTimeline.MAX_DURATION_MS.toLong()
    }

    /** v15.28 重置 GIF 剪辑时间轴：回到默认整段前 5 秒并持久化 */
    fun resetGifTrim() {
        EditorState.gifTrimStartMs = 0L
        EditorState.gifTrimDurationMs = 5_000L
        EditorState.gifTrimCustomized = false
        EditorState.persist(this)
        EditorState.bumpExportBar()
    }

    /** v15.30：动态照片剪辑时间轴更新（时间轴拖动后调用，同步持久化并刷新 UI） */
    fun updateMotionTrim() {
        EditorState.persist(this)
        EditorState.bumpExportBar()
    }

    /**
     * v15.31：预览时间轴选中的片段（剪辑软件体验核心功能）。
     * 把当前 GIF 时间轴选中的 [startMs, startMs+durMs) 片段解码进预览区，
     * 画布随即播放该片段，所见即所得；导出前先确认截取内容正确。
     * 复用导入时缓存的本地文件（motionLocalPath），不重复复制。
     */
    fun previewGifTrim() {
        if (EditorState.isExporting) { toast(getString(R.string.export_busy)); return }
        val local = EditorState.motionLocalPath?.let { java.io.File(it) }
        val srcUri = when {
            local != null && local.exists() && local.length() > 0 -> android.net.Uri.fromFile(local)
            EditorState.motionUri != null -> EditorState.motionUri
            EditorState.motionMp4 != null -> null // 字节数组场景，decode 内部落盘
            else -> { toast(getString(R.string.mp_missing)); return }
        }
        val mp4Bytes = if (srcUri == null) (EditorState.motionMp4 ?: ByteArray(0)) else ByteArray(0)
        EditorState.sanitizeGifParams()
        val startMs = EditorState.gifTrimStartMs.coerceAtLeast(0L)
        var durMs = EditorState.gifTrimDurationMs.coerceAtLeast(300L)
        // 防越界：起点+长度不超过源时长
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
                // 更新预览帧与运动帧序列（预览区立即播放该片段）
                ui.post {
                    val old = EditorState.previewBitmap
                    EditorState.motionFrames?.forEach { runCatching { if (!it.isRecycled) it.recycle() } }
                    // 保留运动源，替换的是"预览用帧序列"
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

