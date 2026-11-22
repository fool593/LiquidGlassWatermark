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
import androidx.compose.ui.graphics.FilterQuality
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

    internal var ownLayerAttached: Boolean = false

    var veilStrength: Float by mutableStateOf(1f)

    var capturePaused: Boolean by mutableStateOf(false)

    var recordThrottleMs: Long = 0L

    var layerScale: Float = 1f

    /** Optional preblurred capture of this exact scene, shared by all its consumers. */
    var softwareImage: ImageBitmap? by mutableStateOf(null)

    var snapshotFade: Float by mutableStateOf(1f)

    var liveMode: Boolean by mutableStateOf(false)

    var forceSoftwareSnapshot: Boolean by mutableStateOf(false)

    var contentStatic: Boolean = false

    var contentRevision: Int by mutableStateOf(0)
        private set

    fun invalidateStaticContent() {
        contentRevision++
    }

    private var inverseLayerScope: InverseLayerScope? = null

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

        var usedLiveLayer = false

        val scaledLayer = (softwareBlurEnabled || forceSoftwareSnapshot) &&
            !liveMode &&
            layerScale < 1f &&
            !com.liquidglass.watermark.glass.isRenderEffectSupported()

        val nodeOffset =
            try {
                layerCoordinates.localPositionOf(coordinates)
            } catch (_: Exception) {
                // TODO: outer transformations lead to wrong position calculation
                coordinates.positionInWindow() - layerCoordinates.positionInWindow()
            }

        var veilMix = 1f
        val drawScene: DrawScope.() -> Unit = {

            val image = softwareImage.takeIf {
                (softwareBlurEnabled || forceSoftwareSnapshot) && !liveMode &&
                    !com.liquidglass.watermark.glass.isRenderEffectSupported()
            }

            val fade = if (image != null) snapshotFade.coerceIn(0f, 1f) else 0f
            veilMix = if (image != null) 1f - fade else 1f
            if (image != null && fade >= 0.999f) {
                drawImage(
                    image = image,
                    dstSize = layerCoordinates.size,

                    filterQuality = FilterQuality.High,
                )
            } else {

                val waitForSnapshot = (softwareBlurEnabled || forceSoftwareSnapshot) && !liveMode &&
                    softwareImage == null &&
                    !com.liquidglass.watermark.glass.isRenderEffectSupported()
                if (!waitForSnapshot) {
                    drawLayer(graphicsLayer)
                    usedLiveLayer = true
                }
                if (image != null && fade > 0.01f) {
                    drawImage(
                        image = image,
                        dstSize = layerCoordinates.size,
                        alpha = fade,
                        filterQuality = FilterQuality.High,
                    )
                }
            }
        }

        val drawVeil: (Float) -> Unit = { mix ->

            val veilK = veilStrength * mix
            if (veilK > 0.01f) {
                val dark = com.liquidglass.watermark.darkModeState
                val top = (if (dark) 0.18f else 0.16f) * veilK
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFFE9EBF1).copy(alpha = top),
                            Color(0xFFF3F4F8).copy(alpha = top * 0.55f),
                        ),
                        startY = 0f,
                        endY = size.height,
                    ),
                    size = size
                )
            }
        }

        // Translate the backdrop scene so that the node's local origin aligns with it.
        val sceneTransform: DrawTransformScope = {
            withTransform({
                if (layerBlock != null) {
                    with(obtainInverseLayerScope()) { inverseTransform(density, layerBlock) }
                }
                val s = if (scaledLayer) layerScale else 1f
                translate(-nodeOffset.x * s, -nodeOffset.y * s)
            }) {
                drawScene()
            }
        }

        if (lens == null) {
            sceneTransform()
            if (usedLiveLayer) drawVeil(veilMix)
            return
        }

        // ── Software refraction (low tier / no AGSL) ───────────────────────────────

        //

        //

        val shortSide = min(size.width, size.height)
        val magnification = lens.magnification(shortSide)

        val band = maxOf(lens.band(shortSide), lens.amountPx * 0.55f)
            .coerceIn(1f, shortSide * 0.4f)
        val path = obtainLensPath(size, density, layoutDirection, lens.shape)
        val cornerRadius = lensCornerRadius(lens.shape, size, density, layoutDirection, shortSide)

        val levels = if (usedLiveLayer) 2 else 3
        clipPath(path) {
            for (i in 0 until levels) {
                val f = if (levels > 1) i / (levels - 1f) else 0f
                val inset = band * f

                val s = 1f + magnification * 1.5f * (1f - f)
                val layerPath = obtainInsetPath(i, size, inset, cornerRadius)
                clipPath(layerPath) {
                    withTransform({
                        if (s > 1.0005f) scale(s, s, center)
                    }) {
                        sceneTransform()
                    }
                }
            }

            if (usedLiveLayer) drawVeil(veilMix)
            // Bevel band: the edge compresses the background into a thin bright rim.
            if (band > 0.5f) {
                drawPath(
                    path = path,
                    brush = obtainBandBrush(size),
                    style = Stroke(width = (band * 0.6f).coerceAtLeast(1f))
                )
            }

            val disp = lens.dispersion
            if (disp > 0.02f) {
                val off = (0.6f + band * 0.6f) * disp
                val w = (1f + 1.6f * disp).coerceAtLeast(1f)
                drawPath(
                    path = path,
                    color = Color(0xFF6FE2FF).copy(alpha = 0.16f * disp),
                    style = Stroke(width = w),
                )
                withTransform({ scale(1f + off / maxOf(size.width, 1f) * 2f, 1f + off / maxOf(size.height, 1f) * 2f, center) }) {
                    drawPath(
                        path = path,
                        color = Color(0xFFFF7CC8).copy(alpha = 0.13f * disp),
                        style = Stroke(width = w),
                    )
                }
            }
        }
    }

    private val insetPaths = arrayOfNulls<Path>(8)

    private fun obtainInsetPath(
        index: Int,
        size: Size,
        inset: Float,
        radius: Float
    ): Path {
        val path = insetPaths.getOrNull(index) ?: Path().also { insetPaths[index] = it }
        path.reset()
        val r = (radius - inset).coerceAtLeast(0f)
        path.addRoundRect(
            androidx.compose.ui.geometry.RoundRect(
                left = inset,
                top = inset,
                right = size.width - inset,
                bottom = size.height - inset,
                topLeftCornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
                topRightCornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
                bottomRightCornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
                bottomLeftCornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
            )
        )
        return path
    }

    private var lensOutlinePath: Path? = null
    private var lensOutlineSize: Long = Long.MIN_VALUE

    @Suppress("unused")
    private fun lensOutlineCache(size: Size, radius: Float): Path {
        val key = size.width.toRawBits().toLong() * 31 + size.height.toRawBits().toLong() * 7 + radius.toRawBits().toLong()
        val cached = lensOutlinePath
        if (cached != null && lensOutlineSize == key) return cached
        val path = cached ?: Path().also { lensOutlinePath = it }
        path.reset()
        path.addRoundRect(
            androidx.compose.ui.geometry.RoundRect(
                left = 0f,
                top = 0f,
                right = size.width,
                bottom = size.height,
                topLeftCornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
                topRightCornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
                bottomRightCornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
                bottomLeftCornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
            )
        )
        lensOutlineSize = key
        return path
    }

    private fun lensCornerRadius(
        shape: Shape,
        size: Size,
        density: Density,
        layoutDirection: LayoutDirection,
        shortSide: Float
    ): Float {
        val cornerShape = shape as? androidx.compose.foundation.shape.CornerBasedShape
            ?: return shortSide / 2f
        return try {
            cornerShape.topStart.toPx(size, density).coerceIn(0f, shortSide / 2f)
        } catch (_: Throwable) {
            shortSide / 2f
        }
    }

    private fun obtainInverseLayerScope(): InverseLayerScope {
        return inverseLayerScope?.apply { reset() }
            ?: InverseLayerScope().also { inverseLayerScope = it }
    }
}

/** Local alias so the scene-drawing lambda stays readable. */
private typealias DrawTransformScope = DrawScope.() -> Unit
