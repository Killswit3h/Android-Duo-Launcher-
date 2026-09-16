package com.jake.duolauncher.today.builtin

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How many upcoming events the Date and Calendar widget shows (FR-59: "next 3 events"). */
const val CALENDAR_EVENT_COUNT = 3

/** How far ahead the provider is asked to look. Wide enough to find three events in a quiet week. */
const val CALENDAR_LOOKAHEAD_DAYS = 7L

/**
 * One instance of a calendar event, reduced to what the widget draws.
 *
 * Deliberately a small, closed shape: no location, no description, no attendees, no organiser, no
 * conferencing link. None of those are read from the provider, so none of them can be retained by
 * accident. Instances of this type are held only inside [CalendarFeedState.Events] in memory and
 * are dropped on [CalendarFeed.stop] (NFR-S4).
 */
data class CalendarEvent(
    val eventId: Long,
    /** The event's title. Blank when the calendar has none; the widget supplies its own wording. */
    val title: String,
    val beginMillis: Long,
    val endMillis: Long,
    val allDay: Boolean,
    /** The calendar's display colour as a packed ARGB int, for the widget's leading accent. */
    val color: Int,
) {
    /** True while the event is running: it has begun and has not yet ended. */
    fun isInProgress(nowMillis: Long): Boolean = beginMillis <= nowMillis && endMillis > nowMillis
}

/**
 * What the Date and Calendar widget should show.
 *
 * Today's date is present in every state, because FR-59 shows the date unconditionally and only the
 * event list depends on `READ_CALENDAR`. That is what keeps the permission prompt from replacing
 * the whole widget.
 */
sealed interface CalendarFeedState {
    /** Today, in the device's current zone. Always available, with or without calendar access. */
    val today: LocalDate

    /**
     * `READ_CALENDAR` has not been granted. The widget shows the date plus its own opt-in prompt
     * rather than an empty list, which is the point-of-use explanation NFR-S2 requires.
     */
    data class PermissionRequired(override val today: LocalDate) : CalendarFeedState

    /** Access is granted. [events] holds up to [CALENDAR_EVENT_COUNT] events, soonest first. */
    data class Events(
        override val today: LocalDate,
        val events: List<CalendarEvent>,
    ) : CalendarFeedState

    /**
     * Access is granted but the provider could not be read (a disabled provider, a profile with no
     * calendar, a transient failure). Distinct from [PermissionRequired] so the widget shows the
     * date quietly instead of nagging for a permission the user already gave.
     */
    data class Unavailable(override val today: LocalDate) : CalendarFeedState
}

/**
 * Reads calendar instances, and reports whether it is allowed to.
 *
 * A seam so the selection rules stay JVM-testable and so the feed holds no `ContentResolver`.
 * [SystemCalendarSource] is the implementation.
 */
interface CalendarSource {
    /** Whether `READ_CALENDAR` is currently granted. Re-read on each refresh, never cached. */
    fun isPermissionGranted(): Boolean

    /**
     * Event instances overlapping `[fromMillis, toMillis)`, in any order. Returns null when the
     * provider could not be read at all, which is different from "no events".
     *
     * Always called on a background thread.
     */
    fun query(fromMillis: Long, toMillis: Long): List<CalendarEvent>?
}

/**
 * The next few events, from a batch of instances the provider returned in arbitrary order.
 *
 * Events that have already finished are dropped, events in progress are kept (a meeting you are
 * currently in is the most relevant row the widget can show), and the rest sort by start time. Ties
 * break on end time and then on id, so a redraw never reshuffles two events that begin together.
 * Repeating events produce one instance per occurrence and each is kept on its own merit; the id is
 * de-duplicated only within a single start time, which is where the provider can legitimately
 * return the same instance twice.
 */
fun selectUpcomingEvents(
    instances: List<CalendarEvent>,
    nowMillis: Long,
    limit: Int = CALENDAR_EVENT_COUNT,
): List<CalendarEvent> {
    if (limit <= 0 || instances.isEmpty()) return emptyList()
    return instances
        .asSequence()
        .filter { it.endMillis > nowMillis }
        .distinctBy { Triple(it.eventId, it.beginMillis, it.endMillis) }
        .sortedWith(
            compareBy<CalendarEvent> { it.beginMillis }
                .thenBy { it.endMillis }
                .thenBy { it.eventId },
        )
        .take(limit)
        .toList()
}

/**
 * Today's date always, and the next [CALENDAR_EVENT_COUNT] events where `READ_CALENDAR` is granted
 * (FR-59).
 *
 * **Nothing is cached or persisted (NFR-S4).** Every refresh re-queries the provider and replaces
 * the state wholesale; [stop] drops the events entirely. There is no disk write, no in-memory
 * carry-over across a stop, and no copy of an event outside the current [state] value. Revoking the
 * permission therefore empties the widget on the next refresh rather than leaving stale rows.
 *
 * The permission is re-checked on every refresh rather than remembered, so a revocation mid-session
 * takes effect without the launcher having to observe anything.
 *
 * The provider query runs on [background] (NFR-P5); only the `MutableStateFlow` write crosses back,
 * and that is thread-safe.
 */
class CalendarFeed(
    private val source: CalendarSource,
    private val ticker: TimeTicker,
    private val background: BackgroundRunner = BackgroundRunner.Immediate,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) : LifecycleTodayFeed() {

    private val mutable = MutableStateFlow<CalendarFeedState>(
        CalendarFeedState.PermissionRequired(today()),
    )

    /** Date plus upcoming events. Safe to collect before the permission exists. */
    val state: StateFlow<CalendarFeedState> = mutable.asStateFlow()

    private var subscription: TickSubscription? = null

    override fun start() {
        if (subscription != null) return
        // Refreshing on the ticker keeps the date correct across midnight and drops events as they
        // finish. It costs one provider query per minute while the launcher is actually on screen,
        // and none at all once it is stopped.
        subscription = ticker.subscribe(::refresh)
        refresh()
    }

    override fun stop() {
        subscription?.cancel()
        subscription = null
        // Drop the events rather than leaving them in the flow: a stopped launcher holds no
        // calendar data at all (NFR-S4).
        mutable.value = CalendarFeedState.PermissionRequired(today())
    }

    /** Re-reads the permission and re-queries. Safe to call from the main thread. */
    fun refresh() {
        val today = today()
        if (!source.isPermissionGranted()) {
            mutable.value = CalendarFeedState.PermissionRequired(today)
            return
        }
        background.execute {
            val now = clock()
            val until = now + CALENDAR_LOOKAHEAD_DAYS * MILLIS_PER_DAY
            val instances = runCatching { source.query(now, until) }.getOrNull()
            mutable.value = when {
                // Re-check: the permission can be revoked between the check above and the query.
                !source.isPermissionGranted() -> CalendarFeedState.PermissionRequired(today)
                instances == null -> CalendarFeedState.Unavailable(today)
                else -> CalendarFeedState.Events(today, selectUpcomingEvents(instances, now))
            }
        }
    }

    private fun today(): LocalDate = Instant.ofEpochMilli(clock()).atZone(zone()).toLocalDate()

    private companion object {
        const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
    }
}
