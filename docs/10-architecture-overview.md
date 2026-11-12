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
│    │     admission → one retained ordinary execution lane         │
│    │     └── NarrationEngine → managed model → PCM → encoder      │
│    │           └── fsync audio → persist progress → durable event │
│    └── Media3 ExoPlayer ← plays the growing ADTS audio file        │
│  Lab / GateRunner → independent diagnostics (until issue #16)     │
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
- **NarrationCoordinator** - owns ordinary queue admission, run identity,
  cooperative Stop, deletion barriers, outcome persistence, and pickup. It is
  retained once per process and manually wired by the shell. An attached
  narrator exposes `narrate(id)`, `stop()`, and `delete(id)` with typed
  `Deferred` outcomes, observable narration facts, and nonblocking detach.
  No caller receives the engine job, native model, or mutable stop predicate.
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
  narrow callback to stop matching playback on the player's thread.
- **Lab / GateRunner** - still independently construct and run diagnostic
  models in this intermediate scope. Issue #16 routes gate, measurement,
  thread changes, Stop, and reset through retained process ownership. They
  remain an explicit gap in the complete one-worker rule until then.
- **UI** - four screens (Confirm, Player, Library, About), Material 3, single
  activity, Navigation-Compose. Share intents land on Confirm.

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

The constitution requires one synthesis worker across the process. Issue #15
enforces one retained ordinary worker; independent diagnostic ownership remains
until issue #16. Do not describe this intermediate implementation as a complete
process-wide guarantee.

Command admission is serialized separately from blocking execution. Pending
commands are considered before dispatch, so Stop accepted before dispatch
prevents model loading. Stop during a chunk retains its run's ownership through
encoding, fsync, saved progress, sink closure, and terminal persistence. A
repeated same-ID request coalesces with the current or stopping run rather than
resetting its stop flag. Durable queue pickup orders by `queuedAt`, then ID.

Detach invalidates commands and callbacks immediately, prevents pickup, and
signals its active run. Coordinator-owned cleanup drains off-main and releases
the model afterward. A replacement attachment waits for that lifetime before
executing; queued rows remain durable while no owner is attached. Recovery is
once per process before admission, rather than once per media-owner creation.
The reserved `gate` is excluded from ordinary recovery pickup and interrupted
gate progress becomes `STOPPED` for an explicit diagnostic request.

Deletion fences admission for its ID, stops matching playback, requests Stop,
and waits for the ordinary writer off-main. It removes the Room row before the
directory, reports completion after removal, and logs the result; startup
retries orphan-directory cleanup. Draining an independent diagnostic writer
during gate deletion is part of issue #16. Playback of existing durable audio
remains independent of queue ownership.

## Assumptions

- sherpa-onnx Kokoro RTF ≥ 1.3x on the floor device (SPIKE-001 verifies).
- arm64-v8a covers all target devices (ADR-005).
- A growing local file can be played by ExoPlayer with periodic
  re-resolution of duration - proven pattern; the web app already does the
  equivalent with its "Load latest" preview.
