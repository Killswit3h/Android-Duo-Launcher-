package com.jake.duolauncher.home

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import com.jake.duolauncher.badges.BadgeCount
import com.jake.duolauncher.icons.LARGE_ICON_SCALE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeAppearanceTest {

    // --- Large icons (FR-17) -----------------------------------------------

    @Test fun `large icons hide labels and grow the icon by twenty percent`() {
        val normal = HomeAppearance(labels = true, largeIcons = false)
        val large = normal.copy(largeIcons = true)
        assertTrue(normal.showLabels)
        assertFalse("FR-17 hides labels while Large icons is on", large.showLabels)
        assertEquals(66f, normal.iconSizeDp(66f), 0.001f)
        assertEquals(66f * LARGE_ICON_SCALE, large.iconSizeDp(66f), 0.001f)
        assertEquals(1.2f, LARGE_ICON_SCALE, 0.0001f)
    }

    @Test fun `the labels switch still hides labels when icons are not large`() {
        assertFalse(HomeAppearance(labels = false).showLabels)
        assertFalse(HomeAppearance(labels = false, largeIcons = true).showLabels)
    }

    // --- Badges (FR-20, FR-22) ---------------------------------------------

    @Test fun `a number badge shows the count and caps instead of growing without limit`() {
        assertEquals("1", badgeText(1))
        assertEquals("42", badgeText(42))
        assertEquals("999", badgeText(MAX_SHOWN_BADGE))
        assertEquals("999+", badgeText(MAX_SHOWN_BADGE + 1))
        assertEquals("999+", badgeText(BadgeCount.MAX))
    }

    @Test fun `no badge text exists for an app with nothing to report`() {
        assertNull(badgeText(0))
        assertNull(badgeText(-3))
        assertNull(badgeDescription(0))
    }

    @Test fun `badge descriptions are singular or plural for TalkBack`() {
        assertEquals("1 notification", badgeDescription(1))
        assertEquals("5 notifications", badgeDescription(5))
    }

    @Test fun `badges disappear when the badge setting is off, whatever the count says`() {
        assertTrue(showsBadge(enabled = true, count = BadgeCount(3)))
        assertFalse(showsBadge(enabled = false, count = BadgeCount(3)))
        assertFalse(showsBadge(enabled = true, count = BadgeCount.None))
    }

    // --- Context menu rows (FR-25, FR-29, FR-30) ---------------------------

    @Test fun `the menu always offers app info and never invents unavailable actions`() {
        val actions = contextMenuActions(ContextMenuCapabilities())
        assertTrue(ContextMenuAction.APP_INFO in actions)
        assertFalse("Uninstall is never shown for a system app", ContextMenuAction.UNINSTALL in actions)
        assertFalse(ContextMenuAction.HIDE_APP in actions)
        assertFalse(ContextMenuAction.EDIT_ICON in actions)
        assertFalse(ContextMenuAction.EDIT_HOME in actions)
    }

    @Test fun `remove from home appears only for a placed app, and add to home only otherwise`() {
        val placed = contextMenuActions(ContextMenuCapabilities(placed = true))
        assertTrue(ContextMenuAction.REMOVE_FROM_HOME in placed)
        assertFalse(ContextMenuAction.ADD_TO_HOME in placed)

        val loose = contextMenuActions(ContextMenuCapabilities(placed = false))
        assertTrue(ContextMenuAction.ADD_TO_HOME in loose)
        assertFalse(ContextMenuAction.REMOVE_FROM_HOME in loose)
    }

    @Test fun `every capability that is granted contributes exactly one row`() {
        val actions = contextMenuActions(
            ContextMenuCapabilities(
                placed = true,
                canEditHome = true,
                canEditIcon = true,
                canCreateFolder = true,
                hasWidgets = true,
                canHide = true,
                canUninstall = true,
            ),
        )
        assertEquals(actions.distinct().size, actions.size)
        assertEquals(ContextMenuAction.entries.size - 1, actions.size) // Add and Remove are exclusive.
        assertTrue(ContextMenuAction.UNINSTALL in actions)
    }

    @Test fun `destructive actions sort last so a mis-tap does not land on them`() {
        val actions = contextMenuActions(
            ContextMenuCapabilities(placed = true, canEditHome = true, canUninstall = true),
        )
        assertEquals(ContextMenuAction.UNINSTALL, actions.last())
        assertTrue(isDestructive(ContextMenuAction.UNINSTALL))
        assertTrue(isDestructive(ContextMenuAction.REMOVE_FROM_HOME))
        assertFalse(isDestructive(ContextMenuAction.APP_INFO))
        val firstDestructive = actions.indexOfFirst(::isDestructive)
        assertTrue(actions.drop(firstDestructive).all(::isDestructive))
    }

    // --- Menu placement (FR-24) --------------------------------------------

    private val container = IntSize(1000, 2000)
    private val menu = IntSize(248, 400)

    @Test fun `the menu hangs below the icon when there is room`() {
        val anchor = Rect(400f, 300f, 500f, 400f)
        val offset = contextMenuOffset(anchor, menu, container, gap = 8f, margin = 16f)
        assertEquals(408, offset.y)
        assertEquals((anchor.center.x - menu.width / 2f).toInt(), offset.x)
    }

    @Test fun `the menu flips above an icon near the bottom rather than running off screen`() {
        val anchor = Rect(400f, 1800f, 500f, 1900f)
        val offset = contextMenuOffset(anchor, menu, container, gap = 8f, margin = 16f)
        assertEquals(1800f - 8f - 400f, offset.y.toFloat(), 1f)
        assertTrue("the menu must stay on screen", offset.y + menu.height <= container.height)
    }

    @Test fun `a menu is clamped inside the screen for an icon in either corner`() {
        val left = contextMenuOffset(Rect(0f, 300f, 60f, 360f), menu, container, 8f, 16f)
        assertTrue("never off the left edge", left.x >= 16)

        val right = contextMenuOffset(Rect(940f, 300f, 1000f, 360f), menu, container, 8f, 16f)
        assertTrue("never off the right edge", right.x + menu.width <= container.width - 16)
    }

    @Test fun `a menu taller than the screen is centred instead of being pushed off either edge`() {
        val tall = IntSize(248, 1990)
        val offset = contextMenuOffset(Rect(400f, 900f, 500f, 1000f), tall, container, 8f, 16f)
        assertTrue(offset.y >= 0)
        assertTrue(offset.y <= container.height)
    }
}
