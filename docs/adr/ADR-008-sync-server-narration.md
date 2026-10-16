# ADR-008: The `sync` flavor may ask the user's server to narrate an oration (OpenAI voices), opt-in per oration

Status: accepted (recorded retroactively; the feature shipped 2026-06-19 in
13abaf5 without this ADR, contrary to constitution rule 13)
Date: 2026-10-15

## Decision

When a `sync` build is connected to an account the server allows onto its paid
OpenAI backend (`/api/v1/me` reports `may_openai`), the New oration screen
offers a "Narrate on the server (OpenAI)" switch. Switched on, that one
oration's text is sent to the user's server (`POST /api/v1/narrate`), which
narrates it with OpenAI's TTS and saves it to the account's library; the app
polls the job and syncs the result down like any server entry.

This amends ADR-007, which said the `sync` flavor "never adds hosted TTS ... or
any third party". It still talks only to the server the user named; the
third party (OpenAI) sits behind that server, reached only by the user's
explicit per-oration choice.

What does not change: the switch defaults off on every oration; it never
appears in `local` builds or for accounts without `may_openai`; on-device
Kokoro narration remains the default and needs no network; the app holds no
API key (the server does - constitution boundary 2 holds).

## Context

The owner wanted the OpenAI voices, which only the server can run (a key on
the phone would break boundary 2, and the model is not on-device). The server
already narrates with OpenAI for allowed web accounts, so the phone reuses that
path rather than growing a new one. The cost is the `sync` build's
"narrates on-device" claim, which becomes "narrates on-device unless you
choose server narration for an oration".

## Consequences (incl. what this rules out)

- The `local` flavor and its no-network claim are untouched (ADR-004).
- `sync`'s privacy statements must name the path: PRIVACY.md, the README
  flavor table, and the About screen were corrected 2026-10-15 (they had kept
  saying text is never sent to be synthesized).
- A Play listing of `sync` would have to declare user content shared with a
  third party (32-compliance-checklist.md [Play] Data Safety item).
- Rules out: server narration as a default, automatic fallback to the server
  (e.g. when the device is slow or hot), or any hosted TTS called directly
  from the app. Any of those needs a new ADR.
- Constitution boundary 1 holds: until an account is connected, `sync`
  behaves exactly like `local`.
