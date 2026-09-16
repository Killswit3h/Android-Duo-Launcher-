@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.jake.duolauncher.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.jake.duolauncher.AppEntry
import com.jake.duolauncher.AppLibrary
import com.jake.duolauncher.DEFAULT_GRID
import com.jake.duolauncher.DiscoverContent
import com.jake.duolauncher.DropTarget
import com.jake.duolauncher.GridSpec
import com.jake.duolauncher.HomeDragState
import com.jake.duolauncher.HomeGeometry
import com.jake.duolauncher.LauncherState
import com.jake.duolauncher.WidgetController
import com.jake.duolauncher.WidgetPlacement
import com.jake.duolauncher.WorkspacePageMotion
import com.jake.duolauncher.coveredIndices
import com.jake.duolauncher.homeCellIndex
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.snapshotFlow
import com.jake.duolauncher.DuoMotionTrace
import com.jake.duolauncher.LiveDiscover
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
internal fun ExpandedWorkspace(
    nativePager: androidx.compose.foundation.pager.PagerState,
    motion: WorkspacePageMotion,
    firstHome: Int,
    visibleHomePages: Int,
    panelWidth: Dp,
    contentHeight: Dp,
    bottomSpace: Dp,
    geometry: HomeGeometry,
    state: LauncherState,
    previewSlots: List<String?>,
    previewLeadingSlots: List<String?>,
    previewWidgetPlacements: List<WidgetPlacement>,
    appsById: Map<String, AppEntry>,
    widgets: WidgetController,
    drag: HomeDragState,
    target: DropTarget?,
    insertionTarget: DropTarget?,
    /** Supplies the App Library's pin, work-profile and remembered-view writes (FR-69, FR-70). */
    model: com.jake.duolauncher.LauncherModel,
    libraryQuery: String,
    onLibraryQuery: (String) -> Unit,
    grid: GridSpec = DEFAULT_GRID,
    editing: Boolean = false,
    onRemoveItem: ((String) -> Unit)? = null,
    onRemoveWidget: ((Int) -> Unit)? = null,
    onEditMode: (() -> Unit)? = null,
    onBackgroundTap: (() -> Unit)? = null,
    onLaunch: (AppEntry) -> Unit,
    onLaunchFrom: (AppEntry, android.graphics.Rect?) -> Unit,
    onPinned: (String, Boolean) -> Unit,
    onTurnOnWork: (Long) -> Unit,
    onActions: (AppEntry) -> Unit,
    onWidget: (Int) -> Unit,
    onFolder: (String) -> Unit,
    onEmptyWidget: (Int) -> Unit,
    onRefresh: () -> Unit,
    /**
     * FR-55, FR-56: what the leading pane draws.
     *
     * Null keeps the Classic workspace grid, which is both the upgrade default and what the
     * Classic leading-page setting selects. Supplying it replaces that grid with the Today View
     * column, giving the first spread the widget column on the left and Home 1 on the right
     * (AC-45) without the pager's page numbering changing at all.
     */
    leadingPane: (@Composable (Modifier) -> Unit)? = null,
) {
    val density = LocalDensity.current
    val viewportWidth = motion.pageWidth
    val stride = motion.homeStride
    val initialHomeOrigin = with(density) { panelWidth.toPx() }
    val homePaneWidth = with(density) { (geometry.gridWidth + 16f).dp.toPx() }
    val stateHolder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val visibleHomes by remember(nativePager, motion, firstHome, visibleHomePages, initialHomeOrigin, homePaneWidth) {
        derivedStateOf(structuralEqualityPolicy()) {
            val physicalPosition = nativePager.currentPage + nativePager.currentPageOffsetFraction
            val scroll = motion.offset(physicalPosition)
            val intersectingHomes = (0 until visibleHomePages).filter { page ->
                val start = initialHomeOrigin + page * stride
                start + homePaneWidth > scroll && start < scroll + viewportWidth
            }
            val nearestLogicalPage = nativePager.currentPage - firstHome
            // While Discover is current, keep the initial Home pair cached. Otherwise Home 2
            // is recreated midway through the first native exit and provider inflation can
            // block the gesture frame even though that pane began offscreen.
            val retentionAnchor = nearestLogicalPage.coerceAtLeast(0)
            (intersectingHomes + (retentionAnchor - 1..retentionAnchor + 1))
                .filter { it in 0 until visibleHomePages }.distinct().sorted()
        }
    }
    val place: Modifier.(Float) -> Modifier = { x ->
        offset {
            val physicalPosition = nativePager.currentPage + nativePager.currentPageOffsetFraction
            IntOffset((x - motion.offset(physicalPosition)).roundToInt(), 0)
        }
    }
    val showDiscover by remember(nativePager, firstHome) {
        derivedStateOf(structuralEqualityPolicy()) {
            firstHome > 0 && nativePager.currentPage + nativePager.currentPageOffsetFraction <= firstHome + .25f
        }
    }
    val leadingX = initialHomeOrigin - stride
    val showLeading by remember(nativePager, motion, firstHome, panelWidth, leadingX, homePaneWidth) {
        derivedStateOf(structuralEqualityPolicy()) {
            val physicalPosition = nativePager.currentPage + nativePager.currentPageOffsetFraction
            val scroll = motion.offset(physicalPosition)
            panelWidth.value > 0f && physicalPosition - firstHome < 1f &&
                leadingX - scroll + homePaneWidth > 0f
        }
    }
    val libraryPhysicalPage = firstHome + visibleHomePages
    val showLibrary by remember(nativePager, libraryPhysicalPage) {
        derivedStateOf(structuralEqualityPolicy()) {
            nativePager.currentPage + nativePager.currentPageOffsetFraction >= libraryPhysicalPage - 1.25f
        }
    }

    Box(Modifier.fillMaxSize().clipToBounds().testTag("expanded-workspace")) {
        if (showDiscover) {
            key("discover-pane") {
                Box(Modifier.place(-viewportWidth).fillMaxSize()) {
                    DiscoverContent(Modifier.fillMaxSize().padding(start = 16.dp, top = 16.dp, bottom = 16.dp))
                }
            }
        }

        if (showLeading) {
            key("expanded-leading-home") {
                Box(Modifier.place(leadingX).width((geometry.gridWidth + 16f).dp).fillMaxHeight()
                    .testTag("expanded-leading-home")) {
                    if (leadingPane != null) {
                        leadingPane(
                            Modifier.fillMaxSize()
                                .padding(start = 16.dp, top = geometry.contentTop.dp, bottom = bottomSpace),
                        )
                    } else {
                        HomePagePane(
                            -1, state, previewSlots, previewLeadingSlots, previewWidgetPlacements, appsById, geometry, contentHeight, bottomSpace,
                            widgets, drag, target, insertionTarget, showLargeWidget = true,
                            grid = grid, editing = editing, onRemoveItem = onRemoveItem, onRemoveWidget = onRemoveWidget,
                            onEditMode = onEditMode, onBackgroundTap = onBackgroundTap,
                            onLaunch = onLaunchFrom, onActions = onActions, onWidget = onWidget,
                            onFolder = onFolder, onEmptyWidget = onEmptyWidget, onRefresh = onRefresh,
                            modifier = Modifier,
                        )
                    }
                }
            }
        }

        visibleHomes.forEach { page ->
            key("expanded-home-$page") {
                stateHolder.SaveableStateProvider("expanded-home-$page") {
                    Box(Modifier.place(initialHomeOrigin + page * stride)
                        .width((geometry.gridWidth + 16f).dp).fillMaxHeight()) {
                        HomePagePane(
                            page, state, previewSlots, previewLeadingSlots, previewWidgetPlacements, appsById, geometry, contentHeight, bottomSpace,
                            widgets, drag, target, insertionTarget, showLargeWidget = page > 0,
                            grid = grid, editing = editing, onRemoveItem = onRemoveItem, onRemoveWidget = onRemoveWidget,
                            onEditMode = onEditMode, onBackgroundTap = onBackgroundTap,
                            onLaunch = onLaunchFrom, onActions = onActions, onWidget = onWidget,
                            onFolder = onFolder,
                            onEmptyWidget = onEmptyWidget,
                            onRefresh = onRefresh,
                        )
                    }
                }
            }
        }

        if (showLibrary) {
            key("library-pane") {
                Box(Modifier.place((visibleHomePages - 1) * stride + viewportWidth).fillMaxSize()) {
                    HostedAppLibrary(state, model, libraryQuery, onLibraryQuery, onLaunch,
                        onActions = onActions,
                        modifier = Modifier.fillMaxSize().padding(start = 16.dp, top = 16.dp, bottom = bottomSpace)
                            .testTag("library-page"),
                        drag = drag, page = visibleHomePages, onLaunchFrom = onLaunchFrom)
                }
            }
        }
    }
}

@Composable
internal fun HomePagePane(
    page: Int,
    state: LauncherState,
    previewSlots: List<String?>,
    previewLeadingSlots: List<String?>,
    previewWidgetPlacements: List<WidgetPlacement>,
    appsById: Map<String, AppEntry>,
    geometry: HomeGeometry,
    contentHeight: Dp,
    bottomSpace: Dp,
    widgets: WidgetController,
    drag: HomeDragState,
    target: DropTarget?,
    insertionTarget: DropTarget?,
    showLargeWidget: Boolean,
    grid: GridSpec = DEFAULT_GRID,
    editing: Boolean = false,
    onRemoveItem: ((String) -> Unit)? = null,
    onRemoveWidget: ((Int) -> Unit)? = null,
    /** FR-45: a long-press on empty Home background enters Edit mode instead of opening a sheet. */
    onEditMode: (() -> Unit)? = null,
    /** FR-48: a tap on empty Home background leaves Edit mode. */
    onBackgroundTap: (() -> Unit)? = null,
    onLaunch: (AppEntry, android.graphics.Rect?) -> Unit,
    onActions: (AppEntry) -> Unit,
    onWidget: (Int) -> Unit,
    onFolder: (String) -> Unit,
    onEmptyWidget: (Int) -> Unit = {},
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val homeScroll = rememberScrollState()
    var paneBounds by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    val pageStart = homeCellIndex(page, 0, grid)
    val backgroundTarget = (pageStart until pageStart + grid.cells).firstOrNull { index ->
        state.layout.slotAt(index) == null && state.widgetPlacements.none { index in it.coveredIndices(grid) }
    } ?: pageStart
    val verticalEdge = with(LocalDensity.current) { 42.dp.toPx() }
    LaunchedEffect(drag.active, page, paneBounds) {
        while (drag.active) {
            val pointer = drag.pointer
            val amount = when {
                !paneBounds.contains(pointer) -> 0f
                pointer.y < paneBounds.top + verticalEdge && homeScroll.canScrollBackward -> -18f
                pointer.y > paneBounds.bottom - verticalEdge && homeScroll.canScrollForward -> 18f
                else -> 0f
            }
            if (amount != 0f) homeScroll.scrollBy(amount)
            delay(16)
        }
    }
    // FR-45: empty Home background is the way into Edit mode. Where no handler is supplied — the
    // leading workspace, and any caller from before Edit mode existed — the old sheet still opens,
    // so this is additive rather than a behaviour change for everyone.
    val backgroundLongPress = { if (!drag.active) onEditMode?.invoke() ?: onEmptyWidget(backgroundTarget) }
    Box(modifier.testTag("home-page-$page")
        .semantics {
            onLongClick("Home options") {
                backgroundLongPress()
                !drag.active
            }
        }
        .onGloballyPositioned { paneBounds = it.boundsInRoot() }
        .width((geometry.gridWidth + 16f).dp)
        .height((contentHeight - bottomSpace).coerceAtLeast(0.dp))) {
        Box(Modifier.width(16.dp).fillMaxHeight().testTag("home-options-margin-$page")
            .pointerInput(backgroundTarget, drag.active, onEditMode, onBackgroundTap) {
                detectTapGestures(
                    onLongPress = { backgroundLongPress() },
                    onTap = { onBackgroundTap?.invoke() },
                )
            })
        Column(Modifier.offset(x = 16.dp).width(geometry.gridWidth.dp).fillMaxHeight()
            .verticalScroll(homeScroll).padding(top = geometry.contentTop.dp, bottom = 8.dp)) {
            SharedHomeGrid(page, state.homeSlots, state.leadingSlots, previewSlots, previewLeadingSlots, previewWidgetPlacements,
                appsById, geometry, widgets, drag, target,
                folders = state.folders, grid = grid, editing = editing,
                onRemoveItem = onRemoveItem, onRemoveWidget = onRemoveWidget,
                onLaunch = onLaunch, onActions = onActions, onWidget = onWidget,
                onFolder = onFolder, onEmptyWidget = onEmptyWidget)
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
            if (state.error != null) Text(state.error, color = Color.White,
                modifier = Modifier.clickable(onClick = onRefresh).padding(12.dp))
        }
    }
}

/** Bridges Google's native Discover progress callbacks onto the Compose pager. */
@Composable
internal fun NativeDiscoverMotion(nativePager: PagerState, firstHome: Int) {

    var nativeMotion by remember { mutableStateOf(false) }
    DisposableEffect(nativePager) {
        val callback: (Float) -> Unit = { progress ->
            val scrolling = nativePager.isScrollInProgress
            if (DuoMotionTrace.enabled) DuoMotionTrace.event("native_callback_received",
                "progress=$progress scrolling=$scrolling nativeMotion=$nativeMotion current=${nativePager.currentPage} offset=${nativePager.currentPageOffsetFraction}")
            if (!scrolling || nativeMotion) {
                val priorNativeMotion = nativeMotion
                nativeMotion = progress > 0f && progress < 1f
                val position = 1f - progress
                val page = position.roundToInt()
                if (DuoMotionTrace.enabled) DuoMotionTrace.event("native_callback_accepted",
                    "progress=$progress nativeMotion=$priorNativeMotion->$nativeMotion requestPage=$page requestOffset=${position - page}")
                nativePager.requestScrollToPage(page, position - page)
            } else if (DuoMotionTrace.enabled) DuoMotionTrace.event("native_callback_rejected",
                "progress=$progress reason=compose_scrolling nativeMotion=$nativeMotion")
        }
        LiveDiscover.onNativeProgress = callback
        onDispose { if (LiveDiscover.onNativeProgress === callback) LiveDiscover.onNativeProgress = null }
    }
    LaunchedEffect(nativePager) {
        snapshotFlow { Triple((1f - nativePager.currentPage - nativePager.currentPageOffsetFraction).coerceIn(0f, 1f), nativePager.isScrollInProgress, nativeMotion) to (nativePager.targetPage < firstHome) }
            .collect { (motion, towardFeed) ->
                val (progress, scrolling, native) = motion
                if (firstHome > 0) {
                    if (DuoMotionTrace.enabled) DuoMotionTrace.event("pager_observer",
                        "progress=$progress scrolling=$scrolling nativeMotion=$native towardFeed=$towardFeed")
                    if (scrolling) {
                        if (nativeMotion && DuoMotionTrace.enabled) DuoMotionTrace.event("native_owner_cleared",
                            "reason=compose_scrolling progress=$progress")
                        nativeMotion = false
                        LiveDiscover.page(progress, true, towardFeed)
                    } else if (!native) LiveDiscover.page(progress, false)
                }
            }
    }

}
