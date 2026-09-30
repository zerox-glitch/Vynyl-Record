# Integration guide

How to lift this module into the full Vynyl app (or anywhere else). The short version: copy seven
packages, add four dependencies, add one Compose call, and drive it through `TurntableController`.

Everything below assumes the source is at `android-turntable-native/` in this repository — the folder
URL to hand to a code assistant is
`https://github.com/zerox-glitch/Vynyl-Record/tree/main/android-turntable-native`.

---

## 1. Copy these packages

Namespace used here: `com.vynylrecord.turntable`. If you keep a different application ID, rename the
package prefix; nothing depends on the name.

| Package | Contents | Depends on |
| --- | --- | --- |
| `model` | `TurntableSpec`, `TonearmGeometry`, `Mat4`, `Vec3`, `MathUtils`, `Easing`, `VinylStyle`, `RecordMetadata`, `LabelTextLayout`, `LabelRenderer` | nothing but Kotlin |
| `graphics.geometry` | `Mesh`, `MeshBuilder`, `MeshFactory`, `TurntableBuilder` | `model` |
| `graphics.material` | `Material`, `MaterialLibrary`, `LabelTextureFactory` | `model`, Android `Canvas` |
| `graphics.gl` | `GlUtil`, `ShaderProgram`, `GpuMesh`, `OffscreenTargets`, `SimpleTextureTarget` | Android GLES30 |
| `graphics.animation` | `VisualPhase`, `AnimationConfig`, `AudioSnapshot`, `TurntableAnimator` | `model` |
| `graphics` | `TurntableScene`, `TurntableRenderer`, `TurntableSurface`, `CameraRig`, `CameraPreset`, `RenderQuality`, `SceneEnvironment`, `GlCapabilities`, `GraphicsEnvironment` | all of the above |
| `audio` | `AudioEngine`, `TransportSnapshot`, `DemoAudio` | Media3 |
| `player` | `TurntableController`, `TurntableUiState`, `TurntableContracts`, `PlaybackEvents`, `TurntableViewModel` | `audio`, `graphics` |
| `ui` | `VynylTurntable`, `StaticTurntableFallback`, `VynylTheme`, `VynylComponents`, `TurntableDemoScreen`, `VynylDemoRoot` | Compose |

Also copy `app/src/main/assets/shaders/` (5 files, required — the renderer loads them from assets by
name) and, if you want the demo recording, `app/src/main/assets/audio/demo-side-a.mp3`.

You can drop `ui/TurntableDemoScreen.kt`, `ui/VynylDemoRoot.kt`, `ui/VynylComponents.kt` and
`MainActivity.kt` if you are writing your own screen; `VynylTurntable` is the only UI file the player
needs, plus `VynylTheme`/`VynylComponents` for the fallback and the demo controls.

## 2. Dependencies

`gradle/libs.versions.toml` in this project pins the versions that were used:

```kotlin
implementation(platform("androidx.compose:compose-bom:2024.10.01"))
implementation("androidx.compose.ui:ui")
implementation("androidx.compose.material3:material3")
implementation("androidx.compose.ui:ui-tooling-preview")
implementation("androidx.core:core-ktx:1.15.0")
implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

// Local, app-private audio playback only. No streaming data sources are used.
implementation("androidx.media3:media3-exoplayer:1.5.1")
implementation("androidx.media3:media3-common:1.5.1")
```

Toolchain: AGP 8.7.3, Kotlin 2.0.21, Gradle 8.9, `compileSdk`/`targetSdk` 35, `minSdk` 26, Java 17.
`buildFeatures { buildConfig = true }` is required — `GlCapabilities.logStartupDiagnostics` and the
debug overlay read `BuildConfig.DEBUG`.

The tests need `junit:junit:4.13.2` and `org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0`; the
instrumentation tests need `androidx.test.ext:junit:1.2.1`, `androidx.test:core-ktx:1.6.1`,
`espresso-core:3.6.1` and `compose ui-test-junit4`.

## 3. Manifest entries

```xml
<!-- Optional, not required: the deck falls back to a 2D Compose rendering without ES 3.0. -->
<uses-feature android:glEsVersion="0x00030000" android:required="false" />

<application ...>
    <activity
        android:name=".MainActivity"
        android:exported="true"
        android:configChanges=""            <!-- deliberately empty: rotation is handled -->
        android:screenOrientation="unspecified" />
</application>
```

**No `INTERNET` permission, no other permission at all.** The module ships no networking code and no
remote assets; keep it that way. If you add a `MediaSessionService` later, that is the moment to
reconsider foreground-service permissions — nothing in the renderer changes.

## 4. Compose entry point

```kotlin
setContent {
    VynylTheme {
        val viewModel: TurntableViewModel = viewModel()
        val uiState by viewModel.controller.state.collectAsStateWithLifecycle()

        LaunchedEffect(Unit) { viewModel.loadDemoRecord() }   // or your own load call

        VynylTurntable(
            uiState = uiState,
            controller = viewModel.controller,
            modifier = Modifier.fillMaxSize(),
            onPlaybackCompleted = { /* advance to the next side, show a toast, … */ },
            onError = { message -> /* surface it in your own chrome */ },
        )
    }
}
```

`VynylTurntable` owns the `GLSurfaceView`, forwards gestures, mirrors the host lifecycle
(`ON_RESUME`/`ON_PAUSE`/`ON_STOP` start and stop the GL thread) and swaps itself for
`StaticTurntableFallback` when `uiState.isEs3Available` is false. You do not need to touch
`GLSurfaceView` directly.

If you host it yourself instead, remember three things: bind with `controller.attachRenderer(renderer)`,
detach on teardown, and call `TurntableSurface.pauseRendering()` when the view stops being visible.

## 5. Loading audio

Two supported paths, both local-only:

```kotlin
// 1. A file in app-private storage (recommended: copy once, then it is a plain file read).
controller.load(File(context.filesDir, "audio/pressing-01.mp3"), metadata)

// 2. A file:// or content:// URI, e.g. from the Storage Access Framework.
controller.loadUri(uri, displayName = "pressing-01.mp3", metadata = metadata)

// 3. The bundled demonstration pressing (staged into filesDir/audio on first use).
controller.loadBundled()
```

`AudioEngine` refuses any other scheme, so a `https://` URI cannot slip in through configuration.
Drop-in replacement: implement `PlaybackBackend` (six methods) if you want a service-backed player,
then pass it to `TurntableController(backend, scope, initialMetadata)`.

## 6. Metadata and the label

```kotlin
val metadata = RecordMetadata(
    title = "Golden Hour",            // required, non-blank
    recipient = "Ayesha",
    sender = "Hamza",
    side = RecordSide.A,
    date = "2026-09-30",              // optional
    catalogue = "VYN 001",            // optional
)
controller.load(file, metadata)
```

The label is drawn on an Android `Canvas` at the quality level's texture size (512/768/1024 px),
uploaded as a GL texture, and regenerated **only** when the wording or the vinyl style changes
(`RecordMetadata.rendersSameLabelAs`). Long titles are measured, wrapped to two balanced lines and
ellipsised by `LabelTextLayout`, which is unit-tested without a device.

`controller.setMetadata(...)` reprints; `controller.flipSide()` flips A/B; `controller.setVinylStyle(...)`
re-tints materials, the studio and the label paper together.

## 7. Observing state

```kotlin
controller.state.collect { state ->
    state.isPlaying        // audio is genuinely running
    state.playRequested    // the user asked; the stylus has not landed yet
    state.showAsPlaying    // what the UI should look like (includes spin-up and seeking)
    state.progress         // 0..1, safe when the duration is unknown
    state.elapsedLabel     // "1:04"
    state.remainingLabel   // "-2:31"
    state.phaseLabel       // "Lowering the stylus" — already human-readable
    state.announcement     // one-shot string for TalkBack; null when there is nothing to say
    state.errorMessage     // non-null after a failure; controller.clearError() to dismiss
}
```

`TurntableUiState` is immutable and only changes when something visible changes, so collecting it
does not cause per-frame recomposition.

### Controller API

```kotlin
controller.load(file, metadata)      controller.loadUri(uri, name, metadata)   controller.loadBundled()
controller.play()                    controller.pause()        controller.togglePlayPause()
controller.seekTo(ms)                controller.seekToProgress(0.35f)          controller.skipBy(-10_000L)
controller.replay()                                           controller.unload()
controller.setVinylStyle(style)      controller.setNextVinylStyle()            controller.setPreviousVinylStyle()
controller.setMetadata(metadata)     controller.flipSide()
controller.setSpeed(speed)           controller.toggleSpeed()
controller.setCameraPreset(preset)   controller.resetCamera()
controller.setQuality(quality)       controller.setReducedMotion(enabled)
controller.state                     controller.dispose()
```

`play()` does not start audio — it asks the mechanism to start. Audio begins only when the stylus
lands (`onNeedleContact`). This is a design guarantee, not an implementation detail: keep it if you
replace the backend.

## 8. Lifecycle and cleanup

| Event | What happens automatically | What you must do |
| --- | --- | --- |
| Rotation | GL surface is destroyed and rebuilt; the controller re-pushes everything | nothing, as long as the controller lives in a `ViewModel` |
| Background | `pauseRendering()` stops the GL thread; the EGL context is released | nothing |
| Navigating away | `onRelease` detaches the renderer and pauses the surface | nothing |
| Process death | app-private staging file survives; next launch reuses it | nothing |
| Long-lived playback in the background | not handled | move the player into a `MediaSessionService` and implement `PlaybackBackend` |
| Final teardown | — | `controller.dispose()` (releases the player, stops the ticker, parks the arm) |

Nothing holds an `Activity`, a `Context`, a `Bitmap` or an EGL object across a configuration change.
`GraphicsEnvironment`, `AudioEngine` and `TurntableController` are all created once in
`TurntableViewModel`; the renderer and the view belong to the composition and die with it.

## 9. Instrumenting the demo

`DemoTestTags` in `ui/TurntableDemoScreen.kt` gives stable handles for UI tests:

```kotlin
composeTestRule.onNodeWithTag(DemoTestTags.PLAY_PAUSE).performClick()
composeTestRule.onNodeWithTag(DemoTestTags.styleChip(VinylStyle.SMOKED_OBSIDIAN)).performClick()
```

`app/src/androidTest/java/com/vynylrecord/turntable/` has two examples: one opening the demo and
driving the transport, one asserting the static fallback renders and stays operable.

## 10. Things that will bite you

* **`unitTests.isReturnDefaultValues = true`** is set in `app/build.gradle.kts` so JVM tests can touch
  classes that reference `android.net.Uri`/`Log`. Keep it, or keep `PlaybackBackend` implementers free
  of Android types in their test path.
* **The shader assets are mandatory.** `TurntableScene.initialise` throws if one is missing (a build
  that strips assets will fall back to the static deck, not crash — but you lose the 3D deck).
* **Emulator GPUs** report ES 3.0 and frequently grant MSAA they cannot resolve; `OffscreenTargets`
  checks framebuffer completeness and downgrades, so the symptom is a softer image rather than a
  black screen.
* **Do not add an `INTERNET` permission** to "make the shaders load" — they come from
  `context.assets`.
* If you add a different pressing, keep it mono/stereo MP3 or AAC in app-private storage; Media3
  handles the rest.
