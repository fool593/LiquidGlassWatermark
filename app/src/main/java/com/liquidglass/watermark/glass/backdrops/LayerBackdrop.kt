package com.liquidglass.watermark.glass.backdrops

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.liquidglass.watermark.glass.Backdrop
import com.liquidglass.watermark.glass.SoftwareLens
import com.liquidglass.watermark.glass.hasFiniteArea
import com.liquidglass.watermark.glass.internal.InverseLayerScope
import kotlin.math.min

private val DefaultOnDraw: ContentDrawScope.() -> Unit = { drawContent() }

@Composable
fun rememberLayerBackdrop(
    graphicsLayer: GraphicsLayer = rememberGraphicsLayer(),
    onDraw: ContentDrawScope.() -> Unit = DefaultOnDraw
): LayerBackdrop {
    return remember(graphicsLayer, onDraw) {
        LayerBackdrop(graphicsLayer, onDraw)
    }
}

@Stable
class LayerBackdrop internal constructor(
    val graphicsLayer: GraphicsLayer,
    internal val onDraw: ContentDrawScope.() -> Unit
) : Backdrop {

    override val isCoordinatesDependent: Boolean = true

    internal var layerCoordinates: LayoutCoordinates? by mutableStateOf(null)

    /** Optional preblurred capture of this exact scene, shared by all its consumers. */
    var softwareImage: ImageBitmap? by mutableStateOf(null)

    /**
     * v15.16 任务3：当这一层的绘制内容在尺寸不变时保持恒定时，置为 true 可跳过每帧重录。
     *
     * 默认 false → 行为与修复前完全一致（每帧 `recordLayer`）。
     * 只对内容确实静态的层开启（目前仅 `EditorScreen` 的全屏 `liquidBackground()` 场景层）。
     * 内容变化时必须调用 [invalidateStaticContent] 让缓存失效。
     */
    var contentStatic: Boolean = false

    /** 静态内容版本号：内容（如深色模式切换）变化时自增，使 `LayerBackdropNode` 重新录制。 */
    var contentRevision: Int by mutableStateOf(0)
        private set

    fun invalidateStaticContent() {
        contentRevision++
    }

    private var inverseLayerScope: InverseLayerScope? = null

    /**
     * v15.16 任务3 卡顿修复：软件折射用的 Path 与边缘渐变按尺寸/形状缓存复用。
     *
     * 修复前：每个玻璃控件每帧都要 `Path().apply { addOutline(outline) }` 新建一个 Path，
     * 再 `Brush.linearGradient(listOf(...))` 新建两个 Color + 一个 List + 一个 Brush。
     * 参数面板单页 10+ 滑块/开关时，这是每帧几十次对象分配 → GC 压力与主线程分配抖动。
     * Path 内容只由 (尺寸, 形状, 布局方向, 密度) 决定，渐变只由尺寸决定 → 可安全复用，
     * 绘制结果逐像素一致。
     */
    private var cachedPath: Path? = null
    private var cachedBandBrush: Brush? = null
    private var cachedBandSize: Long = Long.MIN_VALUE
    private var cachedKey: Long = Long.MIN_VALUE

    private fun obtainLensPath(
        size: Size,
        density: Density,
        layoutDirection: LayoutDirection,
        shape: Shape
    ): Path {
        val key = lensKey(size, density, layoutDirection, shape)
        if (key == cachedKey) {
            cachedPath?.let { return it }
        }
        val outline = shape.createOutline(size, layoutDirection, density)
        val path = cachedPath ?: Path().also { cachedPath = it }
        path.reset()
        path.addOutline(outline)
        cachedKey = key
        return path
    }

    private fun lensKey(
        size: Size,
        density: Density,
        layoutDirection: LayoutDirection,
        shape: Shape
    ): Long {
        var h = size.width.toRawBits().toLong()
        h = h * 31 + size.height.toRawBits().toLong()
        h = h * 31 + density.density.toRawBits().toLong()
        h = h * 31 + layoutDirection.ordinal.toLong()
        h = h * 31 + shape.hashCode().toLong()
        return h
    }

    private fun obtainBandBrush(size: Size): Brush {
        val key = (size.width.toRawBits().toLong() * 31) + size.height.toRawBits().toLong()
        if (key == cachedBandSize) {
            cachedBandBrush?.let { return it }
        }
        val created = Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = .16f),
                Color.White.copy(alpha = .03f)
            ),
            start = Offset(size.width * .15f, 0f),
            end = Offset(size.width * .85f, size.height)
        )
        cachedBandBrush = created
        cachedBandSize = key
        return created
    }

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
        lens: SoftwareLens?
    ) {
        if (!size.hasFiniteArea()) return
        val coordinates = coordinates ?: return
        val layerCoordinates = layerCoordinates ?: return
        if (!coordinates.isAttached || !layerCoordinates.isAttached) return

        val drawScene: DrawScope.() -> Unit = {
            val image = softwareImage.takeIf { softwareBlurEnabled }
            if (image != null) {
                drawImage(image, dstSize = layerCoordinates.size)
            } else {
                drawLayer(graphicsLayer)
            }
        }

        // Translate the backdrop scene so that the node's local origin aligns with it.
        val sceneTransform: DrawTransformScope = {
            withTransform({
                if (layerBlock != null) {
                    with(obtainInverseLayerScope()) { inverseTransform(density, layerBlock) }
                }
                val offset =
                    try {
                        layerCoordinates.localPositionOf(coordinates)
                    } catch (_: Exception) {
                        // TODO: outer transformations lead to wrong position calculation
                        coordinates.positionInWindow() - layerCoordinates.positionInWindow()
                    }
                translate(-offset.x, -offset.y)
            }) {
                drawScene()
            }
        }

        if (lens == null) {
            sceneTransform()
            return
        }

        // ── Software refraction (low tier / no AGSL) ───────────────────────────────
        // The AGSL lens displaces the background along the SDF normal. Without AGSL we
        // reproduce the dominant term of that optics: a normal-driven radial magnification
        // of the recorded backdrop, clipped to the glass shape. The interior therefore still
        // bends the scene (a real geometric refraction, not an alpha tint), and the border
        // band keeps a compressed, brighter rim so the edge thickness stays readable.
        val shortSide = min(size.width, size.height)
        val magnification = lens.magnification(shortSide)
        val path = obtainLensPath(size, density, layoutDirection, lens.shape)
        clipPath(path) {
            withTransform({
                if (magnification > 0.0005f) {
                    scale(1f + magnification, 1f + magnification, center)
                }
            }) {
                sceneTransform()
            }
            // Bevel band: the edge compresses the background into a thin bright rim.
            val band = lens.band(shortSide)
            if (band > 0.5f) {
                drawPath(
                    path = path,
                    brush = obtainBandBrush(size),
                    style = Stroke(width = band)
                )
            }
        }
    }

    private fun obtainInverseLayerScope(): InverseLayerScope {
        return inverseLayerScope?.apply { reset() }
            ?: InverseLayerScope().also { inverseLayerScope = it }
    }
}

/** Local alias so the scene-drawing lambda stays readable. */
private typealias DrawTransformScope = DrawScope.() -> Unit
