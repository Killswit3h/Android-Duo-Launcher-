package com.jake.duolauncher.today

import androidx.compose.ui.unit.dp
import com.jake.duolauncher.today.builtin.SuggestionSlots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Today View's pure layout rules (FR-56, FR-57, FR-59).
 *
 * These are the parts of the column that decide what the user sees and where a drag lands, kept as
 * free functions precisely so they can be checked here rather than only by eye in a preview.
 */
class TodayLayoutTest {

    private fun item(id: String, size: TodayWidgetSize, kind: TodayWidgetKind = TodayWidgetKind.CLOCK) =
        TodayItem(id = id, kind = kind, size = size)

    // -----------------------------------------------------------------------
    // Row grouping
    // -----------------------------------------------------------------------

    @Test fun twoConsecutiveSmallWidgetsShareARow() {
        val rows = todayRows(listOf(item("a", TodayWidgetSize.SMALL), item("b", TodayWidgetSize.SMALL)))

        assertEquals(1, rows.size)
        assertEquals(listOf("a", "b"), rows.first().map(TodayItem::id))
    }

    @Test fun aLoneSmallWidgetKeepsItsOwnRow() {
        val rows = todayRows(listOf(item("a", TodayWidgetSize.SMALL), item("b", TodayWidgetSize.MEDIUM)))

        assertEquals(2, rows.size)
        assertEquals(listOf("a"), rows[0].map(TodayItem::id))
        assertEquals(listOf("b"), rows[1].map(TodayItem::id))
    }

    @Test fun onlyConsecutiveSmallWidgetsPair() {
        val rows = todayRows(
            listOf(
                item("a", TodayWidgetSize.SMALL),
                item("b", TodayWidgetSize.MEDIUM),
                item("c", TodayWidgetSize.SMALL),
            ),
        )

        // The two smalls are not adjacent, so neither is silently pulled up to join the other.
        assertEquals(3, rows.size)
    }

    @Test fun threeSmallWidgetsPairTheFirstTwo() {
        val rows = todayRows(
            listOf(
                item("a", TodayWidgetSize.SMALL),
                item("b", TodayWidgetSize.SMALL),
                item("c", TodayWidgetSize.SMALL),
            ),
        )

        assertEquals(2, rows.size)
        assertEquals(listOf("a", "b"), rows[0].map(TodayItem::id))
        assertEquals(listOf("c"), rows[1].map(TodayItem::id))
    }

    @Test fun fullWidthWidgetsEachTakeARow() {
        val rows = todayRows(
            listOf(
                item("a", TodayWidgetSize.MEDIUM),
                item("b", TodayWidgetSize.LARGE),
                item("c", TodayWidgetSize.EXTRA_LARGE),
            ),
        )

        assertEquals(3, rows.size)
        assertTrue(rows.all { it.size == 1 })
    }

    @Test fun anEmptyColumnHasNoRows() {
        assertEquals(emptyList<List<TodayItem>>(), todayRows(emptyList()))
    }

    // -----------------------------------------------------------------------
    // Reorder
    // -----------------------------------------------------------------------

    @Test fun movingAWidgetUpPlacesItAtTheTargetIndex() {
        val moved = listOf("a", "b", "c").moveItem(from = 2, to = 0)

        assertEquals(listOf("c", "a", "b"), moved)
    }

    @Test fun movingAWidgetDownPlacesItAtTheTargetIndex() {
        val moved = listOf("a", "b", "c").moveItem(from = 0, to = 2)

        assertEquals(listOf("b", "c", "a"), moved)
    }

    @Test fun aDragPastTheEndClampsToTheLastSlotRatherThanThrowing() {
        val moved = listOf("a", "b", "c").moveItem(from = 0, to = 99)

        assertEquals(listOf("b", "c", "a"), moved)
    }

    @Test fun aDragPastTheStartClampsToTheFirstSlot() {
        val moved = listOf("a", "b", "c").moveItem(from = 2, to = -5)

        assertEquals(listOf("c", "a", "b"), moved)
    }

    @Test fun movingAWidgetOntoItselfChangesNothing() {
        val original = listOf("a", "b", "c")

        assertEquals(original, original.moveItem(from = 1, to = 1))
    }

    @Test fun anOutOfRangeSourceIsIgnored() {
        val original = listOf("a", "b", "c")

        assertEquals(original, original.moveItem(from = 7, to = 0))
    }

    @Test fun movingWithinAnEmptyColumnIsSafe() {
        assertEquals(emptyList<String>(), emptyList<String>().moveItem(from = 0, to = 1))
    }

    // -----------------------------------------------------------------------
    // Suggestion slots (FR-59: "4 or 8 apps")
    // -----------------------------------------------------------------------

    @Test fun smallAndMediumSuggestionsShowFourApps() {
        assertEquals(SuggestionSlots.FOUR, suggestionSlotsFor(TodayWidgetSize.SMALL))
        assertEquals(SuggestionSlots.FOUR, suggestionSlotsFor(TodayWidgetSize.MEDIUM))
    }

    @Test fun largeAndExtraLargeSuggestionsShowEightApps() {
        assertEquals(SuggestionSlots.EIGHT, suggestionSlotsFor(TodayWidgetSize.LARGE))
        assertEquals(SuggestionSlots.EIGHT, suggestionSlotsFor(TodayWidgetSize.EXTRA_LARGE))
    }

    // -----------------------------------------------------------------------
    // Spans
    // -----------------------------------------------------------------------

    @Test fun onlyTheSmallWidgetIsNarrowerThanTheColumn() {
        assertFalse(TodayWidgetSize.SMALL.isFullWidth)
        assertTrue(TodayWidgetSize.MEDIUM.isFullWidth)
        assertTrue(TodayWidgetSize.LARGE.isFullWidth)
        assertTrue(TodayWidgetSize.EXTRA_LARGE.isFullWidth)
    }

    @Test fun theExtraLargeWidgetIsOfferedOnlyWhereTheGridFits() {
        // FR-59 offers 4x6 "where the grid fits"; a six-row column fits it and a four-row one does not.
        assertTrue(TodayWidgetSize.EXTRA_LARGE.fitsIn(6))
        assertFalse(TodayWidgetSize.EXTRA_LARGE.fitsIn(4))
        assertTrue(TodayWidgetSize.LARGE.fitsIn(4))
    }

    // -----------------------------------------------------------------------
    // Metrics
    // -----------------------------------------------------------------------

    @Test fun cellsAreSquareAndShareTheGuttersEvenly() {
        val metrics = TodayMetrics(columnWidth = 400.dp, gutter = 12.dp)

        // Four cells and the three gutters between them fill the column exactly.
        assertEquals(91f, metrics.cell.value, TOLERANCE)
        assertEquals(400f, metrics.width(TodayWidgetSize.MEDIUM).value, TOLERANCE)
    }

    @Test fun aSmallWidgetIsSquare() {
        val metrics = TodayMetrics(columnWidth = 400.dp, gutter = 12.dp)

        assertEquals(
            metrics.width(TodayWidgetSize.SMALL).value,
            metrics.height(TodayWidgetSize.SMALL).value,
            TOLERANCE,
        )
    }

    @Test fun tallerSpansAreProportionallyTaller() {
        val metrics = TodayMetrics(columnWidth = 400.dp, gutter = 12.dp)
        val medium = metrics.height(TodayWidgetSize.MEDIUM).value
        val large = metrics.height(TodayWidgetSize.LARGE).value
        val extraLarge = metrics.height(TodayWidgetSize.EXTRA_LARGE).value

        assertTrue(large > medium)
        assertTrue(extraLarge > large)
    }

    @Test fun aZeroWidthColumnNeverProducesANegativeCell() {
        val metrics = TodayMetrics(columnWidth = 0.dp, gutter = 12.dp)

        assertEquals(0f, metrics.cell.value, TOLERANCE)
    }

    // -----------------------------------------------------------------------
    // Analog clock geometry
    // -----------------------------------------------------------------------

    @Test fun theSecondHandSweepsSixDegreesPerSecond() {
        assertEquals(0f, secondHandDegrees(0f), TOLERANCE)
        assertEquals(90f, secondHandDegrees(15f), TOLERANCE)
        assertEquals(180f, secondHandDegrees(30f), TOLERANCE)
    }

    @Test fun theSecondHandWrapsPastTheMinuteRatherThanRunningAway() {
        // The sweep keeps counting up between ticks, so 61s must read as 1s, not as 366 degrees.
        assertEquals(secondHandDegrees(1f), secondHandDegrees(61f), TOLERANCE)
    }

    @Test fun theMinuteHandAdvancesSmoothlyWithinTheMinute() {
        assertEquals(180f, minuteHandDegrees(30, 0f), TOLERANCE)
        // Half a minute later it has moved another three degrees, not jumped a whole step.
        assertEquals(183f, minuteHandDegrees(30, 30f), TOLERANCE)
    }

    @Test fun theHourHandAdvancesWithTheMinutes() {
        assertEquals(270f, hourHandDegrees(9, 0), TOLERANCE)
        assertEquals(285f, hourHandDegrees(9, 30), TOLERANCE)
    }

    @Test fun twelveOClockPointsStraightUp() {
        assertEquals(0f, hourHandDegrees(12, 0), TOLERANCE)
    }

    private companion object {
        const val TOLERANCE = 0.01f
    }
}
