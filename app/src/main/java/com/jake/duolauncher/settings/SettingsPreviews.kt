package com.jake.duolauncher.settings

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.tooling.preview.Preview
import com.jake.duolauncher.DuoSettings
import com.jake.duolauncher.DuoTheme
import com.jake.duolauncher.GridSpec
import com.jake.duolauncher.LayoutMode
import com.jake.duolauncher.LayoutTarget
import com.jake.duolauncher.design.BlurBackdrop
import com.jake.duolauncher.design.DuoSampleBackdrop
import com.jake.duolauncher.design.DuoTokens
import com.jake.duolauncher.icons.IconAppearance
import com.jake.duolauncher.icons.IconPackFailure
import com.jake.duolauncher.icons.IconPackState
import com.jake.duolauncher.icons.InstalledIconPack
import com.jake.duolauncher.library.LibraryApp
import com.jake.duolauncher.profiles.PrivateSpaceState
import com.jake.duolauncher.profiles.PrivateSpaceUnsupportedReason

/**
 * A visual bench for Duo Settings, in the shape `design/GlassGallery.kt` established.
 *
 * Nothing in the launcher uses these. They exist so the inset-grouped style, the search state and —
 * above all — the states that need access Duo has *not* been granted can be eyeballed without a
 * device, a private space or a notification-listener grant.
 */

private val PREVIEW_GRIDS = mapOf(
    LayoutTarget.MIRRORED to GridSpec(4, 6),
    LayoutTarget.COVER to GridSpec(4, 6),
    LayoutTarget.INNER to GridSpec(6, 6),
)

/** A fully set-up launcher: everything granted, a photo wallpaper, an icon pack loaded. */
private fun grantedState(): DuoSettingsUiState = DuoSettingsUiState(
    settings = DuoSettings(
        glassLevel = 55,
        iconAppearance = IconAppearance.TINTED,
        largeIcons = false,
        iconPack = "com.example.pack",
    ),
    grids = PREVIEW_GRIDS,
    layoutMode = LayoutMode.SEPARATE,
    activeTarget = LayoutTarget.INNER,
    expandedActive = true,
    isDefaultHome = true,
    canUndo = true,
    badgeAccessGranted = true,
    contactsGranted = true,
    accessibilityEnabled = true,
    discoverAvailable = true,
    privateSpace = PrivateSpaceState.Unlocked(apps = emptyList()),
    iconPacks = listOf(
        InstalledIconPack("com.example.pack", "Moonshine"),
        InstalledIconPack("com.example.other", "Papercut"),
    ),
    iconPackState = IconPackState.Ready("com.example.pack", 1_842),
    hiddenApps = listOf(
        LibraryApp(id = "a/.M", label = "Calculator", packageName = "com.android.calculator2"),
        LibraryApp(id = "b/.M", label = "Clock", packageName = "com.android.deskclock"),
    ),
    wallpaper = WallpaperUiState(photoSelected = true),
    versionName = "1.0.0-beta01",
)

/** A sideloaded install where nothing optional has been granted yet. */
private fun notGrantedState(): DuoSettingsUiState = DuoSettingsUiState(
    settings = DuoSettings(searchContacts = true, doubleTapLock = true),
    grids = PREVIEW_GRIDS,
    isDefaultHome = false,
    badgeAccessGranted = false,
    contactsGranted = false,
    accessibilityEnabled = false,
    discoverAvailable = false,
    privateSpace = PrivateSpaceState.Unsupported(PrivateSpaceUnsupportedReason.NOT_DEFAULT_HOME),
    iconPackState = IconPackState.Unavailable("com.example.gone", IconPackFailure.NOT_INSTALLED),
    versionName = "1.0.0-beta01",
)

/** Stands in for a photo wallpaper so the glass has something to refract. */
@Composable
private fun PreviewStage(content: @Composable () -> Unit) {
    BlurBackdrop(modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(DuoSampleBackdrop)))
        content()
    }
}

/** A single section, for looking at one group at a time. */
@Composable
private fun SectionStage(content: @Composable () -> Unit) {
    PreviewStage {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(vertical = DuoTokens.space.xl),
            verticalArrangement = Arrangement.spacedBy(DuoTokens.space.xl),
        ) {
            content()
        }
    }
}

// ---------------------------------------------------------------------------
// The overview
// ---------------------------------------------------------------------------

@Preview(name = "Duo Settings", widthDp = 420, heightDp = 900)
@Composable
private fun DuoSettingsOverviewPreview() {
    DuoTheme(dark = false) {
        PreviewStage {
            DuoSettingsScreen(state = grantedState(), actions = DuoSettingsActions())
        }
    }
}

@Preview(
    name = "Duo Settings, dark",
    widthDp = 420,
    heightDp = 900,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun DuoSettingsOverviewDarkPreview() {
    DuoTheme(dark = true) {
        PreviewStage {
            DuoSettingsScreen(state = grantedState(), actions = DuoSettingsActions())
        }
    }
}

@Preview(name = "Duo Settings, unfolded", widthDp = 700, heightDp = 900)
@Composable
private fun DuoSettingsWidePreview() {
    DuoTheme(dark = false) {
        PreviewStage {
            DuoSettingsScreen(state = grantedState(), actions = DuoSettingsActions())
        }
    }
}

// ---------------------------------------------------------------------------
// The search state (AC-64)
// ---------------------------------------------------------------------------

@Preview(name = "Settings search: badge", widthDp = 420, heightDp = 640)
@Composable
private fun DuoSettingsSearchPreview() {
    DuoTheme(dark = false) {
        PreviewStage {
            DuoSettingsScreen(
                state = grantedState(),
                actions = DuoSettingsActions(),
                initialQuery = "badge",
            )
        }
    }
}

@Preview(name = "Settings search: no results", widthDp = 420, heightDp = 420)
@Composable
private fun DuoSettingsSearchEmptyPreview() {
    DuoTheme(dark = false) {
        PreviewStage {
            DuoSettingsScreen(
                state = grantedState(),
                actions = DuoSettingsActions(),
                initialQuery = "zzqx",
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Not-granted states (FR-23, FR-76, error table)
// ---------------------------------------------------------------------------

@Preview(name = "Badges: access not granted", widthDp = 420, heightDp = 560)
@Composable
private fun DuoSettingsBadgesNotGrantedPreview() {
    DuoTheme(dark = false) {
        PreviewStage {
            DuoSettingsScreen(
                state = notGrantedState(),
                actions = DuoSettingsActions(),
                initialQuery = "badge",
            )
        }
    }
}

@Preview(name = "Private space: unavailable", widthDp = 420, heightDp = 560)
@Composable
private fun DuoSettingsPrivateSpaceUnavailablePreview() {
    DuoTheme(dark = false) {
        PreviewStage {
            DuoSettingsScreen(
                state = notGrantedState(),
                actions = DuoSettingsActions(),
                initialQuery = "private",
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Individual sections
// ---------------------------------------------------------------------------

@Preview(name = "Section: Icons", widthDp = 420, heightDp = 760)
@Composable
private fun IconsSectionPreview() {
    DuoTheme(dark = false) {
        SectionStage {
            IconsSection(
                state = grantedState(),
                actions = DuoSettingsActions(),
                visibility = SettingsVisibility.All,
            )
        }
    }
}

@Preview(name = "Section: Icons, pack missing", widthDp = 420, heightDp = 760)
@Composable
private fun IconsSectionPackMissingPreview() {
    DuoTheme(dark = false) {
        SectionStage {
            IconsSection(
                state = notGrantedState(),
                actions = DuoSettingsActions(),
                visibility = SettingsVisibility.All,
            )
        }
    }
}

@Preview(name = "Section: Home Screen and Dock", widthDp = 420, heightDp = 900)
@Composable
private fun HomeAndDockSectionPreview() {
    DuoTheme(dark = false) {
        SectionStage {
            HomeAndDockSection(
                state = grantedState(),
                actions = DuoSettingsActions(),
                visibility = SettingsVisibility.All,
                gridTarget = LayoutTarget.INNER,
                onGridTarget = {},
                onGrid = {},
                presetWide = true,
                onPresetWide = {},
            )
        }
    }
}

@Preview(name = "Section: Gestures, service off", widthDp = 420, heightDp = 620)
@Composable
private fun GesturesSectionPreview() {
    DuoTheme(dark = false) {
        SectionStage {
            GesturesSection(
                state = notGrantedState(),
                actions = DuoSettingsActions(),
                visibility = SettingsVisibility.All,
            )
        }
    }
}
