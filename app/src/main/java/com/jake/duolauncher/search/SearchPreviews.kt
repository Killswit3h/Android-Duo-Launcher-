package com.jake.duolauncher.search

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.tooling.preview.Preview
import com.jake.duolauncher.DuoTheme
import com.jake.duolauncher.design.BlurBackdrop
import com.jake.duolauncher.design.DuoSampleBackdrop
import com.jake.duolauncher.library.LibraryApp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Previews of every state the Search screen has to get right: before typing, with results, with no
 * results, and with contacts denied.
 *
 * Nothing in the launcher uses this file; it exists so the four states can be eyeballed in Android
 * Studio without a device, exactly as `design/GlassGallery.kt` does for the glass system.
 */

/** A stand-in engine that answers every query with one fixed result set. */
private class PreviewSearchEngine(private val results: SearchResults) : SearchEngine {
    override fun query(text: String): Flow<SearchResults> = flowOf(results)
}

private fun previewApp(
    label: String,
    packageName: String,
    isWork: Boolean = false,
): LibraryApp = LibraryApp(
    id = "$packageName/.Main",
    label = label,
    packageName = packageName,
    isWork = isWork,
)

private val PreviewSuggestions = SearchResults(
    apps = listOf(
        previewApp("Messages", "com.duo.messages"),
        previewApp("Camera", "com.duo.camera"),
        previewApp("Calendar", "com.duo.calendar"),
        previewApp("Maps", "com.duo.maps"),
        previewApp("Music", "com.duo.music"),
        previewApp("Photos", "com.duo.photos"),
        previewApp("Wallet", "com.duo.wallet"),
        previewApp("Weather", "com.duo.weather"),
    ).map { AppResult(it, MatchKind.EXACT, score = 0, isSuggestion = true) },
    isSuggestions = true,
)

private val PreviewResults = SearchResults(
    apps = listOf(
        AppResult(previewApp("Calculator", "com.duo.calculator"), MatchKind.PREFIX, score = 900),
        AppResult(previewApp("Calendar", "com.duo.calendar", isWork = true), MatchKind.PREFIX, score = 880),
    ),
    shortcuts = listOf(
        ShortcutResult(
            SearchShortcut(
                id = "new-event",
                appId = "com.duo.calendar/.Main",
                packageName = "com.duo.calendar",
                label = "New event",
                appLabel = "Calendar",
            ),
            MatchKind.WORD_START,
            score = 700,
        ),
    ),
    settings = listOf(
        SettingResult(
            SettingsDestination("wifi", "Wi-Fi", "android.settings.WIFI_SETTINGS", listOf("wifi")),
            MatchKind.CONTAINS,
            score = 500,
        ),
    ),
    contacts = listOf(
        ContactResult(SearchContact("lookup-1", 1L, "Ada Lovelace"), MatchKind.PREFIX, score = 600),
    ),
    calculation = CalculationResult(expression = "2*21", formatted = "42", value = 42.0),
    web = WebResult("cal"),
    query = "cal",
)

private val PreviewNoResults = SearchResults(web = WebResult("zzqx"), query = "zzqx")

private val PreviewContactsDenied = SearchResults(
    apps = listOf(AppResult(previewApp("Adam's Notes", "com.duo.notes"), MatchKind.CONTAINS, score = 400)),
    web = WebResult("ada"),
    query = "ada",
)

/** Wraps a preview in the theme and a photo-like backdrop, so the glass has something to sample. */
@Composable
private fun SearchPreviewHost(
    dark: Boolean,
    results: SearchResults,
    assistantAvailable: Boolean = true,
    contactsGranted: Boolean = true,
) {
    DuoTheme(dark = dark) {
        BlurBackdrop(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize().background(Brush.linearGradient(DuoSampleBackdrop)))
            SearchScreen(
                engine = PreviewSearchEngine(results),
                onOpen = {},
                onDismiss = {},
                assistantAvailable = assistantAvailable,
                contactsGranted = contactsGranted,
            )
        }
    }
}

@Preview(name = "Search — before typing", widthDp = 420, heightDp = 860)
@Composable
private fun SearchSuggestionsPreview() {
    SearchPreviewHost(dark = false, results = PreviewSuggestions)
}

@Preview(
    name = "Search — results, dark",
    widthDp = 420,
    heightDp = 860,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun SearchResultsPreview() {
    SearchPreviewHost(dark = true, results = PreviewResults)
}

@Preview(name = "Search — no results", widthDp = 420, heightDp = 860)
@Composable
private fun SearchNoResultsPreview() {
    SearchPreviewHost(dark = false, results = PreviewNoResults)
}

/** Contacts denied: no Contacts section, one Allow row — and no assistant, so no Ask control. */
@Preview(name = "Search — contacts denied", widthDp = 420, heightDp = 860)
@Composable
private fun SearchContactsDeniedPreview() {
    SearchPreviewHost(
        dark = false,
        results = PreviewContactsDenied,
        assistantAvailable = false,
        contactsGranted = false,
    )
}

/** Font scale 1.3 (NFR-A3): the same result list, with nothing clipped. */
@Preview(name = "Search — results at font scale 1.3", widthDp = 420, heightDp = 860, fontScale = 1.3f)
@Composable
private fun SearchLargeFontPreview() {
    SearchPreviewHost(dark = false, results = PreviewResults)
}
