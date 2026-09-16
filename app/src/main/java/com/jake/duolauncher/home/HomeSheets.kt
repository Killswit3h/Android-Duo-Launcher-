@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.jake.duolauncher.home

import android.appwidget.AppWidgetProviderInfo
import android.os.UserManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextStyle
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

import com.jake.duolauncher.*
import com.jake.duolauncher.shortcuts.DuoUninstall

/**
 * Hosts the string-keyed modal sheets ("dock", "pins", "settings", "widgetActions").
 * The routing mechanism is unchanged from when this lived inline in LauncherScreen.
 */
@Composable
internal fun HomeSheetHost(
    state: LauncherState,
    model: LauncherModel,
    widgets: WidgetController,
    launcherActivity: MainActivity,
    pager: LauncherPager,
    geometry: HomeGeometry,
    wide: Boolean,
    isDefaultHome: Boolean,
    homePages: Int,
    dockSlot: Int,
    appearance: AppearanceState,
    onLaunch: (AppEntry) -> Unit,
    onMakeDefault: () -> Unit,
    onWallpaperPreview: () -> Unit,
    onShadeSetup: () -> Unit,
    onAppearanceMode: (AppearanceMode) -> Unit,
    onAppearanceManual: (String, Double, Double) -> Unit,
    onAppearanceDeviceLocation: () -> Unit,
    onAppearanceClear: () -> Unit,
    sheetState: MutableState<String>,
    customizationPageState: MutableState<CustomizationPage>,
    widgetSlotState: MutableState<Int>,
    widgetTargetIndexState: MutableState<Int>,
    widgetPackageState: MutableState<String?>,
    widgetProfileSerialState: MutableState<Long?>,
    widgetExactTargetState: MutableState<Boolean>,
    selectedIdState: MutableState<String?>,
    pinQueryState: MutableState<String>,
    resizeSlotState: MutableState<Int?>,
    resizeWidthState: MutableState<Int>,
    resizeHeightState: MutableState<Int>,
    resizeConstraintsState: MutableState<WidgetSpanConstraints?>,
) {
    var sheet by sheetState
    var customizationPage by customizationPageState
    var widgetSlot by widgetSlotState
    var widgetTargetIndex by widgetTargetIndexState
    var widgetPackage by widgetPackageState
    var widgetProfileSerial by widgetProfileSerialState
    var widgetExactTarget by widgetExactTargetState
    var selectedId by selectedIdState
    var pinQuery by pinQueryState
    var resizeSlot by resizeSlotState
    var resizeWidth by resizeWidthState
    var resizeHeight by resizeHeightState
    var resizeConstraints by resizeConstraintsState


    if (sheet.isNotEmpty() && sheet != "widgets") {
        val activeCustomizationPage = if (sheet == "settings:wallpaper") CustomizationPage.WALLPAPER else customizationPage
        ModalBottomSheet(onDismissRequest = {
            customizationPage = CustomizationPage.OVERVIEW
            sheet = ""; widgetPackage = null; widgetExactTarget = false
        }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false),
            containerColor = MaterialTheme.colorScheme.surface) {
            ModalDialogBackHandler {
                if ((sheet == "settings" || sheet == "settings:wallpaper") &&
                    activeCustomizationPage != CustomizationPage.OVERVIEW) {
                    customizationPage = CustomizationPage.OVERVIEW
                    sheet = "settings"
                } else {
                    customizationPage = CustomizationPage.OVERVIEW
                    sheet = ""; widgetPackage = null; widgetExactTarget = false
                }
            }
            when (sheet) {
                "dock" -> AppPicker(state.apps, dockSlot,
                    onSelect = {
                        if (canPlaceInDock(state.layout, it.id)) {
                            model.applyDrop(it.id, DropTarget.Dock(dockSlot)); sheet = ""
                        }
                    },
                    onClear = { model.removePlacement(DropTarget.Dock(dockSlot)) },
                    onLongClick = { selectedId = it.id; sheet = "" },
                    canSelect = { canPlaceInDock(state.layout, it.id) },
                    blockedHint = if (state.dock.none { it == null }) "Dock full • Move an app out first" else null)
                "pins" -> Column(Modifier.fillMaxHeight(.9f).imePadding()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { sheet = "" }) { Text("Done") }
                    }
                    AppLibrary(state, pinQuery, { pinQuery = it }, onLaunch, model::setPinned,
                        onActions = { selectedId = it.id; sheet = "" }, editing = true, modifier = Modifier.weight(1f).fillMaxWidth(),
                        onTurnOnWork = { model.turnOnWork(it) })
                }
                "settings", "settings:wallpaper" -> CustomizationSheet(state, wide, model, isDefaultHome,
                    page = activeCustomizationPage, onPage = { customizationPage = it; sheet = "settings" },
                    onMakeDefault = { sheet = ""; onMakeDefault() },
                    onClose = { customizationPage = CustomizationPage.OVERVIEW; sheet = "" }, onEditPins = { sheet = "pins" },
                    onWidget = { widgetSlot = it; widgetPackage = null; widgetProfileSerial = null; widgetExactTarget = false; sheet = "widgets" },
                    onAddWidget = { page -> widgetSlot = model.nextWidgetSlot(); widgetTargetIndex = page * state.grid.cells; widgetPackage = null; widgetProfileSerial = null; widgetExactTarget = false; sheet = "widgets" },
                    onRemoveWidget = widgets::remove,
                    onExportLayout = { sheet = ""; launcherActivity.backups.startExport() },
                    onImportLayout = { sheet = ""; launcherActivity.backups.startImport() },
                    appearance = appearance, onAppearanceMode = onAppearanceMode,
                    onAppearanceManual = onAppearanceManual, onAppearanceDeviceLocation = onAppearanceDeviceLocation,
                    onAppearanceClear = onAppearanceClear,
                    onShadeSetup = { sheet = ""; onShadeSetup() },
                    backgrounds = launcherActivity.backgrounds,
                    onWallpaperPreview = { sheet = ""; onWallpaperPreview() }, homePage = pager.currentPage.coerceIn(0, homePages - 1))
                "widgetActions" -> model.placement(widgetSlot)?.let { placement ->
                    val grid = state.grid
                    val topPitch = (geometry.widgetHeight + 18f) / 2f
                    val gridSizing = WidgetGridSizing(grid.columns, grid.rows, geometry.gridWidth / grid.columns,
                        minOf(topPitch, geometry.rowHeight), maxOf(topPitch, geometry.rowHeight), 10f, 18f,
                        topRowHeightDp = topPitch, appRowHeightDp = geometry.rowHeight)
                    val constraints = widgets.manager.getAppWidgetInfo(placement.id)?.let { widgets.sizing(it, gridSizing) }
                    WidgetActions(placement, constraints, grid = grid,
                        canConfigure = widgets.canReconfigure(placement.id),
                        onConfigure = { widgets.reconfigure(placement.id); sheet = "" },
                        isValid = { x, y -> (x == placement.spanX && y == placement.spanY) || resizeWidget(state.layout, widgetSlot, x, y) != state.layout },
                        onResize = { x, y -> model.resizeWidget(widgetSlot, x, y) },
                        onStartResize = { x, y ->
                            resizeSlot = widgetSlot; resizeWidth = x; resizeHeight = y
                            resizeConstraints = constraints; sheet = ""
                        },
                        onMoveToPage = { page ->
                            (0 until grid.cells).firstOrNull { local ->
                                widgetCandidate(state.layout, placement.slot, page * grid.cells + local,
                                    placement.spanX, placement.spanY) != null
                            }?.let { model.moveWidgetTo(placement.slot, page * grid.cells + it) } == true
                        }, homePages = homePages,
                        onReplace = {
                            widgetPackage = null
                            widgetProfileSerial = widgets.manager.getAppWidgetInfo(placement.id)?.profile?.let {
                                launcherActivity.getSystemService(UserManager::class.java).getSerialNumberForUser(it)
                            }?.takeIf { it >= 0 }
                            widgetExactTarget = false; sheet = "widgets"
                        },
                        onRemove = { widgets.remove(widgetSlot); sheet = "" },
                        onClose = { sheet = "" })
                }
            }
        }
    }

}

@Composable
internal fun FirstRunSheetHost(
    showFirstRun: Boolean,
    isDefaultHome: Boolean,
    model: LauncherModel,
    pager: LauncherPager,
    homePages: Int,
    /** FR-31: the first widget lands on the active layout's grid, not a fixed 4x6. */
    grid: GridSpec = DEFAULT_GRID,
    onMakeDefault: () -> Unit,
    onFinishFirstRun: () -> Unit,
    sheetState: MutableState<String>,
    widgetSlotState: MutableState<Int>,
    widgetTargetIndexState: MutableState<Int>,
    widgetPackageState: MutableState<String?>,
    widgetProfileSerialState: MutableState<Long?>,
    widgetExactTargetState: MutableState<Boolean>,
) {
    var sheet by sheetState
    var widgetSlot by widgetSlotState
    var widgetTargetIndex by widgetTargetIndexState
    var widgetPackage by widgetPackageState
    var widgetProfileSerial by widgetProfileSerialState
    var widgetExactTarget by widgetExactTargetState


    if (showFirstRun) {
        ModalBottomSheet(
            onDismissRequest = onFinishFirstRun,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface,
            modifier = Modifier.testTag("first-run-setup"),
        ) {
            FirstRunSetupSheet(
                isDefaultHome = isDefaultHome,
                onMakeDefault = onMakeDefault,
                onAddWidget = {
                    onFinishFirstRun()
                    widgetSlot = model.nextWidgetSlot()
                    widgetTargetIndex = pager.currentPage.coerceIn(0, homePages - 1) * grid.cells
                    widgetPackage = null
                    widgetProfileSerial = null
                    widgetExactTarget = false
                    sheet = "widgets"
                },
                onExplore = onFinishFirstRun,
                onSkip = onFinishFirstRun,
            )
        }
    }

}

/** App/empty-cell action sheets, folder surfaces and the backup, widget and picker dialogs. */
@Composable
internal fun HomeDialogs(
    state: LauncherState,
    model: LauncherModel,
    widgets: WidgetController,
    drag: HomeDragState,
    pager: LauncherPager,
    launcherActivity: MainActivity,
    appsById: Map<String, AppEntry>,
    homePages: Int,
    lastHomePage: Int,
    expandedWorkspace: Boolean,
    isDefaultHome: Boolean,
    onMakeDefault: () -> Unit,
    iconSize: Float,
    onAppInfo: (AppEntry) -> Unit,
    onLaunchFrom: (AppEntry, android.graphics.Rect?) -> Unit,
    leaveTemporaryWidgetPage: () -> Unit,
    sheetState: MutableState<String>,
    selectedIdState: MutableState<String?>,
    appMoveMenuState: MutableState<Boolean>,
    widgetSlotState: MutableState<Int>,
    widgetTargetIndexState: MutableState<Int>,
    widgetPackageState: MutableState<String?>,
    widgetProfileSerialState: MutableState<Long?>,
    widgetExactTargetState: MutableState<Boolean>,
    emptyCellIndexState: MutableState<Int?>,
    createFolderFirstIdState: MutableState<String?>,
    openFolderIdState: MutableState<String?>,
) {
    var sheet by sheetState
    var selectedId by selectedIdState
    var appMoveMenu by appMoveMenuState
    var widgetSlot by widgetSlotState
    var widgetTargetIndex by widgetTargetIndexState
    var widgetPackage by widgetPackageState
    var widgetProfileSerial by widgetProfileSerialState
    var widgetExactTarget by widgetExactTargetState
    var emptyCellIndex by emptyCellIndexState
    var createFolderFirstId by createFolderFirstIdState
    var openFolderId by openFolderIdState


// FR-24: a long-press that is released opens the glass context menu anchored to the icon, in place
// of the old bottom sheet. Long-press-and-drag is unaffected: `finishDrag` only sets `selectedId`
// when the gesture ended without movement, so a real pickup never reaches here.
appsById[selectedId]?.let { app ->
    val context = LocalContext.current
    val pinned = state.layout.indexOfShortcut(app.id) != null
    val packageName = app.packageName
    val hasWidgets = packageName.isNotEmpty() && runCatching {
        widgets.providersForPackage(packageName, app.user)
    }.getOrDefault(emptyList()).isNotEmpty()
    // FR-29: a system app or Duo itself never shows Uninstall. The answer needs the package
    // manager, so it is resolved off the main thread once per menu rather than during a scroll.
    val canUninstall by produceState(false, app.id) {
        value = withContext(Dispatchers.IO) {
            DuoUninstall.canUninstall(context, packageName, app.userSerial)
        }
    }
    // The drag layer already knows every icon's bounds, so the menu anchors without new plumbing.
    val anchor = drag.regions.values.firstOrNull { it.appId == app.id }?.bounds
    HomeContextMenu(
        app = app,
        anchor = anchor,
        iconSize = iconSize,
        isDefaultHome = isDefaultHome,
        capabilities = ContextMenuCapabilities(
            placed = pinned,
            canEditHome = true,
            // Edit icon (FR-19) and Hide app (FR-75) land with the icon-override and schema-9
            // tasks; until their model calls exist the rows are not offered rather than faked.
            canEditIcon = false,
            canCreateFolder = true,
            hasWidgets = hasWidgets,
            canHide = false,
            canUninstall = canUninstall,
        ),
        onDismiss = { appMoveMenu = false; selectedId = null },
        onMakeDefault = onMakeDefault,
        onAction = { action ->
            when (action) {
                ContextMenuAction.EDIT_HOME -> { selectedId = null; sheet = "settings" }
                ContextMenuAction.APP_INFO -> { onAppInfo(app); selectedId = null }
                ContextMenuAction.CREATE_FOLDER -> { createFolderFirstId = app.id; selectedId = null }
                ContextMenuAction.WIDGETS -> {
                    val page = lastHomePage.coerceIn(0, homePages - 1)
                    widgetTargetIndex = homeCellIndex(page, 0); widgetExactTarget = false
                    widgetSlot = model.nextWidgetSlot(); widgetPackage = packageName
                    widgetProfileSerial = app.userSerial; selectedId = null; sheet = "widgets"
                }
                ContextMenuAction.ADD_TO_HOME, ContextMenuAction.REMOVE_FROM_HOME -> {
                    model.setPinned(app.id, !pinned); selectedId = null
                }
                ContextMenuAction.UNINSTALL -> {
                    DuoUninstall.start(context, packageName, app.userSerial); selectedId = null
                }
                ContextMenuAction.EDIT_ICON, ContextMenuAction.HIDE_APP -> selectedId = null
            }
        },
    )
}
emptyCellIndex?.let { index ->
    ModalBottomSheet(onDismissRequest = { emptyCellIndex = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        EmptySpaceActionSheet(onWidgets = {
                widgetTargetIndex = index; widgetExactTarget = true; widgetSlot = model.nextWidgetSlot(); widgetPackage = null; widgetProfileSerial = null
                emptyCellIndex = null; sheet = "widgets"
            }, onWallpaper = { emptyCellIndex = null; sheet = "settings:wallpaper" },
            onCustomize = { emptyCellIndex = null; sheet = "settings" }, onClose = { emptyCellIndex = null })
    }
}
createFolderFirstId?.let { firstId ->
    val first = appsById[firstId]
    AlertDialog(onDismissRequest = { createFolderFirstId = null }, title = { Text("Create folder with ${first?.label ?: "app"}") },
        text = { LazyColumn(Modifier.heightIn(max = 420.dp).testTag("folder-app-picker")) {
            items(state.apps.filter { it.id != firstId && it.available }, key = { it.id }) { second ->
                TextButton(onClick = {
                    val preferredPage = state.layout.indexOfShortcut(firstId)?.let(::homeCellPage)
                        ?.takeIf { it >= 0 || expandedWorkspace } ?: lastHomePage.coerceIn(0, homePages - 1)
                    val blocked = state.widgetPlacements.flatMapTo(mutableSetOf()) { it.coveredIndices(state.grid) }
                    val targetIndex = (0 until state.grid.cells).map { homeCellIndex(preferredPage, it, state.grid) }
                        .firstOrNull { it !in blocked && state.layout.slotAt(it) in listOf(null, firstId, second.id) }
                    if (targetIndex != null) model.createFolder(firstId, second.id, targetIndex)
                    createFolderFirstId = null
                }, modifier = Modifier.fillMaxWidth().testTag("folder-app-${second.id}")) {
                    Text(second.label, Modifier.fillMaxWidth())
                }
            }
        } }, confirmButton = { TextButton(onClick = { createFolderFirstId = null }) { Text("Cancel") } })
}
openFolderId?.let { id ->
    state.folders.firstOrNull { it.id == id }?.let { folder ->
        val blocked = state.widgetPlacements.flatMapTo(mutableSetOf()) { it.coveredIndices(state.grid) }
        val destinationPages = (if (expandedWorkspace) listOf(-1) else emptyList()) + (0 until homePages)
        val homeDestinations = destinationPages.mapNotNull { destinationPage ->
            (0 until state.grid.cells).map { homeCellIndex(destinationPage, it, state.grid) }
                .firstOrNull { it !in blocked && state.layout.slotAt(it) == null }
        }
        // FR-42: while the device is half-opened the folder panel opens beside the fold, not across
        // it. The clearance is applied here rather than inside FolderPanel so the panel keeps its
        // own centring and only ever sees the space it is allowed to use.
        Box(Modifier.fillMaxSize().hingeClearance()) {
            FolderPanel(folder, appsById, drag, pager.currentPage, homeDestinations,
                dockVacancies = state.dock.indices.filter { state.dock[it] == null },
                onDismiss = { openFolderId = null }, onRename = { model.renameFolder(id, it) },
                onLaunch = onLaunchFrom,
                onMoveOut = { appId, destination ->
                    if (model.removeAppFromFolder(id, appId, destination)) openFolderId = model.folder(id)?.id
                })
        }
    } ?: LaunchedEffect(id) { openFolderId = null }
}
launcherActivity.backups.preview?.let { preview ->
    LayoutRestorePreview(preview, onRestore = {
        launcherActivity.backups.applyImport(); sheet = ""
    }, onCancel = launcherActivity.backups::cancelImport)
}
if (launcherActivity.backups.pickerPending) AlertDialog(onDismissRequest = {},
    title = { Text("Layout document") },
    text = { Text("The system document picker is still open. Return to it to finish, or cancel this operation.") },
    confirmButton = { TextButton(onClick = { launcherActivity.backups.resumePendingPicker() },
        modifier = Modifier.testTag("backup-picker-resume")) { Text("Resume") } },
    dismissButton = { TextButton(onClick = launcherActivity.backups::cancelImport,
        modifier = Modifier.testTag("backup-picker-cancel")) { Text("Cancel") } })
if (launcherActivity.backgrounds.pickerPending && !launcherActivity.backgrounds.loading) AlertDialog(
    onDismissRequest = {}, title = { Text("Background photo") },
    text = { Text("The photo picker was interrupted. Resume choosing a photo, or cancel and keep the current background.") },
    confirmButton = { TextButton(onClick = launcherActivity.backgrounds::choosePhoto,
        modifier = Modifier.testTag("background-picker-resume")) { Text("Resume") } },
    dismissButton = { TextButton(onClick = launcherActivity.backgrounds::cancelPendingSelection,
        modifier = Modifier.testTag("background-picker-cancel")) { Text("Cancel") } })
(launcherActivity.backups.errorMessage ?: launcherActivity.backups.successMessage)?.let { message ->
    AlertDialog(onDismissRequest = launcherActivity.backups::clearMessage,
        title = { Text(if (launcherActivity.backups.errorMessage != null) "Layout backup problem" else "Layout backup") },
        text = { Text(message) }, confirmButton = { TextButton(onClick = launcherActivity.backups::clearMessage) { Text("OK") } })
}
widgets.failureMessage?.let { message ->
    AlertDialog(onDismissRequest = widgets::clearFailure, title = { Text("Widget not added") },
        text = { Text(message, Modifier.testTag("widget-bind-error")) },
        confirmButton = { TextButton(onClick = widgets::clearFailure) { Text("OK") } })
}
if (widgets.pendingPlacement != null && widgets.setupStatus != null) {
    AlertDialog(onDismissRequest = {}, title = { Text("Finish widget setup") },
        text = { Text("The widget is waiting at its chosen spot. Finish setup to add it, or cancel to remove the placeholder.") },
        confirmButton = { Button(onClick = widgets::finishPendingSetup,
            modifier = Modifier.semantics { contentDescription = "Continue widget setup" }) { Text("Finish setup") } },
        dismissButton = { TextButton(onClick = { leaveTemporaryWidgetPage(); widgets.cancelPendingSetup() },
            modifier = Modifier.semantics { contentDescription = "Cancel widget setup" }) { Text("Cancel") } })
}
widgets.reconfigureWidgetId?.let {
    AlertDialog(onDismissRequest = {}, title = { Text("Widget settings") },
        text = { Text("Widget settings were interrupted. Resume configuration, or cancel and keep the widget unchanged.") },
        confirmButton = { Button(onClick = widgets::finishPendingReconfigure,
            modifier = Modifier.testTag("widget-reconfigure-resume")) { Text("Resume") } },
        dismissButton = { TextButton(onClick = widgets::cancelPendingReconfigure,
            modifier = Modifier.testTag("widget-reconfigure-cancel")) { Text("Cancel") } })
}

}
