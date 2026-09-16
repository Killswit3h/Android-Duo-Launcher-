package com.jake.duolauncher.history

import com.jake.duolauncher.parseProfileAppId
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoField
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Ranks apps for App Suggestions (FR-59, FR-68) and, through the same time-of-day signal, for smart
 * stack rotation (FR-63). Everything it knows comes from the local [LaunchHistory]; there is no
 * network path and no analytics.
 */
interface SuggestionRanker {
    fun record(app: ProfileAppId)
    fun suggestions(count: Int, at: Long = System.currentTimeMillis()): List<ProfileAppId>
    fun clear()
}

/**
 * Apps the ranker must never suggest. The rules are injected so the ranker does not reach into the
 * hidden-app, private-space or catalog subsystems: hidden apps (FR-75), private-space apps while
 * the container is locked (FR-77), and apps that are no longer installed.
 */
class SuggestionExclusions(
    val isInstalled: (ProfileAppId) -> Boolean = { true },
    val isHidden: (ProfileAppId) -> Boolean = { false },
    val isPrivateSpaceLocked: (ProfileAppId) -> Boolean = { false },
) {
    fun allows(app: ProfileAppId): Boolean =
        isInstalled(app) && !isHidden(app) && !isPrivateSpaceLocked(app)
}

/** A launch counts half as much once this much time has passed. */
const val SUGGESTION_HALF_LIFE_MILLIS: Long = 3L * 24 * 60 * 60 * 1000

/** How far from the query's time of day a launch can sit and still earn part of the boost. */
const val SUGGESTION_TIME_OF_DAY_WINDOW_MINUTES: Int = 90

/** A launch at exactly the query's time of day counts this much more than one at any other hour. */
const val SUGGESTION_TIME_OF_DAY_BOOST: Double = 1.5

private const val SCORE_RESOLUTION = 1_000_000.0
private const val MINUTES_PER_DAY = 24 * 60

/**
 * How closely a launch matches the time of day being ranked for: 1.0 at the same minute, falling
 * linearly to 0.0 at [SUGGESTION_TIME_OF_DAY_WINDOW_MINUTES], measured around the 24-hour clock so
 * 23:50 and 00:10 are twenty minutes apart.
 */
fun timeOfDayAffinity(eventMillis: Long, atMillis: Long, zone: ZoneId): Double {
    val apart = minutesApartOnTheClock(minuteOfDay(eventMillis, zone), minuteOfDay(atMillis, zone))
    return (1.0 - apart.toDouble() / SUGGESTION_TIME_OF_DAY_WINDOW_MINUTES).coerceAtLeast(0.0)
}

/** One launch's contribution: exponential recency decay, multiplied by the time-of-day boost. */
fun launchWeight(event: LaunchEvent, at: Long, zone: ZoneId): Double {
    val age = (at - event.epochMillis).coerceAtLeast(0L).toDouble()
    val recency = 2.0.pow(-age / SUGGESTION_HALF_LIFE_MILLIS)
    val affinity = timeOfDayAffinity(event.epochMillis, at, zone)
    return recency * (1.0 + SUGGESTION_TIME_OF_DAY_BOOST * affinity)
}

/**
 * The pure ranking core. An app's score is the sum of its launches' [launchWeight], so frequency
 * accumulates while recency and time-of-day decide what each launch is worth. Scores are compared
 * at a fixed resolution and ties fall back to the most recent launch and then to the identity, so
 * repeated calls return the same order and the UI never shuffles between frames.
 */
fun rankLaunchHistory(
    events: List<LaunchEvent>,
    count: Int,
    at: Long,
    zone: ZoneId,
    allows: (ProfileAppId) -> Boolean = { true },
): List<ProfileAppId> {
    if (count <= 0 || events.isEmpty()) return emptyList()
    val ranked = LinkedHashMap<ProfileAppId, Candidate>()
    val excluded = HashSet<ProfileAppId>()
    for (event in events) {
        val app = event.appId
        if (app.isBlank() || app in excluded) continue
        var candidate = ranked[app]
        if (candidate == null) {
            if (!allows(app)) {
                excluded += app
                continue
            }
            candidate = Candidate(app)
            ranked[app] = candidate
        }
        candidate.score += launchWeight(event, at, zone)
        candidate.lastLaunch = maxOf(candidate.lastLaunch, event.epochMillis)
    }
    return ranked.values
        .sortedWith(
            compareByDescending<Candidate> { it.score.quantized() }
                .thenByDescending(Candidate::lastLaunch)
                .thenBy(Candidate::app),
        )
        .take(count)
        .map(Candidate::app)
}

/**
 * The shipped ranker: local history in, app identities out.
 *
 * `record` is called once per app launch, so it does no work beyond a timestamp and handing the
 * event to the store, which persists it off the main thread.
 */
class DefaultSuggestionRanker(
    private val history: LaunchHistory,
    private val exclusions: SuggestionExclusions = SuggestionExclusions(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) : SuggestionRanker {

    /** False when the user turned Suggestions off; recording stays off until it is turned back on. */
    val isEnabled: Boolean get() = history.enabled()

    override fun record(app: ProfileAppId) {
        record(app, parseProfileAppId(app)?.userSerial ?: UNKNOWN_USER_SERIAL)
    }

    /** Preferred call site form: the launching surface already knows the profile serial. */
    fun record(app: ProfileAppId, userSerial: Long) {
        if (!history.enabled() || app.isBlank()) return
        history.append(LaunchEvent(app, userSerial, clock()))
    }

    override fun suggestions(count: Int, at: Long): List<ProfileAppId> =
        if (!history.enabled()) emptyList()
        else rankLaunchHistory(history.events(), count, at, zone(), exclusions::allows)

    override fun clear() = history.clear()

    /** Turning Suggestions off stops recording and deletes the history that exists (FR-84). */
    fun setEnabled(enabled: Boolean) {
        history.setEnabled(enabled)
        if (!enabled) history.clear()
    }
}

private class Candidate(
    val app: ProfileAppId,
    var score: Double = 0.0,
    var lastLaunch: Long = Long.MIN_VALUE,
)

private fun Double.quantized(): Long = (this * SCORE_RESOLUTION).roundToLong()

private fun minuteOfDay(epochMillis: Long, zone: ZoneId): Int =
    Instant.ofEpochMilli(epochMillis).atZone(zone).get(ChronoField.MINUTE_OF_DAY)

private fun minutesApartOnTheClock(first: Int, second: Int): Int {
    val apart = abs(first - second)
    return min(apart, MINUTES_PER_DAY - apart)
}
