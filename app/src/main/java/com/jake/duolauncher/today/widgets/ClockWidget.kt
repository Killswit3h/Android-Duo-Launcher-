package com.jake.duolauncher.today.widgets

import android.content.res.Configuration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jake.duolauncher.DuoTheme
import com.jake.duolauncher.design.DuoTokens
import com.jake.duolauncher.design.currentDuoColors
import com.jake.duolauncher.design.rememberMotionEnabled
import com.jake.duolauncher.today.TodayClockStyle
import com.jake.duolauncher.today.TodayWidgetSize
import com.jake.duolauncher.today.builtin.ClockTime
import com.jake.duolauncher.today.hourHandDegrees
import com.jake.duolauncher.today.minuteHandDegrees
import com.jake.duolauncher.today.secondHandDegrees
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The Clock widget (FR-59), analog or digital, at all four spans.
 *
 * **Why there is no timer in here.** `ClockFeed` republishes on the system's minute broadcast, so
 * the hour and minute hands need nothing else. A sweeping second hand needs finer granularity than
 * any broadcast provides, and polling for it would cost a wake-up per second on a surface that is
 * usually not even on screen. Instead the feed hands over the exact instant of each tick and
 * [rememberSweepSeconds] interpolates forward from it on Compose's own frame clock. An idle
 * launcher therefore does zero periodic work, and the sweep stops the moment the composition does.
 */
@Composable
fun ClockWidget(
    time: ClockTime,
    style: TodayClockStyle,
    size: TodayWidgetSize,
    modifier: Modifier = Modifier,
    use24Hour: Boolean = false,
    onClick: () -> Unit = {},
) {
    val label = "Clock, ${spokenTime(time, use24Hour)}"
    TodayWidgetFrame(
        size = size,
        modifier = modifier.clickable(onClick = onClick),
        semanticsLabel = label,
    ) {
        TodayWidgetCaption(icon = Icons.Rounded.Schedule, label = "Clock")
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when (style) {
                TodayClockStyle.ANALOG -> AnalogFace(
                    time = time,
                    showSeconds = size != TodayWidgetSize.SMALL,
                    modifier = Modifier.fillMaxSize().wrapContentSize().aspectRatio(1f),
                )

                TodayClockStyle.DIGITAL -> DigitalFace(
                    time = time,
                    use24Hour = use24Hour,
                    size = size,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * The seconds-past-the-minute to draw, interpolated from the feed's tick on the frame clock.
 *
 * The anchor is the tick's own [ClockTime.secondOfMinute] plus the sub-second remainder of its
 * [ClockTime.epochMillis], so the hand starts exactly where real time is rather than at a rounded
 * second. From there it advances by elapsed frame time until the next tick re-anchors it, which
 * keeps it from drifting.
 *
 * Returns a [State] rather than a `Float` on purpose: the caller reads it inside a draw lambda, so
 * a new frame redraws the face without recomposing anything.
 *
 * When [animate] is false — the system animator duration scale is 0 (FR-11, NFR-A4), or the span is
 * too small for a second hand — the anchor is published once and no frame is ever requested.
 */
@Composable
private fun rememberSweepSeconds(time: ClockTime, animate: Boolean): State<Float> {
    val seconds = remember { mutableFloatStateOf(time.secondOfMinute.toFloat()) }
    LaunchedEffect(time, animate) {
        val fraction = Math.floorMod(time.epochMillis, MILLIS_PER_SECOND).toFloat() / MILLIS_PER_SECOND
        val anchor = time.secondOfMinute + fraction
        seconds.floatValue = anchor
        if (!animate) return@LaunchedEffect
        val start = withFrameNanos { it }
        while (true) {
            val frame = withFrameNanos { it }
            seconds.floatValue = anchor + (frame - start) / NANOS_PER_SECOND
        }
    }
    return seconds
}

@Composable
private fun AnalogFace(
    time: ClockTime,
    showSeconds: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = currentDuoColors()
    val motionEnabled = rememberMotionEnabled()
    val sweep = rememberSweepSeconds(time, animate = showSeconds && motionEnabled)
    val hour = time.hour12
    val minute = time.minute

    Canvas(modifier = modifier) {
        val radius = size.minDimension / 2f
        if (radius <= 0f) return@Canvas
        val center = Offset(size.width / 2f, size.height / 2f)

        drawCircle(
            color = colors.label3,
            radius = radius - radius * DIAL_INSET,
            center = center,
            style = Stroke(width = radius * DIAL_STROKE),
        )
        repeat(HOUR_TICKS) { index ->
            rotate(degrees = index * (360f / HOUR_TICKS), pivot = center) {
                drawLine(
                    color = colors.label2,
                    start = Offset(center.x, center.y - radius * TICK_INNER),
                    end = Offset(center.x, center.y - radius * TICK_OUTER),
                    strokeWidth = radius * TICK_STROKE,
                    cap = StrokeCap.Round,
                )
            }
        }

        val seconds = sweep.value
        rotate(degrees = hourHandDegrees(hour, minute), pivot = center) {
            drawLine(
                color = colors.label1,
                start = center,
                end = Offset(center.x, center.y - radius * HOUR_HAND),
                strokeWidth = radius * HOUR_STROKE,
                cap = StrokeCap.Round,
            )
        }
        rotate(degrees = minuteHandDegrees(minute, seconds), pivot = center) {
            drawLine(
                color = colors.label1,
                start = center,
                end = Offset(center.x, center.y - radius * MINUTE_HAND),
                strokeWidth = radius * MINUTE_STROKE,
                cap = StrokeCap.Round,
            )
        }
        if (showSeconds) {
            rotate(degrees = secondHandDegrees(seconds), pivot = center) {
                drawLine(
                    color = colors.accent,
                    start = Offset(center.x, center.y + radius * SECOND_TAIL),
                    end = Offset(center.x, center.y - radius * SECOND_HAND),
                    strokeWidth = radius * SECOND_STROKE,
                    cap = StrokeCap.Round,
                )
            }
        }
        drawCircle(color = colors.accent, radius = radius * HUB_RADIUS, center = center)
    }
}

@Composable
private fun DigitalFace(
    time: ClockTime,
    use24Hour: Boolean,
    size: TodayWidgetSize,
    modifier: Modifier = Modifier,
) {
    val colors = currentDuoColors()
    val style = if (size == TodayWidgetSize.SMALL) DuoTokens.type.clock else DuoTokens.type.clockLarge
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(DuoTokens.space.xxs),
    ) {
        Text(
            text = time.dateTime.format(hourMinuteFormatter(use24Hour)),
            style = style,
            color = colors.label1,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
        if (!use24Hour) {
            Text(
                text = if (time.isBeforeNoon) "AM" else "PM",
                style = DuoTokens.type.footnote,
                color = colors.label2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun hourMinuteFormatter(use24Hour: Boolean): DateTimeFormatter =
    DateTimeFormatter.ofPattern(if (use24Hour) "HH:mm" else "h:mm")

/** What TalkBack reads for the whole widget: the time, spoken, not the glyphs on a dial. */
private fun spokenTime(time: ClockTime, use24Hour: Boolean): String =
    time.dateTime.format(DateTimeFormatter.ofPattern(if (use24Hour) "HH:mm" else "h:mm a"))

private const val MILLIS_PER_SECOND = 1000L
private const val NANOS_PER_SECOND = 1_000_000_000f
private const val HOUR_TICKS = 12
private const val DIAL_INSET = 0.02f
private const val DIAL_STROKE = 0.035f
private const val TICK_INNER = 0.80f
private const val TICK_OUTER = 0.92f
private const val TICK_STROKE = 0.035f
private const val HOUR_HAND = 0.50f
private const val HOUR_STROKE = 0.085f
private const val MINUTE_HAND = 0.74f
private const val MINUTE_STROKE = 0.060f
private const val SECOND_HAND = 0.80f
private const val SECOND_TAIL = 0.16f
private const val SECOND_STROKE = 0.028f
private const val HUB_RADIUS = 0.055f

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

internal val PreviewClockTime: ClockTime = ClockTime(
    epochMillis = Instant.parse("2026-09-15T09:41:32Z").toEpochMilli(),
    zone = ZoneId.of("UTC"),
)

@Preview(name = "Clock analog 2x2", widthDp = 200, heightDp = 200)
@Composable
private fun ClockAnalogSmallPreview() {
    DuoTheme { TodayPreviewBackdrop { ClockWidget(PreviewClockTime, TodayClockStyle.ANALOG, TodayWidgetSize.SMALL, Modifier.size(180.dp)) } }
}

@Preview(name = "Clock analog 4x2", widthDp = 400, heightDp = 220)
@Composable
private fun ClockAnalogMediumPreview() {
    DuoTheme { TodayPreviewBackdrop { ClockWidget(PreviewClockTime, TodayClockStyle.ANALOG, TodayWidgetSize.MEDIUM, Modifier.size(380.dp, 190.dp)) } }
}

@Preview(name = "Clock analog 4x4", widthDp = 400, heightDp = 420)
@Composable
private fun ClockAnalogLargePreview() {
    DuoTheme { TodayPreviewBackdrop { ClockWidget(PreviewClockTime, TodayClockStyle.ANALOG, TodayWidgetSize.LARGE, Modifier.size(380.dp, 380.dp)) } }
}

@Preview(name = "Clock analog 4x6", widthDp = 400, heightDp = 600)
@Composable
private fun ClockAnalogExtraLargePreview() {
    DuoTheme { TodayPreviewBackdrop { ClockWidget(PreviewClockTime, TodayClockStyle.ANALOG, TodayWidgetSize.EXTRA_LARGE, Modifier.size(380.dp, 560.dp)) } }
}

@Preview(name = "Clock digital 2x2", widthDp = 200, heightDp = 200)
@Composable
private fun ClockDigitalSmallPreview() {
    DuoTheme { TodayPreviewBackdrop { ClockWidget(PreviewClockTime, TodayClockStyle.DIGITAL, TodayWidgetSize.SMALL, Modifier.size(180.dp)) } }
}

@Preview(name = "Clock digital 4x2", widthDp = 400, heightDp = 220)
@Composable
private fun ClockDigitalMediumPreview() {
    DuoTheme { TodayPreviewBackdrop { ClockWidget(PreviewClockTime, TodayClockStyle.DIGITAL, TodayWidgetSize.MEDIUM, Modifier.size(380.dp, 190.dp)) } }
}

@Preview(name = "Clock digital 4x4", widthDp = 400, heightDp = 420)
@Composable
private fun ClockDigitalLargePreview() {
    DuoTheme { TodayPreviewBackdrop { ClockWidget(PreviewClockTime, TodayClockStyle.DIGITAL, TodayWidgetSize.LARGE, Modifier.size(380.dp, 380.dp)) } }
}

@Preview(name = "Clock digital 4x6, dark", widthDp = 400, heightDp = 600, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ClockDigitalExtraLargePreview() {
    DuoTheme(dark = true) { TodayPreviewBackdrop { ClockWidget(PreviewClockTime, TodayClockStyle.DIGITAL, TodayWidgetSize.EXTRA_LARGE, Modifier.size(380.dp, 560.dp)) } }
}

@Preview(name = "Clock 4x2, font scale 1.3", widthDp = 400, heightDp = 220, fontScale = 1.3f)
@Composable
private fun ClockFontScalePreview() {
    DuoTheme { TodayPreviewBackdrop { ClockWidget(PreviewClockTime, TodayClockStyle.DIGITAL, TodayWidgetSize.MEDIUM, Modifier.size(380.dp, 190.dp)) } }
}
