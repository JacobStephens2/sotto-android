# ADR-010: Serialize Oration publication through one process owner

Status: accepted
Date: 2026-11-17

## Decision

Deepen creation into `engine/store/OrationPublication`. The manual process
provider retains one instance for the Room database and Oration directory,
injected into ordinary creation, ordinary/gate execution, the Narration worker,
and sync. Its private monitor serializes source publication, download promotion,
their Room commits and pre-commit rollback, row-first removal, and orphan
classification/removal. Remove the separate sync and deletion file protocols.

Preparation rereads a server-only row and returns an opaque immutable download
attempt carrying creation identity, expected server name, and a process-local
removal generation. Removal advances the generation before any storage work,
even if removal fails. Finalization revalidates identity, generation, target,
availability, and caller validity before mutation and inside its Room transaction.
Already-available rows keep their bytes, metadata, and log count.

Before replacing a final file, preserve it in a unique attempt-owned backup.
Register replacement ownership before rename/copy can partly succeed. Promote
audio and optional source, fsync/close, then update downloaded metadata and append
exactly one downloaded log in the same transaction, preserving `updatedAt`.
Pre-commit failure restores the preceding files under the monitor. After commit,
backup and staging cleanup are best effort and cannot roll back committed files.

The lock order is publication monitor, Room, then a brief sync validity read.
Enter publication off-main without a Room transaction or sync coordinator lock.
The validity predicate only samples the session epoch, cancellation, and
`deletingIds`; it performs no storage, suspension, or callback work. Network
staging, model loading, inference, encoding, and ordinary chunk writes stay
outside the publication monitor.

## Context

An old account's download could place audio, wait for Room, then roll back by
path after a newer account's download succeeded at that same path. The newer
download returned success with a downloaded row but no audio. Creation and
sweeping already shared exclusion; sync and deletion used independent protocols.

## Consequences

- Serialize unrelated local publication operations while disk work or Room waits.
  This deliberate cost avoids a per-ID lock registry plus a separate sweep lock.
- Keep ADR-003's Android media owner, ADR-007's flavors and sync session owner,
  and ADR-009's admission, playback fencing, writer/model drain, and execution
  lane. The Narration worker calls storage removal only after its existing drain.
- Sync keeps transfers, progress, private session epochs, transfer staging cleanup,
  and abandoned-staging cleanup at startup. The engine accepts local data only.
- Use the publication interface with real Room/files and narrow source/staged-file
  adapters for deterministic barriers, partial operations, fsync faults, and log
  rejection. Keep attached-command, durability/resume, transport, and UI coverage.
- This is ordered filesystem/Room publication without a durable journal or a
  cross-store power-loss guarantee. Attempts need no release and cannot survive
  process death. No schema, permissions, model, or text-pipeline change is needed.
