package com.jake.duolauncher.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.jake.duolauncher.AppearanceSettings
import com.jake.duolauncher.DuoFontChoice
import com.jake.duolauncher.WallpaperSource
import com.jake.duolauncher.design.DuoAccentPreset
import com.jake.duolauncher.design.DuoTokens
import com.jake.duolauncher.design.accentForHue
import com.jake.duolauncher.design.duoColors
import com.jake.duolauncher.design.hueOf
import com.jake.duolauncher.icons.IconAppearance
import com.jake.duolauncher.icons.IconPackFailure
import com.jake.duolauncher.icons.IconPackState
import com.jake.duolauncher.icons.IconShape
import kotlin.math.roundToInt

/**
 * The three sections that decide how Duo *looks*: Wallpaper, Appearance and Icons (FR-79).
 *
 * Each section is a plain list of conditional rows. [SettingsVisibility] decides which rows the
 * search field is currently showing, so a section never has to know whether a query is active.
 */

// ---------------------------------------------------------------------------
// Wallpaper (FR-3, FR-4, FR-9)
// ---------------------------------------------------------------------------

@Composable
internal fun WallpaperSection(
    state: DuoSettingsUiState,
    actions: DuoSettingsActions,
    visibility: SettingsVisibility,
    modifier: Modifier = Modifier,
) {
    val wallpaper = state.wallpaper
    SettingsGroup(
        title = DuoSettingsSection.WALLPAPER.title,
        modifier = modifier,
        footer = "Duo can only blur a wallpaper it draws itself. With the Android wallpaper, " +
            "glass stays tinted and blur is off.",
    ) {
        if (visibility.shows(SettingsIds.WALLPAPER_SOURCE)) {
            SettingsOptionRow(
                title = "Wallpaper source",
                options = WallpaperSource.entries.toList(),
                selected = state.settings.wallpaperSource,
                label = { if (it == WallpaperSource.DUO) "Duo wallpaper" else "Android wallpaper" },
                onSelect = actions.onWallpaperSource,
                testTag = "setting-wallpaper-source",
                optionTag = { "wallpaper-source-${it.name.lowercase()}" },
            )
        }
        if (visibility.shows(SettingsIds.WALLPAPER_PHOTO)) {
            SettingsActionRow {
                SettingsActionChip(
                    label = if (wallpaper.previewPending) "Choose a different photo" else "Choose a photo",
                    onClick = actions.onChoosePhoto,
                    prominent = !wallpaper.previewPending,
                    enabled = !wallpaper.loading,
                    testTag = "background-choose",
                )
                if (wallpaper.previewPending) {
                    SettingsActionChip(
                        label = "Apply",
                        onClick = actions.onApplyPreview,
                        prominent = true,
                        testTag = "background-preview-apply",
                    )
                    SettingsActionChip(
                        label = "Cancel",
                        onClick = actions.onCancelPreview,
                        testTag = "background-preview-cancel",
                    )
                }
            }
            if (wallpaper.loading) {
                SettingsRow(title = "Loading the photo…", testTag = "background-loading")
            }
            wallpaper.message?.let { message ->
                SettingsRow(
                    title = message,
                    onClick = actions.onClearWallpaperMessage,
                    testTag = "background-message",
                )
            }
        }
        if (visibility.shows(SettingsIds.WALLPAPER_RESET) &&
            wallpaper.photoSelected && !wallpaper.previewPending
        ) {
            SettingsActionRow {
                SettingsActionChip(
                    label = "Reset to Duo dunes",
                    onClick = actions.onResetBackground,
                    testTag = "background-reset",
                )
            }
        }
        if (visibility.shows(SettingsIds.WALLPAPER_DIM)) {
            SettingsSwitchRow(
                title = "Dim wallpaper in dark mode",
                subtitle = "Draws a soft scrim over the wallpaper while dark appearance is on.",
                checked = state.settings.dimInDark,
                onCheckedChange = actions.onDimInDark,
                testTag = "setting-dim-in-dark",
            )
        }
        if (visibility.shows(SettingsIds.WALLPAPER_ANDROID)) {
            SettingsNavigationRow(
                title = "Android wallpaper",
                subtitle = "Opens Android's own wallpaper picker. It does not change Duo's background.",
                onClick = actions.onAndroidWallpaper,
                testTag = "wallpaper-preview",
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Appearance (FR-5, FR-6, FR-8, FR-13)
// ---------------------------------------------------------------------------

@Composable
internal fun AppearanceSection(
    state: DuoSettingsUiState,
    actions: DuoSettingsActions,
    visibility: SettingsVisibility,
    modifier: Modifier = Modifier,
) {
    SettingsGroup(title = DuoSettingsSection.APPEARANCE.title, modifier = modifier) {
        if (visibility.shows(SettingsIds.APPEARANCE_MODE)) {
            // The existing light / dark / system / sunrise store, including its location flow.
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = DuoTokens.space.lg, vertical = DuoTokens.space.md),
            ) {
                AppearanceSettings(
                    state = state.appearance,
                    onMode = actions.onAppearanceMode,
                    onManual = actions.onAppearanceManual,
                    onDeviceLocation = actions.onAppearanceDeviceLocation,
                    onClear = actions.onAppearanceClear,
                )
            }
        }
        if (visibility.shows(SettingsIds.APPEARANCE_GLASS)) {
            val glass = state.settings.glassLevel
            SettingsSliderRow(
                title = "Glass",
                subtitle = "Clear lets the wallpaper through. Tinted makes every glass surface solid.",
                valueLabel = when {
                    glass <= 0 -> "Clear"
                    glass >= 100 -> "Tinted"
                    else -> "$glass"
                },
                value = glass.toFloat(),
                range = 0f..100f,
                onChange = { actions.onGlass(it.roundToInt()) },
                testTag = "setting-glass",
            )
        }
        if (visibility.shows(SettingsIds.APPEARANCE_REDUCE_TRANSPARENCY)) {
            SettingsSwitchRow(
                title = "Reduce transparency",
                subtitle = "Draws glass as an opaque tint with no blur.",
                checked = state.settings.reduceTransparency,
                onCheckedChange = actions.onReduceTransparency,
                testTag = "setting-reduce-transparency",
            )
        }
        if (visibility.shows(SettingsIds.APPEARANCE_ACCENT)) {
            AccentRow(state = state, actions = actions)
        }
        if (visibility.shows(SettingsIds.APPEARANCE_FONT)) {
            SettingsOptionRow(
                title = "Font",
                options = DuoFontChoice.entries.toList(),
                selected = state.settings.font,
                label = { if (it == DuoFontChoice.INTER) "Duo (Inter)" else "System font" },
                onSelect = actions.onFont,
                testTag = "setting-font",
                optionTag = { "font-${it.name.lowercase()}" },
            )
        }
    }
}

/**
 * Automatic, the eight presets, and a custom hue (FR-8).
 *
 * The stored value is a resolved ARGB colour (null meaning Automatic), so which swatch is selected
 * is worked out by comparing against each preset at the current appearance rather than by storing a
 * separate "which kind of accent" flag that could disagree with the colour.
 */
@Composable
private fun AccentRow(state: DuoSettingsUiState, actions: DuoSettingsActions) {
    val colors = duoColors()
    val dark = colors.dark
    val stored = state.settings.accent
    val selectedPreset = remember(stored, dark) {
        DuoAccentPreset.entries.firstOrNull { it.color(dark).toArgb() == stored }
    }
    val isAuto = stored == null
    val isCustom = stored != null && selectedPreset == null
    var hue by remember(stored, dark) {
        mutableFloatStateOf(stored?.let { hueOf(Color(it)) } ?: 0f)
    }
    Column(Modifier.fillMaxWidth()) {
        SettingsRow(
            title = "Accent color",
            subtitle = "Automatic follows Android's dynamic colour, or the Duo wallpaper.",
        )
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = DuoTokens.space.lg,
                    end = DuoTokens.space.lg,
                    bottom = DuoTokens.space.sm,
                ),
            horizontalArrangement = Arrangement.spacedBy(DuoTokens.space.xs),
            verticalArrangement = Arrangement.spacedBy(DuoTokens.space.xs),
        ) {
            SettingsSwatch(
                color = colors.accent,
                selected = isAuto,
                contentDescription = "Accent color, Automatic",
                onClick = { actions.onAccent(null) },
                testTag = "accent-auto",
            )
            DuoAccentPreset.entries.forEach { preset ->
                SettingsSwatch(
                    color = preset.color(dark),
                    selected = preset == selectedPreset,
                    contentDescription = "Accent color, ${preset.presetLabel()}",
                    onClick = { actions.onAccent(preset.color(dark).toArgb()) },
                    testTag = "accent-${preset.name.lowercase()}",
                )
            }
            SettingsSwatch(
                color = accentForHue(hue, dark),
                selected = isCustom,
                contentDescription = "Accent color, Custom",
                onClick = { actions.onAccent(accentForHue(hue, dark).toArgb()) },
                testTag = "accent-custom",
            )
        }
        SettingsSliderRow(
            title = "Custom hue",
            valueLabel = "${hue.roundToInt()}°",
            value = hue,
            range = 0f..360f,
            onChange = {
                hue = it
                actions.onAccent(accentForHue(it, dark).toArgb())
            },
            testTag = "setting-accent-hue",
        )
    }
}

/** Sentence case for a preset name, so "ROUNDED_SQUARE"-style constants never reach the user. */
private fun DuoAccentPreset.presetLabel(): String =
    name.lowercase().replaceFirstChar { it.uppercase() }

// ---------------------------------------------------------------------------
// Icons (FR-14 to FR-19)
// ---------------------------------------------------------------------------

@Composable
internal fun IconsSection(
    state: DuoSettingsUiState,
    actions: DuoSettingsActions,
    visibility: SettingsVisibility,
    modifier: Modifier = Modifier,
) {
    val colors = duoColors()
    val settings = state.settings
    SettingsGroup(title = DuoSettingsSection.ICONS.title, modifier = modifier) {
        if (visibility.shows(SettingsIds.ICONS_APPEARANCE)) {
            SettingsOptionRow(
                title = "Icon appearance",
                subtitle = "Clear and Tinted reduce every icon to a single monochrome glyph.",
                options = IconAppearance.entries.toList(),
                selected = settings.iconAppearance,
                label = { it.appearanceLabel() },
                onSelect = actions.onIconAppearance,
                testTag = "setting-icon-appearance",
                optionTag = { "icon-appearance-${it.name.lowercase()}" },
            )
        }
        if (visibility.shows(SettingsIds.ICONS_TINT) &&
            settings.iconAppearance == IconAppearance.TINTED
        ) {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = DuoTokens.space.lg, vertical = DuoTokens.space.sm),
                horizontalArrangement = Arrangement.spacedBy(DuoTokens.space.xs),
                verticalArrangement = Arrangement.spacedBy(DuoTokens.space.xs),
            ) {
                DuoAccentPreset.entries.forEach { preset ->
                    val argb = preset.color(colors.dark).toArgb()
                    SettingsSwatch(
                        color = preset.color(colors.dark),
                        selected = settings.iconTint == argb,
                        contentDescription = "Icon tint, ${preset.presetLabel()}",
                        onClick = { actions.onIconTint(argb, settings.iconTintIntensity) },
                        testTag = "icon-tint-${preset.name.lowercase()}",
                    )
                }
            }
            SettingsSliderRow(
                title = "Tint intensity",
                valueLabel = "${settings.iconTintIntensity}",
                value = settings.iconTintIntensity.toFloat(),
                range = 0f..100f,
                onChange = { actions.onIconTint(settings.iconTint, it.roundToInt()) },
                testTag = "setting-icon-tint-intensity",
            )
        }
        if (visibility.shows(SettingsIds.ICONS_SHAPE)) {
            SettingsOptionRow(
                title = "Icon shape",
                options = IconShape.entries.toList(),
                selected = settings.iconShape,
                label = { it.shapeLabel() },
                onSelect = actions.onIconShape,
                testTag = "setting-icon-shape",
                optionTag = { "icon-shape-${it.name.lowercase()}" },
            )
        }
        if (visibility.shows(SettingsIds.ICONS_LARGE)) {
            SettingsSwitchRow(
                title = "Large icons",
                subtitle = "Hides app names and grows every icon by 20%.",
                checked = settings.largeIcons,
                onCheckedChange = actions.onLargeIcons,
                testTag = "setting-large-icons",
            )
        }
        if (visibility.shows(SettingsIds.ICONS_LABELS)) {
            SettingsSwitchRow(
                title = "Show app names",
                subtitle = if (settings.largeIcons) "Large icons always hide app names." else null,
                checked = state.labels && !settings.largeIcons,
                enabled = !settings.largeIcons,
                onCheckedChange = actions.onLabels,
                testTag = "label-switch",
            )
        }
        if (visibility.shows(SettingsIds.ICONS_PACK)) {
            IconPackRows(state = state, actions = actions)
        }
    }
}

/** The icon-pack chooser plus the error table's two pack failure states. */
@Composable
private fun IconPackRows(state: DuoSettingsUiState, actions: DuoSettingsActions) {
    val packs = state.iconPacks
    if (packs.isEmpty()) {
        SettingsRow(
            title = "Icon pack",
            subtitle = "No icon packs are installed. Duo reads ADW and Nova compatible packs.",
            testTag = "setting-icon-pack-empty",
        )
    } else {
        val options: List<String?> = listOf<String?>(null) + packs.map { it.packageName }
        SettingsOptionRow(
            title = "Icon pack",
            subtitle = "Apps the pack does not cover keep the icon appearance above.",
            options = options,
            selected = state.settings.iconPack,
            label = { packageName ->
                packageName?.let { name -> packs.firstOrNull { it.packageName == name }?.label ?: name }
                    ?: "None"
            },
            onSelect = actions.onIconPack,
            testTag = "setting-icon-pack",
        )
    }
    when (val packState = state.iconPackState) {
        is IconPackState.Unavailable -> SettingsNotice(
            title = when (packState.reason) {
                IconPackFailure.NOT_INSTALLED -> "Icon pack not installed"
                else -> "Couldn't load icon pack"
            },
            message = when (packState.reason) {
                IconPackFailure.NOT_INSTALLED ->
                    "The pack Duo was using is gone. Icons fall back to the appearance above."
                IconPackFailure.NO_APPFILTER ->
                    "That app does not ship an icon map Duo can read."
                IconPackFailure.TOO_LARGE ->
                    "That pack's icon map is too large to read safely."
                IconPackFailure.MALFORMED ->
                    "That pack's icon map could not be parsed. It has been ignored."
            },
            actionLabel = "Reset",
            onAction = { actions.onIconPack(null) },
            testTag = "icon-pack-problem",
        )
        is IconPackState.Ready -> SettingsRow(
            title = "Icon pack",
            value = "${packState.mappings} icons",
            testTag = "icon-pack-ready",
        )
        IconPackState.None -> Unit
    }
}

private fun IconAppearance.appearanceLabel(): String = when (this) {
    IconAppearance.DEFAULT -> "Default"
    IconAppearance.DARK -> "Dark"
    IconAppearance.CLEAR -> "Clear"
    IconAppearance.TINTED -> "Tinted"
}

private fun IconShape.shapeLabel(): String = when (this) {
    IconShape.SQUIRCLE -> "Squircle"
    IconShape.CIRCLE -> "Circle"
    IconShape.ROUNDED_SQUARE -> "Rounded square"
    IconShape.SQUARE -> "Square"
    IconShape.SCALLOP -> "Scalloped"
}
