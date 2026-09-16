package com.jake.duolauncher.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jake.duolauncher.design.DuoTokens
import com.jake.duolauncher.design.GlassLevel
import com.jake.duolauncher.design.GlassSurface
import com.jake.duolauncher.design.LocalDuoTypography
import com.jake.duolauncher.design.duoColors

/**
 * FR-68's category presentation: a grid of glass group tiles, each showing a few large tappable
 * icons plus a 4-icon mini grid that opens the whole group.
 *
 * Every list rendered here comes from [CategoryGrouper], which has already applied the hidden-app
 * and locked-private exclusions (FR-75, FR-77) — no surface in this file filters for itself.
 */
@Composable
internal fun LibraryCategoriesView(
    groups: List<LibraryGroupContent>,
    icons: LibraryIcons,
    onLaunch: (LibraryApp, android.graphics.Rect?) -> Unit,
    onActions: (LibraryApp) -> Unit,
    onOpenGroup: (LibraryGroupContent) -> Unit,
    modifier: Modifier = Modifier,
    combined: Boolean = true,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(TILE_COLUMNS),
        modifier = modifier.testTag("library-categories"),
        horizontalArrangement = Arrangement.spacedBy(DuoTokens.space.md),
        verticalArrangement = Arrangement.spacedBy(DuoTokens.space.md),
        contentPadding = PaddingValues(bottom = DuoTokens.space.md),
    ) {
        items(groups, key = { it.group.name }) { content ->
            LibraryGroupTile(
                content = content,
                icons = icons,
                onLaunch = onLaunch,
                onActions = onActions,
                onOpenGroup = { onOpenGroup(content) },
                combined = combined,
            )
        }
    }
}

/**
 * One category tile.
 *
 * The mini grid opens the group; the large icons launch directly. That mirrors iOS and means the
 * two common actions — open this app, see everything in this category — are each one tap.
 */
@Composable
private fun LibraryGroupTile(
    content: LibraryGroupContent,
    icons: LibraryIcons,
    onLaunch: (LibraryApp, android.graphics.Rect?) -> Unit,
    onActions: (LibraryApp) -> Unit,
    onOpenGroup: () -> Unit,
    combined: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = duoColors()
    val type = LocalDuoTypography.current
    val tile = groupTileOf(content.apps)
    GlassSurface(
        level = GlassLevel.WIDGET,
        shape = DuoTokens.radius.card,
        modifier = modifier
            .fillMaxWidth()
            .testTag("library-group-${content.group.name}"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(DuoTokens.space.md),
            verticalArrangement = Arrangement.spacedBy(DuoTokens.space.sm),
        ) {
            Text(
                text = content.group.title,
                style = type.footnote,
                color = colors.label2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Two rows of two slots. Heights come from the icons, so a larger font scale grows the
            // title without ever squeezing or clipping the grid.
            val slots = buildList<@Composable () -> Unit> {
                tile.large.forEach { app ->
                    add {
                        LibraryAppCell(
                            id = app.id,
                            label = app.label,
                            icons = icons,
                            onClick = { bounds -> onLaunch(app, bounds) },
                            onActions = { onActions(app) },
                            combined = combined,
                            size = TILE_ICON_SIZE,
                            showLabel = false,
                        )
                    }
                }
                if (tile.opensGroup) {
                    add { GroupMiniGrid(content, tile, icons, onOpenGroup) }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(DuoTokens.space.sm)) {
                slots.chunked(TILE_COLUMNS).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(DuoTokens.space.sm),
                    ) {
                        row.forEach { slot ->
                            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { slot() }
                        }
                        repeat(TILE_COLUMNS - row.size) { Box(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

/**
 * The 4-icon mini grid (FR-68). One control, one TalkBack announcement — the four icons inside are
 * a picture of the group, not four separate targets.
 */
@Composable
private fun GroupMiniGrid(
    content: LibraryGroupContent,
    tile: GroupTile,
    icons: LibraryIcons,
    onOpenGroup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val remaining = tile.overflow
    val label = if (remaining > 0) {
        "Open ${content.group.title}, ${content.apps.size} apps, $remaining more"
    } else {
        "Open ${content.group.title}, ${content.apps.size} apps"
    }
    Box(
        modifier = modifier
            .size(TILE_ICON_SIZE)
            .clip(DuoTokens.radius.iconRadiusFor(TILE_ICON_SIZE))
            .clickable(onClick = onOpenGroup)
            .semantics { contentDescription = label }
            .testTag("library-group-open-${content.group.name}"),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(MINI_GRID_PADDING)
                .clearAndSetSemantics { },
            verticalArrangement = Arrangement.spacedBy(MINI_GRID_GAP),
        ) {
            tile.mini.chunked(MINI_COLUMNS).forEach { row ->
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(MINI_GRID_GAP),
                ) {
                    row.forEach { app ->
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            LibraryAppIcon(app.id, MINI_ICON_SIZE, icons)
                        }
                    }
                    repeat(MINI_COLUMNS - row.size) { Box(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/**
 * The full contents of one group, opened from a tile's mini grid (AC-55).
 *
 * Rendered as a panel over the library rather than as a new screen, so the way back is a single
 * dismiss and the library keeps its scroll position underneath.
 */
@Composable
internal fun LibraryGroupPanel(
    content: LibraryGroupContent,
    icons: LibraryIcons,
    onLaunch: (LibraryApp, android.graphics.Rect?) -> Unit,
    onActions: (LibraryApp) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    combined: Boolean = true,
    dragModifier: @Composable (LibraryApp) -> Modifier = { Modifier },
) {
    val colors = duoColors()
    val type = LocalDuoTypography.current
    GlassSurface(
        level = GlassLevel.PANEL,
        shape = DuoTokens.radius.sheet,
        modifier = modifier.testTag("library-group-panel"),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(DuoTokens.space.lg),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = content.group.title,
                    modifier = Modifier.weight(1f),
                    style = type.title3,
                    color = colors.label1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = onClose, modifier = Modifier.testTag("library-group-close")) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Close ${content.group.title}",
                        tint = colors.label1,
                    )
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(GROUP_CELL_WIDTH),
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(DuoTokens.space.sm),
                verticalArrangement = Arrangement.spacedBy(DuoTokens.space.md),
            ) {
                items(content.apps, key = { it.id }) { app ->
                    LibraryAppCell(
                        id = app.id,
                        label = app.label,
                        icons = icons,
                        onClick = { bounds -> onLaunch(app, bounds) },
                        onActions = { onActions(app) },
                        combined = combined,
                        modifier = dragModifier(app),
                    )
                }
            }
        }
    }
}

private const val TILE_COLUMNS = 2
private const val MINI_COLUMNS = 2

private val TILE_ICON_SIZE: Dp = 54.dp
private val MINI_ICON_SIZE: Dp = 22.dp
private val MINI_GRID_PADDING: Dp = 3.dp
private val MINI_GRID_GAP: Dp = 3.dp
private val GROUP_CELL_WIDTH: Dp = 76.dp
