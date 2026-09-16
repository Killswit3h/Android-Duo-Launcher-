package com.jake.duolauncher.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.jake.duolauncher.AppEntry
import com.jake.duolauncher.toAndroidBounds
import com.jake.duolauncher.design.DuoTokens
import com.jake.duolauncher.design.GlassLevel
import com.jake.duolauncher.design.GlassSurface
import com.jake.duolauncher.design.currentDuoColors
import com.jake.duolauncher.design.rememberMotionEnabled
import com.jake.duolauncher.shortcuts.DuoShortcut
import com.jake.duolauncher.shortcuts.DuoPinRequests
import com.jake.duolauncher.shortcuts.DuoShortcuts
import com.jake.duolauncher.shortcuts.PinItemKind
import com.jake.duolauncher.shortcuts.PinnedItem
import com.jake.duolauncher.shortcuts.SET_DEFAULT_HOME_ROW
import com.jake.duolauncher.shortcuts.SHORTCUT_MENU_LIMIT
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The actions the context menu can offer, in the order FR-25 lists them.
 *
 * `Add to Home` and `Create folder` are not in FR-25's list but are kept because they are the only
 * route to those capabilities that exists today; edit mode (F10) and create-by-drop (F16) will
 * supersede them.
 */
enum class ContextMenuAction(val label: String) {
    EDIT_HOME("Edit Home Screen"),
    EDIT_ICON("Edit icon"),
    APP_INFO("App info"),
    CREATE_FOLDER("Create folder"),
    WIDGETS("Widgets"),
    HIDE_APP("Hide app"),
    ADD_TO_HOME("Add to Home"),
    REMOVE_FROM_HOME("Remove from Home"),
    UNINSTALL("Uninstall"),
}

/** What the launcher can currently do with the app the menu was opened on. */
data class ContextMenuCapabilities(
    /** The app occupies a Home cell or a dock slot. */
    val placed: Boolean = false,
    val canEditHome: Boolean = false,
    val canEditIcon: Boolean = false,
    val canCreateFolder: Boolean = true,
    val hasWidgets: Boolean = false,
    val canHide: Boolean = false,
    /** FR-29: false for system apps and for Duo itself, in which case the row is never shown. */
    val canUninstall: Boolean = false,
)

/**
 * The menu's action rows for [capabilities] (FR-25). Pure, so the "only when applicable" rules are
 * unit tested rather than eyeballed.
 *
 * Destructive actions sort last, and `Remove from Home` and `Add to Home` are mutually exclusive:
 * an app is either placed or it is not.
 */
fun contextMenuActions(capabilities: ContextMenuCapabilities): List<ContextMenuAction> =
    buildList {
        if (capabilities.canEditHome) add(ContextMenuAction.EDIT_HOME)
        if (capabilities.canEditIcon) add(ContextMenuAction.EDIT_ICON)
        add(ContextMenuAction.APP_INFO)
        if (capabilities.canCreateFolder) add(ContextMenuAction.CREATE_FOLDER)
        if (capabilities.hasWidgets) add(ContextMenuAction.WIDGETS)
        if (capabilities.canHide) add(ContextMenuAction.HIDE_APP)
        if (capabilities.placed) add(ContextMenuAction.REMOVE_FROM_HOME) else add(ContextMenuAction.ADD_TO_HOME)
        if (capabilities.canUninstall) add(ContextMenuAction.UNINSTALL)
    }

/** Whether an action is destructive, and therefore drawn in the accent-free danger treatment. */
fun isDestructive(action: ContextMenuAction): Boolean =
    action == ContextMenuAction.UNINSTALL || action == ContextMenuAction.REMOVE_FROM_HOME

/**
 * Where a menu of [menuSize] sits relative to the icon at [anchor], inside [container] (FR-24).
 *
 * The menu prefers to hang below the icon, flips above it when there is no room, and is always
 * clamped inside the container with [margin] to spare, so an icon in a corner never pushes the menu
 * off screen. Pure, so it is unit tested.
 *
 * [exclusion] is the fold band to stay clear of while the device is half-opened (FR-42), in the same
 * coordinates as [anchor]. When the clamped menu would straddle the fold it slides to whichever side
 * of the band has room for it, preferring the side it already overlaps most; when neither side can
 * hold it — a menu wider than half the window — it stays put, because a menu pushed off screen is
 * worse than a menu crossing the hinge.
 */
fun contextMenuOffset(
    anchor: Rect,
    menuSize: IntSize,
    container: IntSize,
    gap: Float,
    margin: Float,
    exclusion: Rect? = null,
): IntOffset {
    val below = anchor.bottom + gap
    val above = anchor.top - gap - menuSize.height
    val y = when {
        below + menuSize.height <= container.height - margin -> below
        above >= margin -> above
        else -> (container.height - menuSize.height) / 2f
    }
    val centered = anchor.center.x - menuSize.width / 2f
    val maxX = (container.width - menuSize.width - margin).coerceAtLeast(margin)
    val x = avoidExclusion(centered.coerceIn(margin, maxX), menuSize.width.toFloat(), container, margin, exclusion)
    return IntOffset(x.roundToInt(), y.coerceAtLeast(margin).roundToInt())
}

/** [x] moved off [exclusion] if it can be, keeping the menu inside the container. */
private fun avoidExclusion(x: Float, width: Float, container: IntSize, margin: Float, exclusion: Rect?): Float {
    if (exclusion == null || exclusion.width <= 0f) return x
    if (x + width <= exclusion.left || x >= exclusion.right) return x
    val leftCandidate = exclusion.left - width
    val rightCandidate = exclusion.right
    val leftFits = leftCandidate >= margin
    val rightFits = rightCandidate + width <= container.width - margin
    return when {
        leftFits && rightFits -> if (x + width / 2f <= exclusion.center.x) leftCandidate else rightCandidate
        leftFits -> leftCandidate
        rightFits -> rightCandidate
        else -> x
    }
}

/**
 * The Liquid Glass context menu (FR-24 to FR-27, FR-30).
 *
 * Opened by a long-press that is *released* without moving. The long-press-and-drag path is
 * untouched: pickup is still decided by the root's `homeDragInput`, and this menu is only ever
 * shown by `finishDrag` when the gesture ended without movement, so a real drag never opens it.
 *
 * Home is blurred and dimmed behind the menu by the caller; the pressed icon is redrawn here,
 * unblurred, so it stays the anchor the user is looking at.
 */
@Composable
internal fun HomeContextMenu(
    app: AppEntry,
    anchor: Rect?,
    iconSize: Float,
    isDefaultHome: Boolean,
    capabilities: ContextMenuCapabilities,
    onDismiss: () -> Unit,
    onAction: (ContextMenuAction) -> Unit,
    onMakeDefault: () -> Unit,
) {
    val colors = currentDuoColors()
    val context = LocalContext.current
    val density = LocalDensity.current
    val motion = rememberMotionEnabled()
    val scope = rememberCoroutineScope()
    val repository = remember(context) { DuoShortcuts.repository(context) }

    var shortcuts by remember(app.id) { mutableStateOf<List<DuoShortcut>>(emptyList()) }
    var hostPermission by remember(app.id) { mutableStateOf(isDefaultHome) }
    LaunchedEffect(app.id) {
        hostPermission = repository.refreshHostPermission()
        shortcuts = if (hostPermission) repository.shortcutsFor(app.id, SHORTCUT_MENU_LIMIT) else emptyList()
    }

    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val progress by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = duoSpec(DuoTokens.motion.standard(), motion),
        label = "context menu",
    )

    var menuSize by remember { mutableStateOf(IntSize.Zero) }
    var container by remember { mutableStateOf(IntSize.Zero) }
    val gap = with(density) { DuoTokens.space.sm.toPx() }
    val margin = with(density) { DuoTokens.space.lg.toPx() }

    /** A shortcut dragged out of the menu is pinned to Home (FR-27). */
    fun pinShortcut(shortcut: DuoShortcut) {
        scope.launch {
            if (repository.pin(shortcut)) {
                DuoPinRequests.place(
                    PinnedItem(
                        kind = PinItemKind.SHORTCUT,
                        shortcut = shortcut.key,
                        label = shortcut.label,
                    ),
                )
            }
            onDismiss()
        }
    }

    // The anchor arrives in root coordinates from the drag layer, but this overlay is nested inside
    // the workspace's safe-drawing padding. Without this translation the menu and the redrawn icon
    // would both sit low by the height of the status bar inset.
    var origin by remember { mutableStateOf(Offset.Zero) }
    val localAnchor = anchor?.translate(-origin.x, -origin.y)

    BackHandler { onDismiss() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.boundsInRoot().topLeft }
            .onSizeChanged { container = it }
            .testTag("app-context-menu-scrim")
            .background(colors.scrim.copy(alpha = SCRIM_ALPHA * progress))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = "Close app options",
                onClick = onDismiss,
            ),
    ) {
        // The icon the menu belongs to, redrawn crisp above the blurred Home behind it.
        if (localAnchor != null) {
            Box(
                Modifier
                    .offset { IntOffset(localAnchor.left.roundToInt(), localAnchor.top.roundToInt()) }
                    .size(with(density) { localAnchor.width.toDp() }, with(density) { localAnchor.height.toDp() }),
                contentAlignment = Alignment.TopCenter,
            ) {
                AppIcon(app, iconSize, Modifier.testTag("context-menu-anchor-icon"))
            }
        }

        // FR-42: while the device is half-opened the menu stays clear of the fold, like every other
        // surface Home puts on top of itself. The band arrives in window pixels, so it is rebased
        // onto this overlay's own origin before it is used.
        val hinge = LocalHomeHinge.current
        val exclusion = hinge?.takeIf { it.vertical }?.let {
            Rect(it.leftPx - origin.x, it.topPx - origin.y, it.rightPx - origin.x, it.bottomPx - origin.y)
        }
        val offset = remember(localAnchor, menuSize, container, exclusion) {
            if (localAnchor == null || menuSize == IntSize.Zero || container == IntSize.Zero) null
            else contextMenuOffset(localAnchor, menuSize, container, gap, margin, exclusion)
        }
        GlassSurface(
            level = GlassLevel.MENU,
            shape = DuoTokens.radius.card,
            modifier = Modifier
                .then(
                    if (offset != null) Modifier.offset { offset } else Modifier.align(Alignment.Center),
                )
                .width(MENU_WIDTH)
                .onSizeChanged { menuSize = it }
                .graphicsLayer {
                    alpha = progress
                    scaleX = MENU_MIN_SCALE + (1f - MENU_MIN_SCALE) * progress
                    scaleY = MENU_MIN_SCALE + (1f - MENU_MIN_SCALE) * progress
                    transformOrigin = TransformOrigin(MENU_ORIGIN_X, 0f)
                }
                .testTag("app-context-menu")
                .semantics { contentDescription = "${app.label} options" }
                // Taps inside the menu must not reach the dismissing scrim underneath.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = MENU_MAX_HEIGHT)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = DuoTokens.space.sm),
            ) {
                if (!hostPermission) {
                    // FR-30: no shortcuts without the Home role, and an honest reason why.
                    MenuRow(
                        label = SET_DEFAULT_HOME_ROW,
                        icon = Icons.Rounded.Home,
                        tag = "context-menu-set-default-home",
                        onClick = { onDismiss(); onMakeDefault() },
                    )
                    HorizontalDivider(color = colors.separator)
                } else if (shortcuts.isNotEmpty()) {
                    shortcuts.forEach { shortcut ->
                        ShortcutRow(
                            shortcut = shortcut,
                            onLaunch = { bounds ->
                                repository.start(shortcut, bounds)
                                onDismiss()
                            },
                            onDragOut = { pinShortcut(shortcut) },
                        )
                    }
                    HorizontalDivider(color = colors.separator)
                }

                contextMenuActions(capabilities).forEach { action ->
                    MenuRow(
                        label = action.label,
                        icon = iconFor(action),
                        tag = "context-menu-${action.name.lowercase()}",
                        destructive = isDestructive(action),
                        onClick = { onAction(action) },
                    )
                }
            }
        }
    }
}

/**
 * One app shortcut row (FR-26, FR-27). A tap launches it with the row's own source bounds; a drag
 * out of the menu pins it to Home.
 */
@Composable
private fun ShortcutRow(
    shortcut: DuoShortcut,
    onLaunch: (android.graphics.Rect) -> Unit,
    onDragOut: () -> Unit,
) {
    val colors = currentDuoColors()
    val context = LocalContext.current
    val density = LocalDensity.current
    val repository = remember(context) { DuoShortcuts.repository(context) }
    val sizePx = with(density) { SHORTCUT_ICON.roundToPx() }
    val icon = remember(shortcut, sizePx) { mutableStateOf(repository.iconFor(shortcut, sizePx)) }
    LaunchedEffect(shortcut, sizePx) {
        if (icon.value == null) icon.value = repository.loadIcon(shortcut, sizePx)
    }
    val bounds = remember { android.graphics.Rect() }
    var dragged by remember(shortcut) { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ROW_HEIGHT)
            .testTag("context-menu-shortcut-${shortcut.id}")
            .pointerInput(shortcut) {
                detectDragGestures(
                    onDragStart = { dragged = false },
                    onDragEnd = { if (dragged) onDragOut() },
                    onDrag = { change, amount ->
                        change.consume()
                        if (amount.getDistance() > 0f) dragged = true
                    },
                )
            }
            .clickable(role = Role.Button, onClick = { onLaunch(bounds) })
            .padding(horizontal = DuoTokens.space.lg, vertical = DuoTokens.space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val bitmap = icon.value
        if (bitmap != null) {
            Image(
                bitmap,
                null,
                Modifier
                    .size(SHORTCUT_ICON)
                    .clip(CircleShape)
                    .onSizeChangedBounds(bounds),
            )
        } else {
            Box(
                Modifier
                    .size(SHORTCUT_ICON)
                    .background(colors.glassTint.copy(alpha = GLYPH_ALPHA), CircleShape)
                    .onSizeChangedBounds(bounds),
            )
        }
        Spacer(Modifier.width(DuoTokens.space.md))
        Text(
            text = shortcut.label,
            style = DuoTokens.type.callout,
            color = colors.label1,
            maxLines = 2,
        )
    }
}

@Composable
private fun MenuRow(
    label: String,
    icon: ImageVector,
    tag: String,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = currentDuoColors()
    val tint = if (destructive) colors.accent else colors.label1
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ROW_HEIGHT)
            .testTag(tag)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = DuoTokens.space.lg, vertical = DuoTokens.space.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = DuoTokens.type.callout,
            color = tint,
            maxLines = 2,
            modifier = Modifier.padding(end = DuoTokens.space.md),
        )
        Icon(icon, null, Modifier.size(MENU_ICON), tint = tint)
    }
}

private fun iconFor(action: ContextMenuAction): ImageVector = when (action) {
    ContextMenuAction.EDIT_HOME -> Icons.Rounded.GridView
    ContextMenuAction.EDIT_ICON -> Icons.Rounded.AutoAwesome
    ContextMenuAction.APP_INFO -> Icons.Rounded.Info
    ContextMenuAction.CREATE_FOLDER -> Icons.Rounded.CreateNewFolder
    ContextMenuAction.WIDGETS -> Icons.Rounded.Widgets
    ContextMenuAction.HIDE_APP -> Icons.Rounded.VisibilityOff
    ContextMenuAction.ADD_TO_HOME -> Icons.Rounded.Home
    ContextMenuAction.REMOVE_FROM_HOME -> Icons.Rounded.RemoveCircleOutline
    ContextMenuAction.UNINSTALL -> Icons.Rounded.DeleteOutline
}

/** Records a row's window bounds so a launched shortcut gets the zoom origin FR-26 asks for. */
private fun Modifier.onSizeChangedBounds(target: android.graphics.Rect): Modifier =
    onGloballyPositioned { target.set(it.boundsInWindow().toAndroidBounds()) }

private val MENU_WIDTH = 248.dp
private val MENU_MAX_HEIGHT = 440.dp
private val ROW_HEIGHT = 48.dp
private val MENU_ICON = 20.dp
private val SHORTCUT_ICON = 28.dp
private const val SCRIM_ALPHA = 0.32f
private const val GLYPH_ALPHA = 0.5f
private const val MENU_MIN_SCALE = 0.88f
private const val MENU_ORIGIN_X = 0.5f
