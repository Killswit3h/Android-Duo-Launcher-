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

## Integration gaps found by actually running the app (2026-09-16, emulator, unfolded 851dp)

Every one of these passed the build and the unit suite. They are wiring gaps, not implementation
gaps: the components exist, are tested, and are simply not connected to anything.

- **Home tiles do not use the icon pipeline.** Icons render as raw app drawables — square tiles with
  drop shadows — so no appearance (Default/Dark/Clear/Tinted), no shape mask, no monochrome
  fallback, no badges. `IconTile.kt` exists; `HomeGrid` is still drawing the old tile.
- **Nothing hosts the Today View.** The left half of the unfolded screen is empty wallpaper, so the
  iPhone Duo two-page spread — the single most recognisable thing about this design — does not
  happen. `TodayView` is fully parameter-driven and needs a host.
- **The leading page still opens Google Discover** regardless of `LeadingPageKind`, so FR-36's
  fresh-install default of Today View is not honoured at runtime.
- **The startup crash proves the coverage gap.** 763 unit tests passed while the launcher could not
  reach its first frame. Nothing in the suite composes, so nothing in the suite can catch an init
  order bug, a missing host, or an unwired setting. The instrumented suite and a real launch are
  the only checks that would have.

- **Restore applies only part of a backup.** `applyImportedLayout` consumes the active layout plus a
  handful of legacy fields, so a v3 import silently ignores the other two layouts, the imported grid,
  dock side and capacity, leading page and Today items, stacks, hidden apps, icon overrides and the
  entire settings block. The codec round-trips all of it (proven by test); the model does not apply
  it. The fix is local — consume those fields inside the single state commit `applyImportedLayout`
  already performs, and widen its undo snapshot — and must stay atomic, never partially applied.
- **The Settings screen is unreachable.** `home/HomeSheets.kt` still routes the settings sheet to the
  old customization sheet, so FR-80 is unmet from the UI even though the screen exists and is tested.
  FR-80's launcher entry additionally needs an `activity-alias` plus MainActivity routing.

**Conclusion for the build plan:** a dedicated integration pass is required before inspection —
hosting Today View, App Library and Search; driving Home tiles through the icon renderer; wiring the
schema-9 settings (glass level, icon appearance, badge style, gestures, dock side, grid) into every
surface; and wiring the private-space gate into library, search, suggestions and Home per FR-77.

## Restore: residual risks raised by the restore-wiring work

- **Stacks can dangle after a legacy import.** A v1/v2 backup carries no stacks, so the user's
  current ones are kept, but the active layout's widget placements are replaced — a stack can end up
  referencing a slot that vanished or now holds a different widget. `validate()` checks neither
  stack→placement nor `leadingPage.today`→slot coherence, so nothing catches it. Left deliberately
  (a v2 backup must not rewrite what it never described); pruning dangling stacks on legacy import
  is the real fix.
- **`LayoutImportPreview`'s defaults are a trap.** `version` defaults to 3 while `layoutSet` and
  `settings` default to empty, so a hand-built preview claims to carry schema-9 data it does not —
  which would import an empty launcher. One construction site was fixed; the honest fix is a
  `carriesSchema9` flag or defaulting `version` to 1.
- **The SAF round trip is unverified.** Export → restore → undo through the real document picker,
  and AC-66 end to end, are instrumented-only and have not been run on a device yet.
- **Two deliberate behaviour changes for the inspector to sign off:** a v1/v2 import onto a
  non-4×6 grid now reflows instead of corrupting, and an import whose merge fails validation is now
  refused with a message where it previously applied and surfaced later as a recovery banner.

## Security hardening deferred with a deliberate decision

- **Tapjacking on the exported pin sheet.** `PinItemActivity` sets no `filterTouchesWhenObscured`, so an app holding `SYSTEM_ALERT_WINDOW` could overlay it and harvest a tap on **Add**. Impact is capped at pinning an item the user did not intend; nothing leaves the device. Deferred only because the pin sheet's UI was being restyled concurrently — worth doing once that settles.
- **The uninstall hand-off is an implicit intent.** Any app can register an `ACTION_DELETE` filter and show a convincing fake uninstall prompt. Hardening means resolving the handler and requiring a system flag before starting it.

- **`HomeDragIntegrationTest.kt:203`** asserts a layout write is visible immediately after an edit. Layout writes are now debounced 150ms, so that read races. The fix belongs in the test fixture (flush or wait), not in production code.
- **Instrumented gesture pass on the emulator.** The `LauncherScreen` split was verified only by JVM tests, which do not exercise Compose UI. One-page-per-swipe, native widget vertical scroll and scoped long-press pickup are covered exclusively by `app/src/androidTest` and must be run before this is called done.
- **`rememberSaveable` keys shifted** when nine states moved into `HomeWorkspace`. Harmless across app upgrades (Android discards saved instance state on version change) but it is a real structural change worth confirming on device.

## FR-45 Edit mode: root cause of "long-press below the grid does nothing" (2026-09-16, static)

Traced by reading the source; **not** confirmed on a device, because this session had no Android
toolchain (see `specs/04-inspection-report.md`, "Environment").

`home/HomePager.kt` builds every Home page in `HomePagePane`. The background long-press verb is
defined once:

```kotlin
val backgroundLongPress = { if (!drag.active) onEditMode?.invoke() ?: onEmptyWidget(backgroundTarget) }
```

and then attached to exactly one pointer-input region:

```kotlin
Box(Modifier.width(16.dp).fillMaxHeight().testTag("home-options-margin-$page")
    .pointerInput(...) { detectTapGestures(onLongPress = { backgroundLongPress() }, onTap = { onBackgroundTap?.invoke() }) })
```

That region is a **16dp-wide strip down the left edge of the pane**. Everything to its right is the
`Column` that holds `SharedHomeGrid`, and that `Column` is `fillMaxHeight().verticalScroll(...)`, so
it covers the whole pane height including the empty area below the last grid row. A long-press on
empty wallpaper below the grid therefore lands on the scrolling `Column`, which has no long-press
handler, and nothing happens. Only a press inside the 16dp margin reaches Edit mode.

A second, smaller hole: `HomePagePane`'s own `Box` is
`.height((contentHeight - bottomSpace).coerceAtLeast(0.dp))`, while its parent in
`ExpandedWorkspace` is `fillMaxHeight()`. The `bottomSpace` band (44dp default Home, 88dp otherwise)
below the pane has no handler at all on either display.

This matches the reported symptom exactly: the unfolded screen has the most empty space below the
grid, so it is where the dead zone is most obvious.

**Proposed fix, not applied.** Move the `detectTapGestures` off the 16dp margin and onto the pane's
own `Box`, keeping the margin's `testTag` for the existing tests. Compose dispatches the Main pass
child-first, and both `clickable` and `detectTapGestures` consume the down, so a press that lands on
an app tile, an empty cell, a folder or a widget is consumed by that child and never reaches the
pane. Only genuinely empty background falls through. The empty-cell long-press path
(`onEmptyWidget`) and the scoped widget long-press pickup are children and keep priority.

**Why it was not applied in this session.** It changes pointer-event arbitration on Home, which is
the exact regression boundary `docs/architecture.md` and `CONTRIBUTING.md` protect
(one-page-per-swipe, native widget vertical scroll, scoped long-press pickup). The reasoning above
is about Compose pass ordering and consumption, which cannot be confirmed without composing the UI.
Landing it unbuilt and untested would be the same mistake as the `design/DuoType.kt` init-order bug
that 763 green unit tests missed. It needs `:app:testDebugUnitTest`, the instrumented gesture suite,
and checklist section 8 rows 8.1 to 8.12 before it is trustworthy.
