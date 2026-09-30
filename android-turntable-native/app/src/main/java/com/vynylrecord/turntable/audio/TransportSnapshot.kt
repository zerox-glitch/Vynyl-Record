package com.vynylrecord.turntable.audio

/**
 * Immutable, Android-free view of the audio transport.
 *
 * Kept out of [AudioEngine] on purpose: the controller's state projection and the unit tests that
 * cover it only need this struct, so nothing in the test path drags Media3 in.
 */
data class TransportSnapshot(
    val hasMedia: Boolean = false,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val isCompleted: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val errorMessage: String? = null,
    val sourceName: String = "",
) {
    /** Normalised playback progress in `[0, 1]`; 0 while the duration is unknown. */
    val progress: Float
        get() = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    /** Remaining time in milliseconds, never negative. */
    val remainingMs: Long
        get() = if (durationMs > 0L) (durationMs - positionMs).coerceAtLeast(0L) else 0L
}
