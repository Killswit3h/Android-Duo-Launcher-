package com.jake.duolauncher.home

import com.jake.duolauncher.today.TodayClockStyle
import com.jake.duolauncher.today.TodayItem
import com.jake.duolauncher.today.TodayWidgetKind
import com.jake.duolauncher.today.TodayWidgetSize

/**
 * How a Today View column survives a restart (FR-57, AC-47).
 *
 * `LeadingPageConfig.today` is a `List<String>` of opaque ids — the schema deliberately does not
 * model built-in widgets, because it was written for *placement slots and stack ids*. A built-in
 * Today widget has no placement and no binding: it is entirely described by its kind, its span and,
 * for the clock, its face. So rather than widen the schema (and the backup codec, and its
 * validation) for data that is three enums, the description is encoded **into** the id.
 *
 * That is what makes ordering, adding and removing persist through the existing
 * `addToToday` / `removeFromToday` / `moveTodayItem` model calls with no schema change at all: the
 * list of ids *is* the column, and the id *is* the widget.
 *
 * The grammar is `duo-today:<kind>:<size>:<clock style>:<sequence>`. The trailing sequence is what
 * lets the same kind and span appear twice — two 2×2 clocks in different faces, say — while keeping
 * every id unique, which both the model (`addToToday` refuses a duplicate) and Compose (`key`)
 * require. Nothing outside this file parses these; everything else treats them as opaque.
 *
 * Unparseable ids are **skipped rather than guessed**. An id here can also be a real placement slot
 * or stack id once those land, and rendering an unknown id as an arbitrary widget would be worse
 * than leaving it out.
 */

/** The prefix that marks an id as a built-in Today widget rather than a placement or stack id. */
const val TODAY_BUILTIN_PREFIX = "duo-today"

private const val FIELD_SEPARATOR = ':'

/** The id for one built-in widget. [sequence] only has to be unique within the column. */
fun todayBuiltinId(
    kind: TodayWidgetKind,
    size: TodayWidgetSize,
    clockStyle: TodayClockStyle = TodayClockStyle.ANALOG,
    sequence: Int = 0,
): String = listOf(TODAY_BUILTIN_PREFIX, kind.name, size.name, clockStyle.name, sequence.toString())
    .joinToString(FIELD_SEPARATOR.toString())

/**
 * The widget [id] describes, or null when it is not a built-in id or names something this build
 * does not know. Tolerant of a missing clock style and sequence so a hand-edited or older id still
 * resolves.
 */
fun parseTodayBuiltin(id: String): TodayItem? {
    val parts = id.split(FIELD_SEPARATOR)
    if (parts.size < 3 || parts[0] != TODAY_BUILTIN_PREFIX) return null
    val kind = TodayWidgetKind.entries.firstOrNull { it.name == parts[1] } ?: return null
    val size = TodayWidgetSize.entries.firstOrNull { it.name == parts[2] } ?: return null
    val clock = parts.getOrNull(3)?.let { name -> TodayClockStyle.entries.firstOrNull { it.name == name } }
        ?: TodayClockStyle.ANALOG
    return TodayItem(id = id, kind = kind, size = size, clockStyle = clock)
}

/**
 * The column [ids] describe, in order.
 *
 * Ids that are not built-in widgets are skipped, so a column that also names a widget placement or a
 * stack simply does not draw those yet instead of failing to draw at all.
 */
fun todayItemsOf(ids: List<String>): List<TodayItem> = ids.mapNotNull(::parseTodayBuiltin)

/**
 * [ids] with a new widget of [kind] and [size] appended, under an id that cannot collide with one
 * already in the column.
 */
fun todayIdsWithAdded(
    ids: List<String>,
    kind: TodayWidgetKind,
    size: TodayWidgetSize,
    clockStyle: TodayClockStyle = TodayClockStyle.ANALOG,
): List<String> {
    var sequence = ids.size
    var candidate = todayBuiltinId(kind, size, clockStyle, sequence)
    while (candidate in ids) candidate = todayBuiltinId(kind, size, clockStyle, ++sequence)
    return ids + candidate
}

/**
 * The column a fresh install starts with (FR-36, FR-56).
 *
 * Chosen so the first thing the user sees on the left page is recognisably the iPhone Duo spread:
 * the clock and battery pair across the top, the date and calendar below them, and the apps they
 * actually use at the bottom. Every one of these works with no permission granted — Calendar shows
 * the date alone and Suggestions fills in as launch history accumulates — so a fresh install never
 * opens on a column of permission prompts.
 */
val DEFAULT_TODAY_IDS: List<String> = listOf(
    todayBuiltinId(TodayWidgetKind.CLOCK, TodayWidgetSize.SMALL, TodayClockStyle.ANALOG, 0),
    todayBuiltinId(TodayWidgetKind.BATTERIES, TodayWidgetSize.SMALL, sequence = 1),
    todayBuiltinId(TodayWidgetKind.CALENDAR, TodayWidgetSize.MEDIUM, sequence = 2),
    todayBuiltinId(TodayWidgetKind.SUGGESTIONS, TodayWidgetSize.LARGE, sequence = 3),
)

/**
 * The ids to render for a stored column: the user's own, or [DEFAULT_TODAY_IDS] while they have
 * never edited one.
 *
 * The default is **not** written to the model. Persisting it on first draw would make an untouched
 * column indistinguishable from a deliberately emptied one, so "empty" keeps meaning "never edited"
 * until the user's first real edit, which is when the whole resolved column is committed.
 */
fun todayIdsOrDefault(stored: List<String>): List<String> =
    if (stored.isEmpty()) DEFAULT_TODAY_IDS else stored
