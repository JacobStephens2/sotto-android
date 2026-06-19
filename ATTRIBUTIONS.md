# Attributions

Sotto is GPL-3.0 (see LICENSE) and stands on:

- **Kokoro-82M** - the voice. Apache-2.0, trained on documented public-domain
  and permissively licensed audio. Weights packaged by the sherpa-onnx project
  (kokoro-multi-lang-v1_0, int8).
- **sherpa-onnx** (k2-fsa) - on-device inference runtime and Kotlin API.
  Apache-2.0.
- **espeak-ng** - grapheme-to-phoneme data and code, bundled inside
  sherpa-onnx. **GPL-3.0** - the reason Sotto itself is GPL-3.0: the combined
  work distributes espeak-ng, so copyleft applies to the whole, and we prefer
  saying so plainly to engineering around it.
- **ONNX Runtime** (Microsoft) - MIT.
- **AndroidX / Jetpack Compose / Media3** - Apache-2.0.
- **EB Garamond** (Octavio Pardo and the EB Garamond Project Authors) - the
  headline serif. SIL Open Font License 1.1 (`licenses/OFL-EBGaramond.txt`).
- **UnifrakturMaguntia** (j. 'mach' wust) - the blackletter *Sotto* wordmark.
  SIL Open Font License 1.1, Reserved Font Name "UnifrakturMaguntia"
  (`licenses/OFL-UnifrakturMaguntia.txt`).

The seven voice previews bundled in the app were synthesized with Kokoro-82M.
