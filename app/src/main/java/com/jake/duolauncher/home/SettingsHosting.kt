package com.jake.duolauncher.home

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.jake.duolauncher.settings.DuoSettingsScreen
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jake.duolauncher.AppEntry
import com.jake.duolauncher.AppearanceMode
import com.jake.duolauncher.AppearanceState
import com.jake.duolauncher.DiscoverEmbedding
import com.jake.duolauncher.LauncherBackgroundController
import com.jake.duolauncher.LauncherModel
import com.jake.duolauncher.LauncherState
import com.jake.duolauncher.LayoutTarget
import com.jake.duolauncher.MainActivity
import com.jake.duolauncher.SystemShadeAccessibilityService
import com.jake.duolauncher.badges.NotificationAccess
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.jake.duolauncher.library.LibraryIcons
import com.jake.duolauncher.library.libraryAppsOf
import com.jake.duolauncher.profiles.DuoPrivateSpace
import com.jake.duolauncher.search.rememberContactsGranted
import com.jake.duolauncher.settings.DuoSettingsActions
import com.jake.duolauncher.settings.DuoSettingsUiState
import com.jake.duolauncher.settings.WallpaperUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The host side of **Duo Settings** (FR-79, FR-80).
 *
 * `DuoSettingsScreen` owns no model on purpose: it takes everything it draws as one state and
 * everything it can do as one bundle of callbacks. This file is the other half of that bargain — the
 * single place where those two shapes are filled in from `LauncherState`, the repositories and the
 * activity. Keeping it here rather than inside the sheet means the settings screen stays previewable
 * and the wiring stays readable as a list of equalities.
 */

/**
 * Duo Settings as Home hosts it (FR-79, FR-80).
 *
 * Full-screen rather than a bottom sheet, which is what FR-79 asks for and what the screen is built
 * as: it draws its own title bar, its own back behaviour between overview and detail, and its own
 * full-bleed glass panel. Hosting it inside the modal sheet the dock and pin pickers share would
 * have boxed a full screen into 92% of the height and given it two competing back handlers.
 */
@Composable
internal fun DuoSettingsOverlay(
    state: LauncherState,
    model: LauncherModel,
    activity: MainActivity,
    appearance: AppearanceState,
    isDefaultHome: Boolean,
    onClose: () -> Unit,
    onMakeDefault: () -> Unit,
    onAddWidget: () -> Unit,
    onShadeSetup: () -> Unit,
    onWallpaperPreview: () -> Unit,
    onLaunch: (AppEntry) -> Unit,
    onAppearanceMode: (AppearanceMode) -> Unit,
    onAppearanceManual: (String, Double, Double) -> Unit,
    onAppearanceDeviceLocation: () -> Unit,
    onAppearanceClear: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val appsById = remember(state.apps) { state.apps.associateBy { it.id } }
    val uiState = rememberDuoSettingsUiState(state, model, appearance, activity.backgrounds, isDefaultHome)
    // Back leaves Settings. The screen's own handler takes a detail page back to the overview first,
    // and it is declared inside this one, so the innermost open thing closes first.
    BackHandler { onClose() }
    Box(Modifier.fillMaxSize().testTag("duo-settings-overlay")) {
        DuoSettingsScreen(
            state = uiState,
            actions = duoSettingsActions(
                context = context,
                model = model,
                activity = activity,
                scope = scope,
                onClose = onClose,
                onMakeDefault = onMakeDefault,
                onAddWidget = onAddWidget,
                onShadeSetup = onShadeSetup,
                onWallpaperPreview = onWallpaperPreview,
                onLaunch = onLaunch,
                appsById = appsById,
                onAppearanceMode = onAppearanceMode,
                onAppearanceManual = onAppearanceManual,
                onAppearanceDeviceLocation = onAppearanceDeviceLocation,
                onAppearanceClear = onAppearanceClear,
            ),
        )
    }
}

/** Everything Duo Settings draws, assembled from the live state and the repositories. */
@Composable
internal fun rememberDuoSettingsUiState(
    state: LauncherState,
    model: LauncherModel,
    appearance: AppearanceState,
    backgrounds: LauncherBackgroundController,
    isDefaultHome: Boolean,
): DuoSettingsUiState {
    val context = LocalContext.current
    val badges = rememberBadgeRepository()
    val badgeAccess by badges.accessGranted.collectAsStateWithLifecycle()
    val privateSpace by DuoPrivateSpace.repository(context).state.collectAsStateWithLifecycle()
    val iconPacks = remember(context) { DuoHost.iconPacks(context) }
    val iconPackState by iconPacks.state.collectAsStateWithLifecycle()
    // Enumerating installed packs hits the package manager, so it happens once, off the main thread.
    val installedPacks by produceState(initialValue = emptyList<com.jake.duolauncher.icons.InstalledIconPack>(), iconPacks) {
        value = runCatching { iconPacks.installed() }.getOrDefault(emptyList())
    }
    val renderer = rememberIconRenderer()
    val iconStyle = model.iconStyle
    val hidden = remember(state.apps, state.hiddenApps) {
        libraryAppsOf(state.apps.filter { it.id in state.hiddenApps })
    }
    val hiddenFallbacks = remember(state.apps, state.hiddenApps) {
        state.apps.filter { it.id in state.hiddenApps }.associate { it.id to it.icon.asImageBitmap() }
    }
    val versionName = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }
    return DuoSettingsUiState(
        settings = state.settings,
        grids = remember(state.layoutSet) {
            LayoutTarget.entries.associateWith { state.layoutSet.layout(it).grid }
        },
        layoutMode = state.layoutSet.mode,
        activeTarget = state.activeTarget,
        expandedActive = state.expandedActive,
        dock = state.layoutSet.dock,
        leadingPage = state.leadingPage.kind,
        compact = state.compact,
        expanded = state.expanded,
        labels = state.labels,
        googleSearch = state.googleSearch,
        appearance = appearance,
        isDefaultHome = isDefaultHome,
        canUndo = state.canUndoEdit,
        badgeAccessGranted = badgeAccess,
        contactsGranted = rememberContactsGranted(),
        accessibilityEnabled = rememberShadeServiceEnabled(),
        discoverAvailable = remember(context) { DiscoverEmbedding.supported(context) },
        privateSpace = privateSpace,
        iconPacks = installedPacks,
        iconPackState = iconPackState,
        hiddenApps = hidden,
        hiddenAppIcons = remember(renderer, iconStyle, hiddenFallbacks) {
            LibraryIcons(renderer, iconStyle, hiddenFallbacks)
        },
        wallpaper = WallpaperUiState(
            previewPending = backgrounds.previewPending,
            loading = backgrounds.loading,
            photoSelected = backgrounds.photoSelected,
            message = backgrounds.errorMessage ?: backgrounds.successMessage,
        ),
        versionName = versionName,
    )
}

/**
 * Everything Duo Settings can do.
 *
 * Each callback is one model setter or one system route. The two that are not: `onGrid` returns the
 * [com.jake.duolauncher.ReflowOutcome] so the screen can show FR-33's "Moved N items to a new page"
 * with Undo, and `onIconPack` has to both record the choice and ask the pack repository to load it.
 */
internal fun duoSettingsActions(
    context: Context,
    model: LauncherModel,
    activity: MainActivity,
    scope: CoroutineScope,
    onClose: () -> Unit,
    onMakeDefault: () -> Unit,
    onAddWidget: () -> Unit,
    onShadeSetup: () -> Unit,
    onWallpaperPreview: () -> Unit,
    onLaunch: (AppEntry) -> Unit,
    appsById: Map<String, AppEntry>,
    onAppearanceMode: (AppearanceMode) -> Unit,
    onAppearanceManual: (String, Double, Double) -> Unit,
    onAppearanceDeviceLocation: () -> Unit,
    onAppearanceClear: () -> Unit,
): DuoSettingsActions {
    val backgrounds = activity.backgrounds
    val packs = DuoHost.iconPacks(context)
    val ranker = DuoHost.ranker(context)
    return DuoSettingsActions(
        onClose = onClose,
        // ---- Wallpaper ----
        onWallpaperSource = model::setWallpaperSource,
        onDimInDark = model::setDimInDark,
        onChoosePhoto = backgrounds::choosePhoto,
        onApplyPreview = backgrounds::applyPreview,
        onCancelPreview = backgrounds::cancelPreview,
        onResetBackground = backgrounds::reset,
        onClearWallpaperMessage = backgrounds::clearMessage,
        onAndroidWallpaper = onWallpaperPreview,
        // ---- Appearance ----
        onAppearanceMode = onAppearanceMode,
        onAppearanceManual = onAppearanceManual,
        onAppearanceDeviceLocation = onAppearanceDeviceLocation,
        onAppearanceClear = onAppearanceClear,
        onGlass = model::setGlass,
        onReduceTransparency = model::setReduceTransparency,
        onAccent = model::setAccent,
        onFont = model::setFont,
        // ---- Icons ----
        onIconAppearance = model::setIconAppearance,
        onIconTint = model::setIconTint,
        onIconShape = model::setIconShape,
        onLargeIcons = model::setLargeIcons,
        onLabels = model::setLabels,
        onIconPack = { pack ->
            model.setIconPack(pack)
            scope.launch { packs.select(pack) }
        },
        // ---- Home and dock ----
        onGrid = { target, grid -> model.setGrid(target, grid) },
        onLayoutMode = model::setLayoutMode,
        onDockSide = model::setDockSide,
        onDockCapacity = model::setDockCapacity,
        onPreset = model::setPreset,
        onDuoStatus = model::setDuoStatus,
        onLockLayout = model::setLockLayout,
        onAutoAddApps = model::setAutoAddApps,
        onUndoEdit = { model.undoEdit() },
        // ---- Today View ----
        onLeadingPage = model::setLeadingPage,
        // ---- App Library and Search ----
        onLibraryView = model::setLibraryView,
        onSearchContacts = model::setSearchContacts,
        onContactsPermission = { context.startAppInfo() },
        onSuggestions = { enabled ->
            model.setSuggestions(enabled)
            // FR-84: turning Suggestions off also deletes the history already recorded.
            ranker.setEnabled(enabled)
        },
        onClearSuggestionHistory = ranker::clear,
        onGoogleSearch = model::setGoogleSearch,
        // ---- Gestures ----
        onSwipeDown = { model.setGesture(swipeDown = it) },
        onSwipeUp = { model.setGesture(swipeUp = it) },
        onDoubleTapLock = { model.setGesture(doubleTapLock = it) },
        onAccessibilitySetup = onShadeSetup,
        // ---- Badges ----
        onBadgeStyle = model::setBadgeStyle,
        onBadgeAccess = {
            runCatching { context.startActivity(NotificationAccess.settingsIntent(context)) }
                .recoverCatching { context.startActivity(NotificationAccess.listSettingsIntent()) }
        },
        onAppInfo = { context.startAppInfo() },
        // ---- Hidden apps ----
        onUnhideApp = { model.unhideApp(it.id) },
        onOpenHiddenApp = { app -> appsById[app.id]?.let(onLaunch) },
        // ---- Private space ----
        onPrivateSpaceLocked = { DuoPrivateSpace.repository(context).setLocked(it) },
        onHidePrivateContainer = model::setHidePrivateContainer,
        // ---- Backup ----
        onExportLayout = { activity.backups.startExport() },
        onImportLayout = { activity.backups.startImport() },
        // ---- Help ----
        onMakeDefault = onMakeDefault,
        onAddWidget = onAddWidget,
    )
}

/** Duo's App info page: the restricted-settings route, and where a denied permission is re-granted. */
private fun Context.startAppInfo() {
    runCatching { startActivity(NotificationAccess.appInfoIntent(this)) }
}

/**
 * Whether Duo's shade-gesture accessibility service is on right now (FR-53).
 *
 * Re-read on every resume, because the user grants it by leaving for Android's Accessibility
 * settings and coming back; without that the row would still claim the service is off.
 */
@Composable
private fun rememberShadeServiceEnabled(): Boolean {
    val context = LocalContext.current
    var enabled by remember(context) { mutableStateOf(shadeServiceEnabled(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { enabled = shadeServiceEnabled(context) }
    return enabled
}

private fun shadeServiceEnabled(context: Context): Boolean {
    val component = ComponentName(context, SystemShadeAccessibilityService::class.java)
    val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
    return runCatching {
        manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
            val service = it.resolveInfo?.serviceInfo ?: return@any false
            ComponentName(service.packageName, service.name) == component
        }
    }.getOrDefault(false)
}

