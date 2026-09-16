package com.jake.duolauncher

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.jake.duolauncher.library.AppLibraryScreen
import com.jake.duolauncher.library.LibraryAppFacts
import com.jake.duolauncher.library.LibraryExclusions
import com.jake.duolauncher.library.LibrarySuggestionsProvider
import com.jake.duolauncher.library.LibraryView
import com.jake.duolauncher.icons.IconRenderer
import com.jake.duolauncher.icons.IconStyle
import com.jake.duolauncher.profiles.PrivateSpaceApp
import com.jake.duolauncher.profiles.PrivateSpaceState
import com.jake.duolauncher.profiles.PrivateSpaceUnsupportedReason

/**
 * The App Library's entry point, kept in the root package at its original name and signature.
 *
 * The implementation moved to `library/` (work orders F17 and F19), but `home/HomePager.kt`,
 * `home/HomeWorkspace.kt` and `home/HomeSheets.kt` call this by its old name and import it from
 * this package. Keeping the entry point here means the App Library could be rewritten without
 * touching a single call site, and every new capability arrives as a defaulted parameter.
 *
 * Hosts that have the new data wired — hidden apps, the suggestion ranker, the private-space
 * repository, the icon renderer and the remembered view — pass them here; hosts that do not get a
 * library that still browses, searches, filters by profile and drags out to Home.
 */
@Composable
internal fun AppLibrary(
    state: LauncherState,
    query: String,
    onQuery: (String) -> Unit,
    onLaunch: (AppEntry) -> Unit,
    onPin: (String, Boolean) -> Unit,
    onActions: (AppEntry) -> Unit,
    modifier: Modifier = Modifier,
    editing: Boolean = false,
    drag: HomeDragState? = null,
    page: Int? = null,
    onLaunchFrom: (AppEntry, android.graphics.Rect?) -> Unit = { app, _ -> onLaunch(app) },
    onTurnOnWork: (Long) -> Unit = {},
    facts: (AppEntry) -> LibraryAppFacts = { LibraryAppFacts.Unknown },
    exclusions: LibraryExclusions = LibraryExclusions.None,
    suggestions: LibrarySuggestionsProvider = LibrarySuggestionsProvider.None,
    iconRenderer: IconRenderer? = null,
    iconStyle: IconStyle = IconStyle(),
    view: LibraryView? = null,
    onView: (LibraryView) -> Unit = {},
    privateSpace: PrivateSpaceState =
        PrivateSpaceState.Unsupported(PrivateSpaceUnsupportedReason.REQUIRES_ANDROID_15),
    hidePrivateSpace: Boolean = false,
    onPrivateLocked: (Boolean) -> Unit = {},
    onLaunchPrivate: (PrivateSpaceApp) -> Unit = {},
    onDismiss: (() -> Unit)? = null,
) {
    AppLibraryScreen(
        state = state,
        query = query,
        onQuery = onQuery,
        onLaunch = onLaunch,
        onPin = onPin,
        onActions = onActions,
        modifier = modifier,
        editing = editing,
        drag = drag,
        page = page,
        onLaunchFrom = onLaunchFrom,
        onTurnOnWork = onTurnOnWork,
        facts = facts,
        exclusions = exclusions,
        suggestions = suggestions,
        iconRenderer = iconRenderer,
        iconStyle = iconStyle,
        view = view,
        onView = onView,
        privateSpace = privateSpace,
        hidePrivateSpace = hidePrivateSpace,
        onPrivateLocked = onPrivateLocked,
        onLaunchPrivate = onLaunchPrivate,
        onDismiss = onDismiss,
    )
}
