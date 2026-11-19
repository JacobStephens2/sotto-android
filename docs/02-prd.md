# PRD - sotto for Android v1

What the app must do. Implementation choices live in the architecture doc and
ADRs; build order lives in the roadmap.

## Roles

One role: the device owner. No accounts, no admin, no multi-user (the device's
own user separation is the boundary).

## User stories and acceptance criteria

### S1 - Share text to narrate
*As a reader, I share selected text from any app and hear it read.*

- The app appears in the Android share sheet for `text/plain` and text files
  (`.md`, `.markdown`, `.txt`).
- Sharing opens a confirmation screen: detected title (first markdown heading,
  else first line, per the server's `convert()` rule), editable name field,
  voice picker (last-used preselected), Narrate button.
- Tapping Narrate starts playback within 10 seconds on a Pixel-6-class device
  for any document size (streaming synthesis, ADR-003).
- Markdown is cleaned for the ear with the same rules as the server's
  `clean_markdown()` (links dropped, `§102` → "section 102", tables flattened,
  headings get pauses) - ported, with the port verified against the Python
  implementation's test vectors (30-testing-plan.md).

### S2 - Listen like a podcast
*As a listener, I control playback without unlocking the phone.*

Oration playback orders explicit choices when accepted, even if the latest
choice has unavailable audio; unavailable audio keeps existing media intact.
Listen-now starts saved durable audio only without a selected Oration or pending
explicit choice. Catch-up reloads only after durable audio grows, including the
final saved tail after narration finishes, retaining current position, speed,
and pause intent. A seek away from the frontier wins over a pending refresh.
Deletion clears matching playback before removal and fences stale reads/writes;
a recreated gate remains playable. Sleep replacement/cancel and owner close
fence pending deadlines; an accepted pause is logged locally. Listening positions
retain identity and FIFO ordering across owner replacement without changing
sync modification timestamps, and deleting an Oration or resetting the Lab gate
clears cached positions even when no playback owner is attached or after it
closes. Returning to an Oration or replacing its owner restores the latest accepted
position even while its storage write is pending. Before a playback session loads,
the Player screen displays the resumed position and saved duration (or 64 kbps
byte estimate for durable local audio) rather than `0:00 / 0:00`. Transport
mutations (seek, 15s skip, speed, active pause) affect only the matching loaded
Oration, while tapping Play on a non-loaded Oration starts that Oration only if
local audio is available. Sleep-timer chips stay synchronized with MediaSession
session extras across connect, changes, expiration, and disconnect; timeline
ticks only while loaded and playing, and read-along text updates idempotently
without recomposing on timeline ticks or losing scroll/text state. Missing rows
render `"Oration no longer exists"` with a Back action cleanly.

- Media3 session: lock-screen and notification controls, headphone buttons,
  15-second skip increments (`15,000 ms`) in both directions, sleep-timer extras,
  and play/pause.
- Position is remembered per oration and restored on reopen (parity with the
  web app's sessionStorage behavior, but durable).
- Playback continues with the screen off and survives Doze, because synthesis
  and playback run in a foreground media service (ADR-003).
- Playback speed control: 0.8x-2.0x.
- Sleep timer: 15/30/60 minutes.

### S3 - Library
*As a returning user, I find every narration I've made.*

- Library list: name, audio length, word count, created date, narration progress
  if incomplete.
- Entry detail: play, rename, delete, view source text, "narrated in X" metadata.
- Naming mirrors the web app: optional user name, else derived from the heading;
  stored verbatim (the `.title` sidecar concept, as a Room column).
- Storage is app-private. Export: a per-entry "Save MP3 to..." action via the
  system file picker (SAF) - the user moves a file out explicitly; the app never
  publishes anything (constitution.md, boundary 4).

### S4 - Watch and control narration
*As a user, I see progress and can stop a narration.*

- In-progress entries show chunk progress (n / total) and elapsed time, in the
  library and on the player screen.
- Stop ends synthesis after the current chunk and keeps the audio synthesized so
  far, marked "stopped at n of N" (unlike the server, which deletes partials -
  on-device the partial is the user's property).
- Narration resumes from the last completed chunk after process death or reboot
  (parity with the server's resume_jobs design).

### S5 - Honest about itself
*As anyone, I can see what this app is and is not doing.*

- About screen states: the voice is synthetic, produced on-device by
  Kokoro-82M (Apache-2.0, trained on documented public-domain and permissively
  licensed audio); the app holds no INTERNET permission; nothing leaves the
  device.
- A local job log (time, title, outcome) is viewable in-app - the traceability
  boundary, kept local.

### S6 - One library, phone and desktop (`sync` flavor only)
*As the owner of a sotto server account, I work with the same library from my
phone and my desktop browser.*

- Settings → Connect account: server URL (default `sotto.stephens.page`),
  email, password → token stored in Keystore-backed encrypted storage; the
  password is never persisted. Disconnect wipes the token.
- Library gains a server section: entries on the server but not the device
  appear with metadata and a download action; "download all" exists.
- Local orations (COMPLETE or STOPPED) offer "upload to account"; uploaded
  entries become visible and playable in the web Library, indistinguishable
  from web-saved ones (the server writes the same sidecars).
- Renames sync last-write-wins; deletes never propagate automatically
  (12-api-contract.md sync semantics).
- Until an account is connected, the `sync` build behaves identically to
  `local` (constitution boundary 1).
- Sync failures are non-blocking: narration and playback never wait on the
  network.

## Functional requirements

- F1: Accept `ACTION_SEND` (text), `ACTION_SEND` (stream for text files), and
  `ACTION_VIEW`/`ACTION_OPEN_DOCUMENT` for `.md`/`.txt`.
- F2: Document intake accepts complete text after Kotlin whitespace trimming,
  up to 409,600 UTF-8 bytes (400 KB). Files decode malformed UTF-8 with
  replacement and remove one initial BOM; direct text preserves its BOM.
  Arbitrarily long surrounding whitespace is allowed; interior whitespace
  counts toward the limit. Never accept a truncated prefix. Shared text takes
  precedence over a shared stream. Opening, reading, validation, and closure
  run off-main behind one validated operation, with bounded retained stream
  text. Initial reads show a reading state; Back or leaving intake fences late
  completion. Write prevents duplicate Continue and retains rejected text.
  Empty, unreadable, and over-limit results explain the failure; the latter
  names the 400 KB limit without claiming a partial byte count is the file size.
  Restored completed screens do not repeat intake or its messages; an unfinished
  initial read restarts in the new Activity. Intake creates no Oration, log, or
  narration command; publication retains synchronous defensive validation.
- F3: Chunking at sentence boundaries, ~1,000 chars per chunk (server's
  `KOKORO_CHUNK_LIMIT`), implemented as the ported `chunk()`.
- F4: Synthesis via sherpa-onnx running Kokoro-82M int8 (ADR-002); the 7 curated
  voices from the server's `KOKORO_VOICES` list, with preview samples.
- F5: Output stored as one growing audio file per oration plus a source-text
  sidecar and metadata row (11-data-model.md).
- F6: All long work in a single foreground service with a progress notification.
- F7: Estimated narration time shown before starting, from a device-calibrated
  seconds-per-word rate (median of past runs; the server's `calibrated_rates`
  logic, local).
- F8 (`sync` flavor): account connect/disconnect, server-library browse,
  per-entry and bulk download, per-entry upload, rename propagation - per
  12-api-contract.md.

## Non-functional requirements

- N1: **No INTERNET permission in the `local` flavor.** Its manifest must never
  acquire it; CI fails the build if it appears. The `sync` flavor holds INTERNET
  and nothing else beyond `local`'s permissions (constitution.md, ADR-007).
- N2: Time-to-first-audio ≤ 10 s for arbitrary document length (Pixel-6-class).
- N3: Sustained synthesis-while-playing must not drop playback or overheat to
  throttling shutdown on a 60-minute listen; battery drain target ≤ 15%/hour of
  combined synthesis+playback on a mid-range device (validated by SPIKE-001
  before these numbers are treated as commitments).
- N4: APK ≤ 250 MB including bundled int8 model (ADR-005).
- N5: minSdk 29, target latest stable; arm64-v8a only (ADR-005).
- N6: Process death at any moment loses at most the chunk in flight.

## Edge cases

- Shared text is empty or whitespace → reject with message, no entry created.
- Storage full mid-narration → stop gracefully, keep partial, surface the cause.
- Two shares while one narration runs → queue them (single worker; the queue is
  visible in the library as "queued" entries).
- Battery saver / thermal throttling slows synthesis below 1x realtime →
  playback pauses at the synthesis frontier with a "catching up" indicator
  rather than skipping or erroring.
- File with non-UTF-8 encoding → decode with replacement, never crash.
