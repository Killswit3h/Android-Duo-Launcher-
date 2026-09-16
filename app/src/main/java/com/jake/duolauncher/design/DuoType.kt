package com.jake.duolauncher.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.jake.duolauncher.R

/**
 * The launcher's type scale (FR-13).
 *
 * Duo ships Inter (SIL OFL 1.1) as a variable font. Clock styles carry tabular figures so digits
 * do not shuffle as the time ticks. **Use system font** swaps the family for the device font
 * without changing any size, weight or line height.
 */
enum class DuoFont {
    /** Bundled Inter variable font. The default. */
    INTER,
    /** The device's own font, for users who prefer it or need their system font settings honored. */
    SYSTEM,
}

@Immutable
data class DuoTypography(
    val largeTitle: TextStyle,
    val title1: TextStyle,
    val title2: TextStyle,
    val title3: TextStyle,
    val headline: TextStyle,
    val body: TextStyle,
    val callout: TextStyle,
    val subhead: TextStyle,
    val footnote: TextStyle,
    val caption1: TextStyle,
    val caption2: TextStyle,
    /** Home and dock icon labels. */
    val iconLabel: TextStyle,
    /** Status-cluster and widget clocks: tabular figures (FR-13). */
    val clock: TextStyle,
    /** Large widget clock face: tabular figures. */
    val clockLarge: TextStyle,
) {
    /** The same scale rendered in another family, for the Use system font switch. */
    fun withFamily(family: FontFamily?): DuoTypography = DuoTypography(
        largeTitle = largeTitle.copy(fontFamily = family),
        title1 = title1.copy(fontFamily = family),
        title2 = title2.copy(fontFamily = family),
        title3 = title3.copy(fontFamily = family),
        headline = headline.copy(fontFamily = family),
        body = body.copy(fontFamily = family),
        callout = callout.copy(fontFamily = family),
        subhead = subhead.copy(fontFamily = family),
        footnote = footnote.copy(fontFamily = family),
        caption1 = caption1.copy(fontFamily = family),
        caption2 = caption2.copy(fontFamily = family),
        iconLabel = iconLabel.copy(fontFamily = family),
        clock = clock.copy(fontFamily = family),
        clockLarge = clockLarge.copy(fontFamily = family),
    )
}

/**
 * Inter, bundled as a single variable font file. Each weight is the same resource with a different
 * `wght` axis value, so the APK carries one face instead of nine.
 */
val InterFontFamily: FontFamily = FontFamily(
    interFont(FontWeight.Light),
    interFont(FontWeight.Normal),
    interFont(FontWeight.Medium),
    interFont(FontWeight.SemiBold),
    interFont(FontWeight.Bold),
)

// FontVariation is still experimental in this Compose version; the axis API itself is stable enough
// to depend on, and the alternative is shipping five static font files instead of one variable face.
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun interFont(weight: FontWeight): Font = Font(
    resId = R.font.inter_variable,
    weight = weight,
    style = FontStyle.Normal,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

/** The scale, in the given family. [DuoFont.SYSTEM] leaves the family null so Android decides. */
fun duoTypography(font: DuoFont = DuoFont.INTER): DuoTypography =
    BaseDuoTypography.withFamily(if (font == DuoFont.INTER) InterFontFamily else null)

/** FR-13's **Use system font** switch. */
@Composable
fun rememberDuoTypography(useSystemFont: Boolean = false): DuoTypography =
    remember(useSystemFont) { duoTypography(if (useSystemFont) DuoFont.SYSTEM else DuoFont.INTER) }

val LocalDuoTypography = staticCompositionLocalOf { InterDuoTypography }

internal val InterDuoTypography: DuoTypography = duoTypography(DuoFont.INTER)

/** Sizes, weights and line heights, family-agnostic. */
private val BaseDuoTypography = DuoTypography(
    largeTitle = TextStyle(fontSize = 34.sp, lineHeight = 41.sp, fontWeight = FontWeight.Bold),
    title1 = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold),
    title2 = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    title3 = TextStyle(fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.Medium),
    headline = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    body = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal),
    callout = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal),
    subhead = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    footnote = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal),
    caption1 = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal),
    caption2 = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium),
    iconLabel = TextStyle(fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium),
    clock = TextStyle(
        fontSize = 20.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.Medium,
        fontFeatureSettings = TABULAR_FIGURES,
    ),
    clockLarge = TextStyle(
        fontSize = 44.sp,
        lineHeight = 48.sp,
        fontWeight = FontWeight.Light,
        fontFeatureSettings = TABULAR_FIGURES,
    ),
)

/** OpenType feature for fixed-width digits, so a ticking clock does not jitter. */
private const val TABULAR_FIGURES = "tnum"
