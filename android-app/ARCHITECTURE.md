# Architecture

One app, one process, one local device owner. There is no backend to be consistent with, no synchronisation
to reconcile and no session to keep, so the architecture optimises for the things that are actually hard
here: audio that must be exact, a render that must survive the user leaving the screen, and files that must
never be lost.

## The shape of the source tree

```
com.vynylrecord.app
├── VynylApplication.kt        the process: creates the graph, runs the startup sweep
├── MainActivity.kt            the single activity: splash, edge-to-edge, permission gate, lock, intents
├── LocalVynylGraph            the composition local that hands the graph to a screen
├── core/
│   ├── model/                 pure Kotlin: Record, VinylRecipe, presets, styles, occasions, controls
│   ├── data/                  Room entities + DAOs, repositories, DataStore preferences, JSON
│   ├── storage/               the on-disk layout, atomic file moves, `.vynyl` bundles, backups
│   ├── audio/                 AudioRecord capture, MediaExtractor/MediaCodec import, the music bank
│   │   ├── dsp/               the signal chain: the only code that touches samples
│   │   └── render/            the press: pipeline, encoder, waveform, artwork, validation
│   ├── render/                the durable worker, its scheduler and its notification
│   ├── graphics/              GL ES 3.0 renderer, meshes, camera, the deck's animation state machine
│   ├── playback/              the Media3 controller that keeps audio and animation in step
│   ├── export/                M4A / WAV / artwork / bundle export and the share intents
│   ├── security/              optional biometric lock
│   └── design/                the palette, the type scale, the components, the icons, haptics
└── feature/                   onboarding, studio, soundlab, library, player, settings — Compose only
```

### The layering rules

These are the rules the code is written to, in the order they matter:

1. **No audio code and no OpenGL code inside a composable.** A screen observes a `StateFlow` and draws. The
   DSP lives in `core/audio/dsp`, the GL work lives in `core/graphics`, and the player screen talks to
   `RecordPlayerController` rather than to `GLSurfaceView` or `ExoPlayer` directly. This is what makes the
   audio engine testable on a JVM and the renderer testable without a window.
2. **`core/audio/dsp` has no Android dependency at all.** It is Kotlin over `FloatArray` and `java.io`. The
   Android-specific parts — `MediaCodec`, `MediaExtractor`, `Bitmap`, `Canvas`, `AudioRecord` — live in
   `core/audio` and `core/audio/render`. That is why a three-second render can be asserted at full length in
   a unit test.
3. **The database stores metadata only, never audio.** A row is a few hundred bytes: paths, numbers, the
   recipe JSON, the render state. Audio is always a file, and a file is either complete or absent because it
   arrives by an atomic rename.
4. **Audio first, row second.** A record's files are written before the row that describes them; a record's
   files are deleted before its row. Every failure mode leaves a record with too little rather than a row
   pointing at nothing.
5. **A render is complete only when the file proves it.** `RenderPipeline` re-opens its own output, decodes
   it, measures the duration and the peak and runs `RenderValidator` before the repository is allowed to
   mark the record completed. See below.

## The data model

`core/model` holds plain data classes with no framework annotations, and `core/data/db` maps them to Room.
Keeping the domain model, the database row and the bundle's JSON apart is what allows each to change without
breaking the other two.

| Model | What it is |
| --- | --- |
| `Record` | one record: the label (title, recipient, sender, dedication, occasion, date, side labels), the media paths and waveform peaks, the sound (`presetId`, `controls`, `styledId`, background asset and volume) and the job (`renderState`, progress, stage label, error, job id) |
| `RecordEntity` | the row. Column names are the model's, deliberately, and `advancedSettingsJson` carries the whole `VinylRecipe` plus which knobs the user moved |
| `AudioAsset` | a background bed, texture or one-shot: bundled (read from the APK) or imported (a file), with trim points and a default volume |
| `VinylRecipe` | the complete DSP recipe — 40 plain numbers and booleans in real units (Hz, dB, cents, events per minute) |
| `VinylPresetId` | the five pressings, each carrying a full `VinylRecipe`, plus legacy id mapping |
| `VinylStyleId` | the five pressings' appearances: disc, label, groove and brass colours, and whether the vinyl is translucent |
| `VinylControls` | a recipe plus the set of knobs the user moved by hand — the only source of "Customized" |
| `Record.renderState` | `draft → queued → rendering → completed`, with `failed` and `canceled` as terminal states a retry can leave |
| `Record.renderStage` | the nine named stages a press actually runs, each with the fraction it runs up to |

`Seed.derive(recordId, presetId)` is an FNV-1a hash of the two, and it is the seed for every procedural layer.
A record therefore re-renders bit-identically, while two records made from the same recording still get
different crackle patterns.

## Storage

```
files/
  records/{recordId}/source.wav     the capture or the imported file, as 44.1 kHz stereo PCM
                     master.wav     the pressed record
                     cover.png      the sleeve artwork, drawn on the device
                     waveform.json  peaks for the library card and the player
  assets/{assetId}.wav              imported beds, textures and effects
  exports/                          finished files the user asked for
cache/
  render/{jobId}/                   a press in progress; deleted the moment it finishes
  asset-staging/                    bundled assets copied out of the APK so a decoder can read them
  decoded/                          decoded beds, cached so a re-render is fast
  shared/                           copies staged for the share sheet through FileProvider
```

`StorageLayout` owns every path and is the only place that joins a user- or bundle-supplied id onto a
directory: `safeId` strips everything that is not a letter, digit, dash or underscore and caps the length, so
an id from a hostile `.vynyl` bundle cannot climb out of `files/records/`. `FileStore` adds the operations
that need care: `placeAtomically` renames with a `.old` backup, `sweepStaleRenders` removes scratch
directories abandoned by a process that died, and `usage()` produces the numbers the Settings screen shows.

Nothing is stored outside the app's own directory except a file the user explicitly saved through the system
picker, and no storage permission is needed for any of it.

## The press, and why it is durable

A press is a `RenderPipeline.render(...)` call that runs inside a WorkManager foreground worker
(`RenderWorker`, scheduled by `RenderScheduler`). Concretely:

1. The sources are opened and the storage estimate is checked **before** any work starts, so a full disk is a
   sentence rather than a failure three minutes in.
2. The DSP runs block by block into `cache/render/{jobId}/master.wav` — never into memory, so a ten-minute
   record costs the same as a ten-second one.
3. The finished file is re-opened with `WavCodec.Reader`, decoded, measured and validated. Missing, too
   small, wrong length, silent, clipped and undecodable are all failures with distinct messages.
4. The artwork is drawn and the waveform extracted.
5. The master is moved into place with an atomic rename.
6. Only then does `RecordRepository.commitRender` write the paths and the completed state.

The worker is a foreground service with a `dataSync` type, showing a notification with progress, cancel and
open. If the process is killed, the record stays in `queued` or `rendering`; the startup sweep marks it
`failed` with "The press was interrupted" and keeps its source, so pressing it again is one tap. A record is
never marked complete by a process that did not finish.

`RenderPreflight` runs before the press from the UI: a recipe whose high-pass crosses its low-pass, a
background that is switched off, a record longer than ten minutes — each is a sentence the user sees, at one
of three levels, before anything starts.

## State, and how a screen gets it

Compose + `StateFlow`. Each screen has a `ViewModel` that exposes one immutable `UiState` built from
repository flows, and the composables are stateless functions of that state. `VynylGraph` is created once in
`VynylApplication` and handed down through `LocalVynylGraph`; there is no DI framework because there is
exactly one graph, no variants and nothing to swap at runtime.

Navigation is `navigation-compose` with four bottom-bar destinations (Studio, Sound Lab, Master Vault,
Settings) plus `studio/{recordId}` and `player/{recordId}`. The 3D player is pushed *from* a record rather
than sitting in the bar. Onboarding stands alone: a first-run user has nothing to navigate to.

## The 3D player

`RecordPlayerController` owns an `ExoPlayer`, a `TurntableAnimator` and the camera, and it hands the screen a
`DeckPose` per frame. `TurntableSurface` is a thin `AndroidView` around a `GLSurfaceView` that draws that
pose. Audio starts at the moment the needle touches the groove, not when the user presses play, and pausing
holds the needle in the groove rather than lifting it — the record stays where it was. Details, and the
fallback for devices without GL ES 3.0, are in [THREE_D_PLAYER.md](THREE_D_PLAYER.md).

## Security and privacy

* `AppLock` wraps `BiometricPrompt` with `BIOMETRIC_STRONG | DEVICE_CREDENTIAL` — biometrics where they exist,
  the device PIN otherwise, with a configurable timeout and an explicit "unavailable" state rather than a
  screen that cannot be passed.
* The database is the app's own file in the app's own sandbox; there is no export of it and no sync.
* A `.vynyl` bundle is untrusted input. `VynylBundle.read` refuses entry names that are not safe, checks
  every entry against a CRC32 and SHA-256 checksum list, refuses a bundle from a newer format version, and
  enforces per-entry (160 MB) and total (400 MB) size caps. A failed import removes the directory it was
  extracting into.
* No file the app writes is world-readable; sharing goes through `FileProvider` with a single granted URI.
* `android:allowBackup="false"` and `android:fullBackupContent="false"`: the system will not copy the
  library anywhere. Backup is an explicit action the user takes, into a folder they choose.

See [PRIVACY.md](PRIVACY.md).

## The design system

`core/design` holds the palette, the type scale, the components, the icon set and the haptics. The palette is
exactly twelve colours on an obsidian ground with brass borders; headings are serif, durations are monospace,
bodies are sans. Nothing is purple, nothing is glass, nothing is neon, and every interactive target is at
least 48 dp. `VynylTheme` also wires the system-level state: dark status and navigation bars, an accent that
matches the amber, and reduced-motion support that the animator honours.

Haptics are `Haptics.Moment` — record start, pause, preset change, needle drop, complete, error — and
nothing else. A haptic you feel on every tap stops being information.

## Accessibility

TalkBack labels on every icon-only control and every record row; a 2D static deck as the fallback when GL ES
3.0 is missing or "reduced motion" is on; text that scales with the system font size; contrast checked in
`VynylThemeTest` against the WCAG ratio; a focus order that follows the reading order; and hardware keyboard
support through standard focusable components.

## Testing

* `app/src/androidTest` (a device or emulator): the database against real SQLite, the whole press end to end,
  the manifest and the shipped copy, export through the real FileProvider, backup and restore, and the
  navigation shell composed for real.
* `app/src/test` (JVM, no Android): the DSP stage by stage, the full chain over audio fixtures, the models and
  the preset gradient, JSON and recipe round trips, the storage layout and the bundle reader, the mesh
  builders, the camera clamps and the deck's animation.
* `app/src/androidTest` (device): Room's real behaviour, the repository's file-and-row ordering and its
  startup sweep, a full press through the pipeline including MediaCodec, a bundle round trip into a second
  library, the shader/uniform contract, the manifest's permission set, and the onboarding flow.
* `tools/kotlincheck.py` and `tools/check_meshes.py` are the static checks that run in an environment without
  an SDK. See the README for exactly what they prove and what they do not.

## Build configuration

One Gradle version catalog (`gradle/libs.versions.toml`), one module, `minSdk 26` (Android 8.0),
`compileSdk`/`targetSdk 35`, Java/Kotlin 17, KSP for Room with exported schemas in `app/schemas`. No ABI
splits, no NDK, no flavours. The release build enables R8 and resource shrinking; the debug build is
unminified for readable stack traces.

## Known limitations

* **Not built here.** No JDK or Android SDK was available in the environment where this application was
  written, so the Gradle build, the test suites and the APK have not been observed. See the README's
  verification section.
* **Playback tests need a device.** The 3D player and Media3 are exercised through their state machines in
  unit tests, but a real audio output and a real GPU are only available in instrumentation tests on hardware.
* **M4A depends on the device encoder.** The AAC encoder is the platform's; a device without an AAC encoder
  fails the M4A export with a message instead of producing a broken file, and WAV remains available.
* **One locale ships.** All copy is English. The strings are in `strings.xml` and nothing is hard-coded in a
  layout, so a translation is additive.
* `RenderLimits.MAX_RECORD_MS` is ten minutes, chosen so a record still finishes in seconds and a `.vynyl`
  bundle stays shareable.
