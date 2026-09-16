package com.jake.duolauncher.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jake.duolauncher.design.DuoTokens
import com.jake.duolauncher.design.GlassLevel
import com.jake.duolauncher.design.GlassSurface
import com.jake.duolauncher.design.currentDuoColors
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * A built-in card's glass backing (FR-2). The lightest glass level, so the card's own content leads
 * rather than the material it sits on.
 */
@Composable
internal fun GlassCard(modifier: Modifier = Modifier, onClick: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    GlassSurface(
        level = GlassLevel.WIDGET,
        shape = DuoTokens.radius.widget,
        modifier = modifier.fillMaxSize().clip(DuoTokens.radius.widget).clickable(onClick = onClick),
    ) {
        Column(
            Modifier.fillMaxSize().padding(DuoTokens.space.md),
            verticalArrangement = Arrangement.SpaceBetween,
            content = content,
        )
    }
}

@Composable
internal fun currentTime(): LocalDateTime {
    val time by produceState(LocalDateTime.now()) { while (true) { value = LocalDateTime.now(); delay(1000) } }
    return time
}

@Composable
internal fun ClockCard(onClick: () -> Unit) {
    val colors = currentDuoColors()
    val time = currentTime()
    val format = if (android.text.format.DateFormat.is24HourFormat(LocalContext.current)) "HH:mm" else "h:mm"
    GlassCard(onClick = onClick) {
        Icon(Icons.Rounded.Schedule, "Clock widget; tap to replace", tint = colors.label1, modifier = Modifier.size(20.dp))
        Text(time.format(DateTimeFormatter.ofPattern(format)), style = DuoTokens.type.clockLarge,
            color = colors.label1, maxLines = 1)
        Text("Local time", style = DuoTokens.type.caption1, color = colors.label2)
    }
}

@Composable
internal fun DateCard(onClick: () -> Unit) {
    val colors = currentDuoColors()
    val date = currentTime()
    GlassCard(onClick = onClick) {
        Text(date.format(DateTimeFormatter.ofPattern("EEEE")), style = DuoTokens.type.caption1, color = colors.label1, maxLines = 1)
        Text(date.dayOfMonth.toString(), style = DuoTokens.type.clockLarge, color = colors.label1)
        Text(date.format(DateTimeFormatter.ofPattern("MMMM")), style = DuoTokens.type.caption1, color = colors.label2)
    }
}

@Composable
internal fun ExpandedCard(onClick: () -> Unit) {
    val colors = currentDuoColors()
    val date = currentTime()
    GlassCard(onClick = onClick) {
        Column {
            Text(date.format(DateTimeFormatter.ofPattern("EEEE")), style = DuoTokens.type.title2, color = colors.label1)
            Text(date.format(DateTimeFormatter.ofPattern("MMMM d")), style = DuoTokens.type.body, color = colors.label2)
        }
        Column {
            Icon(Icons.Rounded.Widgets, null, tint = colors.label1, modifier = Modifier.size(32.dp))
            Spacer(Modifier.height(DuoTokens.space.lg))
            Text("A little more room.", style = DuoTokens.type.title1, color = colors.label1)
            Spacer(Modifier.height(DuoTokens.space.md))
            Text("Add a calendar, photos, or another widget.", style = DuoTokens.type.footnote, color = colors.label2)
            Spacer(Modifier.height(DuoTokens.space.xl))
            FilledTonalButton(onClick = onClick) {
                Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(DuoTokens.space.sm))
                Text("Add widget")
            }
        }
    }
}
