# Data Model - sotto for Android

Room is authoritative for metadata and progress; the filesystem holds the large
artifacts. Layout mirrors the server's library sidecar convention (`.mp3` +
`.md` + `.title` + `.meta`) so the two products stay conceptually aligned.

## Entities

### Oration

| Column | Type | Notes |
|---|---|---|
| id | UUID string, PK | Directory name under `filesDir/orations/` |
| title | String(120) | Verbatim user name, else heading-derived (server `convert()` rule) |
| status | enum | see state machine |
| voiceId | String | one of the 7 `KOKORO_VOICES` |
| words | Int | word count of cleaned text |
| chunksTotal | Int | set when chunking completes |
| chunksDone | Int | progress; resume point |
| audioBytes | Long | last durable byte offset; truncate-to here on resume |
| audioDurationMs | Long | derived, updated per chunk |
| narratedMs | Long | accumulated wall-clock synthesis time across resumes (fixes the server's known undercount) |
| createdAt / updatedAt | Long | epoch ms |
| queuedAt | Long | durable ordinary request time; pickup sorts ascending, then by stable ID |
| playheadMs | Long | last listening position (S2 position memory) |
| errorMessage | String? | ERROR: terminal error detail; STOPPED: the stop's cause when it was not the user (storage full) |
| serverName | String? | sync key: filename in the server library (`sync` flavor; null = never synced) |
| serverMtime | Long? | server-side mtime at last sync, for rename LWW (12-api-contract.md) |
| downloaded | Bool | false for SERVER_ONLY rows (metadata known, audio not yet fetched) |

Files: `orations/<id>/audio.aac` (ADTS AAC), `orations/<id>/source.md`.

Schema v2 adds `queuedAt`. The v1 migration initializes it from `createdAt`
without rebuilding rows or changing titles, sync metadata, listening positions,
chunk counts, byte offsets, or accumulated narration time. New rows initialize
it at creation. `updatedAt` remains a general metadata timestamp, not a queue
ordering field.

### JobLogEntry

Append-only traceability (boundary 5): `id, at, orationId?, title, event, detail`.
Events: created, queued, started, resumed, chunk-done (after saved durability),
stopped, completed, error, deleted, exported, renamed. Viewable in About →
Activity log. Never leaves the device.

### Settings (single row / DataStore)

`lastVoiceId` (S1 preselect), `playbackSpeed`, `sleepTimerDefault`,
`estimatorRates` (per-voice median s/word once ≥ 2 samples).

## State machine (Oration.status)

```
            ┌────────────┐
 created ──▶│  QUEUED    │──── worker picks up ───▶ NARRATING
            └────────────┘                            │  │  │
   user stop ──────────────────────────────◀──────────┘  │  │
        ▼                                                 │  │
    STOPPED  (partial kept, playable)         all chunks ─┘  └─ failure
                                                  ▼              ▼
                                               COMPLETE        ERROR
                                                                 │
                                  retry (re-queue from chunksDone) ──▶ QUEUED
```

- Ordinary QUEUED → NARRATING only via the coordinator's attached narrator;
  FIFO by `queuedAt`, then ID. The reserved diagnostic ID `gate` is excluded.
- STOPPED/ERROR retry → QUEUED is persisted before successful admission, with
  a refreshed `queuedAt` and unchanged durable progress. Already queued or
  still-running requests coalesce without moving queue time; a request for the
  same stopping run does not revive it. A retry after that run reaches STOPPED
  is a new admission, even while the previous model is closing. Execution
  continues to wait for that drain.
- Process death in ordinary NARRATING → once-per-process recovery reverts it
  to QUEUED with `queuedAt`, `chunksDone`, `audioBytes`, and narration time
  intact. Recovery completes before ordinary admission and is not repeated on
  Android-owner attachment. An interrupted `gate` becomes STOPPED with its
  checkpoint intact, never an ordinary queued run. Recovery records ordinary
  requeue and diagnostic stop events in the local activity log atomically with
  their state transitions.
- STOPPED and COMPLETE are both playable; STOPPED shows "stopped at n of N".
- Stop retains run ownership through the current durable chunk and outcome
  persistence. If the final chunk finishes before Stop takes effect, the
  outcome is COMPLETE. Storage full stays STOPPED with its existing cause;
  other ordinary failures become ERROR. Owner teardown requests a cooperative
  stop rather than converting teardown into ERROR.
- Deletion establishes an admission barrier, stops matching playback, and
  waits off-main for the matching ordinary or diagnostic writer to close. It then deletes the row
  before its directory and logs the result. These are ordered operations,
  not a filesystem/Room transaction; success is reported only after removal.
  Startup retries orphan-directory cleanup after an interrupted deletion.

### Ordinary admission results

| Request state | Result and durable effect |
|---|---|
| Local QUEUED | Admit or coalesce; preserve queue order |
| Local STOPPED / ERROR | Persist QUEUED and new `queuedAt`, keeping durable progress, then admit |
| Current active or stopping run | Coalesce with that run; preserve its stop flag |
| Missing, COMPLETE, server-only, or detached attachment | Typed no-op/rejection; no model load |
| Incomplete reserved `gate` | Diagnostic-only result directing the reader to Lab; no ordinary pickup |
| ID fenced for deletion | Reject admission until removal has finished |

Callers display failed-admission and diagnostic-only outcomes. Durable gate
audio remains playable and the library can request deletion. Library deletion
and typed Lab ResetGate both stop/drain a matching diagnostic writer before
removing files or progress; the append-only log remains.

### Diagnostic admission and reserved gate

- Typed Gate/Measure requests return Busy if ordinary work is active or queued;
  no ordinary run is stopped or mutated. Ordinary requests during admitted
  diagnostics persist QUEUED with normal FIFO semantics and wait for drain.
- Lab owns a separate attachment. Stop, supersession, thread reload, and detach
  fence diagnostic callbacks and stop at a chunk boundary. Construction,
  encoding, durable gate progress/log writes, and model close share the retained
  lane; replacement execution waits for all of them. Media-owner attachment
  neither recovers nor claims the active diagnostic run.
- Gate uses reserved ID `gate`, title `Phase 1 gate`, and voice `af_heart`.
  New runs fsync source text before row creation. Explicit legacy resumption
  writes the supplied bundled text if `source.md` is missing; otherwise saved
  text wins. Existing durable audio/checkpoint/narration time and log survive
  recovery and resume. An interrupted gate becomes STOPPED once per process.

## Invariants

1. `audioBytes` is only advanced after an fsync of the audio file (chunk
   boundary durability - lost work on crash is at most one chunk).
2. `chunksDone` and `audioBytes` move together in one Room transaction.
3. `title` is never derived twice: derived at creation, then only user renames
   touch it.
4. An oration directory without a Room row is orphaned; startup retries its
   removal and logs the result. A local row whose source is missing yields an
   ordinary failure and does not prevent pickup of later queued rows.
5. In the `local` flavor the sync columns are always null/false and no entity
   stores account ids or URLs - nothing to sync, nothing to leak. In the `sync`
   flavor, the only stored identifiers are the user's chosen server URL and
   token (Keystore-backed settings, never in Room) and `serverName` per entry
   (constitution.md boundary 2).
6. SERVER_ONLY rows (`downloaded = false`) are metadata mirrors: no files on
   disk, not playable, excluded from the estimator and the job log until
   downloaded.

## Validation rules

- title: trimmed, 1-120 chars after fallback derivation.
- input text: non-empty after `clean_markdown`, ≤ 400 KB raw (server parity).
- voiceId: must be in the bundled voice list; unknown → default voice
  (server's coerce rule).
