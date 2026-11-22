package com.liquidglass.watermark.glass.shadow

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Density
import com.liquidglass.watermark.glass.GlassPerf
import com.liquidglass.watermark.glass.internal.ShapeProvider
import com.liquidglass.watermark.glass.internal.clipOutline
import com.liquidglass.watermark.glass.isRenderEffectSupported
import com.liquidglass.watermark.glass.hasFiniteArea

internal class InnerShadowElement(
    val shapeProvider: ShapeProvider,
    val shadow: () -> InnerShadow?
) : ModifierNodeElement<InnerShadowNode>() {

    override fun create(): InnerShadowNode {
        return InnerShadowNode(shapeProvider, shadow)
    }

    override fun update(node: InnerShadowNode) {
        node.shapeProvider = shapeProvider
        node.shadow = shadow
        node.invalidateDraw()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "innerShadow"
        properties["shapeProvider"] = shapeProvider
        properties["shadow"] = shadow
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is InnerShadowElement) return false

        if (shapeProvider != other.shapeProvider) return false
        if (shadow != other.shadow) return false

        return true
    }

    override fun hashCode(): Int {
        var result = shapeProvider.hashCode()
        result = 31 * result + shadow.hashCode()
        return result
    }
}

internal class InnerShadowNode(
    var shapeProvider: ShapeProvider,
    var shadow: () -> InnerShadow?
) : DrawModifierNode, Modifier.Node() {

    override val shouldAutoInvalidate: Boolean = false

    private var shadowLayer: GraphicsLayer? = null

    private val paint = Paint()
    private var clipPath: Path? = null

    private var prevRadius = Float.NaN

    private var recordedKey: Long = Long.MIN_VALUE

    override fun ContentDrawScope.draw() {
        drawContent()

        if (!size.hasFiniteArea()) return
        if (!isRenderEffectSupported()) return

        val shadow = shadow() ?: return

        val shadowLayer = shadowLayer
        if (shadowLayer != null) {
            val size = size
            val density: Density = this
            val layoutDirection = layoutDirection

            val radius = shadow.radius.toPx()
            val offsetX = shadow.offset.x.toPx()
            val offsetY = shadow.offset.y.toPx()

            val outline = shapeProvider.shape.createOutline(size, layoutDirection, density)
            val clipPath =
                if (outline is Outline.Rounded) {
                    clipPath ?: Path().also { clipPath = it }
                } else {
                    null
                }

            val key = innerShadowKey(shadow, size.width, size.height, radius, offsetX, offsetY)
            if (key != recordedKey) {
                configurePaint(shadow)

                shadowLayer.alpha = shadow.alpha
                shadowLayer.blendMode = shadow.blendMode
                if (prevRadius != radius) {
                    shadowLayer.renderEffect =
                        if (radius > 0f) {
                            BlurEffect(radius, radius, TileMode.Decal)
                        } else {
                            null
                        }
                    prevRadius = radius
                }
                shadowLayer.record {
                    val canvas = drawContext.canvas
                    canvas.save()
                    canvas.clipOutline(outline, clipPath)
                    canvas.drawOutline(outline, paint)
                    canvas.translate(offsetX, offsetY)
                    canvas.drawOutline(outline, ShadowMaskPaint)
                    canvas.translate(-offsetX, -offsetY)
                    canvas.restore()
                }
                recordedKey = key
                GlassPerf.onInnerShadowRecord()
            } else {
                GlassPerf.onInnerShadowSkip()
            }

            val canvas = drawContext.canvas
            canvas.save()
            canvas.clipOutline(outline, clipPath)
            drawLayer(shadowLayer)
            canvas.restore()
        }
    }

    private fun innerShadowKey(
        shadow: InnerShadow,
        width: Float,
        height: Float,
        radius: Float,
        offsetX: Float,
        offsetY: Float
    ): Long {
        var h = width.toRawBits().toLong()
        h = h * 31 + height.toRawBits().toLong()
        h = h * 31 + radius.toRawBits().toLong()
        h = h * 31 + offsetX.toRawBits().toLong()
        h = h * 31 + offsetY.toRawBits().toLong()
        h = h * 31 + shadow.color.value.toLong()
        h = h * 31 + shadow.alpha.toRawBits().toLong()
        h = h * 31 + shadow.blendMode.hashCode().toLong()

        h = h * 31 + shapeProvider.innerShape.hashCode().toLong()
        return h
    }

    override fun onAttach() {
        val graphicsContext = requireGraphicsContext()
        shadowLayer =
            graphicsContext.createGraphicsLayer().apply {
                compositingStrategy = CompositingStrategy.Offscreen
            }
    }

    override fun onDetach() {
        val graphicsContext = requireGraphicsContext()
        shadowLayer?.let { layer ->
            graphicsContext.releaseGraphicsLayer(layer)
            shadowLayer = null
        }
        recordedKey = Long.MIN_VALUE
        prevRadius = Float.NaN
    }

    private fun DrawScope.configurePaint(shadow: InnerShadow) {
        paint.color = shadow.color
    }
}

private val ShadowMaskPaint = Paint().apply {
    blendMode = BlendMode.Clear
}
