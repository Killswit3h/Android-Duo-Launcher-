# Device test checklist — Galaxy Z Fold 8

Checks that only a real foldable can confirm. Nothing here is verifiable by the JVM suite, by lint,
or by an AOSP emulator, so each item stays UNVERIFIED in `specs/04-inspection-report.md` until
somebody runs it on hardware and records the result in the Result column.

Run every section against a debug build unless a row says otherwise. Set Duo as Home first:

```
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell cmd role add-role-holder android.app.role.HOME com.jake.duolauncher 0
adb logcat -b crash -c
```

Keep `adb logcat -b crash` running for the whole pass. A crash buffer entry invalidates the section
it appeared in, whatever the visual result looked like.

Legend for Result: PASS, FAIL, or N/A with a note. Record the build's `versionCode` and the commit
hash at the top of each pass.

---

## 1. Refresh rate and blur cost (NFR-P1, NFR-P6, ADR-3)

The emulator renders at 60Hz with a software GPU, so it cannot show whether the shared blur layer
holds 120Hz on real silicon. This is the single most important section.

| # | Check | How | Expect | Result |
|---|---|---|---|---|
| 1.1 | Display is actually at 120Hz while Duo is Home | Settings > Display > Motion smoothness set to Adaptive, then `adb shell dumpsys display \| grep -i "mRefreshRate\|fps"` | Reports 120 while Home is foregrounded, not 60 | |
| 1.2 | Page swipe jank with blur on | Glass slider at maximum, 20 horizontal swipes on the inner display, then `adb shell dumpsys gfxinfo com.jake.duolauncher framestats` | Janky frames ≤ 5% (NFR-P1) | |
| 1.3 | Folder open jank | Open and close a populated folder 20 times, same gfxinfo read | Janky frames ≤ 5% | |
| 1.4 | Blur is one shared layer, not per surface | Open Search over Home with the Glass slider high, watch for a frame-rate drop that scales with the number of glass surfaces on screen | No additional cost as more glass surfaces appear | |
| 1.5 | Glass slider at 0 | Set Glass to 0 | Blur work stops entirely, no residual cost in gfxinfo | |
| 1.6 | Reduce transparency honored | Accessibility > Reduce transparency on | Glass surfaces go opaque, blur stops (FR-6, NFR-A4) | |

Record the actual percentages, not just pass or fail. NFR-P1 is a number, and a build that reports
4.8% is a different artifact from one that reports 0.3%.

## 2. Fold and unfold continuity (FR-37 onward, postures)

The emulator's foldable profile changes window size but does not reproduce One UI's real
fold transition, its app-continuity animation, or the display swap.

| # | Check | How | Expect | Result |
|---|---|---|---|---|
| 2.1 | Cover to inner keeps the page | Sit on Home 2 on the cover display, unfold | Home 2 is still current, no reset to Home 1, no flash of empty wallpaper | |
| 2.2 | Inner to cover keeps the page | Sit on the Home 1 / Home 2 spread, fold | Cover shows one of that pair, not page 1 | |
| 2.3 | Leading workspace survives the fold | Put content on the leading workspace, fold | Leading workspace disappears from the cover view without deleting its contents, and returns on unfold | |
| 2.4 | Today View survives the fold | Leading page set to Today View, unfold from Home 1 | Today column appears on the left of the first spread, scroll position intact | |
| 2.5 | No crash or ANR across 20 fold cycles | Fold and unfold 20 times with Duo foregrounded | Crash buffer stays empty, no ANR | |
| 2.6 | Open folder across a fold | Open a folder on the cover display, unfold | Folder stays open and re-lays out to the inner grid, or closes cleanly. It must not leave a half-drawn panel | |
| 2.7 | Drag in flight across a fold | Start dragging an icon, unfold mid-drag | Drag either completes or cancels cleanly. No orphaned placement, no duplicated icon | |
| 2.8 | Widget rebinding after a fold | A bound native widget on Home 1, fold and unfold | Widget keeps its binding and does not reinflate to a blank box | |

## 3. Half-open posture and hinge avoidance (postures)

Never exercised. The emulator has no half-open posture with a real hinge occlusion rect.

| # | Check | How | Expect | Result |
|---|---|---|---|---|
| 3.1 | Half-open is detected at all | Hold the device at roughly 90 to 135 degrees on Home | Duo reports a HALF_OPENED posture. Confirm with the debug probe or `adb shell dumpsys activity` window layout info | |
| 3.2 | Content avoids the hinge | Half-open with a full Home page | No icon, label, widget or dock control is bisected by the hinge occlusion rect | |
| 3.3 | Today View in half-open | Leading page Today, half-open | Today column stays on one side of the hinge, does not straddle it | |
| 3.4 | Dock and status cluster in half-open | Half-open, dock on both the left and the right setting | Dock and cluster stay clear of the hinge on both sides | |
| 3.5 | Sheets and dialogs in half-open | Open Settings, the context menu and a folder while half-open | Each is positioned in one half, not across the fold | |
| 3.6 | Returning to flat | Flatten from half-open | Layout returns to the full inner layout with no leftover hinge padding | |

If hinge avoidance turns out never to have been wired, record that here as a FAIL rather than N/A.
The point of this section is to find out.

## 4. Upgrade over an existing 0.15.0-beta01 layout (schema 9)

The highest-risk item in the release. Schema 9 rejects a widget overlapping an occupied cell on
ordinary pages, where schema 8 only checked the leading page. It fails safe, so nothing is
overwritten, but a user who hits it sees an empty Home and "Saved Home layout could not be read".

Do this on a device that already ran 0.15.0-beta01 with a real layout, not a fresh install.

| # | Check | How | Expect | Result |
|---|---|---|---|---|
| 4.1 | Capture the pre-upgrade state first | `adb shell run-as com.jake.duolauncher cat` the saved layout, or export a backup from 0.15.0-beta01, and keep it | A v8 payload saved off-device before anything is upgraded | |
| 4.2 | Install over the top | `adb install -r` the new build without uninstalling | Home comes up with every page, widget, folder and dock item intact | |
| 4.3 | No migration error banner | Watch the first launch | No "Saved Home layout could not be read" | |
| 4.4 | Overlap case specifically | If the saved layout has a widget adjacent to a full row, confirm it survives | Layout loads. If it does not, capture the v8 payload and file it, because that is the strict-validation case | |
| 4.5 | The v8 payload is still on disk after a failed read | If 4.3 fails, confirm the old payload was not overwritten | v8 file intact and recoverable | |
| 4.6 | Onboarding does not reappear | First launch after upgrade | Welcome flow stays closed, layout is not replaced | |
| 4.7 | Downgrade safety | Reinstall 0.15.0-beta01 over the new build | Either the old layout loads or the user sees a clean empty Home. No crash loop | |
| 4.8 | Backup round trip across versions | Export from the new build, restore it, then undo | Every field restores (layouts, grid, dock side and capacity, leading page, Today items, stacks, hidden apps, icon overrides, settings) and undo puts it all back (AC-66) | |

The SAF round trip through the real document picker is instrumented-only today and has never run on
hardware, so 4.8 is also the first real test of the picker path.

## 5. Restricted settings grants (badges, accessibility)

One UI applies Restricted Settings to sideloaded APKs, which blocks notification listener and
accessibility grants behind an extra confirmation that no emulator shows.

| # | Check | How | Expect | Result |
|---|---|---|---|---|
| 5.1 | Notification access is reachable | Settings > Notification dots in Duo | Deep link lands on the system notification-listener page, not a dead end | |
| 5.2 | Restricted Settings prompt is survivable | Attempt the grant on a sideloaded build | Duo explains the "Allow restricted settings" step rather than silently failing | |
| 5.3 | Badges appear after the grant | Grant, then generate a notification | Dots or counts appear per the badge style setting | |
| 5.4 | Badges degrade without the grant | Revoke the grant | No crash, no empty badge frames, setting reflects reality | |
| 5.5 | Accessibility service grant | Enable Duo's service for double-tap to lock and shade access | Grant succeeds through Restricted Settings | |
| 5.6 | Accessibility service stays minimal | After the grant, `adb shell dumpsys accessibility` | Duo's service reports no window-content capability and `eventTypes = 0` | |
| 5.7 | Double-tap to lock works | Double-tap empty Home space with the gesture enabled | Screen locks (FR-53) | |
| 5.8 | Double-tap to lock is inert when off | Disable the gesture or the service | Double-tap does nothing, no error toast loop | |
| 5.9 | Privacy claims hold | Re-read `PRIVACY.md` against observed behavior | Every special access is optional, explained, and nothing undocumented is persisted | |
| 5.10 | No INTERNET permission | `adb shell dumpsys package com.jake.duolauncher \| grep -i permission` | `android.permission.INTERNET` absent | |

## 6. One UI behavior with a third-party Home app

Samsung's shell treats non-Samsung launchers differently from AOSP, and none of this reproduces on
an AOSP emulator image.

| # | Check | How | Expect | Result |
|---|---|---|---|---|
| 6.1 | Home role survives a reboot | Set Duo as Home, reboot | Duo is still Home, no One UI Home takeover | |
| 6.2 | Home button and gesture return to Duo | From a third-party app, swipe up or press Home | Returns to Duo's current page | |
| 6.3 | Recents still works | Open recents from Duo | System recents appears. Duo does not claim it (QuickStep is system-launcher only) | |
| 6.4 | Back gesture does not fight Duo's sheets | Open a sheet, use the back gesture | Sheet dismisses, Home does not exit | |
| 6.5 | Edge panels and Samsung gestures | With Samsung edge panels enabled | Edge swipe opens the panel, does not steal Duo's horizontal page swipe | |
| 6.6 | Taskbar absence is clean | Unfold | No half-drawn system taskbar. Duo's own dock and rail are the only ones | |
| 6.7 | Secure lock screen unaffected | Lock and unlock | Samsung's secure lock screen is untouched | |
| 6.8 | Widget picker shows Samsung providers | Add a widget | One UI providers bind and render, including reconfiguration | |
| 6.9 | Work profile badging | With a Samsung work profile present | Work apps carry the badge and the work toggle behaves | |
| 6.10 | Private Space | If the device exposes Private Space | Locked private apps stay hidden from Home, App Library, Search and Suggestions (FR-77) | |
| 6.11 | Google Discover embedding | Leading page set to Discover | Feed loads, native gestures belong to Google, version-guarded alignment workaround behaves on this device's Window Extensions version | |
| 6.12 | Wallpaper and Always On Display | Change the wallpaper, then check AOD | Duo's own scrim applies on Home only, AOD unaffected | |

## 7. Memory and startup on real hardware (NFR-P2, NFR-P4, NFR-P5)

Numbers from an emulator are not comparable. These must come from the Fold 8.

| # | Check | How | Expect | Result |
|---|---|---|---|---|
| 7.1 | Cold start to first Home frame | Macrobenchmark `StartupTimingMetric`, or `adb shell am start -W` after `am force-stop` | ≤ 900 ms (NFR-P2) | |
| 7.2 | Cached icons after first frame | Observe the app catalog filling | ≤ 300 ms after first frame | |
| 7.3 | Total PSS on Home | Settle on Home, then `adb shell dumpsys meminfo com.jake.duolauncher` | ≤ 350 MB (NFR-P4) | |
| 7.4 | Icon cache ceiling | Scroll the App Library through 300 apps, re-read meminfo | Icon LRU stays ≤ 64 MB | |
| 7.5 | No main-thread disk I/O | Debug build with StrictMode, edit the layout, add and remove widgets | No disk-read or disk-write violations on the main thread (NFR-P5) | |
| 7.6 | Search latency with a full catalog | 300 apps installed, type into Search | ≤ 100 ms per keystroke (NFR-P3) | |
| 7.7 | Baseline profile actually applied | `adb shell dumpsys package com.jake.duolauncher \| grep -i "compilation\|status"` after install | Reports a profile-backed compilation state, not `run-from-apk` | |

## 8. Gesture regression boundary

The contract in `docs/architecture.md` and `CONTRIBUTING.md`. Every one of these has been broken by
a well-meaning gesture fix before, so re-run the whole section after any change to pointer input.

| # | Check | How | Expect | Result |
|---|---|---|---|---|
| 8.1 | One page per swipe, pager | Fast repeated horizontal flings | Exactly one page per gesture, never two | |
| 8.2 | One page per swipe, starting on the dock | Fling horizontally starting on a dock icon | One page, dock does not swallow it | |
| 8.3 | One page per swipe, starting on the rail | Fling horizontally starting on the status rail | One page | |
| 8.4 | Native widget vertical scroll | A scrollable widget, drag vertically inside it | Widget content scrolls. Duo does not open Search or the App Library | |
| 8.5 | Swipe down outside a widget | Drag down on empty Home | Search or the shade per the setting | |
| 8.6 | Swipe up outside a widget | Drag up on empty Home | App Library | |
| 8.7 | Scoped long-press pickup on a bound widget | Long-press a widget and drag | Pickup starts, tolerates sub-slop jitter | |
| 8.8 | Pickup cancels correctly | Long-press a widget then scroll, or add a second pointer | Pickup cancels, no ghost drag | |
| 8.9 | Dock full rejection | Drag a fifth app to a full dock | Rejected with feedback, nothing is lost | |
| 8.10 | Empty-cell long-press | Long-press an empty grid cell | Add-to-Home path opens | |
| 8.11 | Empty-wallpaper long-press below the grid | Long-press empty wallpaper below the app grid, unfolded | Edit mode opens (FR-45). See the known defect in `specs/backlog.md` | |
| 8.12 | Tap to leave Edit mode | Tap empty background in Edit mode | Edit mode exits (FR-48) | |
| 8.13 | Discover overscroll is gated | Leading page set to Today, overscroll left from Home 1 | Today, not Google Discover (FR-55) | |

## 9. Display and accessibility on hardware

| # | Check | How | Expect | Result |
|---|---|---|---|---|
| 9.1 | Font scale 1.3 | Set the system font scale to 1.3 | No clipped text in Settings, menus or sheets. Labels ellipsize (NFR-A3) | |
| 9.2 | TalkBack sweep | TalkBack on, traverse Home, dock, Search, App Library, Settings, folders | Every interactive control announces a meaningful label (NFR-A1) | |
| 9.3 | Touch targets | Measure the smallest controls with Layout Bounds on | ≥ 48dp (NFR-A1) | |
| 9.4 | Contrast on glass over bright wallpaper | Bright wallpaper, sweep the Glass slider from 0 to max | Text stays ≥ 4.5:1 at every value (FR-7, NFR-A2) | |
| 9.5 | Animator duration scale 0 | Developer options, animator scale off | No animation anywhere in Duo (FR-11, NFR-A4) | |
| 9.6 | Dark and light | Switch system theme, and cross a scheduled theme boundary | Both themes render, no color literal leaks a wrong value | |
| 9.7 | Cover display widths | Exercise the cover display at its real width | Layout matches the ~411dp expectation, nothing clipped | |

## 10. Sign-off

| Field | Value |
|---|---|
| Device | Galaxy Z Fold 8 |
| One UI / Android version | |
| Duo versionName / versionCode | |
| Commit | |
| Tester | |
| Date | |
| Sections passed | |
| Sections failed | |
| Crash buffer clean | |

A release is not signed off while any section 4 row is FAIL, because that is data loss on upgrade.
Sections 1, 3 and 7 may ship with recorded numbers that miss their target if the owner accepts
them explicitly, but they may not ship unmeasured.
