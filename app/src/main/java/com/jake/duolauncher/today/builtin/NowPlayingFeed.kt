package com.jake.duolauncher.today.builtin

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Album artwork, carried opaquely.
 *
 * The platform hands back an `android.graphics.Bitmap`, but naming that type here would drag an
 * Android UI type into the data layer and make this whole package untestable on the JVM. So the
 * source wraps whatever it got and the widget unwraps it (`artwork.value as? Bitmap`). Nothing in
 * between ever looks inside, and the tests can put anything they like in it.
 */
@JvmInline
value class MediaArtwork(val value: Any)

/**
 * The facts a media session offers, already reduced to what the Now Playing widget needs.
 *
 * The source resolves the platform's action bitmask into [canPause] and [canSkipNext] so the
 * mapping rules below stay free of `PlaybackState` constants.
 */
data class MediaSessionSnapshot(
    val packageName: String,
    val title: String? = null,
    val artist: String? = null,
    /** Used when the app supplies no artist, as many podcast and video apps do. */
    val albumArtist: String? = null,
    val artwork: MediaArtwork? = null,
    val isPlaying: Boolean = false,
    val canPause: Boolean = false,
    val canSkipNext: Boolean = false,
)

/** What the Now Playing widget renders (FR-59): title, artist, artwork and the transport state. */
data class NowPlayingTrack(
    val packageName: String,
    val title: String,
    val artist: String,
    val artwork: MediaArtwork?,
    val isPlaying: Boolean,
    val canPause: Boolean,
    val canSkipNext: Boolean,
)

/** What the Now Playing widget should show. */
sealed interface NowPlayingState {
    /**
     * Notification access is off, so no media session is readable. The widget shows its **Turn on**
     * prompt (AC-49), which is the point-of-use explanation NFR-S2 requires.
     */
    data object AccessRequired : NowPlayingState

    /** Access is granted and nothing is playing. The widget shows its resting state. */
    data object Idle : NowPlayingState

    /** Access is granted and a session has usable metadata. Playing or paused; see [track]. */
    data class Active(val track: NowPlayingTrack) : NowPlayingState
}

/** Play, pause and next for one media session. */
interface MediaTransportControls {
    fun play()

    fun pause()

    fun skipToNext()
}

/** One active media session: what it is playing, and how to control it. */
class MediaSessionEntry(
    val snapshot: MediaSessionSnapshot,
    val controls: MediaTransportControls,
)

/**
 * Supplies the active media sessions, and reports whether it is allowed to.
 *
 * A seam so the selection and mapping rules stay JVM-testable and so this file holds no Android
 * types. [SystemMediaSessionSource] is the implementation, and its docs explain why reading media
 * sessions is separate from the badge path.
 */
interface MediaSessionSource {
    /** Whether notification access is currently granted. */
    fun isAccessGranted(): Boolean

    /** The active sessions, in the platform's own priority order. Empty when access is off. */
    fun sessions(): List<MediaSessionEntry>

    /** Reports changes to the active session list. Called at most once per source. */
    fun observe(onChanged: () -> Unit)

    /** Drops the observer and any platform listener behind it. */
    fun release()
}

/**
 * Turns a session snapshot into a renderable track, or null when there is nothing worth showing.
 *
 * A session with neither a title nor any form of artist is skipped: that is what a session parked by
 * a browser tab or a just-initialised player looks like, and showing an empty card for it is worse
 * than showing the resting state. Artist falls back to the album artist, which is where podcast and
 * video apps usually put the publisher.
 */
fun nowPlayingTrack(snapshot: MediaSessionSnapshot): NowPlayingTrack? {
    val title = snapshot.title?.trim().orEmpty()
    val artist = snapshot.artist?.trim().orEmpty()
        .ifEmpty { snapshot.albumArtist?.trim().orEmpty() }
    if (title.isEmpty() && artist.isEmpty()) return null
    if (snapshot.packageName.isBlank()) return null
    return NowPlayingTrack(
        packageName = snapshot.packageName,
        title = title,
        artist = artist,
        artwork = snapshot.artwork,
        isPlaying = snapshot.isPlaying,
        canPause = snapshot.canPause,
        canSkipNext = snapshot.canSkipNext,
    )
}

/**
 * Which session the widget follows: the first one that is actually playing, otherwise the first
 * with usable metadata.
 *
 * Preferring a playing session matters because the platform keeps paused sessions in the list long
 * after the user moved on, and the most recently used of those often outranks the one making sound.
 *
 * Returns -1 when no session is worth showing, so the caller can pair the index with its controls.
 */
fun selectNowPlayingIndex(snapshots: List<MediaSessionSnapshot>): Int {
    val usable = snapshots.indices.filter { nowPlayingTrack(snapshots[it]) != null }
    if (usable.isEmpty()) return -1
    return usable.firstOrNull { snapshots[it].isPlaying } ?: usable.first()
}

/**
 * Active media session metadata and transport controls for the Now Playing widget (FR-59).
 *
 * **How this stays clear of badge data (NFR-S3).** Media metadata and notification badges are two
 * different Android capabilities that happen to share one user-facing switch. Badges come from
 * `DuoNotificationListener`, which reads five non-content facts off each `StatusBarNotification` and
 * is not touched by this feed at all. Media comes from `MediaSessionManager.getActiveSessions`,
 * which returns `MediaController`s and never exposes a notification. The listener component is used
 * here only as the token that authorises that call; no code path in this package reads, receives or
 * retains a notification title, text or extras, and the listener gained no media responsibility.
 *
 * Nothing is persisted: the state is in-memory, it is cleared on [stop], and the app declares
 * `android:allowBackup="false"`, so no media metadata can reach a backup either.
 *
 * Access is re-read on every refresh, so revoking it empties the widget promptly, which is the
 * "Now Playing disappears within 2 s" row in the error table.
 */
class NowPlayingFeed(
    private val source: MediaSessionSource,
) : LifecycleTodayFeed() {

    private val mutable = MutableStateFlow<NowPlayingState>(NowPlayingState.AccessRequired)

    /** Current track, resting state, or the **Turn on** prompt. */
    val state: StateFlow<NowPlayingState> = mutable.asStateFlow()

    private var started = false
    private var observing = false
    private var controls: MediaTransportControls? = null

    override fun start() {
        if (started) return
        started = true
        if (!observing) {
            observing = true
            source.observe { if (started) refresh() }
        }
        refresh()
    }

    override fun stop() {
        if (!started) return
        started = false
        source.release()
        observing = false
        controls = null
        // Hold no track while stopped.
        mutable.value = NowPlayingState.AccessRequired
    }

    /** Re-reads access and the active sessions. */
    fun refresh() {
        if (!source.isAccessGranted()) {
            controls = null
            mutable.value = NowPlayingState.AccessRequired
            return
        }
        val entries = runCatching { source.sessions() }.getOrDefault(emptyList())
        val index = selectNowPlayingIndex(entries.map(MediaSessionEntry::snapshot))
        if (index < 0) {
            controls = null
            mutable.value = NowPlayingState.Idle
            return
        }
        val entry = entries[index]
        controls = entry.controls
        mutable.value = NowPlayingState.Active(checkNotNull(nowPlayingTrack(entry.snapshot)))
    }

    /**
     * Toggles the selected session, which is what the widget's single play/pause control does.
     * Ignored when nothing is selected.
     */
    fun togglePlayPause() {
        val active = state.value as? NowPlayingState.Active ?: return
        val transport = controls ?: return
        runCatching { if (active.track.isPlaying) transport.pause() else transport.play() }
        refresh()
    }

    /** Skips the selected session to the next track. Ignored when nothing is selected. */
    fun skipToNext() {
        if (state.value !is NowPlayingState.Active) return
        val transport = controls ?: return
        runCatching { transport.skipToNext() }
        refresh()
    }
}
