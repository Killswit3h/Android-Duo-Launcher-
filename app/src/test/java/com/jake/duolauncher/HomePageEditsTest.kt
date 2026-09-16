package com.jake.duolauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The Page overview's three edits (FR-47): reorder, and delete of an empty page.
 *
 * These back `LauncherModel.reorderPages` and `deletePage`, which were no-ops until now. The
 * property that matters most is that a page's contents and its stable id travel together, because
 * hiding is stored against the id.
 */
class HomePageEditsTest {

    private val grid = GridSpec(4, 6)
    private val cells = grid.cells

    /** Three pages: "a" on page 0, "b" on page 1, "c" on page 2, ids 10/20/30. */
    private fun threePages(
        middleEmpty: Boolean = false,
        widgets: List<WidgetPlacement> = emptyList(),
        hidden: Set<Int> = emptySet(),
    ): DuoLayout {
        val slots = MutableList<String?>(cells * 2 + 1) { null }
        slots[0] = "a"
        if (!middleEmpty) slots[cells] = "b"
        slots[cells * 2] = "c"
        return DuoLayout(
            grid = grid,
            slots = slots,
            leadingSlots = List(cells) { null },
            widgetPlacements = widgets,
            pageIds = listOf(10, 20, 30),
            hiddenPageIds = hidden,
        )
    }

    private fun widgetOn(page: Int, slot: Int = 7) = WidgetPlacement(slot, 1000 + slot, page, 2, 2, 2, 2)

    // -----------------------------------------------------------------------
    // Reorder
    // -----------------------------------------------------------------------

    @Test fun `moving a page carries its apps with it`() {
        val moved = reorderHomePages(threePages(), from = 2, to = 0)
        assertEquals("c", moved.slots[0])
        assertEquals("a", moved.slots[cells])
        assertEquals("b", moved.slots[cells * 2])
    }

    @Test fun `moving a page carries its stable id with it`() {
        val moved = reorderHomePages(threePages(), from = 2, to = 0)
        assertEquals(listOf(30, 10, 20), moved.pageIds)
    }

    @Test fun `moving a page carries its widgets with it`() {
        val moved = reorderHomePages(threePages(widgets = listOf(widgetOn(page = 2))), from = 2, to = 0)
        assertEquals(0, moved.widgetPlacements.single().page)
    }

    @Test fun `hiding follows the page, not the position, across a move`() {
        // Page id 30 is hidden. After it moves to the front it must still be the hidden one; hiding
        // by position would now hide whatever page landed at index 2 instead.
        val moved = reorderHomePages(threePages(hidden = setOf(30)), from = 2, to = 0)
        assertEquals(setOf(30), moved.hiddenPageIds)
        assertEquals(30, moved.pageIds[0])
    }

    @Test fun `the leading page is never reordered`() {
        val leading = WidgetPlacement(9, 2000, -1, 0, 0, 2, 2)
        val moved = reorderHomePages(threePages(widgets = listOf(leading)), from = 0, to = 2)
        assertEquals(-1, moved.widgetPlacements.single().page)
    }

    @Test fun `a move that goes nowhere returns the layout unchanged`() {
        val layout = threePages()
        assertSame(layout, reorderHomePages(layout, from = 1, to = 1))
    }

    @Test fun `out-of-range positions clamp instead of throwing`() {
        val moved = reorderHomePages(threePages(), from = 99, to = -5)
        assertEquals(listOf(30, 10, 20), moved.pageIds)
    }

    // -----------------------------------------------------------------------
    // Delete
    // -----------------------------------------------------------------------

    @Test fun `deleting an empty page shifts later pages down`() {
        val deleted = deleteHomePage(threePages(middleEmpty = true), pageId = 20)
        assertEquals("a", deleted.slots[0])
        assertEquals("c", deleted.slots[cells])
        assertEquals(listOf(10, 30), deleted.pageIds)
    }

    @Test fun `deleting a page renumbers the widgets after it`() {
        val deleted = deleteHomePage(threePages(middleEmpty = true, widgets = listOf(widgetOn(page = 2))), pageId = 20)
        assertEquals(1, deleted.widgetPlacements.single().page)
    }

    @Test fun `a deleted page takes its hidden entry with it`() {
        val deleted = deleteHomePage(threePages(middleEmpty = true, hidden = setOf(20)), pageId = 20)
        assertEquals(emptySet<Int>(), deleted.hiddenPageIds)
    }

    @Test fun `a page holding an app cannot be deleted`() {
        val layout = threePages()
        assertSame(layout, deleteHomePage(layout, pageId = 20))
    }

    @Test fun `a page holding only a widget cannot be deleted`() {
        val layout = threePages(middleEmpty = true, widgets = listOf(widgetOn(page = 1)))
        assertSame(layout, deleteHomePage(layout, pageId = 20))
    }

    @Test fun `the last remaining page cannot be deleted`() {
        val single = DuoLayout(grid = grid, slots = emptyList(), leadingSlots = List(cells) { null }, pageIds = listOf(10))
        assertSame(single, deleteHomePage(single, pageId = 10))
    }

    @Test fun `an unknown page id deletes nothing`() {
        val layout = threePages(middleEmpty = true)
        assertSame(layout, deleteHomePage(layout, pageId = 999))
    }
}
