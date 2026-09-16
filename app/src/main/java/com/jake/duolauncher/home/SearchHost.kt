package com.jake.duolauncher.home

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.jake.duolauncher.AppEntry
import com.jake.duolauncher.LauncherModel
import com.jake.duolauncher.LauncherState
import com.jake.duolauncher.MainActivity
import com.jake.duolauncher.design.currentDuoColors
import com.jake.duolauncher.search.AppResult
import com.jake.duolauncher.search.CalculationResult
import com.jake.duolauncher.search.ContactResult
import com.jake.duolauncher.search.SearchResult
import com.jake.duolauncher.search.SearchScreen
import com.jake.duolauncher.search.SettingResult
import com.jake.duolauncher.search.ShortcutResult
import com.jake.duolauncher.search.WebResult
import com.jake.duolauncher.search.assistIntent
import com.jake.duolauncher.search.contactIntent
import com.jake.duolauncher.search.settingsIntent
import com.jake.duolauncher.search.webSearchIntent

/**
 * **Search or Ask** as Home hosts it (FR-71 to FR-74).
 *
 * `SearchScreen` takes its engine and every outcome as parameters; this is where the launcher
 * supplies them. The engine is [DuoHost.searchEngine], built once per process, so opening Search
 * never rebuilds the folded app index — that happens on catalog refresh, off this path.
 *
 * **Placement matters.** The caller draws this inside the launcher's `BlurBackdrop` composition
 * locals. `SearchScreen` is one full-bleed glass panel; outside that scope it has no backdrop to
 * sample and degrades to flat tint.
 */
@Composable
internal fun SearchHost(
    state: LauncherState,
    model: LauncherModel,
    activity: MainActivity,
    onLaunchApp: (AppEntry) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val engine = remember(context) { DuoHost.searchEngine(context) }
    val ranker = remember(context) { DuoHost.ranker(context) }
    val appsById = remember(state.apps) { state.apps.associateBy { it.id } }
    val renderer = rememberIconRenderer()
    val iconStyle = LocalHomeAppearance.current.resolvedIconStyle(currentDuoColors())

    SearchScreen(
        engine = engine,
        modifier = modifier,
        icons = renderer,
        iconStyle = iconStyle,
        onDismiss = onDismiss,
        onOpen = { result -> open(result, appsById, ranker::record, onLaunchApp, context, onDismiss) },
        onAsk = { context.startSafely(assistIntent()); onDismiss() },
        // The screen never requests a permission itself (NFR-S2). Turning the setting on here is
        // what makes the Contacts section eligible; the grant is requested at the point of use.
        onAllowContacts = {
            model.setSearchContacts(true)
            activity.requestContactsAccess()
        },
    )
}

/**
 * What each kind of row does (FR-74). [SearchResult] is sealed, so this is exhaustive: a new result
 * kind fails to compile here rather than silently doing nothing on tap.
 */
private fun open(
    result: SearchResult,
    appsById: Map<String, AppEntry>,
    record: (String, Long) -> Unit,
    onLaunchApp: (AppEntry) -> Unit,
    context: android.content.Context,
    onDismiss: () -> Unit,
) {
    when (result) {
        is AppResult -> appsById[result.id]?.let { app ->
            // FR-84: a launch from Search counts toward suggestions like a launch from Home.
            record(app.id, app.userSerial)
            onLaunchApp(app)
            onDismiss()
        }
        is SettingResult -> { context.startSafely(settingsIntent(result.destination)); onDismiss() }
        is ContactResult -> { context.startSafely(contactIntent(result.contact)); onDismiss() }
        is WebResult -> { context.startSafely(webSearchIntent(result.query)); onDismiss() }
        // The answer is already on screen; there is nothing further to open.
        is CalculationResult -> Unit
        // The shortcuts section has no source wired yet (see DuoHost.searchEngine), so no row of
        // this kind can reach here.
        is ShortcutResult -> Unit
    }
}

private fun android.content.Context.startSafely(intent: Intent) {
    runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
