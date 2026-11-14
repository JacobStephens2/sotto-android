# sotto-android doc set

The planning documents for the Phase 4 product from `docs/android-app/research`: a
plain Kotlin/Compose Android app that narrates documents entirely on-device with
Kokoro-82M. Two flavors (ADR-007): `local` - nothing leaves the phone, and the
manifest proves it - and `sync`, which adds opt-in connection to a sotto server
account so one library is usable from the phone and a desktop browser.

## Reading order

Read [the domain glossary](../GLOSSARY.md) for Oration, chunk, and Narration
worker before changing narration behavior. The documents below define the
rules; the code reading index follows the ordinary narration path.

| # | Document | Role |
|---|---|---|
| 1 | [01-concept-brief.md](01-concept-brief.md) | North star - what this is and is not |
| 2 | [02-prd.md](02-prd.md) | What must happen, acceptance criteria |
| 3 | [03-roadmap.md](03-roadmap.md) | Build order, gates |
| 4 | [10-architecture-overview.md](10-architecture-overview.md) | Technical blueprint |
| 5 | [11-data-model.md](11-data-model.md) | Entities, state machine, invariants |
| 6 | [12-api-contract.md](12-api-contract.md) | Sync API between the app's `sync` flavor and the sotto server |
| 7 | [adr/](adr/) | One file per forever-decision |
| 8 | [20-platform-strategy.md](20-platform-strategy.md) | Android-only scope, capability matrix vs the web app |
| 9 | [21-vertical-slice-plan.md](21-vertical-slice-plan.md) | The first complete path through the architecture |
| 10 | [30-testing-plan.md](30-testing-plan.md) | Test pyramid, device matrix, merge criteria |
| 11 | [31-release-cicd-plan.md](31-release-cicd-plan.md) | Signing, CI, GitHub-release distribution |
| 12 | [32-compliance-checklist.md](32-compliance-checklist.md) | Store/AI-disclosure items, mostly deferred with reasons |
| 13 | [40-code-style.md](40-code-style.md) | Kotlin conventions, module boundaries |
| 14 | [41-design-system.md](41-design-system.md) | UI rules |
| 15 | [constitution.md](constitution.md) | Executable constraints for agent-assisted work |
| 16 | [tasks.md](tasks.md) | Current task list (living) |
| 17 | [CLAUDE.md](CLAUDE.md) | Agent context file, to be copied to the app repo root |
| 18 | [_state.md](_state.md) | Session-to-session handoff state (living) |

## Code reading index

Paths are relative to `app/src/main/kotlin/page/stephens/sotto/`.

| Concern | Start here |
|---|---|
| Ordinary admission, Stop, deletion, attachment, and retained ownership | `engine/narrate/NarrationCoordinator.kt` |
| Shared ordinary/gate source, lifecycle, chunk metadata, sink closure, and outcomes | `engine/narrate/OrationNarration.kt` |
| Durable chunk execution and resume | `engine/narrate/NarrationEngine.kt` |
| Oration rows, queue order, recovery, and migration | `engine/store/Db.kt` |
| Audio append, truncate, and fsync | `engine/store/FileAudioSink.kt` |
| Manual process-wide wiring and managed production model | `shell/service/NarrationCoordinatorProvider.kt` |
| Android media owner, listen-now, governor, and sleep timer | `shell/service/NarrationService.kt` |
| Non-exported in-app command routing | `shell/service/InAppNarrationService.kt` |
| Typed Lab requests and measurements | `engine/narrate/Diagnostics.kt`, `engine/narrate/DiagnosticExecution.kt`, `spike/MainActivity.kt` |

For ownership changes, read [ADR-009](adr/ADR-009-retained-narration-ownership.md)
and the interface tests before following private control or execution details.
Issues #15 and #16 cover ordinary and diagnostic callers through the same
retained worker. Lab owns neither a model nor an inference executor.
Issue #22 consolidates their durable execution behind an internal operation;
`DiagnosticExecution` keeps gate presentation and measurement. Read the
architecture overview for its injected-clock policy and the attached-command
contract tests before following its persistence implementation.

## Deliberately skipped

- **Spec-kit `requirements.md` / `plan.md` / `validation.md`** - they would
  duplicate the PRD, Roadmap, and Testing Plan one-to-one. `constitution.md` and
  `tasks.md` are kept because they do distinct work (constraints; live state).
- **Documentation style guide** - exists already:
  `/var/www/Android2/Writing_Style_Guide_Unified.md` governs all prose here.
  ADR format is defined in `adr/README.md`.

These documents version with the code they govern: when the app repo is created,
this directory moves into it and changes land in the same commits as the
implementation they describe.
