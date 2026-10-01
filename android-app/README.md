# Vynyl Record — native Android application

**Vynyl Record** (subtitle: *Digital Wax Preserver*) is a complete, offline Android application that turns a
voice recording into a vinyl record. You record a few seconds of someone you love — a grandparent, a
partner, a friend moving away — write a dedication, choose how worn the pressing should sound, and the app
presses it into wax on the phone itself. The result lives in a local **Master Vault**, plays on a 3D
turntable, and can be exported as audio, artwork or a portable `.vynyl` bundle.

It is one app for one local device owner. There is no server, no account, no upload, no analytics, and no
internet permission in the manifest: **the installed app works in airplane mode**, permanently.

```
android-app/
├── app/                        the application module
│   ├── src/main/java/com/vynylrecord/app/
│   │   ├── core/               model, data, storage, audio, graphics, design
│   │   └── feature/            onboarding, studio, soundlab, library, player, settings
│   ├── src/test/               unit tests (JVM): DSP, models, JSON, storage, geometry
│   └── src/androidTest/        instrumentation tests: Room, repository, real press, shaders, UI
├── gradle/libs.versions.toml   the only place a version is written
├── tools/                      static checkers and the bundled-asset generator
├── README.md                   this file
├── ARCHITECTURE.md             how the app is put together
├── AUDIO_ENGINE.md             the signal chain, the presets, the formats
├── THREE_D_PLAYER.md           the GL ES 3.0 turntable
├── PRIVACY.md                  what the app does and does not do with your data
└── LICENSES.md                 everything that ships, and where it came from
```

## Build it

Requirements: **JDK 17**, the **Android SDK with platform 35 and build-tools**, and nothing else. There is
no native code, no NDK, no code generation step beyond Room's KSP processor, and no dependency that needs a
network connection at runtime.

```bash
cd android-app
./gradlew assembleDebug     # debug APK
./gradlew test              # JVM unit tests (DSP, models, storage, geometry)
./gradlew lint              # Android lint
./gradlew installDebug      # to a connected device or emulator
```

The debug APK lands at:

```
android-app/app/build/outputs/apk/debug/app-debug.apk
```

Debug builds are signed with the standard Android debug keystore (`~/.android/debug.keystore`), created
automatically by the Android SDK on first use. No signing credentials, keystore or account are needed to
build, install or use this app, and none is checked into this repository.

> **A note on verification.** This application was written in an environment with no JDK and no Android
> SDK, so `./gradlew assembleDebug`, `./gradlew test`, `./gradlew lint` and the APK itself could not be
> produced or executed there. What *was* verified is recorded honestly in "What has been verified" below.
> Nothing in this repository claims a build result that was not observed.

## What has been verified

Static verification, re-run after every change (`tools/`, Python 3, no Android SDK needed):

| Check | Command | Result |
| --- | --- | --- |
| Every Kotlin file parses | `python3 tools/kotlincheck.py` | 98 files, 5 239 declarations — no syntax errors |
| Every import and `R.` reference resolves to something in the source set | same | 0 unknown imports, 0 unknown resources |
| No unused imports | same | 0 |
| Every member call on a project type names a member that type declares | same | 0 unknown members |
| A symbol used from another package always has its import | `python3 tools/importprojectcheck.py` | 0 missing, 255 top-level names |
| Every mesh builder produces outward-facing, in-range geometry | `python3 tools/check_meshes.py` | 10/10 builders |
| Every string is declared once, used, and resolvable | `python3 tools/check_strings.py` | 88 declared, 88 referenced, 0 unused |

The import checker exists because of the environment: with no compiler, a forgotten import is invisible to
every other scan here, and two real ones were found and fixed this way — a `preferencesDataStore` delegate
that was referenced but never declared, and a `WaveformFile` import naming the wrong package. It also holds
a table of Compose and AndroidX extensions (`stringResource`, `collectAsStateWithLifecycle`, `viewModel`,
`AndroidView`, …) that must be imported to compile.

A green run of the member checker is *advisory*: it cannot see a call chained directly onto a constructor
(`Sample().missingMember()`), and it does not type-check. It exists to catch the mistakes that a compiler
would catch, in an environment where there is no compiler.

Not verified here, and to be run on a machine with the SDK: the Gradle build, the 35 test files (24 unit and
11 instrumented, 370 `@Test` methods between them), Android lint, and the debug APK. Those tests were
written against the source and are expected to need tolerance adjustments on first run — the audio
thresholds in particular are honest estimates until a real render has been measured on real hardware.

## The first five minutes

1. **Onboarding** — three pages, once: what the app is (a voice they can return to), that everything is
   pressed locally, and that records are yours to keep. `Skip` is on every page.
2. **Studio** — press *Record*, speak. You can pause and resume: a pause is a pause, not a new take. When
   you stop, the take is written into the app's own storage *before* anything else can fail, so a
   half-finished record still has the voice in it. You can also **import** an existing audio file instead
   of recording.
3. **Dedication and metadata** — title, recipient, sender, occasion, date, side labels, and the dedication
   line that is printed on the label and the artwork.
4. **The sound** — pick one of five pressings, or move any of the fourteen knobs. In the **Sound Lab**,
   enable or trim a bundled background bed, or import your own.
5. **Press it.** The render runs in a foreground worker with a notification ("Pressing your record" with
   progress, cancel and open), so you can navigate away, browse the Vault, or lock the phone.
6. **The Master Vault** — grid or list, search, filter by occasion / preset / style / favourite / state,
   sort by newest, oldest, title, duration or last played. Play, edit, re-render, duplicate, favourite,
   export, share, delete (with confirmation and undo).
7. **The 3D turntable** — the record drops onto the platter, the needle lowers at exactly the moment the
   audio starts, the arm tracks the playhead, and the lamp breathes with the music. Orbit and pinch;
   double-tap to reset; four camera presets.
8. **Export** — M4A, WAV, cover artwork, or a `.vynyl` bundle that carries the master, optionally the
   source, the metadata, the preset, the vinyl style and checksums. Share through the Android Sharesheet,
   or back the whole shelf up to a folder you choose.

## What it does not do

* No network calls, no accounts, no sign-in, no cloud, no analytics, no crash reporting.
* No storage permission of any kind: import and export use the system file picker (SAF), and sharing goes
  through a `FileProvider` that grants a single URI.
* No audio ever lives in the database. Room stores metadata only.
* No simulated progress: a record is marked complete only after the file on disk has been re-opened,
  decoded, measured and validated.

See [PRIVACY.md](PRIVACY.md) for the full statement and the exact permission list.

## Where to read next

* [ARCHITECTURE.md](ARCHITECTURE.md) — layers, data model, storage, lifecycle, testing.
* [AUDIO_ENGINE.md](AUDIO_ENGINE.md) — every DSP stage, every preset value, the two output formats.
* [THREE_D_PLAYER.md](THREE_D_PLAYER.md) — meshes, materials, the shader contract, the animation state
  machine, and the 2D fallback.
