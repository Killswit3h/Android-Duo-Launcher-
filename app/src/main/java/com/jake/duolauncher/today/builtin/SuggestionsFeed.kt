package com.jake.duolauncher.today.builtin

import com.jake.duolauncher.history.ProfileAppId
import com.jake.duolauncher.history.SuggestionRanker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How many apps the App Suggestions widget shows (FR-59: "4 or 8 apps"), which follows its size.
 */
enum class SuggestionSlots(val count: Int) {
    /** The 4×2 medium widget. */
    FOUR(4),

    /** The 4×4 large widget. */
    EIGHT(8),
}

/**
 * Apps for the App Suggestions widget (FR-59), taken from local launch history.
 *
 * **There is no ranking logic here.** The scoring, the time-of-day boost and the hidden-app,
 * private-space and uninstalled-app exclusions all live in `history.DefaultSuggestionRanker`, which
 * is already built and tested; this feed only decides when to ask it and how many to ask for. That
 * keeps one ranker in the codebase, so App Suggestions, the App Library and smart stack rotation
 * cannot drift apart.
 *
 * Refreshing on the ticker matters because the ranking is time-of-day sensitive: the same history
 * yields different suggestions at 09:00 and at 21:00, and the widget should follow that without the
 * user reopening the launcher.
 *
 * The ranker returns an empty list while the user has Suggestions turned off (FR-84), so no extra
 * gate is needed here.
 */
class SuggestionsFeed(
    private val ranker: SuggestionRanker,
    private val ticker: TimeTicker,
    slots: SuggestionSlots = SuggestionSlots.EIGHT,
    private val clock: () -> Long = System::currentTimeMillis,
) : LifecycleTodayFeed() {

    private val mutable = MutableStateFlow<List<ProfileAppId>>(emptyList())

    /** The suggested apps, best first, at most [slots] of them. */
    val state: StateFlow<List<ProfileAppId>> = mutable.asStateFlow()

    var slots: SuggestionSlots = slots
        private set

    private var subscription: TickSubscription? = null

    override fun start() {
        if (subscription != null) return
        subscription = ticker.subscribe(::refresh)
        refresh()
    }

    override fun stop() {
        subscription?.cancel()
        subscription = null
        mutable.value = emptyList()
    }

    /** Resizes the widget's app list. Re-ranks immediately while started. */
    fun setSlots(slots: SuggestionSlots) {
        if (this.slots == slots) return
        this.slots = slots
        if (subscription != null) refresh()
    }

    /** Re-ranks now. Call after a launch so the widget reflects it without waiting for a tick. */
    fun refresh() {
        mutable.value = runCatching { ranker.suggestions(slots.count, clock()) }
            .getOrDefault(emptyList())
            .take(slots.count)
    }
}
