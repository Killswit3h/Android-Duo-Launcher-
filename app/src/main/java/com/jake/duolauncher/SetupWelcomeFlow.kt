package com.jake.duolauncher

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.jake.duolauncher.badges.NotificationAccess
import com.jake.duolauncher.design.DEFAULT_GLASS_LEVEL
import com.jake.duolauncher.design.DuoTokens
import com.jake.duolauncher.design.GlassLevel
import com.jake.duolauncher.design.GlassSurface
import com.jake.duolauncher.design.LocalDuoTypography
import com.jake.duolauncher.design.currentDuoColors
import com.jake.duolauncher.icons.IconAppearance

/**
 * The welcome flow's steps, in the order FR-81 fixes them.
 *
 * [optional] marks the steps that ask for an access grant. Those are the ones that carry an
 * explicit **Skip this step** control, and skipping them leaves a launcher with badges or gestures
 * simply switched off — never a broken one.
 */
internal enum class SetupStep(val title: String, val optional: Boolean) {
    SET_AS_HOME("Welcome to Duo", optional = false),
    CHOOSE_LOOK("Choose your look", optional = false),
    BADGES("Turn on badges", optional = true),
    GESTURES("Gestures and lock", optional = true),
    DONE("You're all set", optional = false),
}

// ---------------------------------------------------------------------------
// Step cards
// ---------------------------------------------------------------------------

@Composable
internal fun SetupStepCard(
    step: SetupStep,
    isDefaultHome: Boolean,
    onMakeDefault: () -> Unit,
    onAddWidget: () -> Unit,
    onSkipStep: () -> Unit,
    appearanceMode: AppearanceMode,
    onAppearanceMode: (AppearanceMode) -> Unit,
    glassLevel: Int,
    onGlassLevel: (Int) -> Unit,
    iconAppearance: IconAppearance,
    onIconAppearance: (IconAppearance) -> Unit,
    onOpenBadgeAccess: (() -> Unit)? = null,
    onOpenShadeAccess: (() -> Unit)? = null,
    onOpenAppInfo: (() -> Unit)? = null,
) {
    GlassSurface(
        level = GlassLevel.PANEL,
        shape = DuoTokens.radius.sheet,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(DuoTokens.space.xl),
            verticalArrangement = Arrangement.spacedBy(DuoTokens.space.md),
        ) {
            when (step) {
                SetupStep.SET_AS_HOME -> SetHomeStep(isDefaultHome, onMakeDefault)
                SetupStep.CHOOSE_LOOK -> ChooseLookStep(
                    appearanceMode = appearanceMode,
                    onAppearanceMode = onAppearanceMode,
                    glassLevel = glassLevel,
                    onGlassLevel = onGlassLevel,
                    iconAppearance = iconAppearance,
                    onIconAppearance = onIconAppearance,
                )
                SetupStep.BADGES -> BadgesStep(onSkipStep, onOpenBadgeAccess, onOpenAppInfo)
                SetupStep.GESTURES -> GesturesStep(onSkipStep, onOpenShadeAccess, onOpenAppInfo)
                SetupStep.DONE -> DoneStep(onAddWidget)
            }
        }
    }
}

@Composable
private fun SetHomeStep(isDefaultHome: Boolean, onMakeDefault: () -> Unit) {
    SetupIntro(
        icon = if (isDefaultHome) Icons.Rounded.Check else Icons.Rounded.Home,
        headline = if (isDefaultHome) "Duo is your Home app" else "Make Duo your Home app",
        detail = if (isDefaultHome) {
            "The Home button comes back here. You can change this in Android settings at any time."
        } else {
            "Android will ask which app to use. Duo only becomes your Home screen — you can switch " +
                "back at any time."
        },
    )
    if (!isDefaultHome) {
        SetupActionButton(
            label = "Choose Home app",
            emphasis = SetupEmphasis.PRIMARY,
            testTag = "setup-make-default",
            onClick = onMakeDefault,
        )
    }
}

@Composable
private fun ChooseLookStep(
    appearanceMode: AppearanceMode,
    onAppearanceMode: (AppearanceMode) -> Unit,
    glassLevel: Int,
    onGlassLevel: (Int) -> Unit,
    iconAppearance: IconAppearance,
    onIconAppearance: (IconAppearance) -> Unit,
) {
    val colors = currentDuoColors()
    val type = LocalDuoTypography.current

    SetupIntro(
        icon = Icons.Rounded.Palette,
        headline = "Pick a starting point",
        detail = "All of this lives in Duo Settings afterwards, so nothing here is permanent.",
    )

    SetupSectionLabel("Appearance")
    Column(verticalArrangement = Arrangement.spacedBy(DuoTokens.space.sm)) {
        SETUP_APPEARANCE_MODES.forEach { (mode, label) ->
            SetupChoiceRow(
                label = label,
                selected = appearanceMode == mode,
                testTag = "setup-appearance-${mode.name.lowercase()}",
                onClick = { onAppearanceMode(mode) },
            )
        }
    }

    SetupSectionLabel("Glass")
    Column {
        Slider(
            value = glassLevel.coerceIn(0, GLASS_MAX).toFloat(),
            onValueChange = { onGlassLevel(it.toInt().coerceIn(0, GLASS_MAX)) },
            valueRange = 0f..GLASS_MAX.toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = colors.accent,
                activeTrackColor = colors.accent,
                inactiveTrackColor = colors.separator,
            ),
            modifier = Modifier.fillMaxWidth().heightIn(min = MIN_TOUCH_TARGET)
                .testTag("setup-glass-level")
                .semantics {
                    contentDescription = "Glass, clear to tinted"
                    stateDescription = "${glassLevel.coerceIn(0, GLASS_MAX)} out of $GLASS_MAX"
                },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            SetupLabel("Clear", type.caption1, SetupTone.TERTIARY)
            SetupLabel("Tinted", type.caption1, SetupTone.TERTIARY)
        }
    }

    SetupSectionLabel("App icons")
    Column(verticalArrangement = Arrangement.spacedBy(DuoTokens.space.sm)) {
        SETUP_ICON_APPEARANCES.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(DuoTokens.space.sm)) {
                pair.forEach { (appearance, label) ->
                    SetupIconAppearanceTile(
                        appearance = appearance,
                        label = label,
                        selected = iconAppearance == appearance,
                        onClick = { onIconAppearance(appearance) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun BadgesStep(
    onSkipStep: () -> Unit,
    onOpenBadgeAccess: (() -> Unit)?,
    onOpenAppInfo: (() -> Unit)?,
) {
    val context = LocalContext.current
    val granted = rememberAccessState { NotificationAccess.isGranted(it) }
    val openAccess = onOpenBadgeAccess ?: { openNotificationAccess(context) }

    SetupIntro(
        icon = Icons.Rounded.Notifications,
        headline = if (granted) "Badges are on" else "Show a dot or a count on app icons",
        detail = "Badges appear on an app's icon while it has notifications waiting.",
    )
    SetupDisclosure(
        "To do that Duo reads only which app a notification came from and how many there are — " +
            "never the title or the text, and nothing is written to storage. The same Android " +
            "permission also lets the Now Playing widget show the media that is playing. Turning " +
            "notification access off in Android settings stops both immediately.",
    )
    if (granted) {
        SetupStatusRow("Notification access is granted.")
    } else {
        SetupActionButton(
            label = "Turn on badges",
            emphasis = SetupEmphasis.PRIMARY,
            testTag = "setup-badges-on",
            onClick = openAccess,
        )
        RestrictedSettingsNote(
            introduction = "If the switch won't stay on:",
            onOpenAppInfo = onOpenAppInfo,
        )
    }
    SetupActionButton(
        label = if (granted) "Continue" else "Skip this step",
        emphasis = SetupEmphasis.SECONDARY,
        testTag = "setup-skip-step",
        onClick = onSkipStep,
    )
}

@Composable
private fun GesturesStep(
    onSkipStep: () -> Unit,
    onOpenShadeAccess: (() -> Unit)?,
    onOpenAppInfo: (() -> Unit)?,
) {
    val context = LocalContext.current
    val enabled = rememberAccessState { shadeServiceEnabled(it) }
    val openAccess = onOpenShadeAccess ?: { openAccessibilitySettings(context) }

    SetupIntro(
        icon = Icons.Rounded.Lock,
        headline = if (enabled) "Gestures are on" else "Swipe for notifications, double-tap to lock",
        detail = "Swipe down on Home to open notifications, and double-tap empty space to lock " +
            "the screen.",
    )
    SetupDisclosure(
        "Android only offers these to a launcher through an accessibility service, so Duo ships " +
            "one. It performs those actions and nothing else: it cannot read window content, and " +
            "it stops listening to accessibility events as soon as it connects. You can turn it " +
            "off in Android Accessibility settings and keep using Duo.",
    )
    if (enabled) {
        SetupStatusRow("Duo's accessibility service is on.")
    } else {
        SetupActionButton(
            label = "Open Accessibility settings",
            emphasis = SetupEmphasis.PRIMARY,
            testTag = "setup-gestures-on",
            onClick = openAccess,
        )
        RestrictedSettingsNote(
            introduction = "If the switch is greyed out or won't stay on:",
            onOpenAppInfo = onOpenAppInfo,
        )
    }
    SetupActionButton(
        label = if (enabled) "Continue" else "Skip this step",
        emphasis = SetupEmphasis.SECONDARY,
        testTag = "setup-skip-step",
        onClick = onSkipStep,
    )
}

@Composable
private fun DoneStep(onAddWidget: () -> Unit) {
    SetupIntro(
        icon = Icons.Rounded.Check,
        headline = "Duo is ready",
        detail = "Long-press empty space to edit Home, or open Duo Settings to change anything " +
            "you skipped.",
    )
    SetupActionButton(
        label = "Add a widget",
        emphasis = SetupEmphasis.SECONDARY,
        testTag = "setup-add-widget",
        onClick = onAddWidget,
    )
}

/**
 * The sideloading trap, spelled out at the point of use (error table: *Sideloaded
 * restricted-settings block*).
 *
 * Duo is distributed as a GitHub release APK. Android treats notification access and accessibility
 * as "restricted settings" for any app installed outside an app store: the toggle appears, the user
 * moves it, and it silently snaps back with no explanation. Without this walkthrough the honest
 * conclusion for a real user is that the feature is broken, so the steps are shown up front rather
 * than hidden behind a "having trouble?" disclosure.
 */
@Composable
private fun RestrictedSettingsNote(introduction: String, onOpenAppInfo: (() -> Unit)?) {
    val context = LocalContext.current
    val colors = currentDuoColors()
    val type = LocalDuoTypography.current
    val openAppInfo = onOpenAppInfo ?: { openAppInfo(context) }

    Column(
        Modifier.fillMaxWidth()
            .clip(DuoTokens.radius.card)
            .background(colors.glassTint.copy(alpha = NOTE_FILL_ALPHA))
            .padding(DuoTokens.space.lg)
            .testTag("setup-restricted-settings"),
        verticalArrangement = Arrangement.spacedBy(DuoTokens.space.sm),
    ) {
        SetupLabel(introduction, type.subhead, SetupTone.PRIMARY)
        SetupLabel(
            "Android blocks these switches for apps installed outside an app store, like Duo's " +
                "GitHub release. It does not warn you — the switch just turns itself back off.",
            type.footnote,
            SetupTone.SECONDARY,
        )
        RESTRICTED_SETTINGS_STEPS.forEachIndexed { position, instruction ->
            Row(verticalAlignment = Alignment.Top) {
                SetupLabel("${position + 1}.", type.footnote, SetupTone.TERTIARY)
                Spacer(Modifier.width(DuoTokens.space.sm))
                SetupLabel(instruction, type.footnote, SetupTone.SECONDARY)
            }
        }
        SetupActionButton(
            label = "Open App info",
            emphasis = SetupEmphasis.SECONDARY,
            testTag = "setup-app-info",
            onClick = openAppInfo,
        )
    }
}

private val RESTRICTED_SETTINGS_STEPS = listOf(
    "Open App info (the button below).",
    "Tap the three-dot menu in the top corner.",
    "Choose Allow restricted settings, then come back and try the switch again.",
)

// ---------------------------------------------------------------------------
// Footer and header pieces
// ---------------------------------------------------------------------------

@Composable
internal fun SetupFooter(
    step: SetupStep,
    onContinue: () -> Unit,
    onExplore: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(DuoTokens.space.sm)) {
        if (step == SetupStep.DONE) {
            // The one control tagged "setup-explore" on this step; on every other step the
            // always-available escape below carries the tag instead, so exactly one exists.
            SetupActionButton(
                label = "Explore Home",
                emphasis = SetupEmphasis.PRIMARY,
                testTag = "setup-explore",
                onClick = onExplore,
            )
        } else {
            if (!step.optional) {
                SetupActionButton(
                    label = "Continue",
                    emphasis = SetupEmphasis.PRIMARY,
                    testTag = "setup-continue",
                    onClick = onContinue,
                )
            }
            SetupActionButton(
                label = "Explore Home",
                emphasis = SetupEmphasis.TERTIARY,
                testTag = "setup-explore",
                onClick = onExplore,
            )
        }
    }
}

@Composable
internal fun SetupCloseButton(onClose: () -> Unit) {
    val colors = currentDuoColors()
    Box(
        Modifier.size(MIN_TOUCH_TARGET)
            .clip(DuoTokens.radius.tile)
            .clickable(onClick = onClose, role = Role.Button)
            .semantics { contentDescription = "Close setup and go to Home" }
            .testTag("setup-close"),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Icon(
            imageVector = Icons.Rounded.Close,
            contentDescription = null,
            tint = colors.label2,
        )
    }
}

// ---------------------------------------------------------------------------
// Shared building blocks
// ---------------------------------------------------------------------------

internal enum class SetupTone { PRIMARY, SECONDARY, TERTIARY, ACCENT }

private enum class SetupEmphasis { PRIMARY, SECONDARY, TERTIARY }

@Composable
internal fun SetupLabel(
    text: String,
    style: TextStyle,
    tone: SetupTone,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
) {
    val colors = currentDuoColors()
    Text(
        text = text,
        style = style,
        color = when (tone) {
            SetupTone.PRIMARY -> colors.label1
            SetupTone.SECONDARY -> colors.label2
            SetupTone.TERTIARY -> colors.label3
            SetupTone.ACCENT -> colors.accent
        },
        maxLines = maxLines,
        // NFR-A3: at font scale 1.3 a long label ellipsizes rather than being clipped.
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

@Composable
private fun SetupSectionLabel(text: String) {
    SetupLabel(
        text = text,
        style = LocalDuoTypography.current.subhead,
        tone = SetupTone.SECONDARY,
        modifier = Modifier.padding(top = DuoTokens.space.sm),
    )
}

@Composable
private fun SetupIntro(icon: ImageVector, headline: String, detail: String) {
    val colors = currentDuoColors()
    val type = LocalDuoTypography.current
    Row(verticalAlignment = Alignment.Top) {
        androidx.compose.material3.Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.accent,
            modifier = Modifier.padding(top = DuoTokens.space.xxs).size(ICON_SIZE),
        )
        Spacer(Modifier.width(DuoTokens.space.md))
        Column(verticalArrangement = Arrangement.spacedBy(DuoTokens.space.xs)) {
            SetupLabel(headline, type.headline, SetupTone.PRIMARY)
            SetupLabel(detail, type.callout, SetupTone.SECONDARY)
        }
    }
}

/** The honest, small-print explanation of what an access actually does. */
@Composable
private fun SetupDisclosure(text: String) {
    SetupLabel(text, LocalDuoTypography.current.footnote, SetupTone.SECONDARY)
}

@Composable
private fun SetupStatusRow(text: String) {
    val colors = currentDuoColors()
    Row(
        Modifier.fillMaxWidth().heightIn(min = MIN_TOUCH_TARGET),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Icon(
            imageVector = Icons.Rounded.Check,
            contentDescription = null,
            tint = colors.accent,
            modifier = Modifier.size(ICON_SIZE),
        )
        Spacer(Modifier.width(DuoTokens.space.sm))
        SetupLabel(text, LocalDuoTypography.current.callout, SetupTone.PRIMARY)
    }
}

@Composable
private fun SetupActionButton(
    label: String,
    emphasis: SetupEmphasis,
    testTag: String,
    onClick: () -> Unit,
) {
    val colors = currentDuoColors()
    val type = LocalDuoTypography.current
    val shape = DuoTokens.radius.card
    val content: @Composable () -> Unit = {
        SetupLabel(
            text = label,
            style = if (emphasis == SetupEmphasis.PRIMARY) type.headline else type.callout,
            tone = if (emphasis == SetupEmphasis.TERTIARY) SetupTone.SECONDARY else SetupTone.ACCENT,
        )
    }
    val base = Modifier.fillMaxWidth().heightIn(min = MIN_TOUCH_TARGET)
        .clip(shape)
        .clickable(onClick = onClick, role = Role.Button)
        .testTag(testTag)

    when (emphasis) {
        SetupEmphasis.PRIMARY -> GlassSurface(level = GlassLevel.MENU, shape = shape, modifier = base) {
            Box(Modifier.fillMaxWidth().padding(DuoTokens.space.md), Alignment.Center) { content() }
        }
        SetupEmphasis.SECONDARY -> Box(
            base.border(HAIRLINE, colors.separator, shape).padding(DuoTokens.space.md),
            Alignment.Center,
        ) { content() }
        SetupEmphasis.TERTIARY -> Box(
            base.padding(DuoTokens.space.md),
            Alignment.Center,
        ) { content() }
    }
}

@Composable
private fun SetupChoiceRow(
    label: String,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit,
) {
    val colors = currentDuoColors()
    val shape = DuoTokens.radius.tile
    Row(
        Modifier.fillMaxWidth().heightIn(min = MIN_TOUCH_TARGET)
            .clip(shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .border(HAIRLINE, if (selected) colors.accent else colors.separator, shape)
            .padding(horizontal = DuoTokens.space.lg, vertical = DuoTokens.space.md)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SetupLabel(
            text = label,
            style = LocalDuoTypography.current.body,
            tone = SetupTone.PRIMARY,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            androidx.compose.material3.Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(ICON_SIZE),
            )
        }
    }
}

@Composable
private fun SetupIconAppearanceTile(
    appearance: IconAppearance,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = currentDuoColors()
    val shape = DuoTokens.radius.tile
    Column(
        modifier.heightIn(min = MIN_TOUCH_TARGET)
            .clip(shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .border(HAIRLINE, if (selected) colors.accent else colors.separator, shape)
            .padding(DuoTokens.space.md)
            .testTag("setup-icon-${appearance.name.lowercase()}"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(DuoTokens.space.sm),
    ) {
        // A miniature of what the appearance does to an icon. Decorative: the row already
        // announces its label and selected state to TalkBack.
        Box(
            Modifier.size(SWATCH_SIZE)
                .clip(DuoTokens.radius.iconRadiusFor(SWATCH_SIZE))
                .background(
                    when (appearance) {
                        IconAppearance.DEFAULT -> colors.accent
                        IconAppearance.DARK -> colors.ink.copy(alpha = SWATCH_DARK_ALPHA)
                        IconAppearance.CLEAR -> colors.glassTint.copy(alpha = SWATCH_CLEAR_ALPHA)
                        IconAppearance.TINTED -> colors.accent.copy(alpha = SWATCH_TINT_ALPHA)
                    },
                )
                .clearAndSetSemantics { },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(SWATCH_GLYPH_SIZE)
                    .clip(DuoTokens.radius.iconRadiusFor(SWATCH_GLYPH_SIZE))
                    .background(
                        when (appearance) {
                            IconAppearance.DEFAULT -> colors.glassTint
                            IconAppearance.DARK -> colors.label3
                            IconAppearance.CLEAR -> colors.label1
                            IconAppearance.TINTED -> colors.accent
                        },
                    ),
            )
        }
        SetupLabel(
            text = label,
            style = LocalDuoTypography.current.caption1,
            tone = if (selected) SetupTone.PRIMARY else SetupTone.SECONDARY,
            maxLines = 1,
        )
    }
}

// ---------------------------------------------------------------------------
// Live access state and the system screens it points at
// ---------------------------------------------------------------------------

/**
 * Re-reads an access grant whenever the launcher comes back to the foreground.
 *
 * The user leaves for a system Settings screen and returns; without this the step would still claim
 * the access is off. Both reads are cheap settings lookups, made once per resume.
 */
@Composable
private fun rememberAccessState(read: (Context) -> Boolean): Boolean {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var granted by remember(context) { mutableStateOf(runCatching { read(context) }.getOrDefault(false)) }
    DisposableEffect(owner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = runCatching { read(context) }.getOrDefault(false)
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return granted
}

/**
 * Whether Duo's shade-gesture accessibility service is enabled right now.
 *
 * Mirrors the check inside [SystemShadeAccessibilityService], which keeps its own copy private.
 */
private fun shadeServiceEnabled(context: Context): Boolean {
    val component = ComponentName(context, SystemShadeAccessibilityService::class.java)
    val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
    return runCatching {
        manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
            val service = it.resolveInfo?.serviceInfo ?: return@any false
            ComponentName(service.packageName, service.name) == component
        }
    }.getOrDefault(false)
}

private fun openNotificationAccess(context: Context) {
    // The per-app screen is not present on every build, so fall back to the full listener list.
    runCatching { context.startActivity(NotificationAccess.settingsIntent(context)) }
        .recoverCatching { context.startActivity(NotificationAccess.listSettingsIntent()) }
        .onFailure { context.toast("Notification settings are unavailable on this device.") }
}

private fun openAccessibilitySettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure { context.toast("Accessibility settings are unavailable on this device.") }
}

private fun openAppInfo(context: Context) {
    runCatching { context.startActivity(NotificationAccess.appInfoIntent(context)) }
        .onFailure { context.toast("App info is unavailable on this device.") }
}

private fun Context.toast(message: String) =
    Toast.makeText(this, message, Toast.LENGTH_LONG).show()

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

private val SETUP_APPEARANCE_MODES = listOf(
    AppearanceMode.LIGHT to "Light",
    AppearanceMode.DARK to "Dark",
    AppearanceMode.SYSTEM to "Automatic (follow system)",
)

private val SETUP_ICON_APPEARANCES = listOf(
    IconAppearance.DEFAULT to "Default",
    IconAppearance.DARK to "Dark",
    IconAppearance.CLEAR to "Clear",
    IconAppearance.TINTED to "Tinted",
)

private const val GLASS_MAX = 100

/** NFR-A1: nothing interactive in this flow is smaller than this. */
private val MIN_TOUCH_TARGET = 48.dp
private val ICON_SIZE = 22.dp
private val SWATCH_SIZE = 40.dp
private val SWATCH_GLYPH_SIZE = 18.dp
private val HAIRLINE = 1.dp
private const val NOTE_FILL_ALPHA = 0.5f
private const val SWATCH_DARK_ALPHA = 0.85f
private const val SWATCH_CLEAR_ALPHA = 0.35f
private const val SWATCH_TINT_ALPHA = 0.30f

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

@Composable
private fun PreviewStep(step: SetupStep, dark: Boolean = false, isDefaultHome: Boolean = false) {
    DuoTheme(dark = dark) {
        Box(Modifier.padding(DuoTokens.space.lg)) {
            SetupStepCard(
                step = step,
                isDefaultHome = isDefaultHome,
                onMakeDefault = {},
                onAddWidget = {},
                onSkipStep = {},
                appearanceMode = AppearanceMode.SYSTEM,
                onAppearanceMode = {},
                glassLevel = DEFAULT_GLASS_LEVEL,
                onGlassLevel = {},
                iconAppearance = IconAppearance.DEFAULT,
                onIconAppearance = {},
                onOpenBadgeAccess = {},
                onOpenShadeAccess = {},
                onOpenAppInfo = {},
            )
        }
    }
}

@Preview(name = "1 Set as Home", widthDp = 420)
@Composable
private fun SetHomeStepPreview() = PreviewStep(SetupStep.SET_AS_HOME)

@Preview(name = "1 Set as Home, already default", widthDp = 420)
@Composable
private fun SetHomeStepDonePreview() = PreviewStep(SetupStep.SET_AS_HOME, isDefaultHome = true)

@Preview(name = "2 Choose look", widthDp = 420, heightDp = 640)
@Composable
private fun ChooseLookStepPreview() = PreviewStep(SetupStep.CHOOSE_LOOK)

@Preview(name = "3 Badges", widthDp = 420, heightDp = 640)
@Composable
private fun BadgesStepPreview() = PreviewStep(SetupStep.BADGES)

@Preview(name = "4 Gestures and lock", widthDp = 420, heightDp = 640)
@Composable
private fun GesturesStepPreview() = PreviewStep(SetupStep.GESTURES)

@Preview(name = "5 Done", widthDp = 420)
@Composable
private fun DoneStepPreview() = PreviewStep(SetupStep.DONE)

@Preview(
    name = "3 Badges, dark",
    widthDp = 420,
    heightDp = 640,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun BadgesStepDarkPreview() = PreviewStep(SetupStep.BADGES, dark = true)

@Preview(name = "2 Choose look, font scale 1.3", widthDp = 420, heightDp = 760, fontScale = 1.3f)
@Composable
private fun ChooseLookStepLargeTextPreview() = PreviewStep(SetupStep.CHOOSE_LOOK)
