# Tasks - sotto-android (living)

Status: planning complete, awaiting SPIKE-001. Updated 2026-06-12.

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

- [x] Library long-press sheet: rename / delete (service-owned, real deletion) /
      source view / export via SAF - 2026-06-12
- [x] Player screen over MediaController: seek slider, 15s skips, speed chips
      (0.8-2x), sleep timer (15/30/60, service-side), catching-up state - 2026-06-12
- [x] Voice picker w/ bundled previews + AUTHORITATIVE sid map (from sherpa's
      generate_voices_bin.py - af_heart=3; early builds spoke af_alloy=0);
      calibrated estimate (median of completed orations) - 2026-06-12
- [x] About: provenance + no-network claim + on-device activity log - 2026-06-12
- [ ] PRD edge cases: storage-full, thermal catch-up, queueing, encoding

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
