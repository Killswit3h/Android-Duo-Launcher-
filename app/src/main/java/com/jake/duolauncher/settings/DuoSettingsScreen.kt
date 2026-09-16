package com.jake.duolauncher.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.jake.duolauncher.AppearanceMode
import com.jake.duolauncher.AppearanceState
import com.jake.duolauncher.AppLibraryView
import com.jake.duolauncher.DockConfig
import com.jake.duolauncher.DockSide
import com.jake.duolauncher.DuoBadgeStyle
import com.jake.duolauncher.DuoFontChoice
import com.jake.duolauncher.DuoSettings
import com.jake.duolauncher.GridSpec
import com.jake.duolauncher.LayoutMode
import com.jake.duolauncher.LayoutPreset
import com.jake.duolauncher.LayoutTarget
import com.jake.duolauncher.LeadingPageKind
import com.jake.duolauncher.ReflowOutcome
import com.jake.duolauncher.SwipeDownAction
import com.jake.duolauncher.SwipeUpAction
import com.jake.duolauncher.WallpaperSource
import com.jake.duolauncher.design.DuoTokens
import com.jake.duolauncher.design.GlassLevel
import com.jake.duolauncher.design.GlassSurface
import com.jake.duolauncher.design.LocalDuoTypography
import com.jake.duolauncher.design.duoColors
import com.jake.duolauncher.design.rememberMotionEnabled
import com.jake.duolauncher.icons.IconAppearance
import com.jake.duolauncher.icons.IconPackState
import com.jake.duolauncher.icons.IconShape
import com.jake.duolauncher.icons.InstalledIconPack
import com.jake.duolauncher.library.LibraryApp
import com.jake.duolauncher.library.LibraryIcons
import com.jake.duolauncher.profiles.PrivateSpaceState
import kotlinx.coroutines.delay

/**
 * **Duo Settings** (FR-79, FR-80, AC-64).
 *
 * The full-screen, iOS inset-grouped home for every setting this build added. Most of them — glass
 * level, accent, icon appearance and shape, icon packs, badge style, gestures, grids, dock side and
 * capacity, leading page, hidden apps, private space, layout lock and auto-add — have no other route
 * in the UI, so this screen is what makes them real.
 *
 * ## Shape of the API
 *
 * The screen owns no model. It takes [DuoSettingsUiState] (everything it draws) and
 * [DuoSettingsActions] (everything it can do), so the host wires each callback to the matching
 * `LauncherModel` setter and this file stays free of the view model, of Android system services and
 * of permission plumbing. That is also what lets every state — including the ones that need access
 * Duo has not been granted — be rendered from a `@Preview`.
 *
 * ## Honest access states
 *
 * Badges, contacts and the private space each need something the user controls. None of them is
 * silently hidden: each shows what is missing and the route that fixes it, including Android's
 * **Allow restricted settings** step that a sideloaded install must take before a
 * notification-listener toggle will move (FR-23, error table).
 */
@Composable
internal fun DuoSettingsScreen(
    state: DuoSettingsUiState,
    actions: DuoSettingsActions,
    modifier: Modifier = Modifier,
    /** Seeds the search field. Used by previews and tests; the host leaves it empty. */
    initialQuery: String = "",
) {
    val motionEnabled = rememberMotionEnabled()

    var query by rememberSaveable { mutableStateOf(initialQuery) }
    var detail by rememberSaveable { mutableStateOf<DuoSettingsDetail?>(null) }
    // The target the grid controls edit. UI state, not model state: which layout the user is looking
    // at is a property of this screen, not of the launcher.
    var gridTarget by rememberSaveable { mutableStateOf(state.activeTarget) }
    var presetWide by rememberSaveable { mutableStateOf(state.expandedActive) }
    // FR-33: the count from the last grid change, shown until it is undone or 8 seconds pass.
    var reflowMoved by remember { mutableStateOf<Int?>(null) }

    val index = remember { SettingsSearchIndex() }
    val visibility = remember(query) { SettingsVisibility(index.matchingIds(query)) }

    LaunchedEffect(state.layoutMode) {
        if (state.layoutMode == LayoutMode.MIRRORED) gridTarget = LayoutTarget.MIRRORED
        else if (gridTarget == LayoutTarget.MIRRORED) gridTarget = state.activeTarget
    }
    LaunchedEffect(reflowMoved) {
        if (reflowMoved != null) {
            delay(REFLOW_UNDO_MILLIS)
            reflowMoved = null
        }
    }

    BackHandler(enabled = detail != null) { detail = null }

    val applyGrid: (GridSpec) -> Unit = { grid ->
        val outcome = actions.onGrid(gridTarget, grid)
        reflowMoved = outcome?.movedCount?.takeIf { it > 0 }
    }

    GlassSurface(
        level = GlassLevel.PANEL,
        shape = RectangleShape,
        modifier = modifier.fillMaxSize().testTag("duo-settings"),
    ) {
        Column(Modifier.fillMaxSize().imePadding()) {
            SettingsTitleBar(
                title = detail?.title ?: "Duo Settings",
                onBack = detail?.let { { detail = null } },
                onClose = actions.onClose,
            )
            when (detail) {
                null -> SettingsOverview(
                    state = state,
                    actions = actions,
                    visibility = visibility,
                    query = query,
                    onQuery = { query = it },
                    gridTarget = gridTarget,
                    onGridTarget = { gridTarget = it },
                    onGrid = applyGrid,
                    presetWide = presetWide,
                    onPresetWide = { presetWide = it },
                    onDetail = { detail = it },
                    modifier = Modifier.weight(1f),
                )
                DuoSettingsDetail.HIDDEN_APPS -> HiddenAppsDetail(
                    state = state,
                    actions = actions,
                    modifier = Modifier.weight(1f),
                )
                DuoSettingsDetail.HELP -> HelpDetail(
                    state = state,
                    actions = actions,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // FR-33: "Moved N items to a new page", with Undo, for 8 seconds.
        val moved = reflowMoved
        if (moved != null) {
            ReflowBanner(
                moved = moved,
                motionEnabled = motionEnabled,
                onUndo = {
                    actions.onUndoEdit()
                    reflowMoved = null
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(GROUP_INSET),
            )
        }
    }
}

/** The scrolling list of inset groups. */
@Composable
private fun SettingsOverview(
    state: DuoSettingsUiState,
    actions: DuoSettingsActions,
    visibility: SettingsVisibility,
    query: String,
    onQuery: (String) -> Unit,
    gridTarget: LayoutTarget,
    onGridTarget: (LayoutTarget) -> Unit,
    onGrid: (GridSpec) -> Unit,
    presetWide: Boolean,
    onPresetWide: (Boolean) -> Unit,
    onDetail: (DuoSettingsDetail) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = duoColors()
    val type = LocalDuoTypography.current
    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("duo-settings-list"),
        contentPadding = PaddingValues(bottom = DuoTokens.space.xxxl),
        verticalArrangement = Arrangement.spacedBy(DuoTokens.space.xl),
    ) {
        item(key = "search") {
            SettingsSearchField(
                query = query,
                onQuery = onQuery,
                modifier = Modifier.padding(horizontal = GROUP_INSET),
            )
        }
        if (visibility.isEmpty) {
            item(key = "empty") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = GROUP_INSET)
                        .testTag("settings-no-results")
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    verticalArrangement = Arrangement.spacedBy(DuoTokens.space.xs),
                ) {
                    Text(text = "No settings found", style = type.subhead, color = colors.label1)
                    Text(
                        text = "Try a different word, such as \"badge\", \"grid\" or \"gestures\".",
                        style = type.footnote,
                        color = colors.label2,
                    )
                }
            }
        }
        if (visibility.showsSection(DuoSettingsSection.WALLPAPER)) {
            item(key = "wallpaper") { WallpaperSection(state, actions, visibility) }
        }
        if (visibility.showsSection(DuoSettingsSection.APPEARANCE)) {
            item(key = "appearance") { AppearanceSection(state, actions, visibility) }
        }
        if (visibility.showsSection(DuoSettingsSection.ICONS)) {
            item(key = "icons") { IconsSection(state, actions, visibility) }
        }
        if (visibility.showsSection(DuoSettingsSection.HOME_AND_DOCK)) {
            item(key = "home") {
                HomeAndDockSection(
                    state = state,
                    actions = actions,
                    visibility = visibility,
                    gridTarget = gridTarget,
                    onGridTarget = onGridTarget,
                    onGrid = onGrid,
                    presetWide = presetWide,
                    onPresetWide = onPresetWide,
                )
            }
        }
        if (visibility.showsSection(DuoSettingsSection.TODAY)) {
            item(key = "today") { TodaySection(state, actions, visibility) }
        }
        if (visibility.showsSection(DuoSettingsSection.LIBRARY_AND_SEARCH)) {
            item(key = "library") { LibraryAndSearchSection(state, actions, visibility) }
        }
        if (visibility.showsSection(DuoSettingsSection.GESTURES)) {
            item(key = "gestures") { GesturesSection(state, actions, visibility) }
        }
        if (visibility.showsSection(DuoSettingsSection.BADGES)) {
            item(key = "badges") { BadgesSection(state, actions, visibility) }
        }
        if (visibility.showsSection(DuoSettingsSection.HIDDEN_APPS)) {
            item(key = "hidden") {
                HiddenAppsSection(
                    state = state,
                    visibility = visibility,
                    onOpen = { onDetail(DuoSettingsDetail.HIDDEN_APPS) },
                )
            }
        }
        if (visibility.showsSection(DuoSettingsSection.PRIVATE_SPACE)) {
            item(key = "private") { PrivateSpaceSection(state, actions, visibility) }
        }
        if (visibility.showsSection(DuoSettingsSection.BACKUP)) {
            item(key = "backup") { BackupSection(actions, visibility) }
        }
        if (visibility.showsSection(DuoSettingsSection.HELP)) {
            item(key = "help") {
                HelpSection(
                    state = state,
                    actions = actions,
                    visibility = visibility,
                    onOpen = { onDetail(DuoSettingsDetail.HELP) },
                )
            }
        }
        if (visibility.showsSection(DuoSettingsSection.ABOUT)) {
            item(key = "about") { AboutSection(state, visibility) }
        }
    }
}

/** The screen's own title bar: back into the overview, and a close that leaves Settings. */
@Composable
private fun SettingsTitleBar(
    title: String,
    onBack: (() -> Unit)?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = duoColors()
    val type = LocalDuoTypography.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = TITLE_BAR_HEIGHT)
            .padding(horizontal = DuoTokens.space.sm, vertical = DuoTokens.space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            Box(
                modifier = Modifier
                    .sizeIn(minWidth = TOUCH_TARGET, minHeight = TOUCH_TARGET)
                    .clickable(onClick = onBack)
                    .testTag("settings-back")
                    .semantics {
                        role = Role.Button
                        contentDescription = "Back to Duo Settings"
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = null,
                    tint = colors.label1,
                )
            }
        } else {
            Spacer(Modifier.width(DuoTokens.space.sm))
        }
        Text(
            text = title,
            style = type.title2,
            color = colors.label1,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = DuoTokens.space.sm)
                .semantics { heading() },
        )
        Box(
            modifier = Modifier
                .sizeIn(minWidth = TOUCH_TARGET, minHeight = TOUCH_TARGET)
                .clickable(onClick = onClose)
                .testTag("settings-close")
                .semantics {
                    role = Role.Button
                    contentDescription = "Close Duo Settings"
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(imageVector = Icons.Rounded.Close, contentDescription = null, tint = colors.label1)
        }
    }
}

/**
 * FR-33's snackbar: "Moved N items to a new page", with **Undo**.
 *
 * It fades in only while motion is allowed; at animator duration scale 0 it simply appears
 * (FR-11, NFR-A4). It is a live region, so TalkBack announces the move rather than leaving a
 * sighted-only explanation of why the Home screen just changed.
 */
@Composable
private fun ReflowBanner(
    moved: Int,
    motionEnabled: Boolean,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = duoColors()
    val type = LocalDuoTypography.current
    val message = if (moved == 1) "Moved 1 item to a new page" else "Moved $moved items to a new page"
    val appear by animateFloatAsState(
        targetValue = 1f,
        animationSpec = if (motionEnabled) DuoTokens.motion.standard() else snap(),
        label = "reflow-banner",
    )
    GlassSurface(
        level = GlassLevel.MENU,
        shape = DuoTokens.radius.card,
        modifier = modifier
            .fillMaxWidth()
            .alpha(appear)
            .testTag("reflow-snackbar"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = ROW_MIN_HEIGHT)
                .padding(horizontal = DuoTokens.space.lg, vertical = DuoTokens.space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                style = type.subhead,
                color = colors.label1,
                modifier = Modifier
                    .weight(1f)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
            Spacer(Modifier.width(DuoTokens.space.sm))
            SettingsActionChip(
                label = "Undo",
                onClick = onUndo,
                prominent = true,
                testTag = "reflow-undo",
            )
        }
    }
}

/** The pages that are too long, or too list-like, to sit inline in the overview. */
internal enum class DuoSettingsDetail(val title: String) {
    HIDDEN_APPS("Hidden apps"),
    HELP("Help"),
}

// ---------------------------------------------------------------------------
// The host contract
// ---------------------------------------------------------------------------

/**
 * Everything Duo Settings draws.
 *
 * Built by the host from `LauncherState`, the icon-pack repository, the badge repository and the
 * private-space repository. Defaults describe a fresh install with nothing granted, which is also
 * what the previews render.
 */
@Immutable
internal data class DuoSettingsUiState(
    /** The schema-9 settings block, `LauncherState.settings`. */
    val settings: DuoSettings = DuoSettings(),
    /** Every stored layout's grid, so the grid controls can edit one the user is not looking at. */
    val grids: Map<LayoutTarget, GridSpec> = emptyMap(),
    /** `LauncherState.layoutSet.mode`. */
    val layoutMode: LayoutMode = LayoutMode.MIRRORED,
    /** `LauncherState.activeTarget`: which grid the screen opens on. */
    val activeTarget: LayoutTarget = LayoutTarget.MIRRORED,
    /** `LauncherState.expandedActive`: which display's presets the screen opens on. */
    val expandedActive: Boolean = false,
    /** `LauncherState.layoutSet.dock`. */
    val dock: DockConfig = DockConfig(),
    /** `LauncherState.leadingPage.kind`. */
    val leadingPage: LeadingPageKind = LeadingPageKind.TODAY,
    /** The per-display geometry presets (`LauncherState.compact` / `.expanded`). */
    val compact: LayoutPreset = LayoutPreset(),
    val expanded: LayoutPreset = LayoutPreset(),
    /** `LauncherState.labels`. */
    val labels: Boolean = true,
    /** `LauncherState.googleSearch`. */
    val googleSearch: Boolean = true,
    /** The existing light/dark/system/sunrise store. */
    val appearance: AppearanceState = AppearanceState(),
    val isDefaultHome: Boolean = false,
    /** `LauncherState.canUndoEdit`, which is also what makes the FR-33 Undo trustworthy. */
    val canUndo: Boolean = false,
    /** `BadgeRepository.accessGranted` (FR-23). */
    val badgeAccessGranted: Boolean = false,
    /** Whether `READ_CONTACTS` is granted right now (error table: "Contacts access denied"). */
    val contactsGranted: Boolean = false,
    /** Whether Duo's accessibility service is enabled (FR-53, error table). */
    val accessibilityEnabled: Boolean = false,
    /** Whether the existing Discover path is available on this device (FR-55). */
    val discoverAvailable: Boolean = false,
    /** `PrivateSpaceRepository.state` (FR-76, FR-78). */
    val privateSpace: PrivateSpaceState =
        PrivateSpaceState.Unsupported(com.jake.duolauncher.profiles.PrivateSpaceUnsupportedReason.NO_PRIVATE_PROFILE),
    /** `IconPackRepository.installed()` (FR-18). */
    val iconPacks: List<InstalledIconPack> = emptyList(),
    /** `IconPackRepository.state` (FR-18, error table). */
    val iconPackState: IconPackState = IconPackState.None,
    /** The hidden apps, already mapped through `libraryAppsOf` (FR-75). */
    val hiddenApps: List<LibraryApp> = emptyList(),
    /** How the hidden-apps list draws its icons. */
    val hiddenAppIcons: LibraryIcons = LibraryIcons(),
    /** The staged-photo background state. */
    val wallpaper: WallpaperUiState = WallpaperUiState(),
    /** `BuildConfig.VERSION_NAME`. */
    val versionName: String = "",
) {
    /** The grid the controls edit for [target], falling back to the active layout's. */
    fun gridFor(target: LayoutTarget): GridSpec = grids[target] ?: GridSpec(4, 6)

    /** The preset for the display the user is editing. */
    fun preset(wide: Boolean): LayoutPreset = if (wide) expanded else compact
}

/** The staged-photo half of the wallpaper section, from `LauncherBackgroundController`. */
@Immutable
internal data class WallpaperUiState(
    val previewPending: Boolean = false,
    val loading: Boolean = false,
    val photoSelected: Boolean = false,
    val message: String? = null,
)

/**
 * Everything Duo Settings can do.
 *
 * Each callback maps to exactly one `LauncherModel` setter or one system route, named for what the
 * user is doing rather than for the setter, so the host's wiring reads as a list of equalities.
 * Every one defaults to a no-op so a preview, or a host that has not wired a feature yet, renders
 * rather than crashes.
 */
@Immutable
internal class DuoSettingsActions(
    /** Leaves Settings. */
    val onClose: () -> Unit = {},

    // ---- Wallpaper (FR-4, FR-9) ----
    /** `model.setWallpaperSource`. */
    val onWallpaperSource: (WallpaperSource) -> Unit = {},
    /** `model.setDimInDark`. */
    val onDimInDark: (Boolean) -> Unit = {},
    /** `backgrounds.choosePhoto`. */
    val onChoosePhoto: () -> Unit = {},
    /** `backgrounds.applyPreview`. */
    val onApplyPreview: () -> Unit = {},
    /** `backgrounds.cancelPreview`. */
    val onCancelPreview: () -> Unit = {},
    /** `backgrounds.reset`. */
    val onResetBackground: () -> Unit = {},
    /** `backgrounds.clearMessage`. */
    val onClearWallpaperMessage: () -> Unit = {},
    /** Opens Android's wallpaper preview. */
    val onAndroidWallpaper: () -> Unit = {},

    // ---- Appearance (FR-5, FR-6, FR-8, FR-13) ----
    /** The existing appearance store's four entry points. */
    val onAppearanceMode: (AppearanceMode) -> Unit = {},
    val onAppearanceManual: (String, Double, Double) -> Unit = { _, _, _ -> },
    val onAppearanceDeviceLocation: () -> Unit = {},
    val onAppearanceClear: () -> Unit = {},
    /** `model.setGlass` (FR-5). */
    val onGlass: (Int) -> Unit = {},
    /** `model.setReduceTransparency` (FR-6). */
    val onReduceTransparency: (Boolean) -> Unit = {},
    /** `model.setAccent`; null selects Automatic (FR-8). */
    val onAccent: (Int?) -> Unit = {},
    /** `model.setFont` (FR-13). */
    val onFont: (DuoFontChoice) -> Unit = {},

    // ---- Icons (FR-14 to FR-19) ----
    /** `model.setIconAppearance`. */
    val onIconAppearance: (IconAppearance) -> Unit = {},
    /** `model.setIconTint(color, intensity)`. */
    val onIconTint: (Int, Int) -> Unit = { _, _ -> },
    /** `model.setIconShape`. */
    val onIconShape: (IconShape) -> Unit = {},
    /** `model.setLargeIcons`. */
    val onLargeIcons: (Boolean) -> Unit = {},
    /** `model.setLabels`. */
    val onLabels: (Boolean) -> Unit = {},
    /** `model.setIconPack` plus `IconPackRepository.select`; null clears and is also Reset. */
    val onIconPack: (String?) -> Unit = {},

    // ---- Home and dock (FR-31 to FR-33, FR-37, FR-38, FR-41, FR-49, FR-50) ----
    /**
     * `model.setGrid(target, grid, providerLimits)`.
     *
     * Returns the [ReflowOutcome] so the screen can show FR-33's "Moved N items to a new page".
     * The host supplies `providerLimits`, because widget provider limits need display metrics this
     * screen does not have.
     */
    val onGrid: (LayoutTarget, GridSpec) -> ReflowOutcome? = { _, _ -> null },
    /** `model.setLayoutMode` (FR-32). */
    val onLayoutMode: (LayoutMode) -> Unit = {},
    /** `model.setDockSide` (FR-37). */
    val onDockSide: (DockSide) -> Unit = {},
    /** `model.setDockCapacity` (FR-38). */
    val onDockCapacity: (Int) -> Unit = {},
    /** `model.setPreset(wide, preset)`. */
    val onPreset: (Boolean, LayoutPreset) -> Unit = { _, _ -> },
    /** `model.setDuoStatus` (FR-41). */
    val onDuoStatus: (Boolean) -> Unit = {},
    /** `model.setLockLayout` (FR-49). */
    val onLockLayout: (Boolean) -> Unit = {},
    /** `model.setAutoAddApps` (FR-50). */
    val onAutoAddApps: (Boolean) -> Unit = {},
    /** `model.undoEdit()`, which is also FR-33's Undo. */
    val onUndoEdit: () -> Unit = {},

    // ---- Today View (FR-55) ----
    /** `model.setLeadingPage`. */
    val onLeadingPage: (LeadingPageKind) -> Unit = {},

    // ---- App Library and Search (FR-69, FR-72, FR-84) ----
    /** `model.setLibraryView`. */
    val onLibraryView: (AppLibraryView) -> Unit = {},
    /** `model.setSearchContacts`, after requesting `READ_CONTACTS` when turning it on. */
    val onSearchContacts: (Boolean) -> Unit = {},
    /** Opens Duo's App info, where a denied contacts permission is re-granted. */
    val onContactsPermission: () -> Unit = {},
    /** `model.setSuggestions` (FR-84). */
    val onSuggestions: (Boolean) -> Unit = {},
    /** `SuggestionRanker.clear()` (FR-84, AC-67). */
    val onClearSuggestionHistory: () -> Unit = {},
    /** `model.setGoogleSearch`. */
    val onGoogleSearch: (Boolean) -> Unit = {},

    // ---- Gestures (FR-51 to FR-53) ----
    /** `model.setGesture(swipeDown = …)`. */
    val onSwipeDown: (SwipeDownAction) -> Unit = {},
    /** `model.setGesture(swipeUp = …)`. */
    val onSwipeUp: (SwipeUpAction) -> Unit = {},
    /** `model.setGesture(doubleTapLock = …)`. */
    val onDoubleTapLock: (Boolean) -> Unit = {},
    /** The existing shade/accessibility setup sheet. */
    val onAccessibilitySetup: () -> Unit = {},

    // ---- Badges (FR-20, FR-23) ----
    /** `model.setBadgeStyle`. */
    val onBadgeStyle: (DuoBadgeStyle) -> Unit = {},
    /** Starts `NotificationAccess.settingsIntent`, falling back to `listSettingsIntent`. */
    val onBadgeAccess: () -> Unit = {},
    /** Starts `NotificationAccess.appInfoIntent`: the restricted-settings route (FR-23). */
    val onAppInfo: () -> Unit = {},

    // ---- Hidden apps (FR-75) ----
    /** `model.unhideApp`. */
    val onUnhideApp: (LibraryApp) -> Unit = {},
    /** Launches a hidden app from the list. */
    val onOpenHiddenApp: (LibraryApp) -> Unit = {},

    // ---- Private space (FR-76, FR-78) ----
    /** `PrivateSpaceRepository.setLocked`. */
    val onPrivateSpaceLocked: (Boolean) -> Unit = {},
    /** `model.setHidePrivateContainer`. */
    val onHidePrivateContainer: (Boolean) -> Unit = {},

    // ---- Backup (FR-82) ----
    /** `backups.startExport()`. */
    val onExportLayout: () -> Unit = {},
    /** `backups.startImport()`. */
    val onImportLayout: () -> Unit = {},

    // ---- Help ----
    /** Opens Android's Home-app settings. */
    val onMakeDefault: () -> Unit = {},
    /** Opens the widget picker on the current page. */
    val onAddWidget: () -> Unit = {},
)

/** FR-33 offers Undo for 8 seconds. */
private const val REFLOW_UNDO_MILLIS = 8_000L

private val TITLE_BAR_HEIGHT = 64.dp
private val TOUCH_TARGET = 48.dp
