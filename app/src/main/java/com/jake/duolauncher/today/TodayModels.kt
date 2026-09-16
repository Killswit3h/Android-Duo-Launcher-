package com.jake.duolauncher.today

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jake.duolauncher.design.DuoTokens
import com.jake.duolauncher.today.builtin.SuggestionSlots

/**
 * The shape of the Today View column and of the built-in widgets in it (FR-55 to FR-59).
 *
 * Everything in this file is pure: no Compose, no Android, no persistence. The column composable
 * takes its items as a parameter and reports edits through callbacks, because the schema-9 layout
 * task owns the real ordering. That keeps the two tracks from fighting over one source of truth,
 * and it keeps the layout rules below unit-testable on the JVM.
 */

/** The four widget spans (FR-59). `columns` and `rows` are grid cells, as the spec names them. */
enum class TodayWidgetSize(val columns: Int, val rows: Int) {
    /** 2x2. Two of these sit side by side in the column, as they do on iOS. */
    SMALL(columns = 2, rows = 2),

    /** 4x2. */
    MEDIUM(columns = 4, rows = 2),

    /** 4x4. */
    LARGE(columns = 4, rows = 4),

    /**
     * 4x6, the size iOS 27 added for the Duo's inner display. Only offered "where the grid fits"
     * (FR-59), which [fitsIn] decides from the space the column actually has.
     */
    EXTRA_LARGE(columns = 4, rows = 6);

    /** True when this widget occupies the column's full width. */
    val isFullWidth: Boolean get() = columns >= TODAY_COLUMNS

    /** Whether this span fits a column [availableRows] cells tall. */
    fun fitsIn(availableRows: Int): Boolean = rows <= availableRows
}

/** The Today column is four cells wide, matching the HIG's "prefer an even number of columns". */
const val TODAY_COLUMNS = 4

/** The five built-in widgets (FR-59). Names match the persisted `kind` in the spec's schema. */
enum class TodayWidgetKind(val displayName: String) {
    CLOCK("Clock"),
    CALENDAR("Date & Calendar"),
    BATTERIES("Batteries"),
    NOW_PLAYING("Now Playing"),
    SUGGESTIONS("App Suggestions"),
}

/** FR-59: "Clock: analog or digital." */
enum class TodayClockStyle { ANALOG, DIGITAL }

/**
 * One entry in the Today View column.
 *
 * [id] is opaque to the UI and stable across reorders; the schema-9 task supplies the real
 * placement id. [clockStyle] is the only per-widget option any built-in currently has, and maps to
 * the `options` field of the spec's `WidgetPlacement`.
 */
@Immutable
data class TodayItem(
    val id: String,
    val kind: TodayWidgetKind,
    val size: TodayWidgetSize,
    val clockStyle: TodayClockStyle = TodayClockStyle.ANALOG,
)

/**
 * How many apps App Suggestions shows at a given span (FR-59: "4 or 8 apps").
 *
 * The feed's own doc ties FOUR to the 4x2 and EIGHT to the 4x4, so the two larger spans take eight
 * and the two smaller ones take four.
 */
fun suggestionSlotsFor(size: TodayWidgetSize): SuggestionSlots = when (size) {
    TodayWidgetSize.SMALL, TodayWidgetSize.MEDIUM -> SuggestionSlots.FOUR
    TodayWidgetSize.LARGE, TodayWidgetSize.EXTRA_LARGE -> SuggestionSlots.EIGHT
}

/**
 * Groups the column into laid-out rows: two consecutive small widgets share a row, everything else
 * takes a row of its own.
 *
 * Pairing only consecutive smalls means reordering stays predictable — moving a widget never
 * silently re-pairs two unrelated ones further down the column.
 */
fun todayRows(items: List<TodayItem>): List<List<TodayItem>> {
    val rows = ArrayList<List<TodayItem>>(items.size)
    var index = 0
    while (index < items.size) {
        val item = items[index]
        val next = items.getOrNull(index + 1)
        if (item.size == TodayWidgetSize.SMALL && next?.size == TodayWidgetSize.SMALL) {
            rows += listOf(item, next)
            index += 2
        } else {
            rows += listOf(item)
            index += 1
        }
    }
    return rows
}

/**
 * Moves the item at [from] so that it lands at [to], which is what a finished drag reports (FR-57).
 *
 * Out-of-range indices are clamped rather than throwing: a drag that ends past either end of the
 * column means "put it last" or "put it first", not "crash".
 */
fun <T> List<T>.moveItem(from: Int, to: Int): List<T> {
    if (from !in indices || isEmpty()) return this
    val target = to.coerceIn(0, size - 1)
    if (from == target) return this
    val moved = toMutableList()
    moved.add(target, moved.removeAt(from))
    return moved
}

/**
 * Pixel geometry for the column, derived from the width it was actually given.
 *
 * Cells are square, so a 2x2 widget is a square and a 4x2 is a two-to-one letterbox, matching how
 * the spans read on iOS. The gutter doubles as the inter-widget spacing.
 */
@Immutable
data class TodayMetrics(
    val columnWidth: Dp,
    val gutter: Dp = DuoTokens.space.md,
) {
    /** One grid cell, square. */
    val cell: Dp get() = ((columnWidth - gutter * (TODAY_COLUMNS - 1)) / TODAY_COLUMNS).coerceAtLeast(0.dp)

    fun width(size: TodayWidgetSize): Dp = cell * size.columns + gutter * (size.columns - 1)

    fun height(size: TodayWidgetSize): Dp = cell * size.rows + gutter * (size.rows - 1)
}

// ---------------------------------------------------------------------------
// Analog clock geometry (FR-59). Pure, so it is unit tested.
// ---------------------------------------------------------------------------

/** Degrees clockwise from 12 for the second hand. Takes a float so it can sweep between seconds. */
fun secondHandDegrees(seconds: Float): Float = (seconds.mod(60f)) * 6f

/** Degrees for the minute hand, advanced smoothly by the seconds within the minute. */
fun minuteHandDegrees(minute: Int, seconds: Float): Float =
    (minute.mod(60)) * 6f + (seconds.mod(60f)) * 0.1f

/** Degrees for the hour hand, advanced by the minutes within the hour. */
fun hourHandDegrees(hour12: Int, minute: Int): Float =
    (hour12.mod(12)) * 30f + (minute.mod(60)) * 0.5f
