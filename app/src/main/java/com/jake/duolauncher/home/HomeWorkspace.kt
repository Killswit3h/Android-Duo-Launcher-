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
import kotlinx.coroutines.CoroutineScope

@Composable
internal fun HomeWorkspace(
    state: LauncherState,
    model: LauncherModel,
    widgets: WidgetController,
    drag: HomeDragState,
    pager: LauncherPager,
    nativePager: androidx.compose.foundation.pager.PagerState,
    pageGestures: PageGestureLimits,
    pageFling: androidx.compose.foundation.gestures.TargetedFlingBehavior,
    scope: CoroutineScope,
    homeLayer: androidx.compose.ui.graphics.layer.GraphicsLayer,
    launcherActivity: MainActivity,
    launcherRootView: android.view.View,
    appsById: Map<String, AppEntry>,
    previewLayout: HomeLayout,
    target: DropTarget?,
    insertionTarget: DropTarget?,
    blockedDock: Boolean,
    edge: Int,
    eligibleDragPages: Set<Int>,
    widgetDraft: WidgetPlacement?,
    widgetRawTarget: DropTarget.Home?,
    visibleHomePages: Int,
    homePages: Int,
    firstHome: Int,
    lastHomePage: Int,
    isDefaultHome: Boolean,
    showFirstRun: Boolean,
    deviceStatus: DeviceStatus,
    appearance: AppearanceState,
    onLaunch: (AppEntry) -> Unit,
    onLaunchFrom: (AppEntry, android.graphics.Rect?) -> Unit,
    onAppInfo: (AppEntry) -> Unit,
    onMakeDefault: () -> Unit,
    onDiscover: () -> Unit,
    onGoogleSearch: (android.graphics.Rect?) -> Boolean,
    onWallpaperPreview: () -> Unit,
    onShadeSetup: () -> Unit,
    onFinishFirstRun: () -> Unit,
    onAppearanceMode: (AppearanceMode) -> Unit,
    onAppearanceManual: (String, Double, Double) -> Unit,
    onAppearanceDeviceLocation: () -> Unit,
    onAppearanceClear: () -> Unit,
    leaveTemporaryWidgetPage: () -> Unit,
    widgetPickerBack: () -> Unit,
    openLibrary: () -> Unit,
    openDiscover: () -> Unit,
    sheetState: MutableState<String>,
    dockSlotState: MutableState<Int>,
    widgetSlotState: MutableState<Int>,
    widgetExactTargetState: MutableState<Boolean>,
    widgetPackageState: MutableState<String?>,
    widgetSessionState: MutableState<WidgetPickerSession?>,
    widgetPlacementMessageState: MutableState<String?>,
    emptyCellIndexState: MutableState<Int?>,
    resizeSlotState: MutableState<Int?>,
    selectedIdState: MutableState<String?>,
    appMoveMenuState: MutableState<Boolean>,
    customizationPageState: MutableState<CustomizationPage>,
    openFolderIdState: MutableState<String?>,
    createFolderFirstIdState: MutableState<String?>,
    expandedWorkspaceState: MutableState<Boolean>,
) {
    // Re-delegated so the moved bodies below read exactly as they did inline.
    var sheet by sheetState
    var dockSlot by dockSlotState
    var widgetSlot by widgetSlotState
    var widgetExactTarget by widgetExactTargetState
    var widgetPackage by widgetPackageState
    var widgetSession by widgetSessionState
    var widgetPlacementMessage by widgetPlacementMessageState
    var emptyCellIndex by emptyCellIndexState
    var resizeSlot by resizeSlotState
    var selectedId by selectedIdState
    var appMoveMenu by appMoveMenuState
    var customizationPage by customizationPageState
    var openFolderId by openFolderIdState
    var createFolderFirstId by createFolderFirstIdState
    var expandedWorkspace by expandedWorkspaceState
    // These are read and written only inside this region, so they live here.
    val widgetTargetIndexState = rememberSaveable { mutableIntStateOf(Int.MIN_VALUE) }
    var widgetTargetIndex by widgetTargetIndexState
    val widgetProfileSerialState = rememberSaveable { mutableStateOf<Long?>(null) }
    var widgetProfileSerial by widgetProfileSerialState
    val resizeWidthState = rememberSaveable { mutableIntStateOf(1) }
    var resizeWidth by resizeWidthState
    val resizeHeightState = rememberSaveable { mutableIntStateOf(1) }
    var resizeHeight by resizeHeightState
    val resizeConstraintsState = remember { mutableStateOf<WidgetSpanConstraints?>(null) }
    var resizeConstraints by resizeConstraintsState
    var resizePitchX by remember { mutableFloatStateOf(1f) }
    var resizePitchY by remember { mutableFloatStateOf(1f) }
    var resizeTopPitch by remember { mutableFloatStateOf(1f) }
    var resizeAppPitch by remember { mutableFloatStateOf(1f) }
    var libraryQuery by rememberSaveable { mutableStateOf("") }
    val pinQueryState = rememberSaveable { mutableStateOf("") }
    var pinQuery by pinQueryState


    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        val wide = maxWidth.value >= 650f
        val preset = if (wide) state.expanded else state.compact
        val density = LocalDensity.current
        val inLibrary = pager.currentPage == visibleHomePages
        var statusHeight by remember { mutableFloatStateOf(0f) }
        val geometry = homeGeometry(maxWidth.value, maxHeight.value, preset, state.labels,
            statusHeight = if (state.verticalStatus) statusHeight + 22f else 0f,
            labelHeight = with(density) { 14.sp.toDp().value } + 6f, inLibrary = inLibrary,
            homeBottomSpace = if (isDefaultHome) 44f else 88f)
        SideEffect {
            resizePitchX = with(density) { (geometry.gridWidth / GRID_COLUMNS).dp.toPx() }
            resizePitchY = with(density) { minOf((geometry.widgetHeight + 18f) / 2f, geometry.rowHeight).dp.toPx() }
            resizeTopPitch = with(density) { ((geometry.widgetHeight + 18f) / 2f).dp.toPx() }
            resizeAppPitch = with(density) { geometry.rowHeight.dp.toPx() }
        }
        LaunchedEffect(geometry.gridWidth, geometry.widgetHeight, geometry.rowHeight) { resizeSlot = null }
        SideEffect { expandedWorkspace = geometry.expanded }
        LaunchedEffect(geometry.expanded) {
            if (!geometry.expanded) {
                val sessionTargetsLeading = widgetSession?.let { session ->
                    session.candidate?.page == -1 || session.targetIndex?.let(::homeCellPage) == -1
                } == true
                val savedTargetLeading = widgetTargetIndex != Int.MIN_VALUE && homeCellPage(widgetTargetIndex) == -1
                if (sessionTargetsLeading || savedTargetLeading) {
                    widgetSession = null
                    widgetTargetIndex = Int.MIN_VALUE
                    widgetExactTarget = false
                    widgetPackage = null
                    widgetProfileSerial = null
                    widgetPlacementMessage = null
                    sheet = ""
                }
                val dragTouchesLeading = drag.source?.page == -1 ||
                    ((target as? DropTarget.Home)?.index?.let(::homeCellPage) == -1)
                if (dragTouchesLeading) {
                    drag.clear()
                }
            }
        }
        val contentHeight = maxHeight
        val panelWidth = maxWidth - geometry.homeWidth.dp
        val pagerWidth = maxWidth - preset.dockWidth.dp - 28.dp
        val leftColumnOrigin = (maxWidth / 2f - geometry.gridWidth.dp) / 2f - 16.dp
        val homeStride = panelWidth - leftColumnOrigin
        val bottomSpace = if (isDefaultHome) 44.dp else 88.dp
        val workspaceMotion = if (geometry.expanded) remember(firstHome, visibleHomePages, pagerWidth, homeStride, density) {
            WorkspacePageMotion(firstHome, visibleHomePages, with(density) { pagerWidth.toPx() }, with(density) { homeStride.toPx() })
        } else null
        val dockScroll = rememberScrollState()
        var gestureOriginInRoot by remember { mutableStateOf(Offset.Zero) }
        var gestureOriginInWindow by remember { mutableStateOf(Offset.Zero) }
        val pagerInputEnabled = pager.currentPage in -firstHome..visibleHomePages && !drag.active &&
            widgetSession == null && resizeSlot == null && sheet.isEmpty() && !showFirstRun && selectedId == null &&
            openFolderId == null && emptyCellIndex == null && createFolderFirstId == null &&
            launcherActivity.backups.preview == null && !launcherActivity.backups.pickerPending &&
            !launcherActivity.backgrounds.pickerPending && widgets.setupStatus == null &&
            widgets.reconfigureWidgetId == null
        Box(Modifier.fillMaxSize().onGloballyPositioned {
            gestureOriginInRoot = it.boundsInRoot().topLeft
            gestureOriginInWindow = it.boundsInWindow().topLeft
        }.onePageGestures(
            nativePager,
            pageGestures,
            motion = workspaceMotion,
            enabled = pagerInputEnabled,
            // Positive IDs are provider-owned Android views. Leave their vertical
            // stream untouched so scrollable widgets retain native gesture handling.
            // A dock that is already scrolled also gets first use of a downward drag.
            canStartDownwardSwipe = { point ->
                if (pager.currentPage !in 0 until visibleHomePages) false else {
                    val region = drag.hit(point + gestureOriginInRoot, eligibleDragPages)
                    val rootOnScreen = IntArray(2).also(launcherRootView::getLocationOnScreen)
                    val screenPoint = point + gestureOriginInWindow +
                        Offset(rootOnScreen[0].toFloat(), rootOnScreen[1].toFloat())
                    !(region?.target is DropTarget.Dock && dockScroll.value > 0) &&
                        !nativeWidgetConsumesVerticalGesture(launcherRootView, screenPoint)
                }
            },
            onDownwardSwipe = launcherActivity::openSystemShade,
            onLeadingOverscroll = if (firstHome == 0) onDiscover else null,
        )) {
        val pagerModifier = Modifier.fillMaxHeight().width(pagerWidth)
            .drawWithContent {
                homeLayer.record { this@drawWithContent.drawContent() }
                drawLayer(homeLayer)
                LiveDiscover.host.get()?.invalidateFrame()
            }.testTag("app-pager")
            .discoverSwipe(firstHome == 0 && pager.currentPage == 0 && !drag.active && sheet.isEmpty() &&
                !showFirstRun && selectedId == null, onDiscover)
            .onGloballyPositioned {
                if (firstHome > 0) {
                    val bounds = it.boundsInWindow()
                    LiveDiscover.pagerOrigin = bounds.topLeft
                    val padding = 32 * density.density
                    LiveDiscover.prepare(launcherActivity,
                        android.graphics.Rect((bounds.left + padding).toInt(), (bounds.top + padding).toInt(),
                            (bounds.right - 16 * density.density).toInt(), (bounds.bottom - padding).toInt()), bounds.width)
                }
            }
            .semantics { stateDescription = if (pager.currentPage == -1) "Discover" else if (pager.currentPage == visibleHomePages) "All apps" else "Home page ${pager.currentPage + 1} of $visibleHomePages" }
        if (geometry.expanded) {
            Box(pagerModifier) {
                // PagerState remains the source of truth for native Discover progress,
                // snapping, accessibility state, and programmatic page requests.
                HorizontalPager(nativePager, Modifier.fillMaxSize(), userScrollEnabled = false,
                    key = { if (it < firstHome) "discover" else if (it - firstHome == visibleHomePages) "library" else "home-${it - firstHome}" }) { }
                ExpandedWorkspace(
                    nativePager = nativePager, motion = workspaceMotion!!, firstHome = firstHome,
                    visibleHomePages = visibleHomePages, panelWidth = panelWidth,
                    contentHeight = contentHeight, bottomSpace = bottomSpace, geometry = geometry,
                    state = state, previewSlots = previewLayout.slots, previewLeadingSlots = previewLayout.leadingSlots,
                    previewWidgetPlacements = previewLayout.widgetPlacements, appsById = appsById,
                    widgets = widgets, drag = drag, target = target, insertionTarget = insertionTarget,
                    libraryQuery = libraryQuery, onLibraryQuery = { libraryQuery = it },
                    onLaunch = onLaunch, onLaunchFrom = onLaunchFrom, onPinned = model::setPinned,
                    onTurnOnWork = { model.turnOnWork(it) },
                    onActions = { selectedId = it.id }, onWidget = { widgetSlot = it; sheet = "widgetActions" },
                    onFolder = { openFolderId = it },
                    onEmptyWidget = { emptyCellIndex = it },
                    onRefresh = model::refresh,
                )
            }
        } else {
            HorizontalPager(nativePager, pagerModifier,
                // Keep adjacent Home panes attached so ordinary back-and-forth paging does
                // not synchronously inflate provider RemoteViews inside the gesture frame.
                // Discover is two physical positions before Home 2. Retain both Home
                // neighbors to avoid reinflating Home 2's RemoteViews during native exit.
                beyondViewportPageCount = if (firstHome > 0) 2 else 1,
                userScrollEnabled = !drag.active && resizeSlot == null, flingBehavior = pageFling,
                key = { if (it < firstHome) "discover" else if (it - firstHome == visibleHomePages) "library" else "home-${it - firstHome}" }) { physicalPage ->
                val page = physicalPage - firstHome
                if (page == -1) {
                    DiscoverContent(Modifier.fillMaxSize().padding(start = 16.dp, top = 16.dp, bottom = 16.dp))
                } else if (page == visibleHomePages) {
                    AppLibrary(state, libraryQuery, { libraryQuery = it }, onLaunch, model::setPinned,
                        onActions = { selectedId = it.id }, modifier = Modifier.fillMaxSize().padding(start = 16.dp, top = 16.dp, bottom = bottomSpace).testTag("library-page"),
                        drag = drag, page = visibleHomePages, onLaunchFrom = onLaunchFrom, onTurnOnWork = { model.turnOnWork(it) })
                } else {
                    Row(Modifier.fillMaxSize().testTag("home-surface")) {
                        HomePagePane(page, state, previewLayout.slots, previewLayout.leadingSlots, previewLayout.widgetPlacements, appsById, geometry, contentHeight,
                            bottomSpace, widgets, drag, target, insertionTarget, showLargeWidget = false,
                            onLaunch = onLaunchFrom, onActions = { selectedId = it.id },
                            onWidget = { widgetSlot = it; sheet = "widgetActions" },
                            onFolder = { openFolderId = it },
                            onEmptyWidget = { emptyCellIndex = it },
                            onRefresh = model::refresh)
                    }
                }
            }
        }
        if (state.verticalStatus) StatusRail(deviceStatus,
            Modifier.align(Alignment.TopEnd).padding(end = 12.dp).offset(y = geometry.contentTop.dp)
                .width(preset.dockWidth.dp).onSizeChanged {
                    // The normal rail's 20dp location slot and 3dp gap do not move the dock.
                    statusHeight = (with(density) { it.height.toDp().value } -
                        if (contentHeight < 500.dp) 0f else 23f).coerceAtLeast(0f)
                },
            compact = contentHeight < 500.dp, iconSize = dockIconSize(geometry.iconSize).dp)
        Surface(Modifier.align(Alignment.TopEnd).padding(end = 12.dp).offset(y = geometry.dockTop.dp)
            .width(preset.dockWidth.dp).height(geometry.dockHeight.dp).graphicsLayer {
                // Composite the stationary dock independently of the shared pager layer.
                compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen
            }.testTag("dock"),
            shape = RoundedCornerShape(30.dp), color = Glass.copy(alpha = .32f),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = .3f))) {
            Column(Modifier.padding(vertical = 8.dp).verticalScroll(dockScroll)) {
                DockAppColumn(state.dock, previewLayout.dock, appsById, geometry.dockRowHeight,
                    dockIconSize(geometry.iconSize), drag, insertionTarget,
                    onLaunch = onLaunchFrom, onChoose = { dockSlot = it; sheet = "dock" })
            }
        }
        Column(Modifier.align(Alignment.BottomStart).width(pagerWidth).padding(start = 16.dp, bottom = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (!isDefaultHome) FilledTonalButton(onClick = { sheet = ""; onMakeDefault() }, Modifier.heightIn(min = 48.dp).testTag("home-setup")) {
                Icon(Icons.Rounded.Home, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Set as home app")
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                if (!drag.active) IconButton(onClick = openDiscover, Modifier.size(32.dp).testTag("discover-page-link")) {
                    Icon(Icons.Rounded.Explore, "Discover", tint = Color.White.copy(alpha = .65f), modifier = Modifier.size(17.dp))
                }
                if (visibleHomePages <= 6) repeat(visibleHomePages) { index ->
                    Box(Modifier.size(28.dp).clip(CircleShape).clickable { scope.launch { pager.animateScrollToPage(index) } }
                        .semantics { contentDescription = if (index == homePages) "New home page" else "Home page ${index + 1}" }, contentAlignment = Alignment.Center) {
                        if (index == homePages) Icon(Icons.Rounded.Add, null, tint = Color.White, modifier = Modifier.size(14.dp))
                        else Box(Modifier.size(if (index == pager.currentPage) 6.dp else 4.dp).background(Color.White.copy(alpha = if (index == pager.currentPage) 1f else .4f), CircleShape))
                    }
                } else Text("${minOf(pager.currentPage + 1, homePages)} / $homePages", color = Color.White, fontSize = 12.sp)
                IconButton(onClick = openLibrary, Modifier.size(32.dp).testTag("library-page-link")) {
                    Icon(Icons.AutoMirrored.Rounded.FormatListBulleted, "All apps page", tint = Color.White.copy(alpha = if (pager.currentPage == homePages) 1f else .6f), modifier = Modifier.size(17.dp))
                }
            }
        }
        if (!inLibrary && !drag.active) Column(Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 6.dp)
            .width(preset.dockWidth.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val controlSize = dockIconSize(geometry.iconSize).dp
            if (pager.currentPage == -1) CircleControl(Icons.Rounded.ArrowForward, "Back to home", "discover-home", controlSize) { scope.launch { pager.animateScrollToPage(0) } }
            val searchBounds = remember { android.graphics.Rect() }
            Box(Modifier.onGloballyPositioned { searchBounds.set(it.boundsInWindow().toAndroidBounds()) }) {
                CircleControl(Icons.Rounded.Search, if (state.googleSearch) "Search Google" else "Search apps", "search", controlSize) {
                    if (!state.googleSearch || !onGoogleSearch(searchBounds)) openLibrary()
                }
            }
        }
        HomeSheetHost(
            state = state, model = model, widgets = widgets, launcherActivity = launcherActivity,
            pager = pager, geometry = geometry, wide = wide, isDefaultHome = isDefaultHome,
            homePages = homePages, dockSlot = dockSlot, appearance = appearance,
            onLaunch = onLaunch, onMakeDefault = onMakeDefault,
            onWallpaperPreview = onWallpaperPreview, onShadeSetup = onShadeSetup,
            onAppearanceMode = onAppearanceMode, onAppearanceManual = onAppearanceManual,
            onAppearanceDeviceLocation = onAppearanceDeviceLocation,
            onAppearanceClear = onAppearanceClear,
            sheetState = sheetState, customizationPageState = customizationPageState,
            widgetSlotState = widgetSlotState, widgetTargetIndexState = widgetTargetIndexState,
            widgetPackageState = widgetPackageState,
            widgetProfileSerialState = widgetProfileSerialState,
            widgetExactTargetState = widgetExactTargetState, selectedIdState = selectedIdState,
            pinQueryState = pinQueryState, resizeSlotState = resizeSlotState,
            resizeWidthState = resizeWidthState, resizeHeightState = resizeHeightState,
            resizeConstraintsState = resizeConstraintsState,
        )
        FirstRunSheetHost(
            showFirstRun = showFirstRun, isDefaultHome = isDefaultHome, model = model,
            pager = pager, homePages = homePages, onMakeDefault = onMakeDefault,
            onFinishFirstRun = onFinishFirstRun, sheetState = sheetState,
            widgetSlotState = widgetSlotState, widgetTargetIndexState = widgetTargetIndexState,
            widgetPackageState = widgetPackageState,
            widgetProfileSerialState = widgetProfileSerialState,
            widgetExactTargetState = widgetExactTargetState,
        )
        WidgetPlacementLayer(
            state = state, model = model, widgets = widgets, drag = drag, pager = pager,
            scope = scope, launcherActivity = launcherActivity, geometry = geometry,
            homePages = homePages, lastHomePage = lastHomePage,
            expandedWorkspace = expandedWorkspace, eligibleDragPages = eligibleDragPages,
            widgetDraft = widgetDraft, widgetRawTarget = widgetRawTarget,
            widgetSlot = widgetSlot, widgetTargetIndex = widgetTargetIndex,
            widgetExactTarget = widgetExactTarget,
            leaveTemporaryWidgetPage = leaveTemporaryWidgetPage,
            widgetPickerBack = widgetPickerBack,
            sheetState = sheetState, widgetSessionState = widgetSessionState,
            widgetPlacementMessageState = widgetPlacementMessageState,
            widgetPackageState = widgetPackageState,
            widgetProfileSerialState = widgetProfileSerialState,
        )
    }
    if (drag.active) {
        if (drag.moved) {
            if (pager.currentPage > 0) Box(Modifier.align(Alignment.CenterStart).width(6.dp).height(112.dp)
                .background(Color.White.copy(alpha = if (edge < 0) .9f else .3f), RoundedCornerShape(6.dp)).testTag("drag-edge-left"))
            if (pager.currentPage < homePages) Box(Modifier.align(Alignment.CenterEnd).width(6.dp).height(112.dp)
                .background(Color.White.copy(alpha = if (edge > 0) .9f else .3f), RoundedCornerShape(6.dp)).testTag("drag-edge-right"))
        }
        appsById[drag.source?.appId]?.let { app ->
            val size = 66.dp
            val px = with(LocalDensity.current) { size.toPx() }
            Image(app.icon.asImageBitmap(), "Moving ${app.label}", Modifier
                .offset { IntOffset((drag.pointer.x - drag.rootOrigin.x - px / 2).roundToInt(), (drag.pointer.y - drag.rootOrigin.y - px * .65f).roundToInt()) }
                .size(size).shadow(16.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).testTag("drag-ghost"))
        }
        drag.source?.appId?.let { state.layout.folder(it) }?.let { folder ->
            Surface(Modifier.offset { IntOffset((drag.pointer.x - drag.rootOrigin.x - 42.dp.toPx()).roundToInt(),
                (drag.pointer.y - drag.rootOrigin.y - 52.dp.toPx()).roundToInt()) }.size(84.dp)
                .shadow(16.dp, RoundedCornerShape(20.dp)).testTag("folder-drag-ghost"),
                color = Glass.copy(alpha = .96f), shape = RoundedCornerShape(20.dp)) {
                Box(contentAlignment = Alignment.Center) { Text(folder.title, color = Ink, textAlign = TextAlign.Center) }
            }
        }
        drag.source?.widgetId?.let { id ->
            val width = 144.dp; val height = 108.dp
            val x = with(LocalDensity.current) { width.toPx() }
            val y = with(LocalDensity.current) { height.toPx() }
            Surface(Modifier.offset { IntOffset((drag.pointer.x - x / 2).roundToInt(), (drag.pointer.y - y * .65f).roundToInt()) }
                .size(width, height).shadow(16.dp, RoundedCornerShape(24.dp)).testTag("drag-ghost"),
                color = Glass.copy(alpha = .95f), shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.Widgets, null, tint = Ink)
                    Spacer(Modifier.height(8.dp))
                    Text(remember(id, widgets) { widgetLabel(id, widgets) }, color = Ink, maxLines = 2, textAlign = TextAlign.Center)
                }
            }
        }
        if (blockedDock) Surface(
            Modifier.align(Alignment.TopCenter).statusBarsPadding()
                .padding(top = 10.dp, start = 20.dp, end = 100.dp),
            color = Glass.copy(alpha = .96f), shape = RoundedCornerShape(18.dp)
        ) {
            Text("Dock full • Move an app out first",
                Modifier.padding(horizontal = 16.dp, vertical = 12.dp), color = Ink, fontSize = 13.sp)
        }
        if (drag.moved && drag.source?.target !is DropTarget.Library &&
            drag.source?.appId?.let(::isFolderId) != true) Surface(
            // Keep removal in the right-side control area that is vacated during a drag.
            // A centered target overlaps the expanded workspace's right-hand first cell.
            Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 12.dp, bottom = 12.dp)
                .width((if (expandedWorkspace) state.expanded else state.compact).dockWidth.dp).height(64.dp)
                .dropRegion(drag, DropTarget.Remove).testTag("remove-drop-target"),
            color = if (target == DropTarget.Remove) Color(0xFFB33B3B) else Glass.copy(alpha = .96f), shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.fillMaxSize().padding(vertical = 6.dp), verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.DeleteOutline, null)
                Text("Remove", fontSize = 11.sp, maxLines = 1)
            }
        }
    }
    WidgetResizeOverlay(
        state = state, model = model, drag = drag,
        resizePitchX = resizePitchX, resizePitchY = resizePitchY,
        resizeTopPitch = resizeTopPitch, resizeAppPitch = resizeAppPitch,
        resizeSlotState = resizeSlotState, resizeWidthState = resizeWidthState,
        resizeHeightState = resizeHeightState, resizeConstraintsState = resizeConstraintsState,
    )
    HomeDialogs(
        state = state, model = model, widgets = widgets, drag = drag, pager = pager,
        launcherActivity = launcherActivity, appsById = appsById, homePages = homePages,
        lastHomePage = lastHomePage, expandedWorkspace = expandedWorkspace,
        onAppInfo = onAppInfo, onLaunchFrom = onLaunchFrom,
        leaveTemporaryWidgetPage = leaveTemporaryWidgetPage,
        sheetState = sheetState, selectedIdState = selectedIdState,
        appMoveMenuState = appMoveMenuState, widgetSlotState = widgetSlotState,
        widgetTargetIndexState = widgetTargetIndexState, widgetPackageState = widgetPackageState,
        widgetProfileSerialState = widgetProfileSerialState,
        widgetExactTargetState = widgetExactTargetState, emptyCellIndexState = emptyCellIndexState,
        createFolderFirstIdState = createFolderFirstIdState, openFolderIdState = openFolderIdState,
    )
    }

}

@Composable
internal fun CircleControl(icon: ImageVector, label: String, tag: String, visualSize: Dp, action: () -> Unit) {
    IconButton(onClick = action, modifier = Modifier.size(visualSize.coerceAtLeast(48.dp)).testTag(tag)) {
        Box(Modifier.size(visualSize).testTag("$tag-visual").background(Glass.copy(alpha = .22f), CircleShape)
            .border(1.dp, Color.White.copy(alpha = .25f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = Color.White, modifier = Modifier.size(22.dp))
        }
    }
}
