// Adapted from KernelSU manager (tiann/KernelSU) — Apache 2.0.
// Source: manager/app/src/main/java/me/weishu/kernelsu/ui/component/FloatingBottomBar.kt
// (itself "Adapted from compose-miuix-ui example (IosLiquidGlassNavigationBar)")
//

//

package com.liquidglass.watermark.ui.ksu

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import kotlinx.coroutines.android.awaitFrame
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.foundation.layout.BoxWithConstraints
import com.liquidglass.watermark.darkModeState
import com.liquidglass.watermark.glass.Backdrop
import com.liquidglass.watermark.glass.backdrops.layerBackdrop
import com.liquidglass.watermark.glass.backdrops.softwareBlurEnabled
import com.liquidglass.watermark.glass.drawBackdrop
import com.liquidglass.watermark.glass.effects.blur
import com.liquidglass.watermark.glass.effects.ksuLens
import com.liquidglass.watermark.glass.effects.vibrancy
import com.liquidglass.watermark.glass.highlight.Highlight
import com.liquidglass.watermark.glass.highlight.HighlightStyle
import com.liquidglass.watermark.glass.isRenderEffectSupported
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

// ═══════════════════════════════════════════════════════════════════

// ═══════════════════════════════════════════════════════════════════

val DockPillHeight: Dp = 52.dp

val DockIndicatorHeight: Dp = 44.dp

val DockSideMargin: Dp = 10.dp

val DockBottomGap: Dp = 22.dp

val DockMaxWidth: Dp = 560.dp

private val DockInnerPad: Dp = 10.dp

private val DockAccent = Color(0xFF0091FF)

private val iosIndicatorSpecular: Highlight = Highlight(
    width = 1.dp,
    alpha = 1f,
    style = HighlightStyle.Default(angle = -45f, falloff = 0.8f)
)

val LocalFloatingBottomBarTabScale = staticCompositionLocalOf { { 1f } }

// ═══════════════════════════════════════════════════════════════════

// ═══════════════════════════════════════════════════════════════════

private data class DockGlassSpec(
    val pillH: Dp,
    val barShape: RoundedCornerShape,
    val lensH: Dp,
    val pillShape: RoundedCornerShape,

    val contentColor: Color,

    val refractHPx: Float,

    val refractAmtPx: Float,

    val dispAmt: Float,

    val frostEff: Float,

    val rimBase: Float,
)

@Composable
private fun rememberDockGlassSpec(
    frostLevel: Int,
    blurLevel: Int,
    refractHLevel: Int,
    refractLevel: Int,
    tintLevel: Int,
    rimLevel: Int,
    alphaLevel: Int,
    disperseLevel: Int,
    flowLevel: Int,
): DockGlassSpec {
    val isInDark = darkModeState

    val screenW = LocalConfiguration.current.screenWidthDp
    val pillH = (screenW * 0.175f).coerceIn(54f, 72f).dp

    val barShape = remember(pillH) { RoundedCornerShape(pillH / 2) }

    val lensH = (pillH.value + 2f).dp
    val pillShape = remember(lensH) { RoundedCornerShape(lensH / 2) }

    val rimBase = rimLevel.coerceIn(0, 100) / 100f
    val frostEff = frostLevel.coerceIn(0, 100) / 100f

    val refractHPx = with(LocalDensity.current) {
        ((refractHLevel.coerceIn(0, 100) / 100f) * 20f).dp.toPx()
    }
    val refractAmtPx = with(LocalDensity.current) {
        ((refractLevel.coerceIn(0, 100) / 100f) * 26f).dp.toPx()
    }

    return DockGlassSpec(
        pillH = pillH,
        barShape = barShape,
        lensH = lensH,
        pillShape = pillShape,
        contentColor = if (isInDark) Color(0xFFF5F7FA) else Color(0xFF1A1D24),
        refractHPx = refractHPx,
        refractAmtPx = refractAmtPx,
        dispAmt = disperseLevel.coerceIn(0, 100) / 100f,
        frostEff = frostEff,
        rimBase = rimBase,
    )
}

// ═══════════════════════════════════════════════════════════════════

// ═══════════════════════════════════════════════════════════════════

@Composable
fun RowScope.KsuFloatingBottomBarItem(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val scale = LocalFloatingBottomBarTabScale.current

    CompositionLocalProvider(
        LocalContentColor provides if (selected) DockAccent else LocalContentColor.current
    ) {
        Column(
            modifier
                .semantics(mergeDescendants = true) {
                    this.selected = selected
                    role = Role.Tab
                    onClick {
                        onClick()
                        true
                    }
                }
                .onKeyEvent { event ->
                    val activationKey = event.key == Key.Enter ||
                            event.key == Key.NumPadEnter || event.key == Key.Spacebar
                    if (activationKey) {
                        if (event.type == KeyEventType.KeyUp) onClick()
                        true
                    } else false
                }
                .focusable()
                .fillMaxHeight()
                .weight(1f)
                .graphicsLayer {
                    val s = scale()
                    scaleX = s
                    scaleY = s
                },
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content
        )
    }
}

@Composable
fun KsuDockLabel(text: String) {
    androidx.compose.material3.Text(
        text = text,
        color = LocalContentColor.current,
        fontSize = 11.sp,
        lineHeight = 13.5.sp,
        maxLines = 1,
        softWrap = false,
        overflow = androidx.compose.ui.text.style.TextOverflow.Visible,
    )
}

// ═══════════════════════════════════════════════════════════════════

// ═══════════════════════════════════════════════════════════════════

@Composable
fun KsuFloatingBottomBar(
    modifier: Modifier = Modifier,
    selectedIndex: Int,
    onSelected: (index: Int) -> Unit,
    backdrop: Backdrop,
    tabsCount: Int,
    isBlurEnabled: Boolean = true,

    frostLevel: Int = 40,
    blurLevel: Int = 50,
    refractHLevel: Int = 45,
    refractLevel: Int = 55,
    tintLevel: Int = 35,
    rimLevel: Int = 50,
    alphaLevel: Int = 55,
    disperseLevel: Int = 25,
    flowLevel: Int = 0,
    content: @Composable RowScope.((Int) -> Unit) -> Unit
) {
    val spec = rememberDockGlassSpec(
        frostLevel = frostLevel,
        blurLevel = blurLevel,
        refractHLevel = refractHLevel,
        refractLevel = refractLevel,
        tintLevel = tintLevel,
        rimLevel = rimLevel,
        alphaLevel = alphaLevel,
        disperseLevel = disperseLevel,
        flowLevel = flowLevel,
    )
    val pillH = spec.pillH
    val barShape = spec.barShape

    val isInDark = darkModeState

    val density = LocalDensity.current
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val animationScope = rememberCoroutineScope()

    //

    val accentColor = if (!isInDark) Color(0xFF0088FF) else Color(0xFF0091FF)

    // Stable, always-legible body. The lower bound is high enough that even a momentarily
    // empty refraction texture cannot read as "transparent".
    val containerAlpha = 0.46f + 0.26f * (alphaLevel.coerceIn(0, 100) / 100f)
    val containerColor =
        (if (!isInDark) Color(0xFFFAFAFA) else Color(0xFF121212)).copy(alpha = containerAlpha)
    val containerBase =
        (if (!isInDark) Color.White else Color(0xFF0A0A0A)).copy(alpha = 0.12f)

    val flow = flowLevel.coerceIn(0, 100) / 100f
    val flowTransition = rememberInfiniteTransition(label = "dockFlow")
    val flowPhase by flowTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = (4200 - 3000 * flow).toInt().coerceAtLeast(1100),
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "dockFlowPhase"
    )

    val baseHighlight = remember {
        iosIndicatorSpecular.copy(style = HighlightStyle.Default(angle = -45f, falloff = 0.8f))
    }

    var currentIndex by remember { mutableIntStateOf(selectedIndex) }
    val onSelectedUpdated by rememberUpdatedState(onSelected)

    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.CenterStart
    ) {
        val outerPad = 12.dp
        val outerPadPx = with(density) { outerPad.toPx() }
        val tabWidthPx =
            ((constraints.maxWidth.toFloat() - outerPadPx * 2f) / tabsCount).coerceAtLeast(1f)

        val lensOverhangPx = outerPadPx
        val dockInnerW = tabWidthPx * tabsCount

        val offsetAnimation = remember { Animatable(0f) }
        val panelOffset by remember(density, constraints.maxWidth) {
            derivedStateOf {
                val fraction = (offsetAnimation.value / constraints.maxWidth.toFloat())
                    .fastCoerceIn(-1f, 1f)
                with(density) { 4.dp.toPx() * fraction.sign * EaseOut.transform(abs(fraction)) }
            }
        }

        val dampedDragAnimation = remember(animationScope, tabsCount, tabWidthPx, isLtr) {
            var downX = 0f
            var downAt = 0L
            var travelled = 0f
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = selectedIndex.toFloat(),
                valueRange = 0f..(tabsCount - 1).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = 78f / 56f,
                onDragStarted = {},
                onDragStopped = {
                    val targetIndex = targetValue.roundToInt().coerceIn(0, tabsCount - 1)
                    if (currentIndex != targetIndex) {
                        currentIndex = targetIndex
                        onSelectedUpdated(targetIndex)
                    }
                    settleToValue(targetIndex.toFloat(), flingFactor = 0.12f)
                    animationScope.launch {
                        offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f))
                    }
                },
                onDragCancelled = {
                    settleToValue(currentIndex.toFloat())
                    animationScope.launch {
                        offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f))
                    }
                },
                onDrag = { _, dragAmount ->
                    dragToValue(
                        (targetValue + dragAmount.x / tabWidthPx * if (isLtr) 1f else -1f)
                            .coerceIn(0f, (tabsCount - 1).toFloat())
                    )
                    animationScope.launch {
                        offsetAnimation.snapTo(offsetAnimation.value + dragAmount.x)
                    }
                }
            )
        }

        LaunchedEffect(selectedIndex) {
            if (currentIndex != selectedIndex) {
                currentIndex = selectedIndex
                dampedDragAnimation.animateToValue(selectedIndex.toFloat())
            }
        }

        fun activateTab(index: Int) {
            if (index !in 0 until tabsCount) return
            if (currentIndex != index) {
                currentIndex = index
                onSelectedUpdated(index)
            }
            dampedDragAnimation.animateToValue(index.toFloat())
        }

        Box(
            Modifier
                .graphicsLayer { translationX = panelOffset }

                .clip(barShape)
                .then(
                    if (isBlurEnabled) Modifier.drawBackdrop(
                        backdrop = backdrop,
                        shape = { barShape },
                        effects = {
                            padding = maxOf(padding, 40.dp.toPx(), spec.refractAmtPx + 8.dp.toPx())
                            vibrancy()
                            if (blurLevel > 2) {
                                blur((0.6f + 23.4f * (blurLevel.coerceIn(0, 100) / 100f)).dp.toPx())
                            }

                            ksuLens(
                                refractionHeight = spec.refractHPx,
                                refractionAmount = spec.refractAmtPx,
                                depthEffect = true,
                                chromaticAberration = spec.dispAmt,
                            )
                        },

                        highlight = {
                            baseHighlight.copy(alpha = 0.10f + 0.40f * spec.rimBase)
                        },
                        onDrawSurface = {
                            drawRect(containerBase)
                            drawRect(containerColor)
                        }
                    ) else Modifier
                        .background(containerColor, barShape)
                        .border(1.dp, Color.White.copy(alpha = 0.18f + 0.45f * spec.rimBase), barShape)
                )
                .height(pillH)
                .fillMaxWidth()
        )

        val canvasW = with(density) { (tabWidthPx * 1.95f).toDp() }
        val canvasH = pillH + 14.dp
        val lensShape = RoundedCornerShape(50)

        // Selection lens. Real backdrop refraction + dispersion, no painted white slab:
        // the old build stacked three white rects and two thick white strokes, which is
        // exactly what read as "washed out" and "white rim" in dark mode.
        Box(
            Modifier
                .padding(horizontal = outerPad)
                .graphicsLayer {
                    val p = dampedDragAnimation.pressProgress
                    val v = (dampedDragAnimation.velocity / 10f).fastCoerceIn(-0.30f, 0.30f)
                    // Keep the locator width constant while pressed. Letting it grow with
                    // pressProgress pushed rawLeft past the coerceIn clamp on the first/last
                    // tab, which shifted the capsule center inward ~0.185 cell and made the
                    // pressed capsule overlap the neighbouring tab's label. Growth is now
                    // expressed only by the inner lens size + scaleX/scaleY below.
                    val lensWpx = tabWidthPx * 1.28f
                    val rawLeft = (if (isLtr) {
                        dampedDragAnimation.value * tabWidthPx
                    } else {
                        dockInnerW - (dampedDragAnimation.value + 1f) * tabWidthPx
                    }) + (tabWidthPx - lensWpx) / 2f

                    val lensLeft = rawLeft.coerceIn(-lensOverhangPx, dockInnerW - lensWpx + lensOverhangPx)
                    translationX = lensLeft - (size.width - lensWpx) / 2f + panelOffset

                    // Liquid squash and stretch driven by drag velocity.
                    val stretch = 1f + 0.12f * abs(v)
                    val squash = 1f - 0.07f * abs(v)
                    scaleX = stretch * (0.985f + 0.030f * p)
                    scaleY = squash * (0.955f + 0.060f * p)
                }
                .height(canvasH)
                .width(canvasW),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    // Pressed width capped at 0.70 of canvas (= 1.365 cell). Half-width
                    // 0.683 cell * scaleX 1.015 = 0.693 cell < neighbour label left edge
                    // (~0.83 cell), so the enlarged lens never covers the next tab's text.
                    .fillMaxWidth(0.6400f + 0.0600f * dampedDragAnimation.pressProgress)
                    // Static height hugs the dock: 0.86 * (pillH+14) * scaleY(0.955) ≈ pillH,
                    // removing the old ~8dp gap. Pressed 0.90 stays within the canvas bound.
                    .fillMaxHeight(0.8600f + 0.0400f * dampedDragAnimation.pressProgress)
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { lensShape },
                        effects = {
                            padding = maxOf(padding, 26.dp.toPx(), spec.refractAmtPx * 0.55f + 8.dp.toPx())
                            blur((2.2f + 2.4f * dampedDragAnimation.pressProgress).dp.toPx())
                            ksuLens(
                                refractionHeight = spec.refractHPx * 0.60f,
                                refractionAmount = spec.refractAmtPx * 0.52f,
                                depthEffect = true,
                                chromaticAberration = 0.08f + 0.42f * spec.dispAmt,
                            )
                        },
                        highlight = {
                            Highlight(
                                width = 0.9.dp,
                                alpha = if (isInDark) 0.30f else 0.50f,
                                style = HighlightStyle.Default(angle = -45f, falloff = 0.9f)
                            )
                        },
                        onDrawSurface = {
                            // Dark mode: near-clear body so the refracted scene carries the look.
                            drawRect(
                                Brush.verticalGradient(
                                    listOf(
                                        Color.White.copy(alpha = if (isInDark) 0.050f else 0.20f),
                                        Color.White.copy(alpha = if (isInDark) 0.014f else 0.09f)
                                    )
                                )
                            )
                            if (flow > 0.01f) {
                                val bandW = size.width * 0.42f
                                val bx = -bandW + (size.width + bandW * 2f) * flowPhase
                                drawRect(
                                    Brush.horizontalGradient(
                                        colors = listOf(
                                            Color.Transparent,
                                            Color.White.copy(alpha = 0.055f * flow),
                                            Color.Transparent
                                        ),
                                        startX = bx,
                                        endX = bx + bandW
                                    )
                                )
                            }
                        }
                    )
                    .border(
                        0.5.dp,
                        Color.White.copy(alpha = if (isInDark) 0.10f else 0.26f),
                        lensShape
                    )
            )
        }

        Row(
            Modifier
                .graphicsLayer { translationX = panelOffset }
                .then(dampedDragAnimation.modifier)
                .pointerInput(tabWidthPx, tabsCount, isLtr) {
                    awaitEachGesture {
                        val down = awaitFirstDown(
                            requireUnconsumed = false,
                            pass = PointerEventPass.Initial,
                        )
                        val startX = down.position.x
                        val downTime = down.uptimeMillis
                        var moved = false
                        var upAt = 0L
                        var gotUp = false
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if ((change.position - down.position).getDistance() > 12f) {
                                moved = true
                            }
                            if (!change.pressed) {
                                upAt = change.uptimeMillis
                                gotUp = true
                                break
                            }
                        }

                        if (gotUp && !moved && upAt - downTime < 300L) {
                            val raw = ((startX - outerPadPx) / tabWidthPx)
                                .toInt().coerceIn(0, tabsCount - 1)
                            val idx = if (isLtr) raw else tabsCount - 1 - raw

                            animationScope.launch {
                                awaitFrame()
                                activateTab(idx)
                            }
                        }
                    }
                }
                .height(pillH)
                .fillMaxWidth()
                .padding(horizontal = outerPad, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CompositionLocalProvider(LocalContentColor provides spec.contentColor) {
                content(::activateTab)
            }
        }
    }
}

