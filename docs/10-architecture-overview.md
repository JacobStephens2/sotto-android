# Architecture Overview - sotto for Android

Single-module-first plain Kotlin/Compose app (ADR-001) with one hard internal
boundary: the **engine** (pure logic + inference + persistence) must not depend
on the **shell** (Compose UI, Android services). The engine is where the value
is; the shell is replaceable.

## System diagram

```
share sheet / file open / paste
        │
        ▼
┌──────────────────────────── app (shell) ───────────────────────────┐
│  Compose UI: Confirm · Player · Library · About                    │
│  InAppNarrationService (not exported, ADR-008)                     │
│  NarrationService (foreground, mediaPlayback type)                 │
│    ├── attached narrator → process NarrationCoordinator (engine)   │
│    │     admission → one retained process-wide execution lane     │
│    │     └── OrationNarration → NarrationEngine → model → encoder │
│    │           └── fsync audio → persist progress → durable event │
│    └── Media3 ExoPlayer ← plays the growing ADTS audio file        │
│  Lab → typed diagnostics → same NarrationCoordinator lane        │
│  Room DB: orations · chunks-progress · joblog · settings           │
│  Files (app-private): orations/<id>/audio + source.md              │
└─────────────────────────────────────────────────────────────────────┘
        ▲
        └── `local` flavor: NO network - no INTERNET permission exists (ADR-004).
            `sync` flavor adds, in src/sync only (ADR-007):
            SyncEngine ──▶ sotto server /api/v1 (12-api-contract.md)
            (library mirror: download server entries, upload local ones;
             narration never touches it)
```

## Components

### Engine (package `engine/`, no Android UI imports)

- **TextPipeline** - Kotlin ports of app.py's `clean_markdown()` and `chunk()`
  (1,000-char sentence-boundary chunks). Deterministic, validated against test
  vectors generated from the Python originals. These two functions are the
  shared-DNA between the server and this app; the test vectors are the contract.
- **OrationCreation** - ordinary creation, gate preparation, and startup orphan
  sweeping share one manually wired process instance. Its private monitor covers
  directory preparation, same-directory pending source write/fsync/close/rename,
  and the Room transaction publishing the row and created log. Sweeping classifies
  and removes rowless directories under that same monitor. Expected validation
  and storage failures return typed results; rollback removes only preparation
  owned by that attempt, best effort. Saved gate source wins; explicit legacy
  repair retains the row, audio, checkpoint, narration time, and existing log.
  No model construction, inference, encoding, or chunk writes hold this monitor.
- **NarrationCoordinator** - owns ordinary queue admission, run identity,
  cooperative Stop, deletion barriers, outcome persistence, and pickup. It is
  retained once per process and manually wired by the shell. An attached
  narrator exposes `narrate(id)`, `stop()`, and `delete(id)` with typed
  `Deferred` outcomes, observable narration facts, and nonblocking detach.
  No caller receives the engine job, native model, or mutable stop predicate.
  Ordinary and gate execution use the same internal `OrationNarration`
  operation; gate verdicts remain in diagnostic execution.
- **OrationNarration** - synchronous internal execution on the coordinator's
  lane, accepting typed ordinary-ID or gate-bundle requests. It reads/cleans
  ordinary saved source or calls shared gate preparation, starts/resumes the
  lifecycle, acquires the coordinator-owned synthesizer lazily, scopes the real
  audio sink, and records chunks and the terminal outcome after closure. It
  emits immutable prepared/chunk-committed facts after the required writes,
  with no UI dispatch. Ordinary listen-now deduplication and all callback
  fencing stay in coordination. This operation owns no executor, attachment,
  run identity, model cache, or model close (ADR-009).
  Its injected clock supplies lifecycle logs, Room progress, chunk metadata,
  and outcomes as well as accumulated narration time. Production defaults to
  wall time; tests supply a distinct clock. Creation/recovery/admission and
  diagnostic measurement keep their existing clocks and owners.
- **Managed synthesizer seam** - construction, generation, and close happen
  within the coordinator's execution lane. The shell supplies sherpa-onnx's
  Kotlin/JNI adapter with the existing four-thread ordinary profile; tests
  supply a deterministic blocking resource. Phonemization remains sherpa-onnx's
  bundled espeak-ng. A cached model belongs to its attachment/profile and is
  closed only after its writer drains.
- **NarrationEngine** - drives an oration: for each remaining chunk, synthesize,
  encode, append to the oration's audio file, persist progress (chunk index +
  byte offset) in Room, emit progress. Resume = truncate to last persisted byte
  offset and continue, exactly the server's run_job resume design.
- **Estimator** - median seconds-per-word from completed orations on this
  device; fallback constant until 2+ samples exist (server's `calibrated_rates`
  logic).

### Shell

- **NarrationService** - a `MediaSessionService` (Media3) holding ExoPlayer and
  the ordinary narrator attachment. One service, one notification combines
  narration progress and playback controls; the foreground type is
  `mediaPlayback` while playing. The process coordinator retains blocking work
  during owner teardown, and ordinary queue pickup requires an attached owner
  (ADR-009). ADR-003's expedited WorkManager path remains planned; this change
  does not add background scheduling. The service is exported so the system
  can bind the session, and it ignores in-app commands (ADR-008).
- **InAppNarrationService** - not exported, no intent-filter. The UI starts it
  for narrate, play, stop narration, the sleep timer, and delete. It hands
  those to `NarrationService` inside the process. Another app cannot start it.
  Ordinary narration commands delegate to the attached narrator; rejected
  admission and diagnostic-only results remain observable to the caller.
- **Playback and listen-now** - ExoPlayer, MediaSession transport, the sleep
  timer, and playhead governor stay in the media owner. Listen-now follows a
  first-durable-audio event after fsync and saved progress, with attachment/run
  fencing. The governor persists the playhead and re-resolves a growing file
  when playback reaches its current end ("catching up"). Deletion invokes a
  narrow callback to stop matching playback on the player's thread. Observable
  deletion facts block playback until removal finishes; per-ID deletion versions
  fence pending reads from the removed audio. Explicit playback can use a newly
  created reserved gate in the same media-owner lifetime.
- **Lab** - attaches to the same coordinator with typed gate, measurement,
  thread configuration, Stop, and reset requests. It displays Busy when ordinary
  work is active or queued. Lab retains controls, PCM playback, progress, and
  thermal/battery observations, with no model or inference executor of its own.
  Saved gate playback uses the non-exported in-app play command and the existing
  media owner, so gate reset and library deletion stop that playback too.
  Leaving Lab detaches and fences callbacks immediately; retained cleanup
  drains blocking work off-main.
- **UI** - four screens (Confirm, Player, Library, About), Material 3, single
  activity, Navigation-Compose. Share intents land on Confirm. Confirm disables
  repeated local submission while awaiting creation off-main. A typed failure
  stays on Confirm with its text, name, and voice available for retry. Only a
  successful creation sends the ID-only non-exported narrate command and returns
  to Library. If the UI disappears before delivery, the committed QUEUED row
  remains available for ordinary pickup by an attached owner.

### Sync (flavor `sync` only, package `src/sync/`)

- **SyncEngine** - implements the client side of 12-api-contract.md: token
  auth (stored in Keystore-backed settings), library list/download/upload,
  rename LWW. Peripheral by construction: it mutates the same Room rows and
  files the engine owns, through the same DAOs, and nothing in `engine/` or
  `shell/` references it. The `local` flavor compiles without this source set
  existing.

## Audio format decision (resolved by SPIKE-001)

Kokoro emits 24 kHz mono PCM. `AdtsAacEncoder` encodes each chunk with a fresh
MediaCodec instance into appendable ADTS AAC at `orations/<id>/audio.aac`.
Frame-boundary truncation and deterministic per-chunk encoding preserve
byte-identical resume. Issue #15 keeps the encoder, progress, and audio-sink
seams and does not change this format.

## Data design

See 11-data-model.md. Summary: Room is the source of truth for metadata and
progress; the filesystem holds the two big artifacts (audio, source text) under
`filesDir/orations/<orationId>/`, mirroring the server's sidecar convention.

## Concurrency model

One retained worker owns all production inference and model lifetimes across
the process, including diagnostic construction, generation, encoding, file
work, gate progress/log writes, reconfiguration, and close. Its typed
diagnostic interface exposes neither native models nor arbitrary work closures.
Diagnostic starts return Busy while ordinary work is active or queued, leaving
ordinary work untouched. Ordinary requests admitted during diagnostics persist
in FIFO order and wait for the diagnostic to finish or cooperatively stop and
drain. Thread reload and supersession fence old callbacks and wait for the
previous writer and model. Ordinary narration keeps four threads; Lab selects
2/3/4 threads with its original measurement sid 0 and gate voice mapping.
Thread changes still control an admitted diagnostic when ordinary work waits.
Explicit Stop retains its terminal summary/verdict while fencing progress and
audio; supersession, detach, and deletion fence terminal callbacks as well.

Command admission is serialized separately from blocking execution. Pending
commands are considered before dispatch, so Stop accepted before dispatch
prevents source access and model loading. A vanished ordinary row does no work;
missing source records one error without a model and pickup continues. Lazy
ordinary acquisition closes any previous owner's model, then rechecks Stop
and attachment validity before replacement construction. Gate coordination
still constructs its model before preparation and checks early Stop there.
Stop during a chunk retains its run's ownership through
encoding, fsync, saved progress, sink closure, and terminal persistence. A
repeated same-ID request coalesces with the current or stopping run rather than
resetting its stop flag. Durable queue pickup orders by `queuedAt`, then ID.

Detach invalidates commands and callbacks immediately, prevents pickup, and
signals its active run. Coordinator-owned cleanup drains off-main and releases
the model afterward. A replacement attachment waits for that lifetime before
executing; queued rows remain durable while no owner is attached. Recovery is
once per process before admission, rather than once per media-owner creation.
The reserved `gate` is excluded from ordinary recovery pickup and interrupted
gate progress becomes `STOPPED` for an explicit diagnostic request. Replacing
the media owner during a live gate never reruns process recovery or stops the
Lab run. New gates persist source text; a legacy gate missing source adopts the
supplied bundle only on an explicit Lab request. Saved source wins on resume.

Deletion fences admission for its ID, stops matching playback, requests Stop,
and waits for the matching ordinary or diagnostic writer off-main. It removes the Room row before the
directory, reports completion after removal, and logs the result; startup
retries orphan-directory cleanup. Gate reset uses the same drain and deletion
barrier, retaining the log. Playback of existing durable audio
remains independent of queue ownership.

## Assumptions

- sherpa-onnx Kokoro RTF ≥ 1.3x on the floor device (SPIKE-001 verifies).
- arm64-v8a covers all target devices (ADR-005).
- A growing local file can be played by ExoPlayer with periodic
  re-resolution of duration - proven pattern; the web app already does the
  equivalent with its "Load latest" preview.
