package com.liquidglass.watermark.glass.backdrops

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.IntSize
import com.liquidglass.watermark.glass.GlassPerf
import com.liquidglass.watermark.glass.internal.recordLayer
import com.liquidglass.watermark.glass.hasFiniteArea

fun Modifier.layerBackdrop(backdrop: LayerBackdrop): Modifier =
    this then LayerBackdropElement(backdrop)

private class LayerBackdropElement(
    val backdrop: LayerBackdrop
) : ModifierNodeElement<LayerBackdropNode>() {

    override fun create(): LayerBackdropNode {
        return LayerBackdropNode(backdrop)
    }

    override fun update(node: LayerBackdropNode) {
        if (node.backdrop != backdrop) {
            node.backdrop.layerCoordinates = null
            node.backdrop = backdrop
            node.resetRecordedState()
        }
        node.invalidateDraw()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "layerBackdrop"
        properties["backdrop"] = backdrop
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LayerBackdropElement) return false

        if (backdrop != other.backdrop) return false

        return true
    }

    override fun hashCode(): Int {
        return backdrop.hashCode()
    }
}

private class LayerBackdropNode(
    var backdrop: LayerBackdrop
) : DrawModifierNode, GlobalPositionAwareModifierNode, Modifier.Node() {

    /**
     * v15.16 任务3 卡顿修复：内容恒定的场景捕获层（如 `liquidBackground()` 全屏背景）
     * 不必每帧重录。
     *
     * 修复前：`recordLayer(backdrop.graphicsLayer, ...)` 在 `draw()` 里**每帧无条件执行**，
     * 哪怕这一层的内容（静态渐变 + 3 个径向光晕）自上一帧起没有任何变化。
     * 全屏尺寸的一次 `GraphicsLayer.record` 要重新光栅化整块背景，是主线程上最重的
     * 单项开销之一；拖动滑块时 `bump()` 每帧触发重组 → 每帧重录。
     *
     * 只在 `backdrop.contentStatic == true` 时启用缓存（默认 false，行为与修复前完全一致）：
     * 目前只有 `EditorScreen` 的全屏场景层声明为静态。**内容会随动画变化的层
     * （如 LiquidToggle 轨道的颜色插值）保持每帧重录，视觉效果零变化。**
     */
    private var recordedSize: IntSize = IntSize.Zero
    private var recordedBackdrop: LayerBackdrop? = null
    private var recordedRevision: Int = Int.MIN_VALUE

    override fun ContentDrawScope.draw() {
        drawContent()
        if (!size.hasFiniteArea()) return

        if (backdrop.contentStatic) {
            val currentSize = IntSize(size.width.toInt(), size.height.toInt())
            val revision = backdrop.contentRevision
            if (currentSize == recordedSize &&
                recordedBackdrop === backdrop &&
                revision == recordedRevision
            ) {
                GlassPerf.onSceneSkip()
                return
            }
            recordLayer(backdrop.graphicsLayer, node = this@LayerBackdropNode) {
                backdrop.onDraw(this@draw)
            }
            recordedSize = currentSize
            recordedBackdrop = backdrop
            recordedRevision = revision
            GlassPerf.onSceneRecord()
            return
        }

        recordLayer(backdrop.graphicsLayer, node = this@LayerBackdropNode) { backdrop.onDraw(this@draw) }
        GlassPerf.onSceneRecord()
    }

    internal fun resetRecordedState() {
        recordedSize = IntSize.Zero
        recordedBackdrop = null
        recordedRevision = Int.MIN_VALUE
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        if (coordinates.isAttached) {
            backdrop.layerCoordinates = coordinates
        }
    }

    override fun onDetach() {
        backdrop.layerCoordinates = null
        resetRecordedState()
    }
}
