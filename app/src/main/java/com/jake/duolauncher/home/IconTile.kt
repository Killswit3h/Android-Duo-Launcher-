package com.jake.duolauncher.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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

/**
 * One app icon, rendered by the icon pipeline (FR-12, FR-14 to FR-19).
 *
 * The pipeline owns appearance, tint and shape, so nothing here masks or recolors the result: the
 * bitmap that arrives is already the squircle, circle or scallop the user chose. Until the first
 * render lands, the catalog's own bitmap stands in, clipped to the icon corner, so a cold page
 * never shows an empty square.
 */
@Composable
internal fun AppIcon(app: AppEntry, sizeDp: Float, modifier: Modifier = Modifier) {
    val appearance = LocalHomeAppearance.current
    val colors = currentDuoColors()
    val style = appearance.resolvedIconStyle(colors)
    val rendered = rememberAppIcon(app.id, sizeDp, style, appearance.largeIcons)
    val size = appearance.iconSizeDp(sizeDp).dp
    if (rendered != null) {
        Image(rendered, null, modifier.size(size))
    } else {
        Image(
            app.icon.asImageBitmap(),
            null,
            modifier.size(size).clip(DuoTokens.radius.iconRadiusFor(size)),
        )
    }
}

/**
 * A notification badge on an icon (FR-20, FR-22).
 *
 * Dot and Number are the same badge at two sizes: the dot is what is left when there is no number
 * to show. Both take the accent so the badge belongs to the user's theme rather than introducing a
 * color of its own.
 */
@Composable
internal fun BoxScope.BadgeOverlay(count: BadgeCount, iconSizeDp: Float, tag: String) {
    val appearance = LocalHomeAppearance.current
    if (!showsBadge(appearance.badgesEnabled, count)) return
    val colors = currentDuoColors()
    val description = badgeDescription(count.count)
    val dot = (iconSizeDp * DOT_FRACTION).dp.coerceAtLeast(MIN_DOT)
    val text = badgeText(count.count).takeIf { appearance.badgeStyle == BadgeStyle.NUMBER }
    Box(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .offset(x = DuoTokens.space.xs, y = -DuoTokens.space.xxs)
            .testTag(tag)
            .semantics { if (description != null) contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        if (text == null) {
            Box(Modifier.size(dot).background(colors.accent, CircleShape))
        } else {
            Box(
                modifier = Modifier
                    .sizeIn(minWidth = NUMBER_MIN, minHeight = NUMBER_MIN)
                    .background(colors.accent, CircleShape)
                    .padding(horizontal = DuoTokens.space.xs),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = text,
                    style = DuoTokens.type.caption2,
                    color = onAccentLabel(colors),
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * A Home or Library app tile: icon, optional label, badge, and the press spring (FR-10).
 *
 * Long-press here only *reports* the press. Picking an item up for a drag is owned by the root's
 * pointer handler (`homeDragInput`), which is why this tile must never claim the gesture: a
 * long-press that turns into a drag has to reach the root untouched.
 */
@Composable
internal fun AppTile(
    app: AppEntry,
    size: Float,
    modifier: Modifier = Modifier,
    /** FR-45: Edit mode makes the tile jiggle and grows a − badge. */
    editing: Boolean = false,
    /** FR-46: null when this placement cannot be removed, which hides the − badge. */
    onRemove: (() -> Unit)? = null,
    onClick: (android.graphics.Rect) -> Unit,
    onLongClick: () -> Unit,
) {
    val appearance = LocalHomeAppearance.current
    val colors = currentDuoColors()
    val motion = rememberMotionEnabled()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = pressScale(pressed),
        animationSpec = duoSpec(DuoTokens.motion.snappy(), motion),
        label = "app press",
    )
    val bounds = remember { android.graphics.Rect() }
    val badge = LocalBadges.current[app.id] ?: BadgeCount.None
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = MIN_TOUCH_TARGET)
            .semantics(mergeDescendants = true) { contentDescription = app.label }
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = { onClick(bounds) },
            )
            .semantics { onLongClick("App options") { onLongClick(); true } }
            .padding(horizontal = DuoTokens.space.xxs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.jiggle(editing, app.id)) {
            AppIcon(
                app = app,
                sizeDp = size,
                modifier = Modifier
                    .onGloballyPositioned { bounds.set(it.boundsInWindow().toAndroidBounds()) }
                    .graphicsLayer { scaleX = scale; scaleY = scale },
            )
            BadgeOverlay(badge, appearance.iconSizeDp(size), "badge-${app.id}")
            if (editing && onRemove != null) {
                RemoveBadge(RemovableKind.APP, app.label, "remove-${app.id}", onRemove)
            }
        }
        if (appearance.showLabels) {
            Text(
                text = app.label,
                style = DuoTokens.type.iconLabel.copy(
                    shadow = Shadow(colors.scrim.copy(alpha = LABEL_SHADOW), Offset.Zero, LABEL_BLUR),
                ),
                color = colors.specular,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = DuoTokens.space.xs).clearAndSetSemantics { },
            )
        }
    }
}

/**
 * A folder tile: a glass square holding the first four icons, with the folder's aggregated badge
 * (FR-22).
 */
@Composable
internal fun FolderTile(
    folder: FolderEntry,
    apps: Map<String, AppEntry>,
    size: Float,
    drag: HomeDragState,
    page: Int,
    modifier: Modifier = Modifier,
    /** FR-45. */
    editing: Boolean = false,
    /** FR-46: removing a folder from Home; null hides the − badge. */
    onRemove: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val appearance = LocalHomeAppearance.current
    val colors = currentDuoColors()
    val badges = LocalBadges.current
    val badge = remember(folder.appIds, badges) {
        val total = folder.appIds.distinct().sumOf { badges[it]?.count ?: 0 }
        BadgeCount.of(total.toLong())
    }
    val tileSize = appearance.iconSizeDp(size).dp
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = "Folder ${folder.title}, ${folder.appIds.size} apps"
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.jiggle(editing, folder.id)) {
            GlassSurface(
                level = GlassLevel.WIDGET,
                shape = DuoTokens.radius.iconRadiusFor(tileSize),
                modifier = Modifier
                    .size(tileSize)
                    .dropRegion(drag, DropTarget.Folder(folder.id), page = page, folderId = folder.id)
                    .testTag("folder-drop-${folder.id}"),
            ) {
                folder.appIds.take(FOLDER_PREVIEW_ICONS).forEachIndexed { index, id ->
                    apps[id]?.let { app ->
                        AppIcon(
                            app = app,
                            sizeDp = size * FOLDER_ICON_FRACTION,
                            modifier = Modifier
                                .align(
                                    when (index) {
                                        0 -> Alignment.TopStart
                                        1 -> Alignment.TopEnd
                                        2 -> Alignment.BottomStart
                                        else -> Alignment.BottomEnd
                                    },
                                )
                                .padding(DuoTokens.space.xs),
                        )
                    }
                }
            }
            BadgeOverlay(badge, appearance.iconSizeDp(size), "badge-folder-${folder.id}")
            if (editing && onRemove != null) {
                RemoveBadge(RemovableKind.FOLDER, folder.title, "remove-folder-${folder.id}", onRemove)
            }
        }
        if (appearance.showLabels) {
            Text(
                text = folder.title,
                style = DuoTokens.type.iconLabel.copy(
                    shadow = Shadow(colors.scrim.copy(alpha = LABEL_SHADOW), Offset.Zero, LABEL_BLUR),
                ),
                color = colors.specular,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = DuoTokens.space.xs).clearAndSetSemantics { },
            )
        }
    }
}

/** An empty or gap cell marker, as glass rather than a white outline. */
@Composable
internal fun SlotPlaceholder(size: Float, emphasized: Boolean, modifier: Modifier = Modifier) {
    val colors = currentDuoColors()
    Box(
        modifier
            .size(size.dp)
            .background(
                colors.glassTint.copy(alpha = if (emphasized) GAP_ALPHA else EMPTY_ALPHA),
                DuoTokens.radius.iconRadiusFor(size.dp),
            ),
    )
}

private const val FOLDER_PREVIEW_ICONS = 4
private const val FOLDER_ICON_FRACTION = 0.38f
private const val DOT_FRACTION = 0.18f
private const val LABEL_SHADOW = 0.55f
private const val LABEL_BLUR = 3f
private const val GAP_ALPHA = 0.28f
private const val EMPTY_ALPHA = 0.12f
private val MIN_DOT = 8.dp
private val NUMBER_MIN = 18.dp
private val MIN_TOUCH_TARGET = 48.dp
