# Sotto for Android

Share any text to your phone and listen to it as a private narration - no
account, no server, no network. The voice is Kokoro-82M, running entirely on
the device; *sotto voce* is the idea: a narrator that speaks only to you.

Sibling of the Sotto web service (https://github.com/JacobStephens2/sotto):
same text pipeline (held equivalent by shared test vectors), same voices,
opposite trust model - the web service asks you to trust a server; this app
removes the question.

## The claim, and how to check it

The `local` build's manifest does not contain the INTERNET permission. The
operating system itself will refuse it a socket. Verify any release APK:

    aapt dump permissions sotto-local-<version>.apk

Every release's notes include that command's output. There is no analytics,
no crash reporting, no update check - nothing leaves the phone. PRIVACY.md is
short because there is nothing to disclose.

Two artifacts per release (see `docs/adr/ADR-007`):

| | `sotto-local` | `sotto-sync` |
|---|---|---|
| Narration | on-device | on-device |
| Network | none - no INTERNET permission | your own Sotto server, opt-in, library sync only |

## What it does

- **Share-sheet first:** select text anywhere → Share → Sotto → it narrates,
  playing within seconds while the rest synthesizes just ahead of you.
- A real audio app: lock-screen and headphone controls, 15-second skips,
  playback speed, sleep timer, position memory per oration.
- A local library: rename, read the source text, export the audio where you
  choose, delete for real. Narrations survive process death and resume from
  the last finished chunk - byte-identically (it's tested).
- Seven voices with previews; time estimates calibrated from your own
  device's measured rate.
- An activity log you can read in-app. It never leaves the device.

## Install

Releases ship as APKs on this repo's releases page (sideload; enable
unknown-source installs). minSdk 29, arm64 phones. ~250 MB - the entire voice
is inside.

## Build

    tools/fetch-third-party.sh        # sherpa-onnx AAR + Kokoro int8 weights
    ./gradlew assembleLocalDebug

Docs live in `docs/` - start at `docs/README.md`; `docs/constitution.md` is
the binding set of constraints, each with its enforcement named. The text
pipeline's reference implementation is the server repo; `pipeline-vectors.json`
is the contract.

## License

GPL-3.0. The bundled espeak-ng (inside sherpa-onnx) is GPL-3.0, so the
combined work is copyleft regardless - we prefer saying so plainly. Full
component list in ATTRIBUTIONS.md.
