# Build Plan: Duo OS (`duo-os`)

Spec: `specs/02-spec.md` (approved 2026-09-15) · Brief: `specs/01-research-brief.md`

## 1. Stack (detected, unchanged)

| Item | Value |
|---|---|
| Language / UI | Kotlin 2.1.20, Jetpack Compose, Material 3 (BOM 2025.06.01) |
| Build | AGP 8.11.0, Gradle 8.14.3, JDK 17, single `:app` module |
| SDK | compileSdk/targetSdk 36, minSdk 31 |
| State | `LauncherModel : AndroidViewModel` exposing `StateFlow<LauncherState>` |
| Persistence | SharedPreferences holding hand-written `org.json`, versioned schema with migration backups |
| Tests | JUnit 4 JVM tests (136), Compose UI + UiAutomator instrumented tests (123) |
| Verify | `./scripts/gradle.sh :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` |

Baseline confirmed on this machine 2026-09-15: build succeeds, unit tests and lint pass, `app-debug.apk` produced. Emulator `DuoFold` (API 36, Fold profile) is available for instrumented runs.

**New dependencies (only these):**
- `dev.chrisbanes.haze:haze` — backdrop blur for Compose (Apache-2.0). Rationale in ADR-2.
- Inter variable font as a bundled resource (SIL OFL 1.1).
- `androidx.window` is already present and will now be used for `WindowInfoTracker` postures.

No networking, DI, Room or DataStore dependency is added. `THIRD_PARTY_NOTICES.md` gains Haze and Inter.

## 2. Architecture

### 2.1 Package map

The root package `com.jake.duolauncher` stays the home of every existing type, so the 36 instrumented test classes and 17 unit test classes keep compiling untouched (ADR-1). New subsystems go into subpackages:

```
app/src/main/java/com/jake/duolauncher/
├── (root)                  existing files, minus what is split out
│   ├── MainActivity.kt         + posture provider wiring, lock action, pin-request routing
│   ├── LauncherModel.kt        schema 9 state, layout sets, hidden apps, overrides
│   ├── HomeEditing.kt          grid rules made grid-size aware
│   ├── LayoutModel.kt          presets, geometry, grid spec
│   ├── LauncherScreen.kt       → reduced to composition root (< 400 lines)
│   ├── home/                   HomePager, HomeGrid, HomeItemTile, EditMode, PageOverview,
│   │                           DockRail, StatusCluster, ContextMenu, DragController
│   ├── design/                 DuoTokens, DuoColors, DuoType, DuoMotion, GlassSurface,
│   │                           BlurBackdrop, AccentResolver, ConcentricShapes
│   ├── icons/                  IconRenderer, IconCache, IconAppearance, IconShapes,
│   │                           IconPackRepository, IconOverrideStore, MonochromeFallback
│   ├── badges/                 DuoNotificationListener, BadgeRepository, BadgeModels
│   ├── shortcuts/              ShortcutRepository, PinItemActivity, ShortcutModels
│   ├── today/                  TodayView, TodayEditor, builtin/{Clock,Calendar,Batteries,
│   │                           NowPlaying,Suggestions}Widget, BuiltinWidgetHost
│   ├── stacks/                 WidgetStackHost, StackGestures, StackEditor
│   ├── library/                AppLibraryScreen, CategoryGrouper, AzListView
│   ├── search/                 SearchScreen, SearchEngine, providers/{Apps,Shortcuts,
│   │                           Settings,Contacts,Calculator,Web}Provider, SearchModels
│   ├── profiles/               PrivateSpaceRepository (+ existing ProfileSupport.kt in root)
│   ├── settings/               DuoSettingsScreen, SettingsSections, SettingsSearch
│   ├── history/                LaunchHistoryStore, SuggestionRanker
│   └── postures/               PostureProvider, PostureModels, HingeAvoidance
```

Existing `Discover*`, `Widget*`, `LayoutBackup`, `BackupController`, `LauncherBackground`, `DuneWallpaper`, `Appearance*`, `DeviceStatus*`, `SetupExperience`, `SystemShadeController`, `FolderEditing` stay where they are. `FolderPanel.kt`, `AppLibrary.kt`, `CustomizationSheet.kt`, `LauncherActionSheet.kt` and `StatusRail.kt` are rewritten in place or superseded by the new packages, with the old file deleted in the same commit that replaces it.

### 2.2 Internal contract (the "API" both tracks import)

There is no server here, so the integration contract is the set of Kotlin interfaces between the data/system track (backend-agent) and the UI track (frontend-agent). **These signatures are frozen once S1 lands; changes are announced to both tracks in one edit of this file.**

```kotlin
// design/DuoTokens.kt — owned by frontend-agent
object DuoTokens {
    val radius: Radii                 // icon, tile, card, sheet, dock, widget, folder
    val space: Spacing                // 2,4,8,12,16,20,24,32
    val motion: Motion                // springStandard, springSnappy, springGentle
    val type: DuoTypography
}
@Composable fun duoColors(): DuoColors     // ink, glassTint, accent, label 1..3, separator
@Composable fun GlassSurface(
    level: GlassLevel,                // BAR, PANEL, MENU, WIDGET
    shape: Shape = DuoTokens.radius.card,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
)

// icons/IconRenderer.kt — backend-agent
interface IconRenderer {
    fun request(app: ProfileAppId, sizePx: Int, style: IconStyle): ImageBitmap?   // cache hit or null
    suspend fun load(app: ProfileAppId, sizePx: Int, style: IconStyle): ImageBitmap
    fun invalidate(style: IconStyle? = null)
}
data class IconStyle(
    val appearance: IconAppearance,   // DEFAULT, DARK, CLEAR, TINTED
    val tint: Int, val tintIntensity: Int,
    val shape: IconShape,             // SQUIRCLE, CIRCLE, ROUNDED_SQUARE, SQUARE, SCALLOP
    val pack: String?, val dark: Boolean
)

// badges/BadgeRepository.kt — backend-agent
interface BadgeRepository {
    val badges: StateFlow<Map<ProfileAppId, BadgeCount>>   // empty when access is off
    val accessGranted: StateFlow<Boolean>
    fun badgeForFolder(apps: List<ProfileAppId>): BadgeCount
}

// shortcuts/ShortcutRepository.kt — backend-agent
interface ShortcutRepository {
    val hostPermission: StateFlow<Boolean>
    suspend fun shortcutsFor(app: ProfileAppId, limit: Int = 5): List<DuoShortcut>
    fun start(shortcut: DuoShortcut, bounds: Rect?)
    suspend fun pin(shortcut: DuoShortcut): Boolean
    fun iconFor(shortcut: DuoShortcut, sizePx: Int): ImageBitmap?
}

// search/SearchEngine.kt — backend-agent
interface SearchEngine {
    fun query(text: String): Flow<SearchResults>   // debounced, cancels previous
}
data class SearchResults(
    val apps: List<AppResult>, val shortcuts: List<ShortcutResult>,
    val settings: List<SettingResult>, val contacts: List<ContactResult>,
    val calculation: CalculationResult?, val web: WebResult?
)

// postures/PostureProvider.kt — backend-agent
interface PostureProvider {
    val posture: StateFlow<DuoPosture>   // FLAT, HALF_OPENED(hingeBoundsPx, orientation), UNKNOWN
}

// history/SuggestionRanker.kt — backend-agent
interface SuggestionRanker {
    fun record(app: ProfileAppId)
    fun suggestions(count: Int, at: Long = now()): List<ProfileAppId>
    fun clear()
}

// profiles/PrivateSpaceRepository.kt — backend-agent
interface PrivateSpaceRepository {
    val state: StateFlow<PrivateSpaceState>  // Unsupported(reason) | Locked | Unlocked(apps)
    fun setLocked(locked: Boolean)
}
```

Model-level additions owned by `LauncherModel` (backend-agent), consumed by UI through `LauncherState`:
`layoutSet`, `activeLayout(posture)`, `setGrid`, `setLayoutMode`, `reflow`, `setDockSide`, `setDockCapacity`, `hideApp`, `unhideApp`, `setIconOverride`, `setLeadingPage`, `todayItems`, `addToToday`, `moveTodayItem`, `createStack`, `addToStack`, `removeFromStack`, `setStackActive`, `setFolderTint`, `setFolderSize`, `setLockLayout`, `setAutoAddApps`, `setGesture`, `setGlass`, `setAccent`, `setBadgeStyle`.

### 2.3 Data and migration ownership

`LauncherModel` remains the only writer of `launcher/state`. The four existing out-of-model readers (`DiscoverActivity.kt:230`, `DiscoverBounds.kt:37,70`, `LiveDiscoverActivity.kt:233`) are replaced by a small read-only `LauncherStateSnapshot` helper so schema 9 does not break them.

Migration path: v8 → v9 writes `state_v8_backup` first, then converts a single page list into `LayoutSet(mode = MIRRORED, mirrored = <pages>, grid = 4×6)`, keeps `leadingSlots` as `LeadingPage.classic`, and defaults every new setting. A v9 JSON fixture and a real v8 fixture captured from the current build both get unit tests (AC-29).

### 2.4 Threading and performance

- Icon rendering, catalog refresh, icon-pack parsing, search indexing and persistence run on `Dispatchers.IO` or `Default`.
- Persistence is debounced (150 ms) and coalesced, replacing today's write-on-every-edit (NFR-P5).
- One `BlurBackdrop` composition-local holds a single blurred wallpaper layer that every `GlassSurface` samples (NFR-P6).
- `AppEntry` drops its `Bitmap` field and holds an icon key instead, so state equality stops comparing bitmaps.

### 2.5 Permissions and their boundaries (security-agent owns review)

| Permission / access | Added in | Guard |
|---|---|---|
| `BIND_NOTIFICATION_LISTENER_SERVICE` service | S2 | Exported with the system permission only; reads package/user/channel badge flag/media metadata; nothing persisted (NFR-S3) |
| `REQUEST_DELETE_PACKAGES` | S2 | Only for the Uninstall action |
| `READ_CONTACTS` | S5 | Runtime, opt-in from Search settings; queried on demand; never stored |
| `READ_CALENDAR` | S4 | Runtime, opt-in from the Calendar widget |
| `ACCESS_HIDDEN_PROFILES` | S5 | Requires `ROLE_HOME`; container hidden otherwise |
| Accessibility (existing) | S3 | Gains `GLOBAL_ACTION_LOCK_SCREEN` only; still `eventTypes=0`, no window content |
| `SET_WALLPAPER` | S1 | Only for the system-wallpaper option |

No INTERNET permission. Every item above stays optional, and the app works fully without any of them.

## 3. Architecture decisions (ADR)

**ADR-1 — Keep the root package; add subpackages only for new code.** Moving existing classes into subpackages would touch all 53 test files and every import, creating a huge diff that hides real changes and risks breaking instrumented fixtures. Alternative considered: a full clean-architecture repackage. Rejected as pure churn during a feature build; file-size health (NFR-M1) is achieved by splitting `LauncherScreen.kt` into `home/` files, which is new code.

**ADR-2 — Use Haze for blur rather than hand-rolled `RenderEffect`.** Compose's `Modifier.blur` blurs a composable's own content, not what is behind it, so a backdrop needs a render-node graph that Haze already implements and maintains for API 31+. Alternative: capture the wallpaper to a bitmap and blur it once with `RenderEffect` ourselves. We keep that as the documented fallback path when Haze reports unsupported (error table), but not as the primary.

**ADR-3 — One blurred wallpaper layer, not per-surface live blur.** The Fold 8's inner display at 120 Hz cannot afford 6 independent backdrop blurs. The wallpaper is drawn once into a blurred layer that all glass surfaces sample with a tint, which also matches how iOS composites glass.

**ADR-4 — Layout data is a `LayoutSet`, never a live migration between cover and inner.** Storing mirrored, cover and inner layouts independently means switching modes or grids is never destructive, which is the invariant behind FR-32/FR-33 and the regression boundary.

**ADR-5 — Built-in widgets are Compose, not `AppWidgetProvider`s.** They live inside the launcher process, so they can use tokens, glass and springs directly, need no host binding, and cannot be broken by a widget-host restore. The cost is they are unavailable to other launchers, which is irrelevant here.

**ADR-6 — Search is a provider list with a fixed ranking, not a plugin system.** Each provider returns a typed result section; the engine merges with a fixed section order and per-section limits. This keeps result latency predictable (NFR-P3) and avoids a general extension surface that would need its own security review.

## 4. Work orders

Tracks are named for the pipeline's agents. In this project "backend" means data, system integration and pure logic; "frontend" means Compose UI.

### 4.1 Backend track (data, system, logic)

| # | Task | FRs | Files |
|---|---|---|---|
| B1 | Schema 9 model: `LayoutSet`, grid spec, dock config, leading page, stacks, folder tint/size, hidden apps, icon overrides, settings block; migration from v8 with backup; `LauncherStateSnapshot` for the Discover readers | FR-31–36, 39, 49, 50, 55, 58, 61, 66, 75 | `LauncherModel.kt`, `LayoutModel.kt`, `HomeEditing.kt`, `DiscoverActivity.kt`, `DiscoverBounds.kt`, `LiveDiscoverActivity.kt` |
| B2 | Grid-size-aware placement and reflow with undo; widget span clamping | FR-31, 33, 34 | `HomeEditing.kt`, new `home/Reflow.kt` |
| B3 | Debounced off-main persistence; `AppEntry` icon-key refactor | NFR-P5 | `LauncherModel.kt` |
| B4 | Icon pipeline: density-correct renderer, LRU cache with `onTrimMemory`, appearances, shapes, monochrome fallback | FR-12, 14–17 | `icons/*` |
| B5 | Icon packs: `appfilter.xml` discovery and parsing (hardened), per-app overrides | FR-18, 19, NFR-S5 | `icons/IconPackRepository.kt`, `icons/IconOverrideStore.kt` |
| B6 | Badges: notification listener, repository, folder aggregation, access state | FR-20–23 | `badges/*`, `AndroidManifest.xml` |
| B7 | Shortcuts: query/start/pin, pin-item confirm activity, uninstall action | FR-25–29 | `shortcuts/*`, `MainActivity.kt`, `AndroidManifest.xml` |
| B8 | Postures: `WindowInfoTracker` provider, hinge-avoidance geometry | FR-42–44 | `postures/*` |
| B9 | Lock action on the existing accessibility service; gesture settings plumbing | FR-51–53 | `SystemShadeController.kt`, `LauncherModel.kt` |
| B10 | Launch history + suggestion ranker (capped, clearable, not backed up) | FR-84, 59, 68 | `history/*` |
| B11 | Built-in widget data sources: clock ticks, calendar query, battery, media session | FR-59 | `today/builtin/*`, `DeviceStatus.kt` |
| B12 | Stack rules: create, add, remove, collapse-to-widget, active index, smart rotate | FR-61–64 | `stacks/StackRules.kt`, `LauncherModel.kt` |
| B13 | App Library categorization and A–Z indexing | FR-68–70 | `library/CategoryGrouper.kt` |
| B14 | Search engine and providers with ranking and debounce | FR-71–74 | `search/*` |
| B15 | Private space repository | FR-76–78 | `profiles/PrivateSpaceRepository.kt`, `AndroidManifest.xml` |
| B16 | Backup v3 encode/decode; v2 import; newer-version refusal | FR-82, 83 | `LayoutBackup.kt`, `BackupController.kt` |
| B17 | Unit tests for all pure logic above | NFR-M2 | `app/src/test/**` |

### 4.2 Frontend track (Compose UI)

| # | Task | FRs | Files |
|---|---|---|---|
| F1 | Design tokens, colors, typography (Inter), motion specs, concentric shapes | FR-1, 10, 11, 13 | `design/*`, `res/font/*` |
| F2 | `GlassSurface` + single `BlurBackdrop`, specular rim, dim layer, reduce-transparency | FR-2–7, NFR-P6 | `design/GlassSurface.kt`, `design/BlurBackdrop.kt` |
| F3 | Wallpaper source switch (Duo vs system), dark dim, accent resolver | FR-4, 8, 9 | `LauncherBackground.kt`, `DuneWallpaper.kt`, `design/AccentResolver.kt` |
| F4 | Split `LauncherScreen.kt` into `home/` composables; composition root ≤ 400 lines; delete dead code | NFR-M1 | `LauncherScreen.kt`, `home/*` |
| F5 | Icon tiles, labels, large icons, badges rendering | FR-14–17, 20–22 | `home/HomeItemTile.kt` |
| F6 | Glass context menu with shortcuts and actions, drag-to-pin | FR-24–27, 30 | `home/ContextMenu.kt` |
| F7 | Pin-request confirm sheet | FR-28 | `shortcuts/PinItemActivity.kt` UI |
| F8 | Dock rail (side, capacity, folders) and corner status cluster | FR-37–41 | `home/DockRail.kt`, `home/StatusCluster.kt` (replaces `StatusRail.kt`) |
| F9 | Grid rendering for configurable grids, spread layout, hinge avoidance, landscape | FR-31, 42–44 | `home/HomeGrid.kt`, `home/HomePager.kt` |
| F10 | Edit mode (jiggle, − badges, toolbar), page overview, lock behavior | FR-45–49 | `home/EditMode.kt`, `home/PageOverview.kt` |
| F11 | Gestures: swipe down (Search/Notifications), swipe up (Library), double-tap lock, preserving one-page swipe | FR-51–54 | `PageGestures.kt` |
| F12 | Today View column, editor, drag between Today and Home | FR-55–58 | `today/TodayView.kt`, `today/TodayEditor.kt` |
| F13 | Built-in widget UIs in 4 sizes | FR-59 | `today/builtin/*` |
| F14 | Android widget clipping and margins | FR-60 | `WidgetController.kt`, `home/HomeGrid.kt` |
| F15 | Widget stack host, vertical paging that respects native scroll, dots, stack editor | FR-61–64 | `stacks/*` |
| F16 | Folder redesign: zoom panel, pages, tint, large folders, create-by-drop | FR-65–67 | `FolderPanel.kt`, `home/HomeGrid.kt` |
| F17 | App Library: categories, group expansion, A–Z view, work filter | FR-68–70 | `library/*` (replaces `AppLibrary.kt`) |
| F18 | Search UI with sections, keyboard, Ask control | FR-71–74 | `search/SearchScreen.kt` |
| F19 | Private container UI, hidden-apps UI | FR-75–78 | `library/*`, `settings/*` |
| F20 | Duo Settings screen with sections and settings search | FR-79, 80 | `settings/*` (replaces `CustomizationSheet.kt`) |
| F21 | Onboarding refresh | FR-81 | `SetupExperience.kt` |
| F22 | Predictive back for all launcher surfaces | FR-85 | `MainActivity.kt`, `AndroidManifest.xml`, sheets |
| F23 | String extraction, TalkBack labels, font-scale pass | NFR-A1, A3, M4 | `res/values/strings.xml`, all UI |

### 4.3 Security track (review + hardening)

| # | Task | Trigger |
|---|---|---|
| S-1 | Review the notification listener: data minimization, no persistence, no logging of titles/text, correct export guard | after B6 |
| S-2 | Review icon pack parsing: untrusted XML, entity expansion, resource limits, drawable bounds, failure isolation | after B5 |
| S-3 | Review the pin-item activity: only accepts genuine `PinItemRequest`, no arbitrary intent execution, locked-layout path | after B7 |
| S-4 | Review contacts and calendar usage: on-demand only, permission checks, no caching, correct behavior on denial | after B14, B11 |
| S-5 | Review private space: no leakage while locked (search, suggestions, badges, backups) | after B15 |
| S-6 | Review backup v3: no notification/contact/history data, size and type validation, newer-version refusal, path handling in SAF | after B16 |
| S-7 | Review the accessibility addition: still no window content, lock action only, correct disclosure | after B9 |
| S-8 | Full-diff pass: no INTERNET, no new exported components without guards, no logging of user content, `PRIVACY.md` matches reality, `check-public-source.sh` passes | before inspection |

## 5. Build sequence

Stages are sequential; tasks inside a stage may interleave between tracks where noted. **Every stage ends with:** `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug` green, one or more Conventional Commits, and a clean tree.

| Stage | Contents | Parallelism |
|---|---|---|
| **S1** | F1, F2, F3, F4, B3 | F4 (the split) lands first so later UI work edits small files; F1/F2 can proceed alongside |
| **S2** | B4, B5, B6, B7, F5, F6, F7 → S-1, S-2, S-3 | B4/B5 before F5; B6/B7 before F6 |
| **S3** | B1, B2, B8, B9, F8, F9, F10, F11 | B1 gates everything in this stage; B8 parallel with B1 |
| **S4** | B11, B12, F12, F13, F14, F15, F16 | B11/B12 before F13/F15 |
| **S5** | B10, B13, B14, B15, F17, F18, F19 → S-4, S-5 | B13/B14 before F17/F18 |
| **S6** | B16, F20, F21, F22, F23, B17 top-up, docs, version bump → S-6, S-7, S-8 | docs last |

Gate before inspection: instrumented suite on `DuoFold` (emulator), plus a manual device pass on the Fold 8 for the items marked *(device)* in the spec.

## 6. Git plan (per `.claude/skills/git-workflow/`)

- Branch `feat/duo-os` cut from `main` with a clean tree, **before any code**.
- First commit: the three spec documents.
- One Conventional Commit per work-order task, subject referencing FR numbers, e.g.
  `feat(icons): themed and tinted icon appearances (FR-14, FR-15)`.
- `main` is never committed to directly. No pushing to any remote (user decision).
- Merge `--no-ff` into local `main` only after the inspector reports PASS.

## 7. Definition of done

**Backend track**
- Every FR in its work order is implemented and reachable from the UI.
- Schema 9 migration is proven by unit tests against a real v8 fixture, and no user data is lost.
- All new pure logic has JVM unit tests; the pre-existing 136 pass, except where an FR intentionally changes behavior (each such change is named in the commit).
- No main-thread disk or decode work (StrictMode clean in debug during editing).

**Frontend track**
- Every FR in its work order is implemented, with visual verification on the emulator at cover and inner sizes.
- No inline color literals outside the token module.
- No main-source file over 900 lines.
- TalkBack labels present, 48dp targets, font scale 1.3 without clipping.
- The regression-boundary gestures behave exactly as before (one-page swipe, native widget scroll, long-press pickup).

**Security track**
- All 8 reviews completed with findings either fixed or recorded in `specs/backlog.md` with an explicit risk note.
- No INTERNET permission; every new access optional, disclosed and revocable.
- `PRIVACY.md` updated to match the shipped behavior.

**Integration (my responsibility)**
- `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleRelease` all green; 0 lint errors.
- `scripts/check-public-source.sh` passes and `PUBLIC-FILES` covers new public files.
- Instrumented suite green on `DuoFold`.
- The debug APK installs over 0.15.0-beta01 on the Fold 8 with layouts intact *(device check by the user)*.
- Every FR-1…FR-85 is claimed by implemented code before handing to the inspector.
