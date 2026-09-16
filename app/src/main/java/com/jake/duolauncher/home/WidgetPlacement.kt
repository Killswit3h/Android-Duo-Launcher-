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

/**
 * The "widgets" sheet: provider catalog, placement session, drag preview and the
 * placement toolbar. Kept as one unit because the placement drag, the cell hit
 * testing and the preview anchor all share the same session state.
 */
@Composable
internal fun BoxScope.WidgetPlacementLayer(
    state: LauncherState,
    model: LauncherModel,
    widgets: WidgetController,
    drag: HomeDragState,
    pager: LauncherPager,
    scope: CoroutineScope,
    launcherActivity: MainActivity,
    geometry: HomeGeometry,
    homePages: Int,
    lastHomePage: Int,
    expandedWorkspace: Boolean,
    eligibleDragPages: Set<Int>,
    widgetDraft: WidgetPlacement?,
    widgetRawTarget: DropTarget.Home?,
    widgetSlot: Int,
    widgetTargetIndex: Int,
    widgetExactTarget: Boolean,
    leaveTemporaryWidgetPage: () -> Unit,
    widgetPickerBack: () -> Unit,
    sheetState: MutableState<String>,
    widgetSessionState: MutableState<WidgetPickerSession?>,
    widgetPlacementMessageState: MutableState<String?>,
    widgetPackageState: MutableState<String?>,
    widgetProfileSerialState: MutableState<Long?>,
) {
    var sheet by sheetState
    var widgetSession by widgetSessionState
    var widgetPlacementMessage by widgetPlacementMessageState
    var widgetPackage by widgetPackageState
    var widgetProfileSerial by widgetProfileSerialState


    if (sheet == "widgets") {
        val catalogProfiles = remember(state.profiles) { state.profiles.filter { it.isPersonal || it.isWork } }
        val selectedProfile = catalogProfiles.firstOrNull { it.userSerial == widgetProfileSerial }
            ?: catalogProfiles.firstOrNull { it.isPersonal } ?: AppProfile(0, "Personal", true, false, false, true, true)
        val userManager = remember(launcherActivity) { launcherActivity.getSystemService(UserManager::class.java) }
        val providers = remember(widgetPackage, selectedProfile, sheet, state.apps) {
            val user = userManager.getUserForSerialNumber(selectedProfile.userSerial)
            if (user == null || !selectedProfile.available || !selectedProfile.unlocked || selectedProfile.quiet) emptyList()
            else runCatching { widgetPackage?.let { widgets.providersForPackage(it, user) }
                ?: widgets.providers(user) }.getOrDefault(emptyList()).filter { provider ->
                provider.widgetCategory and AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN != 0 &&
                    provider.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_HIDE_FROM_PICKER == 0
            }
        }
        val catalog by produceState<List<WidgetCatalogEntry>?>(null, providers, selectedProfile.userSerial, sheet) {
            value = withContext(Dispatchers.IO) { widgetCatalog(launcherActivity, providers, selectedProfile) }
        }
        val topPitch = (geometry.widgetHeight + 18f) / 2f
        // FR-31: the picker measures, addresses and places against the layout's own grid.
        val grid = state.grid
        val pickerSizing = remember(geometry, grid) { WidgetGridSizing(grid.columns, grid.rows,
            geometry.gridWidth / grid.columns, minOf(topPitch, geometry.rowHeight),
            maxOf(topPitch, geometry.rowHeight), 10f, 18f,
            topRowHeightDp = topPitch, appRowHeightDp = geometry.rowHeight) }
        val footprint: (AppWidgetProviderInfo) -> WidgetSpan? = { provider ->
            widgets.sizing(provider, pickerSizing)?.takeIf { it.minimumFitsGrid }?.preferred
        }
        VisualWidgetPicker(catalog, catalogProfiles.ifEmpty { listOf(selectedProfile) }, selectedProfile,
            onSelectProfile = { widgetProfileSerial = it.userSerial; widgetPlacementMessage = null },
            onTurnOnWork = { model.turnOnWork(it) }, hiddenForDrag = widgetSession != null,
            footprint = footprint,
            onBack = widgetPickerBack,
            onTap = { provider ->
                footprint(provider)?.let { preferredSpan ->
                    val existing = model.placement(widgetSlot)
                    val constraints = widgets.sizing(provider, pickerSizing)
                    val span = existing?.let { placement ->
                        WidgetSpan(placement.spanX, placement.spanY).takeIf {
                            constraints != null && it.width in constraints.minimum.width..constraints.maximum.width &&
                                it.height in constraints.minimum.height..constraints.maximum.height
                        }
                    } ?: preferredSpan
                    val special = existing?.takeIf { it.row + it.spanY > grid.rows }
                    if (special != null) {
                        widgetSession = WidgetPickerSession(provider, widgetSlot,
                            WidgetSpan(special.spanX, special.spanY), Offset.Zero,
                            dragging = false, candidate = special)
                        widgetPlacementMessage = null
                        scope.launch { pager.scrollToPage(special.page.coerceAtLeast(0).coerceAtMost(homePages - 1)) }
                        return@let
                    }
                    val requestedIndex = existing?.let { homeCellIndex(it.page, it.row * grid.columns + it.column, grid) }
                        ?: widgetTargetIndex.takeUnless { it == Int.MIN_VALUE } ?: 0
                    val requestedPage = homeCellPage(requestedIndex, grid).coerceIn(if (expandedWorkspace) -1 else 0, homePages)
                    val availablePages = (if (expandedWorkspace) -1 else 0)..homePages
                    val autoPages = (listOf(requestedPage) + availablePages.filter { it != requestedPage })
                    val freeIndex = if (existing != null || widgetExactTarget) requestedIndex.takeIf {
                        widgetCandidate(state.layout, widgetSlot, it, span.width, span.height) != null
                    } else autoPages.asSequence().flatMap { page ->
                        (0 until grid.cells).asSequence().map { homeCellIndex(page, it, grid) }
                    }.firstOrNull { widgetCandidate(state.layout, widgetSlot, it, span.width, span.height) != null }
                    val targetIndex = freeIndex ?: requestedIndex
                    widgetSession = WidgetPickerSession(provider, widgetSlot, span, Offset.Zero,
                        dragging = false, targetIndex = targetIndex)
                    widgetPlacementMessage = if (freeIndex == null)
                        "There isn’t room for this size. Choose another page or move an item first." else null
                    scope.launch { pager.scrollToPage(homeCellPage(targetIndex, grid).coerceIn(0, homePages)) }
                }
            },
            onBuiltin = builtin@{ builtinId ->
                val existing = model.placement(widgetSlot)
                val special = existing?.takeIf { it.row + it.spanY > grid.rows }
                val span = existing?.let { WidgetSpan(it.spanX, it.spanY) } ?: WidgetSpan(2, 2)
                if (special != null) {
                    widgetSession = WidgetPickerSession(null, widgetSlot, span, Offset.Zero,
                        dragging = false, candidate = special, builtinId = builtinId)
                    widgetPlacementMessage = null
                    scope.launch { pager.scrollToPage(special.page.coerceAtLeast(0).coerceAtMost(homePages - 1)) }
                    return@builtin
                }
                val requested = existing?.let {
                    homeCellIndex(it.page, it.row * grid.columns + it.column, grid)
                } ?: widgetTargetIndex.takeUnless { it == Int.MIN_VALUE } ?: 0
                val requestedPage = homeCellPage(requested, grid).coerceIn(if (expandedWorkspace) -1 else 0, homePages)
                val availablePages = (if (expandedWorkspace) -1 else 0)..homePages
                val candidates = if (model.placement(widgetSlot) != null || widgetExactTarget) sequenceOf(requested)
                    else (listOf(requestedPage) + availablePages.filter { it != requestedPage }).asSequence()
                        .flatMap { page -> (0 until grid.cells).asSequence().map { homeCellIndex(page, it, grid) } }
                val free = candidates.firstOrNull {
                    widgetCandidate(state.layout, widgetSlot, it, span.width, span.height) != null
                }
                widgetSession = WidgetPickerSession(null, widgetSlot, span, Offset.Zero,
                    dragging = false, targetIndex = free ?: requested, builtinId = builtinId)
                widgetPlacementMessage = if (free == null)
                    "There isn’t room for this card. Choose another page or move an item first." else null
                scope.launch { pager.scrollToPage(homeCellPage(free ?: requested, grid).coerceIn(0, homePages)) }
            },
            onDragStart = { provider, point ->
                footprint(provider)?.let { span ->
                    widgetSession = WidgetPickerSession(provider, widgetSlot, span, point, dragging = true)
                    widgetPlacementMessage = null
                    scope.launch { pager.scrollToPage(lastHomePage.coerceIn(0, homePages - 1)) }
                }
            },
            onDrag = { point -> widgetSession = widgetSession?.copy(pointer = point) },
            onDrop = {
                val session = widgetSession
                if (session != null && widgetDraft != null) {
                    session.provider?.let { widgets.add(widgetDraft, it, pickerSizing) }
                        ?: session.builtinId?.let { widgets.setBuiltin(widgetDraft.copy(id = it)) }
                    widgetSession = null; sheet = ""; widgetPackage = null
                } else {
                    leaveTemporaryWidgetPage(); widgetSession = null
                    widgetPlacementMessage = "There isn’t room there. Try another space or page."
                }
            },
            onCancelDrag = {
                if (widgetSession != null) {
                    leaveTemporaryWidgetPage(); widgetSession = null
                }
            })
        widgetSession?.let { session ->
            val placementDensity = LocalDensity.current
            val sessionEntry = session.provider?.let { selected -> catalog?.firstOrNull {
                it.provider.provider == selected.provider && it.provider.profile == selected.profile } }
            // Legacy overflow replacements are locked to their existing view
            // bounds and may begin below the canonical six-row grid. They have
            // no Home-cell address; specialAnchor below is their visual anchor.
            val candidateIndex = widgetDraft?.takeIf { session.candidate == null }
                ?.let { homeCellIndex(it.page, it.row * grid.columns + it.column, grid) }
            val visualIndex = candidateIndex ?: widgetRawTarget?.index ?: session.targetIndex
            val specialAnchor = session.candidate?.let { drag.regions[DropTarget.Widget(session.slot)]?.bounds }
            val anchor = specialAnchor ?: visualIndex?.let { drag.regions[DropTarget.Home(it)]?.bounds }
            Box(Modifier.fillMaxSize().testTag("widget-placement-mode")
                .then(if (!session.dragging && session.candidate == null) Modifier.pointerInput(session.slot, session.span) {
                    detectTapGestures { local ->
                        val point = local + drag.rootOrigin
                        val cell = drag.regions.values.firstOrNull {
                            it.target is DropTarget.Home && it.page in eligibleDragPages && it.bounds.contains(point)
                        }?.target as? DropTarget.Home
                        cell?.let { widgetSession = session.copy(pointer = point, targetIndex = it.index) }
                    }
                } else Modifier)) {
                Row(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 8.dp)
                    .background(Glass.copy(alpha = .97f), RoundedCornerShape(22.dp))
                    .testTag("widget-placement-toolbar"), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = widgetPickerBack) { Text("Back to widgets") }
                    if (session.candidate != null) Text("Replace here", color = Ink,
                        modifier = Modifier.testTag("widget-replacement-locked"))
                    val targetPage = homeCellPage(session.targetIndex ?: 0, grid)
                    if (!session.dragging && session.candidate == null) IconButton(
                        enabled = targetPage > if (expandedWorkspace) -1 else 0, onClick = {
                        val local = homeCellLocal(session.targetIndex ?: 0, grid)
                        val page = targetPage - 1
                        widgetSession = session.copy(targetIndex = homeCellIndex(page, local, grid))
                        scope.launch { pager.animateScrollToPage(page.coerceAtLeast(0)) }
                    }) { Icon(Icons.Rounded.ChevronLeft, "Previous home page") }
                    Text("${session.span.width} × ${session.span.height}", color = Ink)
                    if (!session.dragging && session.candidate == null) IconButton(enabled = targetPage < homePages, onClick = {
                        val local = homeCellLocal(session.targetIndex ?: 0, grid)
                        val page = (targetPage + 1).coerceAtMost(homePages)
                        widgetSession = session.copy(targetIndex = homeCellIndex(page, local, grid))
                        scope.launch { pager.animateScrollToPage(page.coerceAtLeast(0)) }
                    }) { Icon(Icons.Rounded.ChevronRight, "Next home page") }
                    if (!session.dragging) TextButton(enabled = widgetDraft != null, onClick = {
                        widgetDraft?.let { draft ->
                            val contentSize = specialAnchor?.let { bounds -> with(placementDensity) {
                                WidgetContentSize(bounds.width.toDp().value, bounds.height.toDp().value)
                            } }
                            session.provider?.let { widgets.add(draft, it, pickerSizing, contentSize) }
                                ?: session.builtinId?.let { widgets.setBuiltin(draft.copy(id = it)) }
                            widgetSession = null; sheet = ""; widgetPackage = null
                        }
                    }, modifier = Modifier.testTag("widget-placement-apply")) { Text("Place") }
                    TextButton(onClick = { leaveTemporaryWidgetPage(); widgetSession = null; sheet = ""; widgetPackage = null },
                        modifier = Modifier.testTag("widget-placement-cancel")) { Text("Cancel") }
                }
                if (anchor != null) {
                    val density = LocalDensity.current
                    val cellWidthPx = with(density) { (geometry.gridWidth / grid.columns).dp.toPx() }
                    fun pickerRowTop(row: Int): Float = if (row <= 2) row * with(density) { topPitch.dp.toPx() }
                        else with(density) { (geometry.widgetHeight + 18f + (row - 2) * geometry.rowHeight).dp.toPx() }
                    val candidateRow = homeCellLocal(visualIndex ?: 0, grid) / grid.columns
                    val previewWidth = specialAnchor?.let { with(density) { it.width.toDp() } }
                        ?: with(density) { (cellWidthPx * session.span.width - 10.dp.toPx()).toDp() }
                    val previewHeight = specialAnchor?.let { with(density) { it.height.toDp() } }
                        ?: with(density) { (pickerRowTop(candidateRow + session.span.height) -
                            pickerRowTop(candidateRow) - 18.dp.toPx()).coerceAtLeast(48.dp.toPx()).toDp() }
                    val previewX = if (specialAnchor != null) anchor.left
                        else anchor.left + with(density) { 5.dp.toPx() }
                    Surface(Modifier.offset { IntOffset(previewX.roundToInt(), anchor.top.roundToInt()) }
                        .size(previewWidth, previewHeight).testTag("widget-placement-preview")
                        .semantics { stateDescription = if (widgetDraft != null) "Ready to place" else "No room here" },
                        color = if (widgetDraft != null) Glass.copy(alpha = .82f) else Color(0xFFE7B6B6).copy(alpha = .9f),
                        shape = RoundedCornerShape(24.dp), border = androidx.compose.foundation.BorderStroke(3.dp,
                            if (widgetDraft != null) Color.White else Color(0xFFFF6B6B))) {
                        Box(Modifier.fillMaxSize()) {
                            if (sessionEntry != null) WidgetProviderPreview(sessionEntry, session.span,
                                Modifier.fillMaxSize().padding(5.dp).clip(RoundedCornerShape(18.dp)))
                            else Column(Modifier.align(Alignment.Center).padding(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(session.provider?.loadLabel(launcherActivity.packageManager)?.toString()
                                    ?: when (session.builtinId) {
                                        CLOCK_WIDGET -> "Clock"
                                        DATE_WIDGET -> "Date"
                                        else -> "Widget panel"
                                    }, color = Ink,
                                    textAlign = TextAlign.Center)
                                Text("${session.span.width} × ${session.span.height}", color = Ink)
                            }
                            if (widgetDraft == null) Box(Modifier.matchParentSize()
                                .background(Color(0xFFB83B3B).copy(alpha = .34f)), contentAlignment = Alignment.Center) {
                                Text("No room here", color = Color.White, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                } else if (session.dragging) {
                    Surface(Modifier.offset { IntOffset((session.pointer.x - 90.dp.toPx()).roundToInt(),
                        (session.pointer.y - 60.dp.toPx()).roundToInt()) }.size(180.dp, 120.dp)
                        .testTag("widget-placement-preview").semantics { stateDescription = "No room here" },
                        color = Color(0xFFE7B6B6).copy(alpha = .9f), shape = RoundedCornerShape(24.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            if (sessionEntry != null) WidgetProviderPreview(sessionEntry, session.span,
                                Modifier.fillMaxSize().padding(5.dp).clip(RoundedCornerShape(18.dp)))
                            Box(Modifier.matchParentSize().background(Color(0xFFB83B3B).copy(alpha = .34f)),
                                contentAlignment = Alignment.Center) { Text("No room here", color = Color.White) }
                        }
                    }
                }
            }
        }
        widgetPlacementMessage?.let { message ->
            Surface(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(20.dp),
                color = Glass, shape = RoundedCornerShape(18.dp)) { Text(message, Modifier.padding(16.dp), color = Ink) }
        }
    }

}

/** The on-Home widget resize frame and its drag handle. */
@Composable
internal fun WidgetResizeOverlay(
    state: LauncherState,
    model: LauncherModel,
    drag: HomeDragState,
    resizePitchX: Float,
    resizePitchY: Float,
    resizeTopPitch: Float,
    resizeAppPitch: Float,
    resizeSlotState: MutableState<Int?>,
    resizeWidthState: MutableState<Int>,
    resizeHeightState: MutableState<Int>,
    resizeConstraintsState: MutableState<WidgetSpanConstraints?>,
) {
    var resizeSlot by resizeSlotState
    var resizeWidth by resizeWidthState
    var resizeHeight by resizeHeightState
    var resizeConstraints by resizeConstraintsState


resizeSlot?.let { slot ->
    val placement = model.placement(slot)
    val bounds = drag.regions[DropTarget.Widget(slot)]?.bounds
    if (placement != null && bounds != null) {
        val grid = state.grid
        val minW = resizeConstraints?.minimum?.width ?: 2
        val minH = resizeConstraints?.minimum?.height ?: 2
        val maxW = minOf(grid.columns - placement.column, resizeConstraints?.maximum?.width ?: grid.columns)
        val maxH = minOf(grid.rows - placement.row, resizeConstraints?.maximum?.height ?: grid.rows)
        val feasible = placement.page >= -1 && placement.row in 0 until grid.rows &&
            !(placement.id >= 0 && resizeConstraints == null) && minW <= maxW && minH <= maxH
        val candidate = resizeWidget(state.layout, slot, resizeWidth, resizeHeight)
        val valid = feasible && ((resizeWidth == placement.spanX && resizeHeight == placement.spanY) || candidate != state.layout)
        val widthPx = (bounds.width + (resizeWidth - placement.spanX) * resizePitchX).coerceAtLeast(resizePitchX)
        val density = LocalDensity.current
        fun resizeRowTop(row: Int) = if (row <= 2) row * resizeTopPitch else 2 * resizeTopPitch + (row - 2) * resizeAppPitch
        val heightPx = (resizeRowTop(placement.row + resizeHeight) - resizeRowTop(placement.row) -
            with(density) { 18.dp.toPx() }).coerceAtLeast(resizePitchY)
        Box(Modifier.offset { IntOffset(bounds.left.roundToInt(), bounds.top.roundToInt()) }
            .size(with(density) { widthPx.toDp() }, with(density) { heightPx.toDp() })
            .border(3.dp, if (valid) Color.White else Color(0xFFFF6B6B), RoundedCornerShape(24.dp))
            .testTag("widget-resize-preview-$slot")) {
            Box(Modifier.align(Alignment.BottomEnd).offset(12.dp, 12.dp).size(44.dp)
                .background(if (valid) Color.White else Color(0xFFFF6B6B), CircleShape)
                .testTag("widget-resize-handle-$slot")
                .pointerInput(slot, resizeConstraints) {
                    var dx = 0f; var dy = 0f; var startWidth = resizeWidth; var startHeight = resizeHeight
                    detectDragGestures(onDragStart = {
                        dx = 0f; dy = 0f; startWidth = resizeWidth; startHeight = resizeHeight
                    }, onDrag = { change, amount ->
                        change.consume(); dx += amount.x; dy += amount.y
                        if (feasible && resizeConstraints?.canResizeHorizontally != false)
                            resizeWidth = (startWidth + (dx / resizePitchX).roundToInt()).coerceIn(minW, maxW)
                        if (feasible && resizeConstraints?.canResizeVertically != false)
                            resizeHeight = (startHeight + (dy / resizePitchY).roundToInt()).coerceIn(minH, maxH)
                    })
                }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.OpenInFull, "Drag to resize widget", tint = Ink, modifier = Modifier.size(22.dp))
            }
            Row(Modifier.align(Alignment.TopCenter).padding(top = 8.dp)
                .background(Glass.copy(alpha = .96f), RoundedCornerShape(20.dp))) {
                TextButton(onClick = { resizeSlot = null }) { Text("Cancel") }
                TextButton(enabled = valid, onClick = {
                    model.resizeWidget(slot, resizeWidth, resizeHeight); resizeSlot = null
                }) { Text("Apply") }
            }
            if (!feasible) Text("Move this widget into the six-row grid before resizing.",
                color = Color.White, modifier = Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = .65f)).padding(8.dp))
        }
    }
}

}
