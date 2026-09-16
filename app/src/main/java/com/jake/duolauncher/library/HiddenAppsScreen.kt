package com.jake.duolauncher.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.jake.duolauncher.design.DuoTokens
import com.jake.duolauncher.design.LocalDuoTypography
import com.jake.duolauncher.design.duoColors

/**
 * **Settings → Hidden apps** (FR-75).
 *
 * The one surface in the launcher that deliberately renders hidden apps, because it is where the
 * user undoes hiding them. Every other surface — Home, dock, folders, the App Library, Suggestions
 * and Search — excludes them through `LibraryExclusions`, so this list takes its apps as a
 * parameter rather than filtering a catalog itself.
 *
 * Exposed for the Duo Settings screen (work order F20), which owns the screen this sits inside.
 */
@Composable
internal fun HiddenAppsList(
    apps: List<LibraryApp>,
    icons: LibraryIcons,
    onOpen: (LibraryApp) -> Unit,
    onUnhide: (LibraryApp) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = duoColors()
    val type = LocalDuoTypography.current
    if (apps.isEmpty()) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(DuoTokens.space.lg)
                .testTag("hidden-apps-empty"),
            verticalArrangement = Arrangement.spacedBy(DuoTokens.space.xs),
        ) {
            Text(text = "No hidden apps", style = type.subhead, color = colors.label1)
            Text(
                text = "Hide an app from its long-press menu to keep it out of Home, the App Library and Search.",
                style = type.footnote,
                color = colors.label2,
            )
        }
        return
    }
    LazyColumn(
        modifier = modifier.testTag("hidden-apps-list"),
        contentPadding = PaddingValues(vertical = DuoTokens.space.sm),
    ) {
        items(apps, key = { it.id }) { app ->
            LibraryAppRow(
                app = app,
                icons = icons,
                onClick = { onOpen(app) },
                onActions = { onUnhide(app) },
                trailing = {
                    TextButton(
                        onClick = { onUnhide(app) },
                        modifier = Modifier
                            .testTag("unhide-${app.id}")
                            .semantics { contentDescription = "Unhide ${app.label}" },
                    ) {
                        Text(text = "Unhide", style = type.callout, color = colors.accent)
                    }
                },
            )
        }
    }
}
