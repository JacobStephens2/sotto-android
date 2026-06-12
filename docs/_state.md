# _state.md - sotto-android session handoff

Phase: **Phase 1 nearly complete (2026-06-12): TextPipeline, NarrationEngine, Room store, FileAudioSink, SherpaSynthesizer wiring, instrumented resume test, CI green.** Phase 1 gate harness shipped in spike-003: 'Phase 1 gate' button narrates the bundled encyclical through the real stack (Sherpa -> ADTS -> Room -> sink); 'Kill process' + re-tap proves resume on hardware. Awaiting Jacob's run. Then Phase 2 (vertical slice).
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
