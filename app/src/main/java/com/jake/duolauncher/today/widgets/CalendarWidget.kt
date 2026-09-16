package com.jake.duolauncher.today.widgets

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jake.duolauncher.DuoTheme
import com.jake.duolauncher.design.DuoTokens
import com.jake.duolauncher.design.currentDuoColors
import com.jake.duolauncher.today.TodayWidgetSize
import com.jake.duolauncher.today.builtin.CALENDAR_EVENT_COUNT
import com.jake.duolauncher.today.builtin.CalendarEvent
import com.jake.duolauncher.today.builtin.CalendarFeedState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale

/**
 * The Date and Calendar widget (FR-59).
 *
 * The date is unconditional and the events are not, which is the whole reason the feed carries
 * `today` in every one of its states: losing calendar access costs you the event list, never the
 * widget. The three states differ only in what sits under the date —
 *
 *  * [CalendarFeedState.Events] — up to the next three, by span.
 *  * [CalendarFeedState.PermissionRequired] — the inline **Turn on** prompt (NFR-S2). The widget
 *    reports the tap and nothing more; the host owns the permission request.
 *  * [CalendarFeedState.Unavailable] — nothing. Access was already granted, so nagging for it again
 *    would be wrong; the date simply stands alone.
 */
@Composable
fun CalendarWidget(
    state: CalendarFeedState,
    size: TodayWidgetSize,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
    use24Hour: Boolean = false,
    onGrantAccess: () -> Unit = {},
    onClick: () -> Unit = {},
) {
    val colors = currentDuoColors()
    val events = (state as? CalendarFeedState.Events)?.events.orEmpty()
    val shown = events.take(eventsShownAt(size))

    TodayWidgetFrame(
        size = size,
        modifier = modifier.clickable(onClick = onClick),
        semanticsLabel = if (state is CalendarFeedState.PermissionRequired) {
            null
        } else {
            spokenSummary(state.today, shown, zone, use24Hour)
        },
    ) {
        TodayWidgetCaption(icon = Icons.Rounded.CalendarToday, label = weekdayOf(state.today))
        Text(
            text = state.today.dayOfMonth.toString(),
            style = if (size == TodayWidgetSize.SMALL) DuoTokens.type.title1 else DuoTokens.type.clockLarge,
            color = colors.label1,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )

        when (state) {
            is CalendarFeedState.PermissionRequired -> TodayTurnOnPrompt(
                message = "Show your next events here.",
                actionLabel = "Turn on",
                accessibilityLabel = "Turn on calendar access",
                onClick = onGrantAccess,
                compact = size == TodayWidgetSize.SMALL,
            )

            is CalendarFeedState.Unavailable -> Unit

            is CalendarFeedState.Events -> if (shown.isEmpty()) {
                TodayWidgetPlaceholder(text = "No events")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(DuoTokens.space.xs)) {
                    shown.forEach { event ->
                        EventRow(event = event, zone = zone, use24Hour = use24Hour)
                    }
                }
            }
        }
    }
}

@Composable
private fun EventRow(
    event: CalendarEvent,
    zone: ZoneId,
    use24Hour: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = currentDuoColors()
    // The dot's colour is the calendar's own, as the provider reported it — runtime data, not a
    // hard-coded palette entry. A calendar with no colour falls back to the Duo accent.
    val dot = if (event.color != 0) Color(event.color) else colors.accent
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(DuoTokens.space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(EVENT_DOT)
                .clip(CircleShape)
                .background(dot),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = event.title.ifBlank { "(No title)" },
                style = DuoTokens.type.footnote,
                color = colors.label1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = eventTime(event, zone, use24Hour),
                style = DuoTokens.type.caption2,
                color = colors.label2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** How many events fit a given span. FR-59's ceiling is three, and the 2x2 has room for one. */
internal fun eventsShownAt(size: TodayWidgetSize): Int = when (size) {
    TodayWidgetSize.SMALL -> 1
    TodayWidgetSize.MEDIUM -> 2
    TodayWidgetSize.LARGE, TodayWidgetSize.EXTRA_LARGE -> CALENDAR_EVENT_COUNT
}

private fun eventTime(event: CalendarEvent, zone: ZoneId, use24Hour: Boolean): String {
    if (event.allDay) return "All day"
    val formatter = DateTimeFormatter.ofPattern(if (use24Hour) "HH:mm" else "h:mm a")
    return Instant.ofEpochMilli(event.beginMillis).atZone(zone).format(formatter)
}

private fun weekdayOf(date: LocalDate): String =
    date.dayOfWeek.getDisplayName(JavaTextStyle.FULL, Locale.getDefault())

private fun spokenSummary(
    today: LocalDate,
    events: List<CalendarEvent>,
    zone: ZoneId,
    use24Hour: Boolean,
): String {
    val date = "${weekdayOf(today)} ${today.dayOfMonth}"
    if (events.isEmpty()) return "$date, no events"
    val spoken = events.joinToString(separator = ", ") { event ->
        "${event.title.ifBlank { "Untitled event" }} at ${eventTime(event, zone, use24Hour)}"
    }
    return "$date, $spoken"
}

private val EVENT_DOT = 8.dp

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

private val PreviewZone: ZoneId = ZoneId.of("UTC")
private val PreviewToday: LocalDate = LocalDate.parse("2026-09-15")

private fun previewEvent(title: String, hour: Int, colorArgb: Int, allDay: Boolean = false) = CalendarEvent(
    eventId = hour.toLong(),
    title = title,
    beginMillis = Instant.parse("2026-09-15T00:00:00Z").toEpochMilli() + hour * 3_600_000L,
    endMillis = Instant.parse("2026-09-15T00:00:00Z").toEpochMilli() + (hour + 1) * 3_600_000L,
    allDay = allDay,
    color = colorArgb,
)

private val PreviewEvents = CalendarFeedState.Events(
    today = PreviewToday,
    events = listOf(
        previewEvent("Design review", 10, 0xFF4B4F9B.toInt()),
        previewEvent("Lunch with Sam", 12, 0xFF3B7444.toInt()),
        previewEvent("Fold 8 device pass", 15, 0xFFB2631F.toInt()),
    ),
)

@Preview(name = "Calendar 2x2", widthDp = 200, heightDp = 200)
@Composable
private fun CalendarSmallPreview() {
    DuoTheme { TodayPreviewBackdrop { CalendarWidget(PreviewEvents, TodayWidgetSize.SMALL, Modifier.size(180.dp), PreviewZone) } }
}

@Preview(name = "Calendar 4x2", widthDp = 400, heightDp = 220)
@Composable
private fun CalendarMediumPreview() {
    DuoTheme { TodayPreviewBackdrop { CalendarWidget(PreviewEvents, TodayWidgetSize.MEDIUM, Modifier.size(380.dp, 190.dp), PreviewZone) } }
}

@Preview(name = "Calendar 4x4", widthDp = 400, heightDp = 420)
@Composable
private fun CalendarLargePreview() {
    DuoTheme { TodayPreviewBackdrop { CalendarWidget(PreviewEvents, TodayWidgetSize.LARGE, Modifier.size(380.dp, 380.dp), PreviewZone) } }
}

@Preview(name = "Calendar 4x6", widthDp = 400, heightDp = 600)
@Composable
private fun CalendarExtraLargePreview() {
    DuoTheme { TodayPreviewBackdrop { CalendarWidget(PreviewEvents, TodayWidgetSize.EXTRA_LARGE, Modifier.size(380.dp, 560.dp), PreviewZone) } }
}

@Preview(name = "Calendar, turn on", widthDp = 400, heightDp = 220)
@Composable
private fun CalendarPermissionPreview() {
    DuoTheme {
        TodayPreviewBackdrop {
            CalendarWidget(CalendarFeedState.PermissionRequired(PreviewToday), TodayWidgetSize.MEDIUM, Modifier.size(380.dp, 190.dp), PreviewZone)
        }
    }
}

@Preview(name = "Calendar, unavailable, dark", widthDp = 400, heightDp = 220, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun CalendarUnavailablePreview() {
    DuoTheme(dark = true) {
        TodayPreviewBackdrop {
            CalendarWidget(CalendarFeedState.Unavailable(PreviewToday), TodayWidgetSize.MEDIUM, Modifier.size(380.dp, 190.dp), PreviewZone)
        }
    }
}

@Preview(name = "Calendar 4x4, font scale 1.3", widthDp = 400, heightDp = 420, fontScale = 1.3f)
@Composable
private fun CalendarFontScalePreview() {
    DuoTheme { TodayPreviewBackdrop { CalendarWidget(PreviewEvents, TodayWidgetSize.LARGE, Modifier.size(380.dp, 380.dp), PreviewZone) } }
}
