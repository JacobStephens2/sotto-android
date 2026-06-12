# _state.md - sotto-android session handoff

Phase: **planning complete; Phase 0 (SPIKE-001) not started**
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
| RTF sustained 30 min | pending (spike-001 sustained run invalid: state race + device suspend; rerun on spike-002) | - | - |
| Battery %/hr (pure synthesis, 4T) | ~30%/hr (interim, from 5.9-min run-once) | same | 2026-06-12 |
| Thermal | NONE throughout run-once | same | 2026-06-12 |
| Peak PSS | 1,080 MB | same | 2026-06-12 |
| Model load | 1.0-1.2s | same | 2026-06-12 |
| APK size (spike, debug, model bundled) | 203 MB | - | 2026-06-12 |

Interim verdict: run-once **passes the 1.0x gate** (below the 1.3x comfort target).
Open quality flag: int8 output sounds "a bit tinny / ringy" (Jacob, on-device) -
A/B against the fp32 model before confirming ADR-002's int8 choice.
Spike-002 adds: overlap-proof runs, suspend-aware clocks, thread sweep (2/3/4).

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
