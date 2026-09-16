package com.jake.duolauncher

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntOffset
import com.jake.duolauncher.design.DEFAULT_GLASS_LEVEL
import com.jake.duolauncher.design.DuoTokens
import com.jake.duolauncher.design.LocalDuoTypography
import com.jake.duolauncher.design.rememberDuoTypography
import com.jake.duolauncher.design.rememberMotionEnabled
import com.jake.duolauncher.icons.IconAppearance

internal enum class SetupEntryDecision { SHOW, ALREADY_FINISHED, EXISTING_INSTALL }

internal fun setupEntryDecision(
    finished: Boolean,
    started: Boolean,
    hadLauncherState: Boolean,
): SetupEntryDecision = when {
    finished -> SetupEntryDecision.ALREADY_FINISHED
    started -> SetupEntryDecision.SHOW
    hadLauncherState -> SetupEntryDecision.EXISTING_INSTALL
    else -> SetupEntryDecision.SHOW
}

/** Setup is intentionally separate from launcher state so it cannot rewrite an upgraded layout. */
internal class SetupExperience(context: Context, prefsName: String = PREFS) {
    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    fun entryDecision(hadLauncherState: Boolean): SetupEntryDecision {
        val decision = setupEntryDecision(
            finished = prefs.getBoolean(FINISHED, false),
            started = prefs.getBoolean(STARTED, false),
            hadLauncherState = hadLauncherState,
        )
        // A fresh launch writes only setup state. This keeps the cohort stable after the
        // launcher creates its normal layout preferences or Android recreates the activity.
        if (decision == SetupEntryDecision.SHOW && !prefs.getBoolean(STARTED, false))
            prefs.edit().putBoolean(STARTED, true).commit()
        return decision
    }

    fun finish() {
        // Finish before dismissing the sheet so process death cannot make it reappear.
        prefs.edit().putBoolean(FINISHED, true).commit()
    }

    companion object {
        private const val PREFS = "setup_experience"
        private const val FINISHED = "finished"
        private const val STARTED = "started"

        fun hadLauncherState(context: Context): Boolean =
            context.getSharedPreferences("launcher", Context.MODE_PRIVATE).all.isNotEmpty() ||
                context.getSharedPreferences("app_catalog", Context.MODE_PRIVATE).all.isNotEmpty()
    }
}

/**
 * The Liquid Glass welcome flow shown on a fresh install only (FR-81).
 *
 * Steps run **Set as Home → Choose look → Turn on badges → Gestures and lock → Done**. Every step
 * after the first is optional: each one offers Skip, and **Explore Home** leaves the whole flow at
 * any point. Skipping everything leaves a fully working launcher, because nothing here is required
 * for Home to draw — the two access steps only ask for optional grants (AC-65).
 *
 * The parameter list is source-frozen for `FirstRunSheetHost` in `home/HomeSheets.kt`: the first
 * five parameters keep their names, types and meanings, and everything added since carries a
 * default so existing call sites compile untouched.
 *
 * The **Choose look** selections are hoisted rather than persisted here. This composable owns no
 * settings storage; it reports the user's choice through [onAppearanceMode], [onGlassLevel] and
 * [onIconAppearance] so the launcher's own settings store is the only writer.
 */
@Composable
internal fun FirstRunSetupSheet(
    isDefaultHome: Boolean,
    onMakeDefault: () -> Unit,
    onAddWidget: () -> Unit,
    onExplore: () -> Unit,
    onSkip: () -> Unit,
    appearanceMode: AppearanceMode = AppearanceMode.SYSTEM,
    onAppearanceMode: (AppearanceMode) -> Unit = {},
    glassLevel: Int = DEFAULT_GLASS_LEVEL,
    onGlassLevel: (Int) -> Unit = {},
    iconAppearance: IconAppearance = IconAppearance.DEFAULT,
    onIconAppearance: (IconAppearance) -> Unit = {},
    /** Overrides the flow's own launch of Duo's notification-access screen. */
    onOpenBadgeAccess: (() -> Unit)? = null,
    /** Overrides the flow's own launch of Android's Accessibility settings. */
    onOpenShadeAccess: (() -> Unit)? = null,
    /** Overrides the flow's own launch of Duo's App info page (restricted settings). */
    onOpenAppInfo: (() -> Unit)? = null,
) {
    val steps = SetupStep.entries
    var stepIndex by rememberSaveable { mutableIntStateOf(0) }
    val index = stepIndex.coerceIn(0, steps.lastIndex)
    val step = steps[index]
    val motionEnabled = rememberMotionEnabled()
    val maxHeight = with(LocalDensity.current) {
        (LocalWindowInfo.current.containerSize.height * .9f).toDp()
    }

    CompositionLocalProvider(LocalDuoTypography provides rememberDuoTypography()) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = maxHeight).verticalScroll(rememberScrollState())
                .navigationBarsPadding().padding(horizontal = DuoTokens.space.xxl)
                .padding(bottom = DuoTokens.space.xl),
            verticalArrangement = Arrangement.spacedBy(DuoTokens.space.lg),
        ) {
            SetupHeader(step = step, index = index, total = steps.size, onClose = onSkip)

            AnimatedContent(
                targetState = index,
                transitionSpec = { setupStepTransition(motionEnabled, forward = targetState >= initialState) },
                label = "setup-step",
            ) { animatedIndex ->
                SetupStepCard(
                    step = steps[animatedIndex.coerceIn(0, steps.lastIndex)],
                    isDefaultHome = isDefaultHome,
                    onMakeDefault = onMakeDefault,
                    onAddWidget = onAddWidget,
                    onSkipStep = { stepIndex = (index + 1).coerceAtMost(steps.lastIndex) },
                    appearanceMode = appearanceMode,
                    onAppearanceMode = onAppearanceMode,
                    glassLevel = glassLevel,
                    onGlassLevel = onGlassLevel,
                    iconAppearance = iconAppearance,
                    onIconAppearance = onIconAppearance,
                    onOpenBadgeAccess = onOpenBadgeAccess,
                    onOpenShadeAccess = onOpenShadeAccess,
                    onOpenAppInfo = onOpenAppInfo,
                )
            }

            SetupFooter(
                step = step,
                onContinue = { stepIndex = (index + 1).coerceAtMost(steps.lastIndex) },
                onExplore = onExplore,
            )
        }
    }
}

/**
 * Step-to-step motion (FR-10), suppressed entirely while the animator duration scale is 0 (FR-11).
 */
private fun AnimatedContentTransitionScope<Int>.setupStepTransition(
    motionEnabled: Boolean,
    forward: Boolean,
): ContentTransform = if (!motionEnabled) {
    fadeIn(snap()) togetherWith fadeOut(snap()) using
        SizeTransform(clip = false, sizeAnimationSpec = { _, _ -> snap() })
} else {
    val offset = if (forward) 1 else -1
    (
        fadeIn(DuoTokens.motion.standard()) +
            slideInHorizontally(DuoTokens.motion.standard<IntOffset>()) { width -> offset * width / SLIDE_FRACTION }
        ) togetherWith (
        fadeOut(DuoTokens.motion.standard()) +
            slideOutHorizontally(DuoTokens.motion.standard<IntOffset>()) { width -> -offset * width / SLIDE_FRACTION }
        ) using SizeTransform(clip = false, sizeAnimationSpec = { _, _ -> DuoTokens.motion.standard() })
}

/** Steps slide by a fraction of their width, so the outgoing card stays legible as it leaves. */
private const val SLIDE_FRACTION = 6

@Composable
private fun SetupHeader(step: SetupStep, index: Int, total: Int, onClose: () -> Unit) {
    val type = LocalDuoTypography.current
    Row(Modifier.fillMaxWidth().padding(top = DuoTokens.space.sm), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            SetupLabel(text = step.title, style = type.title2, tone = SetupTone.PRIMARY)
            SetupLabel(
                text = "Step ${index + 1} of $total",
                style = type.footnote,
                tone = SetupTone.TERTIARY,
            )
        }
        Spacer(Modifier.width(DuoTokens.space.md))
        SetupCloseButton(onClose = onClose)
    }
}
