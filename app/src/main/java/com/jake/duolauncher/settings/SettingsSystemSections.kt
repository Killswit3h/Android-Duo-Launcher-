package com.jake.duolauncher.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.SwipeDown
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.jake.duolauncher.AppLibraryView
import com.jake.duolauncher.DockSide
import com.jake.duolauncher.DuoBadgeStyle
import com.jake.duolauncher.GridSpec
import com.jake.duolauncher.LayoutMode
import com.jake.duolauncher.LayoutTarget
import com.jake.duolauncher.LeadingPageKind
import com.jake.duolauncher.MAX_DOCK_CAPACITY
import com.jake.duolauncher.MAX_GRID_SIZE
import com.jake.duolauncher.MIN_DOCK_CAPACITY
import com.jake.duolauncher.MIN_GRID_SIZE
import com.jake.duolauncher.SwipeDownAction
import com.jake.duolauncher.SwipeUpAction
import com.jake.duolauncher.design.DuoTokens
import com.jake.duolauncher.library.HiddenAppsList
import com.jake.duolauncher.profiles.PrivateSpaceState
import com.jake.duolauncher.profiles.PrivateSpaceUnsupportedReason
import kotlin.math.roundToInt

/**
 * The sections that decide how Duo *behaves*, plus the three that are mostly prose (FR-79).
 *
 * Home Screen and Dock, Today View, App Library and Search, Gestures, Badges, Hidden apps, Private
 * space, Backup, Help and About.
 */

// ---------------------------------------------------------------------------
// Home Screen and Dock (FR-31 to FR-33, FR-37, FR-38, FR-41, FR-49, FR-50)
// ---------------------------------------------------------------------------

@Composable
internal fun HomeAndDockSection(
    state: DuoSettingsUiState,
    actions: DuoSettingsActions,
    visibility: SettingsVisibility,
    gridTarget: LayoutTarget,
    onGridTarget: (LayoutTarget) -> Unit,
    onGrid: (GridSpec) -> Unit,
    presetWide: Boolean,
    onPresetWide: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings = state.settings
    val grid = state.gridFor(gridTarget)
    val preset = state.preset(presetWide)
    SettingsGroup(
        title = DuoSettingsSection.HOME_AND_DOCK.title,
        modifier = modifier,
        footer = "Changing a grid never deletes anything. Items that no longer fit move to the " +
            "next free cell, or to a new page.",
    ) {
        if (visibility.shows(SettingsIds.HOME_LAYOUT_MODE)) {
            SettingsOptionRow(
                title = "Layout mode",
                subtitle = "Separate keeps an independent layout for the cover and inner screens.",
                options = LayoutMode.entries.toList(),
                selected = state.layoutMode,
                label = { if (it == LayoutMode.MIRRORED) "Mirrored" else "Separate" },
                onSelect = actions.onLayoutMode,
                testTag = "setting-layout-mode",
                optionTag = { "layout-mode-${it.name.lowercase()}" },
            )
        }
        if (visibility.shows(SettingsIds.HOME_GRID)) {
            if (state.layoutMode == LayoutMode.SEPARATE) {
                SettingsOptionRow(
                    title = "Grid for",
                    options = listOf(LayoutTarget.COVER, LayoutTarget.INNER),
                    selected = gridTarget,
                    label = { if (it == LayoutTarget.COVER) "Cover" else "Inner" },
                    onSelect = onGridTarget,
                    testTag = "setting-grid-target",
                    optionTag = { "grid-target-${it.name.lowercase()}" },
                )
            }
            val sizes = (MIN_GRID_SIZE..MAX_GRID_SIZE).toList()
            SettingsOptionRow(
                title = "Columns",
                options = sizes,
                selected = grid.columns,
                label = { it.toString() },
                onSelect = { onGrid(grid.copy(columns = it)) },
                testTag = "setting-grid-columns",
                optionTag = { "grid-columns-$it" },
            )
            SettingsOptionRow(
                title = "Rows",
                options = sizes,
                selected = grid.rows,
                label = { it.toString() },
                onSelect = { onGrid(grid.copy(rows = it)) },
                testTag = "setting-grid-rows",
                optionTag = { "grid-rows-$it" },
            )
        }
        if (visibility.shows(SettingsIds.HOME_ICON_SIZE) ||
            visibility.shows(SettingsIds.HOME_ROW_GAP) ||
            visibility.shows(SettingsIds.HOME_DOCK_WIDTH)
        ) {
            SettingsOptionRow(
                title = "Sizes for",
                options = listOf(false, true),
                selected = presetWide,
                label = { if (it) "Inner" else "Cover" },
                onSelect = onPresetWide,
                testTag = "setting-preset-target",
                optionTag = { if (it) "preset-inner" else "preset-cover" },
            )
        }
        if (visibility.shows(SettingsIds.HOME_ICON_SIZE)) {
            SettingsSliderRow(
                title = "App icon size",
                valueLabel = "${preset.iconSize.roundToInt()} dp",
                value = preset.iconSize,
                range = 40f..68f,
                onChange = { actions.onPreset(presetWide, preset.copy(iconSize = it)) },
                testTag = "setting-icon-size",
            )
        }
        if (visibility.shows(SettingsIds.HOME_ROW_GAP)) {
            SettingsSliderRow(
                title = "Space between rows",
                valueLabel = "${preset.rowGap.roundToInt()} dp",
                value = preset.rowGap,
                range = 0f..28f,
                onChange = { actions.onPreset(presetWide, preset.copy(rowGap = it)) },
                testTag = "setting-row-gap",
            )
        }
        if (visibility.shows(SettingsIds.HOME_DOCK_SIDE)) {
            SettingsOptionRow(
                title = "Dock side",
                subtitle = "The status cluster and page controls follow the dock.",
                options = DockSide.entries.toList(),
                selected = state.dock.side,
                label = { if (it == DockSide.RIGHT) "Right" else "Left" },
                onSelect = actions.onDockSide,
                testTag = "setting-dock-side",
                optionTag = { "dock-side-${it.name.lowercase()}" },
            )
        }
        if (visibility.shows(SettingsIds.HOME_DOCK_CAPACITY)) {
            SettingsOptionRow(
                title = "Apps in the dock",
                options = (MIN_DOCK_CAPACITY..MAX_DOCK_CAPACITY).toList(),
                selected = state.dock.capacity,
                label = { it.toString() },
                onSelect = actions.onDockCapacity,
                testTag = "setting-dock-capacity",
                optionTag = { "dock-capacity-$it" },
            )
        }
        if (visibility.shows(SettingsIds.HOME_DOCK_WIDTH)) {
            SettingsSliderRow(
                title = "Dock width",
                valueLabel = "${preset.dockWidth.roundToInt()} dp",
                value = preset.dockWidth,
                range = 56f..84f,
                onChange = { actions.onPreset(presetWide, preset.copy(dockWidth = it)) },
                testTag = "setting-dock-width",
            )
        }
        if (visibility.shows(SettingsIds.HOME_STATUS)) {
            SettingsSwitchRow(
                title = "Duo status",
                subtitle = "A circular corner cluster with the time, battery, Wi-Fi and signal.",
                checked = settings.duoStatus,
                onCheckedChange = actions.onDuoStatus,
                testTag = "setting-duo-status",
            )
        }
        if (visibility.shows(SettingsIds.HOME_LOCK)) {
            SettingsSwitchRow(
                title = "Lock Home layout",
                subtitle = "Blocks dragging, removing and Edit mode. Apps still launch.",
                checked = settings.lockLayout,
                onCheckedChange = actions.onLockLayout,
                testTag = "setting-lock-layout",
            )
        }
        if (visibility.shows(SettingsIds.HOME_AUTO_ADD)) {
            SettingsSwitchRow(
                title = "Add new apps to Home",
                subtitle = if (settings.lockLayout) "Paused while the Home layout is locked." else null,
                checked = settings.autoAddApps,
                enabled = !settings.lockLayout,
                onCheckedChange = actions.onAutoAddApps,
                testTag = "setting-auto-add",
            )
        }
        if (visibility.shows(SettingsIds.HOME_UNDO) && state.canUndo) {
            SettingsActionRow {
                SettingsActionChip(
                    label = "Undo last layout change",
                    onClick = actions.onUndoEdit,
                    testTag = "setting-undo-edit",
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Today View (FR-55)
// ---------------------------------------------------------------------------

@Composable
internal fun TodaySection(
    state: DuoSettingsUiState,
    actions: DuoSettingsActions,
    visibility: SettingsVisibility,
    modifier: Modifier = Modifier,
) {
    SettingsGroup(
        title = DuoSettingsSection.TODAY.title,
        modifier = modifier,
        footer = "Every option keeps its own contents, so switching between them never clears the " +
            "other one.",
    ) {
        if (visibility.shows(SettingsIds.TODAY_LEADING_PAGE)) {
            val options = LeadingPageKind.entries.filter {
                it != LeadingPageKind.DISCOVER || state.discoverAvailable
            }
            SettingsOptionRow(
                title = "Leading page",
                subtitle = "The page to the left of Home 1.",
                options = options,
                selected = state.leadingPage,
                label = {
                    when (it) {
                        LeadingPageKind.TODAY -> "Today View"
                        LeadingPageKind.DISCOVER -> "Google Discover"
                        LeadingPageKind.CLASSIC -> "Classic workspace"
                    }
                },
                onSelect = actions.onLeadingPage,
                testTag = "setting-leading-page",
                optionTag = { "leading-page-${it.name.lowercase()}" },
            )
            if (!state.discoverAvailable) {
                SettingsRow(
                    title = "Google Discover is unavailable on this device",
                    subtitle = "Duo can only show the feed where Google provides it.",
                    testTag = "leading-page-discover-unavailable",
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// App Library and Search (FR-69, FR-72, FR-84)
// ---------------------------------------------------------------------------

@Composable
internal fun LibraryAndSearchSection(
    state: DuoSettingsUiState,
    actions: DuoSettingsActions,
    visibility: SettingsVisibility,
    modifier: Modifier = Modifier,
) {
    val settings = state.settings
    SettingsGroup(title = DuoSettingsSection.LIBRARY_AND_SEARCH.title, modifier = modifier) {
        if (visibility.shows(SettingsIds.LIBRARY_VIEW)) {
            SettingsOptionRow(
                title = "App Library view",
                options = AppLibraryView.entries.toList(),
                selected = settings.libraryView,
                label = { if (it == AppLibraryView.CATEGORIES) "Categories" else "A–Z list" },
                onSelect = actions.onLibraryView,
                testTag = "setting-library-view",
                optionTag = { "library-view-${it.name.lowercase()}" },
            )
        }
        if (visibility.shows(SettingsIds.SEARCH_CONTACTS)) {
            SettingsSwitchRow(
                title = "Contacts in Search",
                subtitle = "Contacts are read only while you are searching, and never saved.",
                checked = settings.searchContacts,
                onCheckedChange = actions.onSearchContacts,
                testTag = "setting-search-contacts",
            )
            if (settings.searchContacts && !state.contactsGranted) {
                SettingsNotice(
                    title = "Contacts access is off",
                    message = "Android has not granted Duo access to contacts, so the Contacts " +
                        "section stays hidden in Search.",
                    actionLabel = "Allow contacts",
                    onAction = actions.onContactsPermission,
                    testTag = "contacts-not-granted",
                )
            }
        }
        if (visibility.shows(SettingsIds.SEARCH_SUGGESTIONS)) {
            SettingsSwitchRow(
                title = "Suggestions",
                subtitle = "Suggested apps come from launches recorded on this device only.",
                checked = settings.suggestions,
                onCheckedChange = actions.onSuggestions,
                testTag = "setting-suggestions",
            )
        }
        if (visibility.shows(SettingsIds.SEARCH_CLEAR_HISTORY)) {
            SettingsActionRow {
                SettingsActionChip(
                    label = "Clear suggestion history",
                    onClick = actions.onClearSuggestionHistory,
                    testTag = "setting-clear-history",
                )
            }
        }
        if (visibility.shows(SettingsIds.SEARCH_GOOGLE)) {
            SettingsSwitchRow(
                title = "Search button opens Google",
                subtitle = "Duo's own search always stays available in the App Library.",
                checked = state.googleSearch,
                onCheckedChange = actions.onGoogleSearch,
                testTag = "google-search-switch",
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Gestures (FR-51 to FR-53)
// ---------------------------------------------------------------------------

@Composable
internal fun GesturesSection(
    state: DuoSettingsUiState,
    actions: DuoSettingsActions,
    visibility: SettingsVisibility,
    modifier: Modifier = Modifier,
) {
    val settings = state.settings
    val needsService = settings.doubleTapLock || settings.swipeDown == SwipeDownAction.NOTIFICATIONS
    SettingsGroup(title = DuoSettingsSection.GESTURES.title, modifier = modifier) {
        if (visibility.shows(SettingsIds.GESTURE_SWIPE_DOWN)) {
            SettingsOptionRow(
                title = "Swipe down",
                options = SwipeDownAction.entries.toList(),
                selected = settings.swipeDown,
                label = {
                    when (it) {
                        SwipeDownAction.SEARCH -> "Search"
                        SwipeDownAction.NOTIFICATIONS -> "Notifications"
                        SwipeDownAction.NONE -> "Nothing"
                    }
                },
                onSelect = actions.onSwipeDown,
                testTag = "setting-swipe-down",
                optionTag = { "swipe-down-${it.name.lowercase()}" },
            )
        }
        if (visibility.shows(SettingsIds.GESTURE_SWIPE_UP)) {
            SettingsOptionRow(
                title = "Swipe up",
                options = SwipeUpAction.entries.toList(),
                selected = settings.swipeUp,
                label = { if (it == SwipeUpAction.APP_LIBRARY) "App Library" else "Nothing" },
                onSelect = actions.onSwipeUp,
                testTag = "setting-swipe-up",
                optionTag = { "swipe-up-${it.name.lowercase()}" },
            )
        }
        if (visibility.shows(SettingsIds.GESTURE_DOUBLE_TAP)) {
            SettingsSwitchRow(
                title = "Double-tap to lock",
                subtitle = "Double-tap empty Home space to turn the screen off.",
                checked = settings.doubleTapLock,
                onCheckedChange = actions.onDoubleTapLock,
                testTag = "setting-double-tap-lock",
            )
        }
        if (needsService && !state.accessibilityEnabled &&
            (visibility.shows(SettingsIds.GESTURE_DOUBLE_TAP) ||
                visibility.shows(SettingsIds.GESTURE_SWIPE_DOWN))
        ) {
            SettingsNotice(
                title = "Duo's accessibility service is off",
                message = "These gestures use an optional Android accessibility service that only " +
                    "opens system panels and locks the screen. It never reads screen content.",
                actionLabel = "Set up",
                onAction = actions.onAccessibilitySetup,
                testTag = "accessibility-not-granted",
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Badges (FR-20, FR-23)
// ---------------------------------------------------------------------------

@Composable
internal fun BadgesSection(
    state: DuoSettingsUiState,
    actions: DuoSettingsActions,
    visibility: SettingsVisibility,
    modifier: Modifier = Modifier,
) {
    SettingsGroup(title = DuoSettingsSection.BADGES.title, modifier = modifier) {
        if (visibility.shows(SettingsIds.BADGE_STYLE)) {
            SettingsOptionRow(
                title = "Badge style",
                options = DuoBadgeStyle.entries.toList(),
                selected = state.settings.badgeStyle,
                label = {
                    when (it) {
                        DuoBadgeStyle.OFF -> "Off"
                        DuoBadgeStyle.DOT -> "Dot"
                        DuoBadgeStyle.NUMBER -> "Number"
                    }
                },
                onSelect = actions.onBadgeStyle,
                testTag = "setting-badge-style",
                optionTag = { "badge-style-${it.name.lowercase()}" },
            )
        }
        if (visibility.shows(SettingsIds.BADGE_ACCESS)) {
            if (state.badgeAccessGranted) {
                SettingsRow(
                    title = "Notification access",
                    subtitle = "Duo reads only which app a notification came from and how many " +
                        "there are. Titles and text are never read or saved.",
                    value = "On",
                    onClick = actions.onBadgeAccess,
                    testTag = "badge-access-granted",
                )
            } else {
                SettingsNotice(
                    title = "Turn on badges",
                    message = "Badges need Android's notification access. On a sideloaded install, " +
                        "open App info first, then ⋮ → Allow restricted settings, before the " +
                        "notification-access switch will move.",
                    actionLabel = "Turn on",
                    onAction = actions.onBadgeAccess,
                    secondaryLabel = "Open App info",
                    onSecondary = actions.onAppInfo,
                    testTag = "badge-access-turn-on",
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Hidden apps (FR-75)
// ---------------------------------------------------------------------------

@Composable
internal fun HiddenAppsSection(
    state: DuoSettingsUiState,
    visibility: SettingsVisibility,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsGroup(title = DuoSettingsSection.HIDDEN_APPS.title, modifier = modifier) {
        if (visibility.shows(SettingsIds.HIDDEN_APPS)) {
            val count = state.hiddenApps.size
            SettingsNavigationRow(
                title = "Hidden apps",
                subtitle = "Hidden apps are kept out of Home, the App Library, Suggestions and Search.",
                value = if (count == 0) "None" else count.toString(),
                onClick = onOpen,
                testTag = "setting-hidden-apps",
            )
        }
    }
}

/** The hidden-apps page. Hosts the list the App Library track already owns. */
@Composable
internal fun HiddenAppsDetail(
    state: DuoSettingsUiState,
    actions: DuoSettingsActions,
    modifier: Modifier = Modifier,
) {
    HiddenAppsList(
        apps = state.hiddenApps,
        icons = state.hiddenAppIcons,
        onOpen = actions.onOpenHiddenApp,
        onUnhide = actions.onUnhideApp,
        modifier = modifier.fillMaxSize().padding(horizontal = GROUP_INSET),
    )
}

// ---------------------------------------------------------------------------
// Private space (FR-76, FR-78)
// ---------------------------------------------------------------------------

@Composable
internal fun PrivateSpaceSection(
    state: DuoSettingsUiState,
    actions: DuoSettingsActions,
    visibility: SettingsVisibility,
    modifier: Modifier = Modifier,
) {
    val privateSpace = state.privateSpace
    SettingsGroup(title = DuoSettingsSection.PRIVATE_SPACE.title, modifier = modifier) {
        if (visibility.shows(SettingsIds.PRIVATE_STATE)) {
            when (privateSpace) {
                is PrivateSpaceState.Unsupported -> SettingsRow(
                    title = "Private space",
                    subtitle = privateSpace.reason.explain(),
                    value = "Unavailable",
                    testTag = "private-space-unsupported",
                )
                PrivateSpaceState.Locked -> {
                    SettingsRow(
                        title = "Private space",
                        subtitle = "While it is locked, its apps, badges and search results are hidden.",
                        value = "Locked",
                        testTag = "private-space-locked",
                    )
                    SettingsActionRow {
                        SettingsActionChip(
                            label = "Unlock",
                            onClick = { actions.onPrivateSpaceLocked(false) },
                            prominent = true,
                            testTag = "private-space-unlock",
                        )
                    }
                }
                is PrivateSpaceState.Unlocked -> {
                    SettingsRow(
                        title = "Private space",
                        value = "${privateSpace.apps.size} apps",
                        testTag = "private-space-unlocked",
                    )
                    SettingsActionRow {
                        SettingsActionChip(
                            label = "Lock",
                            onClick = { actions.onPrivateSpaceLocked(true) },
                            prominent = true,
                            testTag = "private-space-lock",
                        )
                    }
                }
            }
        }
        if (visibility.shows(SettingsIds.PRIVATE_HIDE)) {
            SettingsSwitchRow(
                title = "Hide private space",
                subtitle = "The container stays out of the App Library until you search \"private\".",
                checked = state.settings.hidePrivateContainer,
                enabled = privateSpace !is PrivateSpaceState.Unsupported,
                onCheckedChange = actions.onHidePrivateContainer,
                testTag = "setting-hide-private",
            )
        }
    }
}

/** The error table's "Private space API unavailable" row says *why*, rather than hiding. */
private fun PrivateSpaceUnsupportedReason.explain(): String = when (this) {
    PrivateSpaceUnsupportedReason.REQUIRES_ANDROID_15 ->
        "Private space needs Android 15 or newer."
    PrivateSpaceUnsupportedReason.NOT_DEFAULT_HOME ->
        "Android only shows the private space to the default Home app. Set Duo as Home to use it."
    PrivateSpaceUnsupportedReason.NO_PRIVATE_PROFILE ->
        "No private space has been set up yet. Create one in Android's Security & privacy settings."
}

// ---------------------------------------------------------------------------
// Backup (FR-82)
// ---------------------------------------------------------------------------

@Composable
internal fun BackupSection(
    actions: DuoSettingsActions,
    visibility: SettingsVisibility,
    modifier: Modifier = Modifier,
) {
    SettingsGroup(
        title = DuoSettingsSection.BACKUP.title,
        modifier = modifier,
        footer = "Saves the Home layouts, dock, folders, widgets and every setting on this screen. " +
            "Restoring shows a review before anything changes.",
    ) {
        if (visibility.shows(SettingsIds.BACKUP_EXPORT) || visibility.shows(SettingsIds.BACKUP_IMPORT)) {
            SettingsActionRow {
                if (visibility.shows(SettingsIds.BACKUP_EXPORT)) {
                    SettingsActionChip(
                        label = "Save",
                        onClick = actions.onExportLayout,
                        prominent = true,
                        testTag = "layout-export",
                    )
                }
                if (visibility.shows(SettingsIds.BACKUP_IMPORT)) {
                    SettingsActionChip(
                        label = "Restore",
                        onClick = actions.onImportLayout,
                        testTag = "layout-import",
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Help and About
// ---------------------------------------------------------------------------

@Composable
internal fun HelpSection(
    state: DuoSettingsUiState,
    actions: DuoSettingsActions,
    visibility: SettingsVisibility,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsGroup(title = DuoSettingsSection.HELP.title, modifier = modifier) {
        if (visibility.shows(SettingsIds.HELP_HOME_APP)) {
            SettingsRow(
                title = "Home app",
                subtitle = if (state.isDefaultHome) {
                    "Duo is your Home app."
                } else {
                    "Choose Duo in Android's Home settings to use it when you press Home."
                },
                testTag = "help-home-app",
            )
            SettingsActionRow {
                SettingsActionChip(
                    label = if (state.isDefaultHome) "Change home app" else "Set Duo as Home",
                    onClick = actions.onMakeDefault,
                    prominent = !state.isDefaultHome,
                    testTag = "default-home-settings",
                )
            }
        }
        if (visibility.shows(SettingsIds.HELP_OPEN)) {
            SettingsNavigationRow(
                title = "Help",
                subtitle = "Widgets, gestures, customizing a page and Discover.",
                onClick = onOpen,
                testTag = "setting-help",
            )
        }
    }
}

/**
 * The help page.
 *
 * This is the content the old customization sheet carried, kept reachable from Duo Settings so
 * superseding that sheet does not quietly drop the launcher's only in-app guidance.
 */
@Composable
internal fun HelpDetail(
    state: DuoSettingsUiState,
    actions: DuoSettingsActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = DuoTokens.space.xxxl),
    ) {
        SettingsGroup(title = "Home app") {
            SettingsInfoRow(
                icon = Icons.Rounded.Home,
                title = "Home app",
                detail = if (state.isDefaultHome) {
                    "Duo is your Home app. You can switch launchers in Android's Home settings."
                } else {
                    "Choose Duo in Android's Home settings to use it when you press Home."
                },
            )
            SettingsActionRow {
                SettingsActionChip(
                    label = if (state.isDefaultHome) "Change home app" else "Set Duo as Home",
                    onClick = actions.onMakeDefault,
                    prominent = !state.isDefaultHome,
                    testTag = "help-home-settings",
                )
            }
        }
        Column(Modifier.fillMaxWidth().padding(top = DuoTokens.space.xl)) {
            SettingsGroup(title = "Customizing") {
                SettingsInfoRow(
                    icon = Icons.Rounded.TouchApp,
                    title = "Customize any page",
                    detail = "Long-press empty space to enter Edit mode, then choose Customize. " +
                        "If a page is full, long-press the slim area at its left edge.",
                )
                SettingsInfoRow(
                    icon = Icons.Rounded.Widgets,
                    title = "Widgets",
                    detail = "Add Android widgets to empty Home cells. Hold a widget to move, " +
                        "resize or remove it.",
                )
                SettingsActionRow {
                    SettingsActionChip(
                        label = "Add widget to this page",
                        onClick = actions.onAddWidget,
                        testTag = "help-add-widget",
                    )
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(top = DuoTokens.space.xl)) {
            SettingsGroup(title = "Gestures") {
                SettingsInfoRow(
                    icon = Icons.Rounded.SwipeDown,
                    title = "Notifications and quick settings",
                    detail = "Set Swipe down to Notifications in Gestures. The first time, Duo " +
                        "explains Android's optional Accessibility setting. The service only " +
                        "opens the system panels.",
                )
                SettingsActionRow {
                    SettingsActionChip(
                        label = "Set up shade gestures",
                        onClick = actions.onAccessibilitySetup,
                        testTag = "help-shade-setup",
                    )
                }
                SettingsInfoRow(
                    icon = Icons.Rounded.Explore,
                    title = "Discover",
                    detail = "Swipe right from the first Home page while Google Discover is the " +
                        "leading page. If Google cannot provide the feed, Duo keeps a Home return " +
                        "and recovery actions available.",
                )
            }
        }
    }
}

@Composable
internal fun AboutSection(
    state: DuoSettingsUiState,
    visibility: SettingsVisibility,
    modifier: Modifier = Modifier,
) {
    SettingsGroup(title = DuoSettingsSection.ABOUT.title, modifier = modifier) {
        if (visibility.shows(SettingsIds.ABOUT_VERSION)) {
            SettingsRow(
                title = "Version",
                value = state.versionName.ifBlank { "—" },
                testTag = "about-version",
            )
        }
        if (visibility.shows(SettingsIds.ABOUT_PRIVACY)) {
            SettingsRow(
                title = "Privacy",
                subtitle = "Duo has no internet permission, no analytics and no accounts. Every " +
                    "optional access — notifications, contacts, calendar and the lock gesture — " +
                    "is asked for where it is used and can be revoked at any time.",
                testTag = "about-privacy",
            )
        }
    }
}
