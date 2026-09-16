# Inspection report — 1.0.0-beta01 attempt

**Status: BLOCKED. Not an inspection PASS, and not a FAIL of the code either.**
The build toolchain could not be installed in this session, so no acceptance criterion could be
executed. This report records the blocker precisely enough to be reproduced or ruled out, states
what is and is not known about the branch, and leaves the FR sweep to a session that can build.

Date: 2026-09-16
Branch: `claude/duo-launcher-final-push-eiv90p`, cut from `main` at `aedb6d2`
Package: `com.jake.duolauncher`, versionName 1.0.0-beta01, versionCode 31

---

## Environment

### What is present

| Component | State |
|---|---|
| JDK 17 | Installed (`openjdk 17.0.20`, at `/usr/lib/jvm/java-17-openjdk-amd64`) |
| Gradle 8.14.3 | Downloaded by the wrapper from `services.gradle.org`, works |
| Kotlin compiler 2.1.20 | Installed standalone from the JetBrains GitHub release, usable only as a parser |
| Android SDK | **Absent** |
| Hardware virtualization | **Absent** (`/dev/kvm` does not exist) |

### What blocks the build

`dl.google.com` is denied by this session's egress policy. That one host serves both things this
project cannot build without:

- **Google Maven** (`https://dl.google.com/dl/android/maven2/`, for which `maven.google.com` is an
  alias that redirects to it). Every AndroidX artifact, the Compose BOM, and the Android Gradle
  Plugin come from there and from nowhere else.
- **The Android SDK repository** (`https://dl.google.com/android/repository/`), which serves the
  command line tools, platform 36, build-tools 36.0.0 and platform-tools.

Reproduction:

```
$ curl -o /dev/null -w '%{http_code}\n' https://dl.google.com/android/repository/repository2-3.xml
000                     # CONNECT tunnel failed, response 403

$ ./scripts/gradle.sh --no-daemon :app:assembleDebug
FAILURE: Build failed with an exception.
* What went wrong:
Plugin [id: 'com.android.application', version: '8.11.0', apply: false] was not found in any of the
following sources:
- Gradle Core Plugins ... - Plugin Repositories (could not resolve plugin artifact
  'com.android.application:com.android.application.gradle.plugin:8.11.0')
```

Hosts confirmed reachable: `repo1.maven.org`, `services.gradle.org`, `developer.android.com`,
`github.com`. Hosts confirmed blocked: `dl.google.com`, `maven.google.com`, and the community
mirrors (`maven.aliyun.com`, `mirrors.cloud.tencent.com`, `repo.huaweicloud.com`,
`mirrors.tuna.tsinghua.edu.cn`, `jitpack.io`). Maven Central does not carry AndroidX or AGP:
`repo1.maven.org/maven2/androidx/window/window/1.5.1/window-1.5.1.pom` returns 404, and so does the
same path on the Gradle Plugin Portal.

Working around an egress policy denial is explicitly out of bounds, so no mirror, proxy bypass or
vendored-artifact scheme was attempted.

### What that costs

Every one of these is unavailable, not merely unrun:

- `:app:assembleDebug`, `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleRelease`
- the 894-test unit suite, so the "never drops below 894" guarantee could not be checked
- the instrumented suite (`:app:connectedDebugAndroidTest`) and any Gradle Managed Device: no SDK,
  and separately no KVM, so an emulator could not be created even with an SDK
- Baseline Profile generation, macrobenchmarks, `dumpsys gfxinfo`, `dumpsys meminfo`, StrictMode
- installing the APK, setting Duo as Home, screenshots, `adb logcat -b crash`
- the public-source export check's build step (`scripts/check-public-source.sh` itself is shell and
  could run, but the CI gate it feeds builds the export)

### To unblock

Allow `dl.google.com` and `maven.google.com` for this environment. Emulator work needs `/dev/kvm`
in addition. With those, the full brief is executable as written.

---

## What this session did and did not change

### Changed

| Change | Risk | Verified how |
|---|---|---|
| `specs/device-test-checklist.md` added (item D22) | None, documentation | Read-through only |
| `specs/backlog.md`: FR-45 Edit mode root cause recorded | None, documentation | Static source reading, see below |
| `README.md`: two stale "Known limits" bullets corrected | Low, documentation | Static: the features they deny are present in source |
| `specs/04-inspection-report.md` (this file) | None, documentation | n/a |

The README correction is the one factual claim here, so it is worth stating the evidence.
`icons/IconPackRepository.kt`, `icons/AppFilter.kt` and `icons/IconRenderer.kt` implement icon packs,
and `settings/SettingsLookSections.kt` exposes them. `badges/` implements notification dots
(`BadgeRepository.kt`, `DuoNotificationListener.kt`, `BadgeAccess.kt`) and `home/IconTile.kt`
consumes them. `profiles/PrivateSpaceRepository.kt` provides `PrivateSpaceGate`, and
`home/HostServices.kt` plus `library/LibraryHosting.kt` consume it. The README said all three were
unimplemented or unsupported. That is stale. Whether each one *behaves* correctly is a device
question and is on the checklist (rows 5.3, 6.10, 9.x).

### Deliberately not changed

No production Kotlin was written. That is a judgement call, and the reasoning is the point of this
section.

The brief's own hard rule is "verify at runtime, not just with unit tests", and its cautionary tale
is this repo's history: 763 green unit tests coexisted with a launcher that crashed on its first
frame, because nothing in the JVM suite composes. In this session there is no unit suite either, and
no compiler that can resolve an Android or Compose symbol. Anything written here would have been
checked by nothing stronger than a Kotlin parser, which catches unbalanced braces and nothing else.
No type error, no unresolved reference, no Compose misuse, no lint violation, no init-order bug.

Landing items A7 (widget stacks UI), A8 (folder redesign), A10 (predictive back across six surfaces)
and A13 (strings and accessibility pass) in that state would have handed over several thousand lines
of Compose that almost certainly does not compile, on a branch whose value is that it currently
builds and carries 894 green tests. The likely outcome is a longer debugging job than writing them
fresh.

The gesture items are worse than that. Item B6's fix moves pointer-event handling on Home, which is
the exact regression boundary `docs/architecture.md` and `CONTRIBUTING.md` protect: one page per
swipe across pager, dock and rail, native widget vertical scrolling, and scoped long-press pickup.
That boundary has been broken before by plausible-looking fixes. It is confirmable only by the
instrumented suite and a real device, neither of which exists here.

So the work that was possible without a toolchain was done, and the work that needs one was analysed
and recorded rather than guessed at.

---

## Findings that did not need a build

### FR-45, item B6: Edit mode does not open below the app grid. Root cause confirmed by reading.

`home/HomePager.kt`, `HomePagePane`. The background long-press verb exists and is correct:

```kotlin
val backgroundLongPress = { if (!drag.active) onEditMode?.invoke() ?: onEmptyWidget(backgroundTarget) }
```

It is attached to exactly one pointer-input region, a **16dp-wide strip down the left edge of the
pane**:

```kotlin
Box(Modifier.width(16.dp).fillMaxHeight().testTag("home-options-margin-$page")
    .pointerInput(...) { detectTapGestures(onLongPress = { backgroundLongPress() }, ...) })
```

Everything to the right of that strip is the `Column` holding `SharedHomeGrid`, and that `Column` is
`fillMaxHeight().verticalScroll(...)`. It therefore covers the entire pane height, including the
empty region below the last grid row, and it has no long-press handler. A press on empty wallpaper
below the grid lands on the scrolling `Column` and is dropped.

There is a second, smaller dead band. `HomePagePane`'s `Box` is
`.height((contentHeight - bottomSpace).coerceAtLeast(0.dp))` while its parent in `ExpandedWorkspace`
is `fillMaxHeight()`, so the `bottomSpace` strip (44dp on default Home, 88dp otherwise) sits below
the pane with no handler on either display.

This explains the reported symptom exactly, including why the unfolded screen is where it shows:
that is where there is the most empty space below the grid.

The proposed fix, the argument for why it should be safe, and the reason it was not applied unbuilt
are in `specs/backlog.md`. It is a small change. It is not a change to make blind.

### The pre-existing gap list still stands

`specs/backlog.md` already carries the items this brief restates as C14 to C19 (the FR-49 cold-start
hole in `PinItemActivity`, tapjacking on the exported pin sheet, the implicit `ACTION_DELETE`
hand-off, dangling stacks after a legacy import, `LayoutImportPreview`'s dishonest `version` default,
and the three unconsolidated TIME_TICK receivers). Nothing in this session contradicts any of them,
and none could be fixed to a standard worth committing.

---

## Acceptance criteria

Every criterion AC-1 through AC-68, covering FR-1 through FR-85, is **UNVERIFIED-NEEDS-TOOLCHAIN**.

Marking them individually would imply a sweep that did not happen. The honest statement is the
categorical one: nothing was executed, so nothing was verified. The last evidence on record for this
tree is the state described in `specs/backlog.md`, which was produced by a session that could build
and run the app, and which already lists the integration gaps found by doing so.

For the criteria that no build could settle anyway, `specs/device-test-checklist.md` is the
instrument: 10 sections covering refresh rate and blur cost, fold and unfold continuity, half-open
hinge avoidance, the schema 9 upgrade over a real 0.15.0-beta01 layout, One UI restricted-settings
grants, third-party Home behavior, on-device performance numbers, the gesture regression boundary,
accessibility at font scale 1.3, and sign-off.

---

## Recommendation

Do not release, and do not treat this branch as inspected.

1. Allow `dl.google.com` and `maven.google.com`, and provide `/dev/kvm` if emulator work is wanted.
2. Re-run the baseline `./scripts/gradle.sh :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`
   and confirm 894 tests, 0 failures, before changing anything.
3. Work items A1 through C19 from the brief in order, each behind that gate.
4. Run `specs/device-test-checklist.md` on the Fold 8.
5. Redo this report with a real FR sweep.
