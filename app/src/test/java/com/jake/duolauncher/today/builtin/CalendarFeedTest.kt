package com.jake.duolauncher.today.builtin

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private val ZONE: ZoneId = ZoneId.of("UTC")

private fun at(day: Int, hour: Int, minute: Int = 0): Long =
    ((day * 24L + hour) * 60L + minute) * 60_000L

private fun event(
    id: Long,
    startHour: Int,
    endHour: Int = startHour + 1,
    day: Int = 0,
    title: String = "Event $id",
) = CalendarEvent(
    eventId = id,
    title = title,
    beginMillis = at(day, startHour),
    endMillis = at(day, endHour),
    allDay = false,
    color = 0,
)

private class FakeCalendarSource(
    var granted: Boolean = true,
    var events: List<CalendarEvent>? = emptyList(),
) : CalendarSource {
    var queries = 0
        private set

    override fun isPermissionGranted(): Boolean = granted

    override fun query(fromMillis: Long, toMillis: Long): List<CalendarEvent>? {
        queries++
        return events
    }
}

private fun feed(
    source: FakeCalendarSource,
    ticker: FakeTicker = FakeTicker(),
    now: () -> Long = { at(0, 9) },
) = CalendarFeed(source, ticker, BackgroundRunner.Immediate, now) { ZONE }

class CalendarFeedTest {
    @Test fun withoutPermissionTheDateIsStillAvailableAndTheProviderIsNeverQueried() {
        val source = FakeCalendarSource(granted = false, events = listOf(event(1, 10)))

        val feed = feed(source).apply { start() }

        val state = feed.state.value
        assertTrue(state is CalendarFeedState.PermissionRequired)
        assertEquals(LocalDate.of(1970, 1, 1), state.today)
        assertEquals(0, source.queries)
    }

    @Test fun grantedPermissionPublishesTheNextThreeEventsSoonestFirst() {
        val source = FakeCalendarSource(
            events = listOf(event(3, 16), event(1, 10), event(4, 18), event(2, 12)),
        )

        val feed = feed(source).apply { start() }

        val state = feed.state.value as CalendarFeedState.Events
        assertEquals(listOf(1L, 2L, 3L), state.events.map(CalendarEvent::eventId))
    }

    @Test fun eventsThatAlreadyFinishedAreDroppedAndAnEventInProgressIsKept() {
        val inProgress = event(1, startHour = 8, endHour = 10)
        val finished = event(2, startHour = 6, endHour = 7)
        val source = FakeCalendarSource(events = listOf(finished, inProgress, event(3, 11)))

        val feed = feed(source).apply { start() }

        val state = feed.state.value as CalendarFeedState.Events
        assertEquals(listOf(1L, 3L), state.events.map(CalendarEvent::eventId))
        assertTrue(state.events.first().isInProgress(at(0, 9)))
    }

    @Test fun aProviderFailureIsUnavailableRatherThanAPermissionPrompt() {
        val source = FakeCalendarSource(events = null)

        val feed = feed(source).apply { start() }

        assertTrue(feed.state.value is CalendarFeedState.Unavailable)
    }

    @Test fun revokingPermissionEmptiesTheEventsOnTheNextRefresh() {
        val source = FakeCalendarSource(events = listOf(event(1, 10)))
        val feed = feed(source).apply { start() }
        assertTrue(feed.state.value is CalendarFeedState.Events)

        source.granted = false
        feed.refresh()

        assertTrue(feed.state.value is CalendarFeedState.PermissionRequired)
    }

    @Test fun eachTickRequeriesSoNothingIsEverCached() {
        val ticker = FakeTicker()
        val source = FakeCalendarSource(events = listOf(event(1, 10)))
        feed(source, ticker).start()
        assertEquals(1, source.queries)

        ticker.tick()
        ticker.tick()

        assertEquals(3, source.queries)
    }

    @Test fun stoppingUnsubscribesAndDropsEveryEventItWasHolding() {
        val ticker = FakeTicker()
        val source = FakeCalendarSource(events = listOf(event(1, 10)))
        val feed = feed(source, ticker).apply { start() }
        assertTrue(feed.state.value is CalendarFeedState.Events)

        feed.stop()
        val queriesWhenStopped = source.queries
        ticker.tick()

        assertEquals(0, ticker.subscriberCount)
        assertEquals(queriesWhenStopped, source.queries)
        assertTrue(feed.state.value is CalendarFeedState.PermissionRequired)
    }

    @Test fun selectionKeepsTheSoonestThreeAndBreaksTiesStably() {
        val sameStart = listOf(
            event(9, startHour = 10, endHour = 12),
            event(4, startHour = 10, endHour = 11),
            event(7, startHour = 10, endHour = 11),
        )

        val selected = selectUpcomingEvents(sameStart, at(0, 9))

        assertEquals(listOf(4L, 7L, 9L), selected.map(CalendarEvent::eventId))
    }

    @Test fun selectionDropsDuplicateInstancesOfTheSameOccurrence() {
        val duplicated = listOf(event(1, 10), event(1, 10), event(2, 12))

        val selected = selectUpcomingEvents(duplicated, at(0, 9))

        assertEquals(listOf(1L, 2L), selected.map(CalendarEvent::eventId))
    }

    @Test fun selectionKeepsEachOccurrenceOfARepeatingEvent() {
        val repeating = listOf(event(1, 10), event(1, 10, day = 1), event(1, 10, day = 2))

        val selected = selectUpcomingEvents(repeating, at(0, 9))

        assertEquals(3, selected.size)
        assertEquals(listOf(at(0, 10), at(1, 10), at(2, 10)), selected.map(CalendarEvent::beginMillis))
    }
}
