package com.jake.duolauncher.today.builtin

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The instant the Clock widget is showing, with the zone it should be read in.
 *
 * [epochMillis] and [zone] are the identity; the derived fields are conveniences so the widget does
 * no date maths of its own. A second hand is deliberately **not** driven by this type: see
 * [ClockFeed] for why, and use [secondOfMinute] as the anchor for a locally animated one.
 */
data class ClockTime(val epochMillis: Long, val zone: ZoneId) {
    /** Resolved once at construction; excluded from equality, which is [epochMillis] and [zone]. */
    val dateTime: ZonedDateTime = Instant.ofEpochMilli(epochMillis).atZone(zone)

    /** 0..23, for a 24-hour digital face. */
    val hour24: Int get() = dateTime.hour

    /** 1..12, for a 12-hour digital face and for an analog hour hand. */
    val hour12: Int get() = ((hour24 + 11) % 12) + 1

    val minute: Int get() = dateTime.minute

    /** Whole seconds past the minute at the moment this tick was taken. */
    val secondOfMinute: Int get() = dateTime.second

    val isBeforeNoon: Boolean get() = hour24 < 12
}

/**
 * Current time for the Clock widget (FR-59), analog or digital.
 *
 * **Why there is no timer here.** The feed republishes on each [TimeTicker] tick, which is the
 * system's minute-boundary broadcast, so an idle launcher does no periodic work at all. Minute
 * granularity is everything a digital face and an analog hour/minute hand need.
 *
 * A sweeping second hand needs finer granularity than any broadcast provides, and polling for it
 * would burn a wake-up per second for a surface that is usually not even visible. So this feed
 * publishes the exact instant of each tick and an analog face animates its own second hand from
 * Compose's frame clock, anchored on [ClockTime.epochMillis] and [ClockTime.secondOfMinute]. The
 * animation is then free, frame-synchronised, and stops with the composition.
 *
 * The zone is re-read on every tick, so a timezone change lands on the next broadcast; the Android
 * ticker subscribes to the timezone and date broadcasts as well as the minute one, so that is
 * immediate rather than up to a minute late.
 */
class ClockFeed(
    private val ticker: TimeTicker,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) : LifecycleTodayFeed() {

    private val mutable = MutableStateFlow(reading())

    /** The current time. Republished on each tick while started, frozen while stopped. */
    val state: StateFlow<ClockTime> = mutable.asStateFlow()

    private var subscription: TickSubscription? = null

    override fun start() {
        if (subscription != null) return
        subscription = ticker.subscribe(::publish)
        publish()
    }

    override fun stop() {
        subscription?.cancel()
        subscription = null
    }

    private fun publish() {
        mutable.value = reading()
    }

    private fun reading() = ClockTime(clock(), zone())
}
