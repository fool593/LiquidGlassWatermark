package com.liquidglass.watermark.glass.shadow

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import com.liquidglass.watermark.glass.GlassPerf
import com.liquidglass.watermark.glass.internal.ShapeProvider
import com.liquidglass.watermark.glass.internal.blur
import com.liquidglass.watermark.glass.hasFiniteArea
import kotlin.math.ceil

internal class ShadowElement(
    val shapeProvider: ShapeProvider,
    val shadow: () -> Shadow?
) : ModifierNodeElement<ShadowNode>() {

    override fun create(): ShadowNode {
        return ShadowNode(shapeProvider, shadow)
    }

    override fun update(node: ShadowNode) {
        node.shapeProvider = shapeProvider
        node.shadow = shadow
        node.invalidateDraw()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "shadow"
        properties["shapeProvider"] = shapeProvider
        properties["shadow"] = shadow
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ShadowElement) return false

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

internal class ShadowNode(
    var shapeProvider: ShapeProvider,
    var shadow: () -> Shadow?
) : DrawModifierNode, Modifier.Node() {

    override val shouldAutoInvalidate: Boolean = false

    private var shadowLayer: GraphicsLayer? = null

    private val paint = Paint()

    /**
     * v15.16 任务3 卡顿修复：阴影离屏层只在输入真正变化时重录。
     *
     * 修复前：`shadowLayer.record(shadowSize) { ... }` 在 `draw()` 里**每帧无条件执行**。
     * 一个玻璃控件 → 一张 Offscreen GraphicsLayer，每帧重录整张图（含 BlurMaskFilter 的
     * 模糊光栅化）。参数面板单页 10+ 滑块/开关时，每帧要重录十几~几十张离屏层，
     * 这是 Android 11+ 上 HWUI 合成压力骤增、掉帧的直接来源。
     *
     * 阴影层的内容只由 (尺寸, 形状, 半径, 偏移, 颜色, alpha, blendMode) 决定，
     * 与每帧变化的内容无关 → 可以安全缓存。绘制结果逐像素一致，视觉效果零变化。
     */
    private var recordedKey: Long = Long.MIN_VALUE

    override fun ContentDrawScope.draw() {
        if (!size.hasFiniteArea()) return drawContent()
        val shadow = shadow() ?: return drawContent()

        val shadowLayer = shadowLayer
        if (shadowLayer != null) {
            val size = size
            val density: Density = this
            val layoutDirection = layoutDirection

            val radius = shadow.radius.toPx()
            val offsetX = shadow.offset.x.toPx()
            val offsetY = shadow.offset.y.toPx()
            val shadowSize = IntSize(
                ceil(size.width + radius * 4f + offsetX).toInt(),
                ceil(size.height + radius * 4f + offsetY).toInt()
            )

            val key = shadowKey(shadow, size.width, size.height, radius, offsetX, offsetY, shadowSize)
            if (key != recordedKey) {
                val outline = shapeProvider.shape.createOutline(size, layoutDirection, density)

                configurePaint(shadow)

                shadowLayer.alpha = shadow.alpha
                shadowLayer.blendMode = shadow.blendMode
                shadowLayer.record(shadowSize) {
                    translate(radius * 2f + offsetX, radius * 2f + offsetY) {
                        val canvas = drawContext.canvas
                        canvas.drawOutline(outline, paint)
                        canvas.translate(-offsetX, -offsetY)
                        canvas.drawOutline(outline, ShadowMaskPaint)
                        canvas.translate(offsetX, offsetY)
                    }
                }
                recordedKey = key
                GlassPerf.onShadowRecord()
            } else {
                GlassPerf.onShadowSkip()
            }

            translate(-radius * 2f, -radius * 2f) {
                drawLayer(shadowLayer)
            }
        }

        drawContent()
    }

    private fun shadowKey(
        shadow: Shadow,
        width: Float,
        height: Float,
        radius: Float,
        offsetX: Float,
        offsetY: Float,
        shadowSize: IntSize
    ): Long {
        var h = width.toRawBits().toLong()
        h = h * 31 + height.toRawBits().toLong()
        h = h * 31 + radius.toRawBits().toLong()
        h = h * 31 + offsetX.toRawBits().toLong()
        h = h * 31 + offsetY.toRawBits().toLong()
        h = h * 31 + shadowSize.width.toLong()
        h = h * 31 + shadowSize.height.toLong()
        h = h * 31 + shadow.color.value.toLong()
        h = h * 31 + shadow.alpha.toRawBits().toLong()
        h = h * 31 + shadow.blendMode.hashCode().toLong()
        // 用 shapeBlock 的返回值做哈希（RoundedCornerShape 等为值相等），形状切换时自动失效；
        // 若调用方每帧新建匿名 Shape（identity hashCode 不稳定）则退化为每帧重录，不引入错误缓存。
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
    }

    private fun DrawScope.configurePaint(shadow: Shadow) {
        paint.color = shadow.color
        paint.blur(shadow.radius.toPx())
    }
}

private val ShadowMaskPaint = Paint().apply {
    blendMode = BlendMode.Clear
}
