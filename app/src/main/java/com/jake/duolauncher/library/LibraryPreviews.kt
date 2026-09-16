package com.jake.duolauncher.library

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jake.duolauncher.DuoTheme
import com.jake.duolauncher.design.BlurBackdrop
import com.jake.duolauncher.design.DuoSampleBackdrop
import com.jake.duolauncher.design.DuoTokens
import com.jake.duolauncher.design.GlassLevel
import com.jake.duolauncher.design.GlassSurface
import com.jake.duolauncher.profiles.PrivateSpaceApp
import com.jake.duolauncher.profiles.PrivateSpaceState
import com.jake.duolauncher.profiles.PrivateSpaceUnsupportedReason
import java.util.Locale

/**
 * A visual bench for the App Library, in the shape `design/GlassGallery.kt` established.
 *
 * Nothing in the launcher uses these; they exist so the category tiles, the A–Z list and scrubber,
 * the work-profile filter and the private container can be eyeballed in Android Studio without a
 * device, a work profile or an Android 15 emulator with a private space.
 */

private const val DAY = 24L * 60 * 60 * 1000

private fun previewApp(
    id: String,
    label: String,
    pkg: String,
    category: Int = AppCategories.UNDEFINED,
    ageDays: Long = 400,
    isWork: Boolean = false,
) = LibraryApp(
    id = id,
    label = label,
    packageName = pkg,
    category = category,
    installedAt = System.currentTimeMillis() - ageDays * DAY,
    isWork = isWork,
)

private val PREVIEW_APPS: List<LibraryApp> = listOf(
    previewApp("a/.M", "Messages", "com.android.messaging", AppCategories.SOCIAL),
    previewApp("b/.M", "Signal", "org.thoughtcrime.signal", AppCategories.SOCIAL),
    previewApp("c/.M", "Instagram", "com.instagram.android", AppCategories.SOCIAL),
    previewApp("d/.M", "Discord", "com.discord", AppCategories.SOCIAL),
    previewApp("e/.M", "Reddit", "com.reddit.frontpage", AppCategories.SOCIAL),
    previewApp("f/.M", "Gmail", "com.google.android.gm", AppCategories.PRODUCTIVITY),
    previewApp("g/.M", "Calendar", "com.google.android.calendar", AppCategories.PRODUCTIVITY),
    previewApp("h/.M", "Notion", "notion.id", AppCategories.PRODUCTIVITY),
    previewApp("i/.M", "Slack", "com.slack", AppCategories.PRODUCTIVITY),
    previewApp("j/.M", "Drive", "com.google.android.apps.docs", AppCategories.PRODUCTIVITY),
    previewApp("k/.M", "Sudoku", "com.sudoku.classic", AppCategories.GAME),
    previewApp("l/.M", "Minecraft", "com.mojang.minecraft", AppCategories.GAME),
    previewApp("m/.M", "YouTube", "com.google.android.youtube", AppCategories.VIDEO),
    previewApp("n/.M", "Spotify", "com.spotify.music", AppCategories.AUDIO),
    previewApp("o/.M", "Netflix", "com.netflix.mediaclient", AppCategories.VIDEO),
    previewApp("p/.M", "Camera", "com.android.camera", AppCategories.IMAGE),
    previewApp("q/.M", "Lightroom", "com.adobe.lightroom", AppCategories.IMAGE),
    previewApp("r/.M", "Chrome", "com.android.chrome", AppCategories.NEWS),
    previewApp("s/.M", "Maps", "com.google.android.apps.maps", AppCategories.MAPS),
    previewApp("t/.M", "Clock", "com.android.deskclock"),
    previewApp("u/.M", "Calculator", "com.android.calculator2"),
    previewApp("v/.M", "Files", "com.android.documentsui"),
    previewApp("w/.M", "Settings", "com.android.settings"),
    previewApp("x/.M", "Widgetron", "com.acme.widgetron"),
    previewApp("y/.M", "Éclair", "com.acme.eclair"),
    previewApp("z/.M", "1Password", "com.agilebits.onepassword"),
    // Two freshly installed apps, so Recently Added is populated in the preview.
    previewApp("aa/.M", "Threads", "com.instagram.barcelona", AppCategories.SOCIAL, ageDays = 2),
    previewApp("ab/.M", "Obsidian", "md.obsidian", AppCategories.PRODUCTIVITY, ageDays = 5),
)

private val PREVIEW_WORK_APPS: List<LibraryApp> = listOf(
    previewApp("w1/.M", "Gmail", "com.google.android.gm", AppCategories.PRODUCTIVITY, isWork = true),
    previewApp("w2/.M", "Teams", "com.microsoft.teams", AppCategories.PRODUCTIVITY, isWork = true),
)

private val PREVIEW_SUGGESTIONS = LibrarySuggestionsProvider {
    listOf("f/.M", "r/.M", "n/.M", "a/.M", "s/.M", "m/.M", "g/.M", "t/.M").take(it)
}

private fun previewGroups(apps: List<LibraryApp> = PREVIEW_APPS): List<LibraryGroupContent> =
    CategoryGrouper(Locale.US, PREVIEW_SUGGESTIONS, LibraryExclusions.None).group(apps)

private fun previewIndex(apps: List<LibraryApp> = PREVIEW_APPS): AlphabetIndex =
    AlphabetIndexer(Locale.US, LibraryExclusions.None).index(apps)

/** Stands in for a photo wallpaper so glass has something to refract. */
@Composable
private fun PreviewStage(content: @Composable () -> Unit) {
    BlurBackdrop(modifier = Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.linearGradient(DuoSampleBackdrop)),
        )
        Box(Modifier.fillMaxSize().padding(DuoTokens.space.md)) { content() }
    }
}

@Composable
private fun PreviewPanel(content: @Composable () -> Unit) {
    PreviewStage {
        GlassSurface(
            level = GlassLevel.PANEL,
            shape = DuoTokens.radius.sheet,
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(Modifier.fillMaxSize().padding(DuoTokens.space.lg)) { content() }
        }
    }
}

// ---------------------------------------------------------------------------
// FR-68 — category groups
// ---------------------------------------------------------------------------

@Preview(name = "App Library — categories", widthDp = 420, heightDp = 860)
@Composable
private fun LibraryCategoriesPreview() {
    DuoTheme(dark = false) {
        PreviewPanel {
            LibraryCategoriesView(
                groups = previewGroups(),
                icons = LibraryIcons(),
                onLaunch = { _, _ -> },
                onActions = { },
                onOpenGroup = { },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Preview(
    name = "App Library — categories, dark",
    widthDp = 420,
    heightDp = 860,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun LibraryCategoriesDarkPreview() {
    DuoTheme(dark = true) {
        PreviewPanel {
            LibraryCategoriesView(
                groups = previewGroups(),
                icons = LibraryIcons(),
                onLaunch = { _, _ -> },
                onActions = { },
                onOpenGroup = { },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** The font-scale pass: nothing in a tile may clip at 1.3 (NFR-A3). */
@Preview(name = "App Library — categories, font scale 1.3", widthDp = 420, heightDp = 860, fontScale = 1.3f)
@Composable
private fun LibraryCategoriesLargeFontPreview() {
    DuoTheme(dark = false) {
        PreviewPanel {
            LibraryCategoriesView(
                groups = previewGroups(),
                icons = LibraryIcons(),
                onLaunch = { _, _ -> },
                onActions = { },
                onOpenGroup = { },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** The group a mini grid opens (AC-55). */
@Preview(name = "App Library — opened group", widthDp = 420, heightDp = 860)
@Composable
private fun LibraryGroupPanelPreview() {
    val group = previewGroups().first { it.group == LibraryGroup.PRODUCTIVITY }
    DuoTheme(dark = false) {
        PreviewStage {
            LibraryGroupPanel(
                content = group,
                icons = LibraryIcons(),
                onLaunch = { _, _ -> },
                onActions = { },
                onClose = { },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// FR-69 — A–Z and the scrubber
// ---------------------------------------------------------------------------

@Preview(name = "App Library — A to Z", widthDp = 420, heightDp = 860)
@Composable
private fun LibraryAzPreview() {
    DuoTheme(dark = false) {
        PreviewPanel {
            LibraryAzView(
                index = previewIndex(),
                icons = LibraryIcons(),
                listState = rememberLazyListState(),
                onLaunch = { _, _ -> },
                onActions = { },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Preview(
    name = "App Library — A to Z, dark",
    widthDp = 420,
    heightDp = 860,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun LibraryAzDarkPreview() {
    DuoTheme(dark = true) {
        PreviewPanel {
            LibraryAzView(
                index = previewIndex(),
                icons = LibraryIcons(),
                listState = rememberLazyListState(),
                onLaunch = { _, _ -> },
                onActions = { },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// FR-70 — the work-profile filter and the paused-work controls
// ---------------------------------------------------------------------------

@Preview(name = "App Library — work filter", widthDp = 420, heightDp = 520)
@Composable
private fun LibraryWorkFilterPreview() {
    DuoTheme(dark = false) {
        PreviewPanel {
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(DuoTokens.space.md),
            ) {
                LibraryProfileChips(showWork = true, onShowWork = { })
                LibraryAzView(
                    index = previewIndex(PREVIEW_APPS + PREVIEW_WORK_APPS).let {
                        AlphabetIndexer(Locale.US, LibraryExclusions.None)
                            .index(PREVIEW_APPS + PREVIEW_WORK_APPS, LibraryQuery(profile = ProfileFilter.WORK))
                    },
                    icons = LibraryIcons(),
                    listState = rememberLazyListState(),
                    onLaunch = { _, _ -> },
                    onActions = { },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
        }
    }
}

@Preview(name = "App Library — work apps paused", widthDp = 420, heightDp = 420)
@Composable
private fun LibraryWorkPausedPreview() {
    DuoTheme(dark = false) {
        PreviewPanel {
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(DuoTokens.space.md),
            ) {
                LibraryProfileChips(showWork = true, onShowWork = { })
                WorkPausedNotice(quiet = true, onTurnOnWork = { })
            }
        }
    }
}

// ---------------------------------------------------------------------------
// FR-76, FR-77, FR-78 — the private container
// ---------------------------------------------------------------------------

@Preview(name = "Private space — locked", widthDp = 420, heightDp = 260)
@Composable
private fun PrivateLockedPreview() {
    DuoTheme(dark = false) {
        PreviewStage {
            PrivateContainer(
                state = PrivateSpaceState.Locked,
                icons = LibraryIcons(),
                onSetLocked = { },
                onLaunch = { },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Preview(
    name = "Private space — locked, dark",
    widthDp = 420,
    heightDp = 260,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun PrivateLockedDarkPreview() {
    DuoTheme(dark = true) {
        PreviewStage {
            PrivateContainer(
                state = PrivateSpaceState.Locked,
                icons = LibraryIcons(),
                onSetLocked = { },
                onLaunch = { },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Preview(name = "Private space — unlocked", widthDp = 420, heightDp = 300)
@Composable
private fun PrivateUnlockedPreview() {
    val apps = listOf(
        PrivateSpaceApp("p1", "Vault", "com.vault", 10L),
        PrivateSpaceApp("p2", "Ledger", "com.ledger", 10L),
        PrivateSpaceApp("p3", "Notes", "com.notes", 10L),
    )
    DuoTheme(dark = false) {
        PreviewStage {
            PrivateContainer(
                state = PrivateSpaceState.Unlocked(apps),
                icons = LibraryIcons(),
                onSetLocked = { },
                onLaunch = { },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** The error table's "Private space API unavailable" row, as the settings screen will show it. */
@Preview(name = "Private space — unavailable", widthDp = 420, heightDp = 160)
@Composable
private fun PrivateUnsupportedPreview() {
    DuoTheme(dark = false) {
        PreviewStage {
            GlassSurface(level = GlassLevel.PANEL, shape = DuoTokens.radius.card) {
                PrivateSpaceReasonRow(PrivateSpaceUnsupportedReason.NOT_DEFAULT_HOME)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// FR-75 — hidden apps
// ---------------------------------------------------------------------------

@Preview(name = "Hidden apps", widthDp = 420, heightDp = 400)
@Composable
private fun HiddenAppsPreview() {
    DuoTheme(dark = false) {
        PreviewPanel {
            HiddenAppsList(
                apps = PREVIEW_APPS.take(3),
                icons = LibraryIcons(),
                onOpen = { },
                onUnhide = { },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Preview(name = "Hidden apps — empty", widthDp = 420, heightDp = 240)
@Composable
private fun HiddenAppsEmptyPreview() {
    DuoTheme(dark = false) {
        PreviewPanel {
            HiddenAppsList(
                apps = emptyList(),
                icons = LibraryIcons(),
                onOpen = { },
                onUnhide = { },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
