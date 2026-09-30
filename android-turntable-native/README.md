# Vynyl — native Android turntable (reference implementation)

A standalone Android Studio project that renders a **photoreal-enough record player in real time with
a hand-written OpenGL ES 3.0 renderer**, plays local audio through Media3, and drives the whole
mechanism — record drop, platter spin-up, tonearm travel, stylus cueing, groove tracking, needle
lift, arm return — from one frame-rate-independent state machine.

It exists so the Vynyl app can have a *native 3D deck* instead of a spinning image. Everything is
procedural, bundled and offline: **no GLB models, no downloaded HDRI, no remote textures, no audio
streaming, no `INTERNET` permission**. The demo works in airplane mode the moment it is installed.

```
android-turntable-native/
├── app/src/main/assets/shaders/     GLSL ES 3.0: turntable.vert/.frag, fullscreen.vert,
│                                    background.frag, composite.frag
├── app/src/main/assets/audio/       demo-side-a.mp3  (generated, 32 s, see LICENSES.md)
├── app/src/main/java/com/vynylrecord/turntable/
│   ├── model/                       units, geometry maths, vinyl styles, label layout
│   ├── graphics/geometry/           indexed mesh builders + the turntable assembly
│   ├── graphics/gl/                 shader/VAO/FBO plumbing
│   ├── graphics/material/           materials + the procedural paper label
│   ├── graphics/animation/          the mechanism state machine
│   ├── graphics/                    renderer, scene, camera, environment, capabilities
│   ├── audio/                       Media3 transport (local files only)
│   ├── player/                      TurntableController — the public API
│   └── ui/                          Compose: VynylTurntable, demo screen, static fallback
├── app/src/test/                    JVM unit tests (no device required)
└── app/src/androidTest/             device tests (Compose UI, lifecycle, fallback)
```

---

## 1. Run it

**Requirements**

| Tool | Version |
|---|---|
| Android Studio | Ladybug (2024.2) or newer |
| JDK | 17 (the Gradle toolchain target) |
| Android SDK | Platform 35, Build-Tools 35.x |
| Device / emulator | Android 8.0 (API 26) or newer with **OpenGL ES 3.0** for the 3D path |

**Setup** — no keys, no environment files, no services:

1. `File → Open…` and choose **`android-turntable-native/`** (not the repository root; this folder is
   its own Gradle build).
2. Let Gradle sync. If Android Studio asks for the SDK path, accept the local SDK — there is nothing
   else to configure.
3. Run the `app` configuration on a device or emulator.

**Command line**

```bash
cd android-turntable-native
./gradlew assembleDebug          # APK at app/build/outputs/apk/debug/app-debug.apk
./gradlew test                   # JVM unit tests
./gradlew lint                   # Android lint
./gradlew connectedDebugAndroidTest   # device tests (needs a connected device)
./gradlew installDebug           # install on the connected device
```

**Capture instructions (for screenshots / store assets)**

1. Launch the app and wait for the record to settle — the deck starts in the *Hero* framing with the
   warm key light on the plinth's brass edge.
2. Drag one finger to orbit, pinch to zoom, double-tap to return to the Hero framing. The camera
   never dips below the plinth: that is an enforced limit, not a convention.
3. Tap the expand icon for **full-screen 3D**, then use the camera chips under *Options* for the
   *Needle* and *Label* close-ups.
4. For capture, switch the render quality to **High**, wait a second for the offscreen target to
   resize, then use the debug overlay (gauge icon, debug builds only) to confirm you are at 60 fps.
5. Rotate the device to verify the surface is rebuilt and the transport keeps playing.

---

## 2. What the renderer actually does

Custom OpenGL ES 3.0 forward renderer, no engine, no scene format.

* **Geometry** — every mesh is generated at runtime into an interleaved buffer: position, normal, UV,
  tangent (11 floats / 44 bytes), 16-bit indices, one VAO + immutable VBO/EBO per mesh. The hardware
  is drawn as lathed profiles with chamfers (revolve), rims, a rubber mat, a beveled record with a
  separate groove surface, a tubular tonearm with a tapered headshell, knurled brass controls and
  rounded feet. Around 22 meshes, one draw call each, no per-frame regeneration.
* **Materials** — a 40-uniform shader carries albedo, roughness, metallic, reflectance, clearcoat,
  fresnel boost, opacity, emissive and a brushed-metal mode. Fifteen material kinds cover lacquered
  wood, brushed brass, polished aluminium, black rubber, glossy or smoked vinyl, paper and steel.
* **Grooves** — the record's playing surface emits `u = angle/2π` and `v = radius`; the fragment
  shader recovers a *metric* radius from `uDiscRadialRange` (which tracks the annulus that surface is
  actually built from), lays grooves at a 67 µm pitch, derives the bitangent from the radial gradient
  for the anisotropic highlight, and modulates the specular with an angular term so the disc visibly
  turns. Relief is level-of-detail aware in two terms: the individual 67 µm grooves appear only when
  a fragment is narrower than one groove, and below that they resolve into ~2.2 mm concentric bands,
  so the pressing still shows radial structure at normal viewing distance instead of aliasing into
  moiré or flattening into a plain black disc.
* **Lighting** — a warm three-quarter key, a soft amber fill, a cool rim, a camera-facing wrap fill
  and a sky/floor ambient gradient. Shadowing is analytic: a disc shadow under the platter, a soft
  blob under the arm, and a rounded-rectangle shadow under the plinth, all evaluated in the material
  shader against the real ground plane.
* **Environment** — no HDRI. A full-screen procedural pass paints the studio backdrop (warm cream to
  dusty rose to deep stone), a floor plane whose horizon tracks the real camera, a key-light bloom
  placed where the light actually is, restrained film grain, and a vignette tinted by the vinyl
  style's accent.
* **Post** — the scene renders into an offscreen target (MSAA where the driver allows it, resolved to
  a texture), half-resolution background pass, then a composite that applies exposure, FXAA-style
  edge resolve, grain and vignette, and writes premultiplied output.

**Quality ladder** — `LOW` / `MEDIUM` / `HIGH` change real work, not just labels: mesh segment
counts, groove detail, offscreen render scale and a hard cap on the render-target's longest edge,
MSAA samples (clamped to `GL_MAX_SAMPLES`), analytic shadows, grain and the label bitmap size. The
default is chosen from the live GL context (and `isLowRamDevice`); a device without OpenGL ES 3.0
gets a static Compose fallback that draws the same deck from the same dimensions, with every control
still working.

---

## 3. Audio sync: the needle gate

Playback does not start when you press play. It starts when the stylus lands.

```
play() ─▶ animator PLATTER_STARTING ─▶ TONEARM_MOVING ─▶ NEEDLE_LOWERING
                                                            │
                                            onNeedleContact │  (render thread)
                                                            ▼
                                                   controller ▶ ExoPlayer.play()
```

* The platter ramps up at a constant rate and reaches 33⅓ rpm (200 °/s) in about a second.
* The arm leaves its rest only once the platter is within 1.5 % of speed, then travels to the
  lead-in groove.
* The cueing lever lowers the stylus over ~360 ms; the frame it touches down, `onNeedleContact`
  fires and the controller starts the transport.
* While playing, the arm angle is derived from **real Media3 playback progress**, and the platter
  angle is integrated from elapsed time at the selected speed — the two never drift apart because
  both are read from the same transport snapshot.
* Pausing lifts the stylus and leaves the motor running (an instant, authentic resume). Seeking
  glides the arm to the new groove. Completion lifts the needle, returns the arm, coasts the platter
  down and parks. Errors park the mechanism exactly the same way.
* Audio focus loss, headphone unplug or a transport error stops the mechanism: the controller sees
  playback stop without being asked and raises the needle.

---

## 4. Supported versions

| Component | Version |
|---|---|
| `minSdk` | 26 (Android 8.0) |
| `targetSdk` / `compileSdk` | 35 |
| Android Gradle Plugin | 8.7.3 |
| Kotlin | 2.0.21 |
| Compose BOM | 2024.10.01 (Material 3) |
| Media3 | 1.5.1 (ExoPlayer, local playback only) |
| OpenGL ES | 3.0 for the 3D path; anything older falls back to the Compose surface |

## 5. Offline guarantee

* `AndroidManifest.xml` declares **no permissions at all** — not even `INTERNET`.
* The shaders are GLSL source files in `assets/`; the audio is a generated MP3 in `assets/`; the
  label is drawn at runtime with the Android canvas; the environment is arithmetic.
* No analytics, ads, telemetry, crash reporting or remote logging. Nothing to configure, nothing to
  sign up for.
* `tools/verify_static.py` (see §8) fails the build pipeline if a remote URL or a networking
  dependency sneaks in.

## 6. GPU and device limits — read this before promising a device a good time

| Limit | Effect |
|---|---|
| **No OpenGL ES 3.0** | The 3D path is unavailable *by design*: the shaders are `#version 300 es`. The static Compose fallback is used instead. This is not a workaround; ES 2.0 would need a second shader set to look the same. |
| **No MSAA (`GL_MAX_SAMPLES = 0`)** | The scene renders single-sample and the composite's edge resolve does the work. Edges on the platter rim and the tonearm are visibly harder. Quality is capped at *Medium*. |
| **Max texture size < 2048** | The label bitmap is downscaled to 512 px, and quality drops to *Low*. Very old or very cheap GPUs land here. |
| **Low-RAM devices** | Default quality is *Low*: half-resolution offscreen target, no MSAA, no analytic shadows, no grain. |
| **Large screens / high DPR** | The render target is capped (720 / 1080 / 1440 px longest edge by level) and upscaled. On a 1440p tablet at *Low* the deck will look soft — that is the trade for a stable frame time. |
| **Frame rate** | Targets 60 fps with a graceful 30. The renderer never allocates per frame, but a backgrounded app stops the GL thread entirely (the surface is paused, and the EGL context is rebuilt on resume). |
| **Huge vertex counts** | Meshes use 16-bit indices, so a single mesh is capped at 65 535 vertices. The *High* preset is about 30 k vertices for the whole deck; nothing is near the ceiling, but keep it in mind when adding parts. |
| **Translucent pressings** | Smoked Obsidian and Midnight Sapphire draw in a blended pass after the opaque group. Sorting is per group rather than per triangle, which is correct for a stack of coplanar discs and wrong for intersecting transparency — do not add transparent geometry that crosses the record. |
| **Thermal** | Sustained *High* quality on a mid-range phone will heat up and eventually drop frames if the device throttles. That is the OS's decision; the renderer does not fight it. |

## 7. Accessibility

* **The 3D scene is not required.** Play/pause, seek, skip ±10 s, speed, style, camera reset and
  quality are all Compose controls; the GL surface is decorative and is described, not interacted
  with, by TalkBack.
* Content descriptions on every icon button, ≥ 52 dp touch targets, a polite live region for the
  mechanism phase, a monospace timecode with an ellipsis-free layout, and scalable text throughout.
* Position is never announced continuously — only state changes are.
* Reduced motion (the system "remove animations" switch) disables the idle camera orbit, shortens
  every transition, and stops the static fallback's rotation.

## 8. Tests and verification

```bash
./gradlew test                        # JVM: geometry, camera limits, state machine, controller, label layout
./gradlew connectedDebugAndroidTest   # device: demo screen, transport, lifecycle, static fallback
./gradlew lint
```

The JVM suite covers the parts that are pure maths and state (`model/`, `graphics/animation/`,
`graphics/CameraRig`, `graphics/material/`, `player/TurntableController`) and needs no emulator.

**Static verification in this repository** — `tools/verify_static.py` runs without any Android
tooling; its only requirement is `tree_sitter` plus the Kotlin/GLSL/XML grammars
(`pip install tree-sitter tree-sitter-kotlin tree-sitter-glsl tree-sitter-xml`):

```bash
python3 tools/verify_static.py
```

It parses every Kotlin and GLSL file, checks the XML, asserts the offline contract (no remote URLs,
no networking dependencies, no permissions), cross-checks that every uniform declared in a shader is
uploaded by the renderer and vice versa, and confirms the ES 3.0 manifest contract and asset set.

> **Honest status:** the sources in this folder were written and *statically* verified — every
> Kotlin file parses, every shader parses in both `precision` variants, all 64 shader uniforms match
> the renderer's uploads, and the offline/permission contract holds. The Android build itself
> (`assembleDebug`, `test`, `lint`, `connectedAndroidTest`) was **not** executed in the environment
> this module was authored in, because it had no JDK, no Android SDK and no access to Maven
> repositories. Run `./gradlew assembleDebug && ./gradlew test` on a machine with the SDK before
> treating it as green. Nothing here is a mockup: the renderer is a complete implementation, not a
> placeholder surface.

## 9. Where to go next

* `ARCHITECTURE.md` — threads, state machine, resource ownership, GL lifecycle.
* `INTEGRATION.md` — exactly what to copy into the full Vynyl app, and in what order.
* `LICENSES.md` — third-party licences and the provenance of the bundled audio.
