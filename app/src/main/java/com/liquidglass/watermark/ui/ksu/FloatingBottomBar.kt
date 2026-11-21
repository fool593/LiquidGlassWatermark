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
import androidx.compose.foundation.shape.RoundedCornerShape
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
val DockBottomGap: Dp = 22.dp

/**
 * v15.36：Dock 胶囊最大宽度。
 * 手机（≈360~430dp 宽）会被 fillMaxWidth 铺满，观感与 KSU 原版一致；
 * 平板/折叠屏超过这个值就居中停住，避免 6 个 tab 被拉到荒唐的宽度。
 */
val DockMaxWidth: Dp = 560.dp

val LocalFloatingBottomBarTabScale = staticCompositionLocalOf { { 1f } }

/**
 * v15.40：胶囊内部左右留白（tab 区从 4dp 加到 8dp）。
 * 胶囊缩短后两端是半径 26dp 的半圆，4dp 留白会让首尾两格的内容压到圆弧上；
 * 8dp 起排后图标/指示片都落在直线段内，视觉与 KSU 原版一致。
 */
private val DockInnerPad: Dp = 10.dp

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

/** Dock 强调色（选中项）。v15.41：由选中项自己取色——原来靠隐形镜像层画蓝色图标，
 *  那层删掉后会失去选中高亮，这里直接在 item 内部按 selected 切换内容色。 */
private val DockAccent = Color(0xFF0091FF)

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
                    val scale = scale()
                    scaleX = scale
                    scaleY = scale
                },
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content
        )
    }
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
    // v15.37：Dock 胶囊按屏宽自适应——窄屏（手机 360dp 左右）不再"又短又圆"，
    // 宽屏（平板/折叠展开）也不会又细又长；高度取屏宽 15.5%，46~58dp 之间夹取。
    val screenW = LocalConfiguration.current.screenWidthDp
    val pillH = (screenW * 0.155f).coerceIn(46f, 58f).dp
    // v15.40：胶囊主体改为「固定半径 = 高度一半」的真胶囊。
    // 原来用 CircleShape（50% 圆角，rx 按宽度算），Dock 一缩短 rx 跟着缩，
    // 上下边变成整条弧线 → 视觉上从胶囊退化成椭圆/圆。KSU 原版近满宽、弧线极浅才没露馅。
    // 固定半径后：上下边是直线、两端是半圆，宽度再短也仍是胶囊。
    val barShape = remember(pillH) { RoundedCornerShape(pillH / 2) }
    val indicatorH = (pillH.value - 8f).dp
    // v15.40：选中指示器改为固定半径（= 指示器高度一半）的胶囊片，并按胶囊端弧收窄一圈。
    // 原来用 CircleShape：rx 跟着宽度缩，短 Dock 上直接缩成椭圆，左端还会和胶囊端弧糊成一坨。
    // lensH = pillH - 16dp，使指示片在垂直中段的水平位置天然落在胶囊直线段内（不再越出外轮廓）。
    val lensH = (pillH.value - 16f).dp
    val pillShape = remember(lensH) { RoundedCornerShape(lensH / 2) }
    val accentColor = DockAccent
    val tabContentColor = if (isInDark) Color(0xFFF5F7FA) else Color(0xFF1A1D24)
    // KSU 取 MiuixTheme.colorScheme.surfaceContainer；这里用对应的明暗容器色
    val surfaceContainer = if (isInDark) Color(0xFF2C2C2E) else Color(0xFFF2F2F7)
    // v15.41：Dock 改成真正「能看见后面内容」的液态玻璃——表面填充 0.4 → 0.14，
    // 更像 iOS 的透明玻璃胶囊（背后参数选项透过透镜可见），而不是一块填满的实心底板。
    // v15.42：再降一档透明度。v15.41 的 0.14 在浅色主题下叠上 0.75 白色高光后，
    // 胶囊看起来仍接近一块实心浅色片，后面参数选项透不过来。
    val containerColor = if (isBlurEnabled) surfaceContainer.copy(alpha = 0.05f) else surfaceContainer
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
        val horizontalPaddingPx = with(density) { DockInnerPad.toPx() }
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
        // ① 胶囊玻璃层：只负责玻璃本体（阴影 / 折射 / 高光），不画任何内容。
        // v15.41 分层：玻璃 → 镜片 → 内容，三层叠起来，内容永远在镜片之上，
        // 所以选中格的图标不会再被镜片底板盖掉（原来按住时图标整块消失像是"重影"）。
        Box(
            Modifier
                .fillMaxWidth()
                .onGloballyPositioned { coords ->
                    totalWidthPx = coords.size.width.toFloat()
                    val contentWidthPx = totalWidthPx - with(density) { (DockInnerPad * 2).toPx() }
                    tabWidthPx = (contentWidthPx / tabsCount).coerceAtLeast(0f)
                }
                .graphicsLayer { translationX = panelOffset }
                .dropShadow(
                    shape = barShape,
                    shadow = Shadow(
                        radius = 8.dp,
                        color = Color.Black,
                        alpha = if (isInDark) 0.2f else 0.1f,
                    ),
                )
                // v15.41：先裁剪再画玻璃。drawBackdrop 的折射/模糊会画到 padding 区域，
                // 裁剪必须放在它【之前】才生效（放在链尾只能裁剪其内部内容），
                // 否则按压重绘时会把 40dp 采样区的直边露出来。
                .clip(barShape)
                .then(
                    if (isBlurEnabled) {
                        Modifier.drawBackdrop(
                            backdrop = backdrop,
                            shape = { barShape },
                            effects = {
                                padding = maxOf(padding, 40.dp.toPx())
                                vibrancy()
                                blur(4.dp.toPx())
                                ksuLens(
                                    refractionHeight = 24.dp.toPx(),
                                    refractionAmount = 24.dp.toPx(),
                                )
                            },
                            highlight = { baseHighlight.copy(alpha = 0.4f) },
                            // v15.41：胶囊本体不再随按压放大。原来 layerBlock 把整条玻璃按 16dp 放大，
                            // 绘制边界会露出直边色块（按压时左右两端出现两道矩形边缘），也是"重影"来源之一。
                            // 按压反馈交给上面的镜片层，胶囊保持恒定几何。
                            // 表面填充改由 Modifier.background 承担（严格按 barShape 裁剪），
                            // onDrawSurface 不再画整块矩形，避免玻璃层边界露出直边色块。
                        )
                            .background(containerColor, barShape)
                    } else {
                        Modifier.background(containerColor, barShape)
                    }
                )
                .height(pillH)
                // v15.41：显式按胶囊形状裁剪玻璃层。drawBackdrop 的折射/模糊会画到 padding 区域，
                // 按压时重绘会把这些直边区域露出来（左右两端两道矩形）。裁剪后玻璃只在胶囊内可见。
                .clip(barShape)
        )
        // v15.41：去掉 KSU 的「隐形镜像层」。
        // 原实现用一条 .alpha(0f) + drawBackdrop 的整宽副本记录图标到 tabsBackdrop，
        // 供按下的放大透镜取样；但 drawBackdrop 的合成层不受外层 alpha 控制，实际会再画一遍
        // 44dp 高的胶囊底色 → 按住胶囊时出现上下两层轮廓（重影）。副本删除后，
        // 镜片只取真实背景，按下只有一层玻璃在放大，不再有重影。
        if (tabWidthPx > 0f) {
            val tabWidthDp = with(density) { tabWidthPx.toDp() }
            if (isBlurEnabled) {
                Box(
                    Modifier
                        // v15.41：镜片保留在内容之上，但底色不再是不透明黑（原来绘制 alpha=1 的黑块，
                        // 选中格的图标会被整块盖掉）。现在只留 6% 的淡色玻璃底，图标清晰可见；
                        // 按住放大时只是这层玻璃在折射，不再出现两层轮廓的重影。
                        .padding(horizontal = DockInnerPad)
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
                                val rest = 0.06f * (1f - progress)
                                drawRect(
                                    color = if (!isInDark) Color.Black.copy(alpha = rest) else Color.White.copy(alpha = rest)
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
                        .height(lensH)
                        .width(tabWidthDp)
                )
            } else {
                Box(
                    Modifier
                        .padding(horizontal = DockInnerPad)
                        .graphicsLayer {
                            val progressOffset = dampedDragAnimation.value * tabWidthPx
                            translationX = if (isLtr) progressOffset + panelOffset else -progressOffset + panelOffset
                        }
                        .clip(pillShape)
                        .background(accentColor.copy(alpha = 0.15f), pillShape)
                        .height(lensH)
                        .width(tabWidthDp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    // v15.41：这里原先还会再画一份内容（KSU 的强调色副本），
                    // 但内容层现在已经在镜片之上单独绘制，再画一次就是「重影」。
                    // 非模糊模式下只保留这片淡蓝底板即可。
                }
            }
        }
        // ③ 内容层（图标 + 文字）：画在镜片之上，永远清晰；手势与拖动逻辑与原先完全一致。
        Row(
            Modifier
                .selectableGroup()
                // v15.35 修复：KSU 的拖动检测只认「拖动」，轻点不会命中任何 tab。
                // 这里在条形上补一层 tap：点击直接选中所在格，拖动仍由 DampedDragAnimation 接管
                // （tap 手势在超过 touch slop 后自行取消，不会和拖动抢事件）。
                .pointerInput(tabsCount, tabWidthPx) {
                    detectTapGestures { pos -> activateTab(indexAt(pos.x)) }
                }
                .graphicsLayer { translationX = panelOffset }
                // v15.41：interactiveHighlight 这块「整条按压高光」在按下时会铺出一块方角高光板
                // （裁剪也拦不住它的合成层），看起来像多出来的一层底——正是用户说的"重影"。
                // 按压反馈现在完全由下面的镜片层承担，这里不再叠加。
                .then(dampedDragAnimation.modifier)
                .fillMaxWidth()
                .height(pillH)
                // v15.41：内容层也按胶囊裁剪。按下时 interactiveHighlight 会在整块 Row 上铺一层高光，
                // 不裁剪就会露出直边矩形（就是按住后左右两端那两块方角）。
                .clip(barShape)
                .padding(horizontal = DockInnerPad, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompositionLocalProvider(LocalContentColor provides tabContentColor) {
                content(::activateTab)
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
