package com.vynylrecord.turntable.audio

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.vynylrecord.turntable.player.PlaybackBackend
import com.vynylrecord.turntable.player.PlaybackEvents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Local audio transport, built on Media3's `ExoPlayer`.
 *
 * Ported from the web app's audio layer in spirit, not in code: the Vynyl deck only ever plays
 * **app-private files**. There is no streaming source, no `INTERNET` permission and no network code
 * path — [load] refuses anything that is not a `file://` or `content://` URI, so a stray remote URL
 * fails loudly instead of quietly reaching the network.
 *
 * Responsibilities:
 *
 *  * decode a local file and expose play/pause/seek/replay,
 *  * report progress, duration, completion and errors through [TransportSnapshot]/[PlaybackBackend],
 *  * hold audio focus and release it on pause or loss, and pause when headphones are unplugged.
 *
 * The engine is single-threaded: it is created, driven and released on the main thread, exactly as
 * `ExoPlayer` requires. The renderer never touches it; it only ever sees the immutable
 * [TransportSnapshot] the controller hands to it.
 */
@OptIn(UnstableApi::class)
class AudioEngine(private val context: Context) : PlaybackBackend {

    private var player: ExoPlayer? = null
    private var listener: PlaybackEvents? = null
    private var released = false

    @Volatile private var loadedFile: File? = null
    @Volatile private var lastError: String? = null

    override val isReady: Boolean
        get() = !released

    /** Installs the callback sink. Passing `null` detaches it. */
    fun setListener(listener: PlaybackEvents?) {
        this.listener = listener
    }

    private fun playerOrCreate(): ExoPlayer {
        player?.let { return it }
        val created = ExoPlayer.Builder(context).build()
        created.setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_MEDIA)
                .build(),
            /* handleAudioFocus = */ true,
        )
        created.setHandleAudioBecomingNoisy(true)
        created.repeatMode = Player.REPEAT_MODE_OFF
        created.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                listener?.onPlayingChanged(isPlaying)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) listener?.onPlaybackCompleted()
            }

            override fun onPlayerError(error: PlaybackException) {
                val message = friendlyMessage(error)
                lastError = message
                Log.w(TAG, "Playback error: ${error.errorCodeName}", error)
                listener?.onPlaybackError(message)
            }
        })
        player = created
        return created
    }

    /**
     * Stages a bundled asset into app-private storage so the player reads a real file.
     *
     * Idempotent: an already-staged file with the same length is reused, so this is cheap on the
     * second launch and never leaves a half-written file behind for the player to trip over.
     */
    override suspend fun prepareBundledSource(assetPath: String): File? = withContext(Dispatchers.IO) {
        val name = assetPath.substringAfterLast('/')
        val directory = File(context.filesDir, DemoAudio.DIRECTORY)
        if (!directory.exists() && !directory.mkdirs()) {
            Log.e(TAG, "Could not create ${directory.absolutePath}")
            return@withContext null
        }
        val target = File(directory, name)
        val assetSize = try {
            context.assets.openFd(assetPath).use { it.length }
        } catch (error: Exception) {
            -1L
        }

        if (target.exists() && target.length() > 0L && (assetSize <= 0L || target.length() == assetSize)) {
            return@withContext target
        }

        val temporary = File(directory, "$name.part")
        try {
            context.assets.open(assetPath).use { input ->
                temporary.outputStream().use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
            }
            if (temporary.length() <= 0L) {
                temporary.delete()
                return@withContext null
            }
            if (target.exists() && !target.delete()) {
                Log.w(TAG, "Could not replace ${target.name}")
            }
            if (!temporary.renameTo(target)) {
                Log.e(TAG, "Could not stage ${target.name}")
                temporary.delete()
                return@withContext null
            }
            target
        } catch (error: Exception) {
            Log.e(TAG, "Failed to stage $assetPath", error)
            temporary.delete()
            null
        }
    }

    /**
     * Loads a local file. Returns false (and reports nothing) when the file is missing or empty, so
     * the caller can fall back to a demo sample or show an error.
     */
    override fun load(file: File, displayName: String): Boolean {
        if (released) return false
        if (!file.exists() || file.length() <= 0L) {
            Log.w(TAG, "Refusing to load ${file.absolutePath}: not a readable local file")
            return false
        }
        return loadUri(Uri.fromFile(file), displayName)
    }

    /**
     * Loads a `content://` URI handed over by the Storage Access Framework, or a `file://` URI.
     * Anything else is rejected: this app has no network playback path by design.
     */
    override fun loadUri(uri: Uri, displayName: String): Boolean {
        if (released) return false
        val scheme = uri.scheme?.lowercase()
        if (scheme != "file" && scheme != "content") {
            Log.w(TAG, "Rejected non-local audio source: $uri")
            return false
        }
        return try {
            val active = playerOrCreate()
            active.setMediaItem(MediaItem.fromUri(uri))
            active.prepare()
            lastError = null
            loadedFile = uri.path?.let { File(it) }
            true
        } catch (error: Exception) {
            Log.e(TAG, "Could not load $displayName", error)
            false
        }
    }

    override fun play() {
        if (released) return
        val active = player ?: return
        if (active.playbackState == Player.STATE_IDLE) return
        if (active.playbackState == Player.STATE_ENDED) active.seekTo(0)
        active.play()
    }

    override fun pause() {
        player?.pause()
    }

    override fun seekTo(positionMs: Long) {
        val active = player ?: return
        val duration = active.duration
        val clamped = if (duration > 0L) positionMs.coerceIn(0L, duration) else positionMs.coerceAtLeast(0L)
        active.seekTo(clamped)
    }

    override fun snapshot(): TransportSnapshot {
        val active = player ?: return TransportSnapshot(errorMessage = lastError)
        val duration = active.duration
        return TransportSnapshot(
            hasMedia = active.mediaItemCount > 0,
            isPlaying = active.isPlaying,
            isBuffering = active.playbackState == Player.STATE_BUFFERING,
            isCompleted = active.playbackState == Player.STATE_ENDED,
            positionMs = active.currentPosition.coerceAtLeast(0L),
            durationMs = if (duration == C.TIME_UNSET || duration < 0L) 0L else duration,
            errorMessage = lastError,
            sourceName = loadedFile?.name ?: "",
        )
    }

    override fun release() {
        if (released) return
        released = true
        listener = null
        player?.let {
            it.setHandleAudioBecomingNoisy(false)
            it.stop()
            it.release()
        }
        player = null
        loadedFile = null
    }

    /** Maps a Media3 error onto something a person can read, without leaking internals. */
    private fun friendlyMessage(error: PlaybackException): String = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "The record could not be found on this device."
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> "Vynyl cannot read that file any more."
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        -> "That file is not a recording Vynyl can play."

        PlaybackException.ERROR_CODE_DECODING_FAILED -> "This device cannot decode that recording."
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED -> "The audio output would not start."
        else -> "Playback stopped unexpectedly. Press play to try again."
    }

    companion object {
        private const val TAG = "VynylAudio"

        /** Bundled, locally generated demo pressing. Never a remote URL. */
        const val DEMO_ASSET = DemoAudio.ASSET_PATH

        /** Directory inside app-private storage that bundled audio is staged into. */
        const val DEMO_DIRECTORY = DemoAudio.DIRECTORY

        /** Default display name for the bundled demonstration pressing. */
        const val DEMO_DISPLAY_NAME = DemoAudio.DISPLAY_NAME
    }
}
