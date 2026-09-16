package com.jake.duolauncher.home

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jake.duolauncher.AppEntry
import com.jake.duolauncher.DeviceStatus
import com.jake.duolauncher.DockSide
import com.jake.duolauncher.DuoTheme
import com.jake.duolauncher.FolderEntry
import com.jake.duolauncher.GridSpec
import com.jake.duolauncher.HomeDragState
import com.jake.duolauncher.design.BlurBackdrop
import com.jake.duolauncher.design.DuoSampleBackdrop
import com.jake.duolauncher.design.DuoSampleBackdropHighlight
import com.jake.duolauncher.design.DuoTokens
import com.jake.duolauncher.design.GlassLevel
import com.jake.duolauncher.design.GlassSurface
import com.jake.duolauncher.design.currentDuoColors
import com.jake.duolauncher.postures.DuoPosture
import com.jake.duolauncher.postures.FoldOrientation
import com.jake.duolauncher.postures.PostureRect

/**
 * A visual bench for the Home surface, in the shape `design/GlassGallery.kt` established.
 *
 * Nothing in the launcher uses these; they exist so the side rail on either edge, the corner status
 * cluster, a grid that is not 4x6, the fold gutter and Edit mode can be eyeballed in Android Studio
 * without a foldable, a widget host or a populated layout.
 *
 * The grid here is a stand-in rather than `SharedHomeGrid`: the real grid needs a live
 * `WidgetController` bound to an `AppWidgetHost`, which a preview has no way to provide. It uses the
 * same [hingeColumnGutter] arithmetic the real grid uses, so what the fold does to the columns is
 * the real behaviour and not a drawing of it.
 */

// Lint points at androidx.core's KTX `createBitmap`, but core-ktx is not a dependency of this
// module and taking one on for a preview fixture would not be a fair trade.
@Suppress("UseKtx")
private fun previewIcon(color: Int): Bitmap =
    Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

private fun previewApp(id: String, label: String, color: Int) =
    AppEntry(id = id, label = label, icon = previewIcon(color))

private val PREVIEW_APPS = listOf(
    previewApp("a/.M", "Messages", 0xFF5B8DEF.toInt()),
    previewApp("b/.M", "Camera", 0xFF3F4C7E.toInt()),
    previewApp("c/.M", "Maps", 0xFF57A773.toInt()),
    previewApp("d/.M", "Music", 0xFFE0715C.toInt()),
    previewApp("e/.M", "Notes", 0xFFE7B75C.toInt()),
    previewApp("f/.M", "Photos", 0xFF9C5F7A.toInt()),
    previewApp("g/.M", "Clock", 0xFF44576D.toInt()),
    previewApp("h/.M", "Weather", 0xFF6FA8C7.toInt()),
    previewApp("i/.M", "Files", 0xFF8E7B5B.toInt()),
    previewApp("j/.M", "Calendar", 0xFFC2564F.toInt()),
    previewApp("k/.M", "Wallet", 0xFF3D6B5E.toInt()),
    previewApp("l/.M", "Podcasts", 0xFF7D5BA6.toInt()),
)

private val PREVIEW_FOLDER = FolderEntry(
    id = "duo.folder.11111111-1111-1111-1111-111111111111",
    title = "Work",
    appIds = PREVIEW_APPS.take(4).map { it.id },
)

private val PREVIEW_STATUS = DeviceStatus(
    battery = 72,
    charging = false,
    wifiConnected = true,
    wifiLevel = 3,
    cellularLevel = 3,
)

/** A wallpaper stand-in, because glass over a flat colour shows nothing of what glass does. */
@Composable
private fun HomePreviewBackdrop(content: @Composable () -> Unit) {
    BlurBackdrop(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.linearGradient(DuoSampleBackdrop))
                .background(
                    Brush.radialGradient(
                        colors = listOf(DuoSampleBackdropHighlight, DuoSampleBackdropHighlight.copy(alpha = 0f)),
                        center = Offset.Zero,
                        radius = HIGHLIGHT_RADIUS,
                    ),
                ),
        )
        content()
    }
}

private const val HIGHLIGHT_RADIUS = 900f

// ---------------------------------------------------------------------------
// F8 — the side rail and the status cluster
// ---------------------------------------------------------------------------

/** The dock rail, its status cluster and its page indicator, all on [side] (FR-37, FR-41). */
@Composable
private fun RailPreview(side: DockSide, capacity: Int, status: DeviceStatus = PREVIEW_STATUS) {
    val colors = currentDuoColors()
    val drag = remember { HomeDragState() }
    val apps = remember { PREVIEW_APPS.associateBy { it.id } }
    val dock = (PREVIEW_APPS.take(capacity - 1).map { it.id } + PREVIEW_FOLDER.id).take(capacity)
    val padding = if (side == DockSide.RIGHT) PaddingValues(end = RAIL_EDGE) else PaddingValues(start = RAIL_EDGE)
    HomePreviewBackdrop {
        Box(Modifier.fillMaxSize()) {
            StatusCluster(
                status = status,
                modifier = Modifier.align(topRailAlignment(side)).padding(padding).padding(top = 12.dp),
            )
            Box(
                Modifier
                    .align(topRailAlignment(side))
                    .padding(padding)
                    .padding(top = 104.dp)
                    .width(68.dp)
                    .testTag("dock"),
            ) {
                GlassSurface(level = GlassLevel.BAR, shape = DuoTokens.radius.dock, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(vertical = DuoTokens.space.sm)) {
                        DockAppColumn(
                            savedDock = dock,
                            previewDock = dock,
                            appsById = apps,
                            rowHeight = 60f,
                            iconSize = 44f,
                            drag = drag,
                            target = null,
                            capacity = capacity,
                            folders = listOf(PREVIEW_FOLDER),
                            onLaunch = { _, _ -> },
                            onChoose = { },
                        )
                    }
                }
            }
            GlassSurface(
                level = GlassLevel.BAR,
                shape = CircleShape,
                modifier = Modifier.align(pageIndicatorAlignment(side)).padding(DuoTokens.space.lg),
            ) {
                Row(
                    Modifier.padding(horizontal = DuoTokens.space.md, vertical = DuoTokens.space.sm),
                    horizontalArrangement = Arrangement.spacedBy(DuoTokens.space.sm),
                ) {
                    repeat(3) { index ->
                        Box(
                            Modifier
                                .size(if (index == 0) 6.dp else 4.dp)
                                .background(
                                    if (index == 0) colors.label1 else colors.label3,
                                    CircleShape,
                                ),
                        )
                    }
                }
            }
        }
    }
}

@Preview(name = "Dock rail — right (default)", widthDp = 420, heightDp = 860)
@Composable
private fun DockRailRightPreview() {
    DuoTheme(dark = false) { RailPreview(DockSide.RIGHT, capacity = 4) }
}

@Preview(name = "Dock rail — left", widthDp = 420, heightDp = 860)
@Composable
private fun DockRailLeftPreview() {
    DuoTheme(dark = false) { RailPreview(DockSide.LEFT, capacity = 4) }
}

@Preview(name = "Dock rail — six slots, dark", widthDp = 420, heightDp = 860, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun DockRailSixPreview() {
    DuoTheme(dark = true) { RailPreview(DockSide.RIGHT, capacity = 6) }
}

@Preview(name = "Status cluster", widthDp = 200, heightDp = 200)
@Composable
private fun StatusClusterPreview() {
    DuoTheme(dark = false) {
        HomePreviewBackdrop {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                StatusCluster(PREVIEW_STATUS)
            }
        }
    }
}

/** FR-41: an unknown reading is never drawn as full signal, in the cluster as in the rail. */
@Preview(name = "Status cluster — unknown signals", widthDp = 200, heightDp = 200)
@Composable
private fun StatusClusterUnknownPreview() {
    DuoTheme(dark = false) {
        HomePreviewBackdrop {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                StatusCluster(DeviceStatus(battery = null, wifiConnected = true, wifiLevel = null, cellularLevel = null))
            }
        }
    }
}

@Preview(name = "Status cluster — font scale 1.3", widthDp = 200, heightDp = 200, fontScale = 1.3f)
@Composable
private fun StatusClusterFontScalePreview() {
    DuoTheme(dark = false) {
        HomePreviewBackdrop {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                StatusCluster(PREVIEW_STATUS)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// F9 — a grid that is not 4x6, and the fold
// ---------------------------------------------------------------------------

/**
 * A Home grid of [grid] tiles, positioned by exactly the arithmetic [SharedHomeGrid] uses, so that a
 * bigger grid and the fold gutter can be checked without a widget host.
 */
@Composable
private fun PreviewHomeGrid(
    grid: GridSpec,
    editing: Boolean,
    band: HingeBand?,
    modifier: Modifier = Modifier,
) {
    val apps = PREVIEW_APPS
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val gutter = hingeColumnGutter(0f, widthPx, grid.columns, band)
        val cellWidthPx = gutter?.cellWidthPx ?: (widthPx / grid.columns)
        val cellWidth = with(density) { cellWidthPx.toDp() }
        val rowHeight = with(density) { (maxHeight.toPx() / grid.rows).toDp() }
        repeat(grid.rows) { row ->
            repeat(grid.columns) { column ->
                val index = row * grid.columns + column
                val app = apps.getOrNull(index % apps.size) ?: return@repeat
                if (index >= apps.size) return@repeat
                val x = with(density) { (gutter?.columnLeftPx(column) ?: (column * cellWidthPx)).toDp() }
                Box(
                    Modifier.offset(x = x, y = rowHeight * row).width(cellWidth).height(rowHeight),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    AppTile(
                        app = app,
                        size = 56f,
                        editing = editing,
                        onRemove = if (editing) ({ }) else null,
                        onClick = { },
                        onLongClick = { },
                    )
                }
            }
        }
    }
}

@Preview(name = "Home grid — 6 by 6", widthDp = 560, heightDp = 860)
@Composable
private fun SixBySixGridPreview() {
    DuoTheme(dark = false) {
        HomePreviewBackdrop {
            PreviewHomeGrid(
                grid = GridSpec(6, 6),
                editing = false,
                band = null,
                modifier = Modifier.fillMaxSize().padding(DuoTokens.space.lg),
            )
        }
    }
}

@Preview(name = "Home grid — 8 by 8, dark", widthDp = 720, heightDp = 900, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun EightByEightGridPreview() {
    DuoTheme(dark = true) {
        HomePreviewBackdrop {
            PreviewHomeGrid(
                grid = GridSpec(8, 8),
                editing = false,
                band = null,
                modifier = Modifier.fillMaxSize().padding(DuoTokens.space.lg),
            )
        }
    }
}

/** FR-42: half-opened with a vertical fold — the columns open a gutter rather than sitting on it. */
@Preview(name = "Home grid — 6 by 6 across a fold", widthDp = 560, heightDp = 860)
@Composable
private fun FoldedGridPreview() {
    val density = LocalDensity.current.density
    val band = hingeBandOf(
        DuoPosture.HalfOpened(
            hingeBoundsPx = PostureRect((260 * density).toInt(), 0, (268 * density).toInt(), (860 * density).toInt()),
            orientation = FoldOrientation.VERTICAL,
        ),
        density = density,
    )
    DuoTheme(dark = false) {
        HomePreviewBackdrop {
            PreviewHomeGrid(
                grid = GridSpec(6, 6),
                editing = false,
                band = band,
                modifier = Modifier.fillMaxSize().padding(DuoTokens.space.lg),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// F10 — Edit mode
// ---------------------------------------------------------------------------

@Preview(name = "Edit mode", widthDp = 560, heightDp = 860)
@Composable
private fun EditModePreview() {
    DuoTheme(dark = false) {
        HomePreviewBackdrop {
            Box(Modifier.fillMaxSize()) {
                PreviewHomeGrid(
                    grid = GridSpec(6, 6),
                    editing = true,
                    band = null,
                    modifier = Modifier.fillMaxWidth().fillMaxHeight(0.8f).padding(DuoTokens.space.lg),
                )
                EditToolbar(
                    onAction = { },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(DuoTokens.space.lg),
                )
            }
        }
    }
}

@Preview(name = "Edit mode — toolbar at font scale 1.3", widthDp = 560, heightDp = 200, fontScale = 1.3f)
@Composable
private fun EditToolbarFontScalePreview() {
    DuoTheme(dark = false) {
        HomePreviewBackdrop {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EditToolbar(onAction = { })
            }
        }
    }
}

@Preview(name = "Page overview", widthDp = 560, heightDp = 520)
@Composable
private fun PageOverviewPreview() {
    val grid = GridSpec(4, 6)
    val summaries = listOf(
        HomePageSummary(page = 0, pageId = 0, items = 12, widgets = 1, hidden = false),
        HomePageSummary(page = 1, pageId = 1, items = 0, widgets = 0, hidden = true),
        HomePageSummary(page = 2, pageId = 2, items = 5, widgets = 0, hidden = false),
    )
    DuoTheme(dark = false) {
        HomePreviewBackdrop {
            HomePageOverview(
                summaries = summaries,
                grid = grid,
                occupancy = { page -> List(grid.cells) { (it + page) % 3 == 0 } },
                onMove = { _, _ -> },
                onToggleHidden = { },
                onDelete = { },
                onDismiss = { },
            )
        }
    }
}
