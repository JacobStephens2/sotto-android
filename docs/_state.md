# _state.md - sotto-android session handoff

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
