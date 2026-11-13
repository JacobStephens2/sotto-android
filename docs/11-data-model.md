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
| words | Int | ordinary intake text word count; gate uses cleaned saved text |
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

Ordinary and new gate publication uses `OrationCreation`: write a unique
same-directory pending source, fsync and close it, then rename to `source.md`
before inserting the row and created log together in one Room transaction.
Ordinary IDs are generated inside creation; an existing row or directory is
refused without overwriting or removing it. Intake rejects empty or over-400-KB
input through typed outcomes. Expected storage failures leave no new row or
created log, and remove only attempt-owned artifacts best effort. An abandoned
rowless directory, including pending remnants, is reclaimable at startup.

Creation and orphan classification/removal share a private process monitor,
so sweeping cannot remove a live preparation before its row commits. Every
directory with a row survives sweeping. This is ordered filesystem/Room
publication, not a cross-store atomic transaction or a power-loss guarantee.
Schema and queue ordering are unchanged. Sync library/download publication
keeps its separate metadata-first protocol.

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
  Observable deletion facts clear before completion is reported. Playback fences
  the removed generation, allowing the reserved gate ID to be created and played
  again without recreating the Android media owner.

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
- Threads can stop/reconfigure an admitted diagnostic even with ordinary work
  waiting; Gate/Measure starts still return Busy. Explicit Stop retains the
  current run's terminal measurement summary or gate verdict. Progress/audio
  are fenced on Stop; supersession, detach, and deletion also fence terminal events.
- Gate uses reserved ID `gate`, title `Phase 1 gate`, and voice `af_heart`.
  New runs use the same source publication and row/log transaction as ordinary
  creation. Explicit legacy resumption
  writes the supplied bundled text if `source.md` is missing; otherwise saved
  text wins. Existing durable audio/checkpoint/narration time and log survive
  recovery and resume. An interrupted gate becomes STOPPED once per process.

## Invariants

1. `audioBytes` is only advanced after an fsync of the audio file (chunk
   boundary durability - lost work on crash is at most one chunk).
2. `chunksDone` and `audioBytes` move together in one Room transaction.
3. `title` is never derived twice: derived at creation, then only user renames
   touch it.
4. An oration directory without a Room row is orphaned outside live publication;
   startup classifies/removes it under the creation monitor and logs removal or
   failure. A local row whose source is missing yields an
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
