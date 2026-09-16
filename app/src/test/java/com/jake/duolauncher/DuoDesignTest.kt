package com.jake.duolauncher

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.jake.duolauncher.design.ConcentricRectangle
import com.jake.duolauncher.design.DuoAccent
import com.jake.duolauncher.design.DuoAccentPreset
import com.jake.duolauncher.design.DuoTokens
import com.jake.duolauncher.design.GlassLevel
import com.jake.duolauncher.design.accentForHue
import com.jake.duolauncher.design.concentricRadius
import com.jake.duolauncher.design.glassDimAlpha
import com.jake.duolauncher.design.glassFillAlpha
import com.jake.duolauncher.design.glassRimAlpha
import com.jake.duolauncher.design.hueOf
import com.jake.duolauncher.design.isBrightBackdrop
import com.jake.duolauncher.design.motionEnabled
import com.jake.duolauncher.design.resolveAccent
import com.jake.duolauncher.design.springStiffness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DuoDesignTest {

    // --- Concentric corners (FR-1) -----------------------------------------

    @Test fun `a child nested inside a corner keeps its curve parallel to the parent`() {
        assertEquals(12.dp, concentricRadius(28.dp, 16.dp))
        assertEquals(6.dp, concentricRadius(22.dp, 16.dp))
    }

    @Test fun `a concentric child never inverts when the padding exceeds the parent radius`() {
        assertEquals(0.dp, concentricRadius(12.dp, 20.dp))
        assertEquals(4.dp, concentricRadius(12.dp, 20.dp, minimum = 4.dp))
    }

    @Test fun `the concentric shape helper and the minus operator agree`() {
        val parent = DuoTokens.radius.sheet
        val padding = DuoTokens.space.lg
        assertEquals(ConcentricRectangle(parent, padding).dp, (parent - padding).dp)
        assertEquals(concentricRadius(parent.dp, padding, DuoTokens.radius.minimum), (parent - padding).dp)
    }

    @Test fun `icon corner radius keeps the squircle proportion as the icon grows`() {
        val small = DuoTokens.radius.iconRadiusFor(48.dp).dp.value
        val large = DuoTokens.radius.iconRadiusFor(96.dp).dp.value
        assertEquals(2f, large / small, 0.001f)
    }

    // --- Spacing scale (FR-1) ----------------------------------------------

    @Test fun `the spacing scale is the documented eight steps and strictly increases`() {
        val steps = DuoTokens.space.steps.map { it.value }
        assertEquals(listOf(2f, 4f, 8f, 12f, 16f, 20f, 24f, 32f), steps)
        assertEquals(steps.sorted(), steps)
        assertEquals(steps.distinct().size, steps.size)
    }

    // --- Motion (FR-10, FR-11) ---------------------------------------------

    @Test fun `spring stiffness is the square of the angular frequency for the response`() {
        // response = 2*PI/sqrt(stiffness), so a 0.5s response is ~157.9 and 0.35s is ~322.2.
        assertEquals(157.9f, springStiffness(0.5f), 0.1f)
        assertEquals(322.2f, springStiffness(0.35f), 0.1f)
    }

    @Test fun `a shorter response always produces a stiffer spring`() {
        var previous = Float.MAX_VALUE
        for (response in listOf(0.35f, 0.40f, 0.45f, 0.50f)) {
            val stiffness = springStiffness(response)
            assertTrue("stiffness must fall as response grows", stiffness < previous)
            previous = stiffness
        }
    }

    @Test fun `every motion spec sits inside the damping and response range the spec requires`() {
        val motion = DuoTokens.motion
        val specs = listOf(
            motion.snappyDamping to motion.snappyResponse,
            motion.standardDamping to motion.standardResponse,
            motion.gentleDamping to motion.gentleResponse,
        )
        for ((damping, response) in specs) {
            assertTrue("damping $damping outside 0.8..0.9", damping in 0.8f..0.9f)
            assertTrue("response $response outside 0.35..0.5", response in 0.35f..0.5f)
        }
        assertEquals(0.9f, motion.pressedScale, 0.0001f)
    }

    @Test fun `motion is disabled only when the animator duration scale is zero`() {
        assertFalse(motionEnabled(0f))
        assertTrue(motionEnabled(0.5f))
        assertTrue(motionEnabled(1f))
    }

    // --- Glass curves (FR-5, FR-6, FR-7) -----------------------------------

    @Test fun `glass fill runs from near-clear at zero to a solid tint at one hundred`() {
        for (level in GlassLevel.entries) {
            assertEquals(level.name, 1f, glassFillAlpha(level, 100, reduceTransparency = false), 0.0001f)
            val clear = glassFillAlpha(level, 0, reduceTransparency = false)
            assertTrue("${level.name} should be near clear at 0, was $clear", clear < 0.2f)
        }
    }

    @Test fun `glass fill increases continuously across the slider with no jumps`() {
        for (level in GlassLevel.entries) {
            var previous = -1f
            for (glass in 0..100) {
                val alpha = glassFillAlpha(level, glass, reduceTransparency = false)
                assertTrue("${level.name} at $glass went backwards", alpha > previous)
                assertTrue("${level.name} at $glass left 0..1", alpha in 0f..1f)
                previous = alpha
            }
        }
    }

    @Test fun `the slider is clamped so out-of-range values cannot produce invalid alpha`() {
        assertEquals(1f, glassFillAlpha(GlassLevel.PANEL, 250, reduceTransparency = false), 0.0001f)
        assertEquals(
            glassFillAlpha(GlassLevel.PANEL, 0, reduceTransparency = false),
            glassFillAlpha(GlassLevel.PANEL, -40, reduceTransparency = false),
            0.0001f,
        )
    }

    @Test fun `reduce transparency makes every surface opaque at every slider value`() {
        for (level in GlassLevel.entries) {
            for (glass in listOf(0, 30, 50, 100)) {
                assertEquals(1f, glassFillAlpha(level, glass, reduceTransparency = true), 0.0001f)
            }
        }
    }

    @Test fun `the dim layer only engages below glass thirty and only over bright wallpaper`() {
        assertEquals(0f, glassDimAlpha(0, brightBackdrop = false), 0.0001f)
        assertEquals(0f, glassDimAlpha(30, brightBackdrop = true), 0.0001f)
        assertEquals(0f, glassDimAlpha(100, brightBackdrop = true), 0.0001f)
        assertTrue(glassDimAlpha(29, brightBackdrop = true) > 0f)
    }

    @Test fun `the dim layer never exceeds thirty-five percent and eases off as glass rises`() {
        assertEquals(0.35f, glassDimAlpha(0, brightBackdrop = true), 0.0001f)
        var previous = Float.MAX_VALUE
        for (glass in 0..30) {
            val dim = glassDimAlpha(glass, brightBackdrop = true)
            assertTrue("dim $dim exceeded the 35% ceiling", dim <= 0.35f)
            assertTrue("dim must not rise with the slider", dim < previous)
            previous = dim
        }
    }

    @Test fun `the specular rim stays visible at every slider value`() {
        for (level in GlassLevel.entries) {
            for (glass in listOf(0, 50, 100)) {
                val rim = glassRimAlpha(level, glass)
                assertTrue("${level.name} rim vanished at $glass", rim > 0f)
                assertTrue("${level.name} rim out of range at $glass", rim <= 1f)
            }
            assertTrue(glassRimAlpha(level, 0) > glassRimAlpha(level, 100))
        }
    }

    // --- Accent resolution (FR-8) ------------------------------------------

    @Test fun `a chosen preset wins over dynamic color and the wallpaper`() {
        val accent = resolveAccent(
            accent = DuoAccent.Preset(DuoAccentPreset.GREEN),
            dark = false,
            dynamic = Color.Red,
            wallpaperSeed = Color.Blue,
        )
        assertEquals(DuoAccentPreset.GREEN.color(dark = false), accent)
    }

    @Test fun `every preset offers a distinct color in both appearances`() {
        assertEquals(8, DuoAccentPreset.entries.size)
        for (dark in listOf(false, true)) {
            val colors = DuoAccentPreset.entries.map { it.color(dark) }
            assertEquals("presets collide when dark=$dark", colors.distinct().size, colors.size)
        }
        for (preset in DuoAccentPreset.entries) {
            assertTrue(preset.color(dark = true) != preset.color(dark = false))
        }
    }

    @Test fun `automatic accent prefers dynamic color, then the wallpaper, then blue`() {
        assertEquals(
            Color.Red,
            resolveAccent(DuoAccent.Auto, dark = false, dynamic = Color.Red, wallpaperSeed = Color.Blue),
        )
        // Without dynamic color the wallpaper's hue drives the accent.
        val fromWallpaper = resolveAccent(DuoAccent.Auto, dark = false, dynamic = null, wallpaperSeed = Color.Blue)
        assertEquals(hueOf(Color.Blue), hueOf(fromWallpaper), 0.5f)
        // With neither, a sensible preset rather than a blank color.
        assertEquals(
            DuoAccentPreset.BLUE.color(dark = false),
            resolveAccent(DuoAccent.Auto, dark = false, dynamic = null, wallpaperSeed = null),
        )
    }

    @Test fun `a custom hue keeps that hue and adapts lightness to the appearance`() {
        val hue = 285f
        val light = resolveAccent(DuoAccent.CustomHue(hue), dark = false, dynamic = null, wallpaperSeed = null)
        val dark = resolveAccent(DuoAccent.CustomHue(hue), dark = true, dynamic = null, wallpaperSeed = null)
        assertEquals(hue, hueOf(light), 0.5f)
        assertEquals(hue, hueOf(dark), 0.5f)
        assertTrue("dark-mode accent must be lighter", isBrightBackdrop(dark) || luminanceOrder(dark, light))
    }

    @Test fun `hues outside zero to three-sixty wrap instead of clamping`() {
        assertEquals(hueOf(accentForHue(30f, dark = false)), hueOf(accentForHue(390f, dark = false)), 0.5f)
        assertEquals(hueOf(accentForHue(330f, dark = false)), hueOf(accentForHue(-30f, dark = false)), 0.5f)
    }

    @Test fun `hue extraction matches the primaries and treats grey as hueless`() {
        assertEquals(0f, hueOf(Color.Red), 0.5f)
        assertEquals(120f, hueOf(Color.Green), 0.5f)
        assertEquals(240f, hueOf(Color.Blue), 0.5f)
        assertEquals(0f, hueOf(Color.Gray), 0.0001f)
        assertEquals(0f, hueOf(Color.White), 0.0001f)
    }

    // --- Backdrop brightness (FR-7, NFR-A2) --------------------------------

    @Test fun `a white wallpaper counts as bright and a dark one does not`() {
        assertTrue(isBrightBackdrop(Color.White))
        assertFalse(isBrightBackdrop(Color.Black))
        assertFalse(isBrightBackdrop(DuoAccentPreset.BLUE.color(dark = false)))
    }

    private fun luminanceOrder(brighter: Color, dimmer: Color): Boolean =
        (brighter.red + brighter.green + brighter.blue) > (dimmer.red + dimmer.green + dimmer.blue)
}
