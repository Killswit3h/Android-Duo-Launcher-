package com.jake.duolauncher.today

import android.content.res.Configuration
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.jake.duolauncher.DuoTheme
import com.jake.duolauncher.design.DuoTokens
import com.jake.duolauncher.design.currentDuoColors
import com.jake.duolauncher.design.rememberMotionEnabled
import com.jake.duolauncher.icons.IconRenderer
import com.jake.duolauncher.icons.IconStyle
import com.jake.duolauncher.today.builtin.BatteryReading
import com.jake.duolauncher.today.builtin.CalendarFeedState
import com.jake.duolauncher.today.builtin.NowPlayingState
import com.jake.duolauncher.today.widgets.BatteriesWidget
import com.jake.duolauncher.today.widgets.CalendarWidget
import com.jake.duolauncher.today.widgets.ClockWidget
import com.jake.duolauncher.today.widgets.NowPlayingWidget
import com.jake.duolauncher.today.widgets.PreviewClockTime
import com.jake.duolauncher.today.widgets.SuggestionsWidget
import com.jake.duolauncher.today.widgets.TodayPreviewBackdrop
import com.jake.duolauncher.today.widgets.TodayTouchTarget
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

/**
 * The Today View column: a vertically scrolling stack of widgets, filling the left page of the
 * unfolded spread and the page left of Home 1 when folded (FR-56, AC-45).
 *
 * **This composable owns no ordering.** [items] comes in as a parameter and every edit leaves
 * through [onMove], [onRemove] and [onAdd], because the schema-9 layout task owns persistence. That
 * is what lets the two tracks land independently: this file decides how the column *looks* and how
 * a drag *resolves*, and the other decides what survives a restart (AC-47).
 *
 * A plain [Column] with [verticalScroll] rather than a `LazyColumn`: the column holds a handful of
 * widgets, never a feed, so laziness buys nothing while costing the stable child positions that
 * drag-to-reorder needs.
 */
@Composable
fun TodayView(
    items: List<TodayItem>,
    data: TodayWidgetData,
    modifier: Modifier = Modifier,
    actions: TodayWidgetActions = TodayWidgetActions(),
    editing: Boolean = false,
    onEditingChange: (Boolean) -> Unit = {},
    onMove: (from: Int, to: Int) -> Unit = { _, _ -> },
    onRemove: (id: String) -> Unit = {},
    onAdd: (TodayWidgetKind, TodayWidgetSize) -> Unit = { _, _ -> },
    iconRenderer: IconRenderer? = null,
    iconStyle: IconStyle = IconStyle(),
    use24Hour: Boolean = false,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    var showPicker by rememberSaveable { mutableStateOf(false) }
    val rows = remember(items) { todayRows(items) }
    val indexOf = remember(items) { items.withIndex().associate { (index, item) -> item.id to index } }

    // Root-space vertical centres of each slot, recorded as they lay out. A finished drag resolves
    // to whichever slot centre the dragged card ended up nearest.
    val centers = remember { mutableStateMapOf<String, Float>() }
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }

    fun commitDrag() {
        val id = draggingId
        val from = id?.let { indexOf[it] }
        val anchor = id?.let { centers[it] }
        if (id != null && from != null && anchor != null) {
            val dropped = anchor + dragOffset
            val to = items.indices.minByOrNull { index ->
                abs((centers[items[index].id] ?: Float.MAX_VALUE) - dropped)
            }
            if (to != null && to != from) onMove(from, to)
        }
        draggingId = null
        dragOffset = 0f
    }

    BoxWithConstraints(modifier = modifier) {
        val metrics = TodayMetrics(columnWidth = maxWidth)
        val availableRows = remember(metrics, maxHeight) {
            val cellPlusGutter = metrics.cell + metrics.gutter
            if (cellPlusGutter.value <= 0f) TodayWidgetSize.EXTRA_LARGE.rows
            else (maxHeight.value / cellPlusGutter.value).toInt().coerceAtLeast(TodayWidgetSize.SMALL.rows)
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(vertical = DuoTokens.space.lg),
            verticalArrangement = Arrangement.spacedBy(metrics.gutter),
        ) {
            TodayHeader(
                editing = editing,
                onEditingChange = onEditingChange,
                onAddClick = { showPicker = true },
            )

            if (items.isEmpty()) {
                TodayEmptyState(onAddClick = { showPicker = true })
            }

            rows.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(metrics.gutter),
                ) {
                    row.forEach { item ->
                        val index = indexOf[item.id] ?: 0
                        TodaySlot(
                            item = item,
                            index = index,
                            lastIndex = items.lastIndex,
                            metrics = metrics,
                            editing = editing,
                            dragging = draggingId == item.id,
                            dragOffset = if (draggingId == item.id) dragOffset else 0f,
                            onCenterChanged = { centers[item.id] = it },
                            onDragStart = {
                                draggingId = item.id
                                dragOffset = 0f
                            },
                            onDragDelta = { dragOffset += it },
                            onDragEnd = ::commitDrag,
                            onDragCancel = {
                                draggingId = null
                                dragOffset = 0f
                            },
                            onRemove = { onRemove(item.id) },
                            onMoveUp = { onMove(index, index - 1) },
                            onMoveDown = { onMove(index, index + 1) },
                        ) { slotModifier ->
                            TodayWidget(
                                item = item,
                                data = data,
                                actions = actions,
                                iconRenderer = iconRenderer,
                                iconStyle = iconStyle,
                                use24Hour = use24Hour,
                                zone = zone,
                                modifier = slotModifier,
                            )
                        }
                    }
                    // A lone small widget keeps its half-width rather than stretching to fill.
                    if (row.size == 1 && row.first().size == TodayWidgetSize.SMALL) {
                        Box(modifier = Modifier.width(metrics.width(TodayWidgetSize.SMALL)))
                    }
                }
            }
        }

        if (showPicker) {
            TodayAddWidgetSheet(
                availableRows = availableRows,
                onAdd = { kind, size ->
                    onAdd(kind, size)
                    showPicker = false
                },
                onDismiss = { showPicker = false },
            )
        }
    }
}

/**
 * One positioned widget, plus its edit affordances.
 *
 * The drag handle is the only drag surface, so a drag can never be mistaken for a tap on the
 * widget's own content, and the explicit move buttons beside it give the same reorder to anyone
 * driving the launcher with TalkBack rather than a finger (NFR-A1).
 */
@Composable
private fun TodaySlot(
    item: TodayItem,
    index: Int,
    lastIndex: Int,
    metrics: TodayMetrics,
    editing: Boolean,
    dragging: Boolean,
    dragOffset: Float,
    onCenterChanged: (Float) -> Unit,
    onDragStart: () -> Unit,
    onDragDelta: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    onRemove: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    val motionEnabled = rememberMotionEnabled()
    val lift by animateFloatAsState(
        targetValue = if (dragging) DRAG_LIFT_SCALE else 1f,
        animationSpec = DuoTokens.motion.snappy(),
        label = "today drag lift",
    )
    val scale = if (motionEnabled) lift else 1f

    Column(
        modifier = Modifier
            .width(metrics.width(item.size))
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer {
                translationY = dragOffset
                scaleX = scale
                scaleY = scale
            },
        verticalArrangement = Arrangement.spacedBy(DuoTokens.space.xs),
    ) {
        Box {
            content(
                Modifier
                    .width(metrics.width(item.size))
                    .height(metrics.height(item.size))
                    .testTag("today-widget-${item.id}")
                    .onGloballyPositioned { onCenterChanged(it.boundsInRoot().center.y) },
            )
            if (editing) {
                TodayCircleButton(
                    icon = Icons.Rounded.Remove,
                    label = "Remove ${item.kind.displayName} widget",
                    onClick = onRemove,
                    modifier = Modifier.align(Alignment.TopStart).testTag("today-remove-${item.id}"),
                )
            }
        }
        if (editing) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(DuoTokens.space.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TodayCircleButton(
                    icon = Icons.Rounded.ArrowUpward,
                    label = "Move ${item.kind.displayName} up",
                    enabled = index > 0,
                    onClick = onMoveUp,
                )
                TodayCircleButton(
                    icon = Icons.Rounded.ArrowDownward,
                    label = "Move ${item.kind.displayName} down",
                    enabled = index < lastIndex,
                    onClick = onMoveDown,
                )
                TodayCircleButton(
                    icon = Icons.Rounded.DragHandle,
                    label = "Reorder ${item.kind.displayName}",
                    onClick = {},
                    modifier = Modifier
                        .testTag("today-drag-${item.id}")
                        .pointerInput(item.id) {
                            detectDragGestures(
                                onDragStart = { onDragStart() },
                                onDragEnd = { onDragEnd() },
                                onDragCancel = { onDragCancel() },
                            ) { change, delta ->
                                change.consume()
                                onDragDelta(delta.y)
                            }
                        },
                )
            }
        }
    }
}

/** Renders whichever built-in this item names (FR-59). */
@Composable
private fun TodayWidget(
    item: TodayItem,
    data: TodayWidgetData,
    actions: TodayWidgetActions,
    iconRenderer: IconRenderer?,
    iconStyle: IconStyle,
    use24Hour: Boolean,
    zone: ZoneId,
    modifier: Modifier = Modifier,
) {
    when (item.kind) {
        TodayWidgetKind.CLOCK -> ClockWidget(
            time = data.clock,
            style = item.clockStyle,
            size = item.size,
            modifier = modifier,
            use24Hour = use24Hour,
            onClick = actions.onOpenClock,
        )

        TodayWidgetKind.CALENDAR -> CalendarWidget(
            state = data.calendar,
            size = item.size,
            modifier = modifier,
            zone = zone,
            use24Hour = use24Hour,
            onGrantAccess = actions.onGrantCalendarAccess,
            onClick = actions.onOpenCalendar,
        )

        TodayWidgetKind.BATTERIES -> BatteriesWidget(
            reading = data.battery,
            size = item.size,
            modifier = modifier,
            onClick = actions.onOpenBatterySettings,
        )

        TodayWidgetKind.NOW_PLAYING -> NowPlayingWidget(
            state = data.nowPlaying,
            size = item.size,
            modifier = modifier,
            onTogglePlayPause = actions.onTogglePlayPause,
            onSkipNext = actions.onSkipNext,
            onGrantAccess = actions.onGrantNotificationAccess,
        )

        TodayWidgetKind.SUGGESTIONS -> SuggestionsWidget(
            suggestions = data.suggestions,
            size = item.size,
            modifier = modifier,
            iconRenderer = iconRenderer,
            iconStyle = iconStyle,
            onLaunch = actions.onLaunchApp,
        )
    }
}

/** The column's own Edit affordance (FR-57). */
@Composable
private fun TodayHeader(
    editing: Boolean,
    onEditingChange: (Boolean) -> Unit,
    onAddClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = currentDuoColors()
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(DuoTokens.space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Today",
            style = DuoTokens.type.title2,
            color = colors.label1,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (editing) {
            TodayCircleButton(
                icon = Icons.Rounded.Add,
                label = "Add widget",
                onClick = onAddClick,
                modifier = Modifier.testTag("today-add"),
            )
        }
        TodayPillButton(
            text = if (editing) "Done" else "Edit",
            label = if (editing) "Finish editing Today View" else "Edit Today View",
            onClick = { onEditingChange(!editing) },
            modifier = Modifier.testTag("today-edit"),
        )
    }
}

@Composable
private fun TodayEmptyState(onAddClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = currentDuoColors()
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = DuoTokens.space.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(DuoTokens.space.sm),
    ) {
        Text(
            text = "No widgets yet",
            style = DuoTokens.type.headline,
            color = colors.label1,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "Add a clock, your calendar, or the apps you use most.",
            style = DuoTokens.type.footnote,
            color = colors.label2,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        TodayPillButton(text = "Add widget", label = "Add widget", onClick = onAddClick)
    }
}

private const val DRAG_LIFT_SCALE = 1.04f

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

internal val PreviewTodayData = TodayWidgetData(
    clock = PreviewClockTime,
    calendar = CalendarFeedState.Events(today = LocalDate.parse("2026-09-15"), events = emptyList()),
    battery = BatteryReading(level = 72, charging = false),
    nowPlaying = NowPlayingState.Idle,
    suggestions = listOf("Messages", "Camera", "Maps", "Calendar", "Photos", "Notes", "Music", "Weather")
        .mapIndexed { index, label -> TodaySuggestion("com.example.app$index/.Main", label) },
)

internal val PreviewTodayItems = listOf(
    TodayItem(id = "clock", kind = TodayWidgetKind.CLOCK, size = TodayWidgetSize.SMALL),
    TodayItem(id = "battery", kind = TodayWidgetKind.BATTERIES, size = TodayWidgetSize.SMALL),
    TodayItem(id = "calendar", kind = TodayWidgetKind.CALENDAR, size = TodayWidgetSize.MEDIUM),
    TodayItem(id = "media", kind = TodayWidgetKind.NOW_PLAYING, size = TodayWidgetSize.MEDIUM),
    TodayItem(id = "apps", kind = TodayWidgetKind.SUGGESTIONS, size = TodayWidgetSize.LARGE),
)

@Preview(name = "Today View", widthDp = 420, heightDp = 900)
@Composable
private fun TodayViewPreview() {
    DuoTheme {
        TodayPreviewBackdrop {
            TodayView(items = PreviewTodayItems, data = PreviewTodayData, zone = ZoneId.of("UTC"))
        }
    }
}

@Preview(name = "Today View, editing", widthDp = 420, heightDp = 900)
@Composable
private fun TodayViewEditingPreview() {
    DuoTheme {
        TodayPreviewBackdrop {
            TodayView(items = PreviewTodayItems, data = PreviewTodayData, editing = true, zone = ZoneId.of("UTC"))
        }
    }
}

@Preview(name = "Today View, dark", widthDp = 420, heightDp = 900, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun TodayViewDarkPreview() {
    DuoTheme(dark = true) {
        TodayPreviewBackdrop {
            TodayView(items = PreviewTodayItems, data = PreviewTodayData, zone = ZoneId.of("UTC"))
        }
    }
}

@Preview(name = "Today View, empty", widthDp = 420, heightDp = 500)
@Composable
private fun TodayViewEmptyPreview() {
    DuoTheme {
        TodayPreviewBackdrop {
            TodayView(items = emptyList(), data = PreviewTodayData, zone = ZoneId.of("UTC"))
        }
    }
}

@Preview(name = "Today View, font scale 1.3", widthDp = 420, heightDp = 900, fontScale = 1.3f)
@Composable
private fun TodayViewFontScalePreview() {
    DuoTheme {
        TodayPreviewBackdrop {
            TodayView(items = PreviewTodayItems, data = PreviewTodayData, zone = ZoneId.of("UTC"))
        }
    }
}
