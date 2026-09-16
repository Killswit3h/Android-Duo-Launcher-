package com.jake.duolauncher.today.widgets

import android.content.res.Configuration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jake.duolauncher.DuoTheme
import com.jake.duolauncher.design.DuoTokens
import com.jake.duolauncher.design.currentDuoColors
import com.jake.duolauncher.today.TodayWidgetSize
import com.jake.duolauncher.today.builtin.BatteryReading

/**
 * The Batteries widget (FR-59): the device's charge, and whether it is charging.
 *
 * **A null level is not zero.** `BatteryReading.level` is null until the first battery broadcast
 * lands, and rendering that as an empty ring at 0% would tell the user their phone is about to die.
 * So an unknown reading draws a full-circle track with an em dash, and says so to TalkBack.
 */
@Composable
fun BatteriesWidget(
    reading: BatteryReading,
    size: TodayWidgetSize,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    val colors = currentDuoColors()
    TodayWidgetFrame(
        size = size,
        modifier = modifier.clickable(onClick = onClick),
        semanticsLabel = spokenBattery(reading),
    ) {
        TodayWidgetCaption(icon = Icons.Rounded.BatteryFull, label = "Batteries")
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (size == TodayWidgetSize.SMALL) {
                BatteryRing(reading = reading, modifier = Modifier.fillMaxSize().wrapContentSize().aspectRatio(1f))
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(DuoTokens.space.lg),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BatteryRing(
                        reading = reading,
                        modifier = Modifier.fillMaxSize().wrapContentSize().aspectRatio(1f),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "This device",
                            style = DuoTokens.type.subhead,
                            color = colors.label1,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = statusLine(reading),
                            style = DuoTokens.type.footnote,
                            color = colors.label2,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BatteryRing(
    reading: BatteryReading,
    modifier: Modifier = Modifier,
) {
    val colors = currentDuoColors()
    val level = reading.level
    val fraction = (level ?: 0).coerceIn(0, 100) / 100f

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = size.minDimension * RING_STROKE
            val inset = stroke / 2f
            drawArc(
                color = colors.separator,
                startAngle = RING_START,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            if (level != null) {
                drawArc(
                    color = colors.accent,
                    startAngle = RING_START,
                    sweepAngle = 360f * fraction,
                    useCenter = false,
                    topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                    size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(DuoTokens.space.xxs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (reading.charging) {
                Icon(
                    imageVector = Icons.Rounded.Bolt,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(CHARGING_GLYPH),
                )
            }
            Text(
                text = if (level != null) "$level%" else "—",
                style = DuoTokens.type.clock,
                color = colors.label1,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun statusLine(reading: BatteryReading): String = when {
    !reading.isKnown -> "Level unavailable"
    reading.charging -> "Charging"
    else -> "On battery"
}

private fun spokenBattery(reading: BatteryReading): String {
    val level = reading.level ?: return "Batteries, level unavailable"
    return if (reading.charging) "Batteries, $level percent, charging" else "Batteries, $level percent"
}

private const val RING_STROKE = 0.10f
private const val RING_START = -90f
private val CHARGING_GLYPH = 16.dp

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

private val PreviewBattery = BatteryReading(level = 72, charging = false)
private val PreviewCharging = BatteryReading(level = 41, charging = true)

@Preview(name = "Batteries 2x2", widthDp = 200, heightDp = 200)
@Composable
private fun BatteriesSmallPreview() {
    DuoTheme { TodayPreviewBackdrop { BatteriesWidget(PreviewBattery, TodayWidgetSize.SMALL, Modifier.size(180.dp)) } }
}

@Preview(name = "Batteries 4x2", widthDp = 400, heightDp = 220)
@Composable
private fun BatteriesMediumPreview() {
    DuoTheme { TodayPreviewBackdrop { BatteriesWidget(PreviewCharging, TodayWidgetSize.MEDIUM, Modifier.size(380.dp, 190.dp)) } }
}

@Preview(name = "Batteries 4x4", widthDp = 400, heightDp = 420)
@Composable
private fun BatteriesLargePreview() {
    DuoTheme { TodayPreviewBackdrop { BatteriesWidget(PreviewBattery, TodayWidgetSize.LARGE, Modifier.size(380.dp, 380.dp)) } }
}

@Preview(name = "Batteries 4x6", widthDp = 400, heightDp = 600)
@Composable
private fun BatteriesExtraLargePreview() {
    DuoTheme { TodayPreviewBackdrop { BatteriesWidget(PreviewBattery, TodayWidgetSize.EXTRA_LARGE, Modifier.size(380.dp, 560.dp)) } }
}

@Preview(name = "Batteries, unknown level", widthDp = 400, heightDp = 220)
@Composable
private fun BatteriesUnknownPreview() {
    DuoTheme { TodayPreviewBackdrop { BatteriesWidget(BatteryReading.Unknown, TodayWidgetSize.MEDIUM, Modifier.size(380.dp, 190.dp)) } }
}

@Preview(name = "Batteries 2x2, dark", widthDp = 200, heightDp = 200, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun BatteriesDarkPreview() {
    DuoTheme(dark = true) { TodayPreviewBackdrop { BatteriesWidget(PreviewCharging, TodayWidgetSize.SMALL, Modifier.size(180.dp)) } }
}
