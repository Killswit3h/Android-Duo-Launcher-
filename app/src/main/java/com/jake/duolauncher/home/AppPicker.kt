@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.jake.duolauncher.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.jake.duolauncher.AppEntry

@Composable
internal fun AppPicker(apps: List<AppEntry>, dockSlot: Int?, onSelect: (AppEntry) -> Unit, onClear: () -> Unit,
    onLongClick: (AppEntry) -> Unit, canSelect: (AppEntry) -> Boolean = { true }, blockedHint: String? = null) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(apps, query) { apps.filter { it.label.contains(query.trim(), ignoreCase = true) } }
    Column(Modifier.fillMaxWidth().fillMaxHeight(.88f).padding(horizontal = 20.dp).imePadding()) {
        Text(if (dockSlot == null) "Your apps" else "Dock position ${dockSlot + 1}", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(vertical = 16.dp).testTag("search-field"),
            placeholder = { Text("Search apps") }, leadingIcon = { Icon(Icons.Rounded.Search, null) }, singleLine = true,
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Clear search") } }, shape = RoundedCornerShape(20.dp))
        if (dockSlot != null) TextButton(onClick = onClear) { Text("Leave this position empty") }
        if (blockedHint != null) Text(blockedHint, color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp).testTag("dock-full-guidance"))
        LazyColumn(Modifier.weight(1f)) {
            if (filtered.isEmpty()) item { Text("No apps found", Modifier.padding(vertical = 24.dp)) }
            items(filtered, key = { it.id }) { app ->
                val enabled = canSelect(app)
                Row(Modifier.fillMaxWidth().testTag("picker-app-${app.id}")
                    .combinedClickable(enabled = enabled, onClick = { onSelect(app) }, onLongClick = { onLongClick(app) })
                    .alpha(if (enabled) 1f else .45f)
                    .padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Image(app.icon.asImageBitmap(), null, Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)))
                    Text(app.label, Modifier.padding(start = 16.dp).weight(1f), maxLines = 2)
                    if (dockSlot != null && enabled) Icon(Icons.Rounded.Add, "Choose ${app.label}")
                }
            }
        }
    }
}
