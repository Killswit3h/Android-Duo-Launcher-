# Inspection report — 1.0.0-beta01 attempt

**Status: INCOMPLETE. Not an inspection PASS.**

A subset of the work was completed and verified through CI. The rest could not be attempted to a
standard worth committing, because this session had no Android toolchain and no device. This report
says exactly which is which, and does not claim a sweep that did not happen.

Date: 2026-09-16
Branch: `claude/duo-launcher-final-push-eiv90p`, cut from `main` at `aedb6d2`
Package: `com.jake.duolauncher`, versionName 1.0.0-beta01, versionCode 31

---

## Environment

### The blocker

`dl.google.com` is denied by this session's egress policy. That one host serves both things this
project cannot build without:

- **Google Maven** (`https://dl.google.com/dl/android/maven2/`, for which `maven.google.com` is an
  alias that redirects to it). Every AndroidX artifact, the Compose BOM, and the Android Gradle
  Plugin come from there and from nowhere else.
- **The Android SDK repository** (`https://dl.google.com/android/repository/`): command line tools,
  platform 36, build-tools 36.0.0, platform-tools.

Reproduction:

```
$ curl -o /dev/null -w '%{http_code}\n' https://dl.google.com/android/repository/repository2-3.xml
000                     # CONNECT tunnel failed, response 403

$ ./scripts/gradle.sh --no-daemon :app:assembleDebug
FAILURE: Build failed with an exception.
* What went wrong:
Plugin [id: 'com.android.application', version: '8.11.0', apply: false] was not found in any of the
following sources: ... (could not resolve plugin artifact
  'com.android.application:com.android.application.gradle.plugin:8.11.0')
```

Reachable: `repo1.maven.org`, `services.gradle.org`, `developer.android.com`, `github.com`.
Blocked: `dl.google.com`, `maven.google.com`, and the community mirrors (`maven.aliyun.com`,
`mirrors.cloud.tencent.com`, `repo.huaweicloud.com`, `mirrors.tuna.tsinghua.edu.cn`, `jitpack.io`).
Maven Central and the Gradle Plugin Portal do not carry AndroidX or AGP: both return 404 for
`androidx/window/window/1.5.1/window-1.5.1.pom`. Working around an egress policy denial is out of
bounds, so no mirror or proxy bypass was attempted.

There is also no `/dev/kvm`, so no emulator could be created even with an SDK.

### What made progress possible anyway

**CI is a real gate.** `.github/workflows/ci.yml` runs on a GitHub runner with full network access
and executes `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleRelease` against
the public-source export. That is the whole local gate plus R8, at roughly eight minutes per
iteration. Every code change on this branch was landed behind it.

Locally, two weaker gates were used before spending a CI cycle: a standalone Kotlin 2.1.20 compiler
from the JetBrains GitHub release, used as a **parser only** (no Android or Compose classpath, so it
resolves no framework symbol and catches nothing but syntax), and `scripts/export-public-source.sh`
followed by `scripts/check-public-source.sh`, which are shell and need no toolchain.

### What remains impossible here

- Running the app at all: no APK, no install, no Home role, no screenshots, no `adb logcat -b crash`
- The instrumented suite and any Gradle Managed Device
- Baseline Profile generation, macrobenchmarks, `dumpsys gfxinfo`, `dumpsys meminfo`, StrictMode
- Anything about a foldable: fold continuity, half-open hinge avoidance, 120Hz

### To lift it

Allow `dl.google.com` and `maven.google.com` for this environment. Emulator work needs `/dev/kvm`
in addition.

---

## What was completed and verified

Four commits, each green in CI (build, unit tests, lint, and the R8 release build).

| Item | Change | Evidence |
|---|---|---|
| C14, FR-49 | Cold-start hole in the pin flow closed | `d37d78d`, CI run 20 green |
| C15, NFR-S6 | Tapjacking on the exported pin sheet | `d37d78d`, CI run 20 green |
| C16, FR-29 | Uninstall hand-off requires a system handler | `d37d78d`, CI run 20 green |
| C17, FR-57/61-64 | Dangling stacks pruned, `validate()` asserts coherence | `48ecbbe` |
| C18, FR-82 | `LayoutImportPreview.version` default made honest | `48ecbbe` |
| C19, NFR-P5 | Three TIME_TICK receivers consolidated | `14f2adf` |
| D22 | `specs/device-test-checklist.md` written | `6078a52`, `14f2adf` |
| Docs | README "Known limits" corrected | `6078a52` |

27 unit tests were added across `LauncherStateSnapshotTest`, `HomeLayoutLockTest`,
`UninstallRulesTest` and the new `WidgetStackCoherenceTest`. The suite baseline of 894 was never run
locally, so the claim here is narrower and true: CI's `:app:testDebugUnitTest` passed on every
commit, and no existing test was deleted or modified.

### Three of these deserve a note

**C14** distinguishes three states rather than two, which is the whole fix. Nothing saved reads as
unlocked (the user never set the lock). A pre-schema-9 payload reads as unlocked (`lockLayout`
arrived with schema 9, so the setting genuinely did not exist). A payload that exists but cannot be
parsed reads as **locked**, because that is the "the setting exists and I could not read it" case,
and failing open there was the hole. The live model seam always wins when it exists.

**C17 turned out to be larger than the backlog recorded.** The backlog described dangling stacks as
a legacy-import problem. Reading `LauncherModel` showed `removePlacement` drops a widget placement
and never tells the stack holding it, so the same incoherence arises with no restore involved. That
changed the design: adding the `validate()` rule the brief asked for, on its own, would have
rejected states already on disk from shipped builds and turned an ordinary upgrade into "Saved Home
layout could not be read" and an empty Home. So the pruning runs first at every point a document is
decoded or merged, and `validate()` asserts the invariant only once pruning has established it.
Repairing beats refusing.

**C19 is verified to compile, not to work.** Whether the clocks still tick every minute is runtime
behaviour that nothing in the JVM suite can observe. Section 8b of the device checklist exists for
exactly that, and it covers the pin sheet, uninstall and stack changes for the same reason.

---

## What was not done

### Deliberately not attempted: A1-A5, B7, B8, B10, B13

The performance track (benchmark build type, Baseline Profiles, macrobenchmarks, blur audit, memory)
and the large UI features (widget stacks UI, folder redesign, predictive back, the accessibility and
strings pass).

The performance track is not a coding problem here, it is a measurement problem. A benchmark build
type and a `:baselineprofile` module can be written blind, but their entire purpose is to produce
numbers, and no number can be produced without a device. Committing the scaffolding while reporting
no measurement would add two Gradle modules to the build for nothing.

The UI features are several thousand lines of Compose whose only available check would have been a
syntax parser. This repo's own history is the argument: 763 green unit tests coexisted with a
launcher that crashed on its first frame, because nothing in the JVM suite composes. CI would have
caught compilation, but not layout, not gesture arbitration, not whether anything renders.

### Root-caused but not fixed: B6, FR-45

Edit mode does not open on empty wallpaper below the app grid. The cause is confirmed by reading and
recorded in full in `specs/backlog.md`.

In `home/HomePager.kt`, `HomePagePane` defines the background long-press correctly but attaches it
to exactly one pointer-input region: a 16dp-wide strip down the left edge of the pane. Everything to
its right is the `Column` holding `SharedHomeGrid`, which is `fillMaxHeight().verticalScroll(...)`
and therefore covers the whole pane height including the empty area below the last grid row, with no
long-press handler. A press on empty wallpaper below the grid lands on that scrolling `Column` and is
dropped. A second dead band is the `bottomSpace` strip (44dp or 88dp) below the pane, which has no
handler on either display. This explains why the unfolded screen is where it shows: that is where
there is most empty space below the grid.

The fix is small, and it is deliberately not applied. It moves pointer-event handling on Home, which
is the exact regression boundary `docs/architecture.md` and `CONTRIBUTING.md` protect: one page per
swipe across pager, dock and rail, native widget vertical scrolling, and scoped long-press pickup.
The argument for why it should be safe rests on Compose pass ordering and event consumption, which
CI cannot check and no unit test in this repo exercises. It needs the instrumented gesture suite and
checklist rows 8.1 to 8.12.

### Not attempted, smaller: B9, B11, B12

Widget corner clipping and the Duo Settings launcher entry are small, and each is visual or
system-integration behaviour whose only real check is a device.

**B9, double-tap to lock, turned out to be coupled to B6**, which is worth knowing before anyone
picks it up. Most of FR-53 already exists: `DuoSettings.doubleTapLock`, the settings row that
toggles it, `LauncherModel.setGesture`, and an accessibility service that already performs global
actions with `eventTypes = 0`. What is missing is `GLOBAL_ACTION_LOCK_SCREEN` on the service and a
callback on the background gesture detector. The callback is the problem: double-tap has to be
recognised on the same empty Home background FR-45 long-presses, which today is the 16dp margin
strip. Adding it alone would ship a feature that is technically present and practically impossible
to trigger. The service half could have been landed here and deliberately was not, because a
capability wired to nothing is the failure mode this project has already been bitten by. Do it with
the B6 fix, in one change, verified together.

### D20, D21

The instrumented suite and screenshot verification. Impossible without an SDK, and separately
without KVM.

---

## Acceptance criteria

Every criterion AC-1 through AC-68, covering FR-1 through FR-85, is **UNVERIFIED-NEEDS-DEVICE** with
respect to behaviour.

Marking them individually would imply a sweep that did not happen. What can be said precisely:

- The rules added on this branch are verified as **rules**: `PinRequestGate` composed with the new
  persisted lock, `UninstallAction.isTrustedHandler`, and `stackCoherence` are pure functions with
  unit tests that fail without them, green in CI.
- Nothing on this branch is verified as **behaviour**. No frame was rendered.
- The last behavioural evidence on record for this tree is in `specs/backlog.md`, from a session
  that could build and run the app, and which already lists the integration gaps found by doing so.

`specs/device-test-checklist.md` is the instrument for the rest: 11 sections covering refresh rate
and blur cost, fold and unfold continuity, half-open hinge avoidance, the schema 9 upgrade over a
real 0.15.0-beta01 layout, One UI restricted-settings grants, third-party Home behaviour, on-device
performance numbers, the gesture regression boundary, this branch's own changes (8b), accessibility
at font scale 1.3, and sign-off.

---

## Performance

**No numbers were measured.** No optimized build type exists yet, and nothing could be built or run.

The targets stand unmeasured: NFR-P1 janky frames ≤ 5%, NFR-P2 cold start ≤ 900 ms, NFR-P3 search
≤ 100 ms per keystroke, NFR-P4 PSS ≤ 350 MB and icon LRU ≤ 64 MB, NFR-P5 no main-thread disk I/O,
NFR-P6 one shared blur layer. Section 7 of the device checklist is where they get filled in.

One observation that needed no measurement: the debug APK being 61 MB is expected, since the debug
build type is neither minified nor resource-shrunk. That is what work item A1 exists to fix, and it
remains undone.

---

## Recommendation

Do not release, and do not treat this branch as inspected.

1. Allow `dl.google.com` and `maven.google.com`, and provide `/dev/kvm` if emulator work is wanted.
2. Re-run `./scripts/gradle.sh :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` and confirm
   the suite count before changing anything further.
3. Take the remaining items in the brief's order: A1-A5, then B6-B13, then D20-D21.
4. Run `specs/device-test-checklist.md` on the Fold 8, section 4 first, since that one is data loss
   on upgrade.
5. Redo this report with a real FR sweep.
