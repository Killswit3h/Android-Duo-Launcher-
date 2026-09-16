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

- **FR-77 is not enforced end to end.** The security review found `PrivateSpaceGate` has zero consumers in main source apart from badges. Until the gate is wired into the App Library, the search providers, `SuggestionExclusions.isPrivateSpaceLocked` and Home item filtering, a locked private space's apps remain visible in those surfaces. This is a blocking correctness item, not a polish item.
- ~~**FR-49 is not enforced for pin requests.**~~ **Corrected.** The security review's "registered nowhere" finding was stale: `LauncherModel.init` already registered all three hooks and retracts them identity-checked in `onCleared`. Verified by `HomeLayoutLockTest`. Schema 9 additionally found and fixed a real gap the review missed — FR-49 gated drag, auto-add and pin but *not* remove, which mattered because the App Library sheet toggles a pin without going through Edit mode.
- **FR-49 has a cold-start hole.** `PinItemActivity` can start the process without `LauncherModel` ever being constructed, so the lock hook is null and "absent lock reads as unlocked" lets a pin through on a locked layout. Fix is a persisted-settings fallback: a `lockLayout(context)` reader on `LauncherStateSnapshot` plus one line in `PinItemActivity`.
- **Schema 9 validates more strictly than schema 8 did in one place:** it rejects a widget overlapping an occupied cell on ordinary pages, where v8 only checked the leading page. It fails safe (nothing is overwritten, the v8 payload stays on disk) but a user hitting it sees an empty Home with "Saved Home layout could not be read". Traced as unreachable through the legacy migration paths and the v8 editor, but this is the one case where the upgrade is stricter, so it needs an on-device upgrade test against a real 0.15.0-beta01 profile before release.
- **Verify the private-space and lock wiring with tests that fail without it**, since both gaps were invisible to a green build and a passing suite.

## Security hardening deferred with a deliberate decision

- **Tapjacking on the exported pin sheet.** `PinItemActivity` sets no `filterTouchesWhenObscured`, so an app holding `SYSTEM_ALERT_WINDOW` could overlay it and harvest a tap on **Add**. Impact is capped at pinning an item the user did not intend; nothing leaves the device. Deferred only because the pin sheet's UI was being restyled concurrently — worth doing once that settles.
- **The uninstall hand-off is an implicit intent.** Any app can register an `ACTION_DELETE` filter and show a convincing fake uninstall prompt. Hardening means resolving the handler and requiring a system flag before starting it.

- **`HomeDragIntegrationTest.kt:203`** asserts a layout write is visible immediately after an edit. Layout writes are now debounced 150ms, so that read races. The fix belongs in the test fixture (flush or wait), not in production code.
- **Instrumented gesture pass on the emulator.** The `LauncherScreen` split was verified only by JVM tests, which do not exercise Compose UI. One-page-per-swipe, native widget vertical scroll and scoped long-press pickup are covered exclusively by `app/src/androidTest` and must be run before this is called done.
- **`rememberSaveable` keys shifted** when nine states moved into `HomeWorkspace`. Harmless across app upgrades (Android discards saved instance state on version change) but it is a real structural change worth confirming on device.
