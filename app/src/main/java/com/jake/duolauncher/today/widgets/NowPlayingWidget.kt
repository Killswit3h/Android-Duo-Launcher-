package com.jake.duolauncher.today.widgets

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jake.duolauncher.DuoTheme
import com.jake.duolauncher.design.DuoTokens
import com.jake.duolauncher.design.currentDuoColors
import com.jake.duolauncher.today.TodayWidgetSize
import com.jake.duolauncher.today.builtin.MediaArtwork
import com.jake.duolauncher.today.builtin.NowPlayingState
import com.jake.duolauncher.today.builtin.NowPlayingTrack

/**
 * The Now Playing widget (FR-59, AC-49): title, artist, artwork and a play/pause and next control.
 *
 * Three states, and the first one is the important one:
 *
 *  * [NowPlayingState.AccessRequired] — notification access is off, so no session is readable. The
 *    widget shows its inline **Turn on** prompt and reports the tap; it never opens settings itself.
 *  * [NowPlayingState.Idle] — access granted, nothing playing.
 *  * [NowPlayingState.Active] — the track, with transport.
 *
 * The transport buttons are real controls, so they are [TodayTouchTarget] square and carry their
 * own TalkBack labels (NFR-A1). That is also why this widget does not merge its semantics: merging
 * would fold the buttons into one node and put them out of TalkBack's reach entirely.
 *
 * The artwork arrives wrapped in [MediaArtwork] so the data layer never names an Android type; this
 * is the one place that unwraps it.
 */
@Composable
fun NowPlayingWidget(
    state: NowPlayingState,
    size: TodayWidgetSize,
    modifier: Modifier = Modifier,
    onTogglePlayPause: () -> Unit = {},
    onSkipNext: () -> Unit = {},
    onGrantAccess: () -> Unit = {},
    onClick: () -> Unit = {},
) {
    TodayWidgetFrame(size = size, modifier = modifier.clickable(onClick = onClick)) {
        TodayWidgetCaption(icon = Icons.Rounded.MusicNote, label = "Now Playing")
        when (state) {
            is NowPlayingState.AccessRequired -> TodayTurnOnPrompt(
                message = "See what's playing and control it from here.",
                actionLabel = "Turn on",
                accessibilityLabel = "Turn on notification access for Now Playing",
                onClick = onGrantAccess,
                compact = size == TodayWidgetSize.SMALL,
            )

            is NowPlayingState.Idle -> TodayWidgetPlaceholder(text = "Nothing playing")

            is NowPlayingState.Active -> ActiveTrack(
                track = state.track,
                size = size,
                onTogglePlayPause = onTogglePlayPause,
                onSkipNext = onSkipNext,
            )
        }
    }
}

@Composable
private fun ActiveTrack(
    track: NowPlayingTrack,
    size: TodayWidgetSize,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = currentDuoColors()
    val artwork = rememberArtwork(track.artwork)
    val stacked = size == TodayWidgetSize.SMALL

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(DuoTokens.space.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(DuoTokens.space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(artwork = artwork, size = if (stacked) ARTWORK_SMALL else ARTWORK_REGULAR)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title.ifBlank { "Unknown title" },
                    style = DuoTokens.type.subhead,
                    color = colors.label1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (track.artist.isNotBlank()) {
                    Text(
                        text = track.artist,
                        style = DuoTokens.type.caption2,
                        color = colors.label2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(DuoTokens.space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (track.canPause) {
                TransportButton(
                    onClick = onTogglePlayPause,
                    label = if (track.isPlaying) "Pause" else "Play",
                ) {
                    Icon(
                        imageVector = if (track.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = colors.label1,
                        modifier = Modifier.size(TRANSPORT_GLYPH),
                    )
                }
            }
            if (track.canSkipNext) {
                TransportButton(onClick = onSkipNext, label = "Next track") {
                    Icon(
                        imageVector = Icons.Rounded.SkipNext,
                        contentDescription = null,
                        tint = colors.label1,
                        modifier = Modifier.size(TRANSPORT_GLYPH),
                    )
                }
            }
        }
    }
}

/**
 * One transport control: a 48dp target with the label on the clickable node itself, so TalkBack
 * announces "Pause, button" rather than reading a decorative glyph.
 */
@Composable
private fun TransportButton(
    onClick: () -> Unit,
    label: String,
    content: @Composable () -> Unit,
) {
    TodayTransportSurface(
        modifier = Modifier.clickable(onClickLabel = label, role = Role.Button, onClick = onClick),
        content = content,
    )
}

@Composable
private fun Artwork(artwork: ImageBitmap?, size: Dp, modifier: Modifier = Modifier) {
    val colors = currentDuoColors()
    val shape = DuoTokens.radius.iconRadiusFor(size)
    if (artwork != null) {
        Image(
            bitmap = artwork,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(shape),
        )
    } else {
        Box(
            modifier = modifier.size(size).clip(shape).background(colors.glassTint.copy(alpha = ARTWORK_FALLBACK_ALPHA)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = colors.label2,
                modifier = Modifier.size(size / 2),
            )
        }
    }
}

/**
 * Unwraps the opaque [MediaArtwork] once per track rather than on every recomposition.
 *
 * Anything that is not a [Bitmap] — a test fixture, a future artwork type — simply yields null and
 * the fallback glyph, instead of throwing inside a composable.
 */
@Composable
private fun rememberArtwork(artwork: MediaArtwork?): ImageBitmap? = remember(artwork) {
    (artwork?.value as? Bitmap)?.asImageBitmap()
}

private val ARTWORK_SMALL = 36.dp
private val ARTWORK_REGULAR = 52.dp
private val TRANSPORT_GLYPH = 24.dp
private const val ARTWORK_FALLBACK_ALPHA = 0.30f

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

private val PreviewTrack = NowPlayingTrack(
    packageName = "com.example.music",
    title = "Everything In Its Right Place",
    artist = "Radiohead",
    artwork = null,
    isPlaying = true,
    canPause = true,
    canSkipNext = true,
)

@Preview(name = "Now Playing 2x2", widthDp = 200, heightDp = 200)
@Composable
private fun NowPlayingSmallPreview() {
    DuoTheme { TodayPreviewBackdrop { NowPlayingWidget(NowPlayingState.Active(PreviewTrack), TodayWidgetSize.SMALL, Modifier.size(180.dp)) } }
}

@Preview(name = "Now Playing 4x2", widthDp = 400, heightDp = 220)
@Composable
private fun NowPlayingMediumPreview() {
    DuoTheme { TodayPreviewBackdrop { NowPlayingWidget(NowPlayingState.Active(PreviewTrack), TodayWidgetSize.MEDIUM, Modifier.size(380.dp, 190.dp)) } }
}

@Preview(name = "Now Playing 4x4", widthDp = 400, heightDp = 420)
@Composable
private fun NowPlayingLargePreview() {
    DuoTheme { TodayPreviewBackdrop { NowPlayingWidget(NowPlayingState.Active(PreviewTrack), TodayWidgetSize.LARGE, Modifier.size(380.dp, 380.dp)) } }
}

@Preview(name = "Now Playing 4x6", widthDp = 400, heightDp = 600)
@Composable
private fun NowPlayingExtraLargePreview() {
    DuoTheme { TodayPreviewBackdrop { NowPlayingWidget(NowPlayingState.Active(PreviewTrack.copy(isPlaying = false)), TodayWidgetSize.EXTRA_LARGE, Modifier.size(380.dp, 560.dp)) } }
}

@Preview(name = "Now Playing, turn on", widthDp = 400, heightDp = 220)
@Composable
private fun NowPlayingAccessPreview() {
    DuoTheme { TodayPreviewBackdrop { NowPlayingWidget(NowPlayingState.AccessRequired, TodayWidgetSize.MEDIUM, Modifier.size(380.dp, 190.dp)) } }
}

@Preview(name = "Now Playing, idle, dark", widthDp = 400, heightDp = 220, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun NowPlayingIdlePreview() {
    DuoTheme(dark = true) { TodayPreviewBackdrop { NowPlayingWidget(NowPlayingState.Idle, TodayWidgetSize.MEDIUM, Modifier.size(380.dp, 190.dp)) } }
}

@Preview(name = "Now Playing 4x2, font scale 1.3", widthDp = 400, heightDp = 220, fontScale = 1.3f)
@Composable
private fun NowPlayingFontScalePreview() {
    DuoTheme { TodayPreviewBackdrop { NowPlayingWidget(NowPlayingState.Active(PreviewTrack), TodayWidgetSize.MEDIUM, Modifier.size(380.dp, 190.dp)) } }
}
