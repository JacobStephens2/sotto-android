# Sotto narration

Sotto turns documents into orations that readers can keep, resume, and listen
to on their device.

## Language

**Oration**:
A library item containing a document's narration, title, voice, progress, and
listening position. Its audio can be queued, partial, complete, or available
only from a connected server.
_Avoid_: Track, job

**chunk**:
A sentence-boundary portion of a cleaned document narrated as one resumable
step. A completed chunk is the smallest unit of retained narration progress.
_Avoid_: Segment

**Narration worker**:
The exclusive owner of the current narration, from its start through its last
completed chunk and recorded outcome. A Stop requests a chunk-boundary finish;
the worker remains occupied until that finish is recorded.
_Avoid_: Player, playback session
