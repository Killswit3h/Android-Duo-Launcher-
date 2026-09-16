package com.jake.duolauncher.home

import com.jake.duolauncher.DuoBadgeStyle
import com.jake.duolauncher.DuoSettings
import com.jake.duolauncher.LauncherState
import com.jake.duolauncher.icons.IconAppearance
import com.jake.duolauncher.icons.IconShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The persisted settings reaching the Home surfaces (FR-5, FR-6, FR-14 to FR-17, FR-20).
 *
 * Until `homeAppearanceOf` existed, `LauncherScreen` rendered `HomeAppearance` defaults whatever the
 * user chose, so every one of these settings moved in Duo Settings and changed nothing on screen.
 */
class HomeAppearanceOfTest {

    private fun appearanceFor(settings: DuoSettings, labels: Boolean = true) =
        homeAppearanceOf(LauncherState(settings = settings, labels = labels))

    @Test fun `badge style Off turns badges off`() {
        // The stored style carries the off switch as a third case. Mapping it to Dot left the
        // toggle inert.
        assertFalse(appearanceFor(DuoSettings(badgeStyle = DuoBadgeStyle.OFF)).badgesEnabled)
    }

    @Test fun `badge style Dot and Number each keep badges on in that style`() {
        val dot = appearanceFor(DuoSettings(badgeStyle = DuoBadgeStyle.DOT))
        assertTrue(dot.badgesEnabled)
        assertEquals(BadgeStyle.DOT, dot.badgeStyle)

        val number = appearanceFor(DuoSettings(badgeStyle = DuoBadgeStyle.NUMBER))
        assertTrue(number.badgesEnabled)
        assertEquals(BadgeStyle.NUMBER, number.badgeStyle)
    }

    @Test fun `the glass slider and reduce transparency reach Home`() {
        val appearance = appearanceFor(DuoSettings(glassLevel = 12, reduceTransparency = true))
        assertEquals(12, appearance.glass)
        assertTrue(appearance.reduceTransparency)
    }

    @Test fun `icon appearance, shape, tint and pack reach the icon style`() {
        val style = appearanceFor(
            DuoSettings(
                iconAppearance = IconAppearance.TINTED,
                iconShape = IconShape.CIRCLE,
                iconTint = 0x336699,
                iconTintIntensity = 40,
                iconPack = "com.example.pack",
            ),
        ).iconStyle
        assertEquals(IconAppearance.TINTED, style.appearance)
        assertEquals(IconShape.CIRCLE, style.shape)
        assertEquals(0x336699, style.tint)
        assertEquals(40, style.tintIntensity)
        assertEquals("com.example.pack", style.pack)
    }

    @Test fun `large icons and the labels switch both reach Home`() {
        val appearance = appearanceFor(DuoSettings(largeIcons = true), labels = true)
        assertTrue(appearance.largeIcons)
        // FR-17: large icons hide labels even with the labels switch on.
        assertFalse(appearance.showLabels)

        assertFalse(appearanceFor(DuoSettings(), labels = false).labels)
    }
}
