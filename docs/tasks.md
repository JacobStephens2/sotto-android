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

- [ ] Share-intent → Confirm → narrate-while-listening → library row
- [ ] NarrationService + MediaSession + single notification
- [ ] Playhead governor ("catching up")
- [x] ci.yml live and green (tests, assemble, zero-permission manifest guard,
      third-party cache) - 2026-06-12. Remaining: release.yml dry run
- [ ] One week of daily real use - exit gate

## Phase 3 - v1 surface

- [ ] Library detail / rename / delete / source view
- [ ] Speed, sleep timer, position memory polish
- [ ] Voice picker with previews; estimator calibration
- [ ] About: provenance text, no-network claim + verification command, job log
- [ ] PRD edge cases: storage-full, thermal catch-up, queueing, encoding

## Phase 4 - release 1.0

- [ ] Keystore (strong password), CI secrets, signed release pipeline
- [ ] README with boundary story + aapt verification; privacy page
- [ ] License attributions incl. espeak-ng GPL linkage review
      (32-compliance-checklist.md)
- [ ] Publish `local` + `sync` artifacts together - GitHub release (decided:
      sync is in 1.0, so Phase 5 completes first)

## Phase 5 - sync (ADR-007, 12-api-contract.md)

- [ ] **Server repo:** /api/v1 token auth (hashed tokens in
      state/api_tokens.json, account-page management, rate limit, audit lines)
- [ ] **Server repo:** library list/download/upload/rename/delete endpoints
      honoring existing sidecars
- [ ] App `sync` source set: API client, Connect-account screen, token in
      Keystore-backed storage
- [ ] Pull (SERVER_ONLY rows, download on demand), push (upload local-only),
      rename LWW; per 12-api-contract.md semantics
- [ ] Sync-flavor CI guard (INTERNET only; dependency allowlist)
- [ ] End-to-end: narrate on phone → upload → play in desktop browser; narrate
      on web → download → play offline on phone

## Open questions

- [x] ~~Ship `sync` flavor at 1.0 or as 1.1?~~ **Decided 2026-06-12: sync ships
      at 1.0.** Phase 5 server work therefore precedes the 1.0 release; both
      artifacts (`local`, `sync`) publish together.
- [ ] Final display name (Concept Brief candidates)
- [ ] F-Droid: pursue at 1.0 or later? Model-asset policy question
      (32-compliance-checklist.md)
- [ ] Playhead-position sync (v1.1 candidate, needs API extension)
