package com.jake.duolauncher.icons

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * One step of an icon mask outline, in pixels, with the icon's top-left at (0, 0).
 *
 * The geometry is kept as data rather than as an `android.graphics.Path` so that every shape in
 * FR-16 is unit-testable on the JVM; [IconRasterizer] walks these commands into a real Path.
 */
sealed interface PathCommand {
    data class MoveTo(val x: Float, val y: Float) : PathCommand
    data class LineTo(val x: Float, val y: Float) : PathCommand
    data class CubicTo(
        val x1: Float, val y1: Float,
        val x2: Float, val y2: Float,
        val x3: Float, val y3: Float,
    ) : PathCommand
    data object Close : PathCommand
}

/**
 * The icon shapes of FR-16, as pure geometry.
 *
 * Four of the five are described by a polar radius function r(θ) in [0, 1], where 1 means "touches
 * the edge of the icon box". Expressing them that way means one Bézier fitter serves all of them,
 * and it gives tests an analytic answer to check the generated curve against.
 *
 * The mask is applied to the adaptive layers *after* they are drawn, so the app's own artwork is
 * preserved and only its silhouette changes.
 */
object IconShapes {

    /**
     * Corner radius of [IconShape.ROUNDED_SQUARE] as a fraction of the icon's edge. This is the
     * same proportion the design tokens use for icon corners, so a masked icon and the tile behind
     * it stay concentric.
     */
    const val ROUNDED_SQUARE_RADIUS_RATIO = 0.2237f

    /**
     * The squircle exponent. A Lamé curve with n = 4 is the classic squircle: its corners are
     * continuous (curvature varies smoothly into the straight edge) rather than the abrupt
     * curvature step of a circular corner, which is exactly the difference FR-16 asks for between
     * Squircle and Rounded square.
     */
    const val SQUIRCLE_EXPONENT = 4.0

    /** Lobes around the scalloped "cookie" edge, and how deep they cut. */
    const val SCALLOP_LOBES = 12
    private const val SCALLOP_BASE = 0.92f
    private const val SCALLOP_AMPLITUDE = 0.08f

    /**
     * Cubic segments per full turn. Twelve would already be below a pixel of error at 512px; 24
     * keeps the scallop's twelve lobes at two segments each, which is what that shape needs.
     */
    const val POLAR_SEGMENTS = 24

    /** Whether this shape is described by [polarRadius] rather than by a rounded rectangle. */
    fun isPolar(shape: IconShape): Boolean = when (shape) {
        IconShape.SQUIRCLE, IconShape.CIRCLE, IconShape.SCALLOP -> true
        IconShape.ROUNDED_SQUARE, IconShape.SQUARE -> false
    }

    /**
     * The normalized polar radius of [shape] at [angle] radians, where 1 touches the icon box edge.
     *
     * Undefined (and unused) for the two rectangular shapes, which report 1 along their diagonal
     * rather than pretending to be polar.
     */
    fun polarRadius(shape: IconShape, angle: Float): Float = when (shape) {
        IconShape.CIRCLE -> 1f
        IconShape.SQUIRCLE -> superellipseRadius(angle, SQUIRCLE_EXPONENT)
        IconShape.SCALLOP -> SCALLOP_BASE + SCALLOP_AMPLITUDE * cos(SCALLOP_LOBES * angle)
        IconShape.SQUARE, IconShape.ROUNDED_SQUARE -> squareRadius(angle)
    }

    /**
     * The outline of [shape] for an icon of [sizePx] pixels, ready to be walked into a Path.
     *
     * Every command lies inside the [0, sizePx] box on both axes, so a mask never bleeds outside
     * the bitmap it is clipping.
     */
    fun outline(shape: IconShape, sizePx: Int): List<PathCommand> {
        require(sizePx > 0) { "An icon outline needs a positive size" }
        val size = sizePx.toFloat()
        return when (shape) {
            IconShape.SQUARE -> roundedRectangle(size, 0f)
            IconShape.ROUNDED_SQUARE -> roundedRectangle(size, size * ROUNDED_SQUARE_RADIUS_RATIO)
            else -> polarOutline(size) { angle -> polarRadius(shape, angle) }
        }
    }

    /** Whether the point ([x], [y]) is inside the mask, for tests and for hit geometry. */
    fun contains(shape: IconShape, sizePx: Int, x: Float, y: Float): Boolean {
        val half = sizePx / 2f
        val dx = x - half
        val dy = y - half
        if (abs(dx) > half || abs(dy) > half) return false
        if (!isPolar(shape)) {
            if (shape == IconShape.SQUARE) return true
            val radius = sizePx * ROUNDED_SQUARE_RADIUS_RATIO
            // Outside the corner boxes the rounded square is the full square.
            val cornerX = abs(dx) - (half - radius)
            val cornerY = abs(dy) - (half - radius)
            if (cornerX <= 0f || cornerY <= 0f) return true
            return cornerX * cornerX + cornerY * cornerY <= radius * radius
        }
        val distance = kotlin.math.sqrt(dx * dx + dy * dy)
        if (distance == 0f) return true
        val angle = kotlin.math.atan2(dy, dx)
        return distance <= polarRadius(shape, angle) * half
    }

    /**
     * The fraction of the icon box the mask covers, sampled on a grid. Used by tests to assert the
     * shapes stay ordered from the most to the least generous.
     */
    fun coverageFraction(shape: IconShape, sizePx: Int = 128, samples: Int = 256): Float {
        var inside = 0
        for (row in 0 until samples) {
            for (column in 0 until samples) {
                val x = (column + 0.5f) / samples * sizePx
                val y = (row + 0.5f) / samples * sizePx
                if (contains(shape, sizePx, x, y)) inside++
            }
        }
        return inside.toFloat() / (samples * samples)
    }

    // -----------------------------------------------------------------------
    // Curve construction
    // -----------------------------------------------------------------------

    /**
     * Fits [POLAR_SEGMENTS] cubic Béziers to the closed curve described by [radius].
     *
     * Each segment is a cubic Hermite interpolation of the parametric curve: it matches both the
     * position and the tangent of the true curve at its ends, so consecutive segments join without
     * a visible kink even on the scallop, where the radius is changing fastest.
     */
    private fun polarOutline(size: Float, radius: (Float) -> Float): List<PathCommand> {
        val step = (2.0 * PI / POLAR_SEGMENTS).toFloat()
        val commands = ArrayList<PathCommand>(POLAR_SEGMENTS + 2)
        val start = pointAt(size, 0f, radius)
        commands += PathCommand.MoveTo(start.first, start.second)
        for (segment in 0 until POLAR_SEGMENTS) {
            val from = segment * step
            val to = (segment + 1) * step
            val (x0, y0) = pointAt(size, from, radius)
            val (x1, y1) = pointAt(size, to, radius)
            val (dx0, dy0) = tangentAt(size, from, radius)
            val (dx1, dy1) = tangentAt(size, to, radius)
            val scale = step / 3f
            commands += PathCommand.CubicTo(
                clampToBox(x0 + dx0 * scale, size), clampToBox(y0 + dy0 * scale, size),
                clampToBox(x1 - dx1 * scale, size), clampToBox(y1 - dy1 * scale, size),
                clampToBox(x1, size), clampToBox(y1, size),
            )
        }
        commands += PathCommand.Close
        return commands
    }

    /** A square with circular corners of [cornerRadius], drawn clockwise from the top-left corner. */
    private fun roundedRectangle(size: Float, cornerRadius: Float): List<PathCommand> {
        val r = cornerRadius.coerceIn(0f, size / 2f)
        if (r == 0f) return listOf(
            PathCommand.MoveTo(0f, 0f),
            PathCommand.LineTo(size, 0f),
            PathCommand.LineTo(size, size),
            PathCommand.LineTo(0f, size),
            PathCommand.Close,
        )
        // The standard circular-arc Bézier constant: 4/3 * tan(pi/8).
        val k = r * 0.5522848f
        return listOf(
            PathCommand.MoveTo(r, 0f),
            PathCommand.LineTo(size - r, 0f),
            PathCommand.CubicTo(size - r + k, 0f, size, r - k, size, r),
            PathCommand.LineTo(size, size - r),
            PathCommand.CubicTo(size, size - r + k, size - r + k, size, size - r, size),
            PathCommand.LineTo(r, size),
            PathCommand.CubicTo(r - k, size, 0f, size - r + k, 0f, size - r),
            PathCommand.LineTo(0f, r),
            PathCommand.CubicTo(0f, r - k, r - k, 0f, r, 0f),
            PathCommand.Close,
        )
    }

    /** The point on the curve at [angle], in icon pixels. */
    fun pointAt(size: Float, angle: Float, radius: (Float) -> Float): Pair<Float, Float> {
        val half = size / 2f
        // Deliberately not clamped to 1. A squircle reaches ~1.19 along its diagonal, and that is
        // exactly what makes it fuller than a circle; clamping here would flatten it into one. It
        // still fits the box, whose own corner sits at 1.414.
        val r = radius(angle).coerceAtLeast(0f) * half
        return (half + r * cos(angle)) to (half + r * sin(angle))
    }

    /**
     * dP/dθ, by central difference.
     *
     * A numeric tangent keeps the fitter working for any radius function, including the scallop's
     * cosine and the superellipse, whose analytic derivative is singular in the parametrization
     * that would otherwise be natural for it.
     */
    private fun tangentAt(size: Float, angle: Float, radius: (Float) -> Float): Pair<Float, Float> {
        val h = 1e-4f
        val (ax, ay) = pointAt(size, angle - h, radius)
        val (bx, by) = pointAt(size, angle + h, radius)
        return ((bx - ax) / (2f * h)) to ((by - ay) / (2f * h))
    }

    private fun clampToBox(value: Float, size: Float): Float = value.coerceIn(0f, size)

    /** r(θ) for the Lamé curve |x|^n + |y|^n = 1, normalized so its widest point touches the box. */
    private fun superellipseRadius(angle: Float, exponent: Double): Float {
        val c = abs(cos(angle.toDouble())).pow(exponent)
        val s = abs(sin(angle.toDouble())).pow(exponent)
        return (c + s).pow(-1.0 / exponent).toFloat()
    }

    /** r(θ) of the unit square, used only so the rectangular shapes answer the polar question. */
    private fun squareRadius(angle: Float): Float {
        val c = abs(cos(angle))
        val s = abs(sin(angle))
        return 1f / maxOf(c, s).coerceAtLeast(1e-6f)
    }
}
