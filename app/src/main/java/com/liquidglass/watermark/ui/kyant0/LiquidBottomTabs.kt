package com.liquidglass.watermark.ui.kyant0

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.liquidglass.watermark.darkModeState
import com.liquidglass.watermark.glass.Backdrop
import com.liquidglass.watermark.glass.drawBackdrop
import com.liquidglass.watermark.glass.effects.blur
import com.liquidglass.watermark.glass.effects.lens
import com.liquidglass.watermark.glass.highlight.Highlight
import com.liquidglass.watermark.glass.shadow.Shadow
import com.liquidglass.watermark.ui.glassSurface
import kotlin.math.roundToInt

/** The lens is drawn below labels, so dragging can never refract a second text copy. */
@Composable
fun LiquidBottomTabs(
    selectedTabIndex: () -> Int,
    onTabSelected: (index: Int) -> Unit,
    backdrop: Backdrop,
    tabsCount: Int,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    if (tabsCount <= 0) return
    val selected by rememberUpdatedState(selectedTabIndex().coerceIn(0, tabsCount - 1))
    val select by rememberUpdatedState(onTabSelected)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val light = !darkModeState
    var dragging by remember { mutableStateOf(false) }
    var dragIndex by remember { mutableFloatStateOf(selected.toFloat()) }
    val animatedIndex by animateFloatAsState(
        if (dragging) dragIndex else selected.toFloat(),
        spring(dampingRatio = 0.82f, stiffness = 500f), label = "glassTab"
    )
    BoxWithConstraints(
        modifier.height(60.dp).glassSurface(backdrop, RoundedCornerShape(30.dp))
            .padding(4.dp)
            .pointerInput(tabsCount, rtl) {
                val width = size.width.toFloat() / tabsCount
                detectHorizontalDragGestures(
                    onDragStart = {
                        dragging = true
                        dragIndex = selected.toFloat()
                    },
                    onDragEnd = {
                        val index = dragIndex.roundToInt().coerceIn(0, tabsCount - 1)
                        dragging = false
                        select(index)
                    },
                    onDragCancel = { dragging = false },
                    onHorizontalDrag = { change, delta ->
                        change.consume()
                        dragIndex = (dragIndex + delta / width.coerceAtLeast(1f) *
                            if (rtl) -1f else 1f).coerceIn(0f, (tabsCount - 1).toFloat())
                    }
                )
            },
        contentAlignment = Alignment.CenterStart
    ) {
        val cellWidth = constraints.maxWidth.toFloat() / tabsCount
        Box(
            Modifier.graphicsLayer {
                translationX = (if (rtl) tabsCount - 1f - animatedIndex else animatedIndex) * cellWidth
            }.fillMaxWidth(1f / tabsCount).height(52.dp)
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { RoundedCornerShape(26.dp) },
                    effects = {
                        blur(3.dp.toPx())
                        lens(
                            refractionHeight = if (dragging) 8.dp.toPx() else 6.dp.toPx(),
                            refractionAmount = if (dragging) 16.dp.toPx() else 10.dp.toPx(),
                            depthEffect = true,
                            scaleCompensation = 1f
                        )
                    },
                    highlight = { Highlight.Default.copy(alpha = 0.72f) },
                    shadow = { Shadow(radius = 6.dp, color = Color.Black.copy(alpha = 0.14f)) },
                    onDrawSurface = {
                        drawRect(Brush.verticalGradient(listOf(
                            Color.White.copy(alpha = if (light) 0.64f else 0.24f),
                            Color.White.copy(alpha = if (light) 0.22f else 0.07f)
                        )))
                    }
                )
        )
        CompositionLocalProvider(LocalLiquidBottomTabScale provides { 1f }) {
            Row(Modifier.fillMaxWidth().height(52.dp),
                verticalAlignment = Alignment.CenterVertically, content = content)
        }
    }
}
