package com.jake.duolauncher.home

import com.jake.duolauncher.today.TodayClockStyle
import com.jake.duolauncher.today.TodayItem
import com.jake.duolauncher.today.TodayWidgetKind
import com.jake.duolauncher.today.TodayWidgetSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a Today View column survives a restart (FR-57, AC-47).
 *
 * The column is stored as opaque ids in `LeadingPageConfig.today`, so everything that makes it
 * persist — the id grammar, the defaults and uniqueness — is exercised here.
 */
class TodayHostingTest {

    @Test fun `a built-in id round-trips to the widget it describes`() {
        val id = todayBuiltinId(TodayWidgetKind.CLOCK, TodayWidgetSize.SMALL, TodayClockStyle.DIGITAL, 3)
        assertEquals(
            TodayItem(id = id, kind = TodayWidgetKind.CLOCK, size = TodayWidgetSize.SMALL, clockStyle = TodayClockStyle.DIGITAL),
            parseTodayBuiltin(id),
        )
    }

    @Test fun `every kind and span round-trips`() {
        TodayWidgetKind.entries.forEach { kind ->
            TodayWidgetSize.entries.forEach { size ->
                val item = parseTodayBuiltin(todayBuiltinId(kind, size))
                assertEquals("$kind $size", kind, item?.kind)
                assertEquals("$kind $size", size, item?.size)
            }
        }
    }

    @Test fun `ids that are not built-ins are skipped rather than guessed`() {
        // A placement slot and a stack id share this list once those land; neither may render as
        // an arbitrary widget.
        val column = listOf("12", "stack:abc", todayBuiltinId(TodayWidgetKind.BATTERIES, TodayWidgetSize.SMALL))
        val items = todayItemsOf(column)
        assertEquals(1, items.size)
        assertEquals(TodayWidgetKind.BATTERIES, items.single().kind)
    }

    @Test fun `an unknown kind or span is skipped`() {
        assertNull(parseTodayBuiltin("duo-today:WEATHER:SMALL:ANALOG:0"))
        assertNull(parseTodayBuiltin("duo-today:CLOCK:GIGANTIC:ANALOG:0"))
        assertNull(parseTodayBuiltin("elsewhere:CLOCK:SMALL:ANALOG:0"))
        assertNull(parseTodayBuiltin(""))
    }

    @Test fun `a short id still resolves with the default clock face`() {
        val item = parseTodayBuiltin("duo-today:CALENDAR:MEDIUM")
        assertEquals(TodayWidgetKind.CALENDAR, item?.kind)
        assertEquals(TodayWidgetSize.MEDIUM, item?.size)
        assertEquals(TodayClockStyle.ANALOG, item?.clockStyle)
    }

    @Test fun `adding the same widget twice still yields unique ids`() {
        var ids = emptyList<String>()
        repeat(4) { ids = todayIdsWithAdded(ids, TodayWidgetKind.CLOCK, TodayWidgetSize.SMALL) }
        assertEquals(4, ids.size)
        assertEquals("ids are Compose keys and addToToday refuses duplicates", 4, ids.distinct().size)
        assertEquals(4, todayItemsOf(ids).size)
    }

    @Test fun `adding appends to the end of the column`() {
        val start = listOf(todayBuiltinId(TodayWidgetKind.CLOCK, TodayWidgetSize.SMALL, sequence = 0))
        val next = todayIdsWithAdded(start, TodayWidgetKind.NOW_PLAYING, TodayWidgetSize.MEDIUM)
        assertEquals(start.single(), next.first())
        assertEquals(TodayWidgetKind.NOW_PLAYING, parseTodayBuiltin(next.last())?.kind)
    }

    @Test fun `an untouched column shows the defaults and an edited one shows itself`() {
        assertEquals(DEFAULT_TODAY_IDS, todayIdsOrDefault(emptyList()))
        val edited = listOf(todayBuiltinId(TodayWidgetKind.SUGGESTIONS, TodayWidgetSize.LARGE))
        assertEquals(edited, todayIdsOrDefault(edited))
    }

    @Test fun `the default column is four distinct widgets that all resolve`() {
        assertEquals(4, DEFAULT_TODAY_IDS.distinct().size)
        assertEquals(4, todayItemsOf(DEFAULT_TODAY_IDS).size)
    }

    @Test fun `every id is a non-blank string, which is what the backup codec accepts`() {
        // LayoutBackupV3 reads `today` through strictIds(), which rejects blank entries.
        assertTrue(DEFAULT_TODAY_IDS.all { it.isNotBlank() })
        assertTrue(todayIdsWithAdded(emptyList(), TodayWidgetKind.CLOCK, TodayWidgetSize.SMALL).all { it.isNotBlank() })
    }
}
