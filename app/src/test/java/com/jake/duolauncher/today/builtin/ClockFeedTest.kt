package com.jake.duolauncher.today.builtin

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

private val ZONE: ZoneId = ZoneId.of("UTC")

/** Local wall-clock helper: day 0 hour 0 is the epoch, and the test zone has no offset. */
private fun at(hour: Int, minute: Int = 0, second: Int = 0): Long =
    ((hour * 60L + minute) * 60L + second) * 1000L

class ClockFeedTest {
    @Test fun publishesTheCurrentTimeOnStart() {
        var now = at(9, 41)
        val feed = ClockFeed(FakeTicker(), { now }, { ZONE })

        feed.start()

        assertEquals(9, feed.state.value.hour24)
        assertEquals(41, feed.state.value.minute)
    }

    @Test fun eachTickRepublishesWithoutAnyPolling() {
        val ticker = FakeTicker()
        var now = at(9, 41)
        val feed = ClockFeed(ticker, { now }, { ZONE })
        feed.start()

        now = at(9, 42)
        // Nothing has been published yet: the feed has no timer of its own.
        assertEquals(41, feed.state.value.minute)

        ticker.tick()

        assertEquals(42, feed.state.value.minute)
    }

    @Test fun stoppedFeedUnsubscribesAndStopsEmitting() {
        val ticker = FakeTicker()
        var now = at(9, 41)
        val feed = ClockFeed(ticker, { now }, { ZONE })
        feed.start()
        assertEquals(1, ticker.subscriberCount)

        feed.stop()
        now = at(23, 5)
        ticker.tick()

        assertEquals(0, ticker.subscriberCount)
        assertEquals(41, feed.state.value.minute)
    }

    @Test fun startingTwiceKeepsOneSubscription() {
        val ticker = FakeTicker()
        val feed = ClockFeed(ticker, { at(9, 41) }, { ZONE })

        feed.start()
        feed.start()

        assertEquals(1, ticker.subscriberCount)
    }

    @Test fun twelveHourFaceWrapsMidnightAndNoonCorrectly() {
        fun hour12At(hour: Int) = ClockTime(at(hour), ZONE).hour12

        assertEquals(12, hour12At(0))
        assertEquals(1, hour12At(1))
        assertEquals(12, hour12At(12))
        assertEquals(1, hour12At(13))
        assertEquals(11, hour12At(23))
    }

    @Test fun carriesTheSecondSoAnAnalogFaceCanAnchorItsOwnAnimation() {
        val time = ClockTime(at(9, 41, 37), ZONE)

        assertEquals(37, time.secondOfMinute)
        assertEquals(true, time.isBeforeNoon)
    }

    @Test fun zoneIsRereadOnEveryTick() {
        val ticker = FakeTicker()
        var zone = ZONE
        val feed = ClockFeed(ticker, { at(9, 41) }, { zone })
        feed.start()
        assertEquals(9, feed.state.value.hour24)

        zone = ZoneId.of("UTC+2")
        ticker.tick()

        assertEquals(11, feed.state.value.hour24)
    }
}
