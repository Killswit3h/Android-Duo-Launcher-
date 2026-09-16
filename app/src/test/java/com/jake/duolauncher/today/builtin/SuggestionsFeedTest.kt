package com.jake.duolauncher.today.builtin

import com.jake.duolauncher.history.DefaultSuggestionRanker
import com.jake.duolauncher.history.InMemoryLaunchHistory
import com.jake.duolauncher.history.LaunchEvent
import com.jake.duolauncher.history.ProfileAppId
import com.jake.duolauncher.history.SuggestionExclusions
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private val ZONE: ZoneId = ZoneId.of("UTC")

private fun at(day: Int, hour: Int, minute: Int = 0): Long =
    ((day * 24L + hour) * 60L + minute) * 60_000L

private fun app(index: Int): ProfileAppId = "com.example.app$index/.Main"

/** Ten apps, most recently launched first, so ranking order is app0, app1, app2, ... */
private fun tenApps(): List<LaunchEvent> =
    (0 until 10).map { index -> LaunchEvent(app(index), 0L, at(0, 9) - index * 60_000L) }

private fun ranker(
    events: List<LaunchEvent> = tenApps(),
    exclusions: SuggestionExclusions = SuggestionExclusions(),
) = DefaultSuggestionRanker(InMemoryLaunchHistory(events = events), exclusions, { at(0, 9) }) { ZONE }

class SuggestionsFeedTest {
    @Test fun theLargeWidgetGetsEightApps() {
        val feed = SuggestionsFeed(ranker(), FakeTicker(), SuggestionSlots.EIGHT) { at(0, 9) }

        feed.start()

        assertEquals(8, feed.state.value.size)
        assertEquals(app(0), feed.state.value.first())
    }

    @Test fun theMediumWidgetGetsFourApps() {
        val feed = SuggestionsFeed(ranker(), FakeTicker(), SuggestionSlots.FOUR) { at(0, 9) }

        feed.start()

        assertEquals(4, feed.state.value.size)
        assertEquals(listOf(app(0), app(1), app(2), app(3)), feed.state.value)
    }

    @Test fun resizingRepublishesImmediately() {
        val feed = SuggestionsFeed(ranker(), FakeTicker(), SuggestionSlots.FOUR) { at(0, 9) }
        feed.start()

        feed.setSlots(SuggestionSlots.EIGHT)

        assertEquals(SuggestionSlots.EIGHT, feed.slots)
        assertEquals(8, feed.state.value.size)
    }

    @Test fun shortHistoryYieldsFewerThanTheSlotCount() {
        val feed = SuggestionsFeed(
            ranker(events = listOf(LaunchEvent(app(1), 0L, at(0, 8)))),
            FakeTicker(),
            SuggestionSlots.EIGHT,
        ) { at(0, 9) }

        feed.start()

        assertEquals(listOf(app(1)), feed.state.value)
    }

    @Test fun hiddenAndUninstalledAppsAreNeverSuggested() {
        val exclusions = SuggestionExclusions(
            isInstalled = { it != app(1) },
            isHidden = { it == app(0) },
        )
        val feed = SuggestionsFeed(
            ranker(exclusions = exclusions),
            FakeTicker(),
            SuggestionSlots.FOUR,
        ) { at(0, 9) }

        feed.start()

        assertFalse(feed.state.value.contains(app(0)))
        assertFalse(feed.state.value.contains(app(1)))
        assertEquals(listOf(app(2), app(3), app(4), app(5)), feed.state.value)
    }

    @Test fun aLockedPrivateSpaceAppIsNeverSuggested() {
        val exclusions = SuggestionExclusions(isPrivateSpaceLocked = { it == app(0) })
        val feed = SuggestionsFeed(
            ranker(exclusions = exclusions),
            FakeTicker(),
            SuggestionSlots.FOUR,
        ) { at(0, 9) }

        feed.start()

        assertFalse(feed.state.value.contains(app(0)))
        assertEquals(4, feed.state.value.size)
    }

    @Test fun suggestionsTurnedOffLeavesTheWidgetEmpty() {
        val history = InMemoryLaunchHistory(events = tenApps())
        val ranker = DefaultSuggestionRanker(history, SuggestionExclusions(), { at(0, 9) }) { ZONE }
        val feed = SuggestionsFeed(ranker, FakeTicker(), SuggestionSlots.FOUR) { at(0, 9) }
        feed.start()
        assertTrue(feed.state.value.isNotEmpty())

        ranker.setEnabled(false)
        feed.refresh()

        assertEquals(emptyList<ProfileAppId>(), feed.state.value)
    }

    @Test fun eachTickRereanksSoTheTimeOfDaySignalStaysCurrent() {
        val ticker = FakeTicker()
        val morning = LaunchEvent(app(1), 0L, at(0, 9))
        val evening = LaunchEvent(app(2), 0L, at(0, 21))
        var now = at(1, 9)
        val ranker = DefaultSuggestionRanker(
            InMemoryLaunchHistory(events = listOf(morning, evening)),
            SuggestionExclusions(),
            { now },
        ) { ZONE }
        val feed = SuggestionsFeed(ranker, ticker, SuggestionSlots.FOUR) { now }
        feed.start()
        assertEquals(app(1), feed.state.value.first())

        now = at(1, 21)
        ticker.tick()

        assertEquals(app(2), feed.state.value.first())
    }

    @Test fun stoppingUnsubscribesAndClearsTheSuggestions() {
        val ticker = FakeTicker()
        val feed = SuggestionsFeed(ranker(), ticker, SuggestionSlots.FOUR) { at(0, 9) }
        feed.start()
        assertTrue(feed.state.value.isNotEmpty())

        feed.stop()
        ticker.tick()

        assertEquals(0, ticker.subscriberCount)
        assertEquals(emptyList<ProfileAppId>(), feed.state.value)
    }
}
