package com.vynylrecord.turntable.audio

/**
 * Where the bundled demo recording lives.
 *
 * The file is generated for this repository (see LICENSES.md - it is synthesised from sine
 * partials, filtered noise and crackle, with no third-party samples) and ships inside the APK.
 * Nothing here is ever fetched: the asset is copied into app-private storage on first launch and
 * played from there with `file://`.
 *
 * Kept in its own Android-free file so the controller can name the sample without pulling Media3
 * into the class graph of its unit tests.
 */
object DemoAudio {
    /** Asset path inside `app/src/main/assets`. */
    const val ASSET_PATH = "audio/demo-side-a.mp3"

    /** Directory inside app-private storage that bundled audio is staged into. */
    const val DIRECTORY = "audio"

    /** Human-readable name shown while the record is loaded. */
    const val DISPLAY_NAME = "demo-side-a.mp3"
}
