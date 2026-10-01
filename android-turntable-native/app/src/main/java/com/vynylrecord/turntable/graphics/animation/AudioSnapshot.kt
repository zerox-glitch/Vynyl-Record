package com.vynylrecord.turntable.graphics.animation

/**
 * Allocation-free playback snapshot handed to the animator every frame.
 *
 * The animator never touches Media3: the controller writes this immutable-in-practice struct on
 * its own thread (fields are volatile) and the GL thread reads it, so there is one direction of
 * data flow and no locking on the render path.
 */
class AudioSnapshot {
    @Volatile @JvmField var isPrepared: Boolean = false
    @Volatile @JvmField var isPlaying: Boolean = false
    @Volatile @JvmField var isBuffering: Boolean = false
    @Volatile @JvmField var isCompleted: Boolean = false
    @Volatile @JvmField var hasError: Boolean = false
    @Volatile @JvmField var positionMs: Long = 0L
    @Volatile @JvmField var durationMs: Long = 0L

    /** Normalised playback progress in `[0, 1]`; 0 while duration is unknown. */
    val progress: Float
        get() {
            val duration = durationMs
            if (duration <= 0L) return 0f
            val ratio = positionMs.toDouble() / duration.toDouble()
            return ratio.coerceIn(0.0, 1.0).toFloat()
        }

    /**
     * Bulk update from the player thread. Primitive arguments keep this file free of any dependency
     * on the audio package, so the animator can be unit-tested with nothing but a struct.
     */
    fun set(
        prepared: Boolean,
        playing: Boolean,
        buffering: Boolean,
        completed: Boolean,
        error: Boolean,
        positionMsValue: Long,
        durationMsValue: Long,
    ) {
        isPrepared = prepared
        isPlaying = playing
        isBuffering = buffering
        isCompleted = completed
        hasError = error
        positionMs = positionMsValue
        durationMs = durationMsValue
    }

    fun clear() {
        isPrepared = false
        isPlaying = false
        isBuffering = false
        isCompleted = false
        hasError = false
        positionMs = 0L
        durationMs = 0L
    }
}
