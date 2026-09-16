package com.jake.duolauncher.home

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jake.duolauncher.Glass
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Composable
internal fun GlassCard(modifier: Modifier = Modifier, onClick: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxSize().clip(RoundedCornerShape(24.dp)).clickable(onClick = onClick),
        color = Glass.copy(alpha = .24f), shape = RoundedCornerShape(24.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = .18f))) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.SpaceBetween, content = content)
    }
}

@Composable
internal fun currentTime(): LocalDateTime {
    val time by produceState(LocalDateTime.now()) { while (true) { value = LocalDateTime.now(); delay(1000) } }
    return time
}

@Composable
internal fun ClockCard(onClick: () -> Unit) {
    val time = currentTime()
    val format = if (android.text.format.DateFormat.is24HourFormat(LocalContext.current)) "HH:mm" else "h:mm"
    GlassCard(onClick = onClick) {
        Icon(Icons.Rounded.Schedule, "Clock widget; tap to replace", tint = Color.White, modifier = Modifier.size(20.dp))
        Text(time.format(DateTimeFormatter.ofPattern(format)), color = Color.White, fontWeight = FontWeight.Light, fontSize = 30.sp, maxLines = 1)
        Text("Local time", color = Color.White.copy(alpha = .8f), fontSize = 11.sp)
    }
}

@Composable
internal fun DateCard(onClick: () -> Unit) {
    val date = currentTime()
    GlassCard(onClick = onClick) {
        Text(date.format(DateTimeFormatter.ofPattern("EEEE")), color = Color.White, fontSize = 12.sp, maxLines = 1)
        Text(date.dayOfMonth.toString(), color = Color.White, fontWeight = FontWeight.Light, fontSize = 40.sp, lineHeight = 42.sp)
        Text(date.format(DateTimeFormatter.ofPattern("MMMM")), color = Color.White.copy(alpha = .8f), fontSize = 12.sp)
    }
}

@Composable
internal fun ExpandedCard(onClick: () -> Unit) {
    val date = currentTime()
    GlassCard(onClick = onClick) {
        Column {
            Text(date.format(DateTimeFormatter.ofPattern("EEEE")), color = Color.White, fontSize = 22.sp)
            Text(date.format(DateTimeFormatter.ofPattern("MMMM d")), color = Color.White.copy(alpha = .8f), fontSize = 16.sp)
        }
        Column {
            Icon(Icons.Rounded.Widgets, null, tint = Color.White, modifier = Modifier.size(32.dp))
            Spacer(Modifier.height(16.dp))
            Text("A little more room.", color = Color.White, fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.Light)
            Spacer(Modifier.height(12.dp))
            Text("Add a calendar, photos, or another widget.", color = Color.White.copy(alpha = .85f), fontSize = 14.sp)
            Spacer(Modifier.height(20.dp))
            FilledTonalButton(onClick = onClick) { Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Add widget") }
        }
    }
}
