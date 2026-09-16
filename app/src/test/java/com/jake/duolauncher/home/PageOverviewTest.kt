package com.jake.duolauncher.home

import com.jake.duolauncher.GridSpec
import com.jake.duolauncher.HomeLayout
import com.jake.duolauncher.WidgetPlacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Page overview's rules (FR-47): what each page contains, reordering, hiding and showing, and
 * which pages may be deleted. All pure, because the overview is only a view of them.
 */
class PageOverviewTest {

    private fun layout(
        grid: GridSpec = GridSpec(4, 6),
        slots: List<String?> = emptyList(),
        widgets: List<WidgetPlacement> = emptyList(),
    ) = HomeLayout(
        slots = slots,
        dock = List(4) { null },
        widgetPlacements = widgets,
        leadingSlots = List(grid.cells) { null },
        grid = grid,
    )

    /** Three 4x6 pages: page 0 has two apps, page 1 is empty, page 2 has one app. */
    private fun threePages(): HomeLayout {
        val cells = 24
        val slots = MutableList<String?>(cells * 3) { null }
        slots[0] = "a"
        slots[1] = "b"
        slots[cells * 2] = "c"
        return layout(slots = slots)
    }

    // -----------------------------------------------------------------------
    // Summaries
    // -----------------------------------------------------------------------

    @Test fun `each page reports its own items and widgets`() {
        val summaries = homePageSummaries(threePages())

        assertEquals(3, summaries.size)
        assertEquals(listOf(2, 0, 1), summaries.map(HomePageSummary::items))
        assertEquals(listOf(false, true, false), summaries.map(HomePageSummary::isEmpty))
    }

    @Test fun `a page holding only a widget is not empty`() {
        val withWidget = layout(
            slots = List(24) { null } + List(24) { null },
            widgets = listOf(WidgetPlacement(slot = 0, id = 7, page = 1, column = 0, row = 0, spanX = 2, spanY = 2)),
        )

        val summaries = homePageSummaries(withWidget)

        assertEquals(1, summaries[1].widgets)
        assertFalse("a widget still occupies the page", summaries[1].isEmpty)
    }

    @Test fun `a layout without stable page ids falls back to the page number`() {
        // A half-migrated layout must not collapse every page onto id 0.
        val summaries = homePageSummaries(threePages(), pageIds = emptyList())

        assertEquals(listOf(0, 1, 2), summaries.map(HomePageSummary::pageId))
    }

    @Test fun `stable page ids are used when the layout has them`() {
        val summaries = homePageSummaries(threePages(), pageIds = listOf(11, 22, 33), hiddenPageIds = setOf(22))

        assertEquals(listOf(11, 22, 33), summaries.map(HomePageSummary::pageId))
        assertEquals(listOf(false, true, false), summaries.map(HomePageSummary::hidden))
    }

    @Test fun `occupancy marks both filled cells and the cells a widget covers`() {
        val withWidget = layout(
            slots = MutableList<String?>(24) { null }.also { it[5] = "a" },
            widgets = listOf(WidgetPlacement(slot = 0, id = 7, page = 0, column = 0, row = 0, spanX = 2, spanY = 2)),
        )

        val occupied = pageOccupancy(withWidget, page = 0)

        assertEquals(24, occupied.size)
        assertTrue("the widget's own footprint", occupied[0] && occupied[1] && occupied[4] && occupied[5])
        assertFalse("a cell outside the widget and with no app", occupied[2])
    }

    // -----------------------------------------------------------------------
    // Reordering (FR-47)
    // -----------------------------------------------------------------------

    @Test fun `moving a page left and right reorders without losing one`() {
        val order = listOf(10, 20, 30)

        assertEquals(listOf(20, 10, 30), movePage(order, from = 1, to = 0))
        assertEquals(listOf(10, 30, 20), movePage(order, from = 1, to = 2))
        assertEquals(listOf(30, 10, 20), movePage(order, from = 2, to = 0))
    }

    @Test fun `a move that goes nowhere or off the ends is harmless`() {
        val order = listOf(10, 20, 30)

        assertEquals(order, movePage(order, from = 1, to = 1))
        assertEquals("clamped, never thrown", listOf(20, 30, 10), movePage(order, from = 0, to = 9))
        assertEquals(listOf(30, 10, 20), movePage(order, from = -4, to = 0).let { movePage(order, 2, -1) })
        assertEquals(listOf(10), movePage(listOf(10), from = 0, to = 0))
    }

    @Test fun `reordering keeps exactly the same pages`() {
        val order = listOf(10, 20, 30, 40)

        val moved = movePage(order, from = 3, to = 1)

        assertEquals(order.toSet(), moved.toSet())
        assertEquals(order.size, moved.size)
    }

    // -----------------------------------------------------------------------
    // Hiding and showing (FR-47, AC-38)
    // -----------------------------------------------------------------------

    @Test fun `hiding a page removes it from the swipe order but keeps its contents listed`() {
        val ids = listOf(10, 20, 30)

        val hidden = toggleHiddenPage(emptySet(), pageId = 20, allPageIds = ids)

        assertEquals(setOf(20), hidden)
        assertEquals("AC-38: swiping skips the hidden page", listOf(10, 30), visiblePageOrder(ids, hidden))
        assertEquals("the page itself still exists", 3, ids.size)
    }

    @Test fun `showing a hidden page puts it back`() {
        val ids = listOf(10, 20, 30)

        assertEquals(emptySet<Int>(), toggleHiddenPage(setOf(20), pageId = 20, allPageIds = ids))
        assertEquals(ids, visiblePageOrder(ids, emptySet()))
    }

    @Test fun `the last visible page cannot be hidden`() {
        val ids = listOf(10, 20)
        val oneLeft = setOf(10)

        assertEquals("hiding the only page left is refused", oneLeft, toggleHiddenPage(oneLeft, 20, ids))
        assertEquals(listOf(20), visiblePageOrder(ids, oneLeft))
    }

    @Test fun `hiding is stored against the page id, so reordering moves the right page`() {
        val ids = listOf(10, 20, 30)
        val hidden = setOf(20)

        val reordered = movePage(ids, from = 2, to = 0)

        assertEquals(listOf(30, 10), visiblePageOrder(reordered, hidden))
    }

    // -----------------------------------------------------------------------
    // Deleting (FR-47)
    // -----------------------------------------------------------------------

    @Test fun `only an empty page can be deleted`() {
        val summaries = homePageSummaries(threePages())

        assertFalse(canDeletePage(summaries[0], totalPages = 3))
        assertTrue(canDeletePage(summaries[1], totalPages = 3))
        assertFalse(canDeletePage(summaries[2], totalPages = 3))
    }

    @Test fun `the only page is never deletable, even when empty`() {
        val single = homePageSummaries(layout(slots = emptyList())).single()

        assertTrue(single.isEmpty)
        assertFalse(canDeletePage(single, totalPages = 1))
    }

    @Test fun `a page holding a widget is not deletable`() {
        val withWidget = layout(
            slots = List(48) { null },
            widgets = listOf(WidgetPlacement(slot = 0, id = 7, page = 1, column = 0, row = 0, spanX = 2, spanY = 2)),
        )

        val summaries = homePageSummaries(withWidget)

        assertFalse(canDeletePage(summaries[1], totalPages = summaries.size))
    }

    // -----------------------------------------------------------------------
    // A bigger grid (FR-31)
    // -----------------------------------------------------------------------

    @Test fun `the overview follows the configured grid rather than assuming four by six`() {
        val grid = GridSpec(6, 6)
        val slots = MutableList<String?>(grid.cells * 2) { null }.also { it[grid.cells] = "a" }

        val summaries = homePageSummaries(layout(grid = grid, slots = slots))

        assertEquals(2, summaries.size)
        assertEquals(0, summaries[0].items)
        assertEquals(1, summaries[1].items)
        assertEquals(36, pageOccupancy(layout(grid = grid, slots = slots), page = 0).size)
    }
}
