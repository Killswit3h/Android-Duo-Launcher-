# Changelog

## 1.0.0-beta01 (unreleased)

`versionName` is already 1.0.0-beta01, but this release has not been cut and has not been tested on
hardware. Do not publish it from this entry.

Hardening, each verified by build, unit tests and lint in CI and none of it verified on a device:

- Close a cold-start hole in the pin-request flow: a pin request can start the process with no Home
  screen behind it, and a locked Home layout now stays locked in that window (FR-49).
- Drop obscured touches on the pin-request sheet, so an app drawing an overlay cannot harvest a tap
  on **Add**.
- Require the uninstall hand-off to resolve to a system app, so no ordinary app can register for
  `ACTION_DELETE` and show a convincing fake uninstall prompt (FR-29).
- Keep widget stacks and the Today column in step with the widgets they name: a removed widget no
  longer leaves a stack pointing at nothing, a stack left with one widget becomes a plain widget,
  and an existing incoherent layout is repaired on load rather than refused (FR-57, FR-61 to FR-64).
- Make a hand-built restore preview declare the backup version honestly, so it can no longer claim
  data it does not carry (FR-82).
- Put every clock surface on one shared minute ticker instead of three separate broadcast receivers.

Still to write before this release is cut: the user-facing summary of the `duo-os` feature work
already merged to `main`, and `docs/releases/1.0.0-beta01.md` with the tested scope and APK
checksums, following `docs/releases/0.15.0-beta01.md`. Neither can be written honestly until the
release has been built and run. See `specs/04-inspection-report.md` and
`specs/device-test-checklist.md`.

## 0.15.0-beta01

First public-beta preparation release. Tested scope and APK checksums accompany the release package.

- Add a skippable introduction for fresh installations and help through customization; existing layouts open directly.
- Improve recovery choices when Google Discover is unavailable.
- Show distinct Wi-Fi levels across the dot and three arcs.
- Preserve the current wallpaper when photo selection is canceled or fails, and improve interrupted preview recovery and temporary permission cleanup.
- Prepare optimized release builds, external signing, public-source export, and automated build checks.
- Add installation, update, permission, contribution, and compatibility documentation.

## 0.14.7

- Restore long-press pickup in scrollable Android widgets while preserving native vertical scrolling.

## 0.14.6

- Preserve the selected Home page or unfolded pair when returning from an app.

## 0.14.5

- Allow vertical scrolling inside native Android widgets.

## Earlier development

Home/All apps paging; right-side dock; overlapping unfolded pages and an unfolded-only workspace; native widgets and visual selection; cross-page dragging and temporary pages; work/personal profiles; Home folders; local wallpapers and daylight appearance; layout backup; Google search/Discover; long-press customization; and motion/recovery refinements.
