# Licenses and third-party notices

This module contains **no third-party source code, no third-party models, no third-party textures
and no third-party audio samples**. Everything it draws is generated procedurally at runtime, and the
one audio file it ships is synthesised for this repository by a script in `tools/`.

Everything below is either a *runtime dependency* you will pull through Gradle, or a
*development-time tool* that is not part of the app.

---

## 1. Runtime dependencies (in the APK)

| Dependency | Version | License | Why it is here |
| --- | --- | --- | --- |
| Kotlin standard library | 2.0.21 | Apache-2.0 | the language runtime |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core` / `-android` | 1.9.0 | Apache-2.0 | the controller's ticker and staging work |
| `androidx.core:core-ktx` | 1.15.0 | Apache-2.0 | Android KTX helpers |
| `androidx.activity:activity-compose` | 1.9.3 | Apache-2.0 | `ComponentActivity` + `setContent` |
| `androidx.lifecycle:lifecycle-runtime-ktx` | 2.8.7 | Apache-2.0 | lifecycle-aware collection |
| `androidx.lifecycle:lifecycle-runtime-compose` | 2.8.7 | Apache-2.0 | `collectAsStateWithLifecycle` |
| `androidx.lifecycle:lifecycle-viewmodel-compose` | 2.8.7 | Apache-2.0 | retaining the player across rotation |
| `androidx.compose:compose-bom` + `ui`, `ui-graphics`, `material3`, `ui-tooling-preview` | BOM 2024.10.01 | Apache-2.0 | the UI layer |
| `androidx.media3:media3-exoplayer` | 1.5.1 | Apache-2.0 | local audio playback |
| `androidx.media3:media3-common` | 1.5.1 | Apache-2.0 | Media3 shared types (`C`, `PlaybackException`) |

AndroidX and Media3 are licensed under the Apache License 2.0 and are consumed as published artifacts
from Google's Maven repository; their own `NOTICE` files apply where present.

## 2. Test-only dependencies (not in the APK)

| Dependency | Version | License |
| --- | --- | --- |
| JUnit 4 | 4.13.2 | Eclipse Public License 1.0 |
| `androidx.test.ext:junit` | 1.2.1 | Apache-2.0 |
| `androidx.test:core-ktx` | 1.6.1 | Apache-2.0 |
| `androidx.test.espresso:espresso-core` | 3.6.1 | Apache-2.0 |
| `androidx.compose.ui:ui-test-junit4` | BOM 2024.10.01 | Apache-2.0 |
| `compose ui-test-manifest` (debug only) | BOM 2024.10.01 | Apache-2.0 |

## 3. Build tooling

| Tool | Version | License | Shipped? |
| --- | --- | --- | --- |
| Gradle wrapper | 8.9 | Apache-2.0 | no (build only) |
| Android Gradle Plugin | 8.7.3 | Apache-2.0 | no |
| Kotlin Gradle plugin + Compose compiler plugin | 2.0.21 | Apache-2.0 | no |

## 4. Development-time tools (never part of the app or the build)

`tools/verify_static.py` and `tools/generate_demo_audio.py` are plain Python scripts kept for
reproducibility. They are not invoked by Gradle and nothing they need ends up in the APK.

| Tool | Version | License | Used for |
| --- | --- | --- | --- |
| tree-sitter (Python) | 0.26 | MIT | parsing Kotlin, GLSL and XML in `verify_static.py` |
| tree-sitter-kotlin | 1.1.0 | MIT | Kotlin grammar |
| tree-sitter-glsl | 0.2.0 | MIT | GLSL grammar (it cannot parse `precision` statements — the checker works around that) |
| tree-sitter-xml | 0.7.0 | MIT | XML grammar |
| lameenc | 1.8.4 | LGPL-3.0-or-later | MP3 encoding for the generated demo audio |

`lameenc` is a *tool-time* dependency: it wrapped the LAME encoder when `demo-side-a.mp3` was
generated. If you regenerate the sample you will need it (or any other encoder); if you only build
the app you do not.

## 5. Assets generated for this repository

| Asset | Origin | License |
| --- | --- | --- |
| `app/src/main/assets/audio/demo-side-a.mp3` | Synthesised by `tools/generate_demo_audio.py`: an Am9–Fmaj7–Cmaj9–G6 pad plus filtered noise and record crackle, generated from sine partials and a deterministic PRNG. No samples, no recordings, no third-party material. 32 s, stereo, 112 kbps, peak −3 dBFS. | Original to this repository; ship it freely with the module. |
| `app/src/main/assets/shaders/*.vert`, `*.frag` | Written for this module. GLSL ES 3.0. | Original to this repository. |
| `app/src/main/res/drawable/ic_launcher_foreground.xml`, mipmaps | Drawn for this module as vector XML. | Original to this repository. |
| Label artwork, deck geometry, studio environment | Generated at runtime from code (Canvas + procedural meshes + shaders). | Original to this repository. |
| Typography | The platform's `Typeface.SERIF` / `SANS_SERIF` families. No font files are bundled or redistributed. | Device-provided |

## 5b. The press chain and the vault

The DSP in `press/VinylPresser.kt` — the filters, the noise and crackle generators, the wow-and-flutter
resampler, the Schroeder reverb and the soft ceiling — was written for this repository. It uses no
third-party DSP library, no impulse responses and no samples: the crackle is a seeded Poisson process,
the surface is filtered white noise, and the room is four comb filters and two all-passes. Nothing is
fetched at runtime and nothing is bundled beyond the source.

A pressing produced by this app is the user's own audio, transformed on their device. The app makes no
claim over it, and there is nowhere for it to go: the export is a WAV in app-private storage.

## 6. What is deliberately *not* here

* No HDRI or environment map (the studio is a shader).
* No glTF/GLB/OBJ/FBX models (every mesh is generated by `graphics/geometry`).
* No image textures (grain, grooves and the backdrop are procedural; the label is drawn at runtime).
* No music samples, no sound effects, no voice.
* No analytics, crash reporting, ad, attribution or telemetry SDK of any kind.
* No networking library: the module has no `INTERNET` permission and no HTTP data source.

If you later add a user's own audio or artwork, that material is theirs — this notice covers the
module's own content only.

## 7. Repository license

This repository has no top-level `LICENSE` file, so no license is granted here beyond what GitHub's
terms and the third-party licenses above already permit. If Vynyl is going to be distributed, add a
project license and `NOTICE` before shipping; the dependency table in section 1 lists everything the
APK contains.
