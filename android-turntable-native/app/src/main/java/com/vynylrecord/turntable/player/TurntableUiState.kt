package com.vynylrecord.turntable.player

import com.vynylrecord.turntable.graphics.CameraPreset
import com.vynylrecord.turntable.graphics.RenderQuality
import com.vynylrecord.turntable.graphics.animation.VisualPhase
import com.vynylrecord.turntable.model.PlatterSpeed
import com.vynylrecord.turntable.model.RecordMetadata
import com.vynylrecord.turntable.model.VinylStyle

/** Frame statistics for the debug overlay. Cheap to copy, updated a few times a second. */
data class FrameStatistics(
    val fps: Float = 0f,
    val drawCalls: Int = 0,
    val triangles: Int = 0,
    /** MSAA samples the driver actually granted; 0 means the composite resolve is doing the work. */
    val msaaSamples: Int = 0,
) {
    val formattedFps: String get() = if (fps > 0f) "%4.1f".format(fps) else "--"
}

/**
 * Everything the UI needs to draw itself, as one immutable value.
 *
 * The renderer is *not* described here beyond the few facts the interface needs (which quality is
 * running, whether the scene started). Compose recomposes when this changes, and it only changes
 * when something a person can see has changed — not once per frame.
 */
data class TurntableUiState(
    val hasRecord: Boolean = false,
    /** The user has asked for playback but the stylus has not landed yet. */
    val playRequested: Boolean = false,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val isCompleted: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val sourceName: String = "",
    val metadata: RecordMetadata = RecordMetadata.DEFAULT,
    val vinylStyle: VinylStyle = VinylStyle.DEFAULT,
    val speed: PlatterSpeed = PlatterSpeed.THIRTY_THREE,
    val visualPhase: VisualPhase = VisualPhase.IDLE,
    val quality: RenderQuality = RenderQuality.DEFAULT,
    val qualityChosenByUser: Boolean = false,
    val cameraPreset: CameraPreset = CameraPreset.HERO,
    val reducedMotion: Boolean = false,
    val isEs3Available: Boolean = true,
    val errorMessage: String? = null,
    /** Renderer summary or failure reason; shown in the debug overlay, never to the user otherwise. */
    val glDescription: String? = null,
    val frameStatistics: FrameStatistics = FrameStatistics(),
) {

    /** Normalised position, safe with an unknown duration. */
    val progress: Float
        get() = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    val elapsedLabel: String get() = formatTime(positionMs)

    val remainingLabel: String get() = "-" + formatTime((durationMs - positionMs).coerceAtLeast(0L))

    /** True while the mechanism is between "pressed play" and "in the groove". */
    val isStarting: Boolean
        get() = visualPhase == VisualPhase.PLATTER_STARTING ||
            visualPhase == VisualPhase.TONEARM_MOVING ||
            visualPhase == VisualPhase.NEEDLE_LOWERING

    /** True while a record is on the platter and the transport can be driven. */
    val canPlay: Boolean get() = hasRecord && errorMessage == null

    /** True when there is something to scrub through. */
    val canSeek: Boolean get() = hasRecord && durationMs > 0L && errorMessage == null

    /** True when the scene should show the deck as running (platter turning, lamp lit). */
    val showAsPlaying: Boolean
        get() = isPlaying || isStarting || visualPhase == VisualPhase.SEEKING

    /** Short status line: also the polite live region for screen readers. */
    val phaseLabel: String
        get() = when {
            errorMessage != null -> "Playback problem"
            // The controller marks the side finished the moment the transport ends; the mechanism
            // then takes a couple of seconds to lift the arm, so the transport's own flag is the
            // more useful thing to report.
            isCompleted && visualPhase != VisualPhase.PLAYING -> "This side has finished"
            visualPhase == VisualPhase.LOADING_RECORD -> "Loading the record"
            visualPhase == VisualPhase.RECORD_SETTLING -> "Settling the record on the platter"
            visualPhase == VisualPhase.PLATTER_STARTING -> "Platter spinning up"
            visualPhase == VisualPhase.TONEARM_MOVING -> "Arm moving into the lead-in"
            visualPhase == VisualPhase.NEEDLE_LOWERING -> "Lowering the stylus"
            visualPhase == VisualPhase.PLAYING -> "Playing"
            visualPhase == VisualPhase.PAUSED -> "Paused"
            visualPhase == VisualPhase.SEEKING -> "Seeking"
            visualPhase == VisualPhase.NEEDLE_LIFTING -> "Lifting the stylus"
            visualPhase == VisualPhase.TONEARM_RETURNING -> "Returning the arm"
            visualPhase == VisualPhase.PLATTER_STOPPING -> "Platter slowing down"
            visualPhase == VisualPhase.COMPLETED -> "This side has finished"
            visualPhase == VisualPhase.ERROR -> "Playback problem"
            else -> if (hasRecord) "Ready" else "No record loaded"
        }

    /**
     * One-shot announcement for accessibility.
     *
     * Position is deliberately absent: reading the timecode continuously is the classic way to make
     * a media app unusable with TalkBack on.
     */
    val announcement: String?
        get() = when {
            errorMessage != null -> errorMessage
            isCompleted || visualPhase == VisualPhase.COMPLETED -> "This side has finished"
            isPlaying && visualPhase == VisualPhase.PLAYING -> "Playing"
            visualPhase == VisualPhase.PAUSED -> "Paused"
            else -> null
        }

    val speedLabel: String get() = if (speed == PlatterSpeed.FORTY_FIVE) "45 rpm" else "33⅓ rpm"

    val qualityLabel: String get() = quality.displayName

    companion object {
        /** `m:ss`, or `h:mm:ss` past an hour. Negative values clamp to zero. */
        fun formatTime(milliseconds: Long): String {
            val totalSeconds = (milliseconds.coerceAtLeast(0L)) / 1000L
            val seconds = totalSeconds % 60L
            val minutes = (totalSeconds / 60L) % 60L
            val hours = totalSeconds / 3600L
            return if (hours > 0L) {
                "%d:%02d:%02d".format(hours, minutes, seconds)
            } else {
                "%d:%02d".format(minutes, seconds)
            }
        }
    }
}
