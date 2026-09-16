package com.jake.duolauncher.today.widgets

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jake.duolauncher.DuoTheme
import com.jake.duolauncher.design.DuoTokens
import com.jake.duolauncher.design.currentDuoColors
import com.jake.duolauncher.history.ProfileAppId
import com.jake.duolauncher.icons.IconRenderer
import com.jake.duolauncher.icons.IconStyle
import com.jake.duolauncher.icons.iconSizePx
import com.jake.duolauncher.today.TodaySuggestion
import com.jake.duolauncher.today.TodayWidgetSize
import com.jake.duolauncher.today.suggestionSlotsFor

/**
 * The App Suggestions widget (FR-59): four or eight apps from local launch history, by span.
 *
 * The ranking is not here and must not be — `history.DefaultSuggestionRanker` owns it, and
 * `SuggestionsFeed` owns when to ask. This draws whatever it is handed, in order, and reports taps.
 *
 * The tiles are launch targets, so each one is its own TalkBack node with the app's label and a
 * 48dp target (NFR-A1); the widget does not merge its semantics.
 */
@Composable
fun SuggestionsWidget(
    suggestions: List<TodaySuggestion>,
    size: TodayWidgetSize,
    modifier: Modifier = Modifier,
    iconRenderer: IconRenderer? = null,
    iconStyle: IconStyle = IconStyle(),
    showLabels: Boolean = true,
    onLaunch: (ProfileAppId) -> Unit = {},
) {
    val slots = suggestionSlotsFor(size).count
    val shown = suggestions.take(slots)
    val columns = if (size == TodayWidgetSize.SMALL) SMALL_COLUMNS else WIDE_COLUMNS
    val iconSize = if (size == TodayWidgetSize.SMALL) ICON_SMALL else ICON_REGULAR

    TodayWidgetFrame(size = size, modifier = modifier) {
        TodayWidgetCaption(icon = Icons.Rounded.Apps, label = "Suggestions")
        if (shown.isEmpty()) {
            TodayWidgetPlaceholder(text = "Apps you use will show up here")
            return@TodayWidgetFrame
        }
        Column(verticalArrangement = Arrangement.spacedBy(DuoTokens.space.sm)) {
            shown.chunked(columns).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(DuoTokens.space.sm),
                ) {
                    row.forEach { suggestion ->
                        SuggestionTile(
                            suggestion = suggestion,
                            iconRenderer = iconRenderer,
                            iconStyle = iconStyle,
                            iconSize = iconSize,
                            showLabel = showLabels && size != TodayWidgetSize.SMALL,
                            onLaunch = onLaunch,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // Keep a short last row aligned with the rows above it rather than centred.
                    repeat(columns - row.size) {
                        Column(modifier = Modifier.weight(1f)) {}
                    }
                }
            }
        }
    }
}

@Composable
private fun SuggestionTile(
    suggestion: TodaySuggestion,
    iconRenderer: IconRenderer?,
    iconStyle: IconStyle,
    iconSize: Dp,
    showLabel: Boolean,
    onLaunch: (ProfileAppId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = currentDuoColors()
    val icon = rememberAppIcon(iconRenderer, suggestion.app, iconSize, iconStyle)
    Column(
        modifier = modifier
            .heightIn(min = TodayTouchTarget)
            .clickable(
                onClickLabel = suggestion.label,
                role = Role.Button,
                onClick = { onLaunch(suggestion.app) },
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(DuoTokens.space.xxs),
    ) {
        TodayAppIcon(icon = icon, label = suggestion.label, iconSize = iconSize)
        if (showLabel) {
            Text(
                text = suggestion.label,
                style = DuoTokens.type.caption2,
                color = colors.label2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * The app's icon, taken from the cache on the first frame where it is already there and rendered
 * asynchronously where it is not.
 *
 * `IconRenderer.request` is documented as allocation-free on a hit, which is why it is safe to call
 * during composition; `load` is the suspending fallback and runs off the main thread. A null
 * renderer — every `@Preview` in this file — simply yields the generated tile.
 */
@Composable
private fun rememberAppIcon(
    renderer: IconRenderer?,
    app: ProfileAppId,
    iconSize: Dp,
    style: IconStyle,
): ImageBitmap? {
    if (renderer == null) return null
    val density = LocalDensity.current.density
    val sizePx = remember(iconSize, density) { iconSizePx(iconSize.value, density) }
    val cached = renderer.request(app, sizePx, style)
    var loaded by remember(renderer, app, sizePx, style) { mutableStateOf(cached) }
    LaunchedEffect(renderer, app, sizePx, style) {
        if (loaded == null) {
            loaded = runCatching { renderer.load(app, sizePx, style) }.getOrNull()
        }
    }
    return cached ?: loaded
}

private const val SMALL_COLUMNS = 2
private const val WIDE_COLUMNS = 4
private val ICON_SMALL = 40.dp
private val ICON_REGULAR = 48.dp

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

private val PreviewSuggestions = listOf(
    "Messages", "Camera", "Maps", "Calendar", "Photos", "Notes", "Music", "Weather",
).mapIndexed { index, label -> TodaySuggestion(app = "com.example.app$index/.Main", label = label) }

@Preview(name = "Suggestions 2x2", widthDp = 200, heightDp = 200)
@Composable
private fun SuggestionsSmallPreview() {
    DuoTheme { TodayPreviewBackdrop { SuggestionsWidget(PreviewSuggestions, TodayWidgetSize.SMALL, Modifier.size(180.dp)) } }
}

@Preview(name = "Suggestions 4x2", widthDp = 400, heightDp = 220)
@Composable
private fun SuggestionsMediumPreview() {
    DuoTheme { TodayPreviewBackdrop { SuggestionsWidget(PreviewSuggestions, TodayWidgetSize.MEDIUM, Modifier.size(380.dp, 190.dp)) } }
}

@Preview(name = "Suggestions 4x4", widthDp = 400, heightDp = 420)
@Composable
private fun SuggestionsLargePreview() {
    DuoTheme { TodayPreviewBackdrop { SuggestionsWidget(PreviewSuggestions, TodayWidgetSize.LARGE, Modifier.size(380.dp, 380.dp)) } }
}

@Preview(name = "Suggestions 4x6", widthDp = 400, heightDp = 600)
@Composable
private fun SuggestionsExtraLargePreview() {
    DuoTheme { TodayPreviewBackdrop { SuggestionsWidget(PreviewSuggestions, TodayWidgetSize.EXTRA_LARGE, Modifier.size(380.dp, 560.dp)) } }
}

@Preview(name = "Suggestions, empty", widthDp = 400, heightDp = 220)
@Composable
private fun SuggestionsEmptyPreview() {
    DuoTheme { TodayPreviewBackdrop { SuggestionsWidget(emptyList(), TodayWidgetSize.MEDIUM, Modifier.size(380.dp, 190.dp)) } }
}

@Preview(name = "Suggestions 4x4, dark", widthDp = 400, heightDp = 420, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SuggestionsDarkPreview() {
    DuoTheme(dark = true) { TodayPreviewBackdrop { SuggestionsWidget(PreviewSuggestions, TodayWidgetSize.LARGE, Modifier.size(380.dp, 380.dp)) } }
}

@Preview(name = "Suggestions 4x4, font scale 1.3", widthDp = 400, heightDp = 420, fontScale = 1.3f)
@Composable
private fun SuggestionsFontScalePreview() {
    DuoTheme { TodayPreviewBackdrop { SuggestionsWidget(PreviewSuggestions, TodayWidgetSize.LARGE, Modifier.size(380.dp, 380.dp)) } }
}
