@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.jake.duolauncher.library

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jake.duolauncher.design.DuoTokens
import com.jake.duolauncher.design.LocalDuoTypography
import com.jake.duolauncher.design.duoColors
import com.jake.duolauncher.icons.IconRenderer
import com.jake.duolauncher.icons.IconStyle
import com.jake.duolauncher.icons.iconSizePx
import com.jake.duolauncher.toAndroidBounds

/**
 * How the App Library draws icons (FR-12, NFR-P5).
 *
 * [renderer] is the real pipeline; [fallbacks] are the bitmaps the catalog already holds, which is
 * what keeps the library drawing correctly before the icon renderer is wired in. Both may be
 * absent, in which case a neutral placeholder is drawn rather than a gap.
 */
@Stable
internal class LibraryIcons(
    val renderer: IconRenderer? = null,
    val style: IconStyle = IconStyle(),
    val fallbacks: Map<String, ImageBitmap> = emptyMap(),
)

/**
 * The icon for one app, taken from the renderer's **synchronous** cache on every frame and rendered
 * off-thread only on a miss (NFR-P5).
 *
 * This is what keeps a 300-app list flinging smoothly: a cache hit costs a map lookup, and a miss
 * neither blocks the frame nor re-requests work that is already in flight.
 */
@Composable
internal fun rememberLibraryIcon(id: String, sizeDp: Dp, icons: LibraryIcons): ImageBitmap? {
    val fallback = icons.fallbacks[id]
    val renderer = icons.renderer ?: return fallback
    val density = LocalDensity.current.density
    val sizePx = remember(sizeDp, density) { iconSizePx(sizeDp.value, density) }
    // The sync path. Called on every recomposition on purpose: on a hit it is a map lookup.
    val cached = renderer.request(id, sizePx, icons.style)
    var loaded by remember(id, sizePx, icons.style) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(id, sizePx, icons.style, renderer) {
        if (renderer.request(id, sizePx, icons.style) == null) {
            loaded = runCatching { renderer.load(id, sizePx, icons.style) }.getOrNull()
        }
    }
    return cached ?: loaded ?: fallback
}

/**
 * One app icon.
 *
 * Deliberately decorative: the row or cell around it supplies the app's name, so TalkBack announces
 * each app once. A neutral tile stands in until a render arrives, so a fling never leaves holes and
 * never flashes a high-contrast placeholder.
 */
@Composable
internal fun LibraryAppIcon(
    id: String,
    size: Dp,
    icons: LibraryIcons,
    modifier: Modifier = Modifier,
    onBounds: ((android.graphics.Rect) -> Unit)? = null,
) {
    val colors = duoColors()
    val shape = DuoTokens.radius.iconRadiusFor(size)
    val bitmap = rememberLibraryIcon(id, size, icons)
    val box = modifier
        .size(size)
        .then(
            if (onBounds != null) {
                Modifier.onGloballyPositioned { onBounds(it.boundsInWindow().toAndroidBounds()) }
            } else {
                Modifier
            },
        )
        .clip(shape)
    if (bitmap != null) {
        Image(bitmap = bitmap, contentDescription = null, modifier = box)
    } else {
        Box(box.background(colors.label3.copy(alpha = PLACEHOLDER_ALPHA)))
    }
}

/**
 * A full-width app row: the App Library's A–Z and search presentation, and the pin picker's.
 *
 * The 60dp minimum height is a touch-target floor, not decoration, and it is asserted by
 * `LauncherIntegrationTest`.
 */
@Composable
internal fun LibraryAppRow(
    app: LibraryApp,
    icons: LibraryIcons,
    onClick: (android.graphics.Rect?) -> Unit,
    onActions: () -> Unit,
    modifier: Modifier = Modifier,
    combined: Boolean = true,
    trailing: @Composable (() -> Unit)? = null,
) {
    val colors = duoColors()
    val type = LocalDuoTypography.current
    val bounds = remember(app.id) { android.graphics.Rect() }
    val click = { onClick(bounds) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = ROW_MIN_HEIGHT)
            .clip(DuoTokens.radius.tile)
            .testTag("library-app-${app.id}")
            .then(
                if (combined) {
                    Modifier.combinedClickable(onClick = click, onLongClick = onActions)
                } else {
                    // While a drag controller is attached, a long press belongs to the drag, so the
                    // options gesture is exposed to accessibility services instead of to touch.
                    Modifier
                        .clickable(onClick = click)
                        .semantics { onLongClick("App options") { onActions(); true } }
                },
            )
            .padding(vertical = DuoTokens.space.sm, horizontal = DuoTokens.space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LibraryAppIcon(app.id, ROW_ICON_SIZE, icons, onBounds = bounds::set)
        Text(
            text = app.label,
            modifier = Modifier
                .weight(1f)
                .padding(start = DuoTokens.space.md),
            style = type.body,
            color = colors.label1,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        trailing?.invoke()
    }
}

/** An icon-over-label cell, used by the group tiles, the expanded group and the private container. */
@Composable
internal fun LibraryAppCell(
    id: String,
    label: String,
    icons: LibraryIcons,
    onClick: (android.graphics.Rect?) -> Unit,
    modifier: Modifier = Modifier,
    onActions: (() -> Unit)? = null,
    combined: Boolean = true,
    size: Dp = CELL_ICON_SIZE,
    showLabel: Boolean = true,
) {
    val colors = duoColors()
    val type = LocalDuoTypography.current
    val bounds = remember(id) { android.graphics.Rect() }
    val click = { onClick(bounds) }
    Column(
        modifier = modifier
            .clip(DuoTokens.radius.tile)
            .then(
                when {
                    onActions == null -> Modifier.clickable(onClick = click)
                    combined -> Modifier.combinedClickable(onClick = click, onLongClick = onActions)
                    else -> Modifier
                        .clickable(onClick = click)
                        .semantics { onLongClick("App options") { onActions(); true } }
                },
            )
            .semantics(mergeDescendants = true) { }
            .padding(DuoTokens.space.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LibraryAppIcon(id, size, icons, onBounds = bounds::set)
        if (showLabel) {
            Text(
                text = label,
                modifier = Modifier.padding(top = DuoTokens.space.xs),
                style = type.caption2,
                color = colors.label2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** The App Library's flat-list touch target, asserted by the instrumented suite. */
internal val ROW_MIN_HEIGHT: Dp = 60.dp

private val ROW_ICON_SIZE: Dp = 44.dp
internal val CELL_ICON_SIZE: Dp = 52.dp

private const val PLACEHOLDER_ALPHA = 0.25f
