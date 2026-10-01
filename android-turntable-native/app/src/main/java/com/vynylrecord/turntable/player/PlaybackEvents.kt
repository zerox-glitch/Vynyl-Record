package com.vynylrecord.turntable.player

/**
 * Transport events pushed by the audio backend.
 *
 * Deliberately free of Media3 types: the controller implements this interface, and the JVM unit
 * tests drive the controller through it without a device.
 *
 * Every callback arrives on the thread that owns the player — for this app, the main thread.
 */
interface PlaybackEvents {

    /** Audio genuinely started or stopped, including stops the app did not ask for. */
    fun onPlayingChanged(isPlaying: Boolean)

    /** The side finished playing naturally. */
    fun onPlaybackCompleted()

    /** Playback failed. [message] is already phrased for a person. */
    fun onPlaybackError(message: String)
}
