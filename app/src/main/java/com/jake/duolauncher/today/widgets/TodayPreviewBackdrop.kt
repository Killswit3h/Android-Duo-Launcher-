package com.jake.duolauncher.today.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import com.jake.duolauncher.design.BlurBackdrop
import com.jake.duolauncher.design.DuoSampleBackdrop
import com.jake.duolauncher.design.DuoSampleBackdropHighlight
import com.jake.duolauncher.design.DuoTokens

/**
 * The wallpaper stand-in every Today View preview sits on.
 *
 * Glass is meaningless over a flat colour — the point of [com.jake.duolauncher.design.GlassSurface]
 * is what shows through it — so the previews reuse the same fixture wash the design package's own
 * gallery uses rather than inventing a second one. Those fixtures are `internal`, which is
 * module-scoped in Kotlin, so this reads them without adding anything to `design/`.
 *
 * This exists only for `@Preview`; nothing in the launcher calls it.
 */
@Composable
internal fun TodayPreviewBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    BlurBackdrop(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.linearGradient(DuoSampleBackdrop))
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            DuoSampleBackdropHighlight,
                            DuoSampleBackdropHighlight.copy(alpha = 0f),
                        ),
                        center = Offset.Zero,
                        radius = PREVIEW_HIGHLIGHT_RADIUS,
                    ),
                ),
        )
        Box(
            modifier = Modifier.fillMaxSize().padding(DuoTokens.space.md),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

private const val PREVIEW_HIGHLIGHT_RADIUS = 700f
