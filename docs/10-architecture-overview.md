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
│    └── OrationPlayback → Media3 output → owned ExoPlayer          │
│  Lab → typed diagnostics → same NarrationCoordinator lane        │
│  Room DB: orations · chunks-progress · joblog · settings           │
│  Files (app-private): orations/<id>/audio + source.md              │
└─────────────────────────────────────────────────────────────────────┘
        ▲
        └── `local` flavor: NO network - no INTERNET permission exists (ADR-004).
            `sync` flavor wires SyncFactory in src/sync only (ADR-007):
            SyncCoordinator (shell/sync) ──▶ RemoteServer / SottoApi (/api/v1)
            ├── single-flight library sync + LWW rename · CredentialStore
            ├── external sync-staging/ → Oration publication · deletingIds abort
            └── retained ServerNarrationState poll (Confirm · Library · Account)
```

## Components

### Engine (package `engine/`, no Android UI imports)

- **TextPipeline** - Kotlin ports of app.py's `clean_markdown()` and `chunk()`
  (1,000-char sentence-boundary chunks). Deterministic, validated against test
  vectors generated from the Python originals. These two functions are the
  shared-DNA between the server and this app; the test vectors are the contract.
- **Oration publication** (`OrationPublication`, ADR-010) - ordinary creation, gate preparation, and startup orphan
  sweeping share one manually wired process instance. Its private monitor covers
  directory preparation, same-directory pending source write/fsync/close/rename,
  and the Room transaction publishing the row and created log. Sweeping classifies
  and removes rowless directories under that same monitor. Expected validation
  and storage failures return typed results; rollback removes only preparation
  owned by that attempt, best effort. Saved gate source wins; explicit legacy
  repair retains the row, audio, checkpoint, narration time, and existing log.
  Download preparation rereads server-only rows and returns opaque attempts with
  creation identity, server name, and removal generation. Finalization revalidates
  those facts, availability, and sync's synchronous caller-validity predicate
  before final-file mutation and inside its downloaded-row/log transaction.
  Unique backups preserve preceding audio/source; ownership is registered before
  possibly partial promotion. Pre-commit rollback restores files while holding
  the monitor; committed files survive cancellation and best-effort cleanup.
  Removal advances the generation first, deletes the row before the directory,
  and logs success/error. All production paths share the provider's instance.
  No network transfer, model construction, inference, encoding, or chunk writes
  hold this monitor. Enter off-main outside Room and the sync lock; ordering is
  publication monitor, Room, then a brief validity read under the sync lock.
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
  can bind the session, and it ignores in-app commands (ADR-008). Both
  `onGetSession` and the `MediaSession.Callback.onConnect` installed via
  `MediaSession.Builder.setCallback` enforce one controller authorization check,
  accepting same-UID controllers (`Process.myUid()`) and verified trusted
  controllers (`controllerInfo.isTrusted`, matching
  `PackageManager.getPackagesForUid` when resolvable and `uid != SYSTEM_UID`),
  while `onGetSession` keeps returning the session for negative-UID internal
  `MediaSessionService` intents and rejecting all other controllers.
  `onStartCommand` forwards a media-button start only when the intent carries
  the active session URI, or when the binder caller is still inside a
  transaction and is this app (`Binder.getCallingUidOrThrow()` equals
  `Process.myUid()`) or the system (`Process.SYSTEM_UID`). A start that arrives
  with no session URI after the platform has cleared the caller is ignored.
  Notification actions carry that URI. Any other media-button start is ignored.
  `ExoPlayer.Builder` configures `15,000 ms` seek-back and seek-forward
  increments, and `Media3PlaybackOutput` publishes active sleep-timer state
  into `MediaSession.setSessionExtras`.
- **InAppNarrationService** - not exported, no intent-filter. The UI starts it
  for narrate, play, stop narration, the sleep timer, and delete. It hands
  those to `NarrationService` inside the process. Another app cannot start it.
  Ordinary narration commands delegate to the attached narrator; rejected
  admission and diagnostic-only results remain observable to the caller.
- **Oration playback** - one module inside the media owner accepts explicit
  `play(id)`, saved-row `listenNow`, `stopForDeletion(id)`, `sleep(minutes)`, and
  `close()`. The service still constructs/releases ExoPlayer and MediaSession;
  a narrow Media3 output adapter observes identity, position, ended state,
  intent, and speed together on the player thread and publishes active sleep
  minutes via `publishSleepTimer` on arm, replace, cancel, expiration, and close.
  The module owns the two-second
  governor, selection ordering, durable-byte frontier, deletion epochs, and
  timer tokens. Room reads and file checks happen off-main. Latest explicit
  acceptance wins, even if unavailable; listen-now needs neither a selection
  nor a pending explicit request. Expected missing/server-only/empty audio
  leaves existing media intact. Ended audio reloads only for durable growth,
  including the worker's final tail, preserving current position, speed, and
  pause intent after revalidation; a seek away from the frontier wins.
  Deletion synchronously invalidates `PlaybackPersistence` (even when `output` is
  already released on close), fences pending work, and clears matching media;
  facts
  prevent admission during removal while epochs allow later gate recreation.
  A private application-scoped FIFO accepts immutable positive-position
  observations, including close, across replacement owners. Each write checks
  its deletion epoch inside a Room transaction and preserves `updatedAt`.
  Latest accepted primitive positions also supply selection while Room writes
  drain, so switching back or replacing an owner cannot restore a stale position.
  Deletion forgets those positions along with advancing the epoch.
  `NarrationCoordinator` also accepts an `onDeletionFenced` callback invoked inside
  `beginDeletion` (wired to `PlaybackPersistence::invalidateShared`), so
  unattached Lab `ResetGate` clears cached positions without forcing eager
  persistence startup.
  Close synchronously fences effects and releases the output, then returns an
  awaitable FIFO drain without joining on the player thread. Accepted sleep
  deadlines pause once and enqueue a local log off-main; replacement, cancel,
  and close invalidate unaccepted deadlines. Storage failures are contained
  and locally logged. No playback responsibility moves into the Narration worker.
- **Oration player** - client UI facade (`OrationPlayer` behind `PlayerTransport`
  and `PlayerCommands` in `shell/playback/OrationPlayer.kt`) scoped to one
  open Player screen. It observes Room `byIdFlow(id)`, `PlaybackPersistence`,
  `source.md` loading/reload on download flip, Media3 `Player.Listener.onEvents`,
  and `MediaController.Listener` (`onExtrasChanged` / `onDisconnected`). It emits
  discrete `OrationPlayerState` (`Loading`, `Missing`, `Ready`) and high-frequency
  `PlaybackTimeline(positionMs, durationMs)`. Pre-load and `TIME_UNSET` frames
  resolve position from `PlaybackPersistence.resumePosition` and duration from
  `audioDurationMs` or `(audioBytes * 8L) / 64L`, clamped to valid bounds.
  Mutations (`seekTo`, `skipBack` via `seekBack()`, `skipForward` via
  `seekForward()`, `setSpeed`, active `pause`) apply only when `connected &&
  mediaId == id`; `togglePlayPause` on non-loaded rows dispatches `ACTION_PLAY`
  only when local audio exists, and `STATE_ENDED && status == "NARRATING"`
  preserves catching-up mode rather than seeking to zero. An active-only 250 ms
  playhead ticker runs only while loaded and playing and stops immediately on
  pause, `STATE_ENDED`, item switch, disconnect, `Missing`, or `close()`.
- **Lab** - attaches to the same coordinator with typed gate, measurement,
  thread configuration, Stop, and reset requests. It displays Busy when ordinary
  work is active or queued. Lab retains controls, PCM playback, progress, and
  thermal/battery observations, with no model or inference executor of its own.
  Saved gate playback uses the non-exported in-app play command and the existing
  media owner, so gate reset and library deletion stop that playback too.
  Leaving Lab detaches and fences callbacks immediately; retained cleanup
  drains blocking work off-main.
- **Document intake** - `engine/text/Intake.accept` receives typed text or a
  deferred stream opener and returns Accepted, Empty, TooLarge, or Unreadable.
  It owns IO dispatch, replacement UTF-8 decoding, one initial file BOM,
  Kotlin trimming, the normalized 409,600-byte cap, and closure, including
  cancellation. Fixed decoder buffers plus bounded content/pending whitespace
  preserve complete-document semantics without retaining unbounded surrounding
  whitespace. EOF discards pending trailing whitespace; later content makes it
  interior and can establish TooLarge early. The synchronous String validator
  remains the defensive check inside Oration publication's monitor.
- **UI** - four screens (Confirm, Player, Library, About), Material 3, single
  activity. Share intents first render Reading, then accepted input lands on
  Confirm. Only an unfinished saved Reading state reopens the launch input;
  restored Confirm and other screens skip intake. Deferred openers retain the
  application ContentResolver and URI. Composition/lifecycle cancellation and
  screen identity fence abandoned requests. Write uses the same intake operation,
  disables pending Continue, and keeps rejected text. Confirm disables
  repeated local submission while awaiting creation off-main. A typed failure
  stays on Confirm with its text, name, and voice available for retry. Only a
  successful creation sends the ID-only non-exported narrate command and returns
  to Library. If the UI disappears before delivery, the committed QUEUED row
  remains available for ordinary pickup by an attached owner. `PlayerScreen`
  creates and disposes one `OrationPlayer` per Oration ID, collects discrete
  `OrationPlayerState` (`Loading`, `Missing`, `Ready`) without Activity-owned
  `MediaController` polling loops, and isolates `PlaybackTimeline` updates in
  a leaf `PlayerTimeline` composable where `Slider` holds local drag state
  until release (`onValueChangeFinished`). `DropCapText` keys updates on
  `(text, capArgb, bodyArgb, bodySizeSp, lines)` so repeated recompositions are
  idempotent while body color/size changes apply cleanly.

### Sync (`shell/sync/` interface, `src/sync/` implementation)

- **SyncCoordinator** - `SyncCoordinator` and `SyncCoordinatorProvider` live in
  `main/kotlin/.../shell/sync/`, returning `null` from the `local` flavor's
  `SyncFactory` (zero network code, encrypted credentials, or network
  permissions) and a manually wired, process-retained `RealSyncCoordinator`
  singleton in `sync` (with `setForTest` / `shutdownForTest` hooks matching
  `NarrationCoordinatorProvider`).
- **RealSyncCoordinator, RemoteServer, CredentialStore** - confined to
  `src/sync/` (`SottoApi` over stdlib `HttpURLConnection`,
  `EncryptedCredentialStore` over Android Keystore). `engine/` never imports
  Compose, shell, or network; `local` compiles without `src/sync/`.
- **Ownership and isolation** - single-flight library reconciliation,
  monotonic session epochs (cancelling child scope and active sockets on
  off-main `disconnect()` or matching-epoch `401`), external staging in
  `filesDir/sync-staging/` outside `orations/` (swept at startup so
  `OrationPublication.sweepOrphans()` never disturbs live transfers), active
  `deletingIds` cancellation, ID-based download deduplication, download
  finalization through shared Oration publication, and retained
  `ServerNarrationState` polling live behind
  `SyncCoordinator` across Activity recreation.
- **Library action sheet** - the Library resolves the selected ID and creation
  time against its lifecycle-collected Room list. The sheet and its drafts stay
  open for updates to that Oration and close on removal or replacement. Account
  and transfer state come from `SyncState`. Upload progress follows the
  same ID and creation time as the sheet.
- **Upload admission** - the coordinator accepts only the ID and expected
  creation time. It reads the saved row off-main in a Room transaction and
  checks session, deletion, local eligibility and any existing transfer before
  reserving both server names. Matching requests join the retained transfer
  through its refresh. Completion links the same, still-unlinked Oration and
  writes its upload log in one transaction ordered with account changes.

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
directory through Oration publication, reports completion after removal and its
log, and logs failures; startup
retries orphan-directory cleanup. Gate reset uses the same drain and deletion
barrier, retaining the log. Playback of existing durable audio
remains independent of queue ownership.

## Assumptions

- sherpa-onnx Kokoro RTF ≥ 1.3x on the floor device (SPIKE-001 verifies).
- arm64-v8a covers all target devices (ADR-005).
- A growing local file can be played by ExoPlayer with periodic
  re-resolution of duration - proven pattern; the web app already does the
  equivalent with its "Load latest" preview.
