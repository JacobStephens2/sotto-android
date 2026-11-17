# Sotto narration

Sotto turns documents into orations that readers can keep, resume, and listen
to on their device.

## Language

**Oration**:
A library item containing a document's narration, title, voice, progress, and
listening position. Its audio can be queued, partial, complete, or available
only from a connected server.
_Avoid_: Track, job

**Oration publication**:
Process-scoped ownership of making an Oration's saved source, downloaded audio,
and matching Room facts available, including pre-commit rollback, row-first
removal, and orphan reclamation.
_Avoid_: Oration creation (for this module), repository

**chunk**:
A sentence-boundary portion of a cleaned document narrated as one resumable
step. A completed chunk is the smallest unit of retained narration progress.
_Avoid_: Segment

**Narration worker**:
The exclusive owner of the current narration, from its start through its last
completed chunk and recorded outcome. A Stop requests a chunk-boundary finish;
the worker remains occupied until that finish is recorded.
_Avoid_: Player, playback session

**Oration playback**:
The Android-owner-scoped control of saved Oration audio, its listening position,
growing-audio catch-up, and sleep deadline. It does not own the Narration worker.
_Avoid_: Narration worker, synthesis owner
