package com.jake.duolauncher.icons

import kotlin.math.abs
import kotlin.math.pow

/**
 * Generates a monochrome glyph from an adaptive icon's foreground layer (FR-15).
 *
 * Android only started requiring a `monochrome` layer with Android 13, and most installed apps
 * still ship none, so Clear and Tinted would otherwise show a blank tile for the majority of a
 * user's Home screen. This is the fallback that keeps those two appearances usable everywhere,
 * which is what Pixel does for every app.
 *
 * ## The approach
 *
 * The input is the app's own foreground layer, already rasterized at the target size. There are
 * two quite different kinds of foreground out there, and one rule cannot serve both:
 *
 * 1. **Alpha-defined artwork** (the common case, and what the adaptive icon guidelines ask for):
 *    a logo floating on a transparent field. Here the alpha channel already *is* the glyph, so the
 *    coverage is the alpha, and luminance is ignored. Flattening a two-tone logo to its silhouette
 *    is the intended result: a themed icon is a silhouette.
 * 2. **Full-bleed artwork**: a foreground that paints the entire layer opaque, so its silhouette is
 *    a featureless square. Here the mark has to be separated from its field by luminance instead.
 *    The field is the majority luminance; the mark is whichever side of it covers less area, which
 *    is what picks the white wordmark out of a solid blue tile rather than the other way round.
 *
 * Either way the result is normalized: the coverage is scaled so the strongest ink in the layer
 * reaches full strength, then passed through a smoothstep. Without that stretch, artwork with a
 * shallow tonal range fades to a grey smudge when it is filled with a single tint colour. Scaling
 * by the maximum rather than by a percentile band is what keeps the *relative* gradation intact,
 * so an anti-aliased edge stays softer than the mark it belongs to instead of being promoted to
 * full opacity along with it.
 *
 * Returning null means "this layer cannot produce a glyph" — a fully transparent layer, or a
 * flat-coloured one with no mark in it. The renderer falls back to the app's own icon rather than
 * showing an empty tile.
 */
object MonochromeGlyph {

    /** Below this alpha a pixel is treated as empty rather than as very faint artwork. */
    private const val ALPHA_FLOOR = 0.02f

    /** A layer this opaque, this often, is full-bleed artwork: alpha carries no shape information. */
    private const val OPAQUE_FIELD_FRACTION = 0.85f

    /** A mark covering more than this much of the layer is a field, not a mark. */
    private const val MAX_MARK_FRACTION = 0.72f

    /** A glyph needs at least this much ink to be worth showing instead of the real icon. */
    private const val MIN_MARK_FRACTION = 0.005f

    /**
     * Per-pixel glyph coverage in 0..1 for the ARGB_8888 [pixels] of a foreground layer, or null
     * when no usable glyph can be derived.
     *
     * The array is row-major and exactly [width] * [height] long, matching `Bitmap.getPixels`.
     */
    fun coverage(pixels: IntArray, width: Int, height: Int): FloatArray? {
        if (width <= 0 || height <= 0 || pixels.size != width * height) return null
        val count = pixels.size
        val alpha = FloatArray(count)
        val luminance = FloatArray(count)
        var covered = 0
        var opaque = 0
        var alphaTotal = 0f
        var luminanceTotal = 0f
        for (index in 0 until count) {
            val pixel = pixels[index]
            val a = ((pixel ushr 24) and 0xFF) / 255f
            alpha[index] = a
            if (a < ALPHA_FLOOR) continue
            covered++
            if (a >= 0.9f) opaque++
            val l = relativeLuminance(pixel)
            luminance[index] = l
            alphaTotal += a
            luminanceTotal += a * l
        }
        if (covered == 0 || alphaTotal <= 0f) return null

        val fullBleed = opaque.toFloat() / count >= OPAQUE_FIELD_FRACTION
        val raw = if (fullBleed) {
            markByLuminance(alpha, luminance, luminanceTotal / alphaTotal) ?: return null
        } else {
            alpha.copyOf()
        }
        return normalize(raw)
    }

    /**
     * Separates a mark from its field by luminance, for a foreground with no transparency to use.
     *
     * Both polarities are measured, and the one whose ink covers *less* of the layer wins: a mark
     * is by definition the minority of a full-bleed icon. A layer where neither polarity produces a
     * plausible mark (a flat colour, a gradient with no figure) returns null.
     */
    private fun markByLuminance(alpha: FloatArray, luminance: FloatArray, field: Float): FloatArray? {
        val darker = FloatArray(alpha.size)
        val lighter = FloatArray(alpha.size)
        var darkerInk = 0f
        var lighterInk = 0f
        for (index in alpha.indices) {
            val a = alpha[index]
            if (a < ALPHA_FLOOR) continue
            val delta = luminance[index] - field
            if (delta < 0f) {
                val ink = a * (-delta)
                darker[index] = ink
                darkerInk += ink
            } else {
                val ink = a * delta
                lighter[index] = ink
                lighterInk += ink
            }
        }
        val candidates = listOf(darker to darkerInk, lighter to lighterInk)
            .filter { (_, ink) -> ink > 0f }
            .sortedBy { (_, ink) -> ink }
        for ((mark, _) in candidates) {
            val fraction = mark.count { it > 0f }.toFloat() / mark.size
            if (fraction in MIN_MARK_FRACTION..MAX_MARK_FRACTION) return mark
        }
        return null
    }

    /**
     * Stretches the ink range so the glyph uses the full 0..1 scale, then softens the edge.
     *
     * The smoothstep is what keeps an anti-aliased edge looking anti-aliased instead of turning
     * into either a hard staircase or a halo once the glyph is filled with one flat colour.
     */
    private fun normalize(raw: FloatArray): FloatArray? {
        var strongest = 0f
        for (value in raw) if (value > strongest) strongest = value
        if (strongest <= 0f) return null
        val result = FloatArray(raw.size)
        var total = 0f
        for (index in raw.indices) {
            val value = raw[index]
            if (value <= 0f) continue
            val smoothed = smoothstep((value / strongest).coerceIn(0f, 1f))
            result[index] = smoothed
            total += smoothed
        }
        return if (total / raw.size < MIN_MARK_FRACTION) null else result
    }

    /** The classic 3t^2 - 2t^3 ease, on an already clamped input. */
    fun smoothstep(t: Float): Float = t * t * (3f - 2f * t)

    /**
     * WCAG relative luminance of one ARGB pixel, ignoring its alpha.
     *
     * Linearizing each channel before weighting matters here: with raw sRGB values a saturated red
     * mark and a mid grey field come out near enough identical to lose the mark entirely.
     */
    fun relativeLuminance(argb: Int): Float {
        val r = channel(((argb ushr 16) and 0xFF) / 255f)
        val g = channel(((argb ushr 8) and 0xFF) / 255f)
        val b = channel((argb and 0xFF) / 255f)
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }

    private fun channel(value: Float): Float =
        if (value <= 0.03928f) value / 12.92f else ((value + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()

    /** Mean luminance weighted by alpha, exposed for tests and diagnostics. */
    fun meanLuminance(pixels: IntArray): Float {
        var alphaTotal = 0f
        var weighted = 0f
        for (pixel in pixels) {
            val a = ((pixel ushr 24) and 0xFF) / 255f
            if (a < ALPHA_FLOOR) continue
            alphaTotal += a
            weighted += a * relativeLuminance(pixel)
        }
        return if (alphaTotal <= 0f) 0f else weighted / alphaTotal
    }

    /** How much of the layer the finished glyph inks, for tests. */
    fun inkFraction(coverage: FloatArray): Float =
        if (coverage.isEmpty()) 0f else coverage.count { it > 0.5f }.toFloat() / coverage.size
}

/** Blends [intensity] (0..100) of the tint into a fully inked glyph pixel (FR-14, Tinted). */
fun tintedAlpha(coverage: Float, intensity: Int): Float =
    (coverage * (intensity.coerceIn(0, IconStyle.MAX_TINT_INTENSITY) / 100f)).coerceIn(0f, 1f)

/** Whether two coverage maps agree, within [tolerance]. Test and diagnostic helper. */
internal fun coverageMatches(a: FloatArray, b: FloatArray, tolerance: Float = 1e-3f): Boolean =
    a.size == b.size && a.indices.all { abs(a[it] - b[it]) <= tolerance }
