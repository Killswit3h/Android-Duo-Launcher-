@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.jake.duolauncher.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FindReplace
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.jake.duolauncher.ActionRow
import com.jake.duolauncher.CLOCK_WIDGET
import com.jake.duolauncher.DATE_WIDGET
import com.jake.duolauncher.DropTarget
import com.jake.duolauncher.EMPTY_WIDGET
import com.jake.duolauncher.DEFAULT_GRID
import com.jake.duolauncher.GridSpec
import com.jake.duolauncher.Glass
import com.jake.duolauncher.HomeDragState
import com.jake.duolauncher.INFO_WIDGET
import com.jake.duolauncher.Ink
import com.jake.duolauncher.NEEDS_BINDING_WIDGET
import com.jake.duolauncher.WidgetContentSize
import com.jake.duolauncher.WidgetController
import com.jake.duolauncher.WidgetPlacement
import com.jake.duolauncher.WidgetSpanConstraints
import com.jake.duolauncher.design.DuoTokens
import com.jake.duolauncher.design.GlassLevel
import com.jake.duolauncher.design.GlassSurface
import com.jake.duolauncher.design.currentDuoColors
import com.jake.duolauncher.dropRegion

@Composable
internal fun WidgetSlot(id: Int, slot: Int, controller: WidgetController, modifier: Modifier, onAdd: () -> Unit, fallback: @Composable () -> Unit) {
    var restoreMessage by remember(slot) { mutableStateOf<String?>(null) }
    BoxWithConstraints(modifier.clip(RoundedCornerShape(24.dp)).testTag("widget-slot-$slot")) {
        val displayedContentSize = WidgetContentSize(maxWidth.value, maxHeight.value)
        if (id == NEEDS_BINDING_WIDGET) {
            val restore = controller.restoreDescriptor(slot)
            val colors = currentDuoColors()
            GlassSurface(level = GlassLevel.WIDGET, shape = DuoTokens.radius.widget,
                modifier = Modifier.fillMaxSize().testTag("widget-restore-$slot")) {
                Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(restore?.title ?: "Saved widget", color = colors.label1, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                    Text(restore?.profileLabel ?: "Unavailable profile", color = colors.label2,
                        style = MaterialTheme.typography.bodySmall)
                    restoreMessage?.let { Text(it, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center) }
                    Row {
                        TextButton(onClick = {
                            if (!controller.rebindRestoredWidget(slot, contentSize = displayedContentSize))
                                restoreMessage = "That provider or profile isn’t available. Choose a replacement."
                        },
                            modifier = Modifier.testTag("widget-restore-reconnect-$slot")) { Text("Reconnect") }
                        TextButton(onClick = onAdd, modifier = Modifier.testTag("widget-restore-replace-$slot")) { Text("Replace") }
                    }
                }
            }
            return@BoxWithConstraints
        }
        val info = remember(id) { if (id >= 0) controller.manager.getAppWidgetInfo(id) else null }
        if (info == null) fallback()
        else {
            key(id) {
                AndroidView(factory = { context -> controller.host.createView(context, id, info) },
                    modifier = Modifier.fillMaxSize())
            }
        }
    }
}

internal fun widgetLabel(id: Int, controller: WidgetController) = when (id) {
    CLOCK_WIDGET -> "Clock"
    DATE_WIDGET -> "Date"
    INFO_WIDGET -> "Widget panel"
    EMPTY_WIDGET -> "Add widget"
    else -> controller.label(id)
}

@Composable
internal fun MovableWidget(id: Int, slot: Int, controller: WidgetController, drag: HomeDragState,
    target: DropTarget?, modifier: Modifier, page: Int, onAdd: () -> Unit) {
    val cell = DropTarget.Widget(slot)
    WidgetSlot(id, slot, controller, modifier.dropRegion(drag, cell, page = page, widgetId = id)
        .alpha(if (drag.source?.target == cell) .3f else 1f)
        .border(if (drag.active && target == cell) 2.dp else 0.dp,
            if (drag.active && target == cell) Color.White else Color.Transparent, RoundedCornerShape(24.dp))
        .semantics { onLongClick("Move or replace widget") { onAdd(); true } }, onAdd) {
        when (id) {
            CLOCK_WIDGET -> ClockCard(onAdd)
            DATE_WIDGET -> DateCard(onAdd)
            INFO_WIDGET -> if (slot % 3 == 2) ExpandedCard(onAdd) else GlassCard(onClick = onAdd) {
                val colors = currentDuoColors()
                Icon(Icons.Rounded.Widgets, null, tint = colors.label1, modifier = Modifier.size(28.dp))
                Text("Your widgets", style = DuoTokens.type.callout, color = colors.label1, maxLines = 1)
                Text("Tap to choose", style = DuoTokens.type.caption1, color = colors.label2)
            }
            else -> {
                val colors = currentDuoColors()
                GlassSurface(level = GlassLevel.WIDGET, shape = DuoTokens.radius.widget,
                    modifier = Modifier.fillMaxSize().clip(DuoTokens.radius.widget).clickable(onClick = onAdd)) {
                    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.Add, null, tint = colors.label1)
                        Text(if (id >= 0) "Widget unavailable" else "Add widget",
                            style = DuoTokens.type.caption1, color = colors.label1)
                    }
                }
            }
        }
    }
}

@Composable
internal fun WidgetActions(
    placement: WidgetPlacement,
    constraints: WidgetSpanConstraints?,
    canConfigure: Boolean,
    onConfigure: () -> Unit,
    isValid: (Int, Int) -> Boolean,
    onResize: (Int, Int) -> Unit,
    onStartResize: (Int, Int) -> Unit,
    onMoveToPage: (Int) -> Boolean,
    homePages: Int,
    /** FR-31: the span limits follow the layout's own grid, not a fixed 4x6. */
    grid: GridSpec = DEFAULT_GRID,
    onReplace: () -> Unit,
    onRemove: () -> Unit,
    onClose: () -> Unit,
) {
    val sheetMaxHeight = with(LocalDensity.current) {
        (LocalWindowInfo.current.containerSize.height * .88f).toDp()
    }
    var width by remember(placement.slot, placement.spanX) { mutableIntStateOf(placement.spanX) }
    var height by remember(placement.slot, placement.spanY) { mutableIntStateOf(placement.spanY) }
    val minWidth = constraints?.minimum?.width ?: 2
    val minHeight = constraints?.minimum?.height ?: 2
    val maxWidth = minOf(grid.columns - placement.column, constraints?.maximum?.width ?: grid.columns)
    val maxHeight = minOf(grid.rows - placement.row, constraints?.maximum?.height ?: grid.rows)
    val feasible = placement.page >= -1 && placement.row in 0 until grid.rows &&
        !(placement.id >= 0 && constraints == null) && minWidth <= maxWidth && minHeight <= maxHeight
    val valid = feasible && isValid(width, height)
    Column(Modifier.fillMaxWidth().heightIn(max = sheetMaxHeight).verticalScroll(rememberScrollState())
        .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Widget options", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close widget options") }
        }
        if (canConfigure) ActionRow(Icons.Rounded.Settings, "Widget settings", onConfigure,
            Modifier.testTag("widget-settings-${placement.slot}"))
        Text("Resize", style = MaterialTheme.typography.titleMedium)
        Button(enabled = feasible, onClick = { onStartResize(width, height) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Resize on Home") }
        if (!feasible) Text("Move this widget into the six-row grid before resizing.", color = MaterialTheme.colorScheme.error)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            repeat(homePages) { page -> TextButton(onClick = { onMoveToPage(page) },
                modifier = Modifier.testTag("widget-move-${placement.slot}-page-$page")) { Text("Move to page ${page + 1}") } }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Width", Modifier.weight(1f))
            IconButton(enabled = constraints?.canResizeHorizontally != false,
                onClick = { if (feasible) width = (width - 1).coerceAtLeast(minWidth) }) {
                Icon(Icons.Rounded.Remove, "Decrease widget width")
            }
            Text("$width columns", Modifier.width(88.dp), textAlign = TextAlign.Center)
            IconButton(enabled = constraints?.canResizeHorizontally != false,
                onClick = { if (feasible) width = (width + 1).coerceAtMost(maxWidth) }) {
                Icon(Icons.Rounded.Add, "Increase widget width")
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Height", Modifier.weight(1f))
            IconButton(enabled = constraints?.canResizeVertically != false,
                onClick = { if (feasible) height = (height - 1).coerceAtLeast(minHeight) }) {
                Icon(Icons.Rounded.Remove, "Decrease widget height")
            }
            Text("$height rows", Modifier.width(88.dp), textAlign = TextAlign.Center)
            IconButton(enabled = constraints?.canResizeVertically != false,
                onClick = { if (feasible) height = (height + 1).coerceAtMost(maxHeight) }) {
                Icon(Icons.Rounded.Add, "Increase widget height")
            }
        }
        Text("Sizes that overlap another item are ignored.", style = MaterialTheme.typography.bodySmall)
        if (!valid) Text("That size overlaps another item or extends beyond the page.", color = MaterialTheme.colorScheme.error)
        Button(enabled = valid, onClick = { onResize(width, height); onClose() },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Apply size") }
        ActionRow(Icons.Rounded.FindReplace, "Replace", onReplace)
        HorizontalDivider()
        ActionRow(Icons.Rounded.DeleteOutline, "Remove", onRemove, tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(12.dp))
    }
}
