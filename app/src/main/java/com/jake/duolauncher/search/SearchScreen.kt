@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalComposeUiApi::class)

package com.jake.duolauncher.search

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.util.lerp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jake.duolauncher.design.DuoTokens
import com.jake.duolauncher.design.GlassLevel
import com.jake.duolauncher.design.GlassSurface
import com.jake.duolauncher.design.rememberMotionEnabled
import com.jake.duolauncher.icons.IconRenderer
import com.jake.duolauncher.icons.IconStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest

/**
 * The full-screen "Search or Ask" surface (FR-71 to FR-74).
 *
 * **Hosting.** Everything this screen needs arrives as a parameter — the engine, the row callback,
 * the Ask and contacts callbacks and [onDismiss] — so Home can host it behind its swipe-down gesture
 * without this file knowing anything about Home, the launcher model or the activity. It draws
 * itself as one full-bleed [GlassSurface]; place it inside the launcher's `BlurBackdrop` and the
 * wallpaper blurs through it, and outside one it degrades to tinted translucency with no crash.
 *
 * **Keeping up with typing (NFR-P3).** The query is a snapshot state, turned into a flow once and
 * `flatMapLatest`-ed into [SearchEngine.query]. A new keystroke therefore cancels the in-flight
 * query structurally rather than racing it, the engine's own debounce and supersede rule do the
 * rest, and the app index is never rebuilt here — [SearchAppIndex.setApps] belongs on the catalog
 * refresh, off this path entirely.
 *
 * **While a query is in flight** the previous results stay on screen rather than blanking, which is
 * what makes fast typing read as a list refining itself instead of flickering.
 *
 * @param engine the search engine; results are collected from it, never computed here.
 * @param onOpen what a tapped row — and the keyboard's search action (FR-74) — does. [SearchResult]
 *   is sealed, so the host exhaustively decides how each kind launches.
 * @param onDismiss close Search: the Cancel control, and a completed back gesture (FR-85).
 * @param icons the icon pipeline for app and shortcut rows. Null draws the lettered placeholder,
 *   which is also what a not-yet-rendered icon shows.
 * @param onAsk the Ask control (FR-73). Only reachable when [assistantAvailable].
 * @param onAllowContacts asks for `READ_CONTACTS`. This screen never requests a runtime permission
 *   itself; it surfaces the row and hands the decision to the host.
 * @param assistantAvailable whether any activity answers the assist intent. False hides Ask, which
 *   is the error table's "Assistant not configured".
 * @param contactsGranted whether contacts access is granted. False keeps the Contacts section
 *   absent (the engine already omits it) and offers a single "Allow contacts in Search" row.
 */
@Composable
fun SearchScreen(
    engine: SearchEngine,
    onOpen: (SearchResult) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    icons: IconRenderer? = null,
    iconStyle: IconStyle = IconStyle(),
    onAsk: () -> Unit = {},
    onAllowContacts: () -> Unit = {},
    assistantAvailable: Boolean = rememberAssistantAvailable(),
    contactsGranted: Boolean = rememberContactsGranted(),
) {
    var query by rememberSaveable { mutableStateOf("") }

    val results by remember(engine) {
        snapshotFlow { query }.flatMapLatest(engine::query)
    }.collectAsStateWithLifecycle(SearchResults.Empty)

    val motionEnabled = rememberMotionEnabled()
    var backProgress by remember { mutableFloatStateOf(0f) }

    // FR-85: the gesture previews the close, and only a completed gesture dismisses.
    PredictiveBackHandler { events ->
        try {
            events.collect { event -> if (motionEnabled) backProgress = event.progress }
            backProgress = 0f
            onDismiss()
        } catch (_: CancellationException) {
            backProgress = 0f
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .then(if (backProgress > 0f) Modifier.backPreview(backProgress) else Modifier)
            .testTag("search-screen"),
    ) {
        GlassSurface(
            level = GlassLevel.PANEL,
            shape = RectangleShape,
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = DuoTokens.space.lg),
            ) {
                SearchFieldRow(
                    query = query,
                    onQuery = { query = it },
                    onSubmit = { results.top?.let(onOpen) },
                    onDismiss = onDismiss,
                    onAsk = onAsk,
                    assistantAvailable = assistantAvailable,
                )
                SearchBody(
                    results = results,
                    query = query,
                    icons = icons,
                    iconStyle = iconStyle,
                    onOpen = onOpen,
                    onAllowContacts = onAllowContacts,
                    contactsGranted = contactsGranted,
                )
            }
        }
    }
}

/**
 * The results area: FR-71's pre-typing suggestions, or the sectioned results of FR-72 in the
 * engine's fixed order.
 */
@Composable
private fun SearchBody(
    results: SearchResults,
    query: String,
    icons: IconRenderer?,
    iconStyle: IconStyle,
    onOpen: (SearchResult) -> Unit,
    onAllowContacts: () -> Unit,
    contactsGranted: Boolean,
) {
    // The contacts row is an answer to a query, so it only appears once there is one to answer.
    val offerContacts = !contactsGranted && query.isNotBlank()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("search-results"),
        verticalArrangement = Arrangement.spacedBy(DuoTokens.space.xxs),
    ) {
        if (results.isSuggestions) {
            item(key = "suggestions") {
                SuggestionsSection(
                    suggestions = results.apps,
                    icons = icons,
                    iconStyle = iconStyle,
                    onOpen = onOpen,
                )
            }
        } else {
            // The web row does not count as an answer, so "No results" sits above it (error table).
            if (results.hasNoResults) item(key = "no-results") { NoResultsMessage() }

            results.sections().forEach { section ->
                item(key = "header-${section.name}") { SectionHeader(section) }
                items(
                    items = results.rows(section),
                    key = SearchRowKeys::of,
                ) { row ->
                    SearchResultRow(
                        result = row,
                        icons = icons,
                        iconStyle = iconStyle,
                        onOpen = onOpen,
                    )
                }
            }
        }

        if (offerContacts) {
            item(key = "allow-contacts") { AllowContactsRow(onAllowContacts) }
        }
    }
}

/** The search field, its Ask control (FR-73) and Cancel, as one row. */
@Composable
private fun SearchFieldRow(
    query: String,
    onQuery: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
    onAsk: () -> Unit,
    assistantAvailable: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SEARCH_FIELD_HEIGHT)
            .padding(vertical = DuoTokens.space.md),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DuoTokens.space.sm),
    ) {
        SearchField(
            query = query,
            onQuery = onQuery,
            onSubmit = onSubmit,
            onAsk = onAsk,
            assistantAvailable = assistantAvailable,
            modifier = Modifier.weight(1f),
        )
        CancelButton(onDismiss)
    }
}

/** The back-gesture preview: the surface eases back and fades as the gesture progresses (FR-85). */
private fun Modifier.backPreview(progress: Float): Modifier = graphicsLayer {
    val eased = progress.coerceIn(0f, 1f)
    val scale = lerp(1f, BACK_PREVIEW_MIN_SCALE, eased)
    scaleX = scale
    scaleY = scale
    alpha = lerp(1f, BACK_PREVIEW_MIN_ALPHA, eased)
}

/**
 * Whether the device has an assistant to hand off to (FR-73).
 *
 * Resolution is filtered by package visibility on API 30+, so this answers false unless the
 * manifest declares the matching `<queries>` entry — which is the error table's "Ask control
 * hidden", not a crash.
 */
@Composable
fun rememberAssistantAvailable(): Boolean {
    val context = LocalContext.current
    return remember(context) { canResolve(context, assistIntent()) }
}

/**
 * Live `READ_CONTACTS` state.
 *
 * Re-read on every resume, so returning from the system permission dialog — or from Settings after
 * revoking access — is reflected without the host having to push anything in. Nothing here requests
 * the permission; that is the host's callback.
 */
@Composable
fun rememberContactsGranted(): Boolean {
    val context = LocalContext.current
    var granted by remember(context) { mutableStateOf(contactsPermissionGranted(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = contactsPermissionGranted(context) }
    return granted
}

private fun contactsPermissionGranted(context: Context): Boolean =
    runCatching {
        context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)
