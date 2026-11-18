# Code Style Guide - sotto-android

## Baseline

Use Kotlin official code style and keep formatting consistent with surrounding
code. Android lint runs for both flavors in CI. The separate ktlint tooling
and documentation work is tracked in issue #7; this change does not add that
task.

## Module / package boundaries (the rules that matter)

```
app/src/main/kotlin/page/stephens/sotto/
  engine/      pure logic + inference + persistence. NEVER imports
               androidx.compose.*, android.app.*, or anything from shell/.
               JVM-unit-testable except the JNI synthesizer seam.
    text/      TextPipeline (clean_markdown + chunk ports) - pure functions
    narrate/   NarrationCoordinator, NarrationEngine, synthesizer/encoder seams
    store/     Room entities/DAOs, file layout, job log
  shell/       Compose UI, NarrationService, MediaSession, manual process wiring
    synth/     managed sherpa-onnx resource + MediaCodec adapter
    playback/  owner-scoped OrationPlayback, Media3 output, position FIFO
    sync/      nullable process-wide SyncCoordinator + SyncCoordinatorProvider
src/local/     local flavor wiring (`SyncFactory` returns null; no network)
src/sync/      sync flavor only: `SyncFactory`, `RealSyncCoordinator`,
               `RemoteServer`/`SottoApi`, `CredentialStore`/`EncryptedCredentialStore`
               (12-api-contract.md). Nothing outside src/sync may reference
               network classes or Keystore sync credentials - enforced by the
               local flavor failing to compile such references, plus the
               manifest guard.
src/testSync/  sync-flavor JVM suites (`SottoApiContractTest`,
               `SyncCoordinatorTest`, `SyncUiTest`).
```

- Dependency direction: `shell → engine`, `sync → engine + shell`. Never the
  reverse. `engine` knows nothing about flavors.
- The managed synthesizer factory constructs and closes a resource only inside
  the coordinator's execution lane. Tests use deterministic blocking adapters;
  production uses sherpa-onnx with the existing four-thread ordinary profile.
  Keep encoder, progress, and audio-sink seams. Use real Room/files for
  ownership tests, not a generic storage or runner framework. Keep process-wide
  wiring manual and retain application dependencies only.
- One manually wired `OrationPublication` owns source/gate publication, final
  downloaded files and pre-commit rollback, row-first removal, and orphan cleanup
  for the same Room/root. Inject it explicitly; never construct a default parallel
  owner in narration or sync. Enter off-main outside Room and the sync lock;
  order locks publication, Room, brief sync-validity read (ADR-010). Register
  attempt ownership before file operations that may partly succeed. Keep network
  transfer, callbacks, inference, encoding, and ordinary chunk writes outside its
  monitor. The Narration worker keeps admission/playback fencing and writer/model
  drain; sync keeps sessions, transfer progress, and staging cleanup.

## Conventions

- Coroutines: attached ordinary commands promptly return typed `Deferred`
  outcomes; narration facts are observable and UI collection respects lifecycle.
  Coordinator-owned cleanup outlives an Android-owner callback so blocking work
  drains before model close. Detach synchronously invalidates the attachment
  and fences callbacks. No main-thread joins, GlobalScope, or runBlocking
  outside tests.
- Retained process-wide worker ownership lives in `engine/narrate`, including
  admission, run identity, stop flags, outcome persistence, deletion fencing/drain, and
  pickup. The Android caller must not reconstruct that protocol. Lab uses typed
  diagnostic requests on the same coordinator, never a model handle or arbitrary
  work closure. Diagnostic source/file/encoder/progress/log work and model
  reconfiguration/close stay in the same lane (ADR-009).
- Retained process-wide opt-in sync ownership exposes nullable `SyncCoordinator`
  and `SyncCoordinatorProvider` in `shell/sync/` (`null` in `local`;
  `RealSyncCoordinator` plus `RemoteServer`/`SottoApi` and
  `CredentialStore`/`EncryptedCredentialStore` under `src/sync/`). Activity
  callers observe `SyncState` and call coordinator suspending methods without
  global mutable flows, Activity-owned polling loops, duplicate-tap booleans,
  or main-thread storage/network access. Sync transfers stage outside
  `orations/` in `filesDir/sync-staging/`, fence auth against monotonic session
  epochs, cancel on active `deletingIds` or matching-epoch `401`, and preserve
  `updatedAt` during playhead and sync-bookkeeping writes.
- A stop flag belongs to a run, not an oration ID. Pending commands precede
  dispatch; retries persist QUEUED before admission succeeds; duplicates never
  clear a stopping run's flag. Pickup sorts by persisted `queuedAt`, then ID.
  Once-per-process recovery precedes admission and preserves progress/order.
- ExoPlayer, MediaSession transport, governor, and sleep timer stay in the
  existing Android owner. Its OrationPlayback module hides selection, refresh,
  deletion epochs, timers, and close behind domain commands on the player thread.
  Keep output snapshots coherent and revalidate delayed storage results there.
  Use the narrow output seam with Media3 and controlled test adapters, real
  Room/files, and internal dispatcher/time controls; no generic storage seam.
  Only the private application-scoped persistence FIFO outlives close, retaining
  immutable observations and Room dependencies rather than an owner or player.
  Listen-now consumes saved-durable-audio events with
  attachment/run fencing; deletion uses a narrow playback-stop callback on the
  required thread. Detach stops pickup, and replacement execution waits for
  the previous writer and model to drain off-main.
- Narrate, play, stop narration, sleep timer, and delete are started on the
  non-exported `InAppNarrationService`. `NarrationService.onStartCommand` does
  not handle them (ADR-008); that service stays exported only for the Media3
  session.
- Naming mirrors [the domain glossary](../GLOSSARY.md): Oration, chunk,
  Narration worker, narrate - not "track,"
  "segment," "synthesize" in user-facing or domain code. ("Synthesis" is
  correct inside `synth/`, mirroring the server's wording rule: technical word
  at the technical layer.)
- Errors: engine functions return sealed results for expected failures
  (storage full, model load failure); exceptions are reserved for bugs.
- Document intake callers submit `Intake.Source.Text` or a deferred
  `Intake.Source.Stream` to `accept`; they never open, decode, then validate
  files themselves. Keep URI/Intent/Toast knowledge in the shell and preserve
  cancellation and unexpected failures. Publication keeps its pure defensive
  String check and never suspends for file intake under its monitor.
- Comments: explain *why*, in the style of app.py's section comments - one
  plain sentence above the non-obvious block. No doc-comment boilerplate on
  self-evident functions.
- Ported functions (`cleanMarkdown`, `chunk`) keep the Python implementations'
  structure and comment landmarks so the two stay diffable by eye, and cite the
  server file/commit they were ported from.

## Tests

Mirror packages under `test/` (JVM) and `androidTest/` (instrumented). Test
names are sentences: `resume produces byte-identical output after process kill`.
The shared vector file lives at `app/src/test/resources/pipeline-vectors.json` -
regenerated only from the server repo (30-testing-plan.md).

Ownership tests exercise attached commands, outcomes/events, persisted rows and
logs, and real artifact bytes. A controlled synthesizer holds a chunk while
commands arrive and records model/generate/close ordering. Assert externally
visible exclusion and durability; avoid tests that mirror private scheduling
or inspect run tokens.
