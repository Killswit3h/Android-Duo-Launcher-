# Backlog — deferred out of the `duo-os` build

Ideas parked here instead of expanding scope mid-build. Each line says why it is deferred.

## Deferred by the approved spec (section 2, "Later")

- **Smart stack auto-rotation driven by usage patterns.** FR-63 ships time-of-day rotation only; usage-model rotation needs launch data we will not have until the history store has run for weeks.
- **A built-in "Shortcuts" widget** (a grid of pinned app shortcuts, like the iOS 4×6 Shortcuts widget). Needs a shortcut-picker UI beyond FR-27's drag-to-pin.
- **Calendar events beyond the next 3** in the Today View Calendar widget, plus a month view.
- **Localized translations.** FR/NFR-M4 only externalizes strings to `strings.xml`; no translation is shipped.
- **Multiple Today View pages.** FR-56 ships one scrolling column.
- **Per-page wallpapers.**
- **Contact photos in search results.** FR-72 ships name and action rows only.

## Impossible or restricted for a third-party launcher (researched, not attempted)

Recorded so nobody re-researches them. Sources are in `specs/01-research-brief.md` section 4.

- Recents/QuickStep gesture integration and the stock app-close animation — system launcher only.
- The system taskbar with recent apps on the unfolded screen — part of QuickStep.
- App pairs and launching two apps into split screen — no public API.
- Samsung pop-up view, and Android 17 "bubble any app".
- Native At a Glance (`SmartspaceManager`) and system app predictions (`AppPredictionManager`) — `@SystemApi`. Duo ships its own Suggestions (FR-59, FR-68) instead. A Smartspacer SDK integration is a possible future alternative.
- System wallpaper dimming (`setWallpaperDimAmount`) — Duo draws its own scrim on Home only (FR-9).
- Per-display (cover vs inner) system wallpapers — no public Samsung API.

## Raised during the build

- **Consolidate the three existing TIME_TICK receivers** onto the shared ticker added for the Today View feeds (`MainActivity.kt:120`, `Appearance.kt:95` in `rememberSavedAppearance`, `DuneWallpaper.kt:124`). The new `SystemTimeTicker` is one registration that fans out and unregisters when its last subscriber leaves, so the three ad-hoc receivers can move onto it. Deferred because it touches three unrelated subsystems mid-build.
- **`PRIVACY.md` had no entry for the notification-listener badge access** introduced with badges; the paragraph added with the Today View feeds now covers both badges and media. Worth a deliberate re-read of the whole file during the S6 docs pass rather than trusting the incremental edits.

## Required before inspection (not deferred — tracked here so they are not forgotten)

- **`HomeDragIntegrationTest.kt:203`** asserts a layout write is visible immediately after an edit. Layout writes are now debounced 150ms, so that read races. The fix belongs in the test fixture (flush or wait), not in production code.
- **Instrumented gesture pass on the emulator.** The `LauncherScreen` split was verified only by JVM tests, which do not exercise Compose UI. One-page-per-swipe, native widget vertical scroll and scoped long-press pickup are covered exclusively by `app/src/androidTest` and must be run before this is called done.
- **`rememberSaveable` keys shifted** when nine states moved into `HomeWorkspace`. Harmless across app upgrades (Android discards saved instance state on version change) but it is a real structural change worth confirming on device.
