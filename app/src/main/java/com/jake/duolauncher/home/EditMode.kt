package com.jake.duolauncher.home

import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.jake.duolauncher.design.DuoTokens
import com.jake.duolauncher.design.GlassLevel
import com.jake.duolauncher.design.GlassSurface
import com.jake.duolauncher.design.currentDuoColors
import com.jake.duolauncher.design.rememberMotionEnabled
import kotlin.math.abs
import kotlin.math.sin

// ---------------------------------------------------------------------------
// Edit mode state (FR-45, FR-48, FR-49)
// ---------------------------------------------------------------------------

/**
 * Whether Home is in Edit mode, and what Edit mode currently has open.
 *
 * Kept as one object rather than three booleans scattered through the workspace so that the two
 * rules FR-48 and FR-49 state can be enforced in one place: a locked layout never enters, and every
 * exit route — Done, a tap on empty space, Back — closes the overview and any pending confirmation
 * with it.
 */
@Stable
class HomeEditState(active: Boolean = false) {
    /** FR-45: icons jiggle, − badges show and the toolbar is up. */
    var active by mutableStateOf(active)
        private set

    /** FR-47: the Page overview, opened from the page indicator. */
    var pageOverview by mutableStateOf(false)
        private set

    /** FR-46: the widget slot awaiting "remove and release its binding?" confirmation. */
    var pendingWidgetRemoval by mutableStateOf<Int?>(null)
        private set

    /** The refusal to show, when something was blocked (FR-49). Cleared once it has been seen. */
    var message by mutableStateOf<String?>(null)
        private set

    /**
     * Enters Edit mode unless the layout is locked (FR-49), in which case it says so instead.
     *
     * @return true when Edit mode opened.
     */
    fun enter(locked: Boolean): Boolean {
        if (locked) {
            message = LAYOUT_LOCKED_MESSAGE
            return false
        }
        active = true
        return true
    }

    /** FR-48: Done, a tap on empty space, or Back. */
    fun exit() {
        active = false
        pageOverview = false
        pendingWidgetRemoval = null
    }

    fun openPageOverview() {
        if (active) pageOverview = true
    }

    fun closePageOverview() {
        pageOverview = false
    }

    fun confirmWidgetRemoval(slot: Int) {
        if (active) pendingWidgetRemoval = slot
    }

    fun cancelWidgetRemoval() {
        pendingWidgetRemoval = null
    }

    fun clearMessage() {
        message = null
    }

    /** Shows [text] without entering Edit mode — the locked-layout path for drag and remove. */
    fun refuse(text: String = LAYOUT_LOCKED_MESSAGE) {
        message = text
    }

    internal companion object {
        val Saver: Saver<HomeEditState, Boolean> =
            Saver(save = { it.active }, restore = { HomeEditState(it) })
    }
}

@Composable
internal fun rememberHomeEditState(): HomeEditState =
    rememberSaveable(saver = HomeEditState.Saver) { HomeEditState() }

// ---------------------------------------------------------------------------
// The jiggle (FR-45, FR-11) — pure, so it is unit tested
// ---------------------------------------------------------------------------

/**
 * A stable phase offset in `0f..1f` for [seed], so that neighbouring icons do not wobble in lockstep.
 *
 * Derived from the item's own id, so an icon keeps the same phase across recompositions and across
 * a page swipe rather than resetting to the start of the cycle every time it is re-laid-out.
 */
fun jigglePhase(seed: String): Float {
    if (seed.isEmpty()) return 0f
    val hash = seed.fold(0) { acc, c -> acc * 31 + c.code }
    return abs(hash % JIGGLE_PHASES) / JIGGLE_PHASES.toFloat()
}

/**
 * The rotation, in degrees, of an item at [progress] through the jiggle cycle.
 *
 * [progress] runs `0f..1f` and wraps, so the animation driving it only has to be a linear ramp.
 */
fun jiggleRotation(progress: Float, phase: Float, amplitudeDegrees: Float = JIGGLE_DEGREES): Float =
    amplitudeDegrees * sin(((progress + phase) % 1f) * 2f * Math.PI.toFloat())

/** The wobble's half-angle. Small enough to read as "loose", not as a spin. */
const val JIGGLE_DEGREES = 1.6f

/** How many distinct phase offsets the jiggle uses. */
private const val JIGGLE_PHASES = 7

/** One full wobble, in milliseconds. */
private const val JIGGLE_PERIOD_MS = 620

/**
 * The jiggle of Edit mode (FR-45).
 *
 * FR-11 is honoured structurally rather than by animating to zero: when the system animator duration
 * scale is 0 the infinite transition is never created at all, so there is no permanent animation
 * loop running invisibly and no frame is scheduled for it.
 */
@Composable
fun Modifier.jiggle(enabled: Boolean, seed: String): Modifier {
    val motion = rememberMotionEnabled()
    if (!enabled || !motion) return this
    val transition = rememberInfiniteTransition(label = "jiggle")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = JIGGLE_SPEC,
        label = "jiggle progress",
    )
    val phase = jigglePhase(seed)
    return graphicsLayer { rotationZ = jiggleRotation(progress, phase) }
}

private val JIGGLE_SPEC: InfiniteRepeatableSpec<Float> =
    infiniteRepeatable(animation = tween(durationMillis = JIGGLE_PERIOD_MS), repeatMode = RepeatMode.Restart)

// ---------------------------------------------------------------------------
// The remove badge (FR-46)
// ---------------------------------------------------------------------------

/** What a − badge removes, which decides both its wording and whether it needs confirming. */
enum class RemovableKind {
    /** FR-46: leaves Home, stays in App Library. */
    APP,

    /** FR-46: leaves Home; the pinned shortcut itself is unpinned. */
    SHORTCUT,

    /** Leaves Home; its apps return to Home rather than being lost. */
    FOLDER,

    /** FR-46: asks for confirmation, because removing it releases the widget's binding. */
    WIDGET,
}

/** FR-46: only widgets confirm, because only a widget loses state that cannot be recreated. */
fun needsRemovalConfirmation(kind: RemovableKind): Boolean = kind == RemovableKind.WIDGET

/** What TalkBack reads on a − badge, so the destination is never a mystery (NFR-A1). */
fun removeBadgeLabel(kind: RemovableKind, label: String): String = when (kind) {
    RemovableKind.APP -> "Remove $label from Home"
    RemovableKind.SHORTCUT -> "Remove shortcut $label from Home"
    RemovableKind.FOLDER -> "Remove folder $label from Home"
    RemovableKind.WIDGET -> "Remove widget $label"
}

/**
 * The − badge Edit mode puts on a removable item (FR-45, FR-46).
 *
 * It sits in the corner opposite the notification badge so the two never collide, and its touch
 * target is a full 48dp even though the glyph is small (NFR-A3).
 */
@Composable
fun BoxScope.RemoveBadge(
    kind: RemovableKind,
    label: String,
    tag: String,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = currentDuoColors()
    Box(
        modifier = modifier
            .align(Alignment.TopStart)
            .offset(x = -REMOVE_TOUCH / 4, y = -REMOVE_TOUCH / 4)
            .size(REMOVE_TOUCH)
            .clickable(role = Role.Button, onClick = onRemove)
            .testTag(tag)
            .semantics { contentDescription = removeBadgeLabel(kind, label) },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.size(REMOVE_GLYPH).background(colors.glassTint, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Remove,
                contentDescription = null,
                tint = colors.ink,
                modifier = Modifier.size(REMOVE_ICON),
            )
        }
    }
}

private val REMOVE_TOUCH = 48.dp
private val REMOVE_GLYPH = 22.dp
private val REMOVE_ICON = 16.dp

// ---------------------------------------------------------------------------
// The toolbar (FR-45)
// ---------------------------------------------------------------------------

/** The toolbar's actions, in the order FR-45 lists them. */
enum class EditAction(val label: String) {
    /** Opens the Home layout settings: grid size, layout mode, dock. */
    EDIT("Edit"),

    /** FR-45's `+`: the widget picker. */
    ADD_WIDGET("Add widget"),

    /** Icons and glass. */
    CUSTOMIZE("Customize"),

    WALLPAPER("Wallpaper"),

    /** FR-48. */
    DONE("Done"),
}

internal fun editActionIcon(action: EditAction): ImageVector = when (action) {
    EditAction.EDIT -> Icons.Rounded.GridView
    EditAction.ADD_WIDGET -> Icons.Rounded.Add
    EditAction.CUSTOMIZE -> Icons.Rounded.Tune
    EditAction.WALLPAPER -> Icons.Rounded.Wallpaper
    EditAction.DONE -> Icons.Rounded.Check
}

/**
 * Edit mode's glass toolbar (FR-45).
 *
 * One row of icon buttons on a single [GlassLevel.BAR] surface, matching the page indicator it sits
 * above. Every control is icon-only, so every control carries its label for TalkBack and a 48dp
 * touch target; Done is also labelled in text because it is the one action the user is looking for.
 */
@Composable
fun EditToolbar(
    onAction: (EditAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = currentDuoColors()
    GlassSurface(
        level = GlassLevel.BAR,
        shape = CircleShape,
        modifier = modifier.testTag("edit-toolbar"),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = DuoTokens.space.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DuoTokens.space.xxs),
        ) {
            EditAction.entries.forEach { action ->
                val done = action == EditAction.DONE
                Box(
                    modifier = Modifier
                        .sizeIn(minWidth = TOOLBAR_TOUCH, minHeight = TOOLBAR_TOUCH)
                        .clickable(role = Role.Button, onClick = { onAction(action) })
                        .testTag("edit-${action.name.lowercase()}")
                        .semantics { contentDescription = action.label }
                        .padding(horizontal = if (done) DuoTokens.space.sm else 0.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (done) {
                        Text(
                            text = action.label,
                            style = DuoTokens.type.headline,
                            color = colors.accent,
                            maxLines = 1,
                        )
                    } else {
                        Icon(
                            imageVector = editActionIcon(action),
                            contentDescription = null,
                            tint = colors.label1,
                            modifier = Modifier.size(TOOLBAR_ICON),
                        )
                    }
                }
            }
        }
    }
}

private val TOOLBAR_TOUCH = 48.dp
private val TOOLBAR_ICON = 22.dp
