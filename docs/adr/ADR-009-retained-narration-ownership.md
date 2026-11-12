# ADR-009: Retain narration ownership across ordinary commands and Android-owner teardown

Status: accepted
Date: 2026-11-12

## Decision

Put ordinary admission, queue pickup, run identity, Stop, outcome persistence,
deletion, and recovery in the engine's `NarrationCoordinator`. Wire one retained
coordinator per process manually from the shell. The existing
`NarrationService` remains the Android owner of normal playback and its attached
narration lifetime, preserving ADR-003 and ADR-008.

An attachment exposes prompt-returning `narrate(id)`, `stop()`, and `delete(id)`
commands with typed `Deferred` outcomes, observable narration facts, and
nonblocking detach. It exposes no engine job, lock, native model, or mutable
stop predicate. Command admission is serialized separately from one blocking
execution lane. Every run has an internal identity and cooperative stop flag;
its ownership lasts through synthesis, encoding, fsync, progress persistence,
sink closure, terminal outcome persistence, and required model release.

Detach synchronously invalidates the attachment, fences its callbacks, stops
pickup, and requests a chunk-boundary finish. Cleanup belongs to the
coordinator and drains off-main after the Android callback returns. It closes
the model only after the writer exits. A replacement attachment can submit
commands while the old writer drains, but its execution waits. Durable queued
rows wait for an attached owner; cleanup does not start unattached narration.
The retained coordinator keeps application dependencies rather than an
Activity or a detached owner's callbacks.

The model factory constructs and closes managed synthesizers inside the same
execution lane. The ordinary profile remains four threads. Playback stays in
the Android owner: ExoPlayer, MediaSession transport, governor, and sleep timer
do not become coordinator responsibilities. A narrow playback-stop callback
supports deletion; a first-durable-audio event follows fsync and saved progress
for listen-now. Attachment and run identity fence stale playback events.

## Context

A blocking native chunk cannot be made safe by canceling its coroutine. The
former Android caller cleared its current oration ID on Stop, allowing a
second request to enter while the old writer still ran. Delete could remove
that writer's files, and teardown could close its live model. A retry while
busy could also be logged without making the row eligible for later pickup.

Keeping ownership only in an Android callback cannot cover safe cleanup that
outlives that callback. Moving normal playback away from the media owner would
reopen its Android lifecycle and notification arrangement. A retained engine
coordinator hides the blocking lifetime while keeping ordinary playback and
narration attached to the established owner.

Public run handles would require every caller to coordinate cancellation and
completion. A generic command mailbox would expose more protocol than the
ordinary caller needs. Domain commands keep those details private while the
managed synthesizer, encoder, progress, and audio seams support deterministic
tests with real Room rows and files.

## Consequences

- Successful retry admission first persists `QUEUED` and a new `queuedAt`,
  preserving durable progress. Pickup orders by `queuedAt`, then ID; duplicate
  requests preserve order and a stopping run's stop flag.
- Stop accepted before dispatch prevents model loading. Stop during a chunk
  retains ownership through durable completion; a completed final chunk can
  still yield `COMPLETE`. Teardown is a cooperative stop, not an ordinary
  narration error.
- Delete fences admission, stops matching playback, drains any matching
  ordinary writer off-main, then removes the row before the directory and logs
  the result. Success means removal finished; startup retries orphan-directory
  cleanup after an interrupted removal.
- Recovery runs once per process before admission. Ordinary interrupted rows
  retain queue time and progress. The reserved `gate` row is excluded from
  ordinary pickup; interrupted gate progress becomes `STOPPED` for explicit
  Lab resumption.
- Issue #15 establishes retained ownership for ordinary narration only.
  `GateRunner` and the spike/Lab still own independent models and execution.
  [Issue #16](https://github.com/JacobStephens2/sotto-android/issues/16) must
  route them through this coordinator and verify cross-caller exclusion before
  the constitution's complete process-wide one-worker guarantee is satisfied.
- This decision rules out main-thread joins, model release before a writer
  drains, and new ordinary pickup without an attachment. It does not add
  WorkManager scheduling or change permissions, model choice, audio format,
  playback policy, or the text pipeline.
