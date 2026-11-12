# ADR-008: In-app narration commands use a non-exported entry point

Status: accepted
Date: 2026-11-11

## Decision

Start narration, play, stop narration, the sleep timer, and delete are accepted
only by `InAppNarrationService`, which is not exported and has no intent-filter.
The UI is the caller. `NarrationService` stays the exported `MediaSessionService`
and ignores those actions. It still binds the Media3 session, so transport
controls (play, pause, seek) keep working through the session.

## Context

`NarrationService` has to be exported: the system binds
`androidx.media3.session.MediaSessionService` to drive the media session. Those
five actions were handled in its `onStartCommand`, and any other app can start
an exported service. That let another app start narration, play an oration,
stop narration, arm the sleep timer, or delete an oration and its files.

The commands need the player and the narration worker, which stay in the one
foreground media service (ADR-003). The non-exported service only accepts the
intent and hands it to that owner inside the process. A blank start of the
exported service wakes the owner when it is not running; the command is not
put on that intent.

## Consequences

- Another app's intent with those actions does nothing. Same-app code can still
  start `InAppNarrationService`; the UI is the only caller in this codebase.
- A stop that arrives before synthesis starts does not load the model. The
  oration is marked stopped unless it was already complete.
- Rules out handling narrate, play, stop narration, sleep timer, or delete in
  `NarrationService.onStartCommand`. Does not rule out ordinary Media3
  transport controls on the exported session.
