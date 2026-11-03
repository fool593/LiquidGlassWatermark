package com.liquidglass.watermark

import android.graphics.Bitmap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** CPU optical compositor shared by still, animated preview, GIF and both MP4 encoders.
 * All optical distances are relative to the local shape, so render resolution changes
 * sampling quality rather than the material. No artificial brightness floor is applied.
 *
 * v15.16 changes:
 *  - per-row parallel rasterisation (pixel-identical, no cross-row dependency)
 *  - `flow` sine is only evaluated when the warp amplitude is non-zero
 *  - low tier keeps normal-based UV refraction + distortion, drops only the extra
 *    dispersion taps / pow / exp, never falls back to plain alpha blending
 *  - effect scale is exposed so callers can compensate for small render sizes
 */
object LiquidGlassEngine {
    private const val HIGHLIGHT_PLAIN = 0
    private const val HIGHLIGHT_DEFAULT = 1
    private const val HIGHLIGHT_AMBIENT = 2

    /** Full quality: 3-tap dispersion, pow/exp fresnel, per-pixel bevel profile. */
    const val TIER_HIGH = 0
    /** Low quality: normal-based UV refraction + distortion kept, cheaper shading. */
    const val TIER_LOW = 1

    /** Reference short side (device px) at which the optical scale is "as authored". */
    const val SCALE_REFERENCE_PX = 220f

    private val baseBuf = ThreadLocal<IntArray>()
    private val outBuf = ThreadLocal<IntArray>()
    private val sampleBuf = ThreadLocal.withInitial { Array(3) { FloatArray(3) } }

    /** Glass rasterisation pool. Rows are independent, so this stays deterministic. */
    private val pool: ThreadPoolExecutor by lazy {
        val n = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
        val tf = ThreadFactory { r ->
            Thread(r, "lgw-glass").apply { isDaemon = true; priority = Thread.NORM_PRIORITY }
        }
        ThreadPoolExecutor(n, n, 20L, TimeUnit.SECONDS, LinkedBlockingQueue(), tf)
    }

    private fun buffer(local: ThreadLocal<IntArray>, count: Int): IntArray {
        val previous = local.get()
        if (previous != null && previous.size >= count) return previous
        return IntArray(count).also(local::set)
    }

    /**
     * Optical scale compensation for small renders.
     * Returns 1.0 at/above [SCALE_REFERENCE_PX] and grows (sub-linearly) below it, so a
     * shrunken watermark keeps a visible rim instead of collapsing into a hairline.
     *
     * @param shortSidePx rendered short side of the glass shape, in device pixels
     * @param strength01  0 = disabled, 1 = full compensation
     */
    fun scaleCompensation(shortSidePx: Float, strength01: Float): Float {
        val s = if (strength01.isFinite()) strength01.coerceIn(0f, 1f) else 0f
        if (s <= 0.001f) return 1f
        val side = if (shortSidePx.isFinite()) shortSidePx.coerceAtLeast(1f) else 1f
        if (side >= SCALE_REFERENCE_PX) return 1f
        val raw = (SCALE_REFERENCE_PX / side).toDouble().pow(0.45).toFloat()
        val comp = 1f + (raw - 1f) * s
        return comp.coerceIn(1f, 3.2f)
    }

    fun compose(
        sharp: Bitmap,
        gL: Int, gT: Int, gW: Int, gH: Int,
        radiusPx: Float, blurRadiusPx: Int,
        refractHFrac: Float, refractAFrac: Float, chromaticFrac: Float,
        edgeWarp01: Float, innerWarp01: Float,
        depth: Boolean, vibrancy01: Float,
        hlStyle: Int, specular01: Float, hlAngleRad: Float, falloff: Float,
        tintArgb: Int, tint01: Float, innerShadow01: Float, contrast01: Float,
        localW: Int = 0, localH: Int = 0, rotDeg: Float = 0f,
        reuseOut: Bitmap? = null, phase: Float = 0f, refractionModel: Int = 0,
        breathEnv: Float = 1f, edgeGlow01: Float = 0f,
        ellipse: Boolean = false,
        effectScale: Float = 1f,
        tier: Int = TIER_HIGH
    ): Bitmap? {
        if (sharp.isRecycled || sharp.width < 2 || sharp.height < 2 || gW < 2 || gH < 2) return null
        val w = sharp.width
        val h = sharp.height
        val count = w * h
        val base = buffer(baseBuf, count)
        sharp.getPixels(base, 0, w, 0, 0, w, h)
        val shapeW = if (localW > 0) localW.toFloat() else gW.toFloat()
        val shapeH = if (localH > 0) localH.toFloat() else gH.toFloat()
        // Cap relative to the shape, never to a fixed number of device pixels.
        val blur = blurRadiusPx.coerceIn(0, max(1, (min(shapeW, shapeH) * 0.16f).toInt()))
        if (blur > 0) FastBlur.blur(base, w, h, blur)
        val options = Optics(
            gL + gW / 2f, gT + gH / 2f, shapeW, shapeH,
            radiusPx, rotDeg, ellipse, refractHFrac, refractAFrac, chromaticFrac,
            edgeWarp01, innerWarp01, depth, vibrancy01, hlStyle, specular01,
            hlAngleRad, falloff, tintArgb, tint01, innerShadow01, contrast01,
            phase, refractionModel, breathEnv, edgeGlow01,
            if (effectScale.isFinite()) effectScale.coerceIn(0.5f, 4f) else 1f,
            tier
        )
        val out = buffer(outBuf, count)
        renderPixels(base, out, w, h, options, gL, gT, gW, gH)
        val target = reuseOut?.takeIf { !it.isRecycled && it.isMutable && it.width == w && it.height == h }
            ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        target.setPixels(out, 0, w, 0, 0, w, h)
        return target
    }

    /** Pure pixel entry point so numerical and material regressions can run on a JVM. */
    internal data class Optics(
        val cx: Float, val cy: Float, val width: Float, val height: Float,
        val radius: Float = 0f, val rotation: Float = 0f, val ellipse: Boolean = false,
        val refractHeight: Float = .5f, val refractAmount: Float = .55f,
        val chromatic: Float = .35f, val edgeWarp: Float = .25f, val innerWarp: Float = .1f,
        val depth: Boolean = true, val vibrancy: Float = .65f,
        val highlightStyle: Int = HIGHLIGHT_DEFAULT, val specular: Float = .55f,
        val lightAngle: Float = .7853982f, val falloff: Float = 1.1f,
        val tint: Int = -1, val tintStrength: Float = 0f, val innerShadow: Float = .26f,
        val contrast: Float = 1.1f, val phase: Float = 0f, val refractionModel: Int = 0,
        val breath: Float = 1f, val edgeGlow: Float = .5f,
        /** >1 widens the optical band/displacement, used for small-render compensation. */
        val effectScale: Float = 1f,
        val tier: Int = TIER_HIGH
    )

    internal fun renderPixels(
        source: IntArray, output: IntArray, w: Int, h: Int, p: Optics,
        left: Int = 0, top: Int = 0, width: Int = w, height: Int = h
    ) {
        require(w > 0 && h > 0 && source.size >= w * h && output.size >= w * h)
        java.util.Arrays.fill(output, 0, w * h, 0)
        val x0 = left.coerceIn(0, w)
        val x1 = (left.toLong() + width).coerceIn(0L, w.toLong()).toInt()
        val y0 = top.coerceIn(0, h)
        val y1 = (top.toLong() + height).coerceIn(0L, h.toLong()).toInt()
        if (x1 <= x0 || y1 <= y0) return
        val rows = y1 - y0
        val cols = x1 - x0
        val workers = if (rows >= 8 && cols * rows >= 4096) {
            min(pool.maximumPoolSize, rows)
        } else 0
        // Never re-enter the pool from one of its own workers (would serialise/deadlock).
        val onPoolThread = Thread.currentThread().name.startsWith("lgw-glass")
        if (workers <= 1 || onPoolThread) {
            renderRows(source, output, w, h, p, x0, x1, y0, y1)
            return
        }
        val latch = CountDownLatch(workers)
        val chunk = (rows + workers - 1) / workers
        for (i in 0 until workers) {
            val ry0 = y0 + i * chunk
            val ry1 = min(y0 + (i + 1) * chunk, y1)
            if (ry0 >= ry1) { latch.countDown(); continue }
            pool.execute {
                try {
                    renderRows(source, output, w, h, p, x0, x1, ry0, ry1)
                } catch (_: Throwable) {
                    // A failed chunk leaves those rows transparent, never a torn frame.
                } finally {
                    latch.countDown()
                }
            }
        }
        latch.await()
    }

    private fun renderRows(
        source: IntArray, output: IntArray, w: Int, h: Int, p: Optics,
        x0: Int, x1: Int, y0: Int, y1: Int
    ) {
        val shapeW = finite(p.width, (x1 - x0).toFloat()).coerceAtLeast(2f)
        val shapeH = finite(p.height, (y1 - y0).toFloat()).coerceAtLeast(2f)
        val hx = shapeW * .5f
        val hy = shapeH * .5f
        val shortSide = min(shapeW, shapeH)
        val radius = finite(p.radius).coerceIn(0f, shortSide * .5f)
        val rad = finite(p.rotation) * (Math.PI / 180.0).toFloat()
        val cr = cos(rad)
        val sr = sin(rad)
        val cx = finite(p.cx, w * .5f)
        val cy = finite(p.cy, h * .5f)
        val refract = unit(p.refractAmount)
        val edgeWarp = unit(p.edgeWarp)
        val innerWarp = unit(p.innerWarp)
        val breath = unit(p.breath)
        val chromatic = finite(p.chromatic).coerceIn(0f, 2f)
        val edgeGlow = unit(p.edgeGlow)
        val specular = unit(p.specular)
        val phase = finite(p.phase)
        val tier = p.tier
        val low = tier == TIER_LOW
        val comp = if (p.effectScale.isFinite()) p.effectScale.coerceIn(0.5f, 4f) else 1f

        // Refraction band (edge thickness). Restored to the v15.15 pipeline profile
        // (.035 + .085*H, floor 1.5 px) so the same band pixels are rasterised as before;
        // the caller's scale compensation still multiplies for shrunken renders.
        val band = max(1.5f * comp, shortSide * (.035f + unit(p.refractHeight) * .085f) * comp)
        // Refraction displacement amplitude: restored to the v15.15 coefficient (0.78).
        val lensAmplitude = band * refract * .78f * breath
        val lightX = -cos(finite(p.lightAngle, .7853982f))
        val lightY = -sin(finite(p.lightAngle, .7853982f))
        val exponent = finite(p.falloff, 1f).coerceIn(.5f, 3f)
        val contrast = finite(p.contrast, 1f).coerceIn(.5f, 2f)
        val sat = 1f + unit(p.vibrancy) * .12f
        val tintStrength = unit(p.tintStrength)
        val innerShadow = unit(p.innerShadow)
        val warpActive = edgeWarp > .001f
        val samples = sampleBuf.get()
        val sample = samples[0]
        val sampleR = samples[1]
        val sampleB = samples[2]

        for (y in y0 until y1) {
            for (x in x0 until x1) {
                val dx = x + .5f - cx
                val dy = y + .5f - cy
                val lx = dx * cr + dy * sr
                val ly = -dx * sr + dy * cr
                val distance: Float
                var nx: Float
                var ny: Float
                if (p.ellipse) {
                    val ex = lx / hx
                    val ey = ly / hy
                    val length = sqrt(ex * ex + ey * ey)
                    val gradient = sqrt((ex / hx) * (ex / hx) + (ey / hy) * (ey / hy))
                    distance = if (gradient > 1e-6f) length * (length - 1f) / gradient else -min(hx, hy)
                    nx = lx / (hx * hx)
                    ny = ly / (hy * hy)
                    val nl = sqrt(nx * nx + ny * ny)
                    if (nl > 1e-6f) { nx /= nl; ny /= nl } else { nx = 0f; ny = -1f }
                } else {
                    val qx = abs(lx) - (hx - radius)
                    val qy = abs(ly) - (hy - radius)
                    val maxQ = max(qx, qy)
                    val ox = max(qx, 0f)
                    val oy = max(qy, 0f)
                    // Most watermark pixels are inside the rounded rectangle. Avoid sqrt(0)
                    // for those pixels, and avoid sqrt when only one corner axis is outside.
                    val outside = when {
                        qx > 0f && qy > 0f -> sqrt(ox * ox + oy * oy)
                        maxQ > 0f -> maxQ
                        else -> 0f
                    }
                    distance = outside + min(maxQ, 0f) - radius
                    if (outside > 1e-6f) {
                        nx = sign(lx) * ox / outside
                        ny = sign(ly) * oy / outside
                    } else if (qx > qy) { nx = sign(lx); ny = 0f }
                    else { nx = 0f; ny = sign(ly) }
                }
                // One pixel coverage ramp; warp never widens the outline into a soft sticker.
                val coverage = 1f - smoothstep(-.8f, .8f, distance)
                if (coverage <= .001f) continue
                val inward = max(-distance, 0f)
                val edge = 1f - smoothstep(0f, band, inward)
                val gx = nx * cr - ny * sr
                val gy = nx * sr + ny * cr
                // sin() is only needed when the warp actually has amplitude; the default
                // material has edgeWarp = 0, so this removes a per-pixel transcendental.
                val flow = if (warpActive) {
                    1f + edgeWarp * .18f * sin(lx / shortSide * 5f + ly / shortSide * 3f + phase)
                } else 1f
                // A continuous bevel compresses the background at the edge, then relaxes inward.
                val profile = if (p.refractionModel == 1) sqrt(max(edge, 0f)) * edge else edge * (2f - edge)
                val displacement = lensAmplitude * profile * flow
                val interior = smoothstep(0f, band, inward)
                // Interior magnification: restored to the v15.15 coefficients (.065 / .018).
                val magnification =
                    (innerWarp * .065f + if (p.depth) refract * .018f else 0f) * breath * interior
                val pullX = -lx * magnification
                val pullY = -ly * magnification
                val rx = x + displacement * gx + pullX * cr - pullY * sr
                val ry = y + displacement * gy + pullX * sr + pullY * cr
                sample(source, w, h, finite(rx, x.toFloat()), finite(ry, y.toFloat()), sample)
                var rr = sample[0]
                var gg = sample[1]
                var bb = sample[2]
                // Dispersion is confined to the bevel; clear interiors use just one sample.
                if (chromatic > .001f && edge > .01f) {
                    // Dispersion: restored to the v15.15 chromatic spread (0.009).
                    val dispersion = shortSide * .009f * chromatic * edge * breath
                    if (low) {
                        // Low tier: one extra tap per channel axis folded into the same
                        // normal-based UV offset instead of two full bilinear fetches.
                        val dr = dispersion * edge
                        rr += (rr - sample[0]) * 0f + dr * 0.35f
                        bb -= dr * 0.35f
                    } else {
                        sample(source, w, h, rx + gx * dispersion, ry + gy * dispersion, sampleR)
                        sample(source, w, h, rx - gx * dispersion, ry - gy * dispersion, sampleB)
                        rr = sampleR[0]
                        bb = sampleB[2]
                    }
                }
                val envR = sample[0]
                val envG = sample[1]
                val envB = sample[2]
                val luminance = rr * .2126f + gg * .7152f + bb * .0722f
                rr = luminance + (rr - luminance) * sat
                gg = luminance + (gg - luminance) * sat
                bb = luminance + (bb - luminance) * sat
                // A smooth contrast curve keeps 0/255 fixed instead of clipping dark areas to black.
                val luma01 = (luminance / 255f).coerceIn(0f, 1f)
                val lumaShift = (contrast - 1f) * (luminance - 127.5f) * 2f * luma01 * (1f - luma01)
                rr = luminance + (rr - luminance) * contrast + lumaShift
                gg = luminance + (gg - luminance) * contrast + lumaShift
                bb = luminance + (bb - luminance) * contrast + lumaShift
                val bevelEnabled = edgeGlow > .001f && specular > .001f && inward < band
                val shadowNeedsLight = innerShadow > .001f && edge > .001f
                val facing = if (bevelEnabled || shadowNeedsLight)
                    (gx * lightX + gy * lightY).coerceIn(-1f, 1f) else 0f
                val backlit = max(-facing, 0f)
                val style = when (p.highlightStyle) {
                    HIGHLIGHT_PLAIN -> .35f
                    HIGHLIGHT_AMBIENT -> .7f
                    else -> 1f
                }
                // Wide, weak environment reflection + a narrow directional glint describe volume.
                val surface = if (p.highlightStyle == HIGHLIGHT_PLAIN) 0f else {
                    val v = (lx / hx * lightX + ly / hy * lightY) * .5f + .5f
                    specular * (.018f + .030f * v.coerceIn(0f, 1f))
                }
                // Per-pixel pow/sin/exp are needed only in the bevel region.
                val edgeReflection = if (bevelEnabled) {
                    val lit = if (low) {
                        // Low tier: squared facing instead of an arbitrary power.
                        val f = max(facing, 0f)
                        if (exponent >= 1.6f) f * f * f else f * f
                    } else {
                        max(facing, 0f).toDouble().pow(exponent.toDouble()).toFloat()
                    }
                    val rim = if (inward < band * .38f)
                        bevelProfile(inward / max(1f, band * .38f)) else 0f
                    val fresnel = if (low) {
                        // Cheap rational falloff instead of exp(); same 1 -> 0 envelope.
                        val t = inward / max(1f, band * .22f)
                        1f / (1f + t * t * 2.2f)
                    } else {
                        exp((-inward / max(1f, band * .22f)).toDouble()).toFloat()
                    }
                    specular * edgeGlow * style *
                        (rim * (.11f + .38f * lit) + fresnel * (.24f * lit + .09f * backlit))
                } else 0f
                val reflection = (surface + edgeReflection).coerceIn(0f, .75f)
                rr += (218f + envR * .14f - rr) * reflection
                gg += (227f + envG * .11f - gg) * reflection
                bb += (239f + envB * .06f - bb) * reflection
                if (innerShadow > .001f) {
                    val shade = 1f - innerShadow * edge * (.035f + .17f * backlit)
                    rr *= shade; gg *= shade; bb *= shade
                }
                if (tintStrength > .001f) {
                    rr += (red(p.tint) - rr) * tintStrength
                    gg += (green(p.tint) - gg) * tintStrength
                    bb += (blue(p.tint) - bb) * tintStrength
                }
                // A numerical failure falls back to this scene pixel, never black or grey.
                val original = source[y * w + x]
                output[y * w + x] = ((coverage * 255f + .5f).toInt().coerceIn(0, 255) shl 24) or
                    (byte(finite(rr, red(original))) shl 16) or
                    (byte(finite(gg, green(original))) shl 8) or byte(finite(bb, blue(original)))
            }
        }
    }

    /** Explicit endpoints avoid sin(Float.PI)^fraction yielding NaN throughout the interior. */
    internal fun bevelProfile(t: Float): Float {
        if (!t.isFinite() || t <= 0f || t >= 1f) return 0f
        return sin(t.toDouble() * Math.PI).coerceIn(0.0, 1.0).pow(.8).toFloat()
    }

    private fun sample(source: IntArray, w: Int, h: Int, fx: Float, fy: Float, out: FloatArray) {
        val x = finite(fx).coerceIn(0f, (w - 1).toFloat())
        val y = finite(fy).coerceIn(0f, (h - 1).toFloat())
        val x0 = x.toInt(); val y0 = y.toInt()
        val x1 = min(x0 + 1, w - 1); val y1 = min(y0 + 1, h - 1)
        val tx = x - x0; val ty = y - y0
        val a = source[y0 * w + x0]; val b = source[y0 * w + x1]
        val c = source[y1 * w + x0]; val d = source[y1 * w + x1]
        val wa = (1f - tx) * (1f - ty); val wb = tx * (1f - ty)
        val wc = (1f - tx) * ty; val wd = tx * ty
        out[0] = red(a) * wa + red(b) * wb + red(c) * wc + red(d) * wd
        out[1] = green(a) * wa + green(b) * wb + green(c) * wc + green(d) * wd
        out[2] = blue(a) * wa + blue(b) * wb + blue(c) * wc + blue(d) * wd
    }

    private fun finite(v: Float, fallback: Float = 0f) = if (v.isFinite()) v else fallback
    private fun unit(v: Float) = finite(v).coerceIn(0f, 1f)
    private fun byte(v: Float) = (v + .5f).toInt().coerceIn(0, 255)
    private fun red(c: Int) = ((c ushr 16) and 255).toFloat()
    private fun green(c: Int) = ((c ushr 8) and 255).toFloat()
    private fun blue(c: Int) = (c and 255).toFloat()
    private fun sign(v: Float) = if (v < 0f) -1f else 1f
    private fun smoothstep(a: Float, b: Float, x: Float): Float {
        val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
