package com.jake.duolauncher.design

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/**
 * The single blurred backdrop layer (ADR-3, NFR-P6).
 *
 * The wallpaper is captured **once** into one graphics layer by [BlurBackdrop]; every
 * [GlassSurface] in the tree samples that same layer through [LocalDuoBackdrop] rather than
 * blurring its own backdrop. Six glass surfaces therefore cost one capture, not six.
 *
 * When blur is unavailable — API 31's known RenderNode invalidation bugs, a low-RAM device or
 * battery saver — [blurSupported] is false, the effect is skipped entirely, and glass renders as
 * tinted translucency. There is no crash and no blank surface.
 */
@Stable
class DuoBackdrop internal constructor(internal val hazeState: HazeState?) {
    /** False when this device or this moment cannot blur; surfaces then fall back to tint only. */
    val blurSupported: Boolean get() = hazeState != null
}

val LocalDuoBackdrop = staticCompositionLocalOf { DuoBackdrop(null) }

/** Creates the shared backdrop. Normally called for you by [BlurBackdrop]. */
@Composable
fun rememberDuoBackdrop(blurEnabled: Boolean = duoBlurSupported()): DuoBackdrop {
    // rememberHazeState must be called unconditionally; we simply discard it when blur is off.
    val hazeState = rememberHazeState(blurEnabled = blurEnabled)
    return remember(hazeState, blurEnabled) { DuoBackdrop(hazeState.takeIf { blurEnabled }) }
}

/**
 * Wraps the wallpaper (and anything else that should show through glass) and publishes it as the
 * one shared backdrop.
 *
 * Place this as high in the launcher tree as the wallpaper itself; every [GlassSurface] nested
 * inside it — dock, folders, menus, sheets, Today View, page indicator — samples it.
 */
@Composable
fun BlurBackdrop(
    modifier: Modifier = Modifier,
    backdrop: DuoBackdrop = rememberDuoBackdrop(),
    content: @Composable BoxScope.() -> Unit,
) {
    CompositionLocalProvider(LocalDuoBackdrop provides backdrop) {
        Box(modifier = modifier.then(backdrop.sourceModifier()), content = content)
    }
}

/** Marks this node's content as the backdrop that glass surfaces sample. */
internal fun DuoBackdrop.sourceModifier(): Modifier =
    hazeState?.let { Modifier.hazeSource(it) } ?: Modifier

/**
 * Draws the shared backdrop, blurred, behind the calling surface.
 *
 * Deliberately applies **no** tint: [GlassSurface] owns fill, rim and edge so that a surface looks
 * the same whether or not blur is available. This is the only place Haze is referenced besides
 * [BlurBackdrop], so the blur implementation can be swapped without touching the glass code.
 */
internal fun Modifier.duoBackdropEffect(backdrop: DuoBackdrop, shape: Shape, blurRadius: Dp): Modifier {
    val state = backdrop.hazeState ?: return this
    return this
        .clip(shape)
        .hazeEffect(state) {
            this.blurRadius = blurRadius
            this.backgroundColor = Color.Transparent
            this.tints = emptyList()
            this.fallbackTint = HazeTint(Color.Transparent)
            this.noiseFactor = 0f
        }
}

/** Whether this device should attempt backdrop blur at all. */
@Composable
fun duoBlurSupported(): Boolean {
    val context = LocalContext.current
    return remember(context) { duoBlurSupported(context) }
}

/**
 * Blur is declined on:
 * - API 31, where RenderNode invalidation for backdrop blur is unreliable
 * - low-RAM devices, where the extra layer is not affordable
 * - battery saver, matching the spec's error table
 */
fun duoBlurSupported(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S_V2) return false
    val lowRam = runCatching {
        context.getSystemService(ActivityManager::class.java)?.isLowRamDevice
    }.getOrNull() ?: false
    if (lowRam) return false
    val powerSaving = runCatching {
        context.getSystemService(PowerManager::class.java)?.isPowerSaveMode
    }.getOrNull() ?: false
    return !powerSaving
}
