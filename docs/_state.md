# _state.md - sotto-android session handoff

2026-11-19: Issue #34 deepens client Oration playback behind `OrationPlayer`
(`shell/playback/OrationPlayer.kt`) with internal `PlayerTransport` and
`PlayerCommands` seams and `OrationPlayer.setFactoryForTest`, configures
`15,000 ms` ExoPlayer skip increments, publishes `OrationPlayback` sleep-timer
state into `MediaSession.setSessionExtras`, and closes remaining deletion
invalidation gaps. `PlayerScreen` collects discrete `OrationPlayerState`
(`Loading`, `Missing`, `Ready`), isolates `PlaybackTimeline` updates in a leaf
`PlayerTimeline` composable with local `Slider` drag state released via
`onValueChangeFinished`, renders `PlayerScaffold` cleanly with vertical scrolling
across viewports, and disposes via `OrationPlayer.close()`. `DropCapText` keys
updates on `(text, capArgb, bodyArgb, bodySizeSp, lines)` to skip redundant
span creation while applying `bodyColor` and `bodySizeSp` changes. On deletion,
closed-owner `SavedOrationPlayback.stopForDeletion` invalidates
`PlaybackPersistence` before `output ?: return`, and `NarrationCoordinator`
invokes `onDeletionFenced` inside `beginDeletion` (wired in
`NarrationCoordinatorProvider` to `PlaybackPersistence::invalidateShared`) so
unattached Lab `ResetGate` clears cached positions without eager startup.
Updated `GLOSSARY.md`, `docs/README.md`, `docs/02-prd.md`,
`docs/10-architecture-overview.md`, `docs/30-testing-plan.md`,
`docs/40-code-style.md`, and `docs/tasks.md` (with follow-up review fixes
unifying `InAppNarrationService.dispatch` across `InAppPlayerCommands` and
`SottoActivity.svc`, and scoping the `docs/40-code-style.md` `source.md` /
`MediaController` rule specifically to `PlayerScreen`); no new ADR, manifest
permission, Room schema, model, network, or text-pipeline change.

Before the fixes, the baseline tests failed in Red as expected (`OrationPlaybackTest`
lines 564/579 `AssertionError`, `NarrationCommandsTest` line 569 `AssertionError`,
`OrationPlayerTest` `ClassCastException` and assertion failures per slice through
line 410, and `PlayerScreenTest` `AssertionError` at line 97 and unmocked
`rememberController` NPE at line 378).

After the final code edits, full CI-equivalent verification, release APK builds,
and screenshot verification passed cleanly:

```sh
PATH=/home/jacob/.local/jdk/jdk-21.0.12.1+1/bin:$PATH JAVA_HOME=/home/jacob/.local/jdk/jdk-21.0.12.1+1 ANDROID_HOME=/home/jacob/Android/Sdk ./gradlew --no-daemon lintLocalDebug lintSyncDebug testLocalDebugUnitTest testSyncDebugUnitTest assembleLocalDebug assembleSyncDebug assembleLocalDebugAndroidTest assembleLocalRelease assembleSyncRelease -Proborazzi.record=false -Proborazzi.verify=true --console=plain
```

Observed results: 180 `localDebug` unit tests across 18 suites and 212 `syncDebug`
unit tests across 21 suites passed (0 failures, 0 errors, 0 skips; including 6 new
`OrationPlayerTest` tests, 3 new `PlayerScreenTest` tests, 2 added
`OrationPlaybackTest` tests, 2 added `NarrationCommandsTest` tests, and all 7
`IlluminatedScreenshotTest` Roborazzi verifications). Android lint passed with
0 errors on both flavors (29 baseline warnings on `localDebug`, 31 baseline
warnings on `syncDebug`; 0 introduced warnings, with 1 pre-existing baseline
`SimpleDateFormat` warning at `SottoActivity.kt:907`). All APKs built cleanly
(`sotto-local-0.4.10-debug.apk`, `sotto-sync-0.4.10-debug.apk`,
`app-local-debug-androidTest.apk`, `sotto-local-0.4.10-release.apk`, and
`sotto-sync-0.4.10-release.apk`), and `aapt dump permissions` confirmed `local`
debug/release APKs have no `INTERNET` or `ACCESS_NETWORK_STATE` while `sync`
debug/release APKs add `INTERNET` only. No `ktlintCheck` task exists in this
repo (tracked under issue #7); no connected Android device is attached
(`adb devices -l` empty), `emulator -accel-check` exits 11 (`/dev/kvm` permission
denied), no headless browser is installed for native Android visual evidence, and
no hardware performance claim is made.

2026-11-18: Issue #32 restricts `NarrationService` MediaSession controller access
across both `onGetSession` and `MediaSession.Callback.onConnect` (installed via
`MediaSession.Builder.setCallback`) using one controller authorization check.
It accepts only same-UID controllers (`Process.myUid()`) and verified trusted
controllers (`controllerInfo.isTrusted`, checking `controllerInfo.packageName`
against `PackageManager.getPackagesForUid(controllerInfo.uid)` when UID packages
are resolvable and `uid != Process.SYSTEM_UID`). `onGetSession` keeps returning
the session for negative-UID internal `MediaSessionService` intents
(`controllerInfo.uid < 0`), while `onConnect` rejects negative UIDs. All other
controllers receive `null` from `onGetSession` and
`MediaSession.ConnectionResult.reject()` from `onConnect`. Updated
`docs/adr/ADR-008-in-app-narration-commands.md`,
`docs/10-architecture-overview.md`, `docs/30-testing-plan.md`,
`docs/40-code-style.md`, and `docs/tasks.md`; no new ADR, manifest, schema,
model, network, or text-pipeline change.

Before the authorization change, the untrusted-controller regression failed
with `java.lang.AssertionError` in `NarrationCommandsTest`:

```sh
PATH=/home/jacob/.local/jdk/jdk-21.0.12.1+1/bin:$PATH JAVA_HOME=/home/jacob/.local/jdk/jdk-21.0.12.1+1 ANDROID_HOME=/home/jacob/Android/Sdk ./gradlew --no-daemon testLocalDebugUnitTest --tests '*NarrationCommandsTest.same-UID controller connects to the session while an untrusted controller is rejected' -Proborazzi.record=false -Proborazzi.verify=true --console=plain
```

`NarrationCommandsTest` (17 tests) covers same-UID, verified trusted
(matching UID package, `SYSTEM_UID`, and unresolvable UID packages),
negative-UID internal `onGetSession` vs `onConnect` rejection, untrusted
rejection, UID-mismatched trusted-package rejection, notification-controller
attachment, and existing `InAppNarrationService` and session behavior.

Final verification passed with 167 local tests (16 suites) and 199 sync tests
(19 suites), zero failures, errors, or skips, zero lint errors (29 local / 31
sync warnings), existing Roborazzi goldens verified, and debug APKs built:

```sh
PATH=/home/jacob/.local/jdk/jdk-21.0.12.1+1/bin:$PATH JAVA_HOME=/home/jacob/.local/jdk/jdk-21.0.12.1+1 ANDROID_HOME=/home/jacob/Android/Sdk ./gradlew --no-daemon lintLocalDebug lintSyncDebug testLocalDebugUnitTest testSyncDebugUnitTest assembleLocalDebug assembleSyncDebug -Proborazzi.record=false -Proborazzi.verify=true --console=plain
```

APK `aapt dump permissions` manifest guards passed: `local` has no `INTERNET`
or `ACCESS_NETWORK_STATE`, and `sync` adds `INTERNET` only. No connected
Android device is available (`adb devices -l` empty), and
`emulator -accel-check` exits 11 (`/dev/kvm` permission denied); no headless
browser is installed for native Android visual evidence. No hardware
performance claim is made.

2026-11-18: Issue #30 deepens Document intake behind `Intake.accept`, taking
typed text or a deferred stream opener and returning Accepted, Empty, TooLarge,
or Unreadable. IO dispatch owns opening, bounded replacement decoding, Kotlin
trimming, normalized UTF-8 size validation, and closure. Pending whitespace
remains undecided until EOF or later content; accepted text is never a capped
prefix. Direct-text BOM policy and publication's synchronous defensive String
check remain unchanged. No new ADR is needed within the existing engine/shell
boundary; no manifest, schema, model, network, or text-pipeline changes.

SottoActivity renders Reading before launch-file work, fences delivery by screen
and lifecycle, and restarts only saved unfinished intake. Completed Confirm and
other restored screens skip validation and messages. Write prevents duplicate
Continue, retains rejected text, and shares the operation. Confirm displays the
complete accepted text and preserves existing creation and sync behavior.

The baseline regression failed with `java.lang.AssertionError`:

```sh
JAVA_HOME=/home/jacob/.local/jdk/jdk-21.0.12.1+1 ANDROID_HOME=/home/jacob/Android/Sdk ./gradlew testLocalDebugUnitTest --tests '*IntakeTest.interior whitespace beyond the read buffer cannot hide the tail' -Proborazzi.record=false --console=plain
```

Targeted intake and Activity/Confirm creation suites pass, including blocked
resolver reads, Back, old success/failure after recreation, complete Confirm
restoration, failures, and Write rejection/double submission. Final verification
passed with 163 local and 195 sync tests, no failures or skips, and zero lint
errors (29 local / 31 sync warnings), with existing Roborazzi goldens verified:

```sh
PATH=/home/jacob/.local/jdk/jdk-21.0.12.1+1/bin:$PATH JAVA_HOME=/home/jacob/.local/jdk/jdk-21.0.12.1+1 ANDROID_HOME=/home/jacob/Android/Sdk ./gradlew --no-daemon lintLocalDebug lintSyncDebug testLocalDebugUnitTest testSyncDebugUnitTest -Proborazzi.record=false -Proborazzi.verify=true --console=plain
```

The initial sync run could not start `keytool`; adding the JDK bin directory to
PATH fixed that environment failure. Independent Standards and Spec reviewers
each read all 14 changed files and raised zero actionable findings, with no
generated exclusions or unread changed files. No connected Android device is
available (`adb devices -l` empty); no headless browser is installed for native
Android visual evidence. No hardware performance claim is made.

2026-11-17: Issue #28 deepens creation into `OrationPublication`, manually shared
by ordinary creation, gate preparation, the Narration worker, and sync. One
private monitor covers final-file mutation, matching Room commits, pre-commit
rollback, row-first removal, and orphan classification/removal (ADR-010).
Opaque download attempts capture creation identity, target, and removal
generation; fresh rows and sync validity are rechecked before promotion and
inside the downloaded-row/log transaction. Unique backups restore preceding
finals on failure. Successful publication records actual audio length and one
downloaded log, preserves `updatedAt`, accepts missing source, and survives
later cancellation or best-effort cleanup. Removal advances generation even
when it fails. Narration keeps admission/playback fencing and writer/model
drain; sync keeps sessions, transfers, progress, and staging cleanup.

The stale-snapshot regression failed before sync integration:

```sh
JAVA_HOME=/home/jacob/.local/jdk/jdk-21.0.12.1+1 ANDROID_HOME=/home/jacob/Android/Sdk ./gradlew testSyncDebugUnitTest --tests '*SyncCoordinatorTest.stale download snapshot*' -Proborazzi.record=false -Proborazzi.verify=true
```

Its actual failure was `expected:<[kep]t> but was:<[replacemen]t>`; the same
command passed after integration. Controlled real-file adapters now exercise
partial promotion, rename/copy fallback, fsync faults, rejected downloaded logs,
rollback before waiting newer attempts, deletion/sweep orderings, removal failure
and orphan retry, changed identity/target/availability, and same-ID recreation.
The account-replacement caller test holds old final audio before commit, holds
Room, installs a replacement session, queues a newer download, and releases the
old attempt. It checks old failure, new success, bytes, metadata, and one log.
Existing creation/gate, attached commands, durability/resume, transport, and
sync caller suites remain. No schema, permissions, text pipeline, model, or
performance claims changed.

Final validation passed with screenshot recording disabled and verification
enabled:

```sh
PATH=/home/jacob/.local/jdk/jdk-21.0.12.1+1/bin:$PATH JAVA_HOME=/home/jacob/.local/jdk/jdk-21.0.12.1+1 ANDROID_HOME=/home/jacob/Android/Sdk ./gradlew lintLocalDebug lintSyncDebug testLocalDebugUnitTest testSyncDebugUnitTest assembleLocalDebug assembleSyncDebug assembleLocalDebugAndroidTest -Proborazzi.record=false -Proborazzi.verify=true
```

The local JVM suite passed 150 tests across 15 suites; sync passed 182 tests
across 18 suites, with no skips or failures. Both lint tasks reported zero errors. Debug
APKs for both flavors and the local instrumentation APK built. APK `aapt dump
permissions` checks passed against the CI allowlist: local has no network
permissions, sync adds only INTERNET, and neither has ACCESS_NETWORK_STATE.

The initial full sync run passed 179 tests and failed its existing TLS test
because `keytool` was absent from PATH. Adding the documented JDK's bin directory
fixed that environment issue. A later local run exposed a diagnostic-supersession
test wait race: the completed row/log can precede enqueueing `GATE COMPLETE`.
The exact focused command failed at its verdict assertion before the correction
and passed afterward:

```sh
PATH=/home/jacob/.local/jdk/jdk-21.0.12.1+1/bin:$PATH JAVA_HOME=/home/jacob/.local/jdk/jdk-21.0.12.1+1 ANDROID_HOME=/home/jacob/Android/Sdk ./gradlew testLocalDebugUnitTest --tests '*NarrationCoordinatorTest.superseding a diagnostic fences*' -Proborazzi.record=false -Proborazzi.verify=true
```

That test now awaits actual verdict delivery while retaining every stale-callback
assertion; production diagnostic behavior is unchanged. Independent Standards
and Spec review against `refs/remotes/origin/master` at
`82174c38fc9c50fb53995ef74139322982056e77` reported zero actionable findings on
each axis, with every changed file read and no generated-output exclusions.
Reports and counts are in `.thirdshift-review-CLQuaC/` for the session trace.

Initial host setup required the documented local JDK and `tools/fetch-third-party.sh`
to stage the fresh worktree's ignored build dependencies. The external prose
guide `/var/www/Android2/Writing_Style_Guide_Unified.md` is absent on this host;
repository prose and ADR guidance remain available. No device is attached
(`adb devices -l` empty); `emulator -accel-check` reports status 11 because this
user lacks `/dev/kvm` permission. Connected resume/migration instrumentation
therefore remains unexecuted here; its source and test APK build are retained.

2026-11-16: Issue #26 deepens Oration playback inside the existing Android media
owner. `OrationPlayback` accepts play, saved-row listen-now, deletion stop,
sleep duration, and close; `SavedOrationPlayback` hides request ordering,
durable-growth refresh, coherent position snapshots, deletion epochs, timer
tokens, and teardown. The service retains ExoPlayer construction/release and
MediaSession lifetime. A narrow output seam has Media3 and controlled test
adapters. Latest explicit acceptance wins even when unavailable; listen-now
yields to selected/pending explicit work. Refresh uses durable bytes rather
than worker activity and preserves current position, speed, and pause intent.

`PlaybackPersistence` shares an application-only FIFO across replacement owners.
Immutable positive-position observations, including close, preserve `updatedAt`;
deletion epochs are checked inside each Room transaction. Latest accepted
primitive positions supply selection while those writes drain and are forgotten
on deletion, so switching back and replacement owners restore the right position.
Close immediately
fences player effects, cancels lookups/ticks/deadlines, releases the output, and
returns an asynchronous drain. Accepted timer pauses enqueue their truthful
local logs even if close follows. Storage failures remain locally observable.
No schema, permissions, network, text pipeline, model, scheduling, or ADR change.

The 24 caller-interface contracts use real local Room and temporary audio with
controlled storage/player dispatch and the Robolectric clock. They cover lookup
completion order, explicit/listen-now arbitration, unavailable audio, identity
and position, unchanged/growing/final-tail audio, current pause/seek/speed,
deletion and gate recreation, queued deadline replacement/cancel, pending work
at close, FIFO replacement writes, and contained storage failures. Real Media3
command entry, exported rejection, sleep, deletion, and Lab reset coverage remains.
Independent narration durability and sync timestamp coverage is unchanged.

Before/after focused runs caught an older lookup reviving after a missing latest
request, absent durable-tail refresh, missing deletion fencing, absent final
position writes, and missing timer acceptance. All those regressions pass after
implementation. The cross-Oration position contract also caught an unintended
1.6x-to-1x speed reset, now fixed.

Independent review against `refs/remotes/origin/master` at
`74d0be94dc046073f5e5417c2188a21b8a29c978` raised zero Standards findings and one
Spec position-restoration finding. Every supplied behavior reproducer ran as
written before deciding it: returning to A expected 1100 ms but restored 100;
the Spec switch/replacement cases expected 4200/5000 but restored 0. Both exact
focused commands pass after the latest-accepted-position correction, and all
three tests remain. Both reviewers checked the correction; no skipped findings,
generated exclusions, or unread changed files remain (15 paths per axis).
Reports and counts are in `.thirdshift-review-bPPmDd/`. The external prose guide
referenced by `docs/README.md` is absent; available repository rules were reviewed.

Final JVM results: local 135 tests across 14 suites; sync 165 across 17 suites.
Both have zero failures, errors, or skips, including all 24 playback contracts
and seven screenshot comparisons per flavor. Both Android lint checks have
zero errors. Debug APKs for both flavors and the local instrumentation APK build.
Screenshot recording stayed disabled and verification enabled throughout.

The broad check used:

```sh
PATH=/home/jacob/.local/jdk/jdk-21.0.12.1+1/bin:$PATH JAVA_HOME=/home/jacob/.local/jdk/jdk-21.0.12.1+1 ANDROID_HOME=/home/jacob/Android/Sdk ./gradlew lintLocalDebug lintSyncDebug testLocalDebugUnitTest testSyncDebugUnitTest assembleLocalDebug assembleSyncDebug assembleLocalDebugAndroidTest -Proborazzi.record=false -Proborazzi.verify=true --no-daemon
```

The initial TLS fixture needed the installed JDK's `bin` on `PATH` for `keytool`.
With that environment corrected, the broad check passed local/lint and all sync
tests except one 10-second Confirm retry UI wait. Both unchanged Confirm tests
passed in an isolated run, then the complete sync suite and APK tasks passed:

```sh
PATH=/home/jacob/.local/jdk/jdk-21.0.12.1+1/bin:$PATH JAVA_HOME=/home/jacob/.local/jdk/jdk-21.0.12.1+1 ANDROID_HOME=/home/jacob/Android/Sdk ./gradlew testSyncDebugUnitTest assembleLocalDebug assembleSyncDebug assembleLocalDebugAndroidTest -Proborazzi.record=false -Proborazzi.verify=true --no-daemon
```

The final `aapt dump permissions` checks use the CI allowlist: local has neither
INTERNET nor ACCESS_NETWORK_STATE; sync adds INTERNET only. No test assertion or
timeout was weakened. No hardware or emulator performance result is claimed.

Room/ADTS instrumentation was attempted with the existing selected classes:

```sh
PATH=/home/jacob/.local/jdk/jdk-21.0.12.1+1/bin:$PATH JAVA_HOME=/home/jacob/.local/jdk/jdk-21.0.12.1+1 ANDROID_HOME=/home/jacob/Android/Sdk ./gradlew connectedLocalDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=page.stephens.sotto.engine.DiagnosticResumeTest,page.stephens.sotto.engine.RoomResumeTest,page.stephens.sotto.engine.store.QueueMigrationTest,page.stephens.sotto.engine.store.InterruptedQueueRecoveryTest --no-daemon
```

It reports `DeviceException: No connected devices!`; `adb devices -l` is empty.
`emulator -accel-check` exits 11 because this user cannot access `/dev/kvm`.
The instrumentation APK is built, but no connected test result is claimed.

2026-11-15: Issue #24 replaces the transient `SyncProvider` / `RealSync` seam with a single nullable process-wide `SyncCoordinator` (`shell/sync/SyncCoordinator.kt`, `null` in `local`, `RealSyncCoordinator` retained singleton in `sync/` with `setForTest` and `shutdownForTest`). Confines all HTTP, credentials, and sync runtime code to `src/sync/`, keeps Room at schema v2 (`setPlayhead`, `markSynced`, and `setDownloaded` preserve `updatedAt`; `markSynced` and `setDownloaded` return `Int`), adds single-flight `syncNow()`, remote LWW rename with `"renamed"` `JobLog` (`mtime * 1000`), best-effort post-push/upload `serverMtime` refresh, isolated `filesDir/sync-staging/` downloads with startup sweep and rollback, by-oration-ID deduplication, `deletingIds` and epoch-fenced `401`/reconciliation state updates, stdlib `SottoApi` hardening behind `RemoteServer` (HTTPS endpoint hostname verification and prompt socket/stream closing on `executeRawSocket`, cancellable I/O handles, and tested `HttpURLConnection` `downloadTo` fallback), off-main `upload()` audio existence checks on `ioDispatcher`, retained server narration state (`Idle`, `Submitting`, `Polling`, `Completed`, `Failed`, `requestIdOrNull`) where new `narrateOnServer()` calls cancel any superseded active job and allocate a fresh `requestId`, and migrated `SottoActivity` callers using `collectAsStateWithLifecycle(..., context = Dispatchers.Main.immediate)` and a `heightIn(min = 48.dp)` Confirm server-narration switch row across configuration changes and `recreate()`. Verified with `JAVA_HOME=/home/jacob/.local/jdk/jdk-21.0.12.1+1 ANDROID_HOME=/home/jacob/Android/Sdk ./gradlew lintLocalDebug lintSyncDebug testLocalDebugUnitTest testSyncDebugUnitTest assembleLocalDebug assembleSyncDebug assembleLocalDebugAndroidTest assembleSyncDebugAndroidTest assembleLocalRelease assembleSyncRelease -Proborazzi.record=false -Proborazzi.verify=true --no-daemon` (0 lint errors; `local`: 111/111 unit tests across 13 suites; `sync`: 141/141 unit tests across 16 suites including `SottoApiContractTest` 9, `SyncCoordinatorTest` 15, `SyncUiTest` 6; debug, `androidTest`, and release APKs built) and `aapt` manifest permission guards (`local` has no `INTERNET`/`ACCESS_NETWORK_STATE`; `sync` adds `INTERNET` only). Connected emulator/hardware instrumentation did not run (`adb devices -l` empty; `emulator -accel-check` exited `11` due to `/dev/kvm` permissions).

2026-11-14: Issue #22 consolidates durable ordinary and gate execution in
internal `OrationNarration`. Typed ordinary-ID/gate-bundle requests hide source
preparation, lifecycle writes, real sink closure, chunk metadata/logs, and
outcome/failure recording. The standalone outcome recorder is removed.
ADR-009's coordinator remains the sole Narration worker owner: admission,
queue order, Stop, deletion, attachments, callback fencing, first-audio
deduplication, and managed model lifetime stay there. Measurement arithmetic
and gate presentation stay in diagnostic execution. This reversible internal
change needs no new ADR, schema, permissions, text vectors, or hardware claims.

One injected execution clock now reaches lifecycle logging, Room progress,
chunk metadata, narration time deltas, and terminal outcomes for both callers.
The ordinary clock regression failed on the baseline with
`expected:<100> but was:<1794646514820>` and passes after consolidation:

```sh
JAVA_HOME=/home/jacob/.local/jdk/jdk-21.0.12.1+1 ANDROID_HOME=/home/jacob/Android/Sdk ./gradlew testLocalDebugUnitTest --tests '*NarrationCoordinatorTest.ordinary durable progress and lifecycle use the injected clock' -Proborazzi.record=false -Proborazzi.verify=true
```

Final validation passes: 110 JVM tests per flavor with no failures, errors, or
skips, both Android lint checks, both debug APKs, and the instrumentation APK.
Screenshot recording is disabled and verification enabled. Command:

```sh
JAVA_HOME=/home/jacob/.local/jdk/jdk-21.0.12.1+1 ANDROID_HOME=/home/jacob/Android/Sdk ./gradlew lintLocalDebug lintSyncDebug testLocalDebugUnitTest testSyncDebugUnitTest assembleLocalDebug assembleSyncDebug assembleLocalDebugAndroidTest -Proborazzi.record=false -Proborazzi.verify=true
```

Both debug APK manifest guards pass: local has no network permissions, and
sync adds INTERNET only. Existing vectors, flavor provenance, independent
chunk-engine durability, creation, ownership, replacement, deletion/reset,
stopped gate verdict, measurement, and command-routing coverage remain green.
The 14 new contract tests use real Room and temporary files. Test publication
waits for startup cleanup acknowledgment; the previous-model close test uses
the existing deletion barrier and waits for the earlier writer to drain.

Room/ADTS instrumentation is blocked by the concrete host environment. The
selected command fails with `DeviceException: No connected devices!`:

```sh
JAVA_HOME=/home/jacob/.local/jdk/jdk-21.0.12.1+1 ANDROID_HOME=/home/jacob/Android/Sdk ./gradlew connectedLocalDebugAndroidTest -Pandroid.injected.device.serial=emulator-5582 -Pandroid.testInstrumentationRunnerArguments.class=page.stephens.sotto.engine.DiagnosticResumeTest,page.stephens.sotto.engine.RoomResumeTest,page.stephens.sotto.engine.store.QueueMigrationTest,page.stephens.sotto.engine.store.InterruptedQueueRecoveryTest
```

`/home/jacob/Android/Sdk/platform-tools/adb devices -l` lists no device, and
`/home/jacob/Android/Sdk/emulator/emulator -accel-check` exits 11 because this
user lacks permission to use `/dev/kvm`. No instrumentation or real-model
performance result is claimed for this session.

Independent Standards and Spec review against `refs/remotes/origin/master` at
`7e70f1c3d0e2d370442fa9dfad6e4d975ac07ddd` raised zero actionable findings on
either axis. Each reviewer read all 12 changed paths, including the deleted
outcome recorder; there are no generated exclusions, unread changed paths, or
skipped findings. Reports and counts are saved in `.thirdshift-review-9EkXEL/`.
The external prose guide referenced by `docs/README.md` is absent; available
repository prose rules were reviewed.

2026-11-13: Issue #20 consolidates ordinary creation, gate preparation, and
orphan cleanup in `OrationCreation`, shared manually by Confirm and the retained
coordinator. A private monitor covers source preparation through the new-row
and created-log transaction, and orphan classification/removal. Pending source
is fsynced, closed, and renamed before metadata publication. Expected validation
and storage errors are typed; failure cleanup removes only attempt-owned
preparation. Existing gate source, audio, checkpoints, narration time, and logs
survive compatibility repair. The monitor never covers native or chunk work.

Confirm awaits local creation off-main, disables duplicate submission, keeps
its fields and a retryable message after failure, and sends the existing ID-only
command only on success. A committed QUEUED row survives missed UI delivery.
No schema, permissions, text pipeline, sync publication, or ADR change.

Final validation passes: 94 JVM tests per flavor with zero failures, errors, or
skips, including ten real Room/files creation tests, two Confirm caller checks,
creation during a blocked native chunk, seven screenshot comparisons and four
provenance checks. Both lint checks, debug APKs, and the instrumentation APK
build pass. Screenshot recording is disabled. Command:

```sh
JAVA_HOME=/home/jacob/.local/jdk/jdk-21.0.12.1+1 ANDROID_HOME=/home/jacob/Android/Sdk ./gradlew lintLocalDebug lintSyncDebug testLocalDebugUnitTest testSyncDebugUnitTest assembleLocalDebug assembleSyncDebug assembleLocalDebugAndroidTest -Proborazzi.record=false -Proborazzi.verify=true
```

The Confirm blocked-commit regression failed on the old UI because it had
already navigated to Library. The identical focused command now passes all 12
creation/Confirm tests:

```sh
JAVA_HOME=/home/jacob/.local/jdk/jdk-21.0.12.1+1 ANDROID_HOME=/home/jacob/Android/Sdk ./gradlew testLocalDebugUnitTest --tests '*ConfirmCreationTest' --tests '*OrationCreationTest' -Proborazzi.record=false
```

Five selected API 34 x86_64 instrumentation tests pass: gate Room/ADTS
byte-identical resume with legacy source, ordinary Room resume, schema migration,
and interrupted ordinary/gate recovery. Command:

```sh
JAVA_HOME=/home/jacob/.local/jdk/jdk-21.0.12.1+1 ANDROID_HOME=/home/jacob/Android/Sdk ANDROID_SERIAL=emulator-5580 ./gradlew connectedLocalDebugAndroidTest -Pandroid.injected.device.serial=emulator-5580 -Pandroid.testInstrumentationRunnerArguments.class=page.stephens.sotto.engine.DiagnosticResumeTest,page.stephens.sotto.engine.RoomResumeTest,page.stephens.sotto.engine.store.QueueMigrationTest,page.stephens.sotto.engine.store.InterruptedQueueRecoveryTest
```

Both debug APK manifest guards pass: local has no network permission; sync adds
INTERNET only. Instrumentation uses deterministic synthesis and real Android AAC
encoding. No new hardware measurement or emulator performance claim is made.

Independent Standards and Spec review against `refs/remotes/origin/master` at
`484d91f192425f25940b8072d4a5ed014e1c742a` raised zero findings on either axis.
Each reviewer read all 13 changed paths; there are no generated exclusions,
unread changed files, or skipped findings. Reports and counts are saved in
`.thirdshift-review-BHzCUU/`. The external prose guide referenced by
`docs/README.md` is absent; repository-local prose rules were reviewed.

Earlier entries below describe earlier sessions.

2026-11-12: Spec #14 whole-branch Standards and Spec review compares issue-14
with `refs/remotes/origin/master` at
`503cb5d95b0d2ff9670744548583b31b527faaf9`, including both landed Tickets.
Standards raised one maintenance finding; Spec raised two playback findings.
All three are addressed. Independent follow-up found no additional findings.
Both axes read all 32 changed paths, with no generated exclusions, unread files,
or skipped findings. Reports and counts are in `.thirdshift-review-WET1vD/`.

The exact gate-playback regressions each failed before their fix and passed
afterward; both remain in `NarrationCommandsTest`. Saved Lab gate playback now
uses the existing non-exported in-app play command and media owner, so reset
stops matching playback before removing audio. Coordinator deletion facts and
per-ID playback deletion versions replace a permanent ID blacklist, allowing
the reserved gate to be recreated and played without recreating the media
owner. Shared Room fixtures clear after coordinator drain. Ordinary and gate
execution share terminal status/log persistence in `NarrationOutcomeRecorder`,
preserving existing outcomes, storage-full wording, and log details.

Final validation passes: 81 JVM tests per flavor, including seven screenshot
comparisons and four provenance checks each; both lint checks and debug/test
APK builds; and both existing manifest guards. Local has no network permission;
sync adds INTERNET only. Command:

```sh
./gradlew lintLocalDebug lintSyncDebug testLocalDebugUnitTest testSyncDebugUnitTest assembleLocalDebug assembleSyncDebug assembleLocalDebugAndroidTest -Proborazzi.record=false -Proborazzi.verify=true
```

Five selected API 34 x86_64 instrumentation tests pass with no failures: shared
gate Room/ADTS byte-identical resume, ordinary Room resume, schema-v1 migration,
and two interrupted queue/gate recovery cases. Command:

```sh
./gradlew connectedLocalDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=page.stephens.sotto.engine.DiagnosticResumeTest,page.stephens.sotto.engine.RoomResumeTest,page.stephens.sotto.engine.store.QueueMigrationTest,page.stephens.sotto.engine.store.InterruptedQueueRecoveryTest
```

The instrumentation uses deterministic synthesis and real Android AAC encoding.
No new hardware measurement or emulator performance claim is made. PR #18 stays
draft; thirdshift owns the later push and optional Security review.

Earlier entries below describe earlier sessions.

2026-11-12: Issue #16 completes retained process-wide ownership. Lab uses typed
Gate/Measure/Threads/Stop/ResetGate requests on the same `NarrationCoordinator`
as ordinary narration. Diagnostic starts return Busy while ordinary work is
active or queued. Ordinary requests during diagnostics persist in FIFO order
and wait for drain. No production caller constructs an independent native model
or inference executor. Model profiles preserve ordinary four-thread narration,
Lab's 2/3/4 threads, measurement sid 0, and the gate voice mapping.

Lab detach, Stop, supersession, and thread changes fence stale callbacks and
drain blocking work before release or replacement. Media-owner replacement
leaves a live gate alone. Gate reset and library deletion share the retained
deletion barrier. New gates fsync source; legacy gates without source adopt the
supplied bundle on explicit resume, preserving checkpoint/audio/log. Recovery
is once per process, retaining STOPPED gate progress for Lab.

Interface tests cover both contention orders, waiting retries/FIFO, blocked
reset/delete, reload, detach, supersession, media attachment, legacy recovery,
saved source, and deterministic measurement observations/arithmetic. The new
`DiagnosticResumeTest` checks byte-identical gate resume with Room and real AAC
encoding. Final validation: 79 JVM tests per flavor pass, including seven
screenshot comparisons and four provenance checks each. Both lint checks and
debug APK assemblies pass with recording disabled and verification enabled:

```sh
./gradlew lintLocalDebug lintSyncDebug testLocalDebugUnitTest testSyncDebugUnitTest assembleLocalDebug assembleSyncDebug assembleLocalDebugAndroidTest -Proborazzi.record=false -Proborazzi.verify=true
```

Both final APK manifest guards pass: local has no network permission, sync adds
INTERNET only. Five selected API 34 x86_64 instrumentation tests pass: diagnostic
Room/ADTS byte-identical resume with recovery and legacy source, existing Room
resume after database reopen, schema-v1 migration, and two interrupted-queue
recovery cases. The runner reported `OK (5 tests)`; deterministic synthesizers
were used, including with the real Android AAC encoder. No new hardware
measurement or emulator performance claim is made.

The production call-site audit finds native construction/generate/release only
inside `SherpaSynthesizer`, constructed solely by the shared provider's managed
factory. Ordinary and diagnostic execution use the same retained lane.
Independent Standards and Spec reviews each raised three findings. All were
addressed: sentence case/test names, stopped terminal reports, thread reload
with waiting ordinary work, and callbacks queued after a gate writer drained.
Every behavioral finding's exact regression failed before its fix and passed
afterward; the tests remain. Each axis read all 18 changed files, with no
exclusions, unread files, or skipped findings. Reports and counts are in the
session's `.thirdshift-review-i57Kc4/` directory.

Earlier entries below describe earlier sessions.

2026-11-12: Issue #15 retains ordinary narration ownership in a manually wired
process-wide engine `NarrationCoordinator`, attached to the existing Android
media owner. Admission, FIFO retry persistence, run identity, Stop, deletion,
outcomes, and pickup move out of the caller. Commands return typed deferred
outcomes; detach invalidates commands/callbacks promptly and drains off-main
before model close or replacement execution. Listen-now follows saved durable
audio. Playback, Media3 transport, governor, sleep timer, the four-thread
ordinary profile, text pipeline, and ADTS format retain their behavior.
Schema v2 adds `queuedAt`, migrating v1 values from `createdAt`; recovery is
once per process and preserves durable progress/order. The reserved `gate` is
excluded from ordinary pickup and an interrupted gate becomes STOPPED.

ADR-009 and the domain glossary record these decisions. This is an intermediate
scope: `GateRunner` and the spike/Lab still independently own diagnostic models
and execution. Issue #16 must integrate them and verify contention before the
complete process-wide one-worker guarantee is established. Issue #15 adds no
WorkManager scheduling or performance claim.

Final validation: 61 JVM tests per flavor pass, including seven screenshot
comparisons and four provenance checks per flavor. Both Android lint runs and
debug APK assemblies pass with recording disabled and screenshot verification
enabled. The APK manifest guards pass: local has no network permission; sync
adds INTERNET only. Command:

```sh
./gradlew lintLocalDebug lintSyncDebug testLocalDebugUnitTest testSyncDebugUnitTest assembleLocalDebug assembleSyncDebug -Proborazzi.record=false -Proborazzi.verify=true
```

Four selected API 34 x86_64 instrumentation tests pass: schema-v1 migration,
two interrupted queue/gate recovery cases, and byte-identical Room resume.
Independent review found one Standards issue (recovery lifecycle logging) and
one Spec issue (retry after a terminal outcome while model close drains).
Both exact reviewer regressions failed before the fixes and pass afterward;
both tests remain. Both axes read all 25 changed files, with no exclusions or
unaddressed findings. Reports are in the session's `.thirdshift-review-Gfgc0B/`.
The emulator used deterministic adapters; no real-model performance was
measured. Earlier entries below describe earlier sessions, not this run.

2026-11-11: ADR-008. Narrate, play, stop narration, sleep timer, and delete move
to non-exported `InAppNarrationService` (UI is the caller). Exported
`NarrationService` ignores those actions and still binds the Media3 session.
JVM coverage in `NarrationCommandsTest`.

Phase: **Phase 1 nearly complete (2026-06-12): TextPipeline, NarrationEngine, Room store, FileAudioSink, SherpaSynthesizer wiring, instrumented resume test, CI green.** v0.4.1 (2026-06-13): Player screen now shows the full source text (scrollable, weighted) readable DURING synthesis - emulator-verified on a NARRATING oration. fp32 ear-test: ring GONE (fp32 wins); making it default is pending an on-device RTF check.

Phase 5 SYNC BUILT + LIVE-VERIFIED 2026-06-12 (0.4.0): server /api/v1 deployed (token auth, library CRUD, .aac first-class web-wide, account-page token revocation); app sync flavor real (stdlib HttpURLConnection client, EncryptedSharedPreferences token, Account screen, server-row mirror w/ download-on-tap, upload from the sheet, rename LWW). E2E on emulator against sotto.stephens.page, both directions. **1.0 is unblocked: remaining = v1.0.0 tag + compliance [1.0] ticks.** Repo made public 2026-10-15 (gitleaks clean over full history).

2026-10-15 pre-1.0 pass: PRD edge cases closed in code (storage-full -> STOPPED w/ cause, partial kept; Intake: UTF-8 w/ replacement, empty refused, 400 KB cap, shared/opened .txt/.md); compliance [1.0] all ticked; PRIVACY/README/About corrected for the sync flavor's opt-in server/OpenAI narration (they still said "never sent to be synthesized"); lint added to CI after it surfaced a crash-on-every-narration for Android 10-13 (Matcher.appendReplacement(StringBuilder) is API 34) - fixed, vectors still pass. Constitution 6 now has its UI test (AboutScreenTest: real SottoActivity under Robolectric, both flavors, no-network statement checked against the merged manifest). **Remaining before v1.0.0 tag (hardware, Jacob):** PRD acceptance pass on device (Phase 3 gate) incl. storage-full + thermal catch-up; fp32-default RTF check; ideally one run on an Android 10-13 device to confirm the crash fix; release criteria in 30-testing-plan.md (forced-kill resume on >10k words, upgrade-over-previous dry run).

Phase 4 release machinery BUILT 2026-06-12: local/sync flavors real (ADR-007; sync currently = local + INTERNET, Phase 5 fills it), signed release pipeline (release.yml on v* tags; notes embed the aapt proof + checksums), GPL-3.0 license decided (espeak-ng linkage), README/PRIVACY/ATTRIBUTIONS, adaptive icon, keystore w/ SOPS-encrypted password backup. 1.0 publish still gated on Phase 5 sync (decided: sync ships at 1.0).

Phase 3 surface BUILT 2026-06-12 (0.3.0): voice picker w/ previews + corrected authoritative sid map (af_heart=3 per sherpa generate_voices_bin.py; prior builds spoke af_alloy), Player screen (MediaController: seek/skips/speed/sleep timer/catching-up), library long-press sheet (rename/delete/source/SAF export), About w/ provenance + job log, FIFO queueing + auto-resume, calibrated estimate. Emulator-verified by uiautomator. Remaining Phase 3: device validation of edge cases; int8-vs-fp32 A/B; 2T/3T battery sweep. PRD acceptance pass on hardware = the Phase 3 gate.

Phase 2 vertical slice BUILT 2026-06-12 (0.2.0-slice): Compose SottoActivity (share target -> Confirm -> Library w/ live Flow), NarrationService (one Media3 service owning player + narration; ADTS constant-bitrate seeking; listen-now autoplay; playhead governor; requeue-on-create recovery). applicationId is now page.stephens.sotto (forever id). Emulator end-to-end verified by state: share intent -> derived title -> narrate -> file growth -> MediaSession metadata -> COMPLETE library row. Remaining slice gate: a week of Jacob's real daily use. Voice feedback: af_heart mapping CONFIRMED warm; int8 ring persists (fp32 A/B still open).

Phase 1 gate: **kill/resume PROVEN on hardware 2026-06-12** - Jacob ran the gate to chunk 8/324, killed the process, re-tapped: 'resuming from chunk 8' through the full real stack (Room + truncate + Kokoro + ADTS). 324 chunks matches the server's count for the same document (pipeline parity in the wild). Remaining: let the gate run to GATE COMPLETE (~4-5 h synthesis; resumable in any number of sittings - every kill/charge break just resumes). Then Phase 2 (vertical slice).
Updated: 2026-10-15

## Decisions snapshot

- Plain Kotlin/Compose, Android-only (ADR-001)
- sherpa-onnx + Kokoro-82M int8, on-device, 7 server-parity voices (ADR-002)
- Streaming chunk-ahead-of-playhead in one MediaSessionService (ADR-003)
- In-app narration commands only on a non-exported service (ADR-008)
- Retained process coordinator for ordinary and diagnostic narration; Android
  media ownership and off-main teardown drain (ADR-009).
- Process-scoped Oration publication owns final source/audio, matching Room
  facts, pre-commit rollback, row-first removal, and orphan reclamation (ADR-010).
- `local` flavor = no INTERNET, flagship claim (ADR-004); `sync` flavor =
  opt-in sotto-server account/library sync (ADR-007, contract in
  12-api-contract.md)
- Model bundled in APK; arm64-v8a; minSdk 29 (ADR-005)
- GitHub-release sideload first; Play/F-Droid deferred decisions (ADR-006)

## Measurements (authoritative; update from device runs only)

| Metric | Value | Device | Date |
|---|---|---|---|
| RTF (run-once, 8 chunks, 4 threads) | **1.22x** synth basis | Jacob's arm64 phone | 2026-06-12 |
| RTF sustained 30.7 min | **1.06x** (decay 1.22→1.17→1.11→1.07→1.06; final inst 1.01x; soft throttle, thermal flag never raised) | Jacob's arm64 phone, spike-002 run 3, suspended=0s, deadline reached | 2026-06-12 |
| Battery %/hr (pure synthesis, 4T) | **19.5%/hr** (Δ10% over 30.7 min) | same (sustained run) | 2026-06-12 |
| Thermal | NONE throughout run-once | same | 2026-06-12 |
| Peak PSS | 1,080 MB (run-once) / 793 MB (sustained) | same | 2026-06-12 |
| Model load | 1.0-1.2s | same | 2026-06-12 |
| APK size (spike, debug, model bundled) | 203 MB | - | 2026-06-12 |

**GATE VERDICT (2026-06-12): PASS - proceed to Phase 1.** Sustained RTF 1.06x ≥ 1.0x
with no thermal shutdown (roadmap Phase 0 gate). Below the 1.3x comfort target at
hot steady state (final instantaneous 1.01x), so two design consequences are
binding: (1) the playhead governor must build buffer aggressively in the cold
early minutes; (2) synthesis-while-listening has thin headroom on this device -
the buffer, not raw speed, is the safety margin.

Open items carried into Phase 1:
- int8 sounds "a bit tinny / ringy" (Jacob, on-device) - A/B vs fp32 before
  confirming ADR-002's int8 choice.
- Optional: 30-min sweep at threads=2/3 for the battery/throttle tradeoff
  (19.5%/hr at 4T exceeds the PRD's ≤15%/hr synth+play ambition).
- Audio container DECIDED 2026-06-12: ADTS AAC (AdtsAacEncoder; appendable +
  truncatable at chunk boundaries + deterministic, preserving byte-identical
  resume; verified on the Android runtime in RealPipelineTest).

Reference points from the server (not phone numbers): VPS 4-core EPYC ≈ 1.4x
RTF, 0.26 s/word; council research cites puff-dayo/Kokoro-82M-Android as
precedent for int8 on-device.

## Next actions

1. Complete the existing hardware acceptance, fp32, and release gates above.

## Open questions (mirror of tasks.md)

final display name · F-Droid timing · playhead sync
(Decided: sync ships at 1.0 - 2026-06-12.)

## Known constraints from the host environment

- VPS disk is tight for Android builds; prefer GitHub-hosted runners or a local
  machine (the Cascade build-output-on-volume lesson).
- Server-side sync API (Phase 5) touches /var/www/sotto.stephens.page/app.py -
  deploys must respect the no-restart-while-narrating rule (check jobs/*.json
  status first; resume exists but don't lean on it).
