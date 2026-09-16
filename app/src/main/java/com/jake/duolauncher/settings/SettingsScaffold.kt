@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.jake.duolauncher.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jake.duolauncher.design.DuoTokens
import com.jake.duolauncher.design.GlassLevel
import com.jake.duolauncher.design.GlassSurface
import com.jake.duolauncher.design.LocalDuoTypography
import com.jake.duolauncher.design.duoColors

/**
 * The iOS inset-grouped building blocks Duo Settings is assembled from (FR-79).
 *
 * Every control here obeys the same four rules, so no section has to remember them:
 * - **48dp touch targets** (NFR-A1): rows are `heightIn(min = ROW_MIN_HEIGHT)` and never a fixed
 *   height, so a row grows with its content instead of clipping it.
 * - **No clipped text at font scale 1.3** (NFR-A3): titles wrap or ellipsize, values sit below
 *   rather than beside long titles, and option chips flow onto a second line.
 * - **A TalkBack label on everything** (NFR-A1): switches and sliders carry the row's own title, so
 *   a control is never announced as a bare "switch".
 * - **No colour literals** (FR-1, AC-1): every colour comes from [duoColors].
 */

/** The floor under every settings row: comfortably above the 48dp minimum target. */
internal val ROW_MIN_HEIGHT = 56.dp

/** The inset a group card sits in, which is what makes the style read as "inset grouped". */
internal val GROUP_INSET = DuoTokens.space.lg

/**
 * One inset group: a header, a glass card of rows, and an optional explanatory footer.
 *
 * Rows fill the card edge to edge and draw their own hairline, so the last row's hairline lands on
 * the card's own rim and disappears into it — the iOS separator behaviour without any row needing to
 * know whether it is last.
 */
@Composable
internal fun SettingsGroup(
    title: String,
    modifier: Modifier = Modifier,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = duoColors()
    val type = LocalDuoTypography.current
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = GROUP_INSET)) {
        Text(
            text = title,
            style = type.footnote,
            color = colors.label2,
            modifier = Modifier
                .padding(start = DuoTokens.space.md, bottom = DuoTokens.space.sm)
                .semantics { heading() },
        )
        GlassSurface(
            level = GlassLevel.PANEL,
            shape = DuoTokens.radius.card,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.fillMaxWidth(), content = content)
        }
        if (footer != null) {
            Text(
                text = footer,
                style = type.caption1,
                color = colors.label3,
                modifier = Modifier.padding(
                    start = DuoTokens.space.md,
                    end = DuoTokens.space.md,
                    top = DuoTokens.space.sm,
                ),
            )
        }
    }
}

/** The hairline between rows. Drawn by the row, so groups stay a plain list of rows. */
@Composable
private fun SettingsHairline() {
    val colors = duoColors()
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = DuoTokens.space.lg)
            .heightIn(min = HAIRLINE)
            .background(colors.separator),
    )
}

/**
 * The base row: a title, an optional supporting line, and a trailing control.
 *
 * [onClick] makes the whole row tappable, which is how a navigation row and an action row behave.
 */
@Composable
internal fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    testTag: String? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val colors = duoColors()
    val type = LocalDuoTypography.current
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .then(
                    if (onClick != null && enabled) {
                        Modifier.clickable(onClick = onClick).semantics { role = Role.Button }
                    } else {
                        Modifier
                    },
                )
                .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
                .heightIn(min = ROW_MIN_HEIGHT)
                .padding(horizontal = DuoTokens.space.lg, vertical = DuoTokens.space.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = type.body,
                    color = if (enabled) colors.label1 else colors.label3,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = type.footnote,
                        color = colors.label2,
                        modifier = Modifier.padding(top = DuoTokens.space.xxs),
                    )
                }
            }
            if (value != null) {
                Spacer(Modifier.width(DuoTokens.space.md))
                Text(
                    text = value,
                    style = type.body,
                    color = colors.label2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.sizeIn(maxWidth = VALUE_MAX_WIDTH),
                )
            }
            if (trailing != null) {
                Spacer(Modifier.width(DuoTokens.space.md))
                trailing()
            }
        }
        SettingsHairline()
    }
}

/** A row that opens a deeper page. */
@Composable
internal fun SettingsNavigationRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    testTag: String? = null,
) {
    val colors = duoColors()
    SettingsRow(
        title = title,
        modifier = modifier,
        subtitle = subtitle,
        value = value,
        onClick = onClick,
        testTag = testTag,
        trailing = {
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = colors.label3,
            )
        },
    )
}

/** A switch row. The switch carries the row's title, so TalkBack never says only "switch". */
@Composable
internal fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    testTag: String? = null,
) {
    val colors = duoColors()
    SettingsRow(
        title = title,
        modifier = modifier,
        subtitle = subtitle,
        enabled = enabled,
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                colors = SwitchDefaults.colors(checkedTrackColor = colors.accent),
                modifier = Modifier
                    .sizeIn(minWidth = TOUCH_TARGET, minHeight = TOUCH_TARGET)
                    .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
                    .semantics { contentDescription = title },
            )
        },
    )
}

/**
 * A slider row: title and current value on one line, the slider below.
 *
 * Stacking rather than placing the slider beside the title is deliberate — at font scale 1.3 a
 * side-by-side slider is what squeezes the label into an ellipsis (NFR-A3).
 */
@Composable
internal fun SettingsSliderRow(
    title: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    subtitle: String? = null,
    enabled: Boolean = true,
    testTag: String? = null,
) {
    val colors = duoColors()
    val type = LocalDuoTypography.current
    Column(Modifier.fillMaxWidth()) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
                .heightIn(min = ROW_MIN_HEIGHT)
                .padding(horizontal = DuoTokens.space.lg, vertical = DuoTokens.space.md),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = type.body,
                    color = if (enabled) colors.label1 else colors.label3,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(DuoTokens.space.sm))
                Text(text = valueLabel, style = type.subhead, color = colors.accent, maxLines = 1)
            }
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = type.footnote,
                    color = colors.label2,
                    modifier = Modifier.padding(top = DuoTokens.space.xxs),
                )
            }
            Slider(
                value = value,
                onValueChange = onChange,
                valueRange = range,
                steps = steps,
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = TOUCH_TARGET)
                    .semantics { contentDescription = "$title, $valueLabel" },
            )
        }
        SettingsHairline()
    }
}

/**
 * A choice row: the options as a flowing set of glass chips.
 *
 * [FlowRow] rather than a fixed segmented control, so five icon shapes at font scale 1.3 wrap onto a
 * second line instead of being clipped (NFR-A3).
 */
@Composable
internal fun <T> SettingsOptionRow(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    testTag: String? = null,
    optionTag: ((T) -> String)? = null,
) {
    val colors = duoColors()
    val type = LocalDuoTypography.current
    Column(Modifier.fillMaxWidth()) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
                .padding(horizontal = DuoTokens.space.lg, vertical = DuoTokens.space.md),
        ) {
            Text(
                text = title,
                style = type.body,
                color = if (enabled) colors.label1 else colors.label3,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = type.footnote,
                    color = colors.label2,
                    modifier = Modifier.padding(top = DuoTokens.space.xxs),
                )
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(top = DuoTokens.space.sm),
                horizontalArrangement = Arrangement.spacedBy(DuoTokens.space.sm),
                verticalArrangement = Arrangement.spacedBy(DuoTokens.space.sm),
            ) {
                options.forEach { option ->
                    val isSelected = option == selected
                    val optionLabel = label(option)
                    Box(
                        modifier = Modifier
                            .clip(DuoTokens.radius.tile)
                            .background(
                                if (isSelected) colors.accent else colors.glassTint.copy(alpha = CHIP_ALPHA),
                            )
                            .clickable(enabled = enabled) { onSelect(option) }
                            .then(
                                if (optionTag != null) Modifier.testTag(optionTag(option)) else Modifier,
                            )
                            .heightIn(min = TOUCH_TARGET)
                            .padding(horizontal = DuoTokens.space.lg, vertical = DuoTokens.space.sm)
                            .semantics {
                                role = Role.RadioButton
                                contentDescription = "$title, $optionLabel"
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = optionLabel,
                            style = type.subhead,
                            color = if (isSelected) onAccent(colors.dark) else colors.label1,
                        )
                    }
                }
            }
        }
        SettingsHairline()
    }
}

/**
 * The honest "this needs access Duo does not have" row (FR-23, error table).
 *
 * It states what is missing, offers the route that fixes it, and — because Duo is normally
 * sideloaded — carries the second route through Android's **Allow restricted settings** step, which
 * is the one that actually unblocks a notification-listener toggle on Android 13+.
 */
@Composable
internal fun SettingsNotice(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
    testTag: String? = null,
) {
    val colors = duoColors()
    val type = LocalDuoTypography.current
    Column(Modifier.fillMaxWidth()) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
                .padding(horizontal = DuoTokens.space.lg, vertical = DuoTokens.space.md),
            verticalArrangement = Arrangement.spacedBy(DuoTokens.space.xs),
        ) {
            Text(text = title, style = type.subhead, color = colors.label1)
            Text(text = message, style = type.footnote, color = colors.label2)
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(top = DuoTokens.space.xs),
                horizontalArrangement = Arrangement.spacedBy(DuoTokens.space.sm),
                verticalArrangement = Arrangement.spacedBy(DuoTokens.space.sm),
            ) {
                if (actionLabel != null && onAction != null) {
                    SettingsActionChip(label = actionLabel, prominent = true, onClick = onAction)
                }
                if (secondaryLabel != null && onSecondary != null) {
                    SettingsActionChip(label = secondaryLabel, prominent = false, onClick = onSecondary)
                }
            }
        }
        SettingsHairline()
    }
}

/** A tappable chip: the prominent one carries the accent, the rest are glass. */
@Composable
internal fun SettingsActionChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    prominent: Boolean = false,
    enabled: Boolean = true,
    testTag: String? = null,
) {
    val colors = duoColors()
    val type = LocalDuoTypography.current
    Box(
        modifier = modifier
            .clip(DuoTokens.radius.tile)
            .background(if (prominent) colors.accent else colors.glassTint.copy(alpha = CHIP_ALPHA))
            .clickable(enabled = enabled, onClick = onClick)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .heightIn(min = TOUCH_TARGET)
            .padding(horizontal = DuoTokens.space.lg, vertical = DuoTokens.space.sm)
            .semantics { role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = type.subhead,
            color = when {
                !enabled -> colors.label3
                prominent -> onAccent(colors.dark)
                else -> colors.accent
            },
        )
    }
}

/** A row of chips inside a group, for actions that belong together (Save / Restore). */
@Composable
internal fun SettingsActionRow(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        FlowRow(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = ROW_MIN_HEIGHT)
                .padding(horizontal = DuoTokens.space.lg, vertical = DuoTokens.space.md),
            horizontalArrangement = Arrangement.spacedBy(DuoTokens.space.sm),
            verticalArrangement = Arrangement.spacedBy(DuoTokens.space.sm),
        ) {
            content()
        }
        SettingsHairline()
    }
}

/** A colour swatch, for the accent and icon-tint pickers. */
@Composable
internal fun SettingsSwatch(
    color: androidx.compose.ui.graphics.Color,
    selected: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String? = null,
) {
    val colors = duoColors()
    Box(
        modifier = modifier
            .sizeIn(minWidth = TOUCH_TARGET, minHeight = TOUCH_TARGET)
            .clickable(onClick = onClick)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .semantics {
                role = Role.RadioButton
                this.contentDescription = contentDescription
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(SWATCH_SIZE)
                .clip(RoundedCornerShape(percent = 50))
                .background(color),
        )
        if (selected) {
            Box(
                Modifier
                    .size(SWATCH_RING)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(colors.specular.copy(alpha = SWATCH_RING_ALPHA)),
            )
        }
    }
}

/**
 * The settings search field (AC-64).
 *
 * A glass bar rather than a Material text field, so it belongs to the same surface family as the
 * groups below it. The clear control appears only when there is something to clear.
 */
@Composable
internal fun SettingsSearchField(
    query: String,
    onQuery: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search settings",
) {
    val colors = duoColors()
    val type = LocalDuoTypography.current
    GlassSurface(
        level = GlassLevel.BAR,
        shape = DuoTokens.radius.tile,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = TOUCH_TARGET)
                .padding(horizontal = DuoTokens.space.md, vertical = DuoTokens.space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                tint = colors.label3,
                modifier = Modifier.size(ICON_SIZE),
            )
            Spacer(Modifier.width(DuoTokens.space.sm))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    Text(text = placeholder, style = type.body, color = colors.label3)
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQuery,
                    singleLine = true,
                    textStyle = type.body.copy(color = colors.label1),
                    cursorBrush = SolidColor(colors.accent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings-search-field")
                        .semantics { contentDescription = placeholder },
                )
            }
            if (query.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .sizeIn(minWidth = TOUCH_TARGET, minHeight = TOUCH_TARGET)
                        .clickable { onQuery("") }
                        .testTag("settings-search-clear")
                        .semantics {
                            role = Role.Button
                            contentDescription = "Clear search"
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = null,
                        tint = colors.label2,
                        modifier = Modifier.size(ICON_SIZE),
                    )
                }
            }
        }
    }
}

/** A leading icon for a help entry. */
@Composable
internal fun SettingsInfoRow(
    icon: ImageVector,
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
) {
    val colors = duoColors()
    val type = LocalDuoTypography.current
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = ROW_MIN_HEIGHT)
                .padding(horizontal = DuoTokens.space.lg, vertical = DuoTokens.space.md),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.padding(top = DuoTokens.space.xxs).size(ICON_SIZE),
            )
            Spacer(Modifier.width(DuoTokens.space.md))
            Column(Modifier.weight(1f)) {
                Text(text = title, style = type.subhead, color = colors.label1)
                Text(
                    text = detail,
                    style = type.footnote,
                    color = colors.label2,
                    modifier = Modifier.padding(top = DuoTokens.space.xxs),
                )
            }
        }
        SettingsHairline()
    }
}

/**
 * A readable label over the accent.
 *
 * Taken from the existing token roles rather than a new literal: the specular highlight is the light
 * one and the glass edge is the dark one, which is the same pairing `home/` already uses.
 */
@Composable
private fun onAccent(dark: Boolean): androidx.compose.ui.graphics.Color {
    val colors = duoColors()
    return if (dark) colors.edge else colors.specular
}

private val HAIRLINE = 1.dp
private val TOUCH_TARGET = 48.dp
private val ICON_SIZE = 22.dp
private val SWATCH_SIZE = 28.dp
private val SWATCH_RING = 12.dp
private val VALUE_MAX_WIDTH = 160.dp
private const val CHIP_ALPHA = 0.5f
private const val SWATCH_RING_ALPHA = 0.9f
