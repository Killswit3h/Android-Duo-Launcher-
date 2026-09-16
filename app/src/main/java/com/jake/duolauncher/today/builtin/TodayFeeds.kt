package com.jake.duolauncher.today.builtin

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.jake.duolauncher.DeviceStatus
import com.jake.duolauncher.history.SuggestionRanker
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow

/**
 * Every built-in Today View widget's data source, wired to the platform and to the launcher's
 * existing subsystems, started and stopped as one (FR-59).
 *
 * This is the only class the Compose widgets need: they read `feeds.clock.state`,
 * `feeds.calendar.state`, `feeds.battery.state`, `feeds.nowPlaying.state` and
 * `feeds.suggestions.state`, and call the two Now Playing transport methods.
 *
 * Nothing is duplicated to build these. The battery comes from the `DeviceStatusMonitor` the status
 * rail already runs, the suggestions come from the `DefaultSuggestionRanker` the launch history
 * already feeds, and the clock and calendar share one registered time-broadcast receiver.
 *
 * Attach it to a lifecycle exactly as `DeviceStatusMonitor` is attached, and every feed follows:
 * while the launcher is stopped nothing is registered, nothing is queried and no calendar or media
 * data is held.
 */
class TodayFeeds(
    context: Context,
    ranker: SuggestionRanker,
    deviceStatus: StateFlow<DeviceStatus>,
) : DefaultLifecycleObserver {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * One background thread for provider queries, so a slow or cold calendar provider can never
     * stall a frame (NFR-P5).
     */
    private val queries = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "duo-today-feeds").apply { isDaemon = true }
    }

    /** Shared with every other clock surface in the process; one receiver serves them all. */
    val ticker: TimeTicker = DuoTicker.of(context)

    val clock = ClockFeed(ticker)

    val calendar = CalendarFeed(
        source = SystemCalendarSource(context),
        ticker = ticker,
        background = BackgroundRunner { task -> queries.execute(task) },
    )

    val battery = BatteryFeed(deviceStatus, scope)

    val nowPlaying = NowPlayingFeed(SystemMediaSessionSource(context))

    val suggestions = SuggestionsFeed(ranker, ticker)

    private val feeds: List<TodayFeed> = listOf(clock, calendar, battery, nowPlaying, suggestions)

    override fun onStart(owner: LifecycleOwner) = feeds.forEach(TodayFeed::start)

    override fun onStop(owner: LifecycleOwner) = feeds.forEach(TodayFeed::stop)

    /** Releases the background thread and the battery collection. Call from `onDestroy`. */
    fun release() {
        feeds.forEach(TodayFeed::stop)
        scope.cancel()
        queries.shutdown()
    }
}
