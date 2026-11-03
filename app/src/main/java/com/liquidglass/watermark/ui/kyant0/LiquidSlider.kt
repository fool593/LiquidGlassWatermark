package com.liquidglass.watermark.ui.kyant0

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.lerp
import com.liquidglass.watermark.glass.Backdrop
import com.liquidglass.watermark.glass.backdrops.layerBackdrop
import com.liquidglass.watermark.glass.backdrops.rememberBackdrop
import com.liquidglass.watermark.glass.backdrops.rememberCombinedBackdrop
import com.liquidglass.watermark.glass.backdrops.rememberLayerBackdrop
import com.liquidglass.watermark.glass.drawBackdrop
import com.liquidglass.watermark.glass.effects.blur
import com.liquidglass.watermark.glass.effects.lens
import com.liquidglass.watermark.glass.highlight.Highlight
import com.liquidglass.watermark.glass.shadow.InnerShadow
import com.liquidglass.watermark.glass.shadow.Shadow
import kotlinx.coroutines.launch

@Composable
fun LiquidSlider(
    value: () -> Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    visibilityThreshold: Float,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    onDragStart: () -> Unit = {},
    // 松手回调：参数为最终值（外部已同步的最新值，用于触发同值重渲染且不重置滑块）
    onDragEnd: (Float) -> Unit = {}
) {
    val isLightTheme = !com.liquidglass.watermark.darkModeState
    val accentColor = if (isLightTheme) Color(0xFF0088FF) else Color(0xFF0091FF)
    val trackColor = if (isLightTheme) Color(0xFF787878).copy(0.2f) else Color(0xFF787880).copy(0.36f)

    val trackBackdrop = rememberLayerBackdrop()
    val density = LocalDensity.current
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val thumbWidthPx = with(density) { 40.dp.toPx() }
    // 两侧留边距：轨道缩短居中（数值映射不变），避免滑块贴边难触碰
    val padPx = with(density) { 20.dp.toPx() }
    val scope = rememberCoroutineScope()

    // rememberUpdatedState: pointerInput coroutine always reads latest value/callback
    val valueState by rememberUpdatedState(value)
    val onChangeState by rememberUpdatedState(onValueChange)

    // local drag value: thumb follows finger instantly, no wait for external recomposition
    var localVal by remember { mutableFloatStateOf(value()) }
    // sync from external when not dragging (preset/reset/programmatic change)
    LaunchedEffect(value()) { localVal = value() }

    val pressAnim = remember { Animatable(0f) }
    val scaleXAnim = remember { Animatable(1f) }
    val scaleYAnim = remember { Animatable(1f) }

    fun press() {
        scope.launch {
            launch { pressAnim.animateTo(1f, spring(1f, 1000f, 0.001f)) }
            launch { scaleXAnim.animateTo(1.25f, spring(0.6f, 250f, 0.001f)) }
            launch { scaleYAnim.animateTo(1.25f, spring(0.7f, 250f, 0.001f)) }
        }
    }
    fun release() {
        scope.launch {
            launch { pressAnim.animateTo(0f, spring(1f, 1000f, 0.001f)) }
            launch { scaleXAnim.animateTo(1f, spring(0.6f, 250f, 0.001f)) }
            launch { scaleYAnim.animateTo(1f, spring(0.7f, 250f, 0.001f)) }
        }
    }

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(40.dp)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    // try/finally 兜底：无论松手、协程取消还是重组移除此滑块，
                    // 都保证执行 onDragEnd（复位轻量渲染标志），绝不把滑块拖成"卡死轻量渲染"
                    var dragging = false
                    try {
                        while (true) {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            press()
                            onDragStart()
                            dragging = true
                            val lo = valueRange.start
                            val hi = valueRange.endInclusive
                            val range = hi - lo
                            // 触摸映射到缩短后的轨道（padPx..size.width-padPx），数值范围不变
                            val tlo = padPx
                            val thi = (size.width - padPx).coerceAtLeast(tlo + 1f)
                            fun pxToVal(px: Float): Float = if (isLtr) {
                                (lo + range * ((px - tlo) / (thi - tlo))).fastCoerceIn(lo, hi)
                            } else {
                                (hi - range * ((px - tlo) / (thi - tlo))).fastCoerceIn(lo, hi)
                            }
                            // tap-to-jump: local state updates instantly
                            var cur = pxToVal(down.position.x)
                            localVal = cur
                            onChangeState(cur)
                            // drag: track total displacement from down point
                            drag(down.id) { change ->
                                change.consume()
                                cur = pxToVal(change.position.x)
                                localVal = cur
                                onChangeState(cur)
                            }
                            // after release, resync local to external (in case of rounding)
                            localVal = valueState()
                            release()
                            onDragEnd(localVal)
                            dragging = false
                        }
                    } finally {
                        // 协程被取消/异常时也复位（同值回调幂等，重复触发无害）
                        if (dragging) runCatching { onDragEnd(valueState()) }
                    }
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        // 两侧留边距：轨道缩短居中（数值映射不变），避免滑块贴边难触碰
        val padDp = with(density) { padPx.toDp() }
        val trackWidth = (constraints.maxWidth - padPx * 2f).coerceAtLeast(1f)
        val range = valueRange.endInclusive - valueRange.start
        // thumb position driven by localVal (instant during drag), falls back to external
        val displayVal = localVal
        val progress = ((displayVal - valueRange.start) / range).fastCoerceIn(0f, 1f)
        // thumb 中心 = padPx + trackWidth*progress；offset = 左边缘 = 中心 - 半宽
        val thumbOffsetPx = (padPx + trackWidth * progress - thumbWidthPx / 2f)
            .fastCoerceIn(padPx - thumbWidthPx / 2f, padPx + trackWidth - thumbWidthPx / 2f)
        val thumbOffsetDp = with(density) { thumbOffsetPx.toDp() }

        // 轨道（先 padding 再 fillMaxWidth → 背景真正缩短居中，不再贴边全宽）
        Box(
            Modifier
                .padding(horizontal = padDp)
                .fillMaxWidth()
                .height(6f.dp)
                .layerBackdrop(trackBackdrop)
                .clip(RoundedCornerShape(50))
                .background(trackColor),
            contentAlignment = Alignment.CenterStart
        ) {
            // 激活进度条：在缩短后的轨道内从左按 progress 填充
            Box(
                Modifier
                    .fillMaxWidth(progress)
                    .height(6f.dp)
                    .clip(RoundedCornerShape(50))
                    .background(accentColor)
            )
        }

        Box(
            Modifier
                .offset(x = thumbOffsetDp, y = 0.dp)
                .drawBackdrop(
                    backdrop = rememberCombinedBackdrop(
                        backdrop,
                        rememberBackdrop(trackBackdrop) { drawBackdrop ->
                            val p = pressAnim.value
                            scale(lerp(2f / 3f, 1f, p), lerp(0f, 1f, p)) { drawBackdrop() }
                        }
                    ),
                    shape = { RoundedCornerShape(50) },
                    effects = {
                        val p = pressAnim.value
                        // 静止为白色圆钮（无需模糊）；长按变透明玻璃：模糊+折射增强（折射克制，防文字重影）
                        blur((2f.dp.toPx() * (1f - p) + 8f.dp.toPx() * p))
                        // 滑块圆钮不做 RGB 色散：长按放大后会透镜折射其下方的标签文字，产生彩色重影
                        // （用户反复反馈的"滑块字体重影"）。仅保留极轻折射表达玻璃感。
                        lens(
                            refractionHeight = 4f.dp.toPx() * p,
                            refractionAmount = 7f.dp.toPx() * p,
                            depthEffect = true,
                            chromaticAberration = false,
                            scaleCompensation = 1f
                        )
                    },
                    highlight = {
                        val p = pressAnim.value
                        Highlight.Ambient.copy(
                            width = Highlight.Ambient.width / 1.5f,
                            blurRadius = Highlight.Ambient.blurRadius / 1.5f,
                            // 长按白高光
                            alpha = p
                        )
                    },
                    shadow = { Shadow(radius = 4f.dp, color = Color.Black.copy(alpha = 0.05f)) },
                    innerShadow = {
                        val p = pressAnim.value
                        InnerShadow(radius = 4f.dp * p, alpha = p)
                    },
                    layerBlock = {
                        scaleX = scaleXAnim.value
                        scaleY = scaleYAnim.value
                    },
                    onDrawSurface = {
                        val p = pressAnim.value
                        // 静止纯白圆钮；长按变通透玻璃（白叠加骤降，玻璃折射透出）
                        drawRect(Color.White.copy(alpha = lerp(0.88f, 0.10f, p)))
                    }
                )
                .size(40f.dp, 24f.dp)
        )
    }
}
