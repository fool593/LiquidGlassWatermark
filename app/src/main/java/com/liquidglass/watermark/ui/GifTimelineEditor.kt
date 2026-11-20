package com.liquidglass.watermark.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liquidglass.watermark.EditorState
import com.liquidglass.watermark.darkModeState
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * v15.30 剪辑时间轴（专业剪辑软件风格）：
 * - 带时间刻度（秒刻度线 + 标签）的轨道；
 * - 选区高亮 + 轨道外侧暗化遮罩（只突出选中片段）；
 * - 左右【修整手柄】分别拖动起点/终点，拖动【选区中间】可整体平移片段；
 * - 快捷片段：5 秒 / 10 秒 / 全片 / 重置；
 * - 时间读数：起点 / 片段 / 总时长（mm:ss.d）。
 * 写回 EditorState.gifTrimStartMs / gifTrimDurationMs（gifTrimCustomized=true），[onChanged] 持久化。
 */
@Composable
fun GifTimelineEditor(
    durationMs: Long,
    onChanged: () -> Unit,
    modifier: Modifier = Modifier,
    // v15.30：时间轴模式——0=GIF 剪辑（gifTrim* 字段），1=动态照片剪辑（motionTrim* 字段）
    mode: Int = 0,
) {
    val safeDur = durationMs.coerceAtLeast(1000L)
    val isDark = darkModeState
    val subColor = if (isDark) Color(0xFF9AA3B0) else Color(0xFF5A6472)
    val trackColor = if (isDark) Color(0x40FFFFFF) else Color(0x26000000)
    val dimColor = if (isDark) Color(0xB3101014) else Color(0xB3E9EDF2)
    val accent = Color(0xFF0091FF)
    val accent2 = Color(0xFF5AC8FA)

    // 按模式读取对应字段（GIF / 动态照片各自独立记忆）
    fun readStart(): Long = if (mode == 1) EditorState.motionTrimStartMs else EditorState.gifTrimStartMs
    fun readDur(): Long = if (mode == 1) EditorState.motionTrimDurationMs else EditorState.gifTrimDurationMs
    fun readCustomized(): Boolean = if (mode == 1) EditorState.motionTrimCustomized else EditorState.gifTrimCustomized
    fun write(s: Long, d: Long, customized: Boolean) {
        if (mode == 1) {
            EditorState.motionTrimStartMs = s; EditorState.motionTrimDurationMs = d
            EditorState.motionTrimCustomized = customized
        } else {
            EditorState.gifTrimStartMs = s; EditorState.gifTrimDurationMs = d
            EditorState.gifTrimCustomized = customized
        }
    }

    var startMs by remember(safeDur) { mutableLongStateOf(0L) }
    var durMs by remember(safeDur) { mutableLongStateOf(minOf(5_000L, safeDur)) }
    LaunchedEffect(safeDur, mode, EditorState.gifTrimCustomized, EditorState.gifTrimStartMs,
        EditorState.gifTrimDurationMs, EditorState.motionTrimCustomized, EditorState.motionTrimStartMs,
        EditorState.motionTrimDurationMs) {
        if (readCustomized()) {
            startMs = readStart().coerceIn(0L, (safeDur - 300L).coerceAtLeast(0L))
            durMs = readDur().coerceIn(300L, (safeDur - startMs).coerceAtLeast(300L))
        } else {
            startMs = 0L
            durMs = minOf(5_000L, safeDur)
        }
    }
    val endMs = (startMs + durMs).coerceAtMost(safeDur)

    fun commit(s: Long, d: Long, customized: Boolean = true) {
        val ns = s.coerceIn(0L, (safeDur - 300L).coerceAtLeast(0L))
        val nd = d.coerceIn(300L, (safeDur - ns).coerceAtLeast(300L))
        startMs = ns
        durMs = nd
        write(ns, nd, customized)
        onChanged()
    }
    fun preset(d: Long) {
        // 快捷片段：优先以当前起点为基准；全片=0 起点；超出尾部自动收窄
        val targetStart = if (d <= 0L) 0L else startMs
        commit(targetStart, if (d <= 0L) safeDur else d, customized = true)
    }

    val trackH = 30.dp
    val startFrac by animateFloatAsState((startMs.toFloat() / safeDur).coerceIn(0f, 1f))
    val endFrac by animateFloatAsState((endMs.toFloat() / safeDur).coerceIn(0f, 1f))
    fun fmt(ms: Long): String {
        val totalDs = (ms.coerceAtLeast(0L) / 100L)
        val m = totalDs / 600
        val s = (totalDs % 600) / 10
        val d = totalDs % 10
        return "%02d:%02d.%d".format(m, s, d)
    }

    Column(modifier) {
        // 时间刻度标签（按 1/2/5/10s 自适应间隔，最多 ~8 个标签）
        val stepMs: Long = when {
            safeDur <= 8_000L -> 1_000L
            safeDur <= 20_000L -> 2_000L
            safeDur <= 50_000L -> 5_000L
            else -> 10_000L
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            var t = 0L
            while (t <= safeDur) {
                Text(
                    fmt(t),
                    color = subColor.copy(alpha = 0.6f), fontSize = 9.sp,
                    modifier = Modifier.width(34.dp)
                )
                Spacer(Modifier.weight(1f))
                t += stepMs
            }
        }
        Spacer(Modifier.height(2.dp))
        // 轨道 + 手柄
        Box(
            Modifier
                .fillMaxWidth()
                .height(trackH)
                .pointerInput(startFrac, endFrac, safeDur) {
                    var mode = 0 // 0=none 1=start 2=end 3=move
                    detectDragGestures(
                        onDragStart = { p ->
                            val x0 = size.width * startFrac
                            val x1 = size.width * endFrac
                            val r = size.height * 0.62f + 12f
                            mode = when {
                                abs(p.x - x0) <= r && abs(p.x - x0) <= abs(p.x - x1) -> 1
                                abs(p.x - x1) <= r -> 2
                                p.x in x0..x1 -> 3
                                else -> { if (p.x < x0) 1 else 2 }
                            }
                        },
                        onDrag = { change, drag ->
                            change.consume()
                            val total = size.width.toFloat()
                            if (total <= 0f) return@detectDragGestures
                            val x = change.position.x.coerceIn(0f, total)
                            val t = (x / total * safeDur).toLong()
                            when (mode) {
                                1 -> commit(t.coerceIn(0L, (endMs - 300L).coerceAtLeast(0L)), durMs)
                                2 -> commit(startMs, (t - startMs).coerceAtLeast(300L))
                                3 -> {
                                    val len = durMs
                                    val newStart = (t - len / 2).coerceIn(0L, (safeDur - len).coerceAtLeast(0L))
                                    commit(newStart, len)
                                }
                            }
                        }
                    )
                }
        ) {
            // 底座 + 刻度
            Canvas(Modifier.fillMaxSize2()) {
                drawRoundRect(color = trackColor, size = size,
                    cornerRadius = CornerRadius(size.height / 2f, size.height / 2f))
                // 秒刻度短竖线（比标签密 4 倍）
                val dense = stepMs / 4L
                var t = 0L
                while (t <= safeDur) {
                    val x = size.width * (t.toFloat() / safeDur)
                    val major = t % stepMs == 0L
                    val h = if (major) size.height * 0.42f else size.height * 0.22f
                    drawLine(Color(0x66FFFFFF), Offset(x, size.height * 0.5f - h / 2f),
                        Offset(x, size.height * 0.5f + h / 2f), strokeWidth = if (major) 1.6f else 1f)
                    t += dense
                }
                // 选区外侧暗化（突出选中片段）
                if (startFrac > 0.005f) {
                    drawRoundRect(color = dimColor,
                        topLeft = Offset(0f, 0f), size = Size(size.width * startFrac, size.height),
                        cornerRadius = CornerRadius(size.height / 2f, size.height / 2f))
                }
                if (endFrac < 0.995f) {
                    drawRoundRect(color = dimColor,
                        topLeft = Offset(size.width * endFrac, 0f),
                        size = Size(size.width * (1f - endFrac), size.height),
                        cornerRadius = CornerRadius(size.height / 2f, size.height / 2f))
                }
                // 选区高亮
                drawRoundRect(
                    brush = Brush.horizontalGradient(listOf(accent, accent2)),
                    topLeft = Offset(size.width * startFrac, 0f),
                    size = Size((size.width * (endFrac - startFrac)).coerceAtLeast(6f), size.height),
                    cornerRadius = CornerRadius(size.height / 2f, size.height / 2f))
            }
            // 起点手柄
            Canvas(Modifier.fillMaxSize2()) {
                val cx = size.width * startFrac
                val cy = size.height / 2f
                val hr = size.height * 0.62f
                drawCircle(Color(0x55000000), hr * 1.7f, Offset(cx, cy))
                drawCircle(Color.White, hr, Offset(cx, cy))
                drawCircle(accent, hr * 0.5f, Offset(cx, cy))
            }
            // 终点手柄
            Canvas(Modifier.fillMaxSize2()) {
                val cx = size.width * endFrac
                val cy = size.height / 2f
                val hr = size.height * 0.62f
                drawCircle(Color(0x55000000), hr * 1.7f, Offset(cx, cy))
                drawCircle(Color.White, hr, Offset(cx, cy))
                drawCircle(accent, hr * 0.5f, Offset(cx, cy))
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("起点 ${fmt(startMs)}", color = subColor, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(14.dp))
            Text("片段 ${fmt(durMs)}", color = subColor, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            Text("总长 ${fmt(safeDur)}", color = subColor.copy(alpha = 0.5f), fontSize = 11.sp)
        }
        Spacer(Modifier.height(6.dp))
        // 快捷片段
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val presets = listOf(
                5_000L to "5 秒", 10_000L to "10 秒", 0L to "全片"
            )
            presets.forEach { (d, label) ->
                val active = if (d == 0L) (startMs == 0L && durMs >= safeDur - 500L)
                else durMs == d.coerceAtMost(safeDur)
                Box(
                    Modifier
                        .weight(1f)
                        .height(26.dp)
                        .background(
                            if (active) accent.copy(alpha = 0.28f) else Color.Transparent,
                            RoundedCornerShape(8.dp)
                        )
                        .clickable(interactionSource = null, indication = null) { preset(d) }
                        .padding(horizontal = 4.dp)
                ) {
                    Text(
                        label, color = if (active) accent2 else subColor, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(vertical = 4.dp)
                    )
                }
            }
            // 「重置」与快捷按钮同排：回默认 5 秒（全片前 5 秒）
            Box(
                Modifier
                    .weight(1.2f)
                    .height(26.dp)
                    .clickable(interactionSource = null, indication = null) {
                        startMs = 0L; durMs = minOf(5_000L, safeDur)
                        write(0L, minOf(5_000L, safeDur), false)
                        onChanged()
                    }
                    .padding(horizontal = 4.dp)
            ) {
                Text("重置", color = subColor, fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.Center).padding(vertical = 4.dp))
            }
        }
    }
}

/** 占满轨道盒的铺满修饰（Canvas 专用） */
private fun Modifier.fillMaxSize2(): Modifier = fillMaxWidth().height(30.dp)