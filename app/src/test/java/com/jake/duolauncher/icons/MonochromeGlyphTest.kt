package com.jake.duolauncher.icons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val SIDE = 16
private const val PIXELS = SIDE * SIDE

private const val TRANSPARENT = 0x00000000
private const val OPAQUE_BLACK = 0xFF000000.toInt()
private const val OPAQUE_WHITE = 0xFFFFFFFF.toInt()
private const val OPAQUE_BLUE = 0xFF1040A0.toInt()

/** Builds a layer, filling it with [background] and stamping a [mark] square of [markSide] in the middle. */
private fun layer(background: Int, mark: Int, markSide: Int): IntArray {
    val pixels = IntArray(PIXELS) { background }
    val start = (SIDE - markSide) / 2
    for (row in start until start + markSide) {
        for (column in start until start + markSide) {
            pixels[row * SIDE + column] = mark
        }
    }
    return pixels
}

private fun indexOfCentre() = (SIDE / 2) * SIDE + (SIDE / 2)

/** FR-15: a monochrome glyph generated from foreground luminance when an app ships no monochrome layer. */
class MonochromeGlyphTest {

    // --- Alpha-defined artwork: the silhouette is the glyph -----------------

    @Test fun `a mark on a transparent field becomes its own silhouette`() {
        val pixels = layer(background = TRANSPARENT, mark = OPAQUE_BLACK, markSide = 8)
        val coverage = requireNotNull(MonochromeGlyph.coverage(pixels, SIDE, SIDE))
        assertEquals(PIXELS, coverage.size)
        assertEquals("the mark is fully inked", 1f, coverage[indexOfCentre()], 0.01f)
        assertEquals("the transparent field is empty", 0f, coverage[0], 0.001f)
        assertEquals(64f / PIXELS, MonochromeGlyph.inkFraction(coverage), 0.01f)
    }

    @Test fun `a light mark on a transparent field inks exactly the same silhouette as a dark one`() {
        // A themed icon is a silhouette: the artwork's own colour must not change its shape.
        val dark = MonochromeGlyph.coverage(layer(TRANSPARENT, OPAQUE_BLACK, 8), SIDE, SIDE)!!
        val light = MonochromeGlyph.coverage(layer(TRANSPARENT, OPAQUE_WHITE, 8), SIDE, SIDE)!!
        assertTrue("silhouettes must match whatever the artwork's colour", coverageMatches(dark, light))
    }

    @Test fun `a partly transparent edge stays partly inked`() {
        val pixels = IntArray(PIXELS) { TRANSPARENT }
        pixels[indexOfCentre()] = OPAQUE_BLACK
        pixels[indexOfCentre() + 1] = 0x80000000.toInt()
        val coverage = MonochromeGlyph.coverage(pixels, SIDE, SIDE)!!
        assertTrue("the anti-aliased edge must survive", coverage[indexOfCentre() + 1] > 0f)
        assertTrue(coverage[indexOfCentre() + 1] < coverage[indexOfCentre()])
    }

    // --- Full-bleed artwork: luminance separates mark from field ------------

    @Test fun `a light wordmark is picked out of a solid coloured tile`() {
        val pixels = layer(background = OPAQUE_BLUE, mark = OPAQUE_WHITE, markSide = 4)
        val coverage = MonochromeGlyph.coverage(pixels, SIDE, SIDE)!!
        assertEquals("the mark is the glyph", 1f, coverage[indexOfCentre()], 0.01f)
        assertEquals("the field is not", 0f, coverage[0], 0.001f)
        assertEquals(16f / PIXELS, MonochromeGlyph.inkFraction(coverage), 0.01f)
    }

    @Test fun `a dark wordmark is picked out of a light tile`() {
        val pixels = layer(background = OPAQUE_WHITE, mark = OPAQUE_BLACK, markSide = 4)
        val coverage = MonochromeGlyph.coverage(pixels, SIDE, SIDE)!!
        assertEquals(1f, coverage[indexOfCentre()], 0.01f)
        assertEquals(0f, coverage[0], 0.001f)
    }

    @Test fun `the minority of a full-bleed layer is the mark, not the field`() {
        // Three quarters white, one quarter black: the black quarter is the mark.
        val pixels = IntArray(PIXELS) { OPAQUE_WHITE }
        for (index in 0 until PIXELS / 4) pixels[index] = OPAQUE_BLACK
        val coverage = MonochromeGlyph.coverage(pixels, SIDE, SIDE)!!
        assertTrue(coverage[0] > 0.5f)
        assertEquals(0f, coverage[PIXELS - 1], 0.001f)
    }

    // --- The cases with no glyph in them ------------------------------------

    @Test fun `a fully transparent layer produces no glyph`() {
        assertNull(MonochromeGlyph.coverage(IntArray(PIXELS) { TRANSPARENT }, SIDE, SIDE))
    }

    @Test fun `a flat coloured layer with no mark produces no glyph`() {
        // Nothing to draw: the renderer falls back to the app's own icon rather than an empty tile.
        assertNull(MonochromeGlyph.coverage(IntArray(PIXELS) { OPAQUE_BLUE }, SIDE, SIDE))
        assertNull(MonochromeGlyph.coverage(IntArray(PIXELS) { OPAQUE_WHITE }, SIDE, SIDE))
    }

    @Test fun `a mark covering almost the whole full-bleed layer is a field, not a mark`() {
        val pixels = IntArray(PIXELS) { OPAQUE_WHITE }
        // 99% one tone, 1% another: neither side is a plausible glyph at this ratio.
        for (index in 0 until 2) pixels[index] = OPAQUE_BLACK
        val coverage = MonochromeGlyph.coverage(pixels, SIDE, SIDE)
        if (coverage != null) assertTrue(MonochromeGlyph.inkFraction(coverage) < 0.72f)
    }

    @Test fun `a malformed buffer is refused rather than read out of bounds`() {
        assertNull(MonochromeGlyph.coverage(IntArray(10), SIDE, SIDE))
        assertNull(MonochromeGlyph.coverage(IntArray(0), 0, 0))
        assertNull(MonochromeGlyph.coverage(IntArray(PIXELS), -1, SIDE))
    }

    // --- Contrast normalization ---------------------------------------------

    @Test fun `a shallow tonal range is stretched to the full scale`() {
        // Without the stretch this icon would fill with a flat grey smudge.
        val pixels = IntArray(PIXELS) { 0xFF808080.toInt() }
        for (index in 0 until 32) pixels[index] = 0xFF8A8A8A.toInt()
        val coverage = MonochromeGlyph.coverage(pixels, SIDE, SIDE)
        if (coverage != null) {
            val inked = coverage.filter { it > 0f }
            assertTrue("the glyph must reach full strength", inked.max() > 0.95f)
        }
    }

    @Test fun `smoothstep eases between nought and one`() {
        assertEquals(0f, MonochromeGlyph.smoothstep(0f), 0.001f)
        assertEquals(1f, MonochromeGlyph.smoothstep(1f), 0.001f)
        assertEquals(0.5f, MonochromeGlyph.smoothstep(0.5f), 0.001f)
        assertTrue(MonochromeGlyph.smoothstep(0.25f) < 0.25f)
        assertTrue(MonochromeGlyph.smoothstep(0.75f) > 0.75f)
    }

    // --- Luminance -----------------------------------------------------------

    @Test fun `relative luminance orders the greys and weights the channels`() {
        assertEquals(0f, MonochromeGlyph.relativeLuminance(OPAQUE_BLACK), 0.001f)
        assertEquals(1f, MonochromeGlyph.relativeLuminance(OPAQUE_WHITE), 0.001f)
        assertTrue(
            MonochromeGlyph.relativeLuminance(0xFF00FF00.toInt()) >
                MonochromeGlyph.relativeLuminance(0xFFFF0000.toInt()),
        )
        assertTrue(
            MonochromeGlyph.relativeLuminance(0xFFFF0000.toInt()) >
                MonochromeGlyph.relativeLuminance(0xFF0000FF.toInt()),
        )
    }

    @Test fun `luminance ignores alpha so a faint pixel is not mistaken for a dark one`() {
        assertEquals(
            MonochromeGlyph.relativeLuminance(OPAQUE_WHITE),
            MonochromeGlyph.relativeLuminance(0x11FFFFFF),
            0.001f,
        )
    }

    @Test fun `mean luminance is weighted by alpha and ignores empty pixels`() {
        val pixels = IntArray(PIXELS) { TRANSPARENT }
        pixels[0] = OPAQUE_WHITE
        assertEquals(1f, MonochromeGlyph.meanLuminance(pixels), 0.001f)
        assertEquals(0f, MonochromeGlyph.meanLuminance(IntArray(PIXELS) { TRANSPARENT }), 0.001f)
    }

    // --- Tint intensity (FR-14) ---------------------------------------------

    @Test fun `tint intensity scales coverage and is clamped to nought to one hundred`() {
        assertEquals(1f, tintedAlpha(1f, 100), 0.001f)
        assertEquals(0.5f, tintedAlpha(1f, 50), 0.001f)
        assertEquals(0f, tintedAlpha(1f, 0), 0.001f)
        assertEquals(1f, tintedAlpha(1f, 500), 0.001f)
        assertEquals(0f, tintedAlpha(1f, -20), 0.001f)
        assertEquals(0.25f, tintedAlpha(0.5f, 50), 0.001f)
    }
}
