package com.jake.duolauncher.history

/**
 * A persisted app identity, as produced by `profileAppId` in the root package: the flattened
 * component for personal apps, and a profile-qualified string for every other profile. The build
 * plan's internal contract names this type `ProfileAppId`.
 */
typealias ProfileAppId = String

/** The most recent launches kept on the device (FR-84). Older events fall off the ring buffer. */
const val LAUNCH_HISTORY_LIMIT = 500

/** Recorded when a launch arrives with a legacy personal identity that carries no profile serial. */
const val UNKNOWN_USER_SERIAL = -1L

/**
 * One local launch: which app, which profile, when. Nothing else is recorded, nothing leaves the
 * device, and this never reaches a layout export (NFR-S4, NFR-S7).
 */
data class LaunchEvent(val appId: ProfileAppId, val userSerial: Long, val epochMillis: Long)

/**
 * Launch history storage. Implementations keep at most [LAUNCH_HISTORY_LIMIT] events, ignore
 * [append] while recording is off, and delete everything already recorded when recording is turned
 * off, so "Suggestions off" leaves nothing behind (FR-84).
 */
interface LaunchHistory {
    /** Oldest first, newest last. */
    fun events(): List<LaunchEvent>

    /** Records one launch. Ignored while [enabled] is false. */
    fun append(event: LaunchEvent)

    /** Deletes every recorded event. */
    fun clear()

    fun enabled(): Boolean

    /** false stops recording and deletes the history that already exists (FR-84). */
    fun setEnabled(enabled: Boolean)
}

/**
 * The ring buffer itself, free of Android types so the ranking path is testable on the JVM. The
 * persistent [LaunchHistoryStore] uses this for its in-memory state and adds the storage.
 */
class InMemoryLaunchHistory(
    private val limit: Int = LAUNCH_HISTORY_LIMIT,
    events: List<LaunchEvent> = emptyList(),
) : LaunchHistory {
    private val buffer = ArrayDeque<LaunchEvent>()
    private var recording = true

    init {
        require(limit > 0) { "Launch history needs room for at least one event" }
        replaceWith(events)
    }

    @Synchronized override fun events(): List<LaunchEvent> = buffer.toList()

    @Synchronized override fun append(event: LaunchEvent) {
        if (!recording) return
        buffer.addLast(event)
        while (buffer.size > limit) buffer.removeFirst()
    }

    @Synchronized override fun clear() = buffer.clear()

    @Synchronized override fun enabled(): Boolean = recording

    @Synchronized override fun setEnabled(enabled: Boolean) {
        recording = enabled
        if (!enabled) buffer.clear()
    }

    /** Replaces the contents with the most recent [limit] of [events], oldest first. */
    @Synchronized fun replaceWith(events: List<LaunchEvent>) {
        buffer.clear()
        buffer.addAll(if (events.size > limit) events.takeLast(limit) else events)
    }
}

private const val FIELD_SEPARATOR = ''
private const val RECORD_SEPARATOR = '\n'

/**
 * Line-per-event storage form. Profile identities contain ':' and '/', so the fields are separated
 * by a control character no component name can hold; events that cannot be represented are dropped
 * rather than escaped.
 */
internal fun encodeLaunchEvents(events: List<LaunchEvent>): String = buildString {
    for (event in events) {
        if (!event.appId.isStorable()) continue
        if (isNotEmpty()) append(RECORD_SEPARATOR)
        append(event.appId).append(FIELD_SEPARATOR)
            .append(event.userSerial).append(FIELD_SEPARATOR)
            .append(event.epochMillis)
    }
}

private fun ProfileAppId.isStorable(): Boolean =
    isNotBlank() && none { it == FIELD_SEPARATOR || it == RECORD_SEPARATOR }

/** Reads [encodeLaunchEvents] output, skipping anything malformed and keeping the newest [limit]. */
internal fun decodeLaunchEvents(stored: String?, limit: Int = LAUNCH_HISTORY_LIMIT): List<LaunchEvent> {
    if (stored.isNullOrBlank()) return emptyList()
    val events = ArrayList<LaunchEvent>()
    for (line in stored.split(RECORD_SEPARATOR)) {
        if (line.isBlank()) continue
        val fields = line.split(FIELD_SEPARATOR)
        if (fields.size != 3) continue
        val appId = fields[0].takeIf { it.isNotBlank() } ?: continue
        val userSerial = fields[1].toLongOrNull() ?: continue
        val epochMillis = fields[2].toLongOrNull()?.takeIf { it >= 0 } ?: continue
        events += LaunchEvent(appId, userSerial, epochMillis)
    }
    return if (events.size > limit) events.takeLast(limit) else events
}
