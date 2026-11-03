package com.liquidglass.watermark.glass.highlight

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
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
import com.liquidglass.watermark.glass.RuntimeShaderCacheImpl
import com.liquidglass.watermark.glass.internal.ShapeProvider
import com.liquidglass.watermark.glass.internal.blur
import com.liquidglass.watermark.glass.internal.clipOutline
import com.liquidglass.watermark.glass.internal.setRuntimeShader
import com.liquidglass.watermark.glass.isRuntimeShaderSupported
import com.liquidglass.watermark.glass.hasFiniteArea
import kotlin.math.ceil

internal class HighlightElement(
    val shapeProvider: ShapeProvider,
    val highlight: () -> Highlight?
) : ModifierNodeElement<HighlightNode>() {

    override fun create(): HighlightNode {
        return HighlightNode(shapeProvider, highlight)
    }

    override fun update(node: HighlightNode) {
        node.shapeProvider = shapeProvider
        node.highlight = highlight
        node.invalidateDraw()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "highlight"
        properties["shapeProvider"] = shapeProvider
        properties["highlight"] = highlight
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HighlightElement) return false

        if (shapeProvider != other.shapeProvider) return false
        if (highlight != other.highlight) return false

        return true
    }

    override fun hashCode(): Int {
        var result = shapeProvider.hashCode()
        result = 31 * result + highlight.hashCode()
        return result
    }
}

internal class HighlightNode(
    var shapeProvider: ShapeProvider,
    var highlight: () -> Highlight?
) : DrawModifierNode, Modifier.Node() {

    override val shouldAutoInvalidate: Boolean = false

    private var highlightLayer: GraphicsLayer? = null

    private val paint =
        Paint().apply {
            style = PaintingStyle.Stroke
        }
    private var clipPath: Path? = null

    private val runtimeShaderCache = RuntimeShaderCacheImpl()

    private var prevStyle: HighlightStyle? = null
    private var shaderUnsupported = false

    /**
     * v15.16 任务3 卡顿修复：高光离屏层只在输入真正变化时重录。
     *
     * 修复前：`highlightLayer.record(safeSize) { ... }` 在 `draw()` 里**每帧无条件执行**。
     * 高光层内容只由 (尺寸, 形状, 宽度, 模糊半径, 颜色, alpha, blendMode, style) 决定，
     * 与每帧变化的内容无关 → 可安全缓存，绘制结果逐像素一致。
     */
    private var recordedKey: Long = Long.MIN_VALUE

    override fun ContentDrawScope.draw() {
        if (!size.hasFiniteArea()) return drawContent()
        val highlight = highlight()
        if (highlight == null || highlight.width.value <= 0f) {
            return drawContent()
        }

        drawContent()

        val highlightLayer = highlightLayer
        if (highlightLayer != null) {
            val size = size
            val density: Density = this
            val layoutDirection = layoutDirection

            val safeSize =
                IntSize(
                    ceil(size.width).toInt() + 2,
                    ceil(size.height).toInt() + 2
                )

            val key = highlightKey(highlight, size.width, size.height, safeSize)
            if (key != recordedKey) {
                val outline = shapeProvider.shape.createOutline(size, layoutDirection, density)
                val clipPath =
                    if (outline is Outline.Rounded) {
                        clipPath ?: Path().also { clipPath = it }
                    } else {
                        null
                    }

                configurePaint(highlight)

                highlightLayer.alpha = highlight.alpha
                highlightLayer.blendMode = highlight.style.blendMode
                highlightLayer.record(safeSize) {
                    translate(1f, 1f) {
                        val canvas = drawContext.canvas
                        canvas.save()
                        canvas.clipOutline(outline, clipPath)
                        canvas.drawOutline(outline, paint)
                        canvas.restore()
                    }
                }
                recordedKey = key
                GlassPerf.onHighlightRecord()
            } else {
                GlassPerf.onHighlightSkip()
            }

            translate(-1f, -1f) {
                drawLayer(highlightLayer)
            }
        }
    }

    private fun highlightKey(
        highlight: Highlight,
        width: Float,
        height: Float,
        safeSize: IntSize
    ): Long {
        var h = width.toRawBits().toLong()
        h = h * 31 + height.toRawBits().toLong()
        h = h * 31 + safeSize.width.toLong()
        h = h * 31 + safeSize.height.toLong()
        h = h * 31 + highlight.width.value.toRawBits().toLong()
        h = h * 31 + highlight.blurRadius.value.toRawBits().toLong()
        h = h * 31 + highlight.alpha.toRawBits().toLong()
        h = h * 31 + highlight.style.color.value.toLong()
        h = h * 31 + highlight.style.blendMode.hashCode().toLong()
        // 用 shapeBlock 的返回值做哈希（RoundedCornerShape 等为值相等），形状切换时自动失效；
        // 若调用方每帧新建匿名 Shape（identity hashCode 不稳定）则退化为每帧重录，不引入错误缓存。
        h = h * 31 + shapeProvider.innerShape.hashCode().toLong()
        h = h * 31 + highlight.style.hashCode().toLong()
        return h
    }

    override fun onAttach() {
        val graphicsContext = requireGraphicsContext()
        highlightLayer = graphicsContext.createGraphicsLayer()
    }

    override fun onDetach() {
        val graphicsContext = requireGraphicsContext()
        highlightLayer?.let { layer ->
            graphicsContext.releaseGraphicsLayer(layer)
            highlightLayer = null
        }
        clipPath = null
        runtimeShaderCache.clear()
        prevStyle = null
        recordedKey = Long.MIN_VALUE
    }

    private fun DrawScope.configurePaint(highlight: Highlight) {
        paint.color = highlight.style.color
        paint.strokeWidth = ceil(highlight.width.toPx().coerceAtMost(size.minDimension / 2f)) * 2f
        paint.blur(highlight.blurRadius.toPx())
        if (isRuntimeShaderSupported() && !shaderUnsupported) try {
            val shader =
                with(highlight.style) {
                    createShader(
                        shape = shapeProvider.shape,
                        runtimeShaderCache = runtimeShaderCache
                    )
                }
            paint.setRuntimeShader(shader)
        } catch (_: Exception) {
            shaderUnsupported = true
            paint.setRuntimeShader(null)
        }
    }
}
