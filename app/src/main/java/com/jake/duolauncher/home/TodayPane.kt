package com.jake.duolauncher.home

import android.content.Intent
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.jake.duolauncher.AppEntry
import com.jake.duolauncher.LauncherModel
import com.jake.duolauncher.LauncherState
import com.jake.duolauncher.MainActivity
import com.jake.duolauncher.design.currentDuoColors
import com.jake.duolauncher.today.TodayView
import com.jake.duolauncher.today.TodayWidgetActions
import com.jake.duolauncher.today.TodayWidgetKind
import com.jake.duolauncher.today.moveItem
import com.jake.duolauncher.today.rememberTodayWidgetData
import com.jake.duolauncher.today.suggestionSlotsFor
import java.time.ZoneId

/**
 * The Today View as Home hosts it (FR-55 to FR-59).
 *
 * `TodayView` is entirely parameter-driven and knows nothing about the launcher; this is the seam
 * that gives it the launcher's feeds, its icon pipeline, its model and its launch path. Keeping the
 * seam in `home/` rather than in `today/` is what lets the column keep its previews and its JVM
 * tests — nothing it depends on reaches back into `LauncherModel`.
 *
 * The column's ordering lives in `LeadingPageConfig.today` and is edited through
 * [LauncherModel.setTodayItems] with the *resolved* list, so a fresh install's default column
 * behaves identically to one the user has already arranged (see `TodayHosting.kt`).
 */
@Composable
internal fun TodayPane(
    state: LauncherState,
    model: LauncherModel,
    activity: MainActivity,
    appsById: Map<String, AppEntry>,
    onLaunch: (AppEntry, android.graphics.Rect?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val feeds = activity.todayFeeds
    val ids = remember(state.leadingPage.today) { todayIdsOrDefault(state.leadingPage.today) }
    val items = remember(ids) { todayItemsOf(ids) }

    // FR-59: App Suggestions shows 4 or 8 apps depending on its span, so the feed is told how many
    // to rank for. Done as an effect rather than during composition because it mutates the feed.
    LaunchedEffect(items) {
        items.firstOrNull { it.kind == TodayWidgetKind.SUGGESTIONS }
            ?.let { feeds.suggestions.setSlots(suggestionSlotsFor(it.size)) }
    }

    val data = rememberTodayWidgetData(feeds) { id -> appsById[id]?.label ?: id }
    val renderer = rememberIconRenderer()
    val iconStyle = LocalHomeAppearance.current.resolvedIconStyle(currentDuoColors())
    val ranker = remember(context) { DuoHost.ranker(context) }
    var editing by rememberSaveable { mutableStateOf(false) }

    val actions = remember(feeds, appsById, ranker) {
        TodayWidgetActions(
            onTogglePlayPause = feeds.nowPlaying::togglePlayPause,
            onSkipNext = feeds.nowPlaying::skipToNext,
            onGrantCalendarAccess = activity::requestCalendarAccess,
            onGrantNotificationAccess = activity::openNotificationAccess,
            onLaunchApp = { id ->
                appsById[id]?.let { app ->
                    // FR-84: a launch from Suggestions feeds the ranker like any other, or the
                    // widget would keep suggesting apps that opening from it never reinforces.
                    ranker.record(app.id, app.userSerial)
                    onLaunch(app, null)
                }
            },
            onOpenClock = { context.startSafely(Intent(AlarmClock.ACTION_SHOW_ALARMS)) },
            onOpenCalendar = {
                context.startSafely(
                    Intent(Intent.ACTION_VIEW)
                        .setData(CalendarContract.CONTENT_URI.buildUpon().appendPath("time").build()),
                )
            },
            onOpenBatterySettings = { context.startSafely(Intent(Intent.ACTION_POWER_USAGE_SUMMARY)) },
        )
    }

    TodayView(
        items = items,
        data = data,
        modifier = modifier,
        actions = actions,
        editing = editing,
        onEditingChange = { editing = it },
        // Every edit commits the whole resolved column, so the first edit to a default column
        // persists all of it rather than silently doing nothing (AC-47).
        onMove = { from, to -> model.setTodayItems(ids.moveItem(from, to)) },
        onRemove = { id -> model.setTodayItems(ids - id) },
        onAdd = { kind, size -> model.setTodayItems(todayIdsWithAdded(ids, kind, size)) },
        iconRenderer = renderer,
        iconStyle = iconStyle,
        use24Hour = DateFormat.is24HourFormat(context),
        zone = ZoneId.systemDefault(),
    )
}

/**
 * Starts a system destination without letting a missing handler take Home down.
 *
 * Every one of these targets is optional on a given build — an emulator with no clock app, a profile
 * with no calendar — and the widget's tap is a convenience, not a contract.
 */
private fun android.content.Context.startSafely(intent: Intent) {
    runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
