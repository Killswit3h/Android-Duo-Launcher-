package com.jake.duolauncher.icons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot

private const val SIZE = 256

/** Evaluates one cubic Bézier segment at [t]. */
private fun cubic(
    x0: Float, y0: Float, command: PathCommand.CubicTo, t: Float,
): Pair<Float, Float> {
    val u = 1f - t
    fun axis(p0: Float, p1: Float, p2: Float, p3: Float) =
        u * u * u * p0 + 3f * u * u * t * p1 + 3f * u * t * t * p2 + t * t * t * p3
    return axis(x0, command.x1, command.x2, command.x3) to
        axis(y0, command.y1, command.y2, command.y3)
}

/** Every point the outline passes through, sampling each segment. */
private fun sampleOutline(shape: IconShape, samplesPerSegment: Int = 8): List<Pair<Float, Float>> {
    val commands = IconShapes.outline(shape, SIZE)
    val points = ArrayList<Pair<Float, Float>>()
    var x = 0f
    var y = 0f
    for (command in commands) when (command) {
        is PathCommand.MoveTo -> {
            x = command.x; y = command.y; points += x to y
        }
        is PathCommand.LineTo -> {
            x = command.x; y = command.y; points += x to y
        }
        is PathCommand.CubicTo -> {
            for (step in 1..samplesPerSegment) {
                points += cubic(x, y, command, step.toFloat() / samplesPerSegment)
            }
            x = command.x3; y = command.y3
        }
        PathCommand.Close -> Unit
    }
    return points
}

/** FR-16: five shapes, applied as a mask to adaptive layers. */
class IconShapeGeometryTest {

    @Test fun `every shape stays inside the icon box`() {
        for (shape in IconShape.entries) {
            for ((x, y) in sampleOutline(shape)) {
                assertTrue("$shape left the box at ($x, $y)", x in -0.01f..SIZE + 0.01f)
                assertTrue("$shape left the box at ($x, $y)", y in -0.01f..SIZE + 0.01f)
            }
        }
    }

    @Test fun `every shape is a closed outline`() {
        for (shape in IconShape.entries) {
            val commands = IconShapes.outline(shape, SIZE)
            assertTrue("$shape must start with a move", commands.first() is PathCommand.MoveTo)
            assertEquals("$shape must be closed", PathCommand.Close, commands.last())
        }
    }

    // --- The generated curve matches the shape it claims to be -------------

    @Test fun `the fitted curve follows the analytic radius of each polar shape`() {
        val half = SIZE / 2f
        val step = (2.0 * PI / IconShapes.POLAR_SEGMENTS).toFloat()
        for (shape in listOf(IconShape.CIRCLE, IconShape.SQUIRCLE, IconShape.SCALLOP)) {
            val commands = IconShapes.outline(shape, SIZE)
            val cubics = commands.filterIsInstance<PathCommand.CubicTo>()
            assertEquals(IconShapes.POLAR_SEGMENTS, cubics.size)
            var x = (commands.first() as PathCommand.MoveTo).x
            var y = (commands.first() as PathCommand.MoveTo).y
            cubics.forEachIndexed { index, command ->
                // The midpoint of a segment is where a Bézier approximation is least accurate.
                val (mx, my) = cubic(x, y, command, 0.5f)
                val angle = (index + 0.5f) * step
                val expected = IconShapes.polarRadius(shape, angle) * half
                val actual = hypot(mx - half, my - half)
                assertEquals("$shape at segment $index", expected, actual, half * 0.01f)
                x = command.x3; y = command.y3
            }
        }
    }

    @Test fun `a circle is the same distance from the centre all the way round`() {
        val half = SIZE / 2f
        for ((x, y) in sampleOutline(IconShape.CIRCLE, samplesPerSegment = 16)) {
            assertEquals(half, hypot(x - half, y - half), half * 0.01f)
        }
    }

    @Test fun `the squircle is a superellipse, fuller than a circle but not a square`() {
        val half = SIZE / 2f
        // Along the axes it touches the edge exactly, like a circle and like a square.
        assertEquals(1f, IconShapes.polarRadius(IconShape.SQUIRCLE, 0f), 0.001f)
        assertEquals(1f, IconShapes.polarRadius(IconShape.SQUIRCLE, (PI / 2).toFloat()), 0.001f)
        // On the diagonal it reaches further than a circle (1.0) and less far than a square (sqrt 2).
        val diagonal = IconShapes.polarRadius(IconShape.SQUIRCLE, (PI / 4).toFloat())
        assertTrue("a squircle is fuller than a circle", diagonal > 1f)
        assertTrue("a squircle is not a square", diagonal < 1.415f)
        assertEquals(1.189f, diagonal, 0.005f)
        assertTrue(IconShapes.contains(IconShape.SQUIRCLE, SIZE, half + 0.8f * half, half + 0.8f * half))
    }

    @Test fun `the scallop has twelve lobes that never leave the box`() {
        var crossings = 0
        var previous = IconShapes.polarRadius(IconShape.SCALLOP, 0f)
        val steps = 720
        var rising = false
        for (step in 1..steps) {
            val angle = (2.0 * PI * step / steps).toFloat()
            val radius = IconShapes.polarRadius(IconShape.SCALLOP, angle)
            assertTrue("a lobe left the box", radius <= 1.001f)
            val nowRising = radius > previous
            if (nowRising && !rising) crossings++
            rising = nowRising
            previous = radius
        }
        assertEquals(IconShapes.SCALLOP_LOBES, crossings)
    }

    @Test fun `the rounded square keeps the design systems corner proportion`() {
        val radius = SIZE * IconShapes.ROUNDED_SQUARE_RADIUS_RATIO
        // Just inside the corner arc is outside the shape; just inside the straight edge is not.
        assertFalse(IconShapes.contains(IconShape.ROUNDED_SQUARE, SIZE, 1f, 1f))
        assertTrue(IconShapes.contains(IconShape.ROUNDED_SQUARE, SIZE, radius, 1f))
        assertTrue(IconShapes.contains(IconShape.ROUNDED_SQUARE, SIZE, 1f, SIZE / 2f))
        assertEquals(0.2237f, IconShapes.ROUNDED_SQUARE_RADIUS_RATIO, 0.0001f)
    }

    @Test fun `a square fills its box and a circle does not`() {
        assertTrue(IconShapes.contains(IconShape.SQUARE, SIZE, 1f, 1f))
        assertFalse(IconShapes.contains(IconShape.CIRCLE, SIZE, 1f, 1f))
        assertEquals(1f, IconShapes.coverageFraction(IconShape.SQUARE), 0.001f)
        assertEquals((PI / 4).toFloat(), IconShapes.coverageFraction(IconShape.CIRCLE), 0.005f)
    }

    // --- Relationships between the shapes ----------------------------------

    @Test fun `the shapes are ordered from the most to the least generous mask`() {
        val coverage = IconShape.entries.associateWith { IconShapes.coverageFraction(it) }
        val ordered = listOf(
            IconShape.SQUARE, IconShape.ROUNDED_SQUARE, IconShape.SQUIRCLE,
            IconShape.CIRCLE, IconShape.SCALLOP,
        )
        ordered.zipWithNext { bigger, smaller ->
            assertTrue(
                "$bigger (${coverage[bigger]}) must cover more than $smaller (${coverage[smaller]})",
                coverage.getValue(bigger) > coverage.getValue(smaller),
            )
        }
    }

    @Test fun `every shape is symmetric about both axes`() {
        val half = SIZE / 2f
        for (shape in IconShape.entries) {
            for (step in 0..40) {
                val offset = step / 40f * half * 0.98f
                val right = IconShapes.contains(shape, SIZE, half + offset, half)
                val left = IconShapes.contains(shape, SIZE, half - offset, half)
                val down = IconShapes.contains(shape, SIZE, half, half + offset)
                val up = IconShapes.contains(shape, SIZE, half, half - offset)
                assertEquals("$shape is not left-right symmetric", right, left)
                assertEquals("$shape is not up-down symmetric", down, up)
            }
        }
    }

    @Test fun `the centre is inside every shape and the far corner is outside all but the square`() {
        for (shape in IconShape.entries) {
            assertTrue("$shape excludes its own centre", IconShapes.contains(shape, SIZE, SIZE / 2f, SIZE / 2f))
            val corner = IconShapes.contains(shape, SIZE, SIZE - 0.5f, SIZE - 0.5f)
            assertEquals("$shape corner", shape == IconShape.SQUARE, corner)
        }
    }

    @Test fun `a point outside the box is never inside a shape`() {
        for (shape in IconShape.entries) {
            assertFalse(IconShapes.contains(shape, SIZE, -1f, SIZE / 2f))
            assertFalse(IconShapes.contains(shape, SIZE, SIZE + 1f, SIZE / 2f))
            assertFalse(IconShapes.contains(shape, SIZE, SIZE / 2f, -1f))
        }
    }

    @Test fun `outlines scale with the icon size`() {
        for (shape in IconShape.entries) {
            val small = IconShapes.outline(shape, 64)
            val large = IconShapes.outline(shape, 128)
            assertEquals(small.size, large.size)
            val smallMove = small.first() as PathCommand.MoveTo
            val largeMove = large.first() as PathCommand.MoveTo
            assertEquals(smallMove.x * 2f, largeMove.x, 0.01f)
            assertEquals(smallMove.y * 2f, largeMove.y, 0.01f)
        }
    }

    @Test fun `a zero or negative size is refused rather than drawn`() {
        for (size in listOf(0, -1)) {
            val failed = runCatching { IconShapes.outline(IconShape.SQUIRCLE, size) }.isFailure
            assertTrue("size $size must be refused", failed)
        }
    }

    @Test fun `consecutive segments join without a gap`() {
        for (shape in listOf(IconShape.CIRCLE, IconShape.SQUIRCLE, IconShape.SCALLOP)) {
            val cubics = IconShapes.outline(shape, SIZE).filterIsInstance<PathCommand.CubicTo>()
            cubics.zipWithNext { first, second ->
                assertTrue(
                    "$shape has a gap between segments",
                    abs(first.x3 - startOf(second, cubics, first)) < 0.001f,
                )
            }
        }
    }

    /** The start of a segment is the previous segment's end, by construction. */
    private fun startOf(
        second: PathCommand.CubicTo,
        cubics: List<PathCommand.CubicTo>,
        first: PathCommand.CubicTo,
    ): Float {
        val index = cubics.indexOf(second)
        return if (index <= 0) first.x3 else cubics[index - 1].x3
    }
}
