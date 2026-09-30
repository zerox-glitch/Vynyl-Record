# Architecture

A standalone Android module that plays a local recording while a procedurally generated 3D record
player performs the same thing on screen. Nothing is fetched, nothing is bundled as a model, and the
whole deck is built from code at runtime.

49 Kotlin files, 5 GLSL shaders, one generated audio sample. ~10 200 lines of Kotlin in `main`.

---

## 1. The three threads

| Thread | Owns | Never touches |
| --- | --- | --- |
| **Main (UI)** | Compose, `TurntableController`, `AudioEngine`/ExoPlayer, input events | any `GL10`/`GLES30` call |
| **Render (`GLSurfaceView`'s GL thread)** | `TurntableScene`, `CameraRig`, `TurntableAnimator`, every GL object | Media3, Compose, the audio file |
| **ExoPlayer's internal threads** | Media3 playback and audio focus | our scene |

There is exactly one shared object between the UI thread and the render thread: the
`TurntableRenderer` instance, which is the `RendererCommands` implementation. Communication is
one-way in each direction:

* UI → render: `@Volatile` intent fields, plus a small monitor-guarded gesture queue
  (`GestureQueue`). Every method returns immediately and touches no GL.
* Render → UI: `RendererCallbacks`, invoked on the render thread; the host forwards to
  `TurntableController`, whose methods only write a `StateFlow` value (thread-safe) or hand work to
  the UI-thread scope.

There is no lock on the render path. The gesture queue is the only synchronised block, it copies six
floats, and it is contended for well under a frame per event.

### Why the transport is replaced wholesale

`TurntableRenderer.transport` is a `@Volatile var TransportSnapshot`. A single reference read gives a
consistent snapshot; seven independent volatile fields could be observed half-updated, which would
make the arm and platter disagree for one frame. `AudioSnapshot` (the render-side mirror) is written
only when the transport changes.

---

## 2. Frame flow

```
onDrawFrame(delta)
 ├─ delta = clamp(now − lastFrame, 1/240 s … 0.1 s)        ← frame-rate independence
 ├─ drainIntents()           quality / reduced motion / style / camera commands
 ├─ gestureDrainToCamera()   orbit, zoom, pan, double-tap reset
 ├─ camera.update(delta)     inertia, damping, idle auto-orbit, preset easing
 ├─ animator.update(delta, audioSnapshot)                  ← the state machine
 ├─ applyPendingLabel()      repaint + upload only when the wording changed
 └─ scene.render(animator, cameraSnapshot, delta)
      ├─ backdrop pass   (half-res, procedural studio, no depth)
      ├─ scene pass      (offscreen, premultiplied alpha, optional MSAA)
      ├─ resolve         (glBlitFramebuffer when multisampled)
      └─ composite pass  (scene over backdrop, edge resolve, grain, vignette)
```

Everything after `drainIntents` runs inside the render thread's exclusive ownership of the scene, so
no defensive synchronisation is needed inside `TurntableScene`, `CameraRig` or `TurntableAnimator`
(the animator locks internally only because the controller's intents arrive from the UI thread).

### Allocation budget

Zero allocations per frame in the steady state:

* matrices: preallocated `FloatArray`s (`viewProjection`, `view`, `nodeMatrices[7 × 16]`, scratch);
* the draw loop iterates an immutable `List<Part>` built once per geometry generation;
* `Vec3` probes are reused fields, not constructor calls;
* the label bitmap is the only large transient, and it is recycled immediately after upload;
* `describe()` and statistics strings are built once every 0.5 s, not per frame.

---

## 3. Geometry

`graphics/geometry` is a small procedural mesh toolkit, independent of the deck:

| Generator | Used for |
| --- | --- |
| `MeshFactory.box` | trim blocks, headshell body |
| `MeshFactory.roundedPrism` | the lacquer plinth (rounded corners, top and bottom bevels) |
| `MeshFactory.cylinder` | spindle, pivot post, control barrels |
| `MeshFactory.disc` | platter casting, mat, record body, label |
| `MeshFactory.annulus` | platter rim, record edge |
| `MeshFactory.planarAnnulus` / `radialAnnulusTop` | strobe ring, record top surface with radial groove UVs |
| `MeshFactory.revolve` | feet, counterweight, knob, button dome (lathe profiles) |
| `MeshFactory.torus` | arm-rest cradle |
| `MeshFactory.tubeBetween` | tonearm tube |
| `MeshFactory.beam` | headshell shell |
| `MeshFactory.groundPlane` | studio floor |

Every vertex is 11 floats / 44 bytes: position (3), normal (3), UV (2), tangent (3). Indices are
16-bit, so one mesh caps at 65 535 vertices — comfortably above the ~30 000 the deck needs at High.
`MeshBuilder.build()` runs a winding pass (`orientTrianglesWithNormals`) so back-face culling is
safe without trusting each generator's handedness.

`TurntableBuilder.build(detail)` assembles the 22 meshes from `TurntableSpec` (a single source of
truth for dimensions) and bakes fixed translations into the vertices, so the scene graph only ever
applies *animated* transforms. Rebuilds happen only when the quality level changes — never per frame.

### Scene graph

Seven nodes, all world-space meshes:

```
WORLD     ground, feet, plinth, arm rest, knob, indicator bezel/lamp
PLATTER   platter, rim, strobe, mat, spindle            ← rotate about the platter axis
RECORD    record body, record top, label                ← platter spin + drop + 9° insertion twist
ARM       pivot brass, tonearm tube                     ← yaw about the pivot, then cock up
NEEDLE    stylus                                         ← arm pose + groove flutter
BUTTON    power button                                   ← 1.6 mm travel
SELECTOR  speed selector                                 ← ±22° detent swing
```

Node matrices are recomputed per frame into one array; parts carry a `baseOffset` into it. No matrix
is allocated, and no mesh is transformed on the CPU after upload.

---

## 4. Animation state machine

`graphics/animation/TurntableAnimator` is the mechanism. 14 states, time-based (not frame-counted),
updated with a clamped delta:

```
IDLE ─▶ LOADING_RECORD ─▶ RECORD_SETTLING ─▶ PLATTER_STARTING ─▶ TONEARM_MOVING
     ─▶ NEEDLE_LOWERING ─▶ PLAYING ⇄ SEEKING
                            │            └─▶ NEEDLE_LIFTING ─▶ PAUSED
                            └─▶ NEEDLE_LIFTING ─▶ TONEARM_RETURNING ─▶ PLATTER_STOPPING
                                                                       ├─▶ IDLE
                                                                       ├─▶ COMPLETED
                                                                       └─▶ ERROR
```

Key behaviours:

* **Record drop**: falls from 22 mm with `easeOutCubic`, then a damped rebound that decays to zero.
* **Platter**: constant acceleration to the selected speed, and a coast-down that does not stop
  instantly. 33⅓ rpm ⇒ 200 °/s, 45 rpm ⇒ 270 °/s.
* **Tonearm**: yaw follows the *audio* progress, not wall time, through
  `TonearmGeometry.angleForProgress`, and is rate-limited so a seek glides instead of teleporting.
  The stylus stays on the effective-length circle (`EFFECTIVE_LENGTH` = 0.20 m, pivot-to-centre
  0.2338 m) — the linear progress mapping is within 0.25° of the exact two-circle solution.
* **Needle**: micro-flutter whose amplitude follows platter speed, plus one decaying thump on
  contact.
* **Controls**: power button depression, indicator lamp ramps (steady / slow pulse when paused /
  fast pulse on error), selector detent swing.
* **Reduced motion**: `AnimationConfig.scaled(true)` shortens every transition to 55 %; the camera
  drops inertia and auto-orbit entirely.

The animator exposes plain `@JvmField` outputs plus two lambdas (`onNeedleContact`,
`onPhaseChanged`) and takes only an `AudioSnapshot` — no Android types, which is why the whole state
machine is unit-testable on the JVM.

---

## 5. Rendering

Custom OpenGL ES 3.0 renderer, three programs, no scene graph library, no glTF.

| Program | Pass | Notes |
| --- | --- | --- |
| `turntable.vert/.frag` | deck | Cook-Torrance-ish direct lighting: three-quarter warm key, amber inverse-square point fill, cool rim weighted by grazing angle, camera-facing wrap fill, hemispheric ambient; fresnel + clearcoat; procedural groove relief with a two-term `fwidth` LOD (individual grooves cross-fading into coarse concentric banding); analytic soft shadows; sRGB out |
| `background.frag` | studio | warm cream → dusty rose → deep stone gradient, floor plane with fade, soft radial glow, optional grain — generated in the shader, never loaded |
| `composite.frag` | resolve | premultiplied scene over backdrop, edge-directed resolve (FXAA-style), grain, vignette, exposure |

**Offscreen first, on screen second.** The scene renders into a target with premultiplied alpha so
the backdrop can be composited behind it, and so hardware MSAA can be requested without paying for
it on the final full-screen pass. If the driver refuses the multisampled configuration
(`glCheckFramebufferStatus`), `OffscreenTargets` silently downgrades to a single-sample attachment and
the composite's edge resolve takes over.

**Materials.** `MaterialLibrary.buildSet(style)` returns 15 materials (lacquer, brass trim, rubber,
cast platter, aluminium rim, strobe, mat, vinyl, paper label, steel, pivot housing, brass control,
control plastic, indicator lamp, ground). Each is a fixed set of scalars/flags — roughness, metallic,
reflectance, clearcoat, fresnel boost, opacity, AO, emissive, groove config, brushed config — so the
shader takes one uniform block per material and no branching per mesh. Styles change *material
values*, never the shader or the geometry.

**Vinyl styles.** `VinylStyle` is a typed enum with a complete material + palette configuration per
entry: Classic Wax Ruby, Midnight Sapphire, Imperial Gold Master, Vintage Emerald, Smoked Obsidian.
A style changes the record's albedo/opacity/grooves, the label paper and ink, the scene accent, the
fill light tint, the glow colour and the backdrop — Sapphire and Obsidian are genuinely translucent
(`recordOpacity` 0.88 / 0.72), which is why translucency is decided per material, not globally.

---

## 6. Audio and the ordering guarantee

`audio/AudioEngine` wraps Media3 `ExoPlayer`:

* music usage / content type with `handleAudioFocus = true`, so a phone call or another player is
  honoured by the platform rather than fought;
* `setHandleAudioBecomingNoisy(true)` for pulled headphones;
* local sources only — `file://` and `content://` are accepted, everything else is refused, and the
  module contains no HTTP data source at all;
* the bundled sample is staged into app-private storage (`filesDir/audio/…`) once, written to a
  `.part` file and renamed, so a killed process cannot leave a half-copied MP3 behind.

The ordering rule that shapes the whole player:

```
play()  →  animator.requestPlay()  →  (state machine)  →  touch-down  →  onNeedleContact  →  backend.play()
```

Audio **never** starts before the stylus visually lands. Pause does the opposite: the transport stops
immediately and the mechanism lifts the needle, which is why resuming is instant — the platter never
stopped. If playback stops for a reason we did not ask for (focus loss, headset, decoder stall), the
ticker notices the transport went quiet and asks the mechanism to raise the stylus.

Progress is sampled, not pushed: `tick()` every 100 ms while playing, 250 ms while idle. Smoothness
of the animation does not depend on it — the platter integrates angle from elapsed time on the render
thread, and the arm catches up rate-limited.

---

## 7. Resource ownership

| Resource | Created | Released |
| --- | --- | --- |
| EGL context | `GLSurfaceView` (ES 3.0, `preserveEGLContextOnPause = false`) | driver, on pause/teardown |
| Shaders, meshes, textures, FBOs | `TurntableScene.initialise` | `TurntableScene.release` |
| Label bitmap | `LabelTextureFactory.create` on the render thread | recycled right after upload |
| ExoPlayer | `AudioEngine` | `AudioEngine.release` (idempotent) |
| Coroutine ticker | `TurntableController.startTicker` | `dispose` / `stopTicker` |
| Scene, camera, animator | `TurntableRenderer` | renderer is dropped with the view |

`preserveEGLContextOnPause` is deliberately **false**. A context that survives *sometimes* is worse
than one that never does — the scene has to be correct in both cases anyway. Keeping it false means
context loss is the normal path: the driver frees every buffer and texture, `onSurfaceCreated` clears
its "applied" markers, and the next frames re-push style, metadata, quality, speed and record state
from the controller. Rotation exercises this on every device.

`TurntableViewModel` owns the engine and the controller, so a configuration change destroys the GL
surface and rebuilds it while the transport keeps playing.

---

## 8. Quality and capability handling

`GraphicsEnvironment` decides, once per process, whether the 3D path can run (a pre-context
`ActivityManager.getDeviceConfigurationInfo` probe) and which default quality to use once the real
context exists.

| Level | Render scale | Pixel cap | MSAA | Shadows | Groove | Edge resolve | Grain | Label |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Low | 50 % | 720 | off | off | off | 0.75 | off | 512 |
| Medium (default) | 72 % | 1080 | 2× | on | 0.75 | 0.5 | on | 768 |
| High | 100 % | 1440 | 4× | on | 1.0 | 0.32 | on | 1024 |

Automatic default: Low on a low-RAM device or with `maxTextureSize < 2048`; High when
`maxSamples ≥ 4`; otherwise Medium. A user choice always wins, and `RenderQuality.clampSamples`
never asks for more samples than `GL_MAX_SAMPLES`.

---

## 9. Accessibility

The scene is decorative and labelled as such. Everything is operable without it:

* all transport, seek, style, camera and quality controls are Compose controls with content
  descriptions, roles and 48 dp+ targets;
* the status line is a polite live region that announces playing / paused / finished / error —
  position is never announced continuously;
* `StaticTurntableFallback` renders the same deck (same `TurntableSpec`, same `TonearmGeometry`) with
  the Compose canvas when ES 3.0 is missing, so the no-GL path is a first-class path;
* reduced motion is read from `Settings.Global.ANIMATOR_DURATION_SCALE` and shortens transitions,
  disables camera inertia and stops the auto-orbit.

## 10. Where the seams are

| Seam | Interface | Swap for |
| --- | --- | --- |
| Audio | `PlaybackBackend` | `MediaSessionService`, a different decoder |
| Renderer | `RendererCommands` / `RendererCallbacks` | another renderer, or a test double |
| Mechanism | `TurntableAnimator` intents | any other animation driver |
| Label | `LabelTextureFactory.create(metadata, style, size)` | another print style |
| Styles | `VinylStyle` | more pressings |

`INTEGRATION.md` covers how to use these from a host app.
