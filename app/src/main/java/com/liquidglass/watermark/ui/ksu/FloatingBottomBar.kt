// Adapted from KernelSU manager (tiann/KernelSU) — Apache 2.0.
// Source: manager/app/src/main/java/me/weishu/kernelsu/ui/component/FloatingBottomBar.kt
// (itself "Adapted from compose-miuix-ui example (IosLiquidGlassNavigationBar)")
//
// 真实 KSU 液态玻璃底栏实现。移植时只做两类对接，其余代码、常量、动画曲线、
// 折射参数与 KSU 完全一致：
//   1. 玻璃/背景 API：top.yukonga.miuix.kmp.blur.* → 本工程同源的
//      com.liquidglass.watermark.glass.*（Backdrop/drawBackdrop/blur/vibrancy/
//      rememberLayerBackdrop/rememberCombinedBackdrop，均为同一上游的移植）。
//      lens 换成 ksuLens（同一份 AGSL 折射 + 色散着色器，色散强度按 KSU 的 float 传入）。
//   2. 主题：MiuixTheme.colorScheme.* → 本工程深/浅色常量；miuix 的 BloomStroke
//      双光源高光 → 同源的 HighlightStyle.Default（光照角度取 KSU 的 -45°/+90°）。
//      设备倾斜感知（rememberDeviceTilt）不参与导航逻辑，这里固定光角。
package com.liquidglass.watermark.ui.ksu

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.lerp
import com.liquidglass.watermark.darkModeState
import com.liquidglass.watermark.glass.Backdrop
import com.liquidglass.watermark.glass.backdrops.layerBackdrop
import com.liquidglass.watermark.glass.backdrops.rememberCombinedBackdrop
import com.liquidglass.watermark.glass.backdrops.rememberLayerBackdrop
import com.liquidglass.watermark.glass.drawBackdrop
import com.liquidglass.watermark.glass.effects.blur
import com.liquidglass.watermark.glass.effects.ksuLens
import com.liquidglass.watermark.glass.effects.vibrancy
import com.liquidglass.watermark.glass.highlight.Highlight
import com.liquidglass.watermark.glass.highlight.HighlightStyle
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/** KSU 底栏本体高度（页面底部需按此预留，否则导出栏会被盖住）。v15.35 压缩：64→52dp。 */
val DockPillHeight: Dp = 52.dp

/** 指示器/强调副本高度（KSU 为 56dp = 胶囊 64dp - 上下各 4dp）。 */
val DockIndicatorHeight: Dp = 44.dp

/** KSU 底栏左右留白（FloatingBottomBar 外层的 start/end padding）。v15.35：28→16dp；v15.37：16→10dp（胶囊更长）。 */
val DockSideMargin: Dp = 10.dp

/** 无系统导航栏时 KSU 使用的底部留白；有导航栏时为 6dp + inset。v15.35：28→10dp。 */
val DockBottomGap: Dp = 10.dp

/**
 * v15.36：Dock 胶囊最大宽度。
 * 手机（≈360~430dp 宽）会被 fillMaxWidth 铺满，观感与 KSU 原版一致；
 * 平板/折叠屏超过这个值就居中停住，避免 6 个 tab 被拉到荒唐的宽度。
 */
val DockMaxWidth: Dp = 560.dp

val LocalFloatingBottomBarTabScale = staticCompositionLocalOf { { 1f } }

/**
 * KSU 指示器高光（原实现为 miuix `BloomStroke` 双光源 + 重力感应的 primary light）。
 * 本工程玻璃引擎同源但不含 BloomStroke，这里用等价的 Default 光照：角度固定 -45°，
 * 仍由 Highlight 的 stroke + 模糊 + Plus 混合生成同一圈镜面亮边。
 */
private val iosIndicatorSpecular: Highlight = Highlight(
    width = 1.dp,
    alpha = 1f,
    style = HighlightStyle.Default(angle = -45f, falloff = 0.8f)
)

@Composable
fun RowScope.KsuFloatingBottomBarItem(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val scale = LocalFloatingBottomBarTabScale.current
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
                val scale = scale()
                scaleX = scale
                scaleY = scale
            },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content
    )
}

@Composable
fun KsuFloatingBottomBar(
    modifier: Modifier = Modifier,
    selectedIndex: Int,
    onSelected: (index: Int) -> Unit,
    backdrop: Backdrop,
    tabsCount: Int,
    isBlurEnabled: Boolean = true,
    content: @Composable RowScope.((Int) -> Unit) -> Unit
) {
    val isInDark = darkModeState
    val pillShape = remember { CircleShape }
    // v15.37：Dock 胶囊按屏宽自适应——窄屏（手机 360dp 左右）不再"又短又圆"，
    // 宽屏（平板/折叠展开）也不会又细又长；高度取屏宽 15.5%，46~58dp 之间夹取。
    val screenW = LocalConfiguration.current.screenWidthDp
    val pillH = (screenW * 0.155f).coerceIn(46f, 58f).dp
    val indicatorH = (pillH.value - 8f).dp
    val accentColor = Color(0xFF0091FF)
    val tabContentColor = if (isInDark) Color(0xFFF5F7FA) else Color(0xFF1A1D24)
    // KSU 取 MiuixTheme.colorScheme.surfaceContainer；这里用对应的明暗容器色
    val surfaceContainer = if (isInDark) Color(0xFF2C2C2E) else Color(0xFFF2F2F7)
    val containerColor = if (isBlurEnabled) surfaceContainer.copy(alpha = 0.4f) else surfaceContainer
    val tabsBackdrop = rememberLayerBackdrop()
    val density = LocalDensity.current
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val animationScope = rememberCoroutineScope()
    var tabWidthPx by remember { mutableFloatStateOf(0f) }
    var totalWidthPx by remember { mutableFloatStateOf(0f) }
    val offsetAnimation = remember { Animatable(0f) }
    val rubberBandPx = with(density) { 4.dp.toPx() }
    val panelOffset by remember(rubberBandPx) {
        derivedStateOf {
            if (totalWidthPx == 0f) {
                0f
            } else {
                val fraction = (offsetAnimation.value / totalWidthPx).fastCoerceIn(-1f, 1f)
                rubberBandPx * fraction.sign * EaseOut.transform(abs(fraction))
            }
        }
    }
    var currentIndex by remember { mutableIntStateOf(selectedIndex) }
    val onSelectedUpdated by rememberUpdatedState(onSelected)
    fun indexAt(positionX: Float): Int {
        if (tabWidthPx == 0f) return currentIndex
        val horizontalPaddingPx = with(density) { 4.dp.toPx() }
        val logicalX = if (isLtr) positionX else totalWidthPx - positionX
        return ((logicalX - horizontalPaddingPx) / tabWidthPx)
            .toInt()
            .coerceIn(0, tabsCount - 1)
    }
    val dampedDragAnimation = remember(animationScope, tabsCount, density, isLtr) {
        DampedDragAnimation(
            animationScope = animationScope,
            initialValue = selectedIndex.toFloat(),
            valueRange = 0f..(tabsCount - 1).toFloat(),
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = 78f / 56f,
            canDrag = { offset ->
                offset.x in 0f..totalWidthPx
            },
            onDragStarted = { position ->
                updateValue(indexAt(position.x).toFloat())
            },
            onDragStopped = {
                val targetIndex = targetValue.roundToInt().coerceIn(0, tabsCount - 1)
                if (currentIndex != targetIndex) {
                    currentIndex = targetIndex
                    onSelectedUpdated(targetIndex)
                }
                updateValue(targetIndex.toFloat())
                animationScope.launch {
                    offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f))
                }
            },
            onDragCancelled = {
                updateValue(currentIndex.toFloat())
                animationScope.launch {
                    offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f))
                }
            },
            onDrag = { _, dragAmount ->
                if (tabWidthPx > 0f && dragAmount.x != 0f) {
                    updateValue(
                        (targetValue + dragAmount.x / tabWidthPx * if (isLtr) 1f else -1f)
                            .coerceIn(0f, (tabsCount - 1).toFloat()),
                    )
                    animationScope.launch {
                        offsetAnimation.snapTo(offsetAnimation.value + dragAmount.x)
                    }
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
    val interactiveHighlight = remember(animationScope, tabWidthPx, dampedDragAnimation) {
        InteractiveHighlight(
            animationScope = animationScope,
            position = { size, _ ->
                Offset(
                    if (isLtr) (dampedDragAnimation.value + 0.5f) * tabWidthPx + panelOffset
                    else size.width - (dampedDragAnimation.value + 0.5f) * tabWidthPx + panelOffset,
                    size.height / 2f
                )
            }
        )
    }
    // KSU：base = extraDegrees -45f，pill = extraDegrees +90f（相对 0.5/0.7 的基准光角）
    val baseHighlight = remember {
        iosIndicatorSpecular.copy(
            style = HighlightStyle.Default(angle = -45f, falloff = 0.8f)
        )
    }
    val pillHighlight = remember {
        iosIndicatorSpecular.copy(
            style = HighlightStyle.Default(angle = 45f, falloff = 0.8f)
        )
    }
    val combinedBackdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop)
    Box(
        modifier = modifier,
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            Modifier
                .onGloballyPositioned { coords ->
                    totalWidthPx = coords.size.width.toFloat()
                    val contentWidthPx = totalWidthPx - with(density) { 8.dp.toPx() }
                    tabWidthPx = (contentWidthPx / tabsCount).coerceAtLeast(0f)
                }
                .selectableGroup()
                // v15.35 修复：KSU 的拖动检测只认「拖动」，轻点不会命中任何 tab。
                // 这里在条形上补一层 tap：点击直接选中所在格，拖动仍由 DampedDragAnimation 接管
                // （tap 手势在超过 touch slop 后自行取消，不会和拖动抢事件）。
                .pointerInput(tabsCount, tabWidthPx) {
                    detectTapGestures { pos -> activateTab(indexAt(pos.x)) }
                }
                .graphicsLayer { translationX = panelOffset }
                .dropShadow(
                    shape = pillShape,
                    shadow = Shadow(
                        radius = 8.dp,
                        color = Color.Black,
                        alpha = if (isInDark) 0.2f else 0.1f,
                    ),
                )
                .then(
                    if (isBlurEnabled) {
                        Modifier.drawBackdrop(
                            backdrop = backdrop,
                            shape = { pillShape },
                            effects = {
                                padding = maxOf(padding, 40.dp.toPx())
                                vibrancy()
                                blur(4.dp.toPx())
                                ksuLens(
                                    refractionHeight = 24.dp.toPx(),
                                    refractionAmount = 24.dp.toPx(),
                                )
                            },
                            highlight = { baseHighlight.copy(alpha = 0.75f) },
                            layerBlock = {
                                val width = size.width.coerceAtLeast(1f)
                                val s = lerp(1f, 1f + 16.dp.toPx() / width, dampedDragAnimation.pressProgress)
                                scaleX = s
                                scaleY = s
                            },
                            onDrawSurface = { drawRect(containerColor) },
                        )
                    } else {
                        Modifier.background(containerColor, pillShape)
                    }
                )
                .then(
                    if (isBlurEnabled) {
                        interactiveHighlight.modifier.then(interactiveHighlight.gestureModifier)
                    } else Modifier
                )
                .then(dampedDragAnimation.modifier)
                .height(pillH)
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompositionLocalProvider(LocalContentColor provides tabContentColor) {
                content(::activateTab)
            }
        }
        if (isBlurEnabled) {
            CompositionLocalProvider(
                LocalFloatingBottomBarTabScale provides {
                    lerp(1f, 1.2f, dampedDragAnimation.pressProgress)
                },
                LocalContentColor provides accentColor,
            ) {
                Row(
                    Modifier
                        .clearAndSetSemantics {}
                        .alpha(0f)
                        .layerBackdrop(tabsBackdrop)
                        .graphicsLayer { translationX = panelOffset }
                        .drawBackdrop(
                            backdrop = backdrop,
                            shape = { pillShape },
                            effects = {
                                vibrancy()
                                blur(4.dp.toPx())
                                ksuLens(
                                    refractionHeight = 24.dp.toPx(),
                                    refractionAmount = 24.dp.toPx(),
                                )
                            },
                            onDrawSurface = { drawRect(containerColor) },
                        )
                        .then(interactiveHighlight.modifier)
                        .height(indicatorH)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    content = { content(::activateTab) }
                )
            }
        }
        if (tabWidthPx > 0f) {
            val tabWidthDp = with(density) { tabWidthPx.toDp() }
            if (isBlurEnabled) {
                Box(
                    Modifier
                        .padding(horizontal = 4.dp)
                        .graphicsLayer {
                            val progressOffset = dampedDragAnimation.value * tabWidthPx
                            translationX = if (isLtr) progressOffset + panelOffset else -progressOffset + panelOffset
                        }
                        .drawBackdrop(
                            backdrop = combinedBackdrop,
                            shape = { pillShape },
                            effects = {
                                val progress = dampedDragAnimation.pressProgress
                                ksuLens(
                                    refractionHeight = 10.dp.toPx() * progress,
                                    refractionAmount = 14.dp.toPx() * progress,
                                    depthEffect = true,
                                    chromaticAberration = 0.5f,
                                )
                            },
                            highlight = { pillHighlight.copy(alpha = dampedDragAnimation.pressProgress) },
                            layerBlock = {
                                scaleX = dampedDragAnimation.scaleX
                                scaleY = dampedDragAnimation.scaleY
                                val velocity = dampedDragAnimation.velocity / 10f
                                scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                                scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                            },
                            onDrawSurface = {
                                val progress = dampedDragAnimation.pressProgress
                                drawRect(
                                    color = if (!isInDark) Color.Black.copy(alpha = 0.1f) else Color.White.copy(alpha = 0.1f),
                                    alpha = 1f - progress,
                                )
                                drawRect(Color.Black.copy(alpha = 0.03f * progress))
                            },
                        )
                        .innerShadow(shape = pillShape) {
                            InnerShadow(
                                radius = 8.dp * dampedDragAnimation.pressProgress,
                                color = Color.Black.copy(alpha = 0.15f),
                                alpha = dampedDragAnimation.pressProgress,
                            )
                        }
                        .height(indicatorH)
                        .width(tabWidthDp)
                )
            } else {
                Box(
                    Modifier
                        .padding(horizontal = 4.dp)
                        .graphicsLayer {
                            val progressOffset = dampedDragAnimation.value * tabWidthPx
                            translationX = if (isLtr) progressOffset + panelOffset else -progressOffset + panelOffset
                        }
                        .clip(pillShape)
                        .background(accentColor.copy(alpha = 0.15f), pillShape)
                        .height(indicatorH)
                        .width(tabWidthDp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    CompositionLocalProvider(LocalContentColor provides accentColor) {
                        Row(
                            Modifier
                                .clearAndSetSemantics {}
                                .wrapContentWidth(align = Alignment.Start, unbounded = true)
                                .requiredWidth(with(density) { (totalWidthPx - 8.dp.toPx()).toDp() })
                                .height(indicatorH)
                                .graphicsLayer {
                                    val progressOffset = dampedDragAnimation.value * tabWidthPx
                                    translationX = if (isLtr) -progressOffset else progressOffset
                                },
                            verticalAlignment = Alignment.CenterVertically,
                            content = { content(::activateTab) }
                        )
                    }
                }
            }
        }
    }
}

/** KSU `FloatingBottomBarItem` 的图标/文字样式包装（11sp、单行，颜色取 LocalContentColor）。 */
@Composable
fun KsuDockLabel(text: String) {
    androidx.compose.material3.Text(
        text = text,
        color = LocalContentColor.current,
        fontSize = 9.5.sp,
        lineHeight = 12.sp,
        maxLines = 1,
        softWrap = false,
        overflow = androidx.compose.ui.text.style.TextOverflow.Visible,
    )
}
