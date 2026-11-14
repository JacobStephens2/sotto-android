# Tasks - sotto-android (living)

Status: release hardware gates remain; ordinary and Lab narration share retained
process-wide ownership (issues #15-16). Updated 2026-11-14.

## Durable Oration narration (issue #22)

- [x] Share ordinary/gate source preparation, lifecycle, chunk metadata/logs,
      sink closure, and outcomes in one internal operation; absorb outcome recorder
- [x] Preserve retained coordination/model lifetime and finite ordinary/gate
      differences, with one injected clock throughout shared execution
- [x] Cover attached-command setup/chunk/preparation failures and clocks with
      real Room/files, including Stop/detach during previous-model close
- [x] Validate both flavors' JVM/lint/screenshot checks, relevant Room/ADTS
      instrumentation or concrete blocker, manifest guards, and Standards/Spec review

## Oration creation (issue #20)

- [x] Share ordinary/gate publication and orphan cleanup behind one process
      OrationCreation instance; publish source before transactional row/log
- [x] Await local Confirm creation off-main, prevent duplicate submission, and
      retain fields with retryable save errors
- [x] Cover real Room/files publication ordering, faults, contention, collisions,
      orphan remnants, legacy gates, and Confirm success/failure
- [x] Validate both flavors' JVM/lint/screenshot checks, relevant Room/gate
      instrumentation, and Standards/Spec review; record results in `_state.md`

## Narration ownership (issues #14-16)

- [x] Issue #15: attached ordinary narration commands, durable retry FIFO,
      retained Stop/deletion/drain, managed model lifetime, and interface tests
- [x] Glossary and ADR-009 document ordinary process retention, off-main drain,
      callback fencing, and the existing Android media owner
- [x] Governing architecture/data-model/coding/testing guidance distinguishes
      ordinary ownership from the still-independent Lab caller
- [x] Validate issue #15 with both flavors' JVM/lint/screenshot checks, relevant
      Room/adapter instrumentation, and existing manifest guards; record results
      in `_state.md`
- [x] Issue #16: route Lab gate/measurement/thread changes/Stop/reset through
      the shared owner; cover cross-caller exclusion, drain, recovery, and legacy data
- [x] Complete ADR-009 and architecture/diagnostic/data-model/test guidance for
      process-wide ownership, diagnostic Busy, waiting FIFO, and reserved gate
- [x] Validate issue #16 with both flavors' JVM/lint/screenshot checks, relevant
      Room/ADTS instrumentation and manifest guards; record review and results
- [x] Review Spec #14 as a whole, repair gate playback reset/recreation and shared
      outcome persistence, and validate both flavors plus Room/ADTS instrumentation

## Now (Phase 0 - SPIKE-001)

- [x] Create `sotto-android` repo (created private 2026-06-12:
      github.com/JacobStephens2/sotto-android); license decision still open
- [x] Copy this doc-set into the repo (canonical here as docs/ since
      2026-06-12; server copy is non-canonical) - server-README pointer pending
- [ ] Bare spike app: sherpa-onnx dependency, bundled Kokoro int8 + voices,
      synthesize fixed 2,000-word text to PCM on-device
- [x] Measured on device (2026-06-12): sustained RTF 1.06x/30.7min, 19.5%/hr,
      thermal NONE, peak PSS 793-1080MB, APK 203MB - see `_state.md`
- [x] Audio container DECIDED: ADTS AAC via MediaCodec (appendable,
      frame-boundary truncatable, ExoPlayer-native, deterministic per-chunk
      with a fresh codec) - AdtsAacEncoder, instrumented-tested 2026-06-12
- [x] Gate decision recorded in `_state.md`: **PASS - proceed** (2026-06-12)

## Phase 1 - engine

- [x] **Server repo:** `tools/generate_pipeline_vectors.py` + 24-case
      pipeline-vectors.json committed (sha256 7566642d...) - 2026-06-12
- [x] Port cleanMarkdown + chunk - all 24 vectors green on JVM (engine/text/TextPipeline.kt) - 2026-06-12
- [x] Room schema per 11-data-model.md (v1 baseline, exportSchema) - 2026-06-12
- [x] NarrationEngine durability/resume + instrumented kill-test (DB-reopen +
      crash-garbage, byte-identical, on emulator) - 2026-06-12
- [x] Job log writer + coverage assertions (in RoomResumeTest) - 2026-06-12

## Phase 2 - vertical slice (21-vertical-slice-plan.md)

- [x] Share-intent → Confirm (derived title, estimate) → narrate → library row
      (verified end-to-end on emulator via uiautomator/state, 2026-06-12)
- [x] NarrationService (Media3 MediaSessionService) + MediaSession w/ metadata
      (session live with title/artist on emulator) - 2026-06-12
- [x] Playhead governor: 2s loop persists playhead + re-resolves the growing
      ADTS file on STATE_ENDED while narrating - 2026-06-12 (device validation pending)
- [x] ci.yml flavor-aware (both flavors, per-flavor guards); release.yml
      tag-triggered signed dual-flavor release w/ aapt proof in notes - dry run
      via v0.3.1 tag 2026-06-12
- [ ] One week of daily real use - exit gate

## Phase 3 - v1 surface

- [x] Library long-press sheet: rename / delete (in-app non-exported command,
      real deletion; ADR-008) /
      source view / export via SAF - 2026-06-12
- [x] Player screen over MediaController: seek slider, 15s skips, speed chips
      (0.8-2x), sleep timer (15/30/60, service-side), catching-up state - 2026-06-12
- [x] Voice picker w/ bundled previews + AUTHORITATIVE sid map (from sherpa's
      generate_voices_bin.py - af_heart=3; early builds spoke af_alloy=0);
      calibrated estimate (median of completed orations) - 2026-06-12
- [x] About: provenance + no-network claim + on-device activity log - 2026-06-12
      (enforced by AboutScreenTest per flavor since 2026-10-15; constitution 6)
- [x] PRD edge cases (code + JVM/emulator verified) - 2026-10-15:
      storage-full = NarrationResult.StorageFull, partial trimmed to the last
      durable chunk, STOPPED with the cause shown, resume byte-identical;
      queueing = FIFO single worker (already in place); thermal catch-up =
      playhead governor + "Catching up" (already in place); encoding/intake =
      Intake (UTF-8 w/ replacement, BOM dropped, empty refused, 400 KB cap),
      shared/opened .txt/.md files (PRD F1). Still needs the on-device pass
      (storage-full and thermal can't be produced on the emulator honestly).
- [x] Lint in CI; fixed a crash on Android 10-13 (TextPipeline used
      Matcher.appendReplacement(StringBuilder), API 34) - 2026-10-15

## Phase 4 - release 1.0

- [x] Keystore: 4096-bit RSA, 32-char random password, SOPS/age-encrypted
      backup at ~/.config/sotto/release-signing.env.sops (verified by decrypt
      BEFORE plaintext removal), CI secrets pushed; signing via SOTTO_* env -
      2026-06-12
- [x] README (claim + verification command + flavor table), PRIVACY.md,
      adaptive launcher icon from the brand mark - 2026-06-12
- [x] License DECIDED: GPL-3.0 (espeak-ng inside sherpa-onnx is GPL-3.0, so
      the combined work is copyleft regardless; matches app-repo conventions).
      LICENSE + ATTRIBUTIONS.md committed - 2026-06-12
- [x] Repo public (pre-flip audit: gitleaks clean over full history, no
      keystore/credentials ever committed, CI secrets only reachable from
      maintainer-pushed v* tags) - 2026-10-15
- [ ] Publish `local` + `sync` artifacts together - GitHub release (decided:
      sync is in 1.0, so Phase 5 completes first)

## Phase 5 - sync (ADR-007, 12-api-contract.md)

- [x] **Server repo:** /api/v1 token auth + account-page token management - 2026-06-12
- [x] **Server repo:** library endpoints, sidecar-honoring; .aac/.m4a first-class
      across the web app - 2026-06-12
- [x] App sync source set: stdlib-only API client, Connect screen,
      EncryptedSharedPreferences token - 2026-06-12
- [x] Pull/download/push/rename-LWW per the contract - 2026-06-12
- [x] Sync-flavor CI guard (INTERNET only) - shipped with Phase 4's flavor split
- [x] E2E both directions against the LIVE server (emulator, throwaway account,
      cleaned up): connect → sync (1 new) → download → playing; narrate →
      upload → server sidecars + audit line - 2026-06-12

## Phase 3 addenda

- [x] fp32 A/B: ear-verdict **fp32 wins** (the int8 "ring" is gone). v0.4.x
      open decision: make fp32 the default if it stays >=1x RTF on-device.
- [x] Read-along: the Player screen shows the full source text (scrollable),
      readable while the oration is still synthesizing (v0.4.1).

- [x] In-app Write/paste entry (v0.3.2). Planning note: PRD S1 had it from the
      start and the slice deferred it, but Phase 3's checklist never picked it
      up - Jacob's request caught the dropped thread. Lesson: when a slice
      defers an item, file it into a named phase immediately.

## Open questions

- [x] ~~Ship `sync` flavor at 1.0 or as 1.1?~~ **Decided 2026-06-12: sync ships
      at 1.0.** Phase 5 server work therefore precedes the 1.0 release; both
      artifacts (`local`, `sync`) publish together.
- [ ] Final display name (Concept Brief candidates)
- [ ] F-Droid: pursue at 1.0 or later? Model-asset policy question
      (32-compliance-checklist.md)
- [ ] Playhead-position sync (v1.1 candidate, needs API extension)
