package com.jake.duolauncher.today.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jake.duolauncher.design.ConcentricRectangle
import com.jake.duolauncher.design.DuoRadius
import com.jake.duolauncher.design.DuoTokens
import com.jake.duolauncher.design.GlassLevel
import com.jake.duolauncher.design.GlassSurface
import com.jake.duolauncher.design.currentDuoColors
import com.jake.duolauncher.today.TodayWidgetSize

/**
 * The chrome every built-in widget shares: one [GlassLevel.WIDGET] surface, the spec's inner
 * margins, and corners that stay concentric with the surface's own (FR-59, FR-60, AC-50).
 */

/**
 * FR-60's 16dp inner margin. The research brief's "11pt when tight" becomes [TodayWidgetMarginTight]
 * — 12dp rather than 11dp, because the spacing scale in [DuoTokens] has no 11 and FR-1 forbids
 * inventing a value between its steps.
 */
val TodayWidgetMargin: Dp = DuoTokens.space.lg

/** The tight margin, used by the 2x2 where a 16dp inset would leave almost no content box. */
val TodayWidgetMarginTight: Dp = DuoTokens.space.md

/** The margin a widget of this span uses. */
fun todayWidgetMargin(size: TodayWidgetSize): Dp =
    if (size == TodayWidgetSize.SMALL) TodayWidgetMarginTight else TodayWidgetMargin

/** The corner a child nested inside the margin needs to stay parallel to the widget's own (FR-1). */
fun todayInnerRadius(size: TodayWidgetSize): DuoRadius =
    ConcentricRectangle(DuoTokens.radius.widget, todayWidgetMargin(size))

/** The minimum touch target every control in a widget honors (NFR-A1). */
val TodayTouchTarget: Dp = 48.dp

/**
 * A built-in widget's glass backing and content box.
 *
 * [semanticsLabel] merges the widget into a single TalkBack node, which is right for the widgets
 * that are one glanceable statement (Clock, Batteries). Widgets that contain their own controls —
 * Now Playing's transport, Suggestions' app tiles — pass null and let those controls speak for
 * themselves, because merging would hide them from TalkBack entirely.
 */
@Composable
fun TodayWidgetFrame(
    size: TodayWidgetSize,
    modifier: Modifier = Modifier,
    semanticsLabel: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val margin = todayWidgetMargin(size)
    GlassSurface(
        level = GlassLevel.WIDGET,
        shape = DuoTokens.radius.widget,
        modifier = modifier.then(
            if (semanticsLabel != null) {
                Modifier.semantics(mergeDescendants = true) { contentDescription = semanticsLabel }
            } else {
                Modifier
            },
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(margin),
            verticalArrangement = Arrangement.spacedBy(DuoTokens.space.xs),
            content = content,
        )
    }
}

/**
 * The inline opt-in a widget shows instead of data it is not allowed to read (NFR-S2, AC-49).
 *
 * It explains the access at the point of use and reports the tap; it never requests anything
 * itself. The button clears [TodayTouchTarget] and carries its own label.
 */
@Composable
fun TodayTurnOnPrompt(
    message: String,
    actionLabel: String,
    accessibilityLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val colors = currentDuoColors()
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(DuoTokens.space.xxs),
    ) {
        if (!compact) {
            Text(
                text = message,
                style = DuoTokens.type.footnote,
                color = colors.label2,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(
            onClick = onClick,
            modifier = Modifier
                .heightIn(min = TodayTouchTarget)
                .semantics { contentDescription = accessibilityLabel },
        ) {
            Text(
                text = actionLabel,
                style = DuoTokens.type.subhead,
                color = colors.accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A widget's small caption row: an icon and a label, used as a heading inside a widget. */
@Composable
fun TodayWidgetCaption(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
) {
    val colors = currentDuoColors()
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(DuoTokens.space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.label2,
            modifier = Modifier.size(DuoTokens.space.lg),
        )
        Text(
            text = label,
            style = DuoTokens.type.caption2,
            color = colors.label2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The quiet stand-in a widget shows when it has nothing to say — no events, nothing playing, no
 * suggestions yet. Never an error, because none of these are errors.
 */
@Composable
fun TodayWidgetPlaceholder(
    text: String,
    modifier: Modifier = Modifier,
) {
    val colors = currentDuoColors()
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Text(
            text = text,
            style = DuoTokens.type.footnote,
            color = colors.label3,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * An app icon, or a generated stand-in.
 *
 * The stand-in is a tinted tile carrying the label's first character, so a suggestion whose icon
 * has not rasterized yet (or a preview with no renderer at all) still reads as that app rather
 * than as a hole in the grid.
 */
@Composable
fun TodayAppIcon(
    icon: ImageBitmap?,
    label: String,
    iconSize: Dp,
    modifier: Modifier = Modifier,
) {
    val colors = currentDuoColors()
    val shape = DuoTokens.radius.iconRadiusFor(iconSize)
    if (icon != null) {
        androidx.compose.foundation.Image(
            bitmap = icon,
            contentDescription = null,
            modifier = modifier.size(iconSize).clip(shape),
        )
    } else {
        Box(
            modifier = modifier
                .size(iconSize)
                .clip(shape)
                .background(colors.accent.copy(alpha = PLACEHOLDER_TILE_ALPHA)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label.take(1).uppercase(),
                style = DuoTokens.type.subhead,
                color = colors.label1,
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** A round control surface for a media transport button, sized to the accessible minimum. */
@Composable
fun TodayTransportSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = currentDuoColors()
    Box(
        modifier = modifier
            .size(TodayTouchTarget)
            .clip(CircleShape)
            .background(colors.glassTint.copy(alpha = TRANSPORT_FILL_ALPHA)),
        contentAlignment = Alignment.Center,
        content = { content() },
    )
}

/** Keeps a label column from stretching a widget wider than its span. */
fun Modifier.todayLabelWidth(max: Dp): Modifier = widthIn(max = max)

private const val PLACEHOLDER_TILE_ALPHA = 0.35f
private const val TRANSPORT_FILL_ALPHA = 0.22f
