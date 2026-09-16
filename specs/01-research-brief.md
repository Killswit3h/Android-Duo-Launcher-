# Research Brief: "Duo OS" overhaul of Duo Launcher

Date: 2026-09-15 · Phase 1 of `/new-feature` · Slug: `duo-os`

## 1. Problem statement

Duo Launcher (0.15.0-beta01) is a working but experimental Fold launcher. It has a right-side dock, overlapping unfolded page pairs, native widgets, folders, an A–Z app list and Google Discover. Many things users expect from a stock launcher are missing: notification dots, app shortcuts, themed icons, hide apps, grid sizes, widget stacks, uninstall, double-tap to sleep, private space and more. Its visual style is a muted "dunes" palette of translucent cards with no blur. The owner uses a Galaxy Z Fold 8 daily and wants one launcher that:

- looks and feels like Apple's **iPhone Duo on iOS 27.1** (Liquid Glass, side rail controls, two-page spread when unfolded);
- keeps Android-level customization;
- reaches feature parity with One UI Home and Pixel Launcher wherever a third-party launcher is allowed to.

## 2. Users and jobs to be done

- **Primary user:** the owner, on a Galaxy Z Fold 8 running One UI 9 / Android 17, installing the app by sideload. Their jobs:
  - Launch apps fast from the cover and inner screens.
  - Glance at widgets on the unfolded left page.
  - See which apps have notifications.
  - Act on app shortcuts.
  - Theme the home screen (icon style, glass tint, wallpaper).
  - Organize with folders, stacks and hidden apps.
  - Trust that the layout survives updates and fold/unfold.
- **Secondary user:** public beta users of the upstream project (`jakesgoodapps/DuoLauncher`). They are bound by its privacy promises and public-source export rules.

## 3. Existing codebase findings

**Stack** (`app/build.gradle.kts`)
- Kotlin 2.1.20, AGP 8.11.0, Gradle 8.14.3, JDK 17.
- compileSdk/targetSdk 36, minSdk 31.
- 100% Jetpack Compose with Material 3 from BOM 2025.06.01, plus `androidx.window:window:1.5.1`.
- No DI, DataStore, Room, networking or image loader. Dependency versions are inline; there is no version catalog.
- About 12k lines in `app/src/main/java/com/jake/duolauncher/`.

**Architecture**
- `MainActivity` creates `LauncherModel`, an AndroidViewModel exposing `StateFlow<LauncherState>`. It also creates the controllers (`WidgetController`, `BackupController`, `LauncherBackgroundController`, `DeviceStatusMonitor`, `AppearanceStore`, `SetupExperience`).
- It renders `DuoTheme { LauncherScreen(...) }`.
- `LauncherScreen.kt` is a 2,037-line god file:
  - The `LauncherScreen` composable alone is about 1,070 lines, with about 30 remembered state variables.
  - Sheets are routed through a string key (`LauncherScreen.kt:139`).
  - The file also holds dead `SettingsPanel`/`SettingSlider` code (lines 1848–1960).

**Persistence**
- SharedPreferences store hand-written `org.json` data. The main layout lives in `launcher/state`, schema 8, with migrations in `LauncherModel.kt`.
- Other preference files: `app_catalog`, `appearance`, `widget_pending`, `layout_backup_*`, `launcher_background`.
- The layout JSON is also parsed directly in `DiscoverActivity.kt:230`, `DiscoverBounds.kt:37,70` and `LiveDiscoverActivity.kt:233`.
- The model migrates layouts and keeps backups of earlier versions (`state_v1_backup`…`v7`). **Any new fields must follow this migration pattern.**

**Layout rules**
- `HomeEditing.kt:3-4` hard-codes `GRID_COLUMNS=4` and `GRID_ROWS=6`.
- The dock has 4 slots.
- Page −1 means the unfolded leading workspace in cell addressing, but it means Discover in pager addressing (`docs/architecture.md`).
- `LayoutPreset` holds separate compact and expanded values for icon size, row gap and dock geometry (`LayoutModel.kt`).

**Fold detection**
- Width only: `maxWidth >= 650dp` (`LayoutModel.kt:47`, `LauncherScreen.kt:418`).
- There is no `WindowInfoTracker`/`FoldingFeature`, so no posture or hinge awareness in `main`.
- Unfolded, `ExpandedWorkspace` (`LauncherScreen.kt:1197`) shows overlapping pairs: leading workspace + Home 1, then Home 1 + Home 2, and so on.
- Debug-only fold probes live in `app/src/debug/`.

**Visual tokens** are scattered:
- `Appearance.kt:77-83` (`DuoPalette`)
- `LauncherScreen.kt:92-119` (`DuoTheme`, `Ink`, `Glass`)
- `DuneWallpaper.kt`
- inline colors elsewhere

The look is translucent `Glass.copy(alpha)` surfaces with 1dp white borders and no blur. Corner radii of 24, 16, 18 and 30dp are used ad hoc. Typography is the default M3 scale. Icons are Material Rounded.

**Icons**
- `LauncherModel.launcherIcon()` rasterizes adaptive icons into a fixed **144px** rounded square.
- Icons render at up to 68dp, which is soft on a Fold.
- The monochrome layer is never used, and `AppEntry` holds a `Bitmap`.

**The existing right-side dock and `StatusRail.kt` already match iPhone Duo's side-rail concept.** This is the strongest hook for the redesign.

**Features present:**
- Home pages and a 4-slot dock.
- All apps (`AppLibrary.kt`: A–Z sections, local search, Personal/Work).
- Folders (`FolderEditing.kt`, `FolderPanel.kt`).
- Native widgets with a picker, resize and reconfigure (`WidgetController.kt`, `WidgetPicker.kt`, `WidgetSizing.kt`).
- Work profile.
- Google global search intent (`GoogleSearch.kt`).
- Live Discover via a hand-rolled `ILauncherOverlay` binder (`DiscoverClient.kt`, `DiscoverBounds.kt`, Window Extensions 8–10 guard).
- Photo background with procedural dunes (`LauncherBackground.kt`, `DuneWallpaper.kt`).
- Light/Dark/System/Sunrise appearance (`Appearance*.kt`, `SolarSchedule.kt`).
- Status rail.
- Shade gestures via an accessibility service (`SystemShadeController.kt`).
- JSON layout backup through SAF (`LayoutBackup.kt`, `BackupController.kt`).
- Single-level undo.
- First-run setup (`SetupExperience.kt`).
- Customization sheet (`CustomizationSheet.kt`).

**Features absent** (evidence is no grep hits or `README.md` "Known limits"):
- grid size setting
- widget stacks
- icon packs
- themed/monochrome icons
- icon shapes
- notification dots
- app shortcuts (`LauncherApps.getShortcuts`)
- uninstall
- hide apps
- Private Space
- double-tap to sleep
- swipe-up drawer / swipe-down search
- blur
- dynamic color
- predictive back opt-in
- system wallpaper passthrough (`windowShowWallpaper`)
- lock layout
- auto-add new apps
- localization (only 3 strings in `strings.xml`)
- suggestions row
- contacts, settings and web search results

**Tests**
- 136 JVM unit tests (`app/src/test`, `app/src/testDebug`), all pure logic.
- 123 instrumented tests (`app/src/androidTest`), which need disposable emulators with Google, Clock and Chrome.
- CI (`.github/workflows/ci.yml`) builds from the public-source export and runs assemble, unit tests, lint and assembleRelease.
- There is no ktlint or detekt.

**Constraints from the repo**
- `PRIVACY.md`: no INTERNET permission, no analytics or accounts, location only on explicit request.
- `CONTRIBUTING.md`: preserve one-page-per-swipe, native widget scroll and long-press pickup, placements, widget bindings and Home-page retention.
- `PUBLIC-FILES` plus `scripts/check-public-source.sh`: export allowlist; the check rejects absolute `/Users/<name>/` paths in any text file.

**Local build environment (blocking)**
- No JDK 17. Only Java 1.8 is installed.
- No Android SDK, no `local.properties` and no Android Studio.
- `adb` is installed via Homebrew, but no device is connected.
- The project **cannot be compiled or tested on this machine** as it stands.

**Files that will be touched:**
- Nearly all of `app/src/main/java/com/jake/duolauncher/`, above all `LauncherScreen.kt` (to be split), `LauncherModel.kt`, `HomeEditing.kt`, `LayoutModel.kt`, `Appearance.kt`, `AppLibrary.kt`, `FolderPanel.kt`, `StatusRail.kt`, `CustomizationSheet.kt`, `LauncherActionSheet.kt` and `MainActivity.kt`.
- `AndroidManifest.xml`, `res/values/*`, `app/build.gradle.kts`.
- `README.md`, `docs/*`, `PRIVACY.md`, `CHANGELOG.md`.

## 4. Prior art

### Apple iPhone Duo / iOS 27.1 (announced 2026-09-09, ships Oct 23)

Sources: [Newsroom](https://www.apple.com/newsroom/2026/09/apple-unveils-iphone-duo/), [HIG: Designing for iPhone Duo](https://developer.apple.com/design/human-interface-guidelines/designing-for-iphone-duo), [MacRumors](https://www.macrumors.com/2026/09/10/apple-details-how-ios-27-adapts-to-iphone-duo/).

**Hardware**
- 7.6" inner display (about 1878×2670) and 5.4" outer display (about 1398×2034).
- Both displays have the same aspect ratio, so apps **scale rather than reflow**.

**Side rail**
- The Dock, Lock Screen controls and app toolbars/tab bars sit on a **vertical side rail**.
- This applies on the outer display and on the inner display in landscape. In inner portrait, bars stay horizontal.
- Toolbar order runs from the top: Back/Close first, then the prominent action. Overflow fills from the bottom.
- The rail stays on the same hardware side even in right-to-left languages.
- Hands-on reports put the Dock on the **right**, where the thumb sits ([Gizmodo](https://gizmodo.com/iphone-duo-hands-on-2000808932)).

**Status bar and Dynamic Island**
- The status bar becomes a **circular corner cluster**.
- The Dynamic Island runs **vertically** along the side and expands for alerts and Live Activities.

**Home Screen, unfolded:** two pages. The left page holds a scrolling **Today View widget column** and the right page holds apps ([TheApplePost](https://www.theapplepost.com/2026/09/09/71990/iphone-duo-brings-split-view-multitasking-to-iphone-for-the-first-time/)). The HIG rules for layout are:
- Prefer an **even number of grid columns** so content splits cleanly at the fold.
- Make small adjustments rather than rearranging content.
- Keep content out of the fold region when the device is partly open.
- Show one extra level of hierarchy on the inner display.

**Unfold motion:** the UI appears to extend from the outer screen onto the inner screen with a "see-through" effect and "comes into focus".

**iOS 27 system**
- Swiping down in the middle of the Home Screen opens **"Search or Ask"** ([MacRumors](https://www.macrumors.com/2026/09/15/50-new-things-iphone-can-do-ios-27/)).
- A new **extra-large 4×6 widget** "designed for the inner display of iPhone Duo" ([MacStories](https://www.macstories.net/stories/ios-and-ipados-27-review/3/)).

**Liquid Glass**
- Translucent material that takes color from what is behind it, with specular highlights and **concentric corners**.
- iOS 27 adds a **transparency slider** from "ultra clear" to "fully tinted", darker glass edges and brighter specular highlights ([MacRumors](https://www.macrumors.com/2026/06/10/how-liquid-glass-is-changing-in-ios-27/)).
- Materials guidance ([HIG](https://developer.apple.com/design/human-interface-guidelines/materials)):
  - Use the regular variant for text-heavy UI.
  - The clear variant is only for use over rich media and needs a 35% dimming layer over bright content.
  - Vibrant label hierarchy: primary, secondary, tertiary.

**App icons** come in 6 appearances: Default, Dark, Clear Light, Clear Dark, Tinted Light, Tinted Dark. They can be shown large with labels hidden, and home backgrounds can be set to light, dark or auto ([HIG](https://developer.apple.com/design/human-interface-guidelines/app-icons), [TechCrunch](https://techcrunch.com/2025/09/24/how-to-customize-your-iphone-home-screen-for-ios-26s-liquid-glass/)).

**Widgets:** 16pt margins (11pt when tight), `ContainerRelativeShape` corners, and full color, accented or vibrant rendering modes.

**Not published or unknown:**
- blur radii and corner-radius values
- the Duo icon grid (rows, columns, spacing)
- Duo behavior for App Library, Notification Center and Control Center
- Duo widget sizes

Where Apple is silent, the design will extrapolate from iPadOS 26/27.

### One UI Home 9 / Pixel Launcher (Android 17)

Features a third-party launcher can copy with public APIs:
- Per-display grids, plus One UI's cover-screen mirroring with independently saved layouts ([Samsung](https://www.samsung.com/us/support/answer/ANS10013191/)).
- Grid sizes, and lock home layout.
- Add new apps to Home.
- Hide apps.
- Badges as Dot or Number, through `NotificationListenerService`.
- App shortcuts: `LauncherApps.getShortcuts`, which needs the default Home role.
- Uninstall and App info.
- Themed icons: `AdaptiveIconDrawable.getMonochrome()` with a generated fallback. Pixel forces themed icons since A16 QPR2 ([9to5Google](https://9to5google.com/2025/09/16/android-16-auto-themed-icons-apps-cant-opt-out/)).
- Icon shapes: circle, square, cookie, arch.
- Labels toggle (Android 17).
- Folder color/name and large folders (One UI 8.5).
- Widget stacks (One UI only).
- Search bar toggle.
- Swipe up for the drawer and swipe down for notifications or search.
- Double-tap to sleep: accessibility `GLOBAL_ACTION_LOCK_SCREEN`, which extends the existing service.
- Work tab with pause.
- **Private space**: `ROLE_HOME` + `ACCESS_HIDDEN_PROFILES` ([Android 15 behavior changes](https://developer.android.com/about/versions/15/behavior-changes-all)).
- Dynamic color.
- Predictive back.
- Showing the system wallpaper, with a dark-mode scrim.
- Suggested apps from local launch history.

**Restricted or impossible for third-party launchers:**
- Recents/QuickStep animations and the system taskbar ([Android Police](https://www.androidpolice.com/third-party-android-launcher-developers-join-forces-voice-frustrations-to-google/)).
- App pairs and split launch.
- Samsung pop-up view.
- Bubble any app.
- Native At a Glance (`SmartspaceManager`).
- `AppPredictionManager`.
- System wallpaper dim.
- A full Settings search index.

**Copy:**
- One UI's independent cover/main layouts.
- One UI's widget stacks and large folders.
- Pixel's forced themed icons and icon shapes.
- iOS 27's glass slider and icon appearance modes.

**Avoid:**
- Pixel's Nov-2025 search regression, which removed contacts and settings results.
- Needless permissions: every access stays optional, per the privacy promises in `PRIVACY.md`.

**Reference launchers:** Lawnchair 15, which implements private space ([docs](https://docs.lawnchair.app)), and Smartspacer for an At a Glance equivalent ([GitHub](https://github.com/KieronQuinn/Smartspacer)).

## 5. Recommended building blocks

| Need | Choice | Why | Rejected |
|---|---|---|---|
| Real blur / glass | Compose `Modifier.blur` + `RenderEffect` (API 31+). Blur a wallpaper snapshot behind surfaces using the **Haze** library (`dev.chrisbanes.haze`, Apache-2.0) | Haze gives backdrop blur for Compose on API 31+ and is widely used | Only faking glass with alpha, which is today's look and cannot read as Liquid Glass |
| Posture awareness | `androidx.window` `WindowInfoTracker` / `FoldingFeature` (already a dependency) | Official API for the hinge and half-opened state | Keeping the width-only 650dp heuristic |
| Notification dots | `NotificationListenerService` (opt-in) | The only public path | Reading notifications through accessibility, which breaks the privacy promise |
| App shortcuts | `LauncherApps.getShortcuts` / `startShortcut` / `pinShortcuts` / `PinItemRequest` | Public, and available to the default launcher | none |
| Themed icons | `AdaptiveIconDrawable.getMonochrome()` + luminance-mask fallback | Matches Pixel and iOS Tinted icons | Icon packs only, which don't cover apps without a pack |
| Icon packs | ADW/Nova `appfilter.xml` convention | De facto standard that most packs ship | A custom format |
| Icon rendering | Rasterize at the actual display px (dp × density), in a cached LRU | Fixes soft 144px icons | Coil/Glide, which add a dependency for local drawables |
| Lock / double-tap sleep | Extend the existing `SystemShadeAccessibilityService` with `GLOBAL_ACTION_LOCK_SCREEN` | Reuses the access users already grant, and keeps biometrics working | `DevicePolicyManager.lockNow`, which forces PIN and blocks uninstall |
| Private space | `LauncherApps.getLauncherUserInfo` + `ACCESS_HIDDEN_PROFILES` + `requestQuietModeEnabled` | Official Android 15 path | Ignoring it |
| Persistence | Keep the existing JSON-in-prefs model with a schema 9 migration; move writes off the main thread and debounce them | Existing convention; preserves user layouts | Migrating to Room/DataStore mid-overhaul, which risks the layout |
| Dynamic color | `android.R.color.system_accent1_*` / Compose `dynamic*ColorScheme` as an optional glass tint | Built in | none |
| Typography | Inter (OFL) bundled as the closest free SF Pro analog, or the system font | SF Pro's license prohibits use on non-Apple platforms | Bundling SF Pro, which is a license violation |
| Motion | Compose `spring()` with iOS-like parameters (damping ≈0.8–0.9, response ≈0.35–0.5s) | Matches iOS spring feel | Tweens |
| Symbols | Material Symbols Rounded (existing Icons Extended) | SF Symbols licensing forbids non-Apple use | SF Symbols |

**Legal note:** the look can echo Liquid Glass. The app must not ship Apple fonts, SF Symbols, Apple wallpapers, icons or the "iPhone"/"Apple" marks. The README already disclaims affiliation.

## 6. Constraints and risks

1. **No local toolchain (blocking).** Without JDK 17 and Android SDK 36, nothing can be compiled or unit-tested here, and a large Compose refactor done blind will not compile on the first try. Fix: install `openjdk@17` and the Android command-line tools (Homebrew) and accept SDK licenses. That is roughly a 1.5 GB download. It needs user consent because it changes the machine.
2. **No device or emulator attached.** Instrumented tests and visual checks need an emulator (with system images, a large download) or the Fold 8 connected over USB/wireless ADB. The repo says instrumented tests must never run on a personal phone, because some fixtures alter state.
3. **Scale.** This is effectively a v1.0 rewrite of the UI layer. Regression risk is highest in gestures (one-page swipe, widget vertical scroll, long-press pickup), widget bindings and saved layouts. It must ship in ordered, individually buildable slices.
4. **Blur performance.** Backdrop blur on the Fold 8's inner display at 120Hz across the dock, folders and sheets can drop frames. Use one shared blurred wallpaper layer rather than a per-surface live blur.
5. **Wallpaper passthrough vs blur.** If the launcher shows the system wallpaper (`windowShowWallpaper`), it cannot read or blur it on modern Android. Real glass blur therefore needs the launcher's own background (the photo or dunes it already uses), with only a tinted glass fallback over the system wallpaper.
6. **Restricted settings.** On a sideloaded APK, Android 13+ blocks notification-listener and accessibility grants until the user allows "restricted settings" in App info. Setup must guide the user through this.
7. **Discover.** It already works through a fragile private binder and version guards. It could conflict with an iOS-style Today View on the unfolded left page.
8. **Repo ownership.** `origin` is `jakesgoodapps/DuoLauncher` and the local git user is `Killswit3h`, so pushing to `origin` is probably not permitted and the pipeline's push step may need a fork. The upstream `.gitignore` also deliberately keeps agent files out of the public tree.
9. **CI and public export.** New files must be added to `PUBLIC-FILES`, and docs must contain no absolute user paths.
10. **iPhone Duo documentation is incomplete.** Xcode 27.1 and "Preparing your app" are due later in September. Grid numbers and blur values must be extrapolated.
11. **Samsung behavior with third-party Home.** The taskbar may be disabled when Duo is the Home app, and Recents animations will look generic. These can't be fixed from the app.

## 7. Open questions for the Spec Designer

**Resolved with user (2026-09-15):** Q1 → install JDK 17 + SDK + Fold-sized emulator. Q2 → keep upstream public-release constraints (package id, no INTERNET, opt-in access, PUBLIC-FILES/CI); git target is local `main` only, no push. Q3 → Today View widget column on the unfolded left page, Google Discover kept as an optional setting. Remaining questions below are for the spec to propose defaults.

1. **Toolchain:** may the build phase install JDK 17 + Android SDK (Homebrew, about 1.5 GB) and an emulator image? Or will the user connect the Fold 8 for manual testing?
2. **Distribution and push target:** personal sideload only, or keep upstream public-release compatibility (privacy promises, `PUBLIC-FILES`, CI)? Where should the branch be pushed: a fork, or local only?
3. **Unfolded left page:** iOS Today View widget column (recommended, per Duo) vs Google Discover vs user choice?
4. **Grid:** default to an even column count per the HIG (e.g. 6×6 inner portrait, 4×6 cover) with configurable rows/columns, while migrating existing 4×6 layouts safely?
5. **Dock side:** keep right (matches Duo), with a left option?
6. **Blur source:** accept a launcher-drawn wallpaper for real blur, with system wallpaper as a tinted-glass option?
7. **Brand and naming:** keep the "Duo Launcher" name and package `com.jake.duolauncher` (required for update compatibility with installed builds)?
8. **Scope ordering:** since this cannot land at once, which slices come first? Proposed: (a) design system and glass, (b) icons and shortcuts/dots, (c) Today View and grid/fold, (d) drawer and search, (e) stacks, folders, hide apps and private space, (f) polish, localization and docs.
