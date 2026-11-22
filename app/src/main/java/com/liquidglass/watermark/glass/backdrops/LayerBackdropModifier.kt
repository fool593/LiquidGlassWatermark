package com.liquidglass.watermark.glass.backdrops

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
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

    private var recordedSize: IntSize = IntSize.Zero
    private var recordedBackdrop: LayerBackdrop? = null
    private var recordedRevision: Int = Int.MIN_VALUE
    private var lastRecordAt = 0L

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

        val throttle = backdrop.recordThrottleMs
        if (throttle > 0L && !backdrop.liveMode) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastRecordAt < throttle) return
            lastRecordAt = now
        }

        val s = if (backdrop.liveMode) 1f else backdrop.layerScale
        val recordSize = if (s < 1f) {
            IntSize(
                (size.width * s).toInt().coerceAtLeast(1),
                (size.height * s).toInt().coerceAtLeast(1),
            )
        } else {
            IntSize(size.width.toInt(), size.height.toInt())
        }
        recordLayer(backdrop.graphicsLayer, node = this@LayerBackdropNode, size = recordSize) {
            if (s < 1f) {
                withTransform({
                    scale(s, s, pivot = androidx.compose.ui.geometry.Offset.Zero)
                }) {
                    backdrop.onDraw(this@draw)
                }
            } else {
                backdrop.onDraw(this@draw)
            }
        }
        GlassPerf.onSceneRecord()
    }

    internal fun resetRecordedState() {
        recordedSize = IntSize.Zero
        recordedBackdrop = null
        recordedRevision = Int.MIN_VALUE
        lastRecordAt = 0L
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        if (coordinates.isAttached) {
            backdrop.layerCoordinates = coordinates
            backdrop.ownLayerAttached = true
        }
    }

    override fun onDetach() {
        backdrop.layerCoordinates = null
        backdrop.ownLayerAttached = false
        resetRecordedState()
    }
}
