package com.jake.duolauncher.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * AC-38: "Given 3 pages with page 2 hidden, swiping skips page 2, and its contents are kept."
 *
 * Home skips a hidden page at the moment a swipe settles on it rather than renumbering the pager,
 * so these two rules are the whole of the behaviour.
 */
class HiddenPagesTest {

    @Test fun `hidden ids translate to the page numbers the pager uses`() {
        assertEquals(setOf(1), hiddenPageNumbers(listOf(10, 20, 30), hidden = setOf(20), pageCount = 3))
    }

    @Test fun `a hidden id follows its page after a reorder`() {
        // Same hidden id, different order: the number it maps to moves with it.
        assertEquals(setOf(0), hiddenPageNumbers(listOf(20, 10, 30), hidden = setOf(20), pageCount = 3))
    }

    @Test fun `a page with no id yet is never reported hidden`() {
        assertEquals(emptySet<Int>(), hiddenPageNumbers(listOf(10), hidden = setOf(20), pageCount = 3))
    }

    @Test fun `nothing hidden means nothing to skip`() {
        assertEquals(emptySet<Int>(), hiddenPageNumbers(listOf(10, 20, 30), hidden = emptySet(), pageCount = 3))
    }

    @Test fun `swiping forward onto a hidden page carries on to the next one`() {
        assertEquals(2, nextVisiblePage(page = 1, forward = true, pageCount = 3, hidden = setOf(1)))
    }

    @Test fun `swiping back onto a hidden page carries on backwards`() {
        assertEquals(0, nextVisiblePage(page = 1, forward = false, pageCount = 3, hidden = setOf(1)))
    }

    @Test fun `a run of hidden pages is skipped in one settle`() {
        assertEquals(3, nextVisiblePage(page = 1, forward = true, pageCount = 4, hidden = setOf(1, 2)))
    }

    @Test fun `swiping past a hidden last Home page reaches the App Library`() {
        // Three Home pages plus the library at index 3, which can never be hidden.
        assertEquals(3, nextVisiblePage(page = 2, forward = true, pageCount = 4, hidden = setOf(2)))
    }

    @Test fun `with nothing visible ahead it turns back rather than stranding the user`() {
        assertEquals(0, nextVisiblePage(page = 2, forward = true, pageCount = 3, hidden = setOf(1, 2)))
    }

    @Test fun `every page hidden has no answer`() {
        assertNull(nextVisiblePage(page = 0, forward = true, pageCount = 2, hidden = setOf(0, 1)))
    }
}
