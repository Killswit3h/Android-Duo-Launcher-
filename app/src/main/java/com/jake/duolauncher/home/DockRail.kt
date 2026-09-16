@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.jake.duolauncher.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.jake.duolauncher.AppEntry
import com.jake.duolauncher.DropTarget
import com.jake.duolauncher.Glass
import com.jake.duolauncher.HomeDragState
import com.jake.duolauncher.dropRegion
import com.jake.duolauncher.toAndroidBounds
import kotlin.math.roundToInt

@Composable
internal fun DockAppColumn(
    savedDock: List<String?>,
    previewDock: List<String?>,
    appsById: Map<String, AppEntry>,
    rowHeight: Float,
    iconSize: Float,
    drag: HomeDragState,
    target: DropTarget?,
    onLaunch: (AppEntry, android.graphics.Rect?) -> Unit,
    onChoose: (Int) -> Unit,
) {
    val draggedId = drag.source?.appId
    val dockTarget = (target as? DropTarget.Dock)?.index
    val source = drag.source?.target as? DropTarget.Dock
    val draggedPreviewIndex = previewDock.indexOf(draggedId)
    val hiddenIndex = when {
        !drag.active || !drag.moved -> null
        dockTarget != null -> draggedPreviewIndex.takeIf { it >= 0 }
        source != null && target !is DropTarget.Home -> draggedPreviewIndex.takeIf { it >= 0 }
        else -> null
    }
    val dimDragged = drag.active && !drag.moved && source != null
    val launchBounds = remember(savedDock.size) { List(savedDock.size) { android.graphics.Rect() } }
    val interactions = remember(savedDock.size) { List(savedDock.size) { MutableInteractionSource() } }
    val slotScales = savedDock.indices.map { index ->
        val pressed by interactions[index].collectIsPressedAsState()
        val scale by animateFloatAsState(if (pressed) .92f else 1f, label = "dock press $index")
        scale
    }
    val density = LocalDensity.current
    val rowHeightPx = with(density) { rowHeight.dp.toPx() }
    Box(Modifier.fillMaxWidth().height((rowHeight * savedDock.size).dp)) {
        savedDock.indices.forEach { index ->
            val cell = DropTarget.Dock(index)
            val savedApp = appsById[savedDock[index]]
            val previewId = previewDock.getOrNull(index)
            val highlighted = drag.active && target == cell
            val gap = hiddenIndex == index
            Box(Modifier.fillMaxWidth().height(rowHeight.dp).offset(y = (rowHeight * index).dp)
                .background(if (highlighted) Color.White.copy(alpha = .3f) else Color.Transparent, RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center) {
                when {
                    gap -> Box(Modifier.size(iconSize.dp).testTag("drag-gap-dock-$index")
                        .background(Glass.copy(alpha = .16f), RoundedCornerShape(14.dp))
                        .border(2.dp, Color.White.copy(alpha = .55f), RoundedCornerShape(14.dp)))
                    previewId == null -> Icon(Icons.Rounded.Add, null, tint = Color.White, modifier = Modifier.size(24.dp))
                }
            }
            Box(Modifier.fillMaxWidth().height(rowHeight.dp).offset(y = (rowHeight * index).dp)
                .testTag("dock-slot-$index").dropRegion(drag, cell, savedApp?.id)
                .semantics(mergeDescendants = true) { contentDescription = savedApp?.label ?: "Choose dock app ${index + 1}" }
                .combinedClickable(interactionSource = interactions[index], indication = LocalIndication.current, role = Role.Button, onClick = {
                    if (savedApp != null) onLaunch(savedApp, launchBounds[index]) else onChoose(index)
                }, onLongClick = null)
                .semantics { onLongClick("Choose dock app") { onChoose(index); true } })
        }

        val ids = (savedDock + previewDock).filterNotNull().distinct()
        ids.forEach { id ->
            val savedIndex = savedDock.indexOf(id)
            val previewIndex = previewDock.indexOf(id)
            val renderIndex = previewIndex.takeIf { it >= 0 } ?: savedIndex.takeIf { it >= 0 } ?: return@forEach
            val app = appsById[id] ?: return@forEach
            key(id) {
                val animatedOffset by animateIntOffsetAsState(
                    IntOffset(0, (renderIndex * rowHeightPx).roundToInt()), label = "dock insertion $id")
                val visible = previewIndex >= 0 && renderIndex != hiddenIndex
                val opacity by animateFloatAsState(
                    if (!visible) 0f else if (dimDragged && id == draggedId) .28f else 1f,
                    label = "dock insertion visibility $id",
                )
                Box(Modifier.offset { animatedOffset }.fillMaxWidth().height(rowHeight.dp).alpha(opacity)
                    .testTag("dock-app-$id"), contentAlignment = Alignment.Center) {
                    Image(app.icon.asImageBitmap(), null, Modifier.size(iconSize.dp).testTag("dock-icon-$id")
                        .onGloballyPositioned { if (savedIndex >= 0) launchBounds[savedIndex].set(it.boundsInWindow().toAndroidBounds()) }
                        .graphicsLayer { scaleX = slotScales[renderIndex]; scaleY = slotScales[renderIndex] }
                        .clip(RoundedCornerShape(11.dp)))
                }
            }
        }
    }
}
