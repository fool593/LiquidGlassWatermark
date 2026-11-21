package com.liquidglass.watermark.ui

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.liquidglass.watermark.DebugLog
import com.liquidglass.watermark.EditorState
import com.liquidglass.watermark.EditorState.params
import com.liquidglass.watermark.MainActivity
import com.liquidglass.watermark.Mp4Exporter
import com.liquidglass.watermark.R
import com.liquidglass.watermark.WatermarkEditorView
import com.liquidglass.watermark.WatermarkScale
import com.liquidglass.watermark.darkModeState
import com.liquidglass.watermark.setDarkMode
import com.liquidglass.watermark.glass.Backdrop
import com.liquidglass.watermark.glass.backdrops.layerBackdrop
import com.liquidglass.watermark.glass.backdrops.rememberLayerBackdrop
import com.liquidglass.watermark.glass.drawBackdrop
import com.liquidglass.watermark.glass.effects.blur
import com.liquidglass.watermark.glass.effects.lens
import com.liquidglass.watermark.glass.effects.vibrancy
import com.liquidglass.watermark.ui.kyant0.LiquidButton
import com.liquidglass.watermark.ui.kyant0.LiquidSlider
import com.liquidglass.watermark.ui.kyant0.LiquidToggle
import androidx.compose.foundation.layout.widthIn
import com.liquidglass.watermark.ui.ksu.DockBottomGap
import com.liquidglass.watermark.ui.ksu.DockMaxWidth
import com.liquidglass.watermark.ui.ksu.DockPillHeight
import com.liquidglass.watermark.ui.ksu.DockSideMargin
import com.liquidglass.watermark.ui.ksu.KsuDockIcon
import com.liquidglass.watermark.ui.ksu.KsuDockLabel
import com.liquidglass.watermark.ui.ksu.KsuFloatingBottomBar
import com.liquidglass.watermark.ui.ksu.KsuFloatingBottomBarItem
import kotlin.math.roundToInt

@Composable
private fun tabNames(): List<String> = listOf(
    stringResource(R.string.tab_shape),
    // v15.38：Dock 精简到 5 项——「光泽」并进「玻璃」，作为玻璃页内的子页签
    stringResource(R.string.tab_glass),
    stringResource(R.string.tab_text),
    stringResource(R.string.tab_pos),
    stringResource(R.string.tab_settings)
)

/** v15.38：Dock 图标显式映射，避免精简项数后图标按序错位（0 形状 / 1 玻璃 / 3 文字 / 4 位置 / 5 设置）。 */
private val DOCK_ICON_IDS = listOf(0, 1, 3, 4, 5)

// 深色模式白字 / 浅色模式黑字，跟随应用内 darkModeState（非系统主题）
@Composable
private fun tMain(): Color = if (darkModeState) Color(0xFFF5F7FA) else Color(0xFF1A1D24)
@Composable
private fun tSub(alpha: Float = 0.55f): Color =
    if (darkModeState) Color.White.copy(alpha = alpha) else Color(0xFF5A6478).copy(alpha = alpha + 0.15f)

// 输入框边框/光标跟随深浅色，避免浅色模式下浅边框看不清
@Composable
private fun fieldColors() = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Color(0xFF0091FF),
    unfocusedBorderColor = if (darkModeState) Color.White.copy(alpha = 0.35f) else Color(0xFF9AA2B0),
    cursorColor = Color(0xFF0091FF),
    focusedLabelColor = Color(0xFF0091FF),
    unfocusedLabelColor = tSub(0.6f)
)

/** 液态玻璃输入框：玻璃背景 + 透明边框（替代旧版圆圈/矩形边框，统一视觉） */
@Composable
private fun GlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    backdrop: Backdrop,
    modifier: Modifier = Modifier
) {
    Box(
        modifier
            .glassSurface(backdrop = backdrop, shape = RoundedCornerShape(12.dp))
    ) {
        androidx.compose.material3.OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label, color = tSub(0.5f), fontSize = 11.sp) },
            singleLine = true,
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
                cursorColor = Color(0xFF0091FF),
                focusedLabelColor = Color(0xFF0091FF),
                unfocusedLabelColor = tSub(0.6f)
            ),
            textStyle = androidx.compose.ui.text.TextStyle(color = tMain(), fontSize = 13.sp),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun EditorScreen(act: MainActivity) {
    val scene = rememberLayerBackdrop()
    var sceneSize by remember { mutableStateOf(IntSize.Zero) }
    PrepareSoftwareGlassBackdrop(scene, sceneSize)
    // v15.16 任务3：全屏场景层的内容只有「深色模式」一个变量，尺寸不变时是静态的。
    // 声明为静态后，拖动滑块等每帧重组不再重录整张全屏背景（这是主线程最重的单项之一）；
    // 深色模式切换时通过 contentRevision 让缓存失效，视觉结果与每帧重录完全一致。
    val darkMode = darkModeState
    LaunchedEffect(scene, darkMode) {
        scene.contentStatic = true
        scene.invalidateStaticContent()
    }
    CompositionLocalProvider(LocalGlassBackdrop provides scene) {
        Box(Modifier.fillMaxSize()) {
            // Capture a real, visible source once. The former per-control backdrops
            // were never recorded, so every button was refracting an empty texture.
            Box(Modifier.fillMaxSize().onSizeChanged { sceneSize = it }
                .layerBackdrop(scene).liquidBackground())
            EditorContent(act)
        }
    }
}

@Composable
private fun EditorContent(act: MainActivity) {
    var tab by remember { mutableIntStateOf(0) }
    // v15.34：底部悬浮 Dock 是覆盖层，必须按它【实际测量高度】给 Column 留出占位，
    // 否则常驻在 Column 末尾的导出栏（MP4/HEVC/GIF + 快速/混合/CPU + 动照…）会被 Dock 整条压住。
    // 用 onSizeChanged 实测（含 navigationBarsPadding 与底部间距），不依赖写死的常量，
    // 换机型 / 字体缩放 / 导航栏高度变化都自动对齐。
    val density = LocalDensity.current
    var dockHeightPx by remember { mutableIntStateOf(-1) }
    val dockSlot: androidx.compose.ui.unit.Dp =
        // +6dp：给 Dock 自身的 dropShadow（radius 10dp）留出余量，不让阴影压到导出栏下沿
        if (dockHeightPx >= 0) with(density) { dockHeightPx.toDp() } + 6.dp
        else DockPillHeight + DockBottomGap + 6.dp

    // v15.16 任务3：不再在 EditorContent 顶层读 act.rev。
    //
    // 原实现 `act.rev` 写在这里，而 bump() 拖动时每帧调用（EditorScreen 内 70 处），
    // 于是每一次拖动都让 TopBar / MaterialRow / PreviewArea / ExportBar / BottomNav /
    // 参数面板**全部重组**——这是 Android 11+ 上 "Slow UI thread" 占比最高的单项。
    //
    // 改为把 rev 的读取下沉到真正需要它的两个叶子：参数面板容器（需在参数变化后
    // 重读 EditorState.params 渲染新值）与预览区（需重绘）。TopBar/MaterialRow/
    // ExportBar/BottomNav 与参数无关，现在保持稳定不重组，视觉与交互完全不变。

    Box(
        Modifier
            .fillMaxSize()
    ) {
        Column(Modifier.fillMaxSize()) {
            TopBar(act)
            MaterialRow(act)
            Box(Modifier.weight(1f)) {
                PreviewArea(Modifier.fillMaxSize(), act)
                // 保存进度覆盖层：只遮预览区，顶栏按钮/底栏保持可见可点（之前全屏遮罩让保存按钮变暗无字）
                if (act.progressPct >= 0) {
                    val pb = LocalGlassBackdrop.current
                    Box(
                        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(stringResource(R.string.exporting, act.progressPct), color = tMain(), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(14.dp))
                            // 液态玻璃进度条：玻璃轨道 + 渐变填充，圆角 pill
                            Box(
                                Modifier
                                    .width(240.dp)
                                    .height(10.dp)
                                    .drawBackdrop(
                                        backdrop = pb,
                                        shape = { androidx.compose.foundation.shape.RoundedCornerShape(50) },
                                        effects = {
                                            vibrancy()
                                            blur(6f.dp.toPx())
                                            lens(6f.dp.toPx(), 12f.dp.toPx(), depthEffect = true, scaleCompensation = 1f)
                                        }
                                    )
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxHeight()
                                        .width((act.progressPct.coerceIn(0, 100) * 2.4f).dp)
                                        .background(
                                            androidx.compose.ui.graphics.Brush.horizontalGradient(
                                                listOf(Color(0xFF0091FF), Color(0xFF5AC8FA))
                                            ),
                                            androidx.compose.foundation.shape.RoundedCornerShape(50)
                                        )
                                )
                            }
                            Spacer(Modifier.height(18.dp))
                            // 取消导出：液态玻璃按钮，导出进行中始终可点
                            LiquidButton(
                                onClick = { act.requestCancelExport() },
                                backdrop = pb,
                                modifier = Modifier.height(40.dp),
                                tint = Color(0xFFE04545)
                            ) {
                                Text(
                                    stringResource(R.string.export_cancel),
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, top = 4.dp)
                    // v15.28 UI 重构：参数面板由固定 190dp 加高到 224dp，6 个 tab 内容更舒展。
                    // v15.35：Dock 压缩后把省下的高度还给画布，面板 268→232dp（内部仍可滚动）。
                    // v15.41：+ dockSlot —— 面板不再在 Dock 前截断，而是穿过 Dock 一直铺到屏幕底，
                    // 于是 Dock 玻璃后面是真参数内容（能透过胶囊看见后面的选项），而不是一块空背景板。
                    .height(232.dp + dockSlot)
                    .glassSurface()
                    .padding(start = 10.dp, end = 10.dp, top = 8.dp)
                    // 导出中：在 Initial 阶段消费全部指针，冻结滑块/开关/参数按钮（触摸不到下层控件）；
                    // 仅锁参数面板，下方 ExportBar 与底部导航在本 Box 之外，仍可点击切换页面。
                    .pointerInput(act.progressPct >= 0) {
                        if (act.progressPct >= 0) awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                            }
                        }
                    }
            ) {
                // v15.16 任务3：rev 的读取下沉到面板，拖动滑块时只有参数面板重组。
                //
                // v15.17 修正：下沉的落点原来是【这里】（面板容器），实际必须落到【每个面板函数内部】。
                // 面板函数（ShapeTab/GlassTab/…）的参数只有 act —— MainActivity 是 unstable 类，但实例
                // 从头到尾是同一个，Compose 生成的跳过判断是 Composer.changed(act) → 走 equals → 恒等，
                // 于是容器重组时面板被整体跳过，函数体根本不执行。结果面板里对 EditorState.params
                // （普通字段，不是 Compose State）的读取全部失效：
                //   · 滑块松手后 localVal 回读外部值拿到拖动前的旧值 → 弹回默认数值
                //   · 字体 / 形状 / 文字色 / 导出模式等按钮的选中 tint 永远不变蓝
                //   · 导入素材后素材行不刷新 → 「删除图片 / 删除视频」按钮不出现
                // 现在 6 个面板函数各自在函数体首行读 act.rev，自己的重组作用域记录该依赖，
                // rev 一变作用域即失效，跳过被绕过。容器这里无需再读（避免整块重组）。
                // v15.38：Dock 由 6 项精简为 5 项（形状 / 玻璃 / 文字 / 位置 / 设置），
                // 原「光泽」并入「玻璃」，在玻璃页顶部以子页签承载，功能零丢失。
                when (tab) {
                    0 -> ShapeTab(act)
                    1 -> GlassGroupTab(act)
                    2 -> TextTab(act)
                    3 -> PosTab(act)
                    else -> SettingsTab(act)
                }
            }
            // 导出栏（常驻）与 Dock 一起作为底部悬浮层，位置不变（导出栏仍在 Dock 正上方），
            // 但不再占用 Column 的流式空间 —— 面板内容可以一直延伸到 Dock 背后。
        }
        // v15.41：底部悬浮层 = 导出栏 + Dock，整层做一块通体玻璃（不是只有胶囊是玻璃）。
        // 玻璃只做模糊/通透，不加实心底板，所以背后的参数内容仍然看得见（只是被磨砂化）；
        // 胶囊依旧是叠在这块玻璃上的透镜，两层都是玻璃。
        val shelfBackdrop = LocalGlassBackdrop.current
        val shelfDark = darkModeState
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .drawBackdrop(
                    backdrop = shelfBackdrop,
                    shape = { RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp) },
                    effects = {
                        padding = maxOf(padding, 40.dp.toPx())
                        vibrancy()
                        blur(14.dp.toPx())
                    },
                    onDrawSurface = {
                        drawRect(
                            if (shelfDark) Color.White.copy(alpha = 0.05f)
                            else Color.Black.copy(alpha = 0.04f)
                        )
                    },
                )
        ) {
            Column(Modifier.fillMaxWidth()) {
                ExportBar(act)
                Spacer(Modifier.height(4.dp))
                BottomNav(tab, { tab = it }, onDockHeight = { dockHeightPx = it })
            }
        }
        // 动态照片合成面板（覆盖层）
        if (act.showMotionPhotoPanel) {
            MotionPhotoPanel(act)
        }
        // v15.30：GIF 工具面板（覆盖层，独立于动照片）
        if (act.showGifPanel) {
            GifPanel(act)
        }
    }
}

/** 导出栏（v15.28 重构）：按「格式 / 模式 / 动作」三组竖线分隔，按钮高度与间距统一。
 *  液态玻璃按钮视觉不变；横向可滚动保证任何设备宽度都不裁剪。 */
@Composable
private fun ExportBar(act: MainActivity) {
    val b = LocalGlassBackdrop.current
    val cur = EditorState.motionExportFormat
    // v15.17：订阅导出栏自己的信号 + 素材版本号。
    // 「快速/混合/CPU」的选中色读的是 EditorState.params.exportMode（普通字段），
    // 「动照」的可用色读的是 mpImageSet/mpVideoSet（普通字段）—— 不订阅 State 时整条导出栏
    // 会被 Compose 跳过，点了不变蓝。这里刻意不订阅 act.rev，避免拖动滑块时每帧重录整排按钮。
    EditorState.exportBarRev
    EditorState.mediaRev
    // 导出中冻结格式切换/合成/清缓存（清缓存可能删除正在写入的临时文件）；底部导航在本栏之外，不受影响
    val locked = act.progressPct >= 0
    Row(
        // v14.45 UI 重构：导出栏横向可滚动——全部按钮（格式/动照/清缓存）永不裁剪
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 2.dp)
            .horizontalScroll(rememberScrollState())
            .pointerInput(locked) {
                if (locked) awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    }
                }
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // ── 组 1：导出格式 ──
        Text(stringResource(R.string.export_fmt), color = tSub(), fontSize = 12.sp)
        // v14.72 格式切换即持久化（修复"导出格式没记住"）
        FormatChip(act, b, "mp4", "MP4", cur) { EditorState.motionExportFormat = "mp4"; EditorState.persist(act) }
        if (Mp4Exporter.hasHevcEncoder())
            FormatChip(act, b, "hevc", "HEVC", cur) { EditorState.motionExportFormat = "hevc"; EditorState.persist(act) }
        FormatChip(act, b, "gif", "GIF", cur) {
            // v15.30：点 GIF 立即弹出 GIF 工具面板（参数 + 选视频），不再只是静默切格式
            act.openGifPanel()
        }
        GroupDivider()
        // ── 组 2：导出模式（快速默认纯GPU / 混合 / CPU 纯软） ──
        Text(stringResource(R.string.export_mode_label), color = tSub(), fontSize = 12.sp)
        listOf(
            stringResource(R.string.export_mode_fast) to 0,
            stringResource(R.string.export_mode_hybrid) to 1,
            stringResource(R.string.export_mode_cpu2) to 2
        ).forEach { (label, mode) ->
            LiquidButton(onClick = {
                EditorState.params.exportMode = mode
                EditorState.persist(act)
                EditorState.bumpExportBar()
                act.bump()
            }, backdrop = b, modifier = Modifier.height(30.dp),
                tint = if (EditorState.params.exportMode == mode) Color(0xFF0091FF) else Color.Unspecified)
            { Text(label, color = tMain(), fontSize = 11.sp, fontWeight = FontWeight.Medium) }
        }
        GroupDivider()
        // ── 组 3：动作（动图合成 / 清缓存 / 删除日志） ──
        // 动态照片合成：主预览已载入运动内容（选动态照片自动配对）时直接合成，
        // 无运动内容才弹手动面板（选封面+视频）
        LiquidButton(
            onClick = {
                val hasMotion = EditorState.motionMp4 != null &&
                    ((EditorState.motionCoverBitmap?.takeIf { !it.isRecycled } != null) ||
                        (EditorState.previewBitmap?.takeIf { !it.isRecycled } != null && !EditorState.isDemo))
                if (hasMotion) act.composeMotionPhoto() else act.showMotionPhotoPanel = true
            },
            backdrop = b,
            modifier = Modifier.height(32.dp),
            tint = if (act.mpImageSet && act.mpVideoSet) Color(0xFF0091FF) else Color.Unspecified
        ) {
            Text(stringResource(R.string.mp_title), color = tMain(), fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
        // 清理缓存（手动）：点击立即清理，进行中变蓝，完成后提示释放量并恢复透明
        var cleaning by remember { mutableStateOf(false) }
        LiquidButton(
            onClick = {
                if (!cleaning) {
                    cleaning = true
                    act.cleanCacheNow { freed ->
                        cleaning = false
                        act.toast(act.getString(R.string.clean_done, fmtMb(freed)))
                    }
                }
            },
            backdrop = b,
            modifier = Modifier.height(32.dp),
            tint = if (cleaning) Color(0xFF0091FF) else Color.Unspecified
        ) {
            Text(
                if (cleaning) stringResource(R.string.cleaning)
                else stringResource(R.string.auto_clean_short),
                color = tMain(), fontSize = 12.sp, fontWeight = FontWeight.Medium
            )
        }
        // 删除日志（用户要求）：一键删除 Download/lgw_debug.log 及 Download/导出日志/ 全部日志，
        // 防止日志冗余堆积；完成后提示删除数量
        var clearingLogs by remember { mutableStateOf(false) }
        LiquidButton(
            onClick = {
                if (!clearingLogs) {
                    clearingLogs = true
                    val n = DebugLog.deleteAllLogs()
                    clearingLogs = false
                    act.toast(act.getString(R.string.delete_logs_done, n))
                }
            },
            backdrop = b,
            modifier = Modifier.height(32.dp),
            tint = if (clearingLogs) Color(0xFF0091FF) else Color.Unspecified
        ) {
            Text(
                if (clearingLogs) stringResource(R.string.cleaning)
                else stringResource(R.string.delete_logs),
                color = tMain(), fontSize = 12.sp, fontWeight = FontWeight.Medium
            )
        }
    }
}

/** 导出栏组分隔竖线：液态玻璃浅淡描边，1dp 宽 20dp 高 */
@Composable
private fun GroupDivider() {
    Box(
        Modifier
            .width(1.dp)
            .height(20.dp)
            .background(Color(0x26FFFFFF))
    )
}

/** 字节数 → 人类可读（"12.5 MB"） */
private fun fmtMb(bytes: Long): String =
    if (bytes <= 0) "0 B"
    else if (bytes < 1024 * 1024) String.format(java.util.Locale.US, "%.0f KB", bytes / 1024f)
    else String.format(java.util.Locale.US, "%.1f MB", bytes / 1024f / 1024f)

@Composable
private fun FormatChip(
    act: MainActivity, b: Backdrop, fmt: String, label: String, cur: String, onClick: () -> Unit
) {
    val selected = cur == fmt
    LiquidButton(
        onClick = onClick,
        backdrop = b,
        modifier = Modifier.height(30.dp),
        tint = if (selected) Color(0xFF0091FF) else Color.Unspecified
    ) {
        Text(label, color = tMain(), fontSize = 12.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

/** 动态照片合成面板：两个槽位（封面图片 / 视频片段）+ 合成保存 */
@Composable
private fun MotionPhotoPanel(act: MainActivity) {
    val b = LocalGlassBackdrop.current
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp)
                .glassSurface(backdrop = b, shape = RoundedCornerShape(24.dp), prominent = true),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.mp_title), color = tMain(), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.mp_subtitle), color = tSub(), fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            // v15.30：动态照片 ≠ GIF。「GIF」是独立工具（自身面板：选视频+参数+时间轴），
            // 不再塞在动照片面板里；入口按钮改为打开 GIF 工具面板。
            LiquidButton(
                onClick = { act.openGifPanel() },
                backdrop = b,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(44.dp)
            ) {
                Text(stringResource(R.string.gif_entry), color = tMain(), fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(8.dp))
            // v14.38 整段动图导入：选【一个】文件（视频或图片）自动填满封面+视频两槽
            LiquidButton(
                onClick = { act.pickWholeMotionPhoto() },
                backdrop = b,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(44.dp),
                tint = if (act.mpImage != null && act.mpVideoSet) Color(0xFF0091FF) else Color.Unspecified
            ) {
                Text(stringResource(R.string.mp_pick_whole), color = tMain(), fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(10.dp))
            // 槽位1：封面图片（已选时显示删除）
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LiquidButton(
                    onClick = { act.pickMotionPhotoImage() },
                    backdrop = b,
                    modifier = Modifier.weight(1f).height(44.dp),
                    tint = if (act.mpImage != null) Color(0xFF0091FF) else Color.Unspecified
                ) {
                    Text(
                        if (act.mpImage != null) stringResource(R.string.mp_image_ok)
                        else stringResource(R.string.mp_pick_image),
                        color = tMain(), fontSize = 14.sp, fontWeight = FontWeight.Medium
                    )
                }
                if (act.mpImage != null) {
                    LiquidButton(
                        onClick = { act.clearMotionCover() },
                        backdrop = b,
                        modifier = Modifier.height(44.dp).width(64.dp),
                        tint = Color(0xFFE04545)
                    ) {
                        Text(stringResource(R.string.mp_delete), color = tMain(), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            // 槽位3：动照剪辑时间轴（v15.30）
            GifTimelineEditor(
                durationMs = act.mpVideoDurationMs(),
                onChanged = { act.updateMotionTrim() },
                mode = 1 // 1=动态照片剪辑模式
            )
            Spacer(Modifier.height(10.dp))
            // 槽位2：视频片段（已选时显示删除）
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LiquidButton(
                    onClick = { act.pickMotionPhotoVideo() },
                    backdrop = b,
                    modifier = Modifier.weight(1f).height(44.dp),
                    tint = if (act.mpVideoSet) Color(0xFF0091FF) else Color.Unspecified
                ) {
                    Text(
                        if (act.mpVideoSet) stringResource(R.string.mp_video_ok)
                        else stringResource(R.string.mp_pick_video),
                        color = tMain(), fontSize = 14.sp, fontWeight = FontWeight.Medium
                    )
                }
                if (act.mpVideoSet) {
                    LiquidButton(
                        onClick = { act.clearMotionVideo() },
                        backdrop = b,
                        modifier = Modifier.height(44.dp).width(64.dp),
                        tint = Color(0xFFE04545)
                    ) {
                        Text(stringResource(R.string.mp_delete), color = tMain(), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            // Container choice only; whether the system gallery animates the result depends on device support.
            Text(stringResource(R.string.motion_format_label), color = tSub(), fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LiquidButton(
                    onClick = { EditorState.motionPhotoFormat = 0; EditorState.persist(act) },
                    backdrop = b,
                    modifier = Modifier.weight(1f).height(38.dp),
                    tint = if (EditorState.motionPhotoFormat == 0) Color(0xFF0091FF) else Color.Unspecified
                ) {
                    Text(stringResource(R.string.motion_format_vivo), color = tMain(), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
                LiquidButton(
                    onClick = { EditorState.motionPhotoFormat = 1; EditorState.persist(act) },
                    backdrop = b,
                    modifier = Modifier.weight(1f).height(38.dp),
                    tint = if (EditorState.motionPhotoFormat == 1) Color(0xFF0091FF) else Color.Unspecified
                ) {
                    Text(stringResource(R.string.motion_format_google), color = tMain(), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
            Spacer(Modifier.height(14.dp))
            // 合成保存（动照片容器：vivo-style / Google XMP —— 动态照片不是 GIF）
            LiquidButton(
                onClick = { act.composeMotionPhoto() },
                backdrop = b,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(46.dp),
                tint = Color(0xFF0091FF)
            ) {
                Text(stringResource(R.string.mp_compose), color = tMain(), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(10.dp))
            // 关闭
            Text(
                stringResource(R.string.mp_close),
                color = tSub(), fontSize = 13.sp,
                modifier = Modifier
                    .padding(8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(interactionSource = null, indication = null) { act.showMotionPhotoPanel = false }
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            )
            Spacer(Modifier.height(14.dp))
        }
    }
}

/**
 * v15.30 GIF 工具面板：与动态照片完全独立。
 * 点导出栏「GIF」（或动照片面板的「GIF」入口）弹出：选择视频 → 参数（帧率/分辨率）→
 * 剪辑时间轴 → 导出 GIF。动态照片走 MotionPhotoPanel（MP4/容器），不在此列。
 */
@Composable
private fun GifPanel(act: MainActivity) {
    val b = LocalGlassBackdrop.current
    // 订阅素材版本号：导入视频后本面板刷新为「已选视频」状态
    EditorState.mediaRev
    EditorState.exportBarRev
    val hasVideoSource = EditorState.motionUri != null ||
        EditorState.motionMp4 != null ||
        (EditorState.motionFrames?.size ?: 0) >= 2
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 24.dp)
                .glassSurface(backdrop = b, shape = RoundedCornerShape(24.dp), prominent = true),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.gif_entry), color = tMain(), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.gif_tool_subtitle), color = tSub(), fontSize = 12.sp)
            Spacer(Modifier.height(12.dp))
            if (!hasVideoSource) {
                // 未选视频：大按钮引导
                LiquidButton(
                    onClick = { act.pickVideoToGif() },
                    backdrop = b,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(48.dp),
                    tint = Color(0xFF0091FF)
                ) {
                    Text(stringResource(R.string.gif_pick_video), color = tMain(), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
                Text(stringResource(R.string.gif_pick_hint), color = tSub(), fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp, bottom = 8.dp))
            } else {
                // 已选视频：参数 + 时间轴 + 导出
                Text(stringResource(R.string.gif_params_title), color = tSub(), fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(15 to "15", 25 to "25", 30 to "30").forEach { (fps, label) ->
                        LiquidButton(
                            onClick = {
                                EditorState.gifFps = fps
                                EditorState.persist(act)
                                EditorState.bumpExportBar()
                                act.bump()
                            },
                            backdrop = b,
                            modifier = Modifier.weight(1f).height(34.dp),
                            tint = if (EditorState.gifFps == fps) Color(0xFF0091FF) else Color.Unspecified
                        ) {
                            Text("$label fps", color = tMain(), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(480 to stringResource(R.string.gif_res_480), 720 to stringResource(R.string.gif_res_720)).forEach { (edge, label) ->
                        LiquidButton(
                            onClick = {
                                EditorState.gifEdgePx = edge
                                EditorState.persist(act)
                                EditorState.bumpExportBar()
                                act.bump()
                            },
                            backdrop = b,
                            modifier = Modifier.weight(1f).height(34.dp),
                            tint = if (EditorState.gifEdgePx == edge) Color(0xFF0091FF) else Color.Unspecified
                        ) {
                            Text(label, color = tMain(), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.gif_trim_title), color = tSub(), fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                GifTimelineEditor(
                    durationMs = act.gifSourceDurationMs(),
                    onChanged = {
                        EditorState.persist(act)
                        EditorState.bumpExportBar()
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)
                )
                Spacer(Modifier.height(8.dp))
                // v15.31：预览选中的片段（剪辑软件核心体验）——点击后画布播放该片段
                LiquidButton(
                    onClick = { act.previewGifTrim() },
                    backdrop = b,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(38.dp),
                    tint = Color(0xFF5AC8FA)
                ) {
                    Text(stringResource(R.string.gif_preview_clip), color = tMain(), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(10.dp))
                // 换视频 / 导出
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LiquidButton(
                        onClick = { act.pickVideoToGif() },
                        backdrop = b,
                        modifier = Modifier.weight(1f).height(42.dp)
                    ) {
                        Text(stringResource(R.string.gif_change_video), color = tMain(), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                    LiquidButton(
                        onClick = { act.exportGifFromPanel() },
                        backdrop = b,
                        modifier = Modifier.weight(1.6f).height(42.dp),
                        tint = Color(0xFF0091FF)
                    ) {
                        Text(stringResource(R.string.gif_export_btn), color = tMain(), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            // 关闭
            Text(
                stringResource(R.string.mp_close),
                color = tSub(), fontSize = 13.sp,
                modifier = Modifier
                    .padding(8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(interactionSource = null, indication = null) { act.showGifPanel = false }
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun TopBar(act: MainActivity) {    val b = LocalGlassBackdrop.current
    // v14.46 简洁三段式顶栏：返回 | 标题 | 保存（素材操作移至下方 MaterialRow 整齐一行）
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LiquidButton(
            onClick = { act.finish() },
            backdrop = b,
            modifier = Modifier.height(38.dp)
        ) {
            Text("←", color = tMain(), fontSize = 16.sp)
        }
        Text(
            stringResource(R.string.app_title),
            color = tMain(), fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center, maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 6.dp)
        )
        LiquidButton(
            onClick = { act.doSave() },
            backdrop = b,
            modifier = Modifier.height(38.dp),
            tint = Color(0xFF0091FF)
        ) {
            Text(stringResource(R.string.save_btn), color = tMain(), fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** v14.46 素材操作行：选图/删图/视频/删视频 等宽整齐排列（不挤顶栏，条件按钮动态出现） */
@Composable
private fun MaterialRow(act: MainActivity) {
    val b = LocalGlassBackdrop.current
    // v15.17：素材行必须订阅「素材版本号」。原来这里读的全是普通字段（previewBitmap/isDemo/
    // motionMp4…），导入图片或视频后 Compose 收不到任何可观察变化 → 整行被跳过 →
    // 「删除图片 / 删除视频」按钮永远不出现（用户反馈"没有删除功能，必须完全退出软件"）。
    EditorState.mediaRev
    val hasStillPhoto = EditorState.previewBitmap != null && !EditorState.isDemo &&
        !EditorState.isGif && EditorState.motionMp4 == null &&
        EditorState.motionUri == null && (EditorState.motionFrames?.size ?: 0) < 2
    val hasVideoSource = EditorState.isGif || EditorState.motionMp4 != null ||
        EditorState.motionUri != null || (EditorState.motionFrames?.size ?: 0) >= 2
    val items = ArrayList<Triple<String, () -> Unit, Color?>>()
    // v15.31：添加入口移到画布空态（选照片/视频直达相册），这里只保留已导入素材的管理按钮
    if (hasStillPhoto) items.add(Triple(stringResource(R.string.pick_image_delete), { act.deleteImportedImage() }, Color(0xFFE04545)))
    if (hasVideoSource) items.add(Triple(stringResource(R.string.pick_video_delete), { act.deleteImportedVideo() }, Color(0xFFE04545)))
    // 空态时整行隐藏（画布空态按钮即选择入口）
    if (items.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items.forEach { (label, onClick, tint) ->
            LiquidButton(
                onClick = onClick,
                backdrop = b,
                modifier = Modifier.weight(1f).height(40.dp),
                tint = tint ?: Color.Unspecified
            ) {
                Text(label, color = tMain(), fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun PreviewArea(modifier: Modifier, act: MainActivity) {
    // v15.31：订阅素材版本号，空态覆盖层随导入/删除即时出现/消失
    EditorState.mediaRev
    // v15.35：单独订阅画布方向信号（拖动参数滑块时预览区不重组，切换方向时立即生效）
    EditorState.canvasOrientRev
    val b = LocalGlassBackdrop.current
    val hasMedia = (EditorState.previewBitmap != null && !EditorState.isDemo) ||
        EditorState.motionUri != null || EditorState.motionMp4 != null
    // v15.35：画布方向自适应——自动铺满 / 横屏 16:9 / 竖屏 9:16，照片与视频都按画布等比 contain
    val canvasAspect = when (params.canvasOrient) {
        EditorState.CANVAS_LANDSCAPE -> 16f / 9f
        EditorState.CANVAS_PORTRAIT -> 9f / 16f
        else -> 0f
    }
    Box(modifier.fillMaxWidth().padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
        // v15.35：画布尺寸显式计算（不依赖 aspectRatio 的约束传递，weight 固定高度下也稳定）：
        // 有比例时按可用区域 contain，无比例（自动）时铺满。
        androidx.compose.foundation.layout.BoxWithConstraints(
            Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            val availW = maxWidth
            val availH = maxHeight
            val cw: androidx.compose.ui.unit.Dp
            val ch: androidx.compose.ui.unit.Dp
            if (canvasAspect > 0f) {
                val hFromW = availW / canvasAspect
                if (hFromW <= availH) { cw = availW; ch = hFromW }
                else { ch = availH; cw = availH * canvasAspect }
            } else { cw = availW; ch = availH }
            Box(Modifier.size(cw, ch)) {
            AndroidView(
                factory = { ctx ->
                    WatermarkEditorView(ctx).also { v ->
                        v.layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        act.preview = v
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            // v15.31：空态覆盖层 —— 画布本身就是选择入口。
            // 还没添加任何照片/视频时，中央显示「选择照片或视频」，点击直达系统相册
            // （Photo Picker：缩略图九宫格，图片+视频一屏选完）。选了之后覆盖层消失，进入预览。
            if (!hasMedia) {
                Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        LiquidButton(
                            onClick = { act.pickMediaFromGallery() },
                            backdrop = b,
                            modifier = Modifier.height(50.dp).padding(horizontal = 20.dp),
                            tint = Color(0xFF0091FF)
                        ) {
                            Text(
                                stringResource(R.string.canvas_pick_media),
                                color = tMain(), fontSize = 15.sp, fontWeight = FontWeight.SemiBold
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.canvas_pick_hint),
                            color = tSub(), fontSize = 12.sp
                        )
                        // v15.37：一次多选入口——1 张照片 + 1 段视频 直接配对成动态照片，
                        // 不必再绕去「动态照片」面板分两次选（系统文件多选，任何机型都支持）
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "一次选「照片 + 视频」配对动态照片",
                            color = Color(0xFF7EB8FF),
                            fontSize = 12.sp,
                            modifier = Modifier
                                .padding(vertical = 6.dp)
                                .clickable { act.pickPairMedia() }
                        )
                    }
                }
            }
        }
    }
}
}

@Composable
private fun BottomNav(tab: Int, onSelect: (Int) -> Unit, onDockHeight: (Int) -> Unit = {}) {
    val b = LocalGlassBackdrop.current
    val tabs = tabNames()
    // v15.39：Dock 精简到 5 项后改为「紧凑居中」——不再铺满整条屏宽，
    // 按 项数 × 62dp + 内边距 算宽度（5 项 ≈ 330dp），像 iOS 那样一条不长的悬浮胶囊，
    // 超宽屏仍封顶 DockMaxWidth；水平居中由外层 Box 的 contentAlignment 负责。
    val compactDockWidth = (62.dp * tabs.size + 22.dp).coerceAtMost(DockMaxWidth)
    // v15.35：Dock 已压缩（52dp 胶囊 + 10dp 底距），底部占位同步变小；有导航栏时用 6dp + inset。
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomPadding = if (bottomInset != 0.dp) 10.dp + bottomInset else DockBottomGap
    Box(
        Modifier
            .fillMaxWidth()
            .onSizeChanged { onDockHeight(it.height) },
        contentAlignment = Alignment.Center
    ) {
        KsuFloatingBottomBar(
            modifier = Modifier
                // KSU 源码原样：吞掉底栏空白区域的点击，避免穿透到底层画布
                .pointerInput(Unit) { detectTapGestures { } }
                // v15.36 起宽度不再 fillMaxWidth；v15.39 改为紧凑定宽 + 居中
                .width(compactDockWidth)
                .padding(start = DockSideMargin, end = DockSideMargin, bottom = bottomPadding),
            selectedIndex = tab,
            onSelected = onSelect,
            backdrop = b,
            tabsCount = tabs.size,
        ) { activateTab ->
            tabs.forEachIndexed { index, label ->
                KsuFloatingBottomBarItem(
                    selected = tab == index,
                    onClick = { activateTab(index) },
                ) {
                    KsuDockIcon(DOCK_ICON_IDS.getOrElse(index) { 5 })
                    KsuDockLabel(label)
                }
            }
        }
    }
}

// ── Tabs ──────────────────────────────────────────────────────

@Composable
private fun ShapeTab(act: MainActivity) {
    // v15.17：面板必须【自己】读 rev。act 实例永不改变，Compose 的跳过判断只比 equals，
    // 若 rev 只在父级读取，本面板会被整体跳过（函数体不执行）→ 面板内对 EditorState.params
    // 的读取全部失效：滑块弹回默认值、按钮选中色不变蓝、导入后删除按钮不出现。
    // 在本作用域读 rev 后，rev 一变本面板的重组作用域即失效，跳过被绕过。
    act.rev
    val b = LocalGlassBackdrop.current
    val p = params
    val shapeNames = listOf(
        stringResource(R.string.shape_rounded_short),
        stringResource(R.string.shape_capsule),
        stringResource(R.string.shape_ellipse),
        stringResource(R.string.shape_rect)
    )
    val shapeValues = listOf(EditorState.SHAPE_ROUNDED, EditorState.SHAPE_CAPSULE, EditorState.SHAPE_ELLIPSE, EditorState.SHAPE_RECT)
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        SectionLabel(stringResource(R.string.shape_section))
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            shapeNames.zip(shapeValues).forEach { (name, shp) ->
                LiquidButton(onClick = { p.shape = shp; act.bump() },
                    backdrop = b, modifier = Modifier.height(34.dp).weight(1f),
                    tint = if (p.shape == shp) Color(0xFF0091FF) else Color.Unspecified)
                { Text(name, color = tMain(), fontSize = 11.sp) }
            }
        }
        // v15.32 自定义裁剪比例：预览与导出同一几何（中心裁剪，仅影响照片展示，不裁剪原图数据）
        SectionLabel(stringResource(R.string.crop_section))
        val cropNames = listOf(
            stringResource(R.string.crop_original), stringResource(R.string.crop_16_9),
            stringResource(R.string.crop_4_3), stringResource(R.string.crop_3_2),
            stringResource(R.string.crop_1_1), stringResource(R.string.crop_9_16),
            stringResource(R.string.crop_3_4), stringResource(R.string.crop_2_3)
        )
        val cropValues = listOf(
            EditorState.CROP_ORIGINAL, EditorState.CROP_16_9, EditorState.CROP_4_3,
            EditorState.CROP_3_2, EditorState.CROP_1_1, EditorState.CROP_9_16,
            EditorState.CROP_3_4, EditorState.CROP_2_3
        )
        cropNames.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEachIndexed { i, name ->
                    val v = cropValues[cropNames.indexOf(name)]
                    LiquidButton(onClick = { p.cropRatio = v; act.bump() },
                        backdrop = b, modifier = Modifier.height(32.dp).weight(1f),
                        tint = if (p.cropRatio == v) Color(0xFF0091FF) else Color.Unspecified)
                    { Text(name, color = tMain(), fontSize = 11.sp) }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            LiquidButton(
                onClick = { act.rotatePhoto(); act.bump() },
                backdrop = b,
                modifier = Modifier.height(36.dp).weight(1f)
            ) {
                Text(stringResource(R.string.rotate_photo_90), color = tMain(), fontSize = 12.sp)
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            // 自定义预览背景：无照片时预览区显示用户选的图（水印/玻璃照常渲染）
            LiquidButton(
                onClick = { act.pickPreviewImage() },
                backdrop = b,
                modifier = Modifier.height(36.dp).weight(1f)
            ) {
                Text(stringResource(R.string.preview_custom_title), color = tMain(), fontSize = 12.sp)
            }
            Spacer(Modifier.width(6.dp))
            LiquidButton(
                onClick = {
                    EditorState.setPreviewCustom(null)
                    act.preview?.invalidateBlur(); act.bump()
                },
                backdrop = b,
                modifier = Modifier.height(36.dp).weight(1f),
                tint = Color(0xFFE04545)
            ) {
                Text(stringResource(R.string.preview_custom_clear), color = tMain(), fontSize = 12.sp)
            }
        }
        IntSlider(stringResource(R.string.corner_only_rounded), p.cornerPct, 0..100, "%.0f%%", b) { p.cornerPct = it; act.bump() }
        FloatSlider(stringResource(R.string.pad_h_short), p.padHDp, 0f..40f, "%.1fdp", b) { p.padHDp = it; act.bump() }
        FloatSlider(stringResource(R.string.pad_v_short), p.padVDp, 0f..30f, "%.1fdp", b) { p.padVDp = it; act.bump() }
        IntSlider(stringResource(R.string.wm_rotation), p.rotation, -180..180, "%.0f°", b) { p.rotation = it; act.bump() }
        IntSlider(stringResource(R.string.wm_scale_short), p.scalePct,
            WatermarkScale.MIN_PERCENT..WatermarkScale.MAX_PERCENT, "%.0f%%", b, gamma = 1f) {
            p.scalePct = it.coerceIn(WatermarkScale.MIN_PERCENT, WatermarkScale.MAX_PERCENT)
            act.bump()
        }
        // 方案2长度：0-100% 线性映射"自然紧凑→满宽"，整段有效无死区；方案1经典保留倍数（1x=满宽）
        if (p.layoutMode == EditorState.LAYOUT_SPLIT) {
            FloatSlider(stringResource(R.string.wm_length_short), p.wmLengthPct * 100f, 0f..100f, "%.0f%%", b) {
                p.wmLengthPct = (it / 100f); act.bump()
            }
        } else {
            FloatSlider(stringResource(R.string.wm_length_short), p.wmLengthPct, 0.01f..20f, "%.2fx", b) { p.wmLengthPct = it; act.bump() }
        }
    }
}

/**
 * v15.38：玻璃 / 光泽合并页。
 * 原来 Dock 上「玻璃」「光泽」是两个独立入口，两项都属于「玻璃材质观感」，
 * 合并后共用 Dock 上的一格，页内用子页签切换——玻璃：iOS27 风格 / 流体 / 模糊 / 色调 等；
 * 光泽：高光强度 / 边缘光 / 内阴影 / 投影 / 鲜艳度 / 高光样式。子页签互不覆盖，功能全保留。
 */
@Composable
private fun GlassGroupTab(act: MainActivity) {
    var sub by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BrandChip(stringResource(R.string.tab_glass), sub == 0) { sub = 0 }
            BrandChip(stringResource(R.string.tab_gloss), sub == 1) { sub = 1 }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            if (sub == 0) GlassTab(act) else GlossTab(act)
        }
    }
}

@Composable
private fun GlassTab(act: MainActivity) {
    // v15.17：面板必须【自己】读 rev。act 实例永不改变，Compose 的跳过判断只比 equals，
    // 若 rev 只在父级读取，本面板会被整体跳过（函数体不执行）→ 面板内对 EditorState.params
    // 的读取全部失效：滑块弹回默认值、按钮选中色不变蓝、导入后删除按钮不出现。
    // 在本作用域读 rev 后，rev 一变本面板的重组作用域即失效，跳过被绕过。
    act.rev
    val b = LocalGlassBackdrop.current
    val p = params
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        SectionLabel(stringResource(R.string.glass_section))
        // v15.28 iOS27 风格通道：默认关闭（观感与旧版一致）；开启后玻璃更通透、
        // 边缘多一条高亮"芯线"、色散更克制、内阴影更轻（引擎内低成本增强，不重写算法）
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.ios27_style),
                    color = tMain(), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(stringResource(R.string.ios27_style_hint),
                    color = tSub(), fontSize = 10.sp, maxLines = 2)
            }
            Spacer(Modifier.width(8.dp))
            LiquidToggle(selected = { p.ios27Style }, backdrop = b, onSelect = { on ->
                p.ios27Style = on
                act.bump()
            })
        }
        // v14.29 流体液态玻璃：胶囊开关（开→重载应用生效）+ 流速滑块（快/慢频率）
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.fluid_effect),
                color = tMain(), fontSize = 13.sp, modifier = Modifier.weight(1f))
            LiquidToggle(selected = { p.fluidEnabled }, backdrop = b, onSelect = { on ->
                p.fluidEnabled = on
                act.bump()
                act.onFluidToggle(on)
            })
        }
        if (p.fluidEnabled) {
            IntSlider(stringResource(R.string.fluid_speed), p.fluidSpeed, 1..100, "%.0f", b) { v ->
                p.fluidSpeed = v
                act.bump()
            }
        }
        // v14.96 呼吸动效：独立开关 + 强度（扭曲振幅随时间起伏：鼓起→停顿→再鼓起）。
        // 与流体独立：流体=相位流动，呼吸=振幅起伏；开→预览持续重绘、导出按视频时间呼吸。
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.breath_effect),
                color = tMain(), fontSize = 13.sp, modifier = Modifier.weight(1f))
            LiquidToggle(selected = { p.breathEnabled }, backdrop = b, onSelect = { on ->
                p.breathEnabled = on
                act.bump()
            })
        }
        if (p.breathEnabled) {
            IntSlider(stringResource(R.string.breath_strength), p.breathStrength, 0..100, "%.0f", b) { v ->
                p.breathStrength = v
                act.bump()
            }
        }
        // ---- v14.97 高级光学调节（折叠面板）：厚度/色散/悬浮高度/流光速度 ----
        // 四个主控直接驱动底层参数（折射强度/色散/外投影/流体速度），与旧滑块同源、双向同步。
        val advOpen = remember { mutableStateOf(false) }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.adv_opt_title),
                color = tMain(), fontSize = 13.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { advOpen.value = !advOpen.value }) {
                Text(stringResource(if (advOpen.value) R.string.adv_collapse else R.string.adv_expand),
                    color = tMain(), fontSize = 13.sp)
            }
        }
        if (advOpen.value) {
            // v14.99 边缘透镜（边缘折射率）：边缘 5-10px 放大镜式弯折，内部平直
            IntSlider(stringResource(R.string.adv_lens), p.refractA, 0..100, "%.0f", b, gamma = 1.2f) { v ->
                p.refractA = v; act.bump()
            }
            // v14.99 边缘高光（菲涅尔）：受光侧棱边亮起、暗角自动隐去（死白描边修复）
            IntSlider(stringResource(R.string.adv_edgeglow), p.edgeLight, 0..100, "%.0f", b, gamma = 1.2f) { v ->
                p.edgeLight = v; act.bump()
            }
            IntSlider(stringResource(R.string.adv_ca), p.chromatic, 0..100, "%.0f", b, gamma = 1.5f) { v ->
                p.chromatic = v; act.bump()
            }
            IntSlider(stringResource(R.string.adv_elevation), p.shadow, 0..100, "%.0f", b) { v ->
                p.shadow = v; act.bump()
            }
            IntSlider(stringResource(R.string.adv_flow),
                if (p.fluidEnabled) p.fluidSpeed else 0, 0..100, "%.0f", b) { v ->
                if (v <= 0) p.fluidEnabled = false
                else { p.fluidEnabled = true; p.fluidSpeed = v.coerceIn(1, 100) }
                act.bump()
            }
        }
        IntSlider(stringResource(R.string.blur_short), p.blur, 0..100, "%.0f", b, gamma = 1.8f) { p.blur = it; act.bump() }
        FloatSlider(stringResource(R.string.glass_contrast), p.glassContrast, 0.5f..2f, "%.2fx", b, gamma = 1.3f) { p.glassContrast = it; act.bump() }
        IntSlider(stringResource(R.string.opacity_clear), p.opacity, 0..100, "%.0f", b) { p.opacity = it; act.bump() }
        IntSlider(stringResource(R.string.refract_a_short), p.refractA, 0..100, "%.0f", b, gamma = 1.5f) { p.refractA = it; act.bump() }
        IntSlider(stringResource(R.string.refract_h_short), p.refractH, 0..100, "%.0f", b, gamma = 1.5f) { p.refractH = it; act.bump() }
        // v14.01 极致优化：边缘扭曲 + 内部扭曲合并为「统一扭曲」一个滑块——
        // warp 由低到高连续生长（低档=边缘波浪、中段=边缘+内部涟漪、高档=整体液态），单滑块全程有效
        IntSlider(stringResource(R.string.warp_strength), p.warp, 0..100, "%.0f", b, gamma = 1.35f) { w ->
            p.warp = w
            p.deriveWarp()
            act.bump()
        }
        IntSlider(stringResource(R.string.chromatic_short), p.chromatic, 0..100, "%.0f", b, gamma = 1.8f) { p.chromatic = it; act.bump() }
        IntSlider(stringResource(R.string.dispersion), p.dispersion, 0..100, "%.0f", b, gamma = 1.8f) { p.dispersion = it; act.bump() }
        IntSlider(stringResource(R.string.tint_strength_lens), p.tintStrength, 0..100, "%.0f", b, gamma = 1.5f) { p.tintStrength = it; act.bump() }
        FloatSlider(stringResource(R.string.border_width), p.borderWidth, 0f..10f, "%.1fdp", b) { p.borderWidth = it; act.bump() }
        IntSlider(stringResource(R.string.border_alpha), p.borderAlpha, 0..255, "%.0f", b) { p.borderAlpha = it; act.bump() }
        // ---- v15.16 任务2：画质档位 + 缩小补偿 ----
        // glassTier：0=high（全量色散 7 抽样 / pow 菲涅尔 / 逐像素剖面）
        //            1=low（保留法线 UV 折射 + 内部放大扭曲，色散与菲涅尔走廉价近似，不退化 alpha）
        IntSlider(stringResource(R.string.glass_tier), p.glassTier, 0..1, "%.0f", b) { v ->
            p.glassTier = v.coerceIn(EditorState.GLASS_TIER_HIGH, EditorState.GLASS_TIER_LOW)
            com.liquidglass.watermark.glass.GlassQuality.tier = p.glassTier
            act.bump()
        }
        // scaleComp：0=关闭，100=小尺寸下光学带最多放大 3.2×（按渲染短边反比补偿）
        IntSlider(stringResource(R.string.scale_comp), p.scaleComp, 0..100, "%.0f", b) { v ->
            p.scaleComp = v; act.bump()
        }
        // ---- v15.18：此前只能靠预设改的两个光学参数，开放为滑块（纯新增，不影响既有控件） ----
        FloatSlider(stringResource(R.string.hl_angle), p.hlAngle, -180f..180f, "%.0f°", b) {
            p.hlAngle = it; act.bump()
        }
        FloatSlider(stringResource(R.string.hl_falloff), p.falloff, 0.5f..3f, "%.2f", b) {
            p.falloff = it; act.bump()
        }
    }
}

@Composable
private fun GlossTab(act: MainActivity) {
    // v15.17：面板必须【自己】读 rev。act 实例永不改变，Compose 的跳过判断只比 equals，
    // 若 rev 只在父级读取，本面板会被整体跳过（函数体不执行）→ 面板内对 EditorState.params
    // 的读取全部失效：滑块弹回默认值、按钮选中色不变蓝、导入后删除按钮不出现。
    // 在本作用域读 rev 后，rev 一变本面板的重组作用域即失效，跳过被绕过。
    act.rev
    val b = LocalGlassBackdrop.current
    val p = params
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        SectionLabel(stringResource(R.string.gloss_section))
        IntSlider(stringResource(R.string.specular_short), p.specular, 0..100, "%.0f", b, gamma = 1.5f) { p.specular = it; act.bump() }
        IntSlider(stringResource(R.string.edge_light), p.edgeLight, 0..100, "%.0f", b, gamma = 1.5f) { p.edgeLight = it; act.bump() }
        IntSlider(stringResource(R.string.inner_shadow), p.innerShadow, 0..100, "%.0f", b, gamma = 1.5f) { p.innerShadow = it; act.bump() }
        IntSlider(stringResource(R.string.shadow_short), p.shadow, 0..100, "%.0f", b, gamma = 1.5f) { p.shadow = it; act.bump() }
        IntSlider(stringResource(R.string.vibrancy_short), p.vibrancy, 0..100, "%.0f", b, gamma = 1.5f) { p.vibrancy = it; act.bump() }
        IntSlider(stringResource(R.string.highlight_style), p.highlightStyle, 0..2, "%.0f", b) { p.highlightStyle = it; act.bump() }
    }
}

@Composable
private fun TextTab(act: MainActivity) {
    // v15.17：面板必须【自己】读 rev。act 实例永不改变，Compose 的跳过判断只比 equals，
    // 若 rev 只在父级读取，本面板会被整体跳过（函数体不执行）→ 面板内对 EditorState.params
    // 的读取全部失效：滑块弹回默认值、按钮选中色不变蓝、导入后删除按钮不出现。
    // 在本作用域读 rev 后，rev 一变本面板的重组作用域即失效，跳过被绕过。
    act.rev
    val b = LocalGlassBackdrop.current
    val p = params
    val resetStr = stringResource(R.string.reset_btn)
    val colorNames = listOf(
        stringResource(R.string.color_white),
        stringResource(R.string.color_black),
        stringResource(R.string.color_red),
        stringResource(R.string.color_orange),
        stringResource(R.string.color_yellow),
        stringResource(R.string.color_green),
        stringResource(R.string.color_cyan),
        stringResource(R.string.color_blue),
        stringResource(R.string.color_purple)
    )
    val colorValues = listOf(
        0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFFF3B30.toInt(),
        0xFFFF9500.toInt(), 0xFFFFCC00.toInt(), 0xFF34C759.toInt(),
        0xFF5AC8FA.toInt(), 0xFF007AFF.toInt(), 0xFFAF52DE.toInt()
    )
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        SectionLabel(stringResource(R.string.text_section))
        // ponytail: 每个输入框独立还原按钮（机型/型号/时间各一个），不是统一大按钮
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlassTextField(
                value = p.model,
                onValueChange = { p.model = it; EditorState.modelCustom = true; act.bumpLight() },
                label = stringResource(R.string.device_name_label),
                backdrop = b,
                modifier = Modifier.weight(1f).padding(vertical = 3.dp)
            )
            LiquidButton(onClick = { p.model = EditorState.defaultModelName(); EditorState.modelCustom = false; act.bump() },
                backdrop = b, modifier = Modifier.width(58.dp).height(48.dp))
            { Text(resetStr, color = tMain(), fontSize = 10.sp) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlassTextField(
                value = p.modelCode,
                onValueChange = { p.modelCode = it; EditorState.modelCodeCustom = true; act.bumpLight() },
                label = stringResource(R.string.model_code_label),
                backdrop = b,
                modifier = Modifier.weight(1f).padding(vertical = 3.dp)
            )
            LiquidButton(onClick = { p.modelCode = EditorState.defaultModelCode(); EditorState.modelCodeCustom = false; act.bump() },
                backdrop = b, modifier = Modifier.width(58.dp).height(48.dp))
            { Text(resetStr, color = tMain(), fontSize = 10.sp) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlassTextField(
                value = p.dateText,
                onValueChange = { p.dateText = it; act.bumpLight() },
                label = stringResource(R.string.datetime_label),
                backdrop = b,
                modifier = Modifier.weight(1f).padding(vertical = 3.dp)
            )
            LiquidButton(onClick = { p.dateText = EditorState.nowString(); act.bump() },
                backdrop = b, modifier = Modifier.width(58.dp).height(48.dp))
            { Text(resetStr, color = tMain(), fontSize = 10.sp) }
        }
        // v15.32 地理位置：关闭 / 相册EXIF / 当前位置 / 自定义，显示在水印日期行下方
        SectionLabel(stringResource(R.string.geo_section))
        val geoNames = listOf(
            stringResource(R.string.geo_mode_off),
            stringResource(R.string.geo_mode_exif),
            stringResource(R.string.geo_mode_current),
            stringResource(R.string.geo_mode_custom)
        )
        val geoValues = listOf(
            EditorState.GEO_OFF, EditorState.GEO_EXIF,
            EditorState.GEO_CURRENT, EditorState.GEO_CUSTOM
        )
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            geoNames.zip(geoValues).forEach { (name, v) ->
                LiquidButton(onClick = {
                    p.geoMode = v
                    if (v == EditorState.GEO_OFF) p.geoText = ""
                    act.bump()
                }, backdrop = b, modifier = Modifier.height(32.dp),
                    tint = if (p.geoMode == v) Color(0xFF0091FF) else Color.Unspecified)
                { Text(name, color = tMain(), fontSize = 11.sp) }
            }
        }
        if (p.geoMode == EditorState.GEO_CUSTOM) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlassTextField(
                    value = p.geoText,
                    onValueChange = { p.geoText = it; act.bumpLight() },
                    label = stringResource(R.string.geo_mode_custom),
                    backdrop = b,
                    modifier = Modifier.weight(1f).padding(vertical = 3.dp)
                )
                LiquidButton(onClick = { p.geoText = ""; act.bump() },
                    backdrop = b, modifier = Modifier.width(58.dp).height(48.dp))
                { Text(resetStr, color = tMain(), fontSize = 10.sp) }
            }
        } else if (p.geoMode == EditorState.GEO_EXIF) {
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                LiquidButton(onClick = { act.readExifGeoFromSource() },
                    backdrop = b, modifier = Modifier.height(34.dp).weight(1f))
                { Text(stringResource(R.string.geo_read_exif), color = tMain(), fontSize = 11.sp) }
            }
        } else if (p.geoMode == EditorState.GEO_CURRENT) {
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                LiquidButton(onClick = { act.useCurrentLocation() },
                    backdrop = b, modifier = Modifier.height(34.dp).weight(1f))
                { Text(stringResource(R.string.geo_use_current), color = tMain(), fontSize = 11.sp) }
            }
        }
        // v15.35：位置写法二选一——真实地名（省/市/区县/街道、村）或经纬度；切换用缓存坐标，不重新定位
        if (p.geoMode != EditorState.GEO_OFF && p.geoMode != EditorState.GEO_CUSTOM) {
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    stringResource(R.string.geo_style_place) to EditorState.GEO_STYLE_PLACE,
                    stringResource(R.string.geo_style_coord) to EditorState.GEO_STYLE_COORD
                ).forEach { (label, v) ->
                    LiquidButton(onClick = { act.applyGeoStyle(v) },
                        backdrop = b, modifier = Modifier.height(32.dp).weight(1f),
                        tint = if (p.geoStyle == v) Color(0xFF0091FF) else Color.Unspecified)
                    { Text(label, color = tMain(), fontSize = 11.sp) }
                }
            }
        }
        if (p.geoMode != EditorState.GEO_OFF) {
            if (p.geoText.isNotBlank()) {
                Text(p.geoText, color = tMain(), fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 2.dp))
            }
            LiquidButton(onClick = { act.clearGeo() },
                backdrop = b, modifier = Modifier.height(32.dp),
                tint = Color(0xFFE04545))
            { Text(stringResource(R.string.geo_clear), color = tMain(), fontSize = 11.sp) }
            Text(stringResource(R.string.geo_hint), color = tSub(0.4f), fontSize = 11.sp)
        }
        // ponytail: 文字颜色选择器（白/黑/赤橙黄绿青蓝紫），textColor 已持久化
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.text_color), color = tMain(), fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            colorNames.zip(colorValues).forEach { (name, color) ->
                val selected = p.textColor == color
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(Color(color))
                        .border(if (selected) 2.dp else 0.dp, Color.White, androidx.compose.foundation.shape.CircleShape)
                        .pointerInput(color) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                if (waitForUpOrCancellation() != null) {
                                    p.textColor = color; act.bump()
                                }
                            }
                        }
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        // 品牌 Logo：内置品牌位图（徕卡/蔡司/哈苏/华为/小米/vivo）+ 自定义导入，保持原始比例渲染
        Text(stringResource(R.string.brand_section), color = tMain(), fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            BrandChip(stringResource(R.string.brand_none), p.logoBrand == EditorState.BRAND_NONE) {
                p.logoBrand = EditorState.BRAND_NONE; p.brandScale = 1f; act.bump()
            }
            EditorState.BRAND_NAMES.forEachIndexed { idx, name ->
                // v14.30：自定义 logo 显示自定义名字；长按可删除
                // v14.98 修复：选项文字显示「当前激活条目的真实名字」——命名后"自定义"字样立即变成 logo 名称；
                // 找不到激活条目时回退 customLogoName / 原文字
                val label = if (idx == EditorState.BRAND_CUSTOM) {
                    p.customLogos.firstOrNull { it.path == p.customLogoPath }?.name
                        ?: p.customLogoName.takeIf { it.isNotBlank() }
                        ?: name
                } else name
                BrandChip(label, p.logoBrand == idx, onClick = {
                    if (idx == EditorState.BRAND_CUSTOM) {
                        // v14.70 多自定义 logo：有条目→弹列表（切换/删除/导入新）；无→直接导入
                        if (p.customLogos.isNotEmpty()) {
                            act.showCustomLogosDialog()
                        } else {
                            act.pickCustomLogo()
                        }
                    } else {
                        p.logoBrand = idx
                        // 切换品牌 → 载入该品牌记忆的 logo 大小（各自独立，互不影响）
                        p.brandScale = EditorState.brandScales[idx] ?: 1f
                        act.bump()
                    }
                }, onLongClick = if (idx == EditorState.BRAND_CUSTOM && p.customLogoPath.isNotEmpty())
                    ({ act.deleteCustomLogo() }) else null)
            }
            // v14.74 自定义 logo 作为预设栏独立条目：每个已保存的自定义 logo 一个方框
            //（点击切换、长按删除该条目——真正隔离，与其他互不影响）
            p.customLogos.forEach { entry ->
                BrandChip(entry.name,
                    p.logoBrand == EditorState.BRAND_CUSTOM && p.customLogoPath == entry.path,
                    onClick = { act.switchCustomLogo(entry) },
                    onLongClick = { act.deleteCustomLogoEntry(entry) })
            }
        }
        // 品牌 Logo 大小：在玻璃条安全盒内缩放（玻璃/文字不随之变大，不溢出、不压时间/竖线）。
        // 0.3x=明显缩小，2.6x=显著放大；实际尺寸再由安全区间 clamp（不压时间/机型、不溢出玻璃），
        // 故放大到上限也不会布局错乱。横长字标（Google/OPPO/真我）随高度明显变大。
        // 仅对当前品牌生效、各自记忆；要让整条水印更大请用"整体缩放"。
        FloatSlider(stringResource(R.string.brand_size), p.brandScale, 0.3f..2.6f, "%.2fx", b) {
            p.brandScale = it
            EditorState.brandScales[p.logoBrand] = it
            act.bump()
        }
        Spacer(Modifier.height(6.dp))
        // logo 位置：前（文字左）/ 后（文字右）/ 居中（纯 logo 水印）
        Text(stringResource(R.string.logo_align), color = tSub(0.7f), fontSize = 13.sp)
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                stringResource(R.string.logo_align_front) to 0,
                stringResource(R.string.logo_align_back) to 1,
                stringResource(R.string.logo_align_center) to 2
            ).forEach { (label, v) ->
                LiquidButton(onClick = { p.logoAlign = v; act.bump() },
                    backdrop = b, modifier = Modifier.height(36.dp),
                    tint = if (p.logoAlign == v) Color(0xFF0091FF) else Color.Unspecified)
                { Text(label, color = tMain(), fontSize = 11.sp) }
            }
        }
        Spacer(Modifier.height(6.dp))
        FloatSlider(stringResource(R.string.title_size_short), p.titleSp, 1f..160f, "%.1fsp", b) { p.titleSp = it; act.bump() }
        FloatSlider(stringResource(R.string.sub_size_short), p.subSp, 1f..120f, "%.1fsp", b) { p.subSp = it; act.bump() }
        // v15.33 字体集成面板：主/型号/时间/地理位置 各自「大小 + 左右」，再加整体偏移/行距/字间距
        SectionLabel(stringResource(R.string.font_panel_section))
        // 主字体
        Text(stringResource(R.string.font_title_line), color = tSub(0.7f), fontSize = 12.sp)
        FloatSlider(stringResource(R.string.font_size), p.fontTitleScale, 0.5f..2.5f, "%.2fx", b) { p.fontTitleScale = it; act.bump() }
        FloatSlider(stringResource(R.string.font_shift), p.fontTitleShift, -60f..60f, "%.0f", b) { p.fontTitleShift = it; act.bump() }
        // 型号
        Text(stringResource(R.string.font_code_line), color = tSub(0.7f), fontSize = 12.sp)
        FloatSlider(stringResource(R.string.font_size), p.fontCodeScale, 0.5f..2.5f, "%.2fx", b) { p.fontCodeScale = it; act.bump() }
        FloatSlider(stringResource(R.string.font_shift), p.fontCodeShift, -60f..60f, "%.0f", b) { p.fontCodeShift = it; act.bump() }
        FloatSlider(stringResource(R.string.font_code_dy), p.fontCodeDy, -40f..40f, "%.0f", b) { p.fontCodeDy = it; act.bump() }
        // 时间
        Text(stringResource(R.string.font_time_line), color = tSub(0.7f), fontSize = 12.sp)
        FloatSlider(stringResource(R.string.font_size), p.fontTimeScale, 0.5f..2.5f, "%.2fx", b) { p.fontTimeScale = it; act.bump() }
        FloatSlider(stringResource(R.string.font_shift), p.fontTimeShift, -60f..60f, "%.0f", b) { p.fontTimeShift = it; act.bump() }
        // 地理位置
        Text(stringResource(R.string.font_geo_line), color = tSub(0.7f), fontSize = 12.sp)
        FloatSlider(stringResource(R.string.font_size), p.fontGeoScale, 0.5f..2.5f, "%.2fx", b) { p.fontGeoScale = it; act.bump() }
        FloatSlider(stringResource(R.string.font_shift), p.fontGeoShift, -60f..60f, "%.0f", b) { p.fontGeoShift = it; act.bump() }
        // v15.35：型号/时间/位置上下排布——开关把位置行放到时间上方，滑块微调各行上下
        ToggleRow(stringResource(R.string.geo_above_time), p.geoAboveTime, b) { p.geoAboveTime = it; act.bump() }
        FloatSlider(stringResource(R.string.font_time_dy), p.fontTimeDy, -40f..40f, "%.0f", b) { p.fontTimeDy = it; act.bump() }
        FloatSlider(stringResource(R.string.font_geo_dy), p.fontGeoDy, -40f..40f, "%.0f", b) { p.fontGeoDy = it; act.bump() }
        // 整体
        FloatSlider(stringResource(R.string.font_block_shift), p.fontBlockShift, -60f..60f, "%.0f", b) { p.fontBlockShift = it; act.bump() }
        FloatSlider(stringResource(R.string.font_line_gap), p.fontLineGap, -8f..24f, "%.0f", b) { p.fontLineGap = it; act.bump() }
        FloatSlider(stringResource(R.string.font_letter_spacing), p.fontLetterSpacing, 0f..0.6f, "%.2f", b) { p.fontLetterSpacing = it; act.bump() }
        ToggleRow(stringResource(R.string.font_shadow), p.fontShadow, b) { p.fontShadow = it; act.bump() }
        ToggleRow(stringResource(R.string.auto_tz), p.autoTz, b) { p.autoTz = it; act.bump() }
        ToggleRow(stringResource(R.string.show_device_name), p.showDevice, b) { p.showDevice = it; act.bump() }
        ToggleRow(stringResource(R.string.show_time_sec), p.showTime, b) { p.showTime = it; act.bump() }
        ToggleRow(stringResource(R.string.live_seconds), EditorState.liveSeconds, b) { EditorState.liveSeconds = it; act.bump() }
        ToggleRow(stringResource(R.string.font_contrast), p.fontContrast, b) { p.fontContrast = it; act.bump() }
    }
}

/** 品牌标识选择 chip：选中态高亮边框；点击回调（自定义入口由调用方决定） */
@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun BrandChip(label: String, selected: Boolean, onLongClick: (() -> Unit)? = null, onClick: () -> Unit = {}) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier
            .glassSurface(shape = shape)
            .clip(shape)
            .background(
                if (selected) Color(0xFF0091FF).copy(alpha = 0.22f)
                else Color.White.copy(alpha = 0.08f)
            )
            .border(
                if (selected) 1.5.dp else 0.dp,
                if (selected) Color(0xFF0091FF) else Color.Transparent,
                shape
            )
            .padding(horizontal = 11.dp, vertical = 6.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Text(label, color = tMain(), fontSize = 12.sp)
    }
}

@Composable
private fun PosTab(act: MainActivity) {
    // v15.17：面板必须【自己】读 rev。act 实例永不改变，Compose 的跳过判断只比 equals，
    // 若 rev 只在父级读取，本面板会被整体跳过（函数体不执行）→ 面板内对 EditorState.params
    // 的读取全部失效：滑块弹回默认值、按钮选中色不变蓝、导入后删除按钮不出现。
    // 在本作用域读 rev 后，rev 一变本面板的重组作用域即失效，跳过被绕过。
    act.rev
    val b = LocalGlassBackdrop.current
    val p = params
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        SectionLabel(stringResource(R.string.pos_section))
        FloatSlider(stringResource(R.string.pos_x), p.relX * 100, 0f..100f, "%.0f%%", b) { p.relX = it / 100f; act.bump() }
        FloatSlider(stringResource(R.string.pos_y), p.relY * 100, 0f..100f, "%.0f%%", b) { p.relY = it / 100f; act.bump() }
        Spacer(Modifier.height(8.dp))
        // v15.35：画布方向（自动铺满 / 横屏 16:9 / 竖屏 9:16），照片与视频都按画布 contain
        Text(stringResource(R.string.canvas_orient), color = tSub(0.7f), fontSize = 13.sp)
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                stringResource(R.string.canvas_auto) to EditorState.CANVAS_AUTO,
                stringResource(R.string.canvas_land) to EditorState.CANVAS_LANDSCAPE,
                stringResource(R.string.canvas_port) to EditorState.CANVAS_PORTRAIT
            ).forEach { (label, v) ->
                LiquidButton(onClick = {
                    p.canvasOrient = v
                    EditorState.canvasOrientRev++
                    act.bump()
                },
                    backdrop = b, modifier = Modifier.height(34.dp).weight(1f),
                    tint = if (p.canvasOrient == v) Color(0xFF0091FF) else Color.Unspecified)
                { Text(label, color = tMain(), fontSize = 11.sp) }
            }
        }
        Text(stringResource(R.string.drag_hint_short), color = tSub(0.4f), fontSize = 12.sp)
    }
}

@Composable
private fun SettingsTab(act: MainActivity) {
    // v15.17：面板必须【自己】读 rev。act 实例永不改变，Compose 的跳过判断只比 equals，
    // 若 rev 只在父级读取，本面板会被整体跳过（函数体不执行）→ 面板内对 EditorState.params
    // 的读取全部失效：滑块弹回默认值、按钮选中色不变蓝、导入后删除按钮不出现。
    // 在本作用域读 rev 后，rev 一变本面板的重组作用域即失效，跳过被绕过。
    act.rev
    val b = LocalGlassBackdrop.current
    val p = params
    val ctx = LocalContext.current
    val presetNames = listOf(
        stringResource(R.string.preset_ios_short),
        stringResource(R.string.preset_vivo_short),
        stringResource(R.string.preset_xiaomi_short),
        stringResource(R.string.preset_huawei_short),
        stringResource(R.string.preset_leica_short),
        stringResource(R.string.preset_crystal_short),
        stringResource(R.string.preset_vitremark_short)
    )
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        SectionLabel(stringResource(R.string.settings_section))
        // 深色/浅色模式：胶囊开关（用户要求放设置页内、胶囊开合，不放顶栏）
        Text(stringResource(R.string.dark_mode), color = tSub(0.7f), fontSize = 13.sp)
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LiquidButton(onClick = {
                    setDarkMode(true)
                    act.bump()
                },
                backdrop = b, modifier = Modifier.height(36.dp),
                tint = if (darkModeState) Color(0xFF0091FF) else Color.Unspecified)
            { Text(stringResource(R.string.theme_dark_short), color = tMain(), fontSize = 11.sp) }
            LiquidButton(onClick = {
                    setDarkMode(false)
                    act.bump()
                },
                backdrop = b, modifier = Modifier.height(36.dp),
                tint = if (!darkModeState) Color(0xFF0091FF) else Color.Unspecified)
            { Text(stringResource(R.string.theme_light_short), color = tMain(), fontSize = 11.sp) }
        }
        // ── v15.15 字体模式：默认 / 系统 / 衬线 / 等宽（切换后即时生效，无需重启）──
        // v15.17：原来只有「默认=sans-serif」和「系统=Typeface.DEFAULT」两项，二者在绝大多数设备
        // 解析到同一字族 → 切换后画面零变化（用户反馈"字体切换切换不了"）。补两种字形差异明显的字族。
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.font_mode_section), color = tSub(0.7f), fontSize = 13.sp)
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                com.liquidglass.watermark.FONT_MODE_DEFAULT to R.string.font_mode_default,
                com.liquidglass.watermark.FONT_MODE_SYSTEM to R.string.font_mode_system,
                com.liquidglass.watermark.FONT_MODE_SERIF to R.string.font_mode_serif,
                com.liquidglass.watermark.FONT_MODE_MONO to R.string.font_mode_mono
            ).forEach { (mode, labelRes) ->
                LiquidButton(onClick = {
                        p.fontMode = mode
                        act.bump()
                    },
                    backdrop = b, modifier = Modifier.height(36.dp),
                    tint = if (p.fontMode == mode) Color(0xFF0091FF) else Color.Unspecified)
                { Text(stringResource(labelRes), color = tMain(), fontSize = 11.sp) }
            }
        }
        Text(stringResource(R.string.font_mode_hint), color = tSub(0.55f), fontSize = 10.sp)
        Spacer(Modifier.height(6.dp))
        ToggleRow(stringResource(R.string.software_blur), p.softwareBlur, b) {
            p.softwareBlur = it
            com.liquidglass.watermark.glass.backdrops.softwareBlurEnabled = it
            act.bump()
        }
        ToggleRow(stringResource(R.string.clean_auto_short), p.autoClean, b) {
            p.autoClean = it
            act.bump()
        }
        Spacer(Modifier.height(10.dp))
        // 方案切换：方案1=经典、方案2=新款浅色，两套参数完全独立（各自持久化，切换互不覆盖）
        Text(stringResource(R.string.plan_section), color = tSub(0.7f), fontSize = 13.sp)
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val cur = EditorState.activePlan
            LiquidButton(onClick = {
                    EditorState.switchPlan(ctx, EditorState.PLAN_CLASSIC)
                    act.bump()
                },
                backdrop = b, modifier = Modifier.height(36.dp),
                tint = if (cur == EditorState.PLAN_CLASSIC) Color(0xFF0091FF) else Color.Unspecified)
            { Text(stringResource(R.string.plan_classic), color = tMain(), fontSize = 11.sp) }
            LiquidButton(onClick = {
                    EditorState.switchPlan(ctx, EditorState.PLAN_LIGHT)
                    act.bump()
                },
                backdrop = b, modifier = Modifier.height(36.dp),
                tint = if (cur == EditorState.PLAN_LIGHT) Color(0xFF0091FF) else Color.Unspecified)
            { Text(stringResource(R.string.plan_light), color = tMain(), fontSize = 11.sp) }
        }
        Spacer(Modifier.height(4.dp))
        // 方案独立重置：只清对应方案参数，不影响另一方案
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LiquidButton(onClick = {
                    EditorState.resetPlan(ctx, EditorState.PLAN_CLASSIC)
                    act.bump()
                },
                backdrop = b, modifier = Modifier.height(32.dp))
            { Text(stringResource(R.string.reset_plan1), color = tSub(0.8f), fontSize = 10.sp) }
            LiquidButton(onClick = {
                    EditorState.resetPlan(ctx, EditorState.PLAN_LIGHT)
                    act.bump()
                },
                backdrop = b, modifier = Modifier.height(32.dp))
            { Text(stringResource(R.string.reset_plan2), color = tSub(0.8f), fontSize = 10.sp) }
        }
        Spacer(Modifier.height(10.dp))
        // v14.49 导出模式三段胶囊（快速/混合/CPU，单选一个）
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(
                stringResource(R.string.export_mode_fast) to 0,
                stringResource(R.string.export_mode_hybrid) to 1,
                stringResource(R.string.export_mode_cpu2) to 2
            ).forEach { (label, mode) ->
                LiquidButton(onClick = {
                    p.exportMode = mode
                    EditorState.persist(act)
                    act.bump()
                }, backdrop = b, modifier = Modifier.weight(1f).height(36.dp),
                    tint = if (p.exportMode == mode) Color(0xFF0091FF) else Color.Unspecified)
                { Text(label, color = tMain(), fontSize = 11.sp, fontWeight = FontWeight.Medium) }
            }
        }
            Text(stringResource(R.string.api_mode), color = tSub(0.7f), fontSize = 13.sp)
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LiquidButton(onClick = { p.apiMode = 0; act.applyApiMode(); act.bump() },
                backdrop = b, modifier = Modifier.height(36.dp),
                tint = if (p.apiMode == 0) Color(0xFF0091FF) else Color.Unspecified)
            { Text(stringResource(R.string.api_auto), color = tMain(), fontSize = 11.sp) }
            LiquidButton(onClick = { p.apiMode = 1; act.applyApiMode(); act.bump() },
                backdrop = b, modifier = Modifier.height(36.dp),
                tint = if (p.apiMode == 1) Color(0xFF0091FF) else Color.Unspecified)
            { Text(stringResource(R.string.api_high), color = tMain(), fontSize = 11.sp) }
            LiquidButton(onClick = { p.apiMode = 2; act.applyApiMode(); act.bump() },
                backdrop = b, modifier = Modifier.height(36.dp),
                tint = if (p.apiMode == 2) Color(0xFF0091FF) else Color.Unspecified)
            { Text(stringResource(R.string.api_low), color = tMain(), fontSize = 11.sp) }
        }
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.language), color = tSub(0.7f), fontSize = 13.sp)
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                stringResource(R.string.lang_zh) to "zh",
                stringResource(R.string.lang_en) to "en",
                stringResource(R.string.lang_hi) to "hi"
            ).forEach { (label, code) ->
                LiquidButton(onClick = { act.setAppLocale(code) },
                    backdrop = b, modifier = Modifier.height(36.dp))
                { Text(label, color = tMain(), fontSize = 11.sp) }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.preset_section), color = tSub(0.7f), fontSize = 13.sp)
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            presetNames.forEachIndexed { i, name ->
                LiquidButton(onClick = { act.applyPreset(i) },
                    backdrop = b, modifier = Modifier.height(36.dp))
                { Text(name, color = tMain(), fontSize = 11.sp) }
            }
        }
        Spacer(Modifier.height(10.dp))
        // 我的预设：保存当前参数 / 分享给他人 / 从文件导入；本地预设点击应用、长按删除
        Text(stringResource(R.string.user_preset_section), color = tSub(0.7f), fontSize = 13.sp)
        Spacer(Modifier.height(4.dp))
        var presetTick by remember { mutableStateOf(0) }
        var showSavePreset by remember { mutableStateOf(false) }
        val userPresets = remember(presetTick) { com.liquidglass.watermark.PresetIO.listLocal(ctx) }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LiquidButton(onClick = { showSavePreset = true },
                backdrop = b, modifier = Modifier.height(34.dp))
            { Text(stringResource(R.string.preset_save), color = tMain(), fontSize = 11.sp) }
            LiquidButton(onClick = { act.sharePreset() },
                backdrop = b, modifier = Modifier.height(34.dp))
            { Text(stringResource(R.string.preset_share_btn), color = tMain(), fontSize = 11.sp) }
            LiquidButton(onClick = { act.pickPreset() },
                backdrop = b, modifier = Modifier.height(34.dp))
            { Text(stringResource(R.string.preset_import), color = tMain(), fontSize = 11.sp) }
        }
        userPresets.forEach { nm ->
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LiquidButton(onClick = {
                        val ok = com.liquidglass.watermark.PresetIO.loadLocal(ctx, nm)
                        android.widget.Toast.makeText(ctx,
                            if (ok) ctx.getString(R.string.preset_apply_ok, nm)
                            else ctx.getString(R.string.preset_import_fail),
                            android.widget.Toast.LENGTH_SHORT).show()
                        if (ok) act.bump()
                    },
                    backdrop = b, modifier = Modifier.height(32.dp).weight(1f))
                { Text(nm, color = tMain(), fontSize = 11.sp, maxLines = 1) }
                LiquidButton(onClick = {
                        com.liquidglass.watermark.PresetIO.deleteLocal(ctx, nm)
                        presetTick++
                    },
                    backdrop = b, modifier = Modifier.height(32.dp).width(56.dp))
                { Text(stringResource(R.string.preset_delete), color = tSub(0.8f), fontSize = 10.sp) }
            }
        }
        if (showSavePreset) {
            var presetName by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showSavePreset = false },
                modifier = Modifier.glassSurface(backdrop = b, shape = RoundedCornerShape(28.dp), prominent = true),
                containerColor = Color.Transparent,
                title = { Text(stringResource(R.string.preset_save), color = tMain(), fontSize = 14.sp) },
                text = {
                    OutlinedTextField(
                        value = presetName,
                        onValueChange = { presetName = it.take(24) },
                        singleLine = true,
                        label = { Text(stringResource(R.string.preset_name_hint), fontSize = 12.sp) }
                    )
                },
                confirmButton = {
                    LiquidButton(onClick = {
                        if (presetName.isNotBlank()) {
                            com.liquidglass.watermark.PresetIO.saveLocal(ctx, presetName)
                            showSavePreset = false
                            presetTick++
                        }
                    }, backdrop = b, modifier = Modifier.height(36.dp), tint = Color(0xFF0091FF))
                    { Text(stringResource(R.string.preset_save_confirm), color = tMain(), fontSize = 13.sp) }
                },
                dismissButton = {
                    LiquidButton(onClick = { showSavePreset = false },
                        backdrop = b, modifier = Modifier.height(36.dp))
                    { Text(stringResource(R.string.preset_cancel), color = tSub(0.8f), fontSize = 13.sp) }
                }
            )
        }
        Spacer(Modifier.height(16.dp))
        // 真实版本号：直接从 PackageManager 读取，与 build.gradle 一致，杜绝显示旧版本
        val verName = remember {
            runCatching {
                ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
            }.getOrNull() ?: "?"
        }
        Text(stringResource(R.string.version_info, verName, android.os.Build.VERSION.RELEASE, android.os.Build.VERSION.SDK_INT),
            color = tSub(0.45f), fontSize = 11.sp)
        Spacer(Modifier.height(8.dp))
        // 导出日志：直接弹窗显示日志全文，用户截图发给开发者即可，无需翻文件系统
        var showLog by remember { mutableStateOf(false) }
        LiquidButton(onClick = { showLog = true },
            backdrop = b, modifier = Modifier.height(38.dp).fillMaxWidth())
        { Text(stringResource(R.string.export_log), color = tMain(), fontSize = 12.sp) }
        if (showLog) {
            val logText = remember { com.liquidglass.watermark.DebugLog.readRecent() }
            AlertDialog(
                onDismissRequest = { showLog = false },
                modifier = Modifier.glassSurface(backdrop = b, shape = RoundedCornerShape(28.dp), prominent = true),
                containerColor = Color.Transparent,
                title = { Text(stringResource(R.string.export_log_title), color = tMain(), fontSize = 14.sp) },
                text = {
                    androidx.compose.foundation.rememberScrollState().let { sc ->
                        Text(logText, color = tSub(0.8f), fontSize = 10.sp,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)
                                .verticalScroll(sc))
                    }
                },
                confirmButton = {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        LiquidButton(onClick = {
                            // 删除全部日志（私有主日志 + Download 副日志 + Download/导出日志/ 所有副本）
                            val n = com.liquidglass.watermark.DebugLog.deleteAllLogs()
                            val app = ctx.applicationContext
                            android.widget.Toast.makeText(
                                app,
                                app.getString(R.string.delete_logs_done, n),
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                            showLog = false
                        }, backdrop = b, modifier = Modifier.height(36.dp), tint = Color(0xFFE04545))
                        { Text(stringResource(R.string.delete_logs), color = tMain(), fontSize = 13.sp) }
                        LiquidButton(onClick = { showLog = false },
                            backdrop = b, modifier = Modifier.height(36.dp))
                        { Text(stringResource(R.string.export_log_close), color = tMain(), fontSize = 13.sp) }
                    }
                },
                dismissButton = {
                    LiquidButton(onClick = {
                        // 导出完整日志文件到公共 Download/导出日志/，不依赖截图（截屏只能看到尾部 4000 字符）
                        val p = com.liquidglass.watermark.DebugLog.exportToDownload()
                        if (p != null) {
                            val app = ctx.applicationContext
                            android.widget.Toast.makeText(
                                app,
                                app.getString(R.string.export_log_done, p),
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                            showLog = false
                        } else {
                            android.widget.Toast.makeText(
                                ctx.applicationContext,
                                "日志导出失败，请检查存储空间",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }, backdrop = b, modifier = Modifier.height(36.dp), tint = Color(0xFF0091FF))
                    { Text(stringResource(R.string.export_to_download), color = tMain(), fontSize = 13.sp) }
                }
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

// ── Helpers ───────────────────────────────────────────────────

@Composable
private fun IntSlider(
    title: String, value: Int, range: IntRange, fmt: String,
    backdrop: Backdrop, gamma: Float = 1f, onChange: (Int) -> Unit
) {
    val min = range.first; val max = range.last; val span = max - min
    val pos = ((value - min).toFloat() / span).coerceIn(0f, 1f)
    // 实际值 → 滑块物理位置（反向 gamma）
    val sliderPos = if (gamma == 1f) pos else Math.pow(pos.toDouble(), 1.0 / gamma).toFloat()
    // v14.50 滑块间距加大（8dp）：防拖动误触相邻滑块
    Column(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = tMain(), fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Text(String.format(fmt, value.toFloat()), color = tSub(0.5f), fontSize = 11.sp)
        }
        LiquidSlider(
            value = { sliderPos },
            onValueChange = { p ->
                val actualPos = if (gamma == 1f) p.coerceIn(0f, 1f)
                    else Math.pow(p.coerceIn(0f, 1f).toDouble(), gamma.toDouble()).toFloat()
                com.liquidglass.watermark.EditorState.lastSliderMoveMs = System.currentTimeMillis()
                val v = (min + span * actualPos).roundToInt()
                com.liquidglass.watermark.DebugLog.throttleLog(
                    "TOUCH", "滑块[$title]拖动=$v 范围=$min..$max", 300L)
                onChange(v)
            },
            valueRange = 0f..1f,
            visibilityThreshold = 0.001f,
            backdrop = backdrop,
            onDragStart = {
                com.liquidglass.watermark.EditorState.sliderInteracting = true
                com.liquidglass.watermark.DebugLog.log(
                    "TOUCH", "滑块[$title]按下 当前值=$value 范围=$min..$max")
            },
            // 松手：复位轻量渲染标志，并用最终值同值重触发（bump）→ 完整玻璃渲染
            // 关键：不能用闭包捕获的旧 value（会把滑块值重置回拖动前，v13.88 的 bug）
            onDragEnd = { finalPos ->
                com.liquidglass.watermark.EditorState.sliderInteracting = false
                val ap = if (gamma == 1f) finalPos.coerceIn(0f, 1f)
                    else Math.pow(finalPos.coerceIn(0f, 1f).toDouble(), gamma.toDouble()).toFloat()
                val v = (min + span * ap).roundToInt()
                com.liquidglass.watermark.DebugLog.log("TOUCH", "滑块[$title]松手 最终值=$v")
                onChange(v)
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun FloatSlider(
    title: String, value: Float, range: ClosedFloatingPointRange<Float>, fmt: String,
    backdrop: Backdrop, gamma: Float = 1f, onChange: (Float) -> Unit
) {
    val min = range.start; val max = range.endInclusive; val span = max - min
    val pos = ((value - min) / span).coerceIn(0f, 1f)
    val sliderPos = if (gamma == 1f) pos else Math.pow(pos.toDouble(), 1.0 / gamma).toFloat()
    // v14.50 滑块间距加大（8dp）：拖动一个滑块不会误触相邻滑块/页面滚动
    Column(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = tMain(), fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Text(String.format(fmt, value), color = tSub(0.5f), fontSize = 11.sp)
        }
        LiquidSlider(
            value = { sliderPos },
            onValueChange = { p ->
                val actualPos = if (gamma == 1f) p.coerceIn(0f, 1f)
                    else Math.pow(p.coerceIn(0f, 1f).toDouble(), gamma.toDouble()).toFloat()
                com.liquidglass.watermark.EditorState.lastSliderMoveMs = System.currentTimeMillis()
                val v = min + span * actualPos
                com.liquidglass.watermark.DebugLog.throttleLog(
                    "TOUCH", "滑块[$title]拖动=$v 范围=$min..$max", 300L)
                onChange(v)
            },
            valueRange = 0f..1f,
            visibilityThreshold = 0.001f,
            backdrop = backdrop,
            onDragStart = {
                com.liquidglass.watermark.EditorState.sliderInteracting = true
                com.liquidglass.watermark.DebugLog.log(
                    "TOUCH", "滑块[$title]按下 当前值=$value 范围=$min..$max")
            },
            // 松手：复位轻量渲染标志，并用最终值同值重触发（bump）→ 完整玻璃渲染
            onDragEnd = { finalPos ->
                com.liquidglass.watermark.EditorState.sliderInteracting = false
                val ap = if (gamma == 1f) finalPos.coerceIn(0f, 1f)
                    else Math.pow(finalPos.coerceIn(0f, 1f).toDouble(), gamma.toDouble()).toFloat()
                val v = min + span * ap
                com.liquidglass.watermark.DebugLog.log("TOUCH", "滑块[$title]松手 最终值=$v")
                onChange(v)
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun ToggleRow(title: String, checked: Boolean, backdrop: Backdrop, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = tMain(), fontSize = 12.sp)
        Spacer(Modifier.weight(1f))
        LiquidToggle(selected = { checked }, onSelect = onChange, backdrop = backdrop)
    }
}
