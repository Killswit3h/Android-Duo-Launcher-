package com.jake.duolauncher.today

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jake.duolauncher.history.ProfileAppId
import com.jake.duolauncher.today.builtin.BatteryReading
import com.jake.duolauncher.today.builtin.CalendarFeedState
import com.jake.duolauncher.today.builtin.ClockTime
import com.jake.duolauncher.today.builtin.NowPlayingState
import com.jake.duolauncher.today.builtin.TodayFeeds

/**
 * What every built-in widget draws, and what it can do — the seam between `today.builtin` (data)
 * and the composables in this package (UI).
 *
 * The widgets take plain values rather than the feeds themselves, so a preview or a screenshot test
 * can render any state without constructing a `ContentResolver` or a media session, and so no
 * widget can accidentally start a subscription of its own.
 */

/** A suggested app, paired with the label TalkBack reads and the tile shows (FR-59, NFR-A1). */
@Immutable
data class TodaySuggestion(val app: ProfileAppId, val label: String)

/**
 * A snapshot of all five feeds.
 *
 * Deliberately not marked `@Immutable`: [suggestions] is a `List`, which Compose cannot prove
 * immutable, and claiming otherwise would let it skip a recomposition that actually mattered. The
 * column holds at most a handful of widgets, so the recomposition cost is irrelevant next to the
 * risk of a stale frame.
 */
data class TodayWidgetData(
    val clock: ClockTime,
    val calendar: CalendarFeedState,
    val battery: BatteryReading,
    val nowPlaying: NowPlayingState,
    val suggestions: List<TodaySuggestion>,
)

/**
 * Everything a widget can ask the host to do.
 *
 * The two access callbacks are why widgets never touch the permission system themselves: a
 * composable cannot launch a settings screen or request a runtime permission without dragging an
 * Activity into it, and NFR-S2 wants that decision made once, by the host, at the point of use. The
 * widget's job is to render the **Turn on** prompt and report the tap.
 *
 * `@Stable` so that passing the same instance does not invalidate every widget in the column.
 */
@Stable
class TodayWidgetActions(
    /** Play or pause the session Now Playing is following. */
    val onTogglePlayPause: () -> Unit = {},
    /** Skip that session to its next track. */
    val onSkipNext: () -> Unit = {},
    /** Host launches the `READ_CALENDAR` request (AC-49's sibling for the Calendar widget). */
    val onGrantCalendarAccess: () -> Unit = {},
    /** Host launches the notification-access settings screen (AC-49). */
    val onGrantNotificationAccess: () -> Unit = {},
    /** Launch a suggested app. */
    val onLaunchApp: (ProfileAppId) -> Unit = {},
    /** Open the system clock app. */
    val onOpenClock: () -> Unit = {},
    /** Open the system calendar app. */
    val onOpenCalendar: () -> Unit = {},
    /** Open battery settings. */
    val onOpenBatterySettings: () -> Unit = {},
)

/**
 * Collects all five feeds into one snapshot, lifecycle-aware so a backgrounded launcher stops
 * collecting (the feeds have already stopped producing by then).
 *
 * [labelFor] resolves a suggestion's app id to its display label. It is a parameter rather than a
 * lookup here because the app list lives in `LauncherModel`, which this package must not depend on.
 */
@Composable
fun rememberTodayWidgetData(
    feeds: TodayFeeds,
    labelFor: (ProfileAppId) -> String,
): TodayWidgetData {
    val clock by feeds.clock.state.collectAsStateWithLifecycle()
    val calendar by feeds.calendar.state.collectAsStateWithLifecycle()
    val battery by feeds.battery.state.collectAsStateWithLifecycle()
    val nowPlaying by feeds.nowPlaying.state.collectAsStateWithLifecycle()
    val suggestions by feeds.suggestions.state.collectAsStateWithLifecycle()
    return TodayWidgetData(
        clock = clock,
        calendar = calendar,
        battery = battery,
        nowPlaying = nowPlaying,
        suggestions = suggestions.map { TodaySuggestion(it, labelFor(it)) },
    )
}
