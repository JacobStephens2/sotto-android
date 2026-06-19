# Design System / UI Style Guide - sotto-android

## Stance

**An illuminated manuscript on a phone.** Sotto transcribes a text and reads it
aloud - the work of the late-medieval monastic scriptorium - so the design
borrows that world: vellum and iron-gall ink, lapis and gold and rubric red,
a blackletter title and a humanist book hand. It is *not* stock Material 3
(that was the v1 stance, now superseded). It is still built entirely on M3
components and tokens - the illumination is a theme over them, never a fight
against them - and readability still wins every contest, because Sotto is a
reading app first. Illumination is used as it was historically: a few gilded
marks on an otherwise plain, legible page.

The shared identity with the web app is the name, the boundary story, the
voices, and now the palette lineage (the web navy descends to the manuscript
lapis); not pixels.

## Foundations

- **Color** (`shell/ui/Theme.kt`). A fixed brand palette - deliberately *not*
  Material You dynamic color, because the manuscript should look the same on
  every device. The Books-of-Hours triad:
  - **primary** lapis-lazuli ultramarine `#2A3F8F` - the costliest medieval
    pigment, Marian blue. (Descends from the web brand navy `#064b87`.)
  - **secondary** gold leaf `#8A6A12` - illumination itself; chips, accents.
  - **tertiary** minium / vermilion rubric red `#A12C22` - the red of initials
    and headings (*rubric* < Latin *ruber*). Kept distinct from `error`.
  - **surface/background** vellum `#F3E9CF / #F8EFD7`; **onSurface** iron-gall
    ink `#2A2014`; outline a sepia rule.
  - **Dark** = the same page by candlelight: inked-sepia ground `#1C1710`, with
    gold and lapis glowing (`primary #AEC2F5`, `secondary #E6C66A`).
- **Typography** (`Theme.kt`, fonts in `res/font`, both SIL OFL):
  - **EB Garamond** (humanist serif) for display / headline / title roles - the
    printed-book voice.
  - **UnifrakturMaguntia** (blackletter) for the **Sotto wordmark only**
    (`SottoWordmark`); too dense for anything longer.
  - **Body and labels stay the system sans** - on-screen prose legibility,
    including the read-along source text, is non-negotiable.
- **Shape** (`SottoShapes`): squared corners (2-8dp) - a ruled page and its
  initial-boxes are rectilinear, not pill-rounded.
- **Spacing:** 4dp grid; standard Material padding; custom values carry a reason.
- **Iconography:** Material Symbols. App icon: the web favicon's speaker-on-navy,
  a candidate to redraw toward a versal initial later.

## Illuminated components (`shell/ui/Illumination.kt`)

Custom, used sparingly:

- **SottoWordmark** - the title in blackletter (the one place that face appears:
  the Library top bar, About).
- **Versal** - an illuminated drop-cap: a gold-bordered lapis box with a serif
  initial, the way a manuscript text opens. Used on the empty Library state.
- **FleuronRule** - a hairline gold rule centered on a fleuron (❧), the scribe's
  section break.

Everything else is stock M3 (TopAppBar, ListItem, FilterChip, LinearProgress,
Slider, AlertDialog, ModalBottomSheet, Switch), inheriting the theme.

## The player screen

The one screen worth real design effort (it's the product). Big play/pause,
15-second skip pair (the web app's `« 15s / 15s »` convention), elapsed/total,
speed chip, sleep-timer chip, chunk-progress line during narration, and the
read-along source panel. Controls mirror the MediaSession notification
one-for-one - no capability in one place and not the other.

## Accessibility (release criteria, not aspirations)

- Every transport control has a contentDescription; TalkBack order follows
  visual order; the progress line announces "narrating, n of N chunks."
- Touch targets ≥ 48dp; text respects system font scale to 200% without
  truncation; contrast per M3 defaults (don't override token pairs). The
  manuscript palette was chosen to keep onColor/onContainer contrast within M3
  guidance - verify any new pairing, don't eyeball it.
- Playback state changes are announced via MediaSession metadata, not toasts.

## Writing in the UI

Follows the server's wording rule: **narrating/oration** in user-facing text,
**synthesized/Kokoro-82M** in the About screen's provenance section. Sentence
case everywhere, no exclamation marks, no "please." Empty states say what to do
("Share text to Sotto from any app, or tap Write to paste it here."), errors say
what happened and what survives ("Storage full - narration stopped after chunk
41. Everything narrated so far is saved."). The scriptorium theme is carried by
type and color, not by archaic copy - the words stay plain and modern.

## Open / next

- A bundled blackletter is in for the wordmark; a true gilded **drop-cap on each
  oration's first letter** and **foliate margin rules** are the next illumination.
- App-icon redraw toward a versal initial.
- The web app could inherit the same palette/type for cross-surface continuity
  (currently only the navy↔lapis lineage is shared).
