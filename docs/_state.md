# _state.md - sotto-android session handoff

Phase: **Phase 1 nearly complete (2026-06-12): TextPipeline, NarrationEngine, Room store, FileAudioSink, SherpaSynthesizer wiring, instrumented resume test, CI green.** Phase 4 release machinery BUILT 2026-06-12: local/sync flavors real (ADR-007; sync currently = local + INTERNET, Phase 5 fills it), signed release pipeline (release.yml on v* tags; notes embed the aapt proof + checksums), GPL-3.0 license decided (espeak-ng linkage), README/PRIVACY/ATTRIBUTIONS, adaptive icon, keystore w/ SOPS-encrypted password backup. 1.0 publish still gated on Phase 5 sync (decided: sync ships at 1.0).

Phase 3 surface BUILT 2026-06-12 (0.3.0): voice picker w/ previews + corrected authoritative sid map (af_heart=3 per sherpa generate_voices_bin.py; prior builds spoke af_alloy), Player screen (MediaController: seek/skips/speed/sleep timer/catching-up), library long-press sheet (rename/delete/source/SAF export), About w/ provenance + job log, FIFO queueing + auto-resume, calibrated estimate. Emulator-verified by uiautomator. Remaining Phase 3: device validation of edge cases; int8-vs-fp32 A/B; 2T/3T battery sweep. PRD acceptance pass on hardware = the Phase 3 gate.

Phase 2 vertical slice BUILT 2026-06-12 (0.2.0-slice): Compose SottoActivity (share target -> Confirm -> Library w/ live Flow), NarrationService (one Media3 service owning player + narration; ADTS constant-bitrate seeking; listen-now autoplay; playhead governor; requeue-on-create recovery). applicationId is now page.stephens.sotto (forever id). Emulator end-to-end verified by state: share intent -> derived title -> narrate -> file growth -> MediaSession metadata -> COMPLETE library row. Remaining slice gate: a week of Jacob's real daily use. Voice feedback: af_heart mapping CONFIRMED warm; int8 ring persists (fp32 A/B still open).

Phase 1 gate: **kill/resume PROVEN on hardware 2026-06-12** - Jacob ran the gate to chunk 8/324, killed the process, re-tapped: 'resuming from chunk 8' through the full real stack (Room + truncate + Kokoro + ADTS). 324 chunks matches the server's count for the same document (pipeline parity in the wild). Remaining: let the gate run to GATE COMPLETE (~4-5 h synthesis; resumable in any number of sittings - every kill/charge break just resumes). Then Phase 2 (vertical slice).
Updated: 2026-06-12

## Decisions snapshot

- Plain Kotlin/Compose, Android-only (ADR-001)
- sherpa-onnx + Kokoro-82M int8, on-device, 7 server-parity voices (ADR-002)
- Streaming chunk-ahead-of-playhead in one MediaSessionService (ADR-003)
- `local` flavor = no INTERNET, flagship claim (ADR-004); `sync` flavor =
  opt-in sotto-server account/library sync (ADR-007, contract in
  12-api-contract.md)
- Model bundled in APK; arm64-v8a; minSdk 29 (ADR-005)
- GitHub-release sideload first; Play/F-Droid deferred decisions (ADR-006)

## Measurements (authoritative; update from device runs only)

| Metric | Value | Device | Date |
|---|---|---|---|
| RTF (run-once, 8 chunks, 4 threads) | **1.22x** synth basis | Jacob's arm64 phone | 2026-06-12 |
| RTF sustained 30.7 min | **1.06x** (decay 1.22→1.17→1.11→1.07→1.06; final inst 1.01x; soft throttle, thermal flag never raised) | Jacob's arm64 phone, spike-002 run 3, suspended=0s, deadline reached | 2026-06-12 |
| Battery %/hr (pure synthesis, 4T) | **19.5%/hr** (Δ10% over 30.7 min) | same (sustained run) | 2026-06-12 |
| Thermal | NONE throughout run-once | same | 2026-06-12 |
| Peak PSS | 1,080 MB (run-once) / 793 MB (sustained) | same | 2026-06-12 |
| Model load | 1.0-1.2s | same | 2026-06-12 |
| APK size (spike, debug, model bundled) | 203 MB | - | 2026-06-12 |

**GATE VERDICT (2026-06-12): PASS - proceed to Phase 1.** Sustained RTF 1.06x ≥ 1.0x
with no thermal shutdown (roadmap Phase 0 gate). Below the 1.3x comfort target at
hot steady state (final instantaneous 1.01x), so two design consequences are
binding: (1) the playhead governor must build buffer aggressively in the cold
early minutes; (2) synthesis-while-listening has thin headroom on this device -
the buffer, not raw speed, is the safety margin.

Open items carried into Phase 1:
- int8 sounds "a bit tinny / ringy" (Jacob, on-device) - A/B vs fp32 before
  confirming ADR-002's int8 choice.
- Optional: 30-min sweep at threads=2/3 for the battery/throttle tradeoff
  (19.5%/hr at 4T exceeds the PRD's ≤15%/hr synth+play ambition).
- Audio container DECIDED 2026-06-12: ADTS AAC (AdtsAacEncoder; appendable +
  truncatable at chunk boundaries + deterministic, preserving byte-identical
  resume; verified on the Android runtime in RealPipelineTest).

Reference points from the server (not phone numbers): VPS 4-core EPYC ≈ 1.4x
RTF, 0.26 s/word; council research cites puff-dayo/Kokoro-82M-Android as
precedent for int8 on-device.

## Next actions

1. Create `sotto-android` repo; copy doc-set in; license decision
2. SPIKE-001 per tasks.md; fill the table above; record gate verdict here
3. If gate passes → Phase 1 (first item is the server-side vector generator)

## Open questions (mirror of tasks.md)

final display name · F-Droid timing · playhead sync
(Decided: sync ships at 1.0 - 2026-06-12.)

## Known constraints from the host environment

- VPS disk is tight for Android builds; prefer GitHub-hosted runners or a local
  machine (the Cascade build-output-on-volume lesson).
- Server-side sync API (Phase 5) touches /var/www/sotto.stephens.page/app.py -
  deploys must respect the no-restart-while-narrating rule (check jobs/*.json
  status first; resume exists but don't lean on it).
