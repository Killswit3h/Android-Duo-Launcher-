package com.jake.duolauncher.today.builtin

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import com.jake.duolauncher.badges.NotificationAccess

/**
 * Reads the active media sessions for the Now Playing widget (FR-59).
 *
 * **Why this is not part of the badge listener, and cannot leak notification content (NFR-S3).**
 *
 * Android gates `MediaSessionManager.getActiveSessions` on an *enabled notification listener
 * component*, which is why Now Playing and badges share one user-facing switch. They do not share a
 * code path:
 *
 *  * Badges flow through `DuoNotificationListener`, which receives `StatusBarNotification`s and
 *    reads five non-content facts from each. It was not modified for this feature and has no media
 *    responsibility.
 *  * Media flows through this class, which calls `getActiveSessions` and receives
 *    `MediaController`s. A `MediaController` is not a notification; there is no API on it that
 *    returns a notification's title, text or extras, so this path structurally cannot read one.
 *
 * `NotificationAccess.listenerComponent` is used here purely as the authorisation token that call
 * requires. Nothing this class reads is written to disk, to `SharedPreferences` or to a log, and it
 * holds no state between calls, so no media metadata survives the process.
 */
class SystemMediaSessionSource(context: Context) : MediaSessionSource {
    private val appContext = context.applicationContext
    private val manager = runCatching {
        appContext.getSystemService(MediaSessionManager::class.java)
    }.getOrNull()

    private var listener: MediaSessionManager.OnActiveSessionsChangedListener? = null

    override fun isAccessGranted(): Boolean = NotificationAccess.isGranted(appContext)

    override fun sessions(): List<MediaSessionEntry> {
        if (!isAccessGranted()) return emptyList()
        val component = NotificationAccess.listenerComponent(appContext)
        val controllers = runCatching { manager?.getActiveSessions(component) }
            .getOrNull()
            .orEmpty()
        return controllers.mapNotNull(::entryFor)
    }

    override fun observe(onChanged: () -> Unit) {
        if (listener != null) return
        val sessions = manager ?: return
        val component = NotificationAccess.listenerComponent(appContext)
        val created = MediaSessionManager.OnActiveSessionsChangedListener { onChanged() }
        // Registering can throw when access is off; the feed simply refreshes on its own instead.
        runCatching {
            sessions.addOnActiveSessionsChangedListener(
                created,
                component,
                Handler(Looper.getMainLooper()),
            )
        }.onSuccess { listener = created }
    }

    override fun release() {
        val current = listener ?: return
        runCatching { manager?.removeOnActiveSessionsChangedListener(current) }
        listener = null
    }

    private fun entryFor(controller: MediaController): MediaSessionEntry? {
        val packageName = controller.packageName?.takeIf(String::isNotBlank) ?: return null
        val metadata = runCatching { controller.metadata }.getOrNull()
        val playback = runCatching { controller.playbackState }.getOrNull()
        val isPlaying = playback?.state == PlaybackState.STATE_PLAYING
        val actions = playback?.actions ?: 0L
        val snapshot = MediaSessionSnapshot(
            packageName = packageName,
            title = metadata.text(MediaMetadata.METADATA_KEY_TITLE)
                ?: metadata.text(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
            artist = metadata.text(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata.text(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
            albumArtist = metadata.text(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
            artwork = metadata.artwork(),
            isPlaying = isPlaying,
            canPause = supportsPlayPause(actions, isPlaying),
            canSkipNext = actions == 0L ||
                (actions and PlaybackState.ACTION_SKIP_TO_NEXT) != 0L,
        )
        return MediaSessionEntry(snapshot, ControllerTransport(controller))
    }

    /**
     * Whether the session accepts the widget's play/pause toggle in its current state.
     *
     * Plenty of apps publish an empty action mask and still respond to transport commands, so an
     * empty mask is treated as "supported" rather than greying out a control that would work.
     */
    private fun supportsPlayPause(actions: Long, isPlaying: Boolean): Boolean {
        if (actions == 0L) return true
        val needed = if (isPlaying) {
            PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE
        } else {
            PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PLAY_PAUSE
        }
        return (actions and needed) != 0L
    }

    private fun MediaMetadata?.text(key: String): String? =
        this?.let { runCatching { it.getText(key) }.getOrNull() }
            ?.toString()
            ?.trim()
            ?.takeIf(String::isNotEmpty)

    /** Album art, in the order the platform recommends, wrapped so the data layer stays opaque. */
    private fun MediaMetadata?.artwork(): MediaArtwork? {
        val metadata = this ?: return null
        val bitmap = runCatching {
            metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        }.getOrNull()
        return bitmap?.let(::MediaArtwork)
    }
}

/** Forwards the widget's controls to one session. Failures are swallowed by the feed. */
private class ControllerTransport(private val controller: MediaController) : MediaTransportControls {
    override fun play() {
        controller.transportControls.play()
    }

    override fun pause() {
        controller.transportControls.pause()
    }

    override fun skipToNext() {
        controller.transportControls.skipToNext()
    }
}
