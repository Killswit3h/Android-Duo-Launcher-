package com.jake.duolauncher.today.builtin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val PLAYER = "com.example.player"

private fun snapshot(
    packageName: String = PLAYER,
    title: String? = "Cirrus",
    artist: String? = "Bonobo",
    albumArtist: String? = null,
    artwork: MediaArtwork? = null,
    isPlaying: Boolean = false,
    canPause: Boolean = true,
    canSkipNext: Boolean = true,
) = MediaSessionSnapshot(
    packageName = packageName,
    title = title,
    artist = artist,
    albumArtist = albumArtist,
    artwork = artwork,
    isPlaying = isPlaying,
    canPause = canPause,
    canSkipNext = canSkipNext,
)

private class RecordingTransport : MediaTransportControls {
    val calls = mutableListOf<String>()

    override fun play() {
        calls += "play"
    }

    override fun pause() {
        calls += "pause"
    }

    override fun skipToNext() {
        calls += "next"
    }
}

private class FakeMediaSource(
    var granted: Boolean = true,
    var entries: List<MediaSessionEntry> = emptyList(),
) : MediaSessionSource {
    var released = 0
        private set
    private var observer: (() -> Unit)? = null

    override fun isAccessGranted(): Boolean = granted

    override fun sessions(): List<MediaSessionEntry> = entries

    override fun observe(onChanged: () -> Unit) {
        observer = onChanged
    }

    override fun release() {
        released++
        observer = null
    }

    fun signalChange() = observer?.invoke()

    val observing: Boolean get() = observer != null
}

private fun entry(snapshot: MediaSessionSnapshot, transport: MediaTransportControls = RecordingTransport()) =
    MediaSessionEntry(snapshot, transport)

class NowPlayingFeedTest {
    @Test fun withoutNotificationAccessTheWidgetAsksForIt() {
        val source = FakeMediaSource(granted = false, entries = listOf(entry(snapshot())))

        val feed = NowPlayingFeed(source).apply { start() }

        assertEquals(NowPlayingState.AccessRequired, feed.state.value)
    }

    @Test fun accessWithNothingPlayingIsIdleRatherThanAPrompt() {
        val feed = NowPlayingFeed(FakeMediaSource(entries = emptyList())).apply { start() }

        assertEquals(NowPlayingState.Idle, feed.state.value)
    }

    @Test fun mapsTitleArtistAndArtwork() {
        val artwork = MediaArtwork("artwork-handle")
        val source = FakeMediaSource(
            entries = listOf(entry(snapshot(artwork = artwork, isPlaying = true))),
        )

        val feed = NowPlayingFeed(source).apply { start() }

        val track = (feed.state.value as NowPlayingState.Active).track
        assertEquals("Cirrus", track.title)
        assertEquals("Bonobo", track.artist)
        assertEquals(artwork, track.artwork)
        assertEquals(PLAYER, track.packageName)
        assertTrue(track.isPlaying)
    }

    @Test fun artistFallsBackToAlbumArtistForPodcastsAndVideo() {
        val mapped = nowPlayingTrack(snapshot(artist = "  ", albumArtist = "The Publisher"))

        assertEquals("The Publisher", mapped?.artist)
    }

    @Test fun aSessionWithNoTitleAndNoArtistIsNotWorthShowing() {
        assertNull(nowPlayingTrack(snapshot(title = null, artist = null, albumArtist = null)))
        assertNull(nowPlayingTrack(snapshot(title = "   ", artist = "")))
    }

    @Test fun titlesAndArtistsAreTrimmed() {
        val mapped = nowPlayingTrack(snapshot(title = "  Cirrus  ", artist = " Bonobo "))

        assertEquals("Cirrus", mapped?.title)
        assertEquals("Bonobo", mapped?.artist)
    }

    @Test fun aPlayingSessionWinsOverAnEarlierPausedOne() {
        val paused = snapshot(packageName = "com.example.paused", title = "Old", isPlaying = false)
        val playing = snapshot(packageName = "com.example.playing", title = "New", isPlaying = true)

        assertEquals(1, selectNowPlayingIndex(listOf(paused, playing)))
    }

    @Test fun withNothingPlayingTheFirstUsableSessionIsShown() {
        val empty = snapshot(title = null, artist = null)
        val usable = snapshot(packageName = "com.example.usable", title = "Paused track")

        assertEquals(1, selectNowPlayingIndex(listOf(empty, usable)))
        assertEquals(-1, selectNowPlayingIndex(listOf(empty)))
        assertEquals(-1, selectNowPlayingIndex(emptyList()))
    }

    @Test fun playPauseTogglesTheSelectedSession() {
        val transport = RecordingTransport()
        val source = FakeMediaSource(
            entries = listOf(entry(snapshot(isPlaying = true), transport)),
        )
        val feed = NowPlayingFeed(source).apply { start() }

        feed.togglePlayPause()

        assertEquals(listOf("pause"), transport.calls)
    }

    @Test fun playPauseResumesAPausedSession() {
        val transport = RecordingTransport()
        val source = FakeMediaSource(entries = listOf(entry(snapshot(isPlaying = false), transport)))
        val feed = NowPlayingFeed(source).apply { start() }

        feed.togglePlayPause()
        feed.skipToNext()

        assertEquals(listOf("play", "next"), transport.calls)
    }

    @Test fun transportIsIgnoredWhenNothingIsSelected() {
        val transport = RecordingTransport()
        val source = FakeMediaSource(granted = false, entries = listOf(entry(snapshot(), transport)))
        val feed = NowPlayingFeed(source).apply { start() }

        feed.togglePlayPause()
        feed.skipToNext()

        assertTrue(transport.calls.isEmpty())
    }

    @Test fun aSessionChangeRepublishes() {
        val source = FakeMediaSource(entries = emptyList())
        val feed = NowPlayingFeed(source).apply { start() }
        assertEquals(NowPlayingState.Idle, feed.state.value)

        source.entries = listOf(entry(snapshot(isPlaying = true)))
        source.signalChange()

        assertTrue(feed.state.value is NowPlayingState.Active)
    }

    @Test fun revokingAccessMidSessionClearsTheTrack() {
        val source = FakeMediaSource(entries = listOf(entry(snapshot(isPlaying = true))))
        val feed = NowPlayingFeed(source).apply { start() }
        assertTrue(feed.state.value is NowPlayingState.Active)

        source.granted = false
        feed.refresh()

        assertEquals(NowPlayingState.AccessRequired, feed.state.value)
    }

    @Test fun stoppingReleasesTheListenerAndHoldsNoTrack() {
        val source = FakeMediaSource(entries = listOf(entry(snapshot(isPlaying = true))))
        val feed = NowPlayingFeed(source).apply { start() }
        assertTrue(source.observing)

        feed.stop()

        assertEquals(1, source.released)
        assertFalse(source.observing)
        assertEquals(NowPlayingState.AccessRequired, feed.state.value)
    }

    @Test fun stoppedFeedIgnoresLateSessionChanges() {
        val source = FakeMediaSource(entries = listOf(entry(snapshot(isPlaying = true))))
        val feed = NowPlayingFeed(source).apply { start() }

        feed.stop()
        source.signalChange()

        assertEquals(NowPlayingState.AccessRequired, feed.state.value)
    }
}
