# Feature: Duo OS — iPhone Duo look and feel with full Android launcher parity

Slug: `duo-os` · Source brief: `specs/01-research-brief.md` · Status: **DRAFT, awaiting user approval**

## 1. Overview and user value

Duo Launcher becomes a complete daily-driver Home app for the Galaxy Z Fold 8. It takes the design language of Apple's iPhone Duo on iOS 27.1:
- Liquid Glass materials, a transparency slider and concentric corners.
- A dock on a side rail and a status cluster in the corner.
- When unfolded, a two-page spread with a Today View widget column on the left.
- An edit mode with jiggling icons, glass context menus, an App Library and "Search" pulled down from Home.

It keeps Android's depth: grids, icon packs and shapes, themed icons, hidden apps, private space, gestures and widget freedom. It also adds the One UI and Pixel features a third-party launcher is allowed to have. Existing installs upgrade in place without losing layouts, widget bindings or settings.

Staged delivery. Every stage compiles, passes tests and leaves the app usable:

| Stage | Theme |
|---|---|
| S1 | Foundation and Liquid Glass design system |
| S2 | Icons, notification dots, app shortcuts, context menu |
| S3 | Home grid, dock, fold postures, edit mode, gestures |
| S4 | Today View, widgets, widget stacks, folders |
| S5 | App Library, Search, hidden apps, private space |
| S6 | Settings, onboarding, backup, accessibility, localization, docs, performance |

## 2. Scope

### In scope
All FR-numbered behavior in section 3.

### Out of scope (explicit)
- **Replacing system features:** Recents/QuickStep animations, the system taskbar, app pairs, split-screen or pop-up launch, bubbles, and the app close animation. These are restricted to the system launcher.
- **System APIs we can't use:** native At a Glance (`SmartspaceManager`), `AppPredictionManager`, system wallpaper dimming, and per-display system wallpapers.
- **System surfaces:** lock screen, Notification Center or Control Center replacements, a Dynamic Island overlay over other apps, and StandBy.
- **Network features:** anything that needs the INTERNET permission, such as a built-in weather provider, web suggestions or cloud backup.
- **Apple assets:** SF Pro / SF Symbols fonts, Apple wallpapers, Apple app icons and Apple trademarks.
- **Distribution and platforms:** Play Store distribution work, tablets and non-foldables as first-class targets, and changing the package id or signing.
- **Fold hardware:** hinge-angle-driven cross-display animation, which stays a debug-only experiment.
- **Git:** pushing to any remote.

### Later (→ `specs/backlog.md`)
- Smart stack auto-rotation driven by usage.
- Custom widget "Shortcuts".
- Calendar events in Today View beyond the next 3 events.
- Localized translations beyond English. Strings will already be externalized.
- Multiple Today View pages.
- A per-page wallpaper.
- Contact photos in search results.

## 3. Functional requirements (EARS)

### S1 — Foundation and Liquid Glass

**Design tokens**
- **FR-1:** The system shall define every color, material, corner radius, spacing, type style and motion spec in one design-token module. Screens shall not use inline color literals, except for fixtures, the wallpaper art and user-selected colors.

**Glass surfaces**
- **FR-2:** The system shall render the dock, folders, context menus, sheets, App Library, Search, Today View widget backings, page indicator and status cluster as Liquid Glass surfaces. Each surface has a translucent fill, a backdrop blur where supported, a specular top rim highlight and a darkened edge.
- **FR-3:** While the wallpaper source is **Duo wallpaper** (photo or built-in art), the system shall blur the wallpaper behind glass surfaces.
- **FR-4:** While the wallpaper source is **System wallpaper**, the system shall show the Android wallpaper behind Home and render glass as tinted translucency without blur.
- **FR-5:** When the user moves the **Glass** slider (0 = Clear, 100 = Tinted), the system shall update the opacity and tint of all glass surfaces live, within one frame of the slider changing.
- **FR-6:** Where **Reduce transparency** is enabled, the system shall render glass surfaces as opaque tinted fills with no blur.
- **FR-7:** While the Glass slider is below 30 and a surface sits over bright wallpaper, the system shall add a dimming layer of up to 35% under text so that labels meet the contrast requirement in NFR-A2.

**Color and dark mode**
- **FR-8:** The system shall offer these accent color options:
  - **Automatic** (derived from the wallpaper through Android dynamic colors, or from the Duo wallpaper's dominant color)
  - 8 presets
  - Custom (a hue picker)
- **FR-9:** Where **Dim wallpaper in dark mode** is enabled and dark appearance is active, the system shall draw a 25% black scrim over the wallpaper on Home.

**Motion and icons**
- **FR-10:** The system shall animate the following with spring motion specs (damping ratio 0.8–0.9, stiffness equivalent to a 0.35–0.5 s response):
  - opening and closing folders, menus, sheets, App Library and Search
  - icon press scale (0.9) and release
- **FR-11:** While the Android animator duration scale is 0, the system shall apply state changes without animation.
- **FR-12:** The system shall rasterize app icons at the device's actual pixel size for their displayed dp size, and cache them in a memory-bounded cache keyed by user, package, activity, style and size.

**Typography**
- **FR-13:** The system shall use a bundled open-license sans-serif typeface (Inter), with a tabular-figure variant for clocks. A **Use system font** option shall switch all launcher text to the device font.

### S2 — Icons, dots, shortcuts, context menu

**Icon appearance**
- **FR-14:** The system shall offer these icon appearances, applied to every icon on Home, the dock, folders, App Library and Search:
  - **Default**
  - **Dark**: darkened background layer, foreground unchanged
  - **Clear**: glass background with a monochrome foreground in white or black by appearance
  - **Tinted**: monochrome foreground in the selected tint color, with an intensity of 0–100
- **FR-15:** When an app lacks a monochrome adaptive icon layer and the appearance is Clear or Tinted, the system shall generate a monochrome glyph from the icon's foreground luminance.
- **FR-16:** The system shall offer these icon shapes, applied to adaptive icons:
  - Squircle (continuous-corner, default)
  - Circle
  - Rounded square
  - Square
  - Scalloped ("cookie")
- **FR-17:** The system shall offer a **Large icons** toggle, which hides labels and enlarges icons by 20%, independent of the per-display icon size slider.

**Icon packs**
- **FR-18:** When an installed app declares an ADW/Nova-compatible icon pack (`appfilter.xml`), the system shall list it under **Icon pack**. When one is selected, the system shall use its mapped icons and fall back to the selected appearance for unmapped apps.
- **FR-19:** When the user chooses **Edit icon** in an app's context menu, the system shall let the user:
  - pick a replacement icon from any installed icon pack
  - rename the label
  - reset both

  Overrides apply wherever that app appears.

**Notification badges**
- **FR-20:** Where notification access is granted and badges are enabled, the system shall show a badge on each app icon that has active badge-eligible notifications. Badges honor `NotificationChannel.canShowBadge()`. Style is **Dot** or **Number**.
- **FR-21:** When an app's last badge-eligible notification is removed, the system shall remove that app's badge within 1 second.
- **FR-22:** The system shall show on a folder tile, and on a stack's app-icon badge, the sum (Number) or presence (Dot) of its apps' badges.
- **FR-23:** If notification access is not granted, then the system shall hide all badges and show a **Turn on** row in Badge settings. That row explains Android's "Allow restricted settings" step for sideloaded installs.

**Context menu and app shortcuts**
- **FR-24:** When the user long-presses an app icon and releases without dragging, the system shall open a Liquid Glass context menu anchored to the icon, with the rest of Home blurred and dimmed.
- **FR-25:** The context menu shall list up to 5 app shortcuts (manifest and dynamic, ordered by rank) above these actions:
  - Edit Home Screen
  - Edit icon
  - App info
  - Hide app
  - Remove from Home (only when on Home or dock)
  - Uninstall (only when the app is uninstallable by the user)
- **FR-26:** When the user taps an app shortcut, the system shall launch it through `LauncherApps.startShortcut` with source bounds from the icon.
- **FR-27:** When the user drags an app shortcut out of the context menu onto Home, the system shall pin that shortcut and place it as a shortcut icon, badged with its app icon.
- **FR-28:** When another app requests to pin a shortcut or widget (`ACTION_CONFIRM_PIN_SHORTCUT` / `ACTION_CONFIRM_PIN_APPWIDGET`), the system shall show a glass confirmation sheet with a preview and **Add** or **Cancel**. **Add** places the item on the first free space on the current Home page, or on a new page.
- **FR-29:** When the user chooses **Uninstall**, the system shall hand off to Android's uninstall confirmation. When the package is removed, the system shall remove its placements, as existing package-removal handling already does.
- **FR-30:** While Duo is not the default Home app, the system shall omit app shortcuts from the context menu and show a single row: **Set Duo as Home app to see shortcuts**.

### S3 — Home, grid, dock, postures, edit mode, gestures

**Grids and layouts**
- **FR-31:** The system shall let the user set Home grid columns (4–8) and rows (4–8) separately for the **Cover** layout and the **Inner page** layout.
- **FR-32:** The system shall offer **Layout mode**:
  - **Mirrored**: one set of pages shown on both screens, using one grid
  - **Separate**: independent pages for cover and inner. Both are stored, so switching modes never discards either.
- **FR-33:** When a grid change leaves an item without a valid position, the system shall move it to the next free cell, then to a new page appended after the current last page. The system shall never delete it and shall offer **Undo** for 8 seconds.
- **FR-34:** When a widget's span exceeds the new grid, the system shall shrink it to the largest span within its provider limits that fits. If none fits, the widget moves to a new page.

**Upgrade and new-install defaults**
- **FR-35:** When upgrading from schema 8, the system shall migrate to:
  - Layout mode Mirrored with a 4×6 grid
  - the existing pages, dock, folders, widgets and bindings preserved exactly

  The schema-8 JSON shall be kept as a backup.
- **FR-36:** On a fresh install, the system shall default to:
  - Separate layouts
  - Cover 4×6 and Inner page 6×6
  - Today View as the leading page
  - dock on the right

**Dock**
- **FR-37:** The system shall place the dock as a vertical glass rail on the **Right** (default) or **Left** screen edge, by setting. The status cluster and page controls mirror to the same side.
- **FR-38:** The system shall let the user set dock capacity from 3 to 6.
- **FR-39:** The dock shall accept apps, pinned shortcuts and folders.
- **FR-40:** If a drop targets a full dock, then the system shall reject it and show "Dock full • Move an app out first", as today.

**Status cluster**
- **FR-41:** Where **Duo status** is enabled, the system shall replace the vertical status rail with a circular corner status cluster on the dock side. It shows time, battery ring, Wi-Fi and cellular indicators, using the existing `DeviceStatusMonitor` data.

**Fold postures and orientation**
- **FR-42:** When the inner display is half-opened with a vertical fold (`FoldingFeature` state `HALF_OPENED`), the system shall keep Home content, open folders, menus and sheets out of the hinge bounds plus a 16dp margin on each side.
- **FR-43:** While the inner display is in landscape or portrait, the system shall lay out the spread with no clipping or overlap.
- **FR-44:** When the device folds or unfolds, the system shall preserve:
  - the current Home page (or its nearest equivalent in Separate mode)
  - any open App Library or Search state
  - in-progress text in Search

**Edit mode**
- **FR-45:** When the user long-presses empty Home space, the system shall enter **Edit mode**, where:
  - icons and widgets jiggle
  - a remove (−) badge appears on removable items
  - a glass toolbar shows **Edit**, **+** (widgets), **Customize** (icons and glass), **Wallpaper** and **Done**
- **FR-46:** While in Edit mode, when the user taps an item's − badge, the system shall remove that placement. For apps and shortcuts this removes from Home only. For widgets it asks for confirmation and releases the binding.
- **FR-47:** While in Edit mode, when the user taps the page indicator, the system shall show a **Page overview** of all Home pages, where the user can hide or show, reorder and delete empty pages.
- **FR-48:** When the user taps Done, taps empty space or presses Back while in Edit mode, the system shall exit Edit mode.
- **FR-49:** While **Lock Home layout** is enabled, the system shall block:
  - drag, remove and Edit mode
  - auto-add
  - pin-request placement, which instead offers **Unlock**

  Launch and context-menu shortcuts keep working.

**Auto-add and gestures**
- **FR-50:** Where **Add new apps to Home** is enabled, when a new launchable app is installed for a profile, the system shall place it in the first free cell of the last Home page, or on a new page.
- **FR-51:** When the user swipes down on Home content (not inside widget content that consumes vertical scroll), the system shall perform the **Swipe down** action. The options are **Search** (default for new installs), **Notifications** and **None**. Upgrades that had shade gestures enabled keep Notifications and Quick Settings with the existing 70/30 split.
- **FR-52:** When the user swipes up on Home (starting above the system gesture area), the system shall perform the **Swipe up** action: **App Library** (default) or **None**.
- **FR-53:** Where **Double-tap to lock** is enabled and Duo's accessibility service is on, when the user double-taps empty Home space, the system shall lock the screen with `GLOBAL_ACTION_LOCK_SCREEN`.
- **FR-54:** The system shall keep one-page-per-swipe horizontal navigation across Home, dock and status, as today.

### S4 — Today View, widgets, stacks, folders

**Today View**
- **FR-55:** The system shall offer a **Leading page** setting:
  - **Today View** (default)
  - **Google Discover** (only where the existing Discover path is available)
  - **Classic workspace** (the existing leading grid)

  Contents of every option are preserved when switching.
- **FR-56:** While the leading page is Today View, the system shall show a vertically scrolling column of widgets. On the inner display it is the left pane of the first spread. On the cover display it is a page to the left of Home 1.
- **FR-57:** The system shall let the user add, remove and reorder Today View widgets through its own Edit button, and through drag-and-drop between Today View and Home.
- **FR-58:** On upgrade, the system shall keep existing Classic workspace contents intact. If a user switches to Today View, the widgets already placed on the leading workspace appear there in top-to-bottom, left-to-right order.

**Built-in widgets**
- **FR-59:** The system shall provide Liquid Glass built-in widgets in these sizes: small 2×2, medium 4×2, large 4×4, extra-large 4×6 (where the grid fits).
  - **Clock:** analog or digital.
  - **Date and Calendar:** date. Next 3 events where calendar access is granted.
  - **Batteries:** device battery.
  - **Now Playing:** active media session title, artist, artwork and play/pause/next, where notification access is granted.
  - **App Suggestions:** 4 or 8 apps from local launch history.

  Existing Clock and Date placements migrate to these widgets.

**Android widgets**
- **FR-60:** The system shall clip Android widgets to the concentric widget corner radius and use 16dp inner margins for built-in widgets.

**Widget stacks**
- **FR-61:** When the user drops a widget onto a widget of the same span in Edit mode, the system shall create a **Widget stack**. A stack holds up to 10 widgets.
- **FR-62:** When the user swipes vertically on a stack, the system shall show the next or previous widget with a spring and a side dot indicator. The exception is a gesture that begins on native widget content that consumes vertical scroll: that content scrolls instead, as today.
- **FR-63:** When the user chooses **Edit stack** from a stack's context menu, the system shall let them reorder, remove and add widgets, and toggle **Smart rotate**. Smart rotate advances by time of day using local launch history.
- **FR-64:** If a stack is left with one widget, then the system shall convert it into a plain widget.

**Folders**
- **FR-65:** When the user opens a folder, the system shall zoom it into a centered glass panel with an editable title and pages of 3×3 apps, 4×4 on the inner display, with page dots.
- **FR-66:** The system shall let the user set a folder's tint color (from the accent presets) and size: **1×1** (3×3 mini preview) or **2×2 Large**, which shows the first 4 apps as directly launchable icons plus a "more" tile.
- **FR-67:** When the user drops one app onto another on Home or the dock in Edit mode or during a drag, the system shall create a folder named from the apps' shared Android category, or "Folder" when there is none.

### S5 — App Library, Search, hidden apps, private space

**App Library**
- **FR-68:** The system shall present the **App Library** with a search field at the top and glass category groups:
  - **Suggestions** (local launch history)
  - **Recently Added** (last 14 days)
  - groups by Android app category (Social, Productivity, Games, Entertainment, Creativity, Information, Utilities, Other)

  Each group shows 3 large icons plus a 4-icon mini grid that opens the full group.
- **FR-69:** The system shall offer an **A–Z list** view of the App Library with section headers and a side letter scrubber, and remember the last chosen view.
- **FR-70:** The system shall keep the Personal and Work filtering and the paused-work controls in the App Library, as today.

**Search**
- **FR-71:** When Search opens (from swipe down, the search control or the App Library field), the system shall show a full-screen glass Search. It focuses the field and opens the keyboard, and shows Siri-Suggestions-style app suggestions before any typing.
- **FR-72:** When the user types, the system shall show results within 100 ms per keystroke, in these sections:
  - **Apps**: prefix, word-start and fuzzy match, ignoring case and accents
  - **App shortcuts**
  - **Settings**: a curated list of Android settings screens
  - **Contacts**: where contacts access is granted
  - **Calculation**: for arithmetic input
  - **Search the web**: hand-off via `ACTION_WEB_SEARCH`
- **FR-73:** When the user taps the **Ask** control in Search, the system shall open the device's default assistant (`ACTION_VOICE_COMMAND` / assist).
- **FR-74:** When the user presses the keyboard's search action, the system shall launch the top result.

**Hidden apps**
- **FR-75:** When the user hides an app, the system shall remove it from Home, dock, folders, App Library, Suggestions and Search results. It is listed under **Settings → Hidden apps**, where it can be opened or unhidden.

**Private space**
- **FR-76:** Where Android 15+, Duo is default Home and a private profile exists, the system shall show a **Private** container at the bottom of the App Library with a lock/unlock control (`requestQuietModeEnabled`).
- **FR-77:** While the private space is locked, the system shall hide its apps, badges, shortcuts and suggestions from all launcher surfaces, including Search.
- **FR-78:** Where **Hide private space** is enabled, the system shall omit the container until the user types "private" in Search.

### S6 — Settings, onboarding, backup, accessibility

**Settings**
- **FR-79:** The system shall provide a full-screen **Duo Settings** screen in iOS inset-grouped style with a search field. The sections are:
  - Wallpaper
  - Appearance (dark mode, glass, accent, font)
  - Icons
  - Home Screen and Dock
  - Today View
  - App Library and Search
  - Gestures
  - Badges
  - Hidden apps
  - Private space
  - Backup
  - Help
  - About
- **FR-80:** The system shall open Duo Settings from Edit mode's Customize control, from the long-press menu on empty space, and from a **Duo Settings** launcher entry.

**Onboarding**
- **FR-81:** On a fresh install only, the system shall show a Liquid Glass welcome flow:
  - Set as Home
  - Choose look (light/dark/auto, glass level, icon appearance)
  - Turn on badges (optional)
  - Gestures and lock (optional)
  - Done

  Each optional step is skippable. Upgrades never see it.

**Backup and restore**
- **FR-82:** The system shall include every setting and layout added by this spec in layout export (backup version 3). It shall still import version 2 backups with defaults for the new fields.
- **FR-83:** If a backup declares a version newer than supported, then the system shall refuse it and show "This backup was made by a newer Duo version."

**Launch history**
- **FR-84:** The system shall record app launches locally, capped at the most recent 500 events, for Suggestions and Smart rotate. **Clear suggestion history** and **Suggestions off** stop recording and delete the history.

**Predictive back**
- **FR-85:** The system shall support predictive back (`enableOnBackInvokedCallback`) for sheets, folders, menus, Search, App Library and Settings, with the preview animation on in-launcher surfaces.

## 4. Acceptance criteria (Given/When/Then)

Unless stated otherwise, each AC is verified on the `DuoFold` emulator in both folded (cover size) and unfolded states, and on the Fold 8 for items marked *(device)*.

### S1 — Foundation and Liquid Glass

**AC-1 (FR-1)**
- Given the main source set
- When searching for `Color(0x` outside the token module, wallpaper art and test fixtures
- Then zero matches.

**AC-2 (FR-2, FR-3)**
- Given Duo wallpaper with a photo
- When opening a folder
- Then the photo is visibly blurred behind the folder panel, and the panel has a lighter top rim and darker edge.

**AC-3 (FR-4)**
- Given System wallpaper is selected
- When returning Home
- Then the Android wallpaper is visible and glass shows a tint with no blur.

**AC-4 (FR-5)**
- Given Home is open
- When dragging the Glass slider from 0 to 100
- Then the dock and page indicator change from near-clear to solid tint continuously, with no restart or flicker.

**AC-5 (FR-6)**
- Given Reduce transparency is on
- When opening App Library
- Then its background is opaque.

**AC-6 (FR-7, NFR-A2)**
- Given a white photo wallpaper and Glass 0
- When measuring dock label contrast in a screenshot
- Then it is ≥ 4.5:1.

**AC-7 (FR-8)**
- Given Accent Automatic and a blue-dominant photo
- When opening Settings
- Then switches and highlights are blue-derived.
- When choosing a preset
- Then they change immediately.

**AC-8 (FR-9)**
- Given dark mode with Dim wallpaper on
- When comparing a screenshot to dim-off
- Then wallpaper luminance is lower by about 25%.

**AC-9 (FR-10, FR-11)**
- Given the animator scale is 1
- When opening a folder
- Then it zooms with an overshoot-free spring.
- Given the animator scale is 0
- When opening a folder
- Then it appears immediately.

**AC-10 (FR-12)** *(device)*
- Given icon size 68dp at xxxhdpi
- When capturing a screenshot
- Then icon edges are crisp, with no upscaling blur.
- Also: unit test verifies the bitmap size equals `dp × density`.

**AC-11 (FR-13)**
- Given defaults
- Then launcher text uses Inter.
- When enabling Use system font
- Then text switches to the device font.

### S2 — Icons, dots, shortcuts, context menu

**AC-12 (FR-14, FR-15)**
- Given Tinted with a red tint
- When viewing Home, dock, a folder, App Library and Search
- Then every icon is a red monochrome glyph, including an app without a monochrome layer (a test fixture app).

**AC-13 (FR-16)**
- Given shape Circle
- Then adaptive icons are circular on all surfaces.

**AC-14 (FR-17)**
- Given Large icons on
- Then labels are hidden and icons are 1.2× the slider size.

**AC-15 (FR-18)**
- Given a fixture icon pack is installed and selected
- Then mapped apps show pack icons, and unmapped apps show the chosen appearance.

**AC-16 (FR-19)**
- Given Edit icon on Chrome
- When choosing a pack icon and the label "Web"
- Then Chrome shows the new icon and label on Home and in App Library.
- When choosing Reset
- Then the originals return.

**AC-17 (FR-20, FR-21)**
- Given notification access is granted
- When a test app posts a notification
- Then its icon shows a dot within 1 s.
- When it is cancelled
- Then the dot disappears within 1 s.
- With Number style and 3 notifications, the badge shows "3".

**AC-18 (FR-22)**
- Given a folder containing the test app
- When the test app posts 2 notifications
- Then the folder badge shows "2".

**AC-19 (FR-23)**
- Given access is not granted
- Then no badges appear and Badge settings shows Turn on with the restricted-settings explanation.

**AC-20 (FR-24, FR-25)**
- Given Duo is default Home
- When long-pressing Chrome and releasing
- Then a glass menu appears next to the icon with Chrome's shortcuts (for example "New tab") above the actions, and Home is blurred behind it.

**AC-21 (FR-26)**
- When tapping "New tab"
- Then Chrome opens a new tab.

**AC-22 (FR-27)**
- When dragging "New tab" to an empty cell
- Then a pinned shortcut icon appears, and tapping it opens a new tab.

**AC-23 (FR-28)**
- Given a fixture app requests a pinned shortcut
- Then the confirmation sheet appears.
- When tapping Add
- Then the shortcut is on the current page.

**AC-24 (FR-29)**
- Given a user-installed fixture app on Home
- When choosing Uninstall and confirming
- Then the icon disappears from Home and App Library.
- System apps show no Uninstall item.

**AC-25 (FR-30)**
- Given Duo is not default Home
- When opening a context menu
- Then there are no shortcuts, and the Set Duo as Home row is shown.

### S3 — Home, grid, dock, postures, edit mode, gestures

**AC-26 (FR-31, FR-33)**
- Given a 4×6 cover page with 24 apps
- When changing the cover grid to 4×5
- Then 4 apps move to a new page, none are lost, and Undo restores the original layout.

**AC-27 (FR-32)**
- Given Separate mode
- When moving an app on the cover
- Then the inner layout is unchanged.
- When switching to Mirrored and back
- Then both layouts are intact.

**AC-28 (FR-34)**
- Given a 4×4 widget
- When the grid becomes 4×3 rows
- Then the widget shrinks to its largest allowed fitting span, or moves to a new page.

**AC-29 (FR-35)**
- Given the app data of 0.15.0-beta01 with apps, folders, dock, a bound widget and a leading-workspace widget
- When installing this build over it
- Then every placement and binding is identical, `state_v8_backup` exists, and onboarding is not shown.
- Also verified by a unit test with a v8 JSON fixture.

**AC-30 (FR-36)**
- Given a fresh install
- Then Separate mode, cover 4×6, inner 6×6, Today View and a right dock are active.

**AC-31 (FR-37)**
- Given Dock side Left
- Then the dock, status cluster and page controls are on the left edge on both screens.

**AC-32 (FR-38, FR-39, FR-40)**
- Given dock capacity 6
- When dragging a folder into the dock
- Then it is placed.
- Given a full dock
- When dropping an app
- Then it is rejected with the "Dock full" message.

**AC-33 (FR-41)**
- Given Duo status is on
- Then a circular cluster in the dock-side corner shows time and a battery ring matching `dumpsys battery`.

**AC-34 (FR-42)** *(device, or emulator posture control)*
- Given half-opened with a vertical hinge
- When opening a folder
- Then its panel does not overlap the hinge bounds plus 16dp.

**AC-35 (FR-43)**
- Given the inner display
- When rotating to landscape
- Then no icon, widget or dock is clipped or overlapped, as verified by screenshot.

**AC-36 (FR-44)**
- Given Search open with "chr" typed on the cover
- When unfolding
- Then Search is still open with "chr".

**AC-37 (FR-45, FR-46, FR-48)**
- When long-pressing empty space
- Then icons jiggle, − badges and the toolbar appear.
- When tapping − on an app
- Then it leaves Home but stays in App Library.
- When pressing Back
- Then Edit mode ends.

**AC-38 (FR-47)**
- Given 3 pages with page 2 hidden in Page overview
- When exiting
- Then swiping skips page 2, and its contents are kept.

**AC-39 (FR-49)**
- Given Lock Home layout
- When long-pressing empty space
- Then Edit mode does not open, and a "Home layout is locked" toast appears.

**AC-40 (FR-50)**
- Given Add new apps on
- When installing a fixture APK
- Then its icon appears on the last page.

**AC-41 (FR-51)**
- Given a fresh install
- When swiping down on Home
- Then Search opens.
- Given an upgrade with shade gestures on
- When swiping down on the left 70%
- Then notifications open.

**AC-42 (FR-52)**
- When swiping up on Home
- Then App Library opens.

**AC-43 (FR-53)**
- Given Double-tap to lock on and the service enabled
- When double-tapping empty space
- Then the screen locks.

**AC-44 (FR-54)**
- Existing `PagerGestureIntegrationTest` and `RailPagerGestureIntegrationTest` pass.

### S4 — Today View, widgets, stacks, folders

**AC-45 (FR-55, FR-56)**
- Given Today View
- When unfolded
- Then the first spread shows the widget column left and Home 1 right.
- When folded
- Then swiping right from Home 1 shows Today View.

**AC-46 (FR-55)**
- Given Google Discover selected on a supported device
- Then the existing Discover behavior works and `LiveDiscoverIntegrationTest` passes.

**AC-47 (FR-57)**
- When adding a Clock widget in Today View Edit and dragging it above Batteries
- Then the order persists after restarting the launcher.

**AC-48 (FR-58)**
- Given upgraded Classic workspace content
- When switching to Today View and back
- Then the Classic workspace is unchanged.

**AC-49 (FR-59)**
- Given notification access is granted and media playing
- Then Now Playing shows the title, and Pause pauses playback.
- Without access, it shows a Turn on prompt.

**AC-50 (FR-60)**
- Given an Android widget
- Then its corners are clipped to the widget radius.

**AC-51 (FR-61, FR-62)**
- When dropping a 4×2 widget on a 4×2 widget
- Then a stack forms.
- When swiping up on it
- Then the next widget shows.
- A scrollable list widget in a stack still scrolls its content (existing `WidgetVerticalGesturesTest` passes).

**AC-52 (FR-63, FR-64)**
- When removing all but one widget in Edit stack
- Then the stack becomes a plain widget.

**AC-53 (FR-65, FR-66)**
- Given a 2×2 Large folder
- When tapping its first icon
- Then that app launches.
- When tapping "more"
- Then the folder opens as a panel with pages.

**AC-54 (FR-67)**
- When dragging Gmail onto Chrome
- Then a folder is created with a category-based name.

### S5 — App Library, Search, hidden apps, private space

**AC-55 (FR-68)**
- Given 40 installed apps
- When opening App Library
- Then Suggestions, Recently Added and category groups are shown, and tapping a mini grid opens that group.

**AC-56 (FR-69)**
- When switching to A–Z and dragging the scrubber to "M"
- Then the list jumps to M.
- When reopening
- Then A–Z is still selected.

**AC-57 (FR-70)**
- The existing `ManagedProfileIntegrationTest` passes.

**AC-58 (FR-71, FR-72)**
- When swiping down and typing "calc"
- Then Calculator is the top app result within 100 ms.
- When typing "2*21"
- Then a Calculation result shows 42.
- When typing "wifi"
- Then a Wi-Fi settings row appears.
- When typing "zzqx"
- Then "No results" and Search the web appear.

**AC-59 (FR-73)**
- When tapping Ask
- Then the default assistant opens.

**AC-60 (FR-74)**
- When typing "chr" and pressing Enter
- Then Chrome launches.

**AC-61 (FR-75)**
- When hiding Chrome
- Then it is absent from Home, App Library and Search.
- It is listed in Hidden apps, and Unhide restores it to App Library.

**AC-62 (FR-76, FR-77)** *(API 35+ emulator with private space)*
- Given a private space
- Then a Private container appears.
- When locked
- Then searching a private app's name returns nothing.

**AC-63 (FR-78)**
- Given Hide private space on
- Then no container appears.
- When searching "private"
- Then it appears.

### S6 — Settings, onboarding, backup, accessibility

**AC-64 (FR-79, FR-80)**
- When opening Duo Settings from Edit → Customize
- Then grouped sections show.
- When typing "badge" in its search
- Then Badges is shown.

**AC-65 (FR-81)**
- Given a fresh install
- Then onboarding shows and every optional step can be skipped.
- Given an upgrade
- Then it does not show.

**AC-66 (FR-82, FR-83)**
- When exporting and importing on a clean install
- Then all layouts and settings from this spec match.
- When importing a v2 fixture
- Then it succeeds.
- When importing a v99 fixture
- Then the newer-version message shows.

**AC-67 (FR-84)**
- When tapping Clear suggestion history
- Then Suggestions is empty until new launches occur.

**AC-68 (FR-85)**
- When performing a back gesture partially over an open folder
- Then the folder previews shrinking.
- When releasing
- Then it closes.

## 5. Error handling table

| Condition | Expected user-visible behavior |
|---|---|
| Notification access revoked while running | Badges and Now Playing disappear within 2 s. Badge settings shows **Turn on**. |
| Sideloaded restricted-settings block | Onboarding and Badge settings show the steps App info → ⋮ → Allow restricted settings, and link to App info. |
| Not default Home (no shortcut host permission) | Context menu omits shortcuts and shows the **Set Duo as Home app** row. Pinned shortcuts show greyed out with a toast "Set Duo as Home app to open shortcuts". |
| Pinned shortcut disabled or removed by its app | The icon is greyed out. Tap shows the app's disabled message, or "Shortcut unavailable". The context menu offers Remove. |
| Selected icon pack uninstalled | Icons fall back to the chosen appearance. Icon settings shows "Icon pack not installed" with a Reset button. |
| Icon pack XML malformed or oversized (>2 MiB) | Pack ignored and listed as "Couldn't load icon pack". No crash. |
| Grid shrink displaces items | Items are moved, never deleted. Snackbar "Moved N items to a new page" with **Undo** (8 s). |
| Widget provider uninstalled inside a stack | Removed from the stack. A stack of 1 becomes a plain widget. A stack of 0 is removed. |
| Widget binding lost after import | The existing Reconnect placeholder (unchanged behavior). |
| Blur unavailable (RenderEffect fails, low-RAM device, battery saver) | Tinted glass fallback. No crash or blank surface. |
| System wallpaper mode | Blur disabled silently. Glass slider still works. |
| Accessibility service off with Double-tap to lock or Notifications swipe set | The first attempt shows a sheet explaining the service and linking to Accessibility settings. |
| App launch fails (disabled, removed, work profile paused) | Toast "App unavailable". For a paused work app: existing "Turn on work apps" prompt. |
| Uninstall selected for a system app | The item is never shown. **App info** remains. |
| Search with no results | "No results" plus a **Search the web** row. |
| Web search intent unresolvable | Row hidden. |
| Contacts access denied | Contacts section absent. A single **Allow contacts in Search** row appears in Search settings. |
| Assistant not configured | Ask control hidden. |
| App with no category | Grouped under **Other**. |
| Private space API unavailable (API < 35, not default Home, no profile) | Private settings row shows the reason. No container. |
| Private space locked | Its apps are excluded from every surface and search. Notifications from it produce no badges. |
| Lock Home layout on and a pin request arrives | Sheet says "Home layout is locked" with **Unlock and add** or **Cancel**. |
| Backup newer than supported | Refused with "This backup was made by a newer Duo version." The current layout is untouched. |
| Corrupt or invalid backup | Existing rejection message. Current layout untouched. |
| Schema 9 load fails validation | Fall back to the most recent valid backup JSON (v9 previous, then v8). A banner reads "Duo restored your last saved layout". |
| Discover selected but unavailable | Existing recovery card, plus **Use Today View instead**. |
| Posture API unavailable | Width-only layout (today's behavior). |
| Fold or unfold during drag or Edit mode | The drag is cancelled with the item returned to origin. Edit mode persists. |
| Icon cache memory pressure (`onTrimMemory`) | The cache evicts. Icons re-render on demand without placeholders flashing for longer than 1 frame of scroll. |

## 6. Non-functional requirements

### Performance (measured on Galaxy Z Fold 8, release build)
- **NFR-P1:** Page swipe and folder open are smooth: janky frames ≤ 5% in `dumpsys gfxinfo` over 20 swipes on the inner display, with blur on.
- **NFR-P2:** Launcher cold start to first Home frame is ≤ 900 ms. The app catalog shows cached icons ≤ 300 ms after first frame.
- **NFR-P3:** Search result update is ≤ 100 ms per keystroke with 300 installed apps.
- **NFR-P4:** Icon bitmap cache is ≤ 64 MB. Total launcher PSS on Home is ≤ 350 MB.
- **NFR-P5:** No layout persistence or icon decoding runs on the main thread. There are no StrictMode disk-read or disk-write violations on the main thread in debug builds during normal editing.
- **NFR-P6:** A single blurred wallpaper layer is shared by all glass surfaces. Blur is not recomputed per surface per frame.

### Accessibility
- **NFR-A1:** Every interactive control has a TalkBack label. Touch targets are ≥ 48dp.
- **NFR-A2:** Text on glass has ≥ 4.5:1 contrast at every Glass slider value (FR-7).
- **NFR-A3:** At font scale 1.3, no settings or menu text is clipped. Labels ellipsize.
- **NFR-A4:** Animator scale 0 disables motion (FR-11). Reduce transparency is honored (FR-6).

### Privacy and security (upstream promises kept)
- **NFR-S1:** No INTERNET permission is added. No analytics, accounts or crash upload.
- **NFR-S2:** Each new access is opt-in, explained at the point of use, revocable, and documented in `PRIVACY.md`. This covers notification listener, contacts, calendar, accessibility lock and `ACCESS_HIDDEN_PROFILES`.
- **NFR-S3:** The notification listener reads only the package, user, badge count, channel badge flag and active media session metadata. It never persists notification text.
- **NFR-S4:** Contacts and calendar are queried on demand and never persisted. Launch history holds only component, user and timestamp, stays local, and is excluded from backups.
- **NFR-S5:** Icon pack XML and resources from other apps are treated as untrusted:
  - parsed with `XmlPullParser` with no external entities
  - 2 MiB limit
  - drawables decoded within size bounds
  - failures isolated
- **NFR-S6:** Exported components are protected by their system permissions: `BIND_NOTIFICATION_LISTENER_SERVICE`, `BIND_ACCESSIBILITY_SERVICE`, and the pin-item confirm activity accepting only `LauncherApps.getPinItemRequest`.
- **NFR-S7:** Backups contain no notification data, contacts, launch history or private-space app identities while it is locked.

### Compatibility
- **NFR-C1:** minSdk 31, target/compile 36. The package id `com.jake.duolauncher` and signing behavior are unchanged, so it updates over 0.15.0-beta01.
- **NFR-C2:** The primary target is Galaxy Z Fold 8 on Android 17. It is verified on the `DuoFold` emulator (API 36) at cover and inner sizes. Features requiring API 35+ degrade gracefully on 31–34.

### Maintainability and quality
- **NFR-M1:** No main-source Kotlin file exceeds 900 lines. `LauncherScreen.kt` is split by responsibility. The dead `SettingsPanel`/`SettingSlider`/`findFreeWidgetIndex` are removed.
- **NFR-M2:** New pure logic has JVM unit tests: grid reflow, schema migration, search ranking, badge aggregation, icon pack parsing, stack rules, backup v3. The existing 136 unit tests pass. Any test changed must correspond to an FR that intentionally changes behavior.
- **NFR-M3:** `./scripts/gradle.sh :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleRelease` succeeds with 0 lint errors. `scripts/check-public-source.sh` passes.
- **NFR-M4:** User-visible strings live in `res/values/strings.xml`.
- **NFR-M5:** README, `docs/user-guide.md`, `docs/architecture.md`, `PRIVACY.md`, `CHANGELOG.md` and `THIRD_PARTY_NOTICES.md` (Inter OFL, Haze) are updated. `PUBLIC-FILES` covers new public files. Version becomes `1.0.0-beta01` (versionCode 31).

### Regression boundary (must still behave as today)
- One page per horizontal swipe across Home, dock and status.
- Native widget vertical scrolling and long-press pickup.
- Widget bind/configure/reconfigure, including process-death recovery.
- Saved placements, folders and bindings survive upgrade.
- Home page retention during edge drag.
- Personal/Work catalogs and pausing.
- Layout export and import (v2 still imports).
- Staged photo background apply/cancel/recovery.
- Live Discover when selected, with its version guards and recovery.
- Google search intent option.
- Setup not re-shown on upgrade.
- "Dock full" rejection.

## 7. Data model sketch

`LauncherState` persisted JSON **schema 9**, migrated from 8 with `state_v8_backup`:

- **LayoutSet**
  - `mode` (MIRRORED | SEPARATE)
  - `mirrored: Layout`
  - `cover: Layout`
  - `inner: Layout`
- **Layout**
  - `grid {columns, rows}`
  - `pages: [Page]`
  - `hiddenPageIds`
- **Page**
  - `id`
  - `items: [HomeItem]`
- **HomeItem**, with position `{page, cell, span}`, is one of:
  - `App(profileAppId)`
  - `Shortcut(package, shortcutId, user)`
  - `Folder(id)`
  - `Widget(placementId)`
  - `Stack(stackId)`
- **Dock**
  - `side` (RIGHT | LEFT)
  - `capacity` (3–6)
  - `items: [App | Shortcut | Folder]`
  - shared by both layouts
- **Folder**
  - `id`
  - `name`
  - `tint`
  - `size` (SMALL | LARGE)
  - `apps: [profileAppId]`
- **WidgetPlacement**
  - `id`
  - `appWidgetId` or built-in `kind` (CLOCK, CALENDAR, BATTERIES, NOW_PLAYING, SUGGESTIONS)
  - `options`
  - existing restore metadata
- **WidgetStack**
  - `id`
  - `placementIds: [..10]`
  - `activeIndex`
  - `smartRotate`
- **LeadingPage**
  - `kind` (TODAY | DISCOVER | CLASSIC)
  - `today: [placementId | stackId]`
  - `classic`: the existing `leadingSlots` and page −1 widgets, kept intact
- **Settings**
  - wallpaper `source` (DUO | SYSTEM)
  - `dimInDark`
  - glass `level` 0–100 and `reduceTransparency`
  - `accent` (AUTO | preset | custom hue)
  - `font` (INTER | SYSTEM)
  - icon `appearance`, `tintColor`, `tintIntensity`, `shape`, `large`, `packPackage`
  - labels
  - badges `style` (OFF | DOT | NUMBER)
  - gestures `swipeDown`, `swipeUp`, `doubleTapLock`
  - `lockLayout`
  - `autoAddApps`
  - status `duoStatus`
  - library `view` (CATEGORIES | AZ)
  - search `contacts`, `suggestions`
  - private `hideContainer`
  - existing appearance mode and presets
- **IconOverride**
  - `profileAppId`
  - `iconPack` + `drawableName` | null
  - `label` | null
- **HiddenApps:** set of `profileAppId`.
- **LaunchHistory:** separate prefs file with ring buffer ≤ 500 of `(component, user, epochMillis)`. Not backed up.

Relationships:
- Folders and stacks are referenced by id from HomeItem, Dock or LeadingPage.
- A placementId belongs to exactly one place: page, stack or Today.
- Hidden apps and IconOverrides are keyed by `profileAppId`, so they apply across all layouts.

## 8. Implementation TODO (high level; detailed plan in `03-build-plan.md`)

- **S1:** token module, glass surface component with a shared blur layer, accent/dynamic color, springs, icon renderer and cache, font bundling, `LauncherScreen.kt` split.
- **S2:** icon appearance/shape pipeline, icon pack parser, NotificationListener badges, shortcut repository, context menu, pin-item activity, uninstall.
- **S3:** schema 9 model and migration, grid reflow, separate layouts, dock side and capacity, dock folders, status cluster, `WindowInfoTracker` postures, edit mode, page overview, lock, auto-add, gestures, lock action.
- **S4:** leading page modes, Today View, built-in widgets, stacks, folder redesign and large folders.
- **S5:** App Library categories and A–Z, Search engine and UI, hidden apps, private space.
- **S6:** Duo Settings, onboarding, backup v3, predictive back, strings extraction, accessibility pass, docs, versioning, performance verification.
