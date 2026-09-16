package com.jake.duolauncher.home

import android.content.pm.LauncherApps
import android.os.UserManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jake.duolauncher.AppEntry
import com.jake.duolauncher.AppLibrary
import com.jake.duolauncher.AppLibraryView
import com.jake.duolauncher.HomeDragState
import com.jake.duolauncher.LauncherModel
import com.jake.duolauncher.LauncherState
import com.jake.duolauncher.design.currentDuoColors
import com.jake.duolauncher.library.LibraryView
import com.jake.duolauncher.profiles.DuoPrivateSpace
import com.jake.duolauncher.profiles.PrivateSpaceApp

/**
 * The App Library with everything the launcher owns already wired in (FR-68 to FR-70, FR-75 to FR-78).
 *
 * `AppLibraryScreen` takes fourteen collaborators as parameters so it can be previewed and tested
 * without a device. The cost of that is that every call site has to supply them, and the three call
 * sites in this build — the compact pager, the expanded workspace and the pin picker — were each
 * supplying none, which is why hidden apps, a locked private space, the icon pipeline, the
 * remembered view and the Suggestions group were all absent from a screen that fully implements
 * them.
 *
 * This wrapper is the one place those are filled in, so the three surfaces cannot disagree about
 * what exists — which for FR-77 is not a consistency nicety but the whole requirement.
 */
@Composable
internal fun HostedAppLibrary(
    state: LauncherState,
    model: LauncherModel,
    query: String,
    onQuery: (String) -> Unit,
    onLaunch: (AppEntry) -> Unit,
    onActions: (AppEntry) -> Unit,
    modifier: Modifier = Modifier,
    editing: Boolean = false,
    drag: HomeDragState? = null,
    page: Int? = null,
    onLaunchFrom: (AppEntry, android.graphics.Rect?) -> Unit = { app, _ -> onLaunch(app) },
    onDismiss: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val privateRepository = remember(context) { DuoPrivateSpace.repository(context) }
    // Collected rather than read once: locking the space from system UI has to empty the container
    // and the lists on the next frame, not the next time this screen happens to be rebuilt (FR-77).
    val privateSpace by privateRepository.state.collectAsStateWithLifecycle()
    val renderer = rememberIconRenderer()
    val iconStyle = LocalHomeAppearance.current.resolvedIconStyle(currentDuoColors())

    AppLibrary(
        state = state,
        query = query,
        onQuery = onQuery,
        onLaunch = onLaunch,
        onPin = model::setPinned,
        onActions = onActions,
        modifier = modifier,
        editing = editing,
        drag = drag,
        page = page,
        onLaunchFrom = onLaunchFrom,
        onTurnOnWork = { model.turnOnWork(it) },
        // FR-75 and FR-77, from the one place both rules live.
        exclusions = DuoHost.libraryExclusions(context),
        suggestions = DuoHost.suggestionsProvider(context),
        iconRenderer = renderer,
        iconStyle = iconStyle,
        // FR-69: the view choice is remembered across restarts, so it comes from the model.
        view = libraryViewOf(state.settings.libraryView),
        onView = { model.setLibraryView(appLibraryViewOf(it)) },
        privateSpace = privateSpace,
        hidePrivateSpace = state.settings.hidePrivateContainer,
        onPrivateLocked = privateRepository::setLocked,
        onLaunchPrivate = { app -> context.launchPrivateSpaceApp(app) },
        onDismiss = onDismiss,
    )
}

/**
 * Launches an app from the private container.
 *
 * Private-space apps are deliberately absent from `LauncherState.apps` — the catalog refresh only
 * enumerates the personal and work profiles — so this cannot go through the launcher's ordinary
 * launch path and has to resolve the profile itself. That absence is also why a locked space cannot
 * leak onto Home: there is nothing there to place.
 */
private fun android.content.Context.launchPrivateSpaceApp(app: PrivateSpaceApp) {
    runCatching {
        val component = com.jake.duolauncher.parseProfileAppId(app.id)?.component
            ?.let { android.content.ComponentName.unflattenFromString(it) } ?: return
        val user = getSystemService(UserManager::class.java)?.getUserForSerialNumber(app.userSerial) ?: return
        getSystemService(LauncherApps::class.java)?.startMainActivity(component, user, null, null)
    }
}

/**
 * The two view enums are deliberately separate — one is persistence, one is UI — so they are mapped
 * by name rather than by ordinal, which keeps either free to gain a case without silently
 * re-pointing the other.
 */
private fun libraryViewOf(value: AppLibraryView): LibraryView =
    LibraryView.entries.firstOrNull { it.name == value.name } ?: LibraryView.CATEGORIES

private fun appLibraryViewOf(value: LibraryView): AppLibraryView =
    AppLibraryView.entries.firstOrNull { it.name == value.name } ?: AppLibraryView.entries.first()
