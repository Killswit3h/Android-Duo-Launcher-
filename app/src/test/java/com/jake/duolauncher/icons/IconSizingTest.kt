package com.jake.duolauncher.icons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** FR-12: icons are rasterized at the device's actual pixel size, and keyed by everything that changes them. */
class IconSizingTest {

    // --- Size from dp and density (FR-12, FR-17) ---------------------------

    @Test fun `an icon is rasterized at its displayed dp times the display density`() {
        // The case AC-10 names: 68dp on an xxxhdpi (density 4) display is 272 real pixels, which
        // the previous fixed 144px rasterization had to upscale.
        assertEquals(272, iconSizePx(68f, 4f))
        assertEquals(180, iconSizePx(60f, 3f))
        assertEquals(120, iconSizePx(60f, 2f))
    }

    @Test fun `a fractional density rounds to the nearest whole pixel`() {
        assertEquals(165, iconSizePx(60f, 2.75f))
        assertEquals(158, iconSizePx(60f, 2.625f))
    }

    @Test fun `large icons are twenty percent bigger at the same dp size`() {
        val normal = iconSizePx(60f, 3f)
        val large = iconSizePx(60f, 3f, large = true)
        assertEquals(180, normal)
        assertEquals(216, large)
        assertEquals(LARGE_ICON_SCALE, large.toFloat() / normal, 0.01f)
    }

    @Test fun `sizes are clamped to a sane range and degenerate input never crashes`() {
        assertEquals(MAX_ICON_PX, iconSizePx(400f, 4f))
        assertEquals(MIN_ICON_PX, iconSizePx(1f, 1f))
        assertEquals(MIN_ICON_PX, iconSizePx(0f, 3f))
        assertEquals(MIN_ICON_PX, iconSizePx(60f, 0f))
        assertEquals(MIN_ICON_PX, iconSizePx(Float.NaN, 3f))
        assertEquals(MIN_ICON_PX, iconSizePx(60f, Float.POSITIVE_INFINITY))
    }

    @Test fun `a rendered icon costs four bytes a pixel`() {
        assertEquals(272L * 272L * 4L, iconBytes(272))
        assertEquals(0L, iconBytes(0))
    }

    // --- Cache budget (NFR-P4) ---------------------------------------------

    @Test fun `the cache budget scales with the device heap but never exceeds the spec ceiling`() {
        assertEquals(MAX_ICON_CACHE_BYTES, iconCacheBudgetBytes(1024L * 1024L * 1024L))
        assertEquals(MIN_ICON_CACHE_BYTES, iconCacheBudgetBytes(8L * 1024L * 1024L))
        assertEquals(32L * 1024L * 1024L, iconCacheBudgetBytes(256L * 1024L * 1024L))
        assertTrue(iconCacheBudgetBytes(Long.MAX_VALUE) <= MAX_ICON_CACHE_BYTES)
    }

    // --- Cache key identity (FR-12) ----------------------------------------

    @Test fun `a key carries the user, the package, the activity, the size and the style`() {
        val key = iconKeyFor("com.example.app/.Main", 144, IconStyle())
        assertEquals(PERSONAL_USER_SERIAL, key!!.userSerial)
        assertEquals("com.example.app", key.packageName)
        // The ComponentName short form expands against its own package.
        assertEquals("com.example.app.Main", key.activity)
        assertEquals(144, key.sizePx)
    }

    @Test fun `a work profile app and its personal twin are different keys`() {
        val personal = iconKeyFor("com.example.app/.Main", 144, IconStyle())
        val work = iconKeyFor("duo-profile:v1:42:com.example.app/.Main", 144, IconStyle())
        assertEquals(42L, work!!.userSerial)
        assertNotEquals(personal, work)
    }

    @Test fun `the same app at two sizes or two styles is two entries`() {
        val base = iconKeyFor("com.example.app/.Main", 144, IconStyle())
        assertNotEquals(base, iconKeyFor("com.example.app/.Main", 288, IconStyle()))
        assertNotEquals(base, iconKeyFor("com.example.app/.Main", 144, IconStyle(shape = IconShape.CIRCLE)))
        assertNotEquals(base, iconKeyFor("com.example.app/.Main", 144, IconStyle(appearance = IconAppearance.DARK)))
        assertNotEquals(base, iconKeyFor("com.example.app/.Main", 144, IconStyle(pack = "com.pack")))
        assertNotEquals(base, iconKeyFor("com.example.app/.Main", 144, IconStyle(dark = true)))
    }

    @Test fun `an unusable identity or size produces no key rather than a broken entry`() {
        assertNull(iconKeyFor("", 144, IconStyle()))
        assertNull(iconKeyFor("no-slash-here", 144, IconStyle()))
        assertNull(iconKeyFor("/leading", 144, IconStyle()))
        assertNull(iconKeyFor("trailing/", 144, IconStyle()))
        assertNull(iconKeyFor("com.example.app/.Main", 0, IconStyle()))
    }

    // --- Style normalization ------------------------------------------------

    @Test fun `styles that render the same icon share a cache entry`() {
        // Only Tinted reads the tint and its intensity, so two Clear styles that differ only in
        // tint must not render, or cache, twice.
        val a = IconStyle(appearance = IconAppearance.CLEAR, tint = 0xFF0000, tintIntensity = 20)
        val b = IconStyle(appearance = IconAppearance.CLEAR, tint = 0x00FF00, tintIntensity = 90)
        assertEquals(a.normalized(), b.normalized())
        assertEquals(iconKeyFor("a/.B", 96, a), iconKeyFor("a/.B", 96, b))
    }

    @Test fun `tinted keeps its tint and clamps its intensity`() {
        val tinted = IconStyle(appearance = IconAppearance.TINTED, tint = 0xFF0000, tintIntensity = 250)
        assertEquals(0xFF0000, tinted.normalized().tint)
        assertEquals(IconStyle.MAX_TINT_INTENSITY, tinted.normalized().tintIntensity)
        assertEquals(0, tinted.copy(tintIntensity = -10).normalized().tintIntensity)
    }

    @Test fun `a blank pack name is the same as no pack`() {
        assertNull(IconStyle(pack = "   ").normalized().pack)
        assertEquals(iconKeyFor("a/.B", 96, IconStyle(pack = "")), iconKeyFor("a/.B", 96, IconStyle(pack = null)))
    }

    @Test fun `only clear and tinted need a monochrome glyph`() {
        assertTrue(IconAppearance.CLEAR.isMonochrome)
        assertTrue(IconAppearance.TINTED.isMonochrome)
        assertTrue(!IconAppearance.DEFAULT.isMonochrome)
        assertTrue(!IconAppearance.DARK.isMonochrome)
    }
}
