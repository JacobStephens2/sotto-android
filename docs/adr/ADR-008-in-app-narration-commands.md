# ADR-008: In-app narration commands use a non-exported entry point

Status: accepted
Date: 2026-11-11

Ownership clarification (2026-11-12):
[ADR-009](ADR-009-retained-narration-ownership.md) moves ordinary worker
coordination behind an attachment owned by the media service. This ADR still
governs command entry and exported Media3 transport access.

Session authorization update (2026-11-18, issue #32):
Exported `NarrationService` restricts Media3 session access across both
`onGetSession` and `MediaSession.Callback.onConnect` (installed via
`MediaSession.Builder.setCallback`) using one controller authorization check.
It accepts only same-UID controllers (`Process.myUid()`) and verified trusted
controllers (`controllerInfo.isTrusted`, checking `controllerInfo.packageName`
against `PackageManager.getPackagesForUid(controllerInfo.uid)` when UID packages
are resolvable and `uid != Process.SYSTEM_UID`). `onGetSession` continues to
return the session for negative-UID internal `MediaSessionService` intents
(`controllerInfo.uid < 0`). All other controllers are rejected (`null` from
`onGetSession`; `MediaSession.ConnectionResult.reject()` from `onConnect`).

Media-button update (2026-11-25, issue #38):
`NarrationService.onStartCommand` forwards `Intent.ACTION_MEDIA_BUTTON` to the
Media3 session only when the intent data is the active session URI, or when the
caller is this app (`Binder.getCallingUid() == Process.myUid()`) or the system
(`Process.SYSTEM_UID`). Media3 notification actions carry that URI, so play,
pause, and seek on the playback notification keep working. Any other
media-button start is ignored and is not passed to `MediaSessionService`.
Same-UID and verified trusted controller access is unchanged.

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
  transport controls for same-UID or verified trusted controllers; rules out
  granting `MediaSession` access to untrusted or UID-mismatched controllers.
- Rules out forwarding a media-button start that neither carries the active
  session URI nor comes from this app or the system. Does not rule out
  notification actions, which carry that URI, or a media-button start from
  this app or `Process.SYSTEM_UID`.
