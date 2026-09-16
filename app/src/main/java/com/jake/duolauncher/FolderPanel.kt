package com.jake.duolauncher

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jake.duolauncher.design.DuoTokens
import com.jake.duolauncher.design.GlassLevel
import com.jake.duolauncher.design.GlassSurface
import com.jake.duolauncher.design.currentDuoColors

@Composable
internal fun FolderPanel(
    folder: FolderEntry, apps: Map<String, AppEntry>, drag: HomeDragState, page: Int,
    homeDestinations: List<Int>, dockVacancies: List<Int>, onDismiss: () -> Unit,
    onRename: (String) -> Unit, onLaunch: (AppEntry, android.graphics.Rect?) -> Unit,
    onMoveOut: (String, DropTarget) -> Unit,
) {
    var title by rememberSaveable(folder.id) { mutableStateOf(folder.title) }
    BackHandler { onDismiss() }
    DisposableEffect(drag, folder.id) {
        drag.activeSourceScope = folder.id
        onDispose { if (drag.activeSourceScope == folder.id) drag.activeSourceScope = null }
    }
    val colors = currentDuoColors()
    Box(Modifier.fillMaxSize().background(colors.scrim.copy(alpha = .28f))
        .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClickLabel = "Close folder",
            onClick = onDismiss,
        )
        .imePadding().testTag("folder-panel"),
        contentAlignment = Alignment.Center) {
        // FR-2: the folder panel is a Liquid Glass surface over the shared blurred wallpaper.
        GlassSurface(level = GlassLevel.PANEL, shape = DuoTokens.radius.folder,
            modifier = Modifier.fillMaxWidth(.9f).fillMaxHeight(.82f).heightIn(min = 260.dp, max = 620.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .testTag("folder-panel-content")) {
            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(title, { title = it }, Modifier.weight(1f).testTag("folder-name"),
                        singleLine = true, label = { Text("Folder name") })
                    TextButton(onClick = { if (title.isNotBlank()) onRename(title); onDismiss() }) { Text("Done") }
                }
                LazyVerticalGrid(GridCells.Adaptive(88.dp), Modifier.fillMaxWidth().weight(1f).padding(top = 12.dp),
                    contentPadding = PaddingValues(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(folder.appIds, key = { it }) { appId ->
                        apps[appId]?.let { app -> FolderChild(app, folder.id, drag, page, homeDestinations, dockVacancies,
                            onLaunch = onLaunch, onMoveOut = onMoveOut) }
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderChild(
    app: AppEntry, folderId: String, drag: HomeDragState, page: Int,
    homeDestinations: List<Int>, dockVacancies: List<Int>,
    onLaunch: (AppEntry, android.graphics.Rect?) -> Unit, onMoveOut: (String, DropTarget) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxWidth().testTag("folder-child-${app.id}"),
        color = currentDuoColors().glassTint.copy(alpha = .34f),
        shape = RoundedCornerShape(18.dp)) {
        Box {
            Column(Modifier.fillMaxWidth().dropRegion(drag, DropTarget.Library(app.id), app.id, page,
                folderId = folderId, scope = folderId).clickable(enabled = app.available) { onLaunch(app, null) }
                .padding(horizontal = 6.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Image(app.icon.asImageBitmap(), null, Modifier.size(46.dp).clip(RoundedCornerShape(12.dp)))
                Text(app.label, Modifier.padding(top = 6.dp), maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium)
                if (app.isWork || !app.available) Text(if (app.available) app.profileLabel else "${app.profileLabel} unavailable",
                    maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
            }
            IconButton(onClick = { menu = true }, Modifier.align(Alignment.TopEnd).size(36.dp)
                .testTag("folder-options-${app.id}")) { Icon(Icons.Rounded.MoreVert, "Move ${app.label}") }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                homeDestinations.distinctBy(::homeCellPage).forEach { destination ->
                    val destinationPage = homeCellPage(destination)
                    val label = if (destinationPage == -1) "Move to Unfolded-only page" else "Move to page ${destinationPage + 1}"
                    DropdownMenuItem(text = { Text(label) }, onClick = {
                        menu = false; onMoveOut(app.id, DropTarget.Home(destination))
                    }, modifier = Modifier.testTag("folder-move-${app.id}-page-$destinationPage"))
                }
                dockVacancies.firstOrNull()?.let { dock ->
                    DropdownMenuItem(text = { Text("Move to dock") }, onClick = {
                        menu = false; onMoveOut(app.id, DropTarget.Dock(dock))
                    }, modifier = Modifier.testTag("folder-move-${app.id}-dock"))
                }
                DropdownMenuItem(text = { Text("Remove shortcut") }, onClick = {
                    menu = false; onMoveOut(app.id, DropTarget.Remove)
                }, modifier = Modifier.testTag("folder-remove-${app.id}"))
            }
        }
    }
}
