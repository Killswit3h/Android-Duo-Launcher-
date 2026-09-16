package com.jake.duolauncher.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jake.duolauncher.AppEntry
import com.jake.duolauncher.DropTarget
import com.jake.duolauncher.HomeDragState
import com.jake.duolauncher.LauncherState
import com.jake.duolauncher.design.DuoTokens
import com.jake.duolauncher.design.GlassLevel
import com.jake.duolauncher.design.GlassSurface
import com.jake.duolauncher.design.LocalDuoTypography
import com.jake.duolauncher.design.duoColors
import com.jake.duolauncher.dropRegion
import com.jake.duolauncher.icons.IconRenderer
import com.jake.duolauncher.icons.IconStyle
import com.jake.duolauncher.profiles.PrivateSpaceApp
import com.jake.duolauncher.profiles.PrivateSpaceState
import com.jake.duolauncher.profiles.PrivateSpaceUnsupportedReason
import com.jake.duolauncher.profiles.showsPrivateContainer
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.ui.graphics.asImageBitmap
import java.util.Locale

/**
 * The App Library (FR-68, FR-69, FR-70) and its private-space surface (FR-76, FR-77, FR-78).
 *
 * **Everything this screen needs is a parameter.** It reads no repository, no preference store and
 * no singleton, so the persistence track can change how hidden apps, suggestions, icon style and the
 * remembered view are stored without touching this file — and so the previews at the bottom of this
 * package can render every state without a device.
 *
 * **The one invariant that is not a display detail:** every list rendered here is produced by
 * [visibleLibraryApps], [CategoryGrouper] or [AlphabetIndexer], each handed the same [exclusions]
 * — hardened once by [withPrivateGuard]. A hidden app (FR-75) or an app in a private space that is
 * not positively unlocked (FR-77) therefore cannot appear in a group, a section, a suggestion or a
 * search result, because there is no path to the screen that bypasses that gate.
 */
@Composable
internal fun AppLibraryScreen(
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
    val colors = duoColors()
    val type = LocalDuoTypography.current

    val pinned = remember(state.homeSlots, state.leadingSlots) {
        (state.homeSlots.asSequence() + state.leadingSlots.asSequence()).filterNotNull().toSet()
    }
    val hasWork = state.profiles.any { it.isWork } || state.apps.any { it.isWork }
    var showWork by rememberSaveable { mutableStateOf(false) }
    var rememberedView by rememberSaveable { mutableStateOf(LibraryView.CATEGORIES) }
    val activeView = view ?: rememberedView
    var openGroup by remember { mutableStateOf<LibraryGroup?>(null) }

    val selectedProfile = if (showWork) {
        state.profiles.firstOrNull { it.isWork }
    } else {
        state.profiles.firstOrNull { it.isPersonal }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(showWork, selectedProfile?.available, selectedProfile?.quiet) {
        listState.scrollToItem(0)
    }

    val appsById = remember(state.apps) { state.apps.associateBy { it.id } }
    val fallbacks = remember(state.apps) { state.apps.associate { it.id to it.icon.asImageBitmap() } }
    val icons = remember(iconRenderer, iconStyle, fallbacks) {
        LibraryIcons(iconRenderer, iconStyle, fallbacks)
    }
    val libraryApps = remember(state.apps) { libraryAppsOf(state.apps, facts) }

    val privateUnlocked = privateSpace is PrivateSpaceState.Unlocked
    val guarded = remember(exclusions, privateUnlocked) { withPrivateGuard(exclusions, privateUnlocked) }
    val filter = profileFilterFor(hasWork, showWork)
    val libraryQuery = LibraryQuery(query, filter)

    // The pin picker and any active search are flat lists; browsing is grouped or sectioned.
    val searching = query.isNotBlank()
    val flat = editing || searching

    val results = remember(libraryApps, query, filter, guarded, flat) {
        if (flat) visibleLibraryApps(libraryApps, libraryQuery, guarded) else emptyList()
    }
    val groups = remember(libraryApps, query, filter, guarded, flat, suggestions) {
        if (flat) {
            emptyList()
        } else {
            CategoryGrouper(Locale.getDefault(), suggestions, guarded).group(libraryApps, libraryQuery)
        }
    }
    val azIndex = remember(libraryApps, query, filter, guarded, flat, activeView) {
        if (!flat && activeView == LibraryView.AZ) {
            AlphabetIndexer(Locale.getDefault(), guarded).index(libraryApps, libraryQuery)
        } else {
            AlphabetIndex.Empty
        }
    }

    val visibleCount = when {
        flat -> results.size
        activeView == LibraryView.AZ -> azIndex.apps().size
        else -> groups.sumOf { it.apps.size }
    }
    val empty = when {
        flat -> results.isEmpty()
        activeView == LibraryView.AZ -> azIndex.isEmpty
        else -> groups.isEmpty()
    }

    val launch: (LibraryApp, android.graphics.Rect?) -> Unit = { app, bounds ->
        appsById[app.id]?.let { entry ->
            if (editing) onPin(entry.id, entry.id !in pinned) else onLaunchFrom(entry, bounds)
        }
    }
    val actions: (LibraryApp) -> Unit = { app -> appsById[app.id]?.let(onActions) }
    val combined = drag == null
    val dragModifier: @Composable (LibraryApp) -> Modifier = { app ->
        if (drag != null) Modifier.dropRegion(drag, DropTarget.Library(app.id), app.id, page) else Modifier
    }

    LibraryPanel(glass = !editing, modifier = modifier) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = DuoTokens.space.lg)
                .padding(top = DuoTokens.space.lg),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (editing) "Choose home apps" else "App Library",
                    modifier = Modifier.weight(1f),
                    style = type.title2,
                    color = colors.label1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (editing) "${pinned.size} pinned" else "$visibleCount",
                    style = type.footnote,
                    color = colors.label2,
                )
                if (!editing) {
                    LibraryViewToggle(
                        view = activeView,
                        onView = { next ->
                            rememberedView = next
                            onView(next)
                        },
                    )
                }
                if (onDismiss != null) {
                    IconButton(onClick = onDismiss, modifier = Modifier.testTag("library-dismiss")) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close App Library", tint = colors.label1)
                    }
                }
            }

            if (hasWork) {
                LibraryProfileChips(
                    showWork = showWork,
                    onShowWork = { showWork = it },
                    modifier = Modifier.padding(top = DuoTokens.space.sm),
                )
            }

            LibrarySearchField(
                query = query,
                onQuery = onQuery,
                editing = editing,
                modifier = Modifier.padding(vertical = DuoTokens.space.md),
            )

            if (showWork && selectedProfile?.available == false) {
                WorkPausedNotice(
                    quiet = selectedProfile.quiet,
                    onTurnOnWork = { onTurnOnWork(selectedProfile.userSerial) },
                )
            }

            Box(Modifier.weight(1f)) {
                when {
                    empty -> Text(
                        text = if (state.loading) "Loading apps…" else "No apps found",
                        modifier = Modifier.padding(vertical = DuoTokens.space.xl),
                        style = type.body,
                        color = colors.label2,
                    )

                    flat -> LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("all-apps-list"),
                        state = listState,
                        contentPadding = PaddingValues(bottom = DuoTokens.space.md),
                    ) {
                        items(results, key = { it.id }) { app ->
                            LibraryAppRow(
                                app = app,
                                icons = icons,
                                onClick = { bounds -> launch(app, bounds) },
                                onActions = { actions(app) },
                                modifier = dragModifier(app),
                                combined = combined,
                                trailing = if (!editing) null else {
                                    { PinToggle(app, app.id in pinned, onPin) }
                                },
                            )
                        }
                    }

                    activeView == LibraryView.AZ -> LibraryAzView(
                        index = azIndex,
                        icons = icons,
                        listState = listState,
                        onLaunch = launch,
                        onActions = actions,
                        modifier = Modifier.fillMaxSize(),
                        combined = combined,
                        dragModifier = dragModifier,
                    )

                    else -> LibraryCategoriesView(
                        groups = groups,
                        icons = icons,
                        onLaunch = launch,
                        onActions = actions,
                        onOpenGroup = { openGroup = it.group },
                        modifier = Modifier.fillMaxSize(),
                        combined = combined,
                    )
                }

                // FR-78: the container is omitted while Hide private space is on, until the user
                // types the reveal keyword. The rule is shared with Search rather than restated.
                if (!editing && showsPrivateContainer(privateSpace, hidePrivateSpace, query)) {
                    PrivateContainer(
                        state = privateSpace,
                        icons = icons,
                        onSetLocked = onPrivateLocked,
                        onLaunch = onLaunchPrivate,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = DuoTokens.space.sm),
                    )
                }

                val opened = groups.firstOrNull { it.group == openGroup }
                if (opened != null) {
                    LibraryGroupPanel(
                        content = opened,
                        icons = icons,
                        onLaunch = { app, bounds -> openGroup = null; launch(app, bounds) },
                        onActions = actions,
                        onClose = { openGroup = null },
                        modifier = Modifier.fillMaxSize(),
                        combined = combined,
                        dragModifier = dragModifier,
                    )
                }
            }
        }
    }
}

/** Glass while browsing; an opaque sheet inside the pin picker, which sits on its own surface. */
@Composable
private fun LibraryPanel(
    glass: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (glass) {
        GlassSurface(level = GlassLevel.PANEL, shape = DuoTokens.radius.sheet, modifier = modifier) {
            content()
        }
    } else {
        Surface(modifier = modifier, shape = DuoTokens.radius.sheet) { content() }
    }
}

/** FR-70's Personal / Work chips, unchanged in behaviour from the list they replace. */
@Composable
internal fun LibraryProfileChips(
    showWork: Boolean,
    onShowWork: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = duoColors()
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(DuoTokens.space.sm),
    ) {
        val chipColors = FilterChipDefaults.filterChipColors(
            labelColor = colors.label1,
            selectedContainerColor = colors.accent.copy(alpha = CHIP_SELECTED_ALPHA),
            selectedLabelColor = colors.label1,
        )
        FilterChip(
            selected = !showWork,
            onClick = { onShowWork(false) },
            label = { Text("Personal") },
            colors = chipColors,
            modifier = Modifier.testTag("library-profile-personal"),
        )
        FilterChip(
            selected = showWork,
            onClick = { onShowWork(true) },
            label = { Text("Work") },
            colors = chipColors,
            modifier = Modifier.testTag("library-profile-work"),
        )
    }
}

/**
 * The paused-work controls (FR-70, and the error table's "work profile paused" row).
 *
 * Rendered above the content rather than inside one list, so it appears in the category view, the
 * A–Z view and search results alike.
 */
@Composable
internal fun WorkPausedNotice(
    quiet: Boolean,
    onTurnOnWork: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = duoColors()
    val type = LocalDuoTypography.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = DuoTokens.space.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(DuoTokens.space.md),
    ) {
        Text(
            text = if (quiet) "Work apps are paused" else "Work profile is unavailable",
            style = type.body,
            color = colors.label1,
        )
        if (quiet) {
            Button(onClick = onTurnOnWork, modifier = Modifier.testTag("turn-on-work")) {
                Text("Turn on work apps")
            }
        }
    }
}

/** The local substring filter at the top of the library (FR-68). */
@Composable
private fun LibrarySearchField(
    query: String,
    onQuery: (String) -> Unit,
    editing: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = duoColors()
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        modifier = modifier
            .fillMaxWidth()
            .testTag(if (editing) "pin-search" else "library-search"),
        placeholder = { Text("Search apps") },
        singleLine = true,
        shape = DuoTokens.radius.tile,
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQuery("") }) {
                    Icon(Icons.Rounded.Close, contentDescription = "Clear search")
                }
            }
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = colors.label1,
            unfocusedTextColor = colors.label1,
            cursorColor = colors.accent,
            focusedContainerColor = colors.glassTint.copy(alpha = FIELD_FILL_FOCUSED),
            unfocusedContainerColor = colors.glassTint.copy(alpha = FIELD_FILL),
            focusedBorderColor = colors.accent,
            unfocusedBorderColor = colors.separator,
            focusedPlaceholderColor = colors.label3,
            unfocusedPlaceholderColor = colors.label3,
            focusedLeadingIconColor = colors.label2,
            unfocusedLeadingIconColor = colors.label2,
            focusedTrailingIconColor = colors.label2,
            unfocusedTrailingIconColor = colors.label2,
        ),
    )
}

/** FR-69's view switch. The choice is remembered by the host through `onView`. */
@Composable
private fun LibraryViewToggle(
    view: LibraryView,
    onView: (LibraryView) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = duoColors()
    val categories = view == LibraryView.CATEGORIES
    IconButton(
        onClick = { onView(view.toggled()) },
        modifier = modifier.testTag(if (categories) "library-view-categories" else "library-view-az"),
    ) {
        Icon(
            imageVector = if (categories) {
                Icons.AutoMirrored.Rounded.FormatListBulleted
            } else {
                Icons.Rounded.GridView
            },
            contentDescription = if (categories) "Show apps A to Z" else "Show app categories",
            tint = colors.label1,
        )
    }
}

/** The pin picker's per-row toggle. Present only while editing, which the suite asserts. */
@Composable
private fun PinToggle(app: LibraryApp, isPinned: Boolean, onPin: (String, Boolean) -> Unit) {
    val colors = duoColors()
    IconButton(
        onClick = { onPin(app.id, !isPinned) },
        modifier = Modifier.testTag("pin-${app.id}"),
    ) {
        Icon(
            imageVector = if (isPinned) Icons.Rounded.PushPin else Icons.Outlined.PushPin,
            contentDescription = if (isPinned) {
                "Remove ${app.label} from home"
            } else {
                "Pin ${app.label} to home"
            },
            tint = if (isPinned) colors.accent else colors.label3,
            modifier = Modifier.size(PIN_ICON_SIZE),
        )
    }
}

private val PIN_ICON_SIZE = 20.dp
private const val FIELD_FILL = 0.12f
private const val FIELD_FILL_FOCUSED = 0.18f
private const val CHIP_SELECTED_ALPHA = 0.28f
