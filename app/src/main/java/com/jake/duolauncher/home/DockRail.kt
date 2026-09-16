@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.jake.duolauncher.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.Color
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
import com.jake.duolauncher.FolderEntry
import com.jake.duolauncher.HomeDragState
import com.jake.duolauncher.badges.BadgeCount
import com.jake.duolauncher.design.DuoTokens
import com.jake.duolauncher.design.GlassLevel
import com.jake.duolauncher.design.GlassSurface
import com.jake.duolauncher.design.currentDuoColors
import com.jake.duolauncher.design.rememberMotionEnabled
import com.jake.duolauncher.dropRegion
import com.jake.duolauncher.toAndroidBounds
import kotlin.math.roundToInt

/**
 * The dock as a vertical rail of slots (FR-37 to FR-40).
 *
 * The rail draws [capacity] slots — 3 to 6 — rather than a fixed four, and each slot holds an app, a
 * pinned shortcut or a folder (FR-39). It does not decide which edge of the screen it is on: the
 * workspace aligns the glass rail that wraps this column, so that mirroring the dock to the left is
 * an alignment change rather than a second layout.
 *
 * Dropping onto a full dock is rejected by the workspace with [DOCK_FULL_MESSAGE], exactly as it
 * always has been; [dockAcceptsDrop] is the rule both sides share.
 */
@Composable
internal fun DockAppColumn(
    savedDock: List<String?>,
    previewDock: List<String?>,
    appsById: Map<String, AppEntry>,
    rowHeight: Float,
    iconSize: Float,
    drag: HomeDragState,
    target: DropTarget?,
    /** FR-38: 3 to 6 slots. */
    capacity: Int = DEFAULT_DOCK_SLOTS,
    /** FR-39: folders the dock may hold, so a folder in a slot draws as a folder. */
    folders: List<FolderEntry> = emptyList(),
    /** FR-45: Edit mode jiggles the rail's items and gives each one a − badge. */
    editing: Boolean = false,
    /** FR-46: clears the dock slot. Null hides the − badges. */
    onRemove: ((Int) -> Unit)? = null,
    onLaunch: (AppEntry, android.graphics.Rect?) -> Unit,
    onChoose: (Int) -> Unit,
    onFolder: (String) -> Unit = {},
) {
    val colors = currentDuoColors()
    val appearance = LocalHomeAppearance.current
    val badges = LocalBadges.current
    val motion = rememberMotionEnabled()
    val slots = dockSlots(savedDock, capacity)
    val preview = dockSlots(previewDock, capacity)
    val draggedId = drag.source?.appId
    val dockTarget = (target as? DropTarget.Dock)?.index
    val source = drag.source?.target as? DropTarget.Dock
    val draggedPreviewIndex = preview.indexOf(draggedId)
    val hiddenIndex = when {
        !drag.active || !drag.moved -> null
        dockTarget != null -> draggedPreviewIndex.takeIf { it >= 0 }
        source != null && target !is DropTarget.Home -> draggedPreviewIndex.takeIf { it >= 0 }
        else -> null
    }
    val dimDragged = drag.active && !drag.moved && source != null
    val launchBounds = remember(slots.size) { List(slots.size) { android.graphics.Rect() } }
    val interactions = remember(slots.size) { List(slots.size) { MutableInteractionSource() } }
    val slotScales = slots.indices.map { index ->
        val pressed by interactions[index].collectIsPressedAsState()
        val scale by animateFloatAsState(
            targetValue = pressScale(pressed),
            animationSpec = duoSpec(DuoTokens.motion.snappy(), motion),
            label = "dock press $index",
        )
        scale
    }
    val density = LocalDensity.current
    val rowHeightPx = with(density) { rowHeight.dp.toPx() }
    fun folderOf(id: String?) = id?.let { value -> folders.firstOrNull { it.id == value } }

    Box(Modifier.fillMaxWidth().height((rowHeight * slots.size).dp)) {
        slots.indices.forEach { index ->
            val cell = DropTarget.Dock(index)
            val savedId = slots[index]
            val savedApp = appsById[savedId]
            val savedFolder = folderOf(savedId)
            val previewId = preview.getOrNull(index)
            val highlighted = drag.active && target == cell
            val gap = hiddenIndex == index
            Box(
                Modifier.fillMaxWidth().height(rowHeight.dp).offset(y = (rowHeight * index).dp)
                    .background(
                        if (highlighted) colors.specular.copy(alpha = DROP_FILL) else Color.Transparent,
                        DuoTokens.radius.tile,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    gap -> SlotPlaceholder(iconSize, emphasized = true,
                        modifier = Modifier.testTag("drag-gap-dock-$index"))
                    previewId == null -> Icon(Icons.Rounded.Add, null, tint = colors.label2,
                        modifier = Modifier.size(24.dp))
                }
            }
            Box(
                Modifier.fillMaxWidth().height(rowHeight.dp).offset(y = (rowHeight * index).dp)
                    .testTag("dock-slot-$index").dropRegion(drag, cell, savedApp?.id ?: savedFolder?.id)
                    .semantics(mergeDescendants = true) {
                        contentDescription = savedApp?.label
                            ?: savedFolder?.let { "Folder ${it.title}, ${it.appIds.size} apps" }
                            ?: "Choose dock app ${index + 1}"
                    }
                    .combinedClickable(
                        interactionSource = interactions[index],
                        indication = LocalIndication.current,
                        role = Role.Button,
                        onClick = {
                            when {
                                savedApp != null -> onLaunch(savedApp, launchBounds[index])
                                savedFolder != null -> onFolder(savedFolder.id)
                                else -> onChoose(index)
                            }
                        },
                        onLongClick = null,
                    )
                    .semantics { onLongClick("Choose dock app") { onChoose(index); true } },
            )
        }

        val ids = (slots + preview).filterNotNull().distinct()
        ids.forEach { id ->
            val savedIndex = slots.indexOf(id)
            val previewIndex = preview.indexOf(id)
            val renderIndex = previewIndex.takeIf { it >= 0 } ?: savedIndex.takeIf { it >= 0 } ?: return@forEach
            val app = appsById[id]
            val folder = folderOf(id)
            if (app == null && folder == null) return@forEach
            key(id) {
                val animatedOffset by animateIntOffsetAsState(
                    IntOffset(0, (renderIndex * rowHeightPx).roundToInt()), label = "dock insertion $id")
                val visible = previewIndex >= 0 && renderIndex != hiddenIndex
                val opacity by animateFloatAsState(
                    if (!visible) 0f else if (dimDragged && id == draggedId) .28f else 1f,
                    label = "dock insertion visibility $id",
                )
                Box(
                    Modifier.offset { animatedOffset }.fillMaxWidth().height(rowHeight.dp).alpha(opacity)
                        .testTag("dock-app-$id"),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.jiggle(editing, id)) {
                        if (app != null) {
                            AppIcon(
                                app, iconSize,
                                Modifier.testTag("dock-icon-$id")
                                    .onGloballyPositioned {
                                        if (savedIndex >= 0) launchBounds[savedIndex].set(it.boundsInWindow().toAndroidBounds())
                                    }
                                    .graphicsLayer { scaleX = slotScales[renderIndex]; scaleY = slotScales[renderIndex] },
                            )
                            BadgeOverlay(badges[app.id] ?: BadgeCount.None,
                                appearance.iconSizeDp(iconSize), "badge-dock-${app.id}")
                        } else if (folder != null) {
                            DockFolderIcon(folder, appsById, iconSize, drag, Modifier.testTag("dock-icon-$id"))
                            val total = remember(folder.appIds, badges) {
                                BadgeCount.of(folder.appIds.distinct().sumOf { badges[it]?.count ?: 0 }.toLong())
                            }
                            BadgeOverlay(total, appearance.iconSizeDp(iconSize), "badge-dock-${folder.id}")
                        }
                        if (editing && onRemove != null && savedIndex >= 0) {
                            RemoveBadge(
                                kind = if (folder != null) RemovableKind.FOLDER else RemovableKind.APP,
                                label = app?.label ?: folder?.title.orEmpty(),
                                tag = "remove-dock-$savedIndex",
                                onRemove = { onRemove(savedIndex) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * A folder in a dock slot (FR-39).
 *
 * The same glass square the Home grid uses, without the label: dock rows are sized for a bare icon,
 * so a label here would either clip or push the rail's rows apart at a large font scale.
 */
@Composable
private fun DockFolderIcon(
    folder: FolderEntry,
    apps: Map<String, AppEntry>,
    size: Float,
    drag: HomeDragState,
    modifier: Modifier = Modifier,
) {
    val appearance = LocalHomeAppearance.current
    val tileSize = appearance.iconSizeDp(size).dp
    GlassSurface(
        level = GlassLevel.WIDGET,
        shape = DuoTokens.radius.iconRadiusFor(tileSize),
        modifier = modifier
            .size(tileSize)
            .dropRegion(drag, DropTarget.Folder(folder.id), folderId = folder.id)
            .testTag("dock-folder-drop-${folder.id}"),
    ) {
        folder.appIds.take(DOCK_FOLDER_PREVIEW_ICONS).forEachIndexed { index, id ->
            apps[id]?.let { app ->
                AppIcon(
                    app = app,
                    sizeDp = size * DOCK_FOLDER_ICON_FRACTION,
                    modifier = Modifier
                        .align(
                            when (index) {
                                0 -> Alignment.TopStart
                                1 -> Alignment.TopEnd
                                2 -> Alignment.BottomStart
                                else -> Alignment.BottomEnd
                            },
                        )
                        .padding(DuoTokens.space.xxs),
                )
            }
        }
    }
}

private const val DROP_FILL = 0.3f
private const val DOCK_FOLDER_PREVIEW_ICONS = 4
private const val DOCK_FOLDER_ICON_FRACTION = 0.38f
