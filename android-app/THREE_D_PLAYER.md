# The 3D turntable

The player is a real turntable, drawn with OpenGL ES 3.0 on the device: a plinth with brass feet, an aluminium
platter, a felt mat, the record itself with procedural grooves, a printed centre label, a spindle, a tonearm
with counterweight, headshell and stylus, two knobs and a lamp. Nothing is a picture of a turntable and
nothing is a flat spinning disc: the grooves are geometry in the fragment shader, the label is a bitmap drawn
at runtime from the record's own metadata, and the needle is lowered onto the record at the moment the audio
starts.

There are no textures, models, HDRIs or shaders fetched from anywhere. The whole scene is code in
`core/graphics`, two GLSL files in `assets/shaders/`, and one procedurally drawn 512-pixel label bitmap.

## Where the code lives

| File | What it does |
| --- | --- |
| `gl/TurntableRenderer.kt` (in `core/graphics`) | the scene: builds the meshes, draws the parts in order, owns the GL resources |
| `Meshes.kt` | 12 procedural mesh builders and the vertex/index buffer they produce |
| `Math3D.kt` | `Vec3`, a column-major `Mat4`, `lerp` and `Smoothed` (half-life based easing) |
| `TurntableScene.kt` | the deck's vocabulary: `DeckState`, `DeckPose`, `DeckPart`, `DeckDimensions`, `DeckMaterials`, and `TurntableAnimator`, the state machine that produces the pose |
| `TurntableCamera.kt` | orbit, pinch-zoom, four presets, idle drift, clamping, matrices |
| `GlProgram.kt` | shader compilation, the uniform cache and the VAO/attribute setup |
| `LabelTexture.kt` | the Canvas-drawn centre label, uploaded to a texture |
| `feature/player/TurntableSurface.kt` | the only file that mentions `GLSurfaceView`; contains no OpenGL of its own |
| `core/playback/RecordPlayerController.kt` | Media3 + the animator + the camera, exposed to the screen as a `DeckPose` per frame |

The rule the split enforces: **a composable never touches GL.** `TurntableSurface` is an `AndroidView`
wrapper; the drawing happens on the `GLSurfaceView`'s own render thread, and the screen passes state in and
gestures out.

## The scene

`DeckDimensions` uses real measurements, because that is what makes the camera distances and the tonearm
geometry believable:

| | |
| --- | --- |
| plinth | 0.45 × 0.38 × 0.075 m, bevelled, on four feet |
| platter | 0.155 m radius, 0.022 m tall, with a brass rim |
| mat | 0.150 m radius, 4 mm felt |
| record | 0.1524 m radius (a 12-inch LP is 0.3048 m across), 2.2 mm thick |
| centre label | 0.050 m radius |
| spindle | 3.5 mm radius, 24 mm tall |
| tonearm | pivot 0.145 m out, 42 mm above the plinth, 6 mm arm, with a counterweight and a headshell |
| stylus | travels from a 0.146 m lead-in radius to a 0.058 m run-out radius |

The 16 parts are drawn back to front and bottom to top (`DeckPart.ordered`): plinth, feet, platter, platter
rim, mat, record, grooves, label, spindle, arm base, tonearm, counterweight, headshell, stylus, controls,
lamp. Depth testing and back-face culling are on; blending is enabled for the translucent pressings and for
the sheen and the lamp, and disabled again for the opaque parts so nothing blends twice.

Meshes come from `Meshes`: `box`, `beveledBox`, `cylinder`, `taperedCylinder`, `cone`, `disc(outerRadius,
innerRadius, thickness, segments)`, `ring`, `torus`, `tube(curve, radius, alongSegments, radialSegments)`,
`wedge`, `knob` and `needle`. The tonearm is a swept tube along a real curve; the plinth is a beveled box,
not a cube; the disc is a `disc` with a hole for the label, not a squashed cylinder. Every builder produces
positions, normals, UVs and 16-bit indices with a validated `MeshData` (`isValid`, `boundingRadius()`), and
`tools/check_meshes.py` verifies from outside Kotlin that no triangle faces inward.

## Materials, and the shader contract

One program draws everything; a part's material is a set of uniforms, not a separate shader. `DeckMaterials`
provides the fixed ones (lacquer, brass, aluminium, steel, felt, stylus) and three that depend on the record:
`record(style)`, `grooveSheen(style, sheen)` and `label(style)`, plus `lamp(color, intensity)`.

Grooves are not a texture. The fragment shader perturbs the surface normal radially — `fract(radius * pitch)`
for the rings, a little angular noise for the wobble a real pressing has — and the perturbed normal goes
through the same lighting as everything else. The result is resolution-independent and costs one `sin`.
A translucent pressing (Smoked Obsidian, Midnight Sapphire) additionally blends and transmits light
(`DeckMaterial.transmission`).

The uniform list is a **contract** between `GlProgram.EXPECTED_UNIFORMS` and
`assets/shaders/turntable.{vert,frag}`. `location(name)` is a map lookup, so a name missing from the list
returns `-1` and every `glUniform*` for it is a silent no-op — the deck simply loses that property and renders
flat. `ShaderContractTest` (instrumentation) asserts the two lists match in both directions, that the shaders
are GLSL ES 3.0, that no GLSL 1.x keyword survived, that the fragment shader reads only varyings the vertex
shader writes, and that the attribute locations are 0 (position) and 1 (normal) as `GpuMesh` uploads them.

## The label

`LabelTexture.sync(record, style, revision, size)` draws the centre label on a Canvas — the title, the
dedication line, the occasion and date, the side labels, brass rings, in the record's own finish — and
uploads it as a 512-pixel texture. It is re-uploaded **only when the metadata revision changes**, which is
what keeps a frame from re-drawing text. `TurntableRenderer.publish(...)` takes that revision along with the
pose and the style; a changed revision rebuilds the texture, a changed style rebuilds the materials.

## Motion: the deck's state machine

`TurntableAnimator` maps *what the player is doing* onto *what the deck is doing*, and produces a `DeckPose`
per frame — platter rotation, disc rotation and lift, arm travel and lift, needle contact, lamp intensity and
warmth, groove sheen, and press progress.

14 states: `IDLE`, `LOADING`, `READY`, `CUEING`, `PLAYING`, `PAUSED`, `SEEKING`, `ENDED`, `LIFTING`,
`RETURNING`, `RENDERING`, `COMPLETE`, `ERROR`, `POWERED_DOWN`. `DeckState.forTransport(isPlaying,
isBuffering, hasRecord, hasError)` is how the controller's transport state becomes a deck state, so the
mapping is one function rather than a set of `if`s spread across a screen.

| Motion | Value |
| --- | --- |
| platter speed | 33⅓ rpm = 3.49 rad/s, ramped up over ~6.5 time constants and down over ~3.2 |
| cue (needle down) | 0.55 s; contact crosses 1.0 exactly when the audio starts |
| lift | 0.42 s |
| loading a record | 1.6 s, the disc dropping 6 cm onto the platter |
| arm travel | 0.18 → 1.0 of the side, tracking the playhead, clamped at both ends |
| arm / lamp easing | half-life 0.07 s / 0.35 s |
| lamp levels | off 0.12, low 0.42, medium 0.78, bright 1.0, flare 1.45 |
| error | the lamp turns warm (`lampWarmth`) so a failure is visible without reading a message |
| maximum step | 1/20 s — a resumed app cannot teleport the platter |

Everything is **frame-rate independent**: the animator integrates a delta with a half-life response rather
than counting frames, so 30 fps and 60 fps produce the same motion (asserted in `TurntableSceneTest`), and a
long frame after the app returns from the background is clamped rather than jumped. `reducedMotion` (a
setting, or the system's animator scale) collapses the transitions to their end state and slows the fallback
deck instead of stopping it — a player that does not move looks broken.

Pausing holds the needle *in* the groove rather than lifting it: lifting at every pause would thump, and the
record should stay where the listener left it.

## The camera

A perspective camera with a 34° field of view, an orbit around the deck, pinch-zoom, and limits that keep it
believable: pitch 8–78° (nothing goes under the plinth), distance 0.55–3.4 m. `orbitBy` applies 0.32°/px
horizontally and 0.24°/px vertically; `zoomBy` is multiplicative so pinch feels the same at every distance;
yaw wraps.

| Preset | yaw | pitch | distance |
| --- | --- | --- | --- |
| Three-quarter view (default) | 34° | 26° | 1.62 m |
| Front view | 0° | 12° | 1.44 m |
| Overhead view | 18° | 62° | 1.86 m |
| Close on the record | 24° | 22° | 1.02 m |

Tapping the deck cycles the presets, double-tapping resets to the default view, and after six seconds without
a touch the camera drifts five degrees per second if auto-orbit is enabled. A touch stops the drift
immediately — an idle animation the user cannot stop is worse than no idle animation.

## Playback, and how audio and motion stay together

`RecordPlayerController` owns an `ExoPlayer`, the animator and the camera, and hands the screen one
`DeckPose` per frame from `onFrame(deltaSeconds, userIsTouchingCamera)`.

* **The audio starts at needle contact.** Pressing play cues the deck; ExoPlayer starts when
  `animator.needleIsDown` becomes true. A record that plays the moment its needle is still descending is the
  single most obvious way this feature goes wrong.
* **The arm follows the playhead.** Every frame the position is fed back into `setPlayhead`, so the arm
  tracks the side and is clamped at the run-out.
* `setVinylStyle` re-materialises the disc without reloading the file; `setMetadata` bumps the revision that
  re-draws the label; `resetCamera`, `setCameraPreset(index)`, `cycleCameraPreset()`, `setAutoOrbit`,
  `setReducedMotion`, `stop` and `release` are all idempotent and safe to call from a screen that is being
  recomposed or torn down.
* `onHidden`/`onVisible` pause the GL surface with the lifecycle; `release` frees the player, the program,
  the meshes and the label texture. Nothing keeps drawing in the background and nothing leaks a GL object.

## Performance

* `RENDERMODE_CONTINUOUSLY` with a renderer that allocates nothing per frame: matrices, vectors, colour
  arrays and the pose are all reused fields, and the meshes are uploaded once.
* Three quality levels (`GraphicsQuality`): **Battery** (0.6× render scale, coarser segments, smaller label
  texture, no shadows), **Balanced** (1.0×, soft shadows — the default) and **Sharp** (1.4×, densest meshes).
  The chosen level scales both the mesh segment counts and the surface resolution.
* The debug FPS readout is available from Settings for anyone who wants to check the frame budget on their
  own device.
* The GL thread is the surface's own; the UI thread only publishes a pose and reads statistics.
* The surface is paused when the screen is not visible, and the renderer has no work to do when the deck is
  powered down.

## When 3D is not available

`StaticDeckFallback` draws the same record with Compose's canvas: the disc in its finish, turning grooves, the
label, a brass marker that shows rotation, and a progress arc — driven by the same `progress` and the same
reduced-motion setting, with the same controls underneath. It is used when the device has no OpenGL ES 3.0,
when the shader program fails to build, and when the viewer asks for reduced motion and the static deck is the
calmer choice. It is not a placeholder: a device that cannot run the 3D view still gets a working record
player, and `glEsVersion 0x00030000` is declared `required="false"` in the manifest so such a device can
install the app at all.

## Testing

`TurntableSceneTest` (JVM) asserts the mechanical properties that a screenshot cannot: the platter really
turns at 33⅓ rpm, the needle lands at the end of the cue rather than before it, pausing holds it in the
groove, a side ending parks the arm, a seek spins faster than a play, frame-rate independence holds at 30 and
60 fps, a resumed app does not teleport the platter, reduced motion collapses the transitions, every one of
the 14 states produces a finite pose, the smoked pressing is translucent and the ruby one is not, and the
stylus travels from the lead-in to the run-out and lifts by exactly the arm lift height.

`ShaderContractTest` (device) asserts the shader/uniform contract described above, and
`RenderPipelineInstrumentedTest` presses a record and checks the artwork that the label shares its drawing
code with.
