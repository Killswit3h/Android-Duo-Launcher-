package com.jake.duolauncher.history

import com.jake.duolauncher.profileAppId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val PERSONAL = "com.example/.MainActivity"
private fun event(app: String, millis: Long, serial: Long = 0L) = LaunchEvent(app, serial, millis)

class LaunchHistoryTest {
    @Test fun ringBufferKeepsTheMostRecentFiveHundredEvents() {
        val history = InMemoryLaunchHistory()
        repeat(600) { index -> history.append(event(PERSONAL, index.toLong())) }
        val events = history.events()
        assertEquals(LAUNCH_HISTORY_LIMIT, events.size)
        assertEquals(100L, events.first().epochMillis)
        assertEquals(599L, events.last().epochMillis)
    }

    @Test fun eventsAreOrderedOldestFirst() {
        val history = InMemoryLaunchHistory()
        history.append(event("com.a/.Main", 10))
        history.append(event("com.b/.Main", 20))
        history.append(event("com.c/.Main", 30))
        assertEquals(listOf(10L, 20L, 30L), history.events().map(LaunchEvent::epochMillis))
    }

    @Test fun seededEventsAreCappedAtTheLimit() {
        val seed = (1..10).map { event(PERSONAL, it.toLong()) }
        val history = InMemoryLaunchHistory(limit = 4, events = seed)
        assertEquals(listOf(7L, 8L, 9L, 10L), history.events().map(LaunchEvent::epochMillis))
    }

    @Test fun clearRemovesEverything() {
        val history = InMemoryLaunchHistory(events = listOf(event(PERSONAL, 1)))
        history.clear()
        assertTrue(history.events().isEmpty())
        assertTrue(history.enabled())
    }

    @Test fun appendIsIgnoredWhileRecordingIsOff() {
        val history = InMemoryLaunchHistory()
        history.setEnabled(false)
        history.append(event(PERSONAL, 1))
        assertTrue(history.events().isEmpty())
        assertFalse(history.enabled())
        history.setEnabled(true)
        history.append(event(PERSONAL, 2))
        assertEquals(listOf(2L), history.events().map(LaunchEvent::epochMillis))
    }

    @Test fun turningRecordingOffDeletesWhatWasRecorded() {
        val history = InMemoryLaunchHistory(events = (1..20).map { event(PERSONAL, it.toLong()) })
        history.setEnabled(false)
        assertTrue(history.events().isEmpty())
    }

    @Test fun storageRoundTripsPersonalAndProfileIdentities() {
        val work = profileAppId("com.example/.MainActivity", userSerial = 42, personalSerial = 0)
        val events = listOf(
            event(PERSONAL, 1_700_000_000_000, serial = UNKNOWN_USER_SERIAL),
            event(work, 1_700_000_060_000, serial = 42),
        )
        assertEquals(events, decodeLaunchEvents(encodeLaunchEvents(events)))
    }

    @Test fun storageSkipsMalformedRecordsAndKeepsTheNewest() {
        val stored = listOf(
            "com.a/.Main010",
            "",
            "com.b/.Main0",
            "com.c/.Mainnotaserial30",
            "com.d/.Main0notatime",
            "050",
            "com.f/.Main0-60",
            "com.g/.Main070",
        ).joinToString("\n")
        assertEquals(
            listOf("com.a/.Main" to 10L, "com.g/.Main" to 70L),
            decodeLaunchEvents(stored).map { it.appId to it.epochMillis },
        )
    }

    @Test fun storageDecodeAppliesTheRingBufferLimit() {
        val stored = encodeLaunchEvents((1..600).map { event(PERSONAL, it.toLong()) })
        val decoded = decodeLaunchEvents(stored)
        assertEquals(LAUNCH_HISTORY_LIMIT, decoded.size)
        assertEquals(600L, decoded.last().epochMillis)
    }

    @Test fun emptyOrMissingStorageDecodesToNothing() {
        assertTrue(decodeLaunchEvents(null).isEmpty())
        assertTrue(decodeLaunchEvents("").isEmpty())
        assertTrue(decodeLaunchEvents("   ").isEmpty())
    }

    @Test fun identitiesThatCannotBeStoredAreDropped() {
        val events = listOf(
            event("com.bad/.Main\nsecond01", 10),
            event("   ", 20),
            event(PERSONAL, 30),
        )
        assertEquals(listOf(PERSONAL), decodeLaunchEvents(encodeLaunchEvents(events)).map(LaunchEvent::appId))
    }
}
