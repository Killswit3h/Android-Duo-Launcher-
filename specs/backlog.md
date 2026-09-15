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

_(appended as work proceeds)_
