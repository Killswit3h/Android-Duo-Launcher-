package com.jake.duolauncher.design

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.tooling.preview.Preview
import com.jake.duolauncher.DuoTheme

/**
 * A visual bench for the glass system: every [GlassLevel] over a photo-like backdrop, at a few
 * Glass slider values, in light and dark. Nothing in the launcher uses it; it exists so the glass
 * can be eyeballed in Android Studio's preview before it is wired into real screens.
 */
@Composable
fun GlassSurfaceGallery(
    glass: Int = DEFAULT_GLASS_LEVEL,
    reduceTransparency: Boolean = false,
    brightBackdrop: Boolean = false,
    modifier: Modifier = Modifier,
) {
    CompositionLocalProvider(
        LocalGlassLevel provides glass,
        LocalReduceTransparency provides reduceTransparency,
        LocalBrightBackdrop provides brightBackdrop,
    ) {
        BlurBackdrop(modifier = modifier.fillMaxSize()) {
            SampleWallpaper()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(DuoTokens.space.lg),
                verticalArrangement = Arrangement.spacedBy(DuoTokens.space.md),
            ) {
                GlassLevel.entries.forEach { level ->
                    GlassSample(level = level, glass = glass)
                }
            }
        }
    }
}

@Composable
private fun GlassSample(level: GlassLevel, glass: Int) {
    val colors = currentDuoColors()
    val shape = when (level) {
        GlassLevel.BAR -> DuoTokens.radius.dock
        GlassLevel.PANEL -> DuoTokens.radius.sheet
        GlassLevel.MENU -> DuoTokens.radius.card
        GlassLevel.WIDGET -> DuoTokens.radius.widget
    }
    GlassSurface(level = level, shape = shape, modifier = Modifier.fillMaxWidth()) {
        // Concentric: the inner content's corner stays parallel to the surface's own (FR-1).
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(DuoTokens.space.lg),
            verticalArrangement = Arrangement.spacedBy(DuoTokens.space.xs),
        ) {
            Text(text = level.name, style = DuoTokens.type.headline, color = colors.label1)
            Text(
                text = "Glass $glass · blur ${level.blurRadius.value.toInt()}dp · " +
                    "inner radius ${(shape as DuoRadius).minus(DuoTokens.space.lg).dp.value.toInt()}dp",
                style = DuoTokens.type.footnote,
                color = colors.label2,
            )
            Text(text = "12:45", style = DuoTokens.type.clock, color = colors.label1)
        }
    }
}

/** Stands in for a photo wallpaper: a warm-to-cool wash with a bright corner highlight. */
@Composable
private fun SampleWallpaper() {
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
                    radius = SAMPLE_HIGHLIGHT_RADIUS,
                ),
            ),
    )
}

private const val SAMPLE_HIGHLIGHT_RADIUS = 700f

@Preview(name = "Glass levels", widthDp = 420, heightDp = 760)
@Composable
private fun GlassSurfaceGalleryPreview() {
    DuoTheme(dark = false) { GlassSurfaceGallery() }
}

@Preview(
    name = "Glass levels, dark",
    widthDp = 420,
    heightDp = 760,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun GlassSurfaceGalleryDarkPreview() {
    DuoTheme(dark = true) { GlassSurfaceGallery() }
}

@Preview(name = "Glass 0, bright wallpaper", widthDp = 420, heightDp = 760)
@Composable
private fun GlassSurfaceGalleryClearPreview() {
    DuoTheme(dark = false) { GlassSurfaceGallery(glass = 0, brightBackdrop = true) }
}

@Preview(name = "Reduce transparency", widthDp = 420, heightDp = 760)
@Composable
private fun GlassSurfaceGalleryOpaquePreview() {
    DuoTheme(dark = false) { GlassSurfaceGallery(reduceTransparency = true) }
}
