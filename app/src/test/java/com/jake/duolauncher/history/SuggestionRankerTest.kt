package com.jake.duolauncher.history

import com.jake.duolauncher.profileAppId
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private val ZONE: ZoneId = ZoneId.of("UTC")

private const val ALPHA = "com.alpha/.Main"
private const val ZULU = "com.zulu/.Main"

/** Local wall-clock helper: day 0 hour 0 is the epoch, and the test zone has no offset. */
private fun at(day: Int, hour: Int, minute: Int = 0): Long =
    ((day * 24L + hour) * 60L + minute) * 60_000L

private fun launch(app: String, day: Int, hour: Int, minute: Int = 0) =
    LaunchEvent(app, 0L, at(day, hour, minute))

private fun ranker(
    events: List<LaunchEvent> = emptyList(),
    exclusions: SuggestionExclusions = SuggestionExclusions(),
    clock: () -> Long = { 0L },
) = DefaultSuggestionRanker(InMemoryLaunchHistory(events = events), exclusions, clock) { ZONE }

class SuggestionRankerTest {
    @Test fun emptyHistoryHasNoSuggestions() {
        assertEquals(emptyList<ProfileAppId>(), ranker().suggestions(4, at(1, 9)))
    }

    @Test fun recentLaunchesOutrankOlderOnesAtTheSameCount() {
        val events = listOf(
            launch(ALPHA, 1, 9), launch(ALPHA, 1, 10), launch(ALPHA, 1, 11),
            launch(ZULU, 5, 9), launch(ZULU, 5, 10), launch(ZULU, 5, 11),
        )
        assertEquals(listOf(ZULU, ALPHA), ranker(events).suggestions(4, at(5, 12)))
    }

    @Test fun moreLaunchesOutrankFewerAtSimilarRecency() {
        val events = listOf(
            launch(ALPHA, 2, 11, 55),
            launch(ZULU, 2, 9), launch(ZULU, 2, 10), launch(ZULU, 2, 11), launch(ZULU, 2, 11, 30),
        )
        assertEquals(listOf(ZULU, ALPHA), ranker(events).suggestions(4, at(2, 12)))
    }

    @Test fun launchesAroundThisTimeOfDayAreBoostedAheadOfMoreRecentOnes() {
        val morningAndEvening = listOf(
            launch(ZULU, 1, 9), launch(ZULU, 2, 9),
            launch(ALPHA, 1, 15), launch(ALPHA, 2, 15),
        )
        // The 15:00 launches are the more recent ones, yet at 09:00 the morning app leads.
        assertEquals(listOf(ZULU, ALPHA), ranker(morningAndEvening).suggestions(2, at(3, 9)))
        // The same history ranked six hours later flips: this is the signal smart rotate uses.
        assertEquals(listOf(ALPHA, ZULU), ranker(morningAndEvening).suggestions(2, at(3, 15)))
    }

    @Test fun theTimeOfDayBoostFadesOutAcrossItsWindow() {
        val noon = at(1, 12)
        assertEquals(1.0, timeOfDayAffinity(noon, noon, ZONE), 1e-9)
        assertEquals(0.5, timeOfDayAffinity(at(1, 12, 45), noon, ZONE), 1e-9)
        assertEquals(0.0, timeOfDayAffinity(at(1, 14), noon, ZONE), 1e-9)
        // Measured around the clock, not across the number line.
        assertEquals(
            timeOfDayAffinity(at(1, 23, 50), at(1, 0, 10), ZONE),
            timeOfDayAffinity(at(1, 0, 30), at(1, 0, 10), ZONE),
            1e-9,
        )
    }

    @Test fun identicalHistoriesBreakTiesByIdentityWhateverTheInsertionOrder() {
        val first = listOf(launch(ALPHA, 1, 9), launch(ZULU, 1, 9), launch(ALPHA, 1, 10), launch(ZULU, 1, 10))
        val second = listOf(launch(ZULU, 1, 10), launch(ALPHA, 1, 10), launch(ZULU, 1, 9), launch(ALPHA, 1, 9))
        assertEquals(listOf(ALPHA, ZULU), ranker(first).suggestions(2, at(1, 11)))
        assertEquals(listOf(ALPHA, ZULU), ranker(second).suggestions(2, at(1, 11)))
    }

    @Test fun nearIdenticalScoresBreakTiesByTheMostRecentLaunch() {
        val now = at(1, 9)
        val events = listOf(
            LaunchEvent(ALPHA, 0L, now - 2),
            LaunchEvent(ZULU, 0L, now - 1),
        )
        // Identity order would put alpha first; the more recent launch wins instead.
        assertEquals(listOf(ZULU, ALPHA), ranker(events).suggestions(2, now))
    }

    @Test fun rankingIsStableWhileTheClockAdvances() {
        val events = listOf(launch(ALPHA, 1, 9), launch(ZULU, 1, 9), launch(ALPHA, 1, 10), launch(ZULU, 1, 10))
        val ranker = ranker(events)
        val expected = ranker.suggestions(2, at(1, 11))
        for (drift in longArrayOf(1, 250, 500, 999, 60_000)) {
            assertEquals(expected, ranker.suggestions(2, at(1, 11) + drift))
        }
    }

    @Test fun hiddenAppsAreNeverSuggested() {
        val events = listOf(launch(ZULU, 1, 9), launch(ZULU, 1, 10), launch(ALPHA, 1, 9))
        val exclusions = SuggestionExclusions(isHidden = { it == ZULU })
        assertEquals(listOf(ALPHA), ranker(events, exclusions).suggestions(4, at(1, 11)))
    }

    @Test fun privateSpaceAppsAreNeverSuggestedWhileLocked() {
        val private = profileAppId("com.private/.Main", userSerial = 77, personalSerial = 0)
        val events = listOf(launch(private, 1, 10), launch(private, 1, 10, 30), launch(ALPHA, 1, 9))
        var locked = true
        val exclusions = SuggestionExclusions(isPrivateSpaceLocked = { it == private && locked })
        val ranker = ranker(events, exclusions)
        assertEquals(listOf(ALPHA), ranker.suggestions(4, at(1, 11)))
        locked = false
        assertEquals(listOf(private, ALPHA), ranker.suggestions(4, at(1, 11)))
    }

    @Test fun uninstalledAppsAreNeverSuggested() {
        val gone = "com.removed/.Main"
        val events = listOf(launch(gone, 1, 10), launch(gone, 1, 10, 30), launch(ALPHA, 1, 9))
        val exclusions = SuggestionExclusions(isInstalled = { it != gone })
        assertEquals(listOf(ALPHA), ranker(events, exclusions).suggestions(4, at(1, 11)))
    }

    @Test fun excludedAppsDoNotConsumeASuggestionSlot() {
        val gone = "com.removed/.Main"
        val events = listOf(
            launch(gone, 1, 10), launch(gone, 1, 10, 30),
            launch(ZULU, 1, 10), launch(ALPHA, 1, 9),
        )
        val exclusions = SuggestionExclusions(isInstalled = { it != gone })
        assertEquals(listOf(ZULU, ALPHA), ranker(events, exclusions).suggestions(2, at(1, 11)))
    }

    @Test fun theRequestedCountIsHonoured() {
        val events = listOf(launch(ALPHA, 1, 9), launch(ZULU, 1, 10))
        val ranker = ranker(events)
        assertEquals(emptyList<ProfileAppId>(), ranker.suggestions(0, at(1, 11)))
        assertEquals(emptyList<ProfileAppId>(), ranker.suggestions(-1, at(1, 11)))
        assertEquals(1, ranker.suggestions(1, at(1, 11)).size)
        assertEquals(2, ranker.suggestions(8, at(1, 11)).size)
    }

    @Test fun recordingStoresOneEventPerLaunch() {
        val history = InMemoryLaunchHistory()
        var now = at(1, 9)
        val ranker = DefaultSuggestionRanker(history, clock = { now }, zone = { ZONE })
        ranker.record(ALPHA)
        now = at(1, 10)
        ranker.record(ALPHA)
        assertEquals(listOf(at(1, 9), at(1, 10)), history.events().map(LaunchEvent::epochMillis))
        assertEquals(listOf(ALPHA), ranker.suggestions(4, at(1, 11)))
    }

    @Test fun recordedEventsCarryTheProfileTheLaunchCameFrom() {
        val history = InMemoryLaunchHistory()
        val work = profileAppId("com.example/.Main", userSerial = 42, personalSerial = 0)
        val ranker = DefaultSuggestionRanker(history, clock = { at(1, 9) }, zone = { ZONE })
        ranker.record(work)
        ranker.record(ALPHA)
        ranker.record(ALPHA, userSerial = 10)
        assertEquals(listOf(42L, UNKNOWN_USER_SERIAL, 10L), history.events().map(LaunchEvent::userSerial))
    }

    @Test fun blankIdentitiesAreNeverRecorded() {
        val history = InMemoryLaunchHistory()
        DefaultSuggestionRanker(history, clock = { at(1, 9) }, zone = { ZONE }).record("   ")
        assertTrue(history.events().isEmpty())
    }

    @Test fun turningSuggestionsOffStopsRecordingAndDeletesTheHistory() {
        val history = InMemoryLaunchHistory(events = listOf(launch(ALPHA, 1, 9), launch(ZULU, 1, 10)))
        val ranker = DefaultSuggestionRanker(history, clock = { at(1, 11) }, zone = { ZONE })
        ranker.setEnabled(false)
        assertFalse(ranker.isEnabled)
        assertTrue(history.events().isEmpty())
        ranker.record(ALPHA)
        assertTrue(history.events().isEmpty())
        assertEquals(emptyList<ProfileAppId>(), ranker.suggestions(4, at(1, 12)))
    }

    @Test fun turningSuggestionsBackOnStartsFromAnEmptyHistory() {
        val history = InMemoryLaunchHistory(events = listOf(launch(ALPHA, 1, 9)))
        var now = at(1, 11)
        val ranker = DefaultSuggestionRanker(history, clock = { now }, zone = { ZONE })
        ranker.setEnabled(false)
        ranker.setEnabled(true)
        assertTrue(ranker.isEnabled)
        assertEquals(emptyList<ProfileAppId>(), ranker.suggestions(4, now))
        now = at(1, 12)
        ranker.record(ZULU)
        assertEquals(listOf(ZULU), ranker.suggestions(4, at(1, 13)))
    }

    @Test fun clearingSuggestionHistoryLeavesRecordingOn() {
        val history = InMemoryLaunchHistory(events = listOf(launch(ALPHA, 1, 9)))
        var now = at(1, 11)
        val ranker = DefaultSuggestionRanker(history, clock = { now }, zone = { ZONE })
        ranker.clear()
        assertEquals(emptyList<ProfileAppId>(), ranker.suggestions(4, now))
        assertTrue(ranker.isEnabled)
        now = at(1, 12)
        ranker.record(ALPHA)
        assertEquals(listOf(ALPHA), ranker.suggestions(4, at(1, 13)))
    }

    @Test fun theRingBufferBoundsWhatTheRankerEverSees() {
        val history = InMemoryLaunchHistory()
        val ranker = DefaultSuggestionRanker(history, clock = { at(1, 9) }, zone = { ZONE })
        repeat(LAUNCH_HISTORY_LIMIT + 50) { ranker.record(ALPHA) }
        assertEquals(LAUNCH_HISTORY_LIMIT, history.events().size)
    }
}
