package com.liquidglass.watermark.ui

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.windowInsetsPadding
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
import kotlinx.coroutines.delay
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
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.platform.LocalConfiguration
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

    stringResource(R.string.tab_appearance),
    stringResource(R.string.tab_text),
    stringResource(R.string.tab_pos),
    stringResource(R.string.tab_settings)
)

private val DOCK_ICON_IDS = listOf(0, 3, 4, 5)

private val PanelNavInset = 152.dp

@Composable
private fun tMain(): Color = if (darkModeState) Color(0xFFF5F7FA) else Color(0xFF1A1D24)
@Composable
private fun tSub(alpha: Float = 0.55f): Color =
    if (darkModeState) Color.White.copy(alpha = alpha) else Color(0xFF5A6478).copy(alpha = alpha + 0.15f)

@Composable
private fun fieldColors() = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Color(0xFF0091FF),
    unfocusedBorderColor = if (darkModeState) Color.White.copy(alpha = 0.35f) else Color(0xFF9AA2B0),
    cursorColor = Color(0xFF0091FF),
    focusedLabelColor = Color(0xFF0091FF),
    unfocusedLabelColor = tSub(0.6f)
)

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

    val density = LocalDensity.current

    //

    val panelBackdrop = rememberLayerBackdrop()

    var panelSize by remember { mutableStateOf(IntSize.Zero) }

    var panelPressed by remember { mutableStateOf(false) }
    var panelUpAt by remember { mutableLongStateOf(android.os.SystemClock.uptimeMillis()) }

    PrepareSoftwareGlassBackdropLive(
        panelBackdrop,
        panelSize,
        revision = tab,
        isIdle = {
            !panelPressed &&
                android.os.SystemClock.uptimeMillis() - panelUpAt > 700L
        },

        blurAmount = { EditorState.dockBlur.coerceIn(0, 100) / 100f },
    )

    //

    LaunchedEffect(panelBackdrop) {

        panelBackdrop.recordThrottleMs = 400L

        if (!com.liquidglass.watermark.glass.isRenderEffectSupported()) {

            panelBackdrop.layerScale = 0.75f
        }
        while (true) {
            delay(80)
            val idle = android.os.SystemClock.uptimeMillis() - panelUpAt
            if (!panelPressed && idle > 300L && panelBackdrop.liveMode) {
                panelBackdrop.liveMode = false
            }

            if (!panelPressed && idle > 700L && panelBackdrop.capturePaused) {
                panelBackdrop.capturePaused = false
            }
        }
    }
    var dockHeightPx by remember { mutableIntStateOf(-1) }
    val dockSlot: androidx.compose.ui.unit.Dp =

        if (dockHeightPx >= 0) with(density) { dockHeightPx.toDp() } + 6.dp
        else DockPillHeight + DockBottomGap + 6.dp

    //

    //

    Box(
        Modifier
            .fillMaxSize()

            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        val down = event.changes.any { it.pressed }
                        if (down) {
                            panelPressed = true
                            panelBackdrop.capturePaused = true
                        } else if (panelPressed) {
                            panelPressed = false
                            panelUpAt = android.os.SystemClock.uptimeMillis()
                        }
                    }
                }
            }
    ) {
        Column(Modifier.fillMaxSize()) {
            TopBar(act)
            MaterialRow(act)
            Box(Modifier.weight(1f)) {
                PreviewArea(Modifier.fillMaxSize(), act)

                if (act.progressPct >= 0) {
                    val pb = LocalGlassBackdrop.current
                    Box(
                        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(stringResource(R.string.exporting, act.progressPct), color = tMain(), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(14.dp))

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

                    .height(232.dp + dockSlot)
                    .onSizeChanged { panelSize = it }

                    //

                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            var downPos: androidx.compose.ui.geometry.Offset? = null
                            var slopHit = false
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Final)
                                val change = event.changes.firstOrNull() ?: continue
                                if (change.pressed) {
                                    val start = downPos
                                    if (start == null) {
                                        downPos = change.position
                                        slopHit = false
                                    } else if (!slopHit &&
                                        (change.position - start).getDistance() >
                                        viewConfiguration.touchSlop
                                    ) {
                                        slopHit = true
                                        panelBackdrop.liveMode = true
                                    }
                                } else {
                                    downPos = null
                                    slopHit = false
                                }
                            }
                        }
                    }

                    .layerBackdrop(panelBackdrop)
                    .glassSurface()

                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(start = 10.dp, end = 10.dp, top = 8.dp)

                    .pointerInput(act.progressPct >= 0) {
                        if (act.progressPct >= 0) awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                            }
                        }
                    }
            ) {

                //

                when (tab) {
                    0 -> AppearanceTab(act)
                    1 -> TextTab(act)
                    2 -> PosTab(act)
                    else -> SettingsTab(act)
                }
            }

        }

        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
        ) {
            Column(Modifier.fillMaxWidth()) {
                ExportBar(act)
                Spacer(Modifier.height(4.dp))
                BottomNav(tab, { tab = it }, onDockHeight = { dockHeightPx = it }, backdrop = panelBackdrop)
            }
        }

        if (act.showMotionPhotoPanel) {
            MotionPhotoPanel(act)
        }

        if (act.showGifPanel) {
            GifPanel(act)
        }
    }
}

@Composable
private fun ExportBar(act: MainActivity) {
    val b = LocalGlassBackdrop.current
    val cur = EditorState.motionExportFormat

    EditorState.exportBarRev
    EditorState.mediaRev

    val locked = act.progressPct >= 0
    Row(

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

        Text(stringResource(R.string.export_fmt), color = tSub(), fontSize = 12.sp)

        FormatChip(act, b, "mp4", "MP4", cur) { EditorState.motionExportFormat = "mp4"; EditorState.persist(act) }
        if (Mp4Exporter.hasHevcEncoder())
            FormatChip(act, b, "hevc", "HEVC", cur) { EditorState.motionExportFormat = "hevc"; EditorState.persist(act) }
        FormatChip(act, b, "gif", "GIF", cur) {

            act.openGifPanel()
        }
        GroupDivider()

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

@Composable
private fun GroupDivider() {
    Box(
        Modifier
            .width(1.dp)
            .height(20.dp)
            .background(Color(0x26FFFFFF))
    )
}

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

            LiquidButton(
                onClick = { act.openGifPanel() },
                backdrop = b,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(44.dp)
            ) {
                Text(stringResource(R.string.gif_entry), color = tMain(), fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(8.dp))

            LiquidButton(
                onClick = { act.pickWholeMotionPhoto() },
                backdrop = b,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(44.dp),
                tint = if (act.mpImage != null && act.mpVideoSet) Color(0xFF0091FF) else Color.Unspecified
            ) {
                Text(stringResource(R.string.mp_pick_whole), color = tMain(), fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(10.dp))

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

            GifTimelineEditor(
                durationMs = act.mpVideoDurationMs(),
                onChanged = { act.updateMotionTrim() },
                mode = 1
            )
            Spacer(Modifier.height(10.dp))

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

            LiquidButton(
                onClick = { act.composeMotionPhoto() },
                backdrop = b,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(46.dp),
                tint = Color(0xFF0091FF)
            ) {
                Text(stringResource(R.string.mp_compose), color = tMain(), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(10.dp))

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

@Composable
private fun GifPanel(act: MainActivity) {
    val b = LocalGlassBackdrop.current

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

                LiquidButton(
                    onClick = { act.previewGifTrim() },
                    backdrop = b,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(38.dp),
                    tint = Color(0xFF5AC8FA)
                ) {
                    Text(stringResource(R.string.gif_preview_clip), color = tMain(), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(10.dp))

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

@Composable
private fun MaterialRow(act: MainActivity) {
    val b = LocalGlassBackdrop.current

    EditorState.mediaRev
    val hasStillPhoto = EditorState.previewBitmap != null && !EditorState.isDemo &&
        !EditorState.isGif && EditorState.motionMp4 == null &&
        EditorState.motionUri == null && (EditorState.motionFrames?.size ?: 0) < 2
    val hasVideoSource = EditorState.isGif || EditorState.motionMp4 != null ||
        EditorState.motionUri != null || (EditorState.motionFrames?.size ?: 0) >= 2
    val items = ArrayList<Triple<String, () -> Unit, Color?>>()

    if (hasStillPhoto) items.add(Triple(stringResource(R.string.pick_image_delete), { act.deleteImportedImage() }, Color(0xFFE04545)))
    if (hasVideoSource) items.add(Triple(stringResource(R.string.pick_video_delete), { act.deleteImportedVideo() }, Color(0xFFE04545)))

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

    EditorState.mediaRev

    EditorState.canvasOrientRev
    val b = LocalGlassBackdrop.current
    val hasMedia = (EditorState.previewBitmap != null && !EditorState.isDemo) ||
        EditorState.motionUri != null || EditorState.motionMp4 != null

    val canvasAspect = when (params.canvasOrient) {
        EditorState.CANVAS_LANDSCAPE -> 16f / 9f
        EditorState.CANVAS_PORTRAIT -> 9f / 16f
        else -> 0f
    }
    Box(modifier.fillMaxWidth().padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {

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
private fun BottomNav(
    tab: Int,
    onSelect: (Int) -> Unit,
    onDockHeight: (Int) -> Unit = {},

    backdrop: Backdrop = LocalGlassBackdrop.current,
) {
    val b = backdrop

    EditorState.dockGlassRev
    val tabs = tabNames()

    val dockTabWidth = (LocalConfiguration.current.screenWidthDp * 0.185f)
        .coerceIn(62f, 84f).dp
    val compactDockWidth = (dockTabWidth * tabs.size + 26.dp).coerceAtMost(DockMaxWidth)

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

                .pointerInput(Unit) { detectTapGestures { } }

                .width(compactDockWidth)
                .padding(start = DockSideMargin, end = DockSideMargin, bottom = bottomPadding),
            selectedIndex = tab,
            onSelected = onSelect,
            backdrop = b,
            tabsCount = tabs.size,
            frostLevel = EditorState.dockFrost,
            blurLevel = EditorState.dockBlur,
            refractHLevel = EditorState.dockRefractH,
            refractLevel = EditorState.dockRefract,
            tintLevel = EditorState.dockTint,
            rimLevel = EditorState.dockRim,
            alphaLevel = EditorState.dockAlpha,
            disperseLevel = EditorState.dockDisperse,
            flowLevel = EditorState.dockFlow,
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
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = PanelNavInset)) {
        SectionLabel(stringResource(R.string.shape_section))
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            shapeNames.zip(shapeValues).forEach { (name, shp) ->
                LiquidButton(onClick = { p.shape = shp; act.bump() },
                    backdrop = b, modifier = Modifier.height(34.dp).weight(1f),
                    tint = if (p.shape == shp) Color(0xFF0091FF) else Color.Unspecified)
                { Text(name, color = tMain(), fontSize = 11.sp) }
            }
        }

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

        if (p.layoutMode == EditorState.LAYOUT_SPLIT) {
            FloatSlider(stringResource(R.string.wm_length_short), p.wmLengthPct * 100f, 0f..100f, "%.0f%%", b) {
                p.wmLengthPct = (it / 100f); act.bump()
            }
        } else {
            FloatSlider(stringResource(R.string.wm_length_short), p.wmLengthPct, 0.01f..20f, "%.2fx", b) { p.wmLengthPct = it; act.bump() }
        }
    }
}

@Composable
private fun AppearanceTab(act: MainActivity) {
    var sub by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BrandChip(stringResource(R.string.tab_shape), sub == 0) { sub = 0 }
            BrandChip(stringResource(R.string.tab_glass), sub == 1) { sub = 1 }
            BrandChip(stringResource(R.string.tab_gloss), sub == 2) { sub = 2 }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when (sub) {
                0 -> ShapeTab(act)
                1 -> GlassTab(act)
                else -> GlossTab(act)
            }
        }
    }
}

@Composable
private fun GlassTab(act: MainActivity) {

    act.rev
    val b = LocalGlassBackdrop.current
    val p = params
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = PanelNavInset)) {
        SectionLabel(stringResource(R.string.glass_section))

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

            IntSlider(stringResource(R.string.adv_lens), p.refractA, 0..100, "%.0f", b, gamma = 1.2f) { v ->
                p.refractA = v; act.bump()
            }

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

        IntSlider(stringResource(R.string.glass_tier), p.glassTier, 0..1, "%.0f", b) { v ->
            p.glassTier = v.coerceIn(EditorState.GLASS_TIER_HIGH, EditorState.GLASS_TIER_LOW)
            com.liquidglass.watermark.glass.GlassQuality.tier = p.glassTier
            act.bump()
        }

        IntSlider(stringResource(R.string.scale_comp), p.scaleComp, 0..100, "%.0f", b) { v ->
            p.scaleComp = v; act.bump()
        }

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

    act.rev
    val b = LocalGlassBackdrop.current
    val p = params
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = PanelNavInset)) {
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
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = PanelNavInset)) {
        SectionLabel(stringResource(R.string.text_section))

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

                val label = if (idx == EditorState.BRAND_CUSTOM) {
                    p.customLogos.firstOrNull { it.path == p.customLogoPath }?.name
                        ?: p.customLogoName.takeIf { it.isNotBlank() }
                        ?: name
                } else name
                BrandChip(label, p.logoBrand == idx, onClick = {
                    if (idx == EditorState.BRAND_CUSTOM) {

                        if (p.customLogos.isNotEmpty()) {
                            act.showCustomLogosDialog()
                        } else {
                            act.pickCustomLogo()
                        }
                    } else {
                        p.logoBrand = idx

                        p.brandScale = EditorState.brandScales[idx] ?: 1f
                        act.bump()
                    }
                }, onLongClick = if (idx == EditorState.BRAND_CUSTOM && p.customLogoPath.isNotEmpty())
                    ({ act.deleteCustomLogo() }) else null)
            }

            p.customLogos.forEach { entry ->
                BrandChip(entry.name,
                    p.logoBrand == EditorState.BRAND_CUSTOM && p.customLogoPath == entry.path,
                    onClick = { act.switchCustomLogo(entry) },
                    onLongClick = { act.deleteCustomLogoEntry(entry) })
            }
        }

        FloatSlider(stringResource(R.string.brand_size), p.brandScale, 0.3f..2.6f, "%.2fx", b) {
            p.brandScale = it
            EditorState.brandScales[p.logoBrand] = it
            act.bump()
        }
        Spacer(Modifier.height(6.dp))

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

        SectionLabel(stringResource(R.string.font_panel_section))

        Text(stringResource(R.string.font_title_line), color = tSub(0.7f), fontSize = 12.sp)
        FloatSlider(stringResource(R.string.font_size), p.fontTitleScale, 0.5f..2.5f, "%.2fx", b) { p.fontTitleScale = it; act.bump() }
        FloatSlider(stringResource(R.string.font_shift), p.fontTitleShift, -60f..60f, "%.0f", b) { p.fontTitleShift = it; act.bump() }

        Text(stringResource(R.string.font_code_line), color = tSub(0.7f), fontSize = 12.sp)
        FloatSlider(stringResource(R.string.font_size), p.fontCodeScale, 0.5f..2.5f, "%.2fx", b) { p.fontCodeScale = it; act.bump() }
        FloatSlider(stringResource(R.string.font_shift), p.fontCodeShift, -60f..60f, "%.0f", b) { p.fontCodeShift = it; act.bump() }
        FloatSlider(stringResource(R.string.font_code_dy), p.fontCodeDy, -40f..40f, "%.0f", b) { p.fontCodeDy = it; act.bump() }

        Text(stringResource(R.string.font_time_line), color = tSub(0.7f), fontSize = 12.sp)
        FloatSlider(stringResource(R.string.font_size), p.fontTimeScale, 0.5f..2.5f, "%.2fx", b) { p.fontTimeScale = it; act.bump() }
        FloatSlider(stringResource(R.string.font_shift), p.fontTimeShift, -60f..60f, "%.0f", b) { p.fontTimeShift = it; act.bump() }

        Text(stringResource(R.string.font_geo_line), color = tSub(0.7f), fontSize = 12.sp)
        FloatSlider(stringResource(R.string.font_size), p.fontGeoScale, 0.5f..2.5f, "%.2fx", b) { p.fontGeoScale = it; act.bump() }
        FloatSlider(stringResource(R.string.font_shift), p.fontGeoShift, -60f..60f, "%.0f", b) { p.fontGeoShift = it; act.bump() }

        ToggleRow(stringResource(R.string.geo_above_time), p.geoAboveTime, b) { p.geoAboveTime = it; act.bump() }
        FloatSlider(stringResource(R.string.font_time_dy), p.fontTimeDy, -40f..40f, "%.0f", b) { p.fontTimeDy = it; act.bump() }
        FloatSlider(stringResource(R.string.font_geo_dy), p.fontGeoDy, -40f..40f, "%.0f", b) { p.fontGeoDy = it; act.bump() }

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

    act.rev
    val b = LocalGlassBackdrop.current
    val p = params
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = PanelNavInset)) {
        SectionLabel(stringResource(R.string.pos_section))
        FloatSlider(stringResource(R.string.pos_x), p.relX * 100, 0f..100f, "%.0f%%", b) { p.relX = it / 100f; act.bump() }
        FloatSlider(stringResource(R.string.pos_y), p.relY * 100, 0f..100f, "%.0f%%", b) { p.relY = it / 100f; act.bump() }
        Spacer(Modifier.height(8.dp))

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

    act.rev

    EditorState.dockGlassRev
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
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = PanelNavInset)) {
        SectionLabel(stringResource(R.string.settings_section))

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

        var dockGlassOpen by remember { mutableStateOf(false) }
        fun applyDock(v: Int, set: (Int) -> Unit): Int {
            set(v)
            EditorState.bumpDockGlass()
            if (!EditorState.sliderInteracting) EditorState.persistDockGlass(ctx)
            return v
        }
        Row(
            Modifier.fillMaxWidth().clickable { dockGlassOpen = !dockGlassOpen }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.dock_capsule_section), color = tSub(0.7f), fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(if (dockGlassOpen) R.string.dock_collapse else R.string.dock_expand),
                color = tSub(0.5f), fontSize = 11.sp
            )
        }
        AnimatedVisibility(visible = dockGlassOpen) {
            Column {
                Text("折射与形变", color = tSub(0.55f), fontSize = 11.sp)
                IntSlider(stringResource(R.string.dock_refract), EditorState.dockRefract, 0..100, "%.0f%%", b) {
                    EditorState.dockRefract = applyDock(it) { v -> EditorState.dockRefract = v }
                }
                IntSlider(stringResource(R.string.dock_refract_h), EditorState.dockRefractH, 0..100, "%.0f%%", b) {
                    EditorState.dockRefractH = applyDock(it) { v -> EditorState.dockRefractH = v }
                }
                IntSlider(stringResource(R.string.dock_disperse), EditorState.dockDisperse, 0..100, "%.0f%%", b) {
                    EditorState.dockDisperse = applyDock(it) { v -> EditorState.dockDisperse = v }
                }
                Text("光与边", color = tSub(0.55f), fontSize = 11.sp)
                IntSlider(stringResource(R.string.dock_rim), EditorState.dockRim, 0..100, "%.0f%%", b) {
                    EditorState.dockRim = applyDock(it) { v -> EditorState.dockRim = v }
                }
                IntSlider(stringResource(R.string.dock_blur), EditorState.dockBlur, 0..100, "%.0f%%", b) {
                    EditorState.dockBlur = applyDock(it) { v -> EditorState.dockBlur = v }
                }
                IntSlider(stringResource(R.string.dock_frost), EditorState.dockFrost, 0..100, "%.0f%%", b) {
                    EditorState.dockFrost = applyDock(it) { v -> EditorState.dockFrost = v }
                }
                Text("本体与动态", color = tSub(0.55f), fontSize = 11.sp)
                IntSlider(stringResource(R.string.dock_alpha), EditorState.dockAlpha, 0..100, "%.0f%%", b) {
                    EditorState.dockAlpha = applyDock(it) { v -> EditorState.dockAlpha = v }
                }
                IntSlider(stringResource(R.string.dock_tint), EditorState.dockTint, 0..100, "%.0f%%", b) {
                    EditorState.dockTint = applyDock(it) { v -> EditorState.dockTint = v }
                }
                IntSlider(stringResource(R.string.dock_flow), EditorState.dockFlow, 0..100, "%.0f%%", b) {
                    EditorState.dockFlow = applyDock(it) { v -> EditorState.dockFlow = v }
                }
                Text(stringResource(R.string.dock_capsule_hint), color = tSub(0.55f), fontSize = 10.sp)
            }
        }
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

        val verName = remember {
            runCatching {
                ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
            }.getOrNull() ?: "?"
        }
        Text(stringResource(R.string.version_info, verName, android.os.Build.VERSION.RELEASE, android.os.Build.VERSION.SDK_INT),
            color = tSub(0.45f), fontSize = 11.sp)
        Spacer(Modifier.height(8.dp))

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

    val sliderPos = if (gamma == 1f) pos else Math.pow(pos.toDouble(), 1.0 / gamma).toFloat()

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
