package com.vynylrecord.app.core.playback

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.vynylrecord.app.core.graphics.DeckPose
import com.vynylrecord.app.core.graphics.DeckState
import com.vynylrecord.app.core.graphics.TurntableAnimator
import com.vynylrecord.app.core.graphics.TurntableCamera
import com.vynylrecord.app.core.model.Record
import com.vynylrecord.app.core.model.VinylStyleId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * The turntable's transport: one record, one deck, one needle.
 *
 * This class holds the three things that have to agree for the 3D player to be honest — the audio player,
 * the deck's animation and the camera — and it is the only place where they meet.
 *
 * ## The needle rule
 *
 * Sound starts when the needle touches the record, not when the button is pressed. That is not a flourish:
 * a listener who sees the arm descend and hears audio a moment later has understood the object, and one who
 * hears a click and *then* sees the arm move has been shown a mockup. So [play] does not call the audio
 * player at all — it moves the deck into [DeckState.CUEING], and the audio starts from [onFrame] on the
 * frame where the stylus reaches the groove.
 *
 * The reverse is also true: pausing keeps the needle down, because lifting it would put a thump at each end
 * of the pause.
 *
 * ## Frames
 *
 * [onFrame] is called from the Compose frame loop with real elapsed seconds. Everything time-dependent —
 * the platter's inertia, the arm's damping, the lamp, the position read from the audio player — is advanced
 * here, so the deck runs at exactly the speed the audio does whatever the device's refresh rate is.
 */
class RecordPlayerController(private val context: Context) : RecordPlayer {

    /** Everything the player screen and its controls read. */
    data class UiState(
        val recordId: String? = null,
        val title: String = "",
        val subtitle: String = "",
        val isPlaying: Boolean = false,
        val isPrepared: Boolean = false,
        val isBuffering: Boolean = false,
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
        val deckState: DeckState = DeckState.IDLE,
        val error: String? = null,
    ) {
        val progress: Float
            get() = if (durationMs <= 0L) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

        val positionLabel: String get() = formatTime(positionMs)

        val durationLabel: String get() = formatTime(durationMs)

        private fun formatTime(milliseconds: Long): String {
            val total = (milliseconds / 1000L).coerceAtLeast(0L)
            return "%d:%02d".format(total / 60L, total % 60L)
        }
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    val animator = TurntableAnimator()
    val camera = TurntableCamera()

    /** Bumped whenever the label's text has to be redrawn; the renderer compares it, cheaply. */
    var metadataRevision: Long = 0L
        private set

    private var player: ExoPlayer? = null
    private var current: Record? = null
    private var style: VinylStyleId = VinylStyleId.DEFAULT
    private var pendingStart = false
    private var selectedPresetIndex = 0

    /** Loads a record and cues it, without starting playback. */
    fun loadRecord(record: Record, masterFile: File): Boolean {
        val existing = current
        if (existing?.id == record.id && player != null && _state.value.isPrepared) {
            setMetadata(record)
            return true
        }
        release()
        current = record
        metadataRevision++
        style = record.styledId

        if (!masterFile.isFile || masterFile.length() <= 44L) {
            _state.value = UiState(recordId = record.id, title = record.displayTitle, error = "This record's audio is missing.")
            animator.setState(DeckState.ERROR)
            return false
        }

        val exoPlayer = try {
            ExoPlayer.Builder(context)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                        .build(),
                    // A record player stops when the headphones are pulled out. Anything else would keep
                    // playing into the room, which is not what a deck does.
                    true,
                )
                .setHandleAudioBecomingNoisy(true)
                .build()
                .also { it.addListener(listener) }
        } catch (error: Exception) {
            Log.w(TAG, "the audio player could not be created", error)
            _state.value = UiState(recordId = record.id, title = record.displayTitle, error = "Audio could not be started on this device.")
            animator.setState(DeckState.ERROR)
            return false
        }

        player = exoPlayer
        exoPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(masterFile)))
        exoPlayer.prepare()
        animator.setPosition(0f)
        animator.setState(DeckState.LOADING, immediate = false)
        _state.value = UiState(
            recordId = record.id,
            title = record.displayTitle,
            subtitle = record.signatureLine.ifBlank { record.dedicationLine },
            durationMs = record.durationMilliseconds,
            deckState = DeckState.LOADING,
        )
        return true
    }

    /** Presses play: the needle lowers, and the audio starts when it lands. */
    fun play() {
        val player = player ?: return
        if (player.playbackState == Player.STATE_IDLE) return
        pendingStart = true
        val position = animator.position
        animator.setPosition(position)
        animator.setState(DeckState.CUEING)
    }

    /** Pauses, leaving the needle on the record. */
    fun pause() {
        pendingStart = false
        player?.pause()
        animator.setState(DeckState.PAUSED)
    }

    fun toggle() {
        if (_state.value.isPlaying) pause() else play()
    }

    /** Seeks by fraction, which is what a drag on the waveform produces. */
    fun seekTo(fraction: Float) {
        val player = player ?: return
        val duration = player.duration.takeIf { it > 0L } ?: current?.durationMilliseconds ?: return
        val target = (duration * fraction.coerceIn(0f, 1f)).toLong()
        player.seekTo(target)
        animator.setPosition(fraction)
        if (player.isPlaying) animator.setState(DeckState.SEEKING)
    }

    /** Lifts the needle and parks the arm, without unloading the record. */
    fun stop() {
        pendingStart = false
        player?.pause()
        player?.seekTo(0L)
        animator.setPosition(0f)
        animator.setState(DeckState.LIFTING)
    }

    fun setVinylStyle(style: VinylStyleId) {
        if (this.style == style) return
        this.style = style
        metadataRevision++
        // The style travels with the record once the user changes it here.
        current?.let { record ->
            current = record.copy(styledId = style)
        }
    }

    fun setMetadata(record: Record) {
        current = record
        metadataRevision++
        _state.value = _state.value.copy(
            recordId = record.id,
            title = record.displayTitle,
            subtitle = record.signatureLine.ifBlank { record.dedicationLine },
        )
    }

    fun resetCamera() {
        camera.reset(immediate = animator.reducedMotion)
    }

    fun setCameraPreset(index: Int) {
        val presets = TurntableCamera.Preset.ordered
        selectedPresetIndex = ((index % presets.size) + presets.size) % presets.size
        camera.applyPreset(presets[selectedPresetIndex], immediate = animator.reducedMotion)
    }

    fun cycleCameraPreset() {
        val preset = camera.nextPreset(immediate = animator.reducedMotion)
        selectedPresetIndex = TurntableCamera.Preset.ordered.indexOf(preset)
    }

    val cameraPresetLabel: String get() = TurntableCamera.Preset.ordered[selectedPresetIndex].label

    fun setAutoOrbit(enabled: Boolean) = camera.setAutoOrbit(enabled)

    fun setReducedMotion(reduced: Boolean) {
        animator.reducedMotion = reduced
        camera.setAutoOrbit(false)
    }

    /** The record currently loaded, for the label texture and the metadata panel. */
    val record: Record? get() = current

    val currentStyle: VinylStyleId get() = style

    /**
     * Advances one frame.
     *
     * @param deltaSeconds real elapsed time
     * @param userIsTouchingCamera set while a gesture is in progress
     * @return the pose to hand to the renderer
     */
    fun onFrame(deltaSeconds: Float, userIsTouchingCamera: Boolean = false): DeckPose {
        val player = player
        if (player != null) {
            val position = player.currentPosition
            val duration = player.duration
            val fraction = if (duration > 0L) position.toFloat() / duration else 0f
            // Only the audio player moves the arm during playback; a seek sets it directly.
            if (_state.value.isPlaying) {
                animator.setPosition(fraction)
            }
            val isEnded = player.playbackState == Player.STATE_ENDED
            if (isEnded && animator.state == DeckState.PLAYING) {
                animator.setState(DeckState.ENDED)
            }
            _state.value = _state.value.copy(
                isPlaying = player.isPlaying,
                isPrepared = player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_ENDED,
                isBuffering = player.playbackState == Player.STATE_BUFFERING,
                positionMs = position,
                durationMs = if (duration > 0L) duration else _state.value.durationMs,
            )
        }

        val pose = animator.update(deltaSeconds)

        // The needle lands, and only now does the audio start.
        if (pendingStart && animator.needleIsDown && animator.state == DeckState.CUEING) {
            pendingStart = false
            animator.setState(DeckState.PLAYING)
            player?.play()
        }

        // A record that has been lifted is parked once the arm has finished travelling.
        if (animator.state == DeckState.LIFTING && animator.currentPose.needleContact <= 0.001f) {
            animator.setState(DeckState.RETURNING)
        } else if (animator.state == DeckState.RETURNING && pose.armLift <= 0.055f && pose.armSwing < 0.01f) {
            animator.setState(DeckState.READY)
        }

        _state.value = _state.value.copy(deckState = animator.state)
        return pose
    }

    /** Pauses everything while the screen is not visible, without losing the position. */
    fun onHidden() {
        pendingStart = false
        player?.pause()
        animator.setState(DeckState.SLEEPING)
    }

    fun onVisible() {
        if (player != null) animator.setState(if (pendingStart) DeckState.CUEING else DeckState.READY)
    }

    /** Drains the deck's state into a per-record "last played" timestamp; called when a record ends. */
    fun positionFraction(): Float = animator.position

    fun release() {
        pendingStart = false
        player?.removeListener(listener)
        player?.release()
        player = null
        current = null
        animator.setState(DeckState.IDLE, immediate = true)
        _state.value = UiState()
    }

    private val listener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "playback failed", error)
            _state.value = _state.value.copy(
                isPlaying = false,
                error = "This record could not be played: ${error.errorCodeName}",
                deckState = DeckState.ERROR,
            )
            animator.setState(DeckState.ERROR)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                animator.setState(DeckState.ENDED)
            }
            _state.value = _state.value.copy(
                isPrepared = playbackState == Player.STATE_READY || playbackState == Player.STATE_ENDED,
                isBuffering = playbackState == Player.STATE_BUFFERING,
            )
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.value = _state.value.copy(isPlaying = isPlaying)
        }
    }

    private companion object {
        const val TAG = "VynylPlayerController"
    }
}

/** An interface the player screen depends on, so a test can drive it without an audio device. */
interface RecordPlayer {
    val state: StateFlow<RecordPlayerController.UiState>
    fun loadRecord(record: Record, masterFile: File): Boolean
    fun play()
    fun pause()
    fun seekTo(fraction: Float)
    fun stop()
    fun release()
}
