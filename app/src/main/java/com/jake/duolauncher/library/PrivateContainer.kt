package com.jake.duolauncher.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jake.duolauncher.design.DuoTokens
import com.jake.duolauncher.design.GlassLevel
import com.jake.duolauncher.design.GlassSurface
import com.jake.duolauncher.design.LocalDuoTypography
import com.jake.duolauncher.design.duoColors
import com.jake.duolauncher.profiles.PrivateSpaceApp
import com.jake.duolauncher.profiles.PrivateSpaceState
import com.jake.duolauncher.profiles.PrivateSpaceUnsupportedReason

/**
 * The Private container at the bottom of the App Library (FR-76, FR-77, FR-78).
 *
 * **Whether this is shown at all is not decided here.** The host asks
 * `profiles.showsPrivateContainer(state, hideContainer, searchText)` — the single rule Search and
 * the library share — and only then composes this. That is what keeps FR-78's "hidden until the
 * user types private" from drifting between the two surfaces.
 *
 * While the state is [PrivateSpaceState.Locked] the repository publishes no app list at all, so
 * there is nothing here that could leak: the locked container is a lock control and nothing else.
 */
@Composable
internal fun PrivateContainer(
    state: PrivateSpaceState,
    icons: LibraryIcons,
    onSetLocked: (Boolean) -> Unit,
    onLaunch: (PrivateSpaceApp) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = duoColors()
    val type = LocalDuoTypography.current
    val unlocked = state as? PrivateSpaceState.Unlocked
    GlassSurface(
        level = GlassLevel.PANEL,
        shape = DuoTokens.radius.card,
        modifier = modifier
            .fillMaxWidth()
            .testTag("library-private-container"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(DuoTokens.space.md),
            verticalArrangement = Arrangement.spacedBy(DuoTokens.space.sm),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Private",
                    modifier = Modifier.weight(1f),
                    style = type.headline,
                    color = colors.label1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(
                    onClick = { onSetLocked(unlocked != null) },
                    modifier = Modifier.testTag("private-lock-toggle"),
                ) {
                    Icon(
                        imageVector = if (unlocked != null) Icons.Rounded.LockOpen else Icons.Rounded.Lock,
                        contentDescription = if (unlocked != null) "Lock private space" else "Unlock private space",
                        tint = colors.label1,
                    )
                }
            }
            if (unlocked == null) {
                Text(
                    text = "Locked. Unlock to see your private apps.",
                    style = type.footnote,
                    color = colors.label2,
                )
            } else if (unlocked.apps.isEmpty()) {
                Text(text = "No apps in your private space.", style = type.footnote, color = colors.label2)
            } else {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(DuoTokens.space.sm),
                ) {
                    items(unlocked.apps, key = { it.id }) { app ->
                        LibraryAppCell(
                            id = app.id,
                            label = app.label,
                            icons = icons,
                            onClick = { onLaunch(app) },
                            modifier = Modifier
                                .size(width = PRIVATE_CELL_WIDTH, height = PRIVATE_CELL_HEIGHT)
                                .testTag("private-app-${app.id}"),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Why the private space cannot be offered (FR-76, and the error table's "Private space API
 * unavailable" row: *the settings row shows the reason, and there is no container*).
 *
 * Exposed for the Duo Settings screen, which is another work order's file. The App Library itself
 * never renders this: `showsPrivateContainer` is false for every unsupported reason, so the library
 * shows nothing at all rather than explaining itself where the user did not ask.
 */
@Composable
internal fun PrivateSpaceReasonRow(
    reason: PrivateSpaceUnsupportedReason,
    modifier: Modifier = Modifier,
) {
    val colors = duoColors()
    val type = LocalDuoTypography.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(DuoTokens.space.md)
            .testTag("private-space-reason"),
        verticalArrangement = Arrangement.spacedBy(DuoTokens.space.xxs),
    ) {
        Text(text = "Private space", style = type.subhead, color = colors.label1)
        Text(text = privateSpaceReasonText(reason), style = type.footnote, color = colors.label2)
    }
}

/** The user-facing explanation for each unsupported reason. Pure, so it is unit tested. */
internal fun privateSpaceReasonText(reason: PrivateSpaceUnsupportedReason): String = when (reason) {
    PrivateSpaceUnsupportedReason.REQUIRES_ANDROID_15 ->
        "Private space needs Android 15 or later."
    PrivateSpaceUnsupportedReason.NOT_DEFAULT_HOME ->
        "Make Duo your default Home app to use private space."
    PrivateSpaceUnsupportedReason.NO_PRIVATE_PROFILE ->
        "Set up a private space in Android Settings to see it here."
}

private val PRIVATE_CELL_WIDTH: Dp = 76.dp
private val PRIVATE_CELL_HEIGHT: Dp = 96.dp
