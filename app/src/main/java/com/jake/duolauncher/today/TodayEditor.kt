package com.jake.duolauncher.today

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jake.duolauncher.DuoTheme
import com.jake.duolauncher.design.ConcentricRectangle
import com.jake.duolauncher.design.DuoTokens
import com.jake.duolauncher.design.GlassLevel
import com.jake.duolauncher.design.GlassSurface
import com.jake.duolauncher.design.currentDuoColors
import com.jake.duolauncher.today.widgets.TodayPreviewBackdrop
import com.jake.duolauncher.today.widgets.TodayTouchTarget

/**
 * The Today View editor's controls: the add-widget sheet, and the small buttons the column's edit
 * mode hangs off (FR-57).
 *
 * Every control here is at least [TodayTouchTarget] and carries a TalkBack label, because all of
 * them are icon-only or short-labelled and would otherwise be unusable without sight (NFR-A1).
 */

/**
 * Picks a built-in widget and a span to add (FR-57, FR-59).
 *
 * [availableRows] is how many grid rows the column actually has, so the 4x6 is offered only "where
 * the grid fits" as FR-59 puts it, rather than being offered and then clipped.
 */
@Composable
fun TodayAddWidgetSheet(
    onAdd: (TodayWidgetKind, TodayWidgetSize) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    availableRows: Int = TodayWidgetSize.EXTRA_LARGE.rows,
) {
    val colors = currentDuoColors()
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.scrim.copy(alpha = SCRIM_ALPHA))
            // A tap anywhere off the sheet dismisses it. No ripple: this is a scrim, not a button.
            .clickable(
                interactionSource = remember0(),
                indication = null,
                onClickLabel = "Close widget picker",
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        GlassSurface(
            level = GlassLevel.PANEL,
            shape = DuoTokens.radius.sheet,
            modifier = Modifier
                .padding(DuoTokens.space.lg)
                .widthIn(max = SHEET_MAX_WIDTH)
                .testTag("today-picker"),
        ) {
            Column(
                modifier = Modifier
                    .padding(DuoTokens.space.lg)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(DuoTokens.space.md),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Add widget",
                        style = DuoTokens.type.title3,
                        color = colors.label1,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TodayCircleButton(
                        icon = Icons.Rounded.Close,
                        label = "Close widget picker",
                        onClick = onDismiss,
                    )
                }

                TodayWidgetKind.entries.forEach { kind ->
                    Column(verticalArrangement = Arrangement.spacedBy(DuoTokens.space.xs)) {
                        Text(
                            text = kind.displayName,
                            style = DuoTokens.type.subhead,
                            color = colors.label1,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(DuoTokens.space.xs)) {
                            TodayWidgetSize.entries
                                .filter { it.fitsIn(availableRows) }
                                .forEach { size ->
                                    TodayPillButton(
                                        text = size.spanLabel,
                                        label = "Add ${kind.displayName}, ${size.spanLabel}",
                                        onClick = { onAdd(kind, size) },
                                    )
                                }
                        }
                    }
                }
            }
        }
    }
}

/** "2x2", "4x6" — the span as the spec names it, and as the picker labels it. */
internal val TodayWidgetSize.spanLabel: String
    get() = "${columns}×$rows"

/**
 * A circular icon-only control, sized to the accessible minimum with its label on the clickable
 * node so TalkBack announces the action rather than the glyph.
 */
@Composable
internal fun TodayCircleButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = currentDuoColors()
    Box(
        modifier = modifier
            .size(TodayTouchTarget)
            .clip(CircleShape)
            .background(colors.glassTint.copy(alpha = CONTROL_FILL_ALPHA))
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .clickable(
                enabled = enabled,
                onClickLabel = label,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.label1,
            modifier = Modifier.size(CONTROL_GLYPH),
        )
    }
}

/** A short text control: the Edit/Done toggle, and the picker's span chips. */
@Composable
internal fun TodayPillButton(
    text: String,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = currentDuoColors()
    Box(
        modifier = modifier
            .heightIn(min = TodayTouchTarget)
            .clip(DuoTokens.radius.tile)
            .background(colors.glassTint.copy(alpha = CONTROL_FILL_ALPHA))
            .clickable(onClickLabel = label, role = Role.Button, onClick = onClick)
            .padding(horizontal = DuoTokens.space.md)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = DuoTokens.type.subhead,
            color = colors.label1,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A remembered interaction source for the scrim.
 *
 * Wrapped in a named helper purely so the scrim's `clickable` reads as one expression; Compose
 * requires the source to be remembered rather than allocated per recomposition.
 */
@Composable
private fun remember0(): MutableInteractionSource =
    androidx.compose.runtime.remember { MutableInteractionSource() }

/** The inner corner for content nested inside a sheet, kept parallel to the sheet's own (FR-1). */
internal val todaySheetInnerRadius
    get() = ConcentricRectangle(DuoTokens.radius.sheet, DuoTokens.space.lg)

private const val SCRIM_ALPHA = 0.45f
private const val CONTROL_FILL_ALPHA = 0.22f
private const val DISABLED_ALPHA = 0.35f
private val CONTROL_GLYPH = 22.dp
private val SHEET_MAX_WIDTH = 420.dp

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

@Preview(name = "Add widget sheet", widthDp = 420, heightDp = 760)
@Composable
private fun TodayAddWidgetSheetPreview() {
    DuoTheme { TodayPreviewBackdrop { TodayAddWidgetSheet(onAdd = { _, _ -> }, onDismiss = {}) } }
}

@Preview(name = "Add widget sheet, short column", widthDp = 420, heightDp = 760)
@Composable
private fun TodayAddWidgetSheetShortPreview() {
    DuoTheme {
        TodayPreviewBackdrop {
            TodayAddWidgetSheet(onAdd = { _, _ -> }, onDismiss = {}, availableRows = 4)
        }
    }
}

@Preview(name = "Add widget sheet, dark", widthDp = 420, heightDp = 760, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun TodayAddWidgetSheetDarkPreview() {
    DuoTheme(dark = true) { TodayPreviewBackdrop { TodayAddWidgetSheet(onAdd = { _, _ -> }, onDismiss = {}) } }
}

@Preview(name = "Add widget sheet, font scale 1.3", widthDp = 420, heightDp = 760, fontScale = 1.3f)
@Composable
private fun TodayAddWidgetSheetFontScalePreview() {
    DuoTheme { TodayPreviewBackdrop { TodayAddWidgetSheet(onAdd = { _, _ -> }, onDismiss = {}) } }
}
