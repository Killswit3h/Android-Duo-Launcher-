package com.jake.duolauncher

import org.junit.Assert.*
import org.junit.Test

class LeadingGridEditingTest {
    @Test fun `signed leading addresses round trip without changing normal pages`() {
        assertEquals(-1, homeCellPage(-24)); assertEquals(0, homeCellLocal(-24))
        assertEquals(-1, homeCellPage(-1)); assertEquals(23, homeCellLocal(-1))
        assertEquals(-24, homeCellIndex(-1, 0)); assertEquals(-1, homeCellIndex(-1, 23))
        assertEquals(0, homeCellIndex(0, 0)); assertEquals(24, homeCellIndex(1, 0))
        val layout = HomeLayout(listOf("screen1"), emptyList(), leadingSlots = List(24) { if (it == 23) "leading" else null })
        assertEquals("leading", layout.slotAt(-1)); assertEquals(-1, layout.indexOfShortcut("leading"))
        assertEquals("screen1", layout.slotAt(0)); assertEquals(1, layout.pageCount)
    }

    @Test fun `leading moves into empty cells directly and occupied insertion stays bounded`() {
        val leading = MutableList<String?>(24) { null }.apply { this[0] = "a"; this[1] = "b"; this[23] = "z" }
        val before = HomeLayout(listOf("home"), emptyList(), leadingSlots = leading)
        val direct = dropApp(before, "a", DropTarget.Home(-22))
        assertNull(direct.slotAt(-24)); assertEquals("a", direct.slotAt(-22)); assertEquals("b", direct.slotAt(-23))
        val inserted = dropApp(before, "new", DropTarget.Home(-23))
        assertEquals(listOf("a", "new", "b"), listOf(-24, -23, -22).map(inserted::slotAt))
        assertEquals(listOf("home"), inserted.slots)
        val full = HomeLayout(listOf("home"), emptyList(), leadingSlots = List(24) { "l$it" })
        assertSame(full, dropApp(full, "new", DropTarget.Home(-24)))
    }

    @Test fun `cross-surface moves are atomic and never duplicate shortcuts`() {
        val before = HomeLayout(listOf("home"), listOf(null, null, null, null), leadingSlots = List(24) { null })
        val toLeading = dropApp(before, "home", DropTarget.Home(-24))
        assertEquals("home", toLeading.slotAt(-24)); assertNull(toLeading.slotAt(0))
        val back = dropApp(toLeading, "home", DropTarget.Home(4))
        assertNull(back.slotAt(-24)); assertEquals("home", back.slotAt(4))
        assertEquals(1, (back.leadingSlots + back.slots + back.dock).count { it == "home" })
    }

    @Test fun `leading widgets collide with leading apps using signed cells`() {
        val widget = WidgetPlacement(4, 26, -1, 0, 0, 2, 2)
        val leading = List<String?>(24) { if (it == 2) "app" else null }
        val layout = HomeLayout(emptyList(), emptyList(), listOf(widget), leadingSlots = leading)
        assertEquals(setOf(-24, -23, -20, -19), widget.coveredIndices())
        assertNull(widgetCandidate(layout, 5, -24, 2, 2))
        assertNull(widgetCandidate(layout, 5, -22, 1, 1))
        assertEquals(WidgetPlacement(5, EMPTY_WIDGET, -1, 2, 1, 1, 1), widgetCandidate(layout, 5, -18, 1, 1))
        assertEquals(widget.copy(column = 2), moveWidget(layout.copy(leadingSlots = List(24) { null }), 4, -22).placement(4))
    }

    @Test fun `folders create dissolve and transfer on leading surface`() {
        val folder = FolderEntry("folder:00000000-0000-0000-0000-000000000008", "Pair", emptyList())
        val before = HomeLayout(listOf("b"), emptyList(), leadingSlots = List(24) { if (it == 0) "a" else null })
        val created = createFolder(before, folder, "a", "b", -24)
        assertEquals(folder.id, created.slotAt(-24)); assertNull(created.slotAt(0))
        val extracted = removeAppFromFolder(created, folder.id, "a", DropTarget.Home(3))
        assertEquals("b", extracted.slotAt(-24)); assertEquals("a", extracted.slotAt(3))
        assertTrue(extracted.folders.isEmpty())
    }
}
