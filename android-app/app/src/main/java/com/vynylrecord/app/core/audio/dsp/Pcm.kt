package com.vynylrecord.app.core.audio.dsp

import java.io.Closeable
import kotlin.math.abs

/**
 * A source of interleaved stereo float PCM, read a block at a time.
 *
 * The whole audio engine is built on this interface rather than on arrays of samples. A three-minute
 * recording is about 63 MB as stereo float at 44.1 kHz; the app renders records longer than that, on
 * phones with 2 GB of RAM. Pulling blocks means the peak cost is the block size, whatever the length of
 * the recording, and it also happens to be the shape MediaCodec and MediaMuxer want.
 *
 * Implementations are not thread-safe. A render runs on exactly one thread.
 */
interface PcmSource : Closeable {

    val sampleRate: Int

    /** Total frames, or -1 when the length is not known up front (a looping bed, for instance). */
    val frameCount: Int

    /**
     * Reads up to [frames] stereo frames into [out], interleaved, starting at `out[offset]`.
     *
     * @return the number of frames actually read; 0 means the source is exhausted.
     */
    fun read(out: FloatArray, offset: Int, frames: Int): Int
}

/** A sink for interleaved stereo float PCM. */
interface PcmSink : Closeable {

    val sampleRate: Int

    fun write(block: FloatArray, offset: Int, frames: Int)

    /** Called exactly once when the stream is complete. Implementations finalise headers here. */
    fun finish()
}

/**
 * The whole of a short buffer, as a source.
 *
 * Used for the bundled assets, for test fixtures and for the needle-drop one-shot — anything where
 * holding the audio is cheaper than streaming it.
 */
class BufferSource(
    private val block: FloatArray,
    override val sampleRate: Int,
    private val channels: Int = 2,
) : PcmSource {

    override val frameCount: Int = if (channels == 0) 0 else block.size / channels
    private var cursor = 0

    override fun read(out: FloatArray, offset: Int, frames: Int): Int {
        val available = frameCount - cursor
        if (available <= 0) return 0
        val count = minOf(frames, available)
        System.arraycopy(block, cursor * channels, out, offset, count * channels)
        cursor += count
        return count
    }

    override fun close() = Unit

    /** Rewinds, so a loop can replay the same buffer without allocating a new one. */
    fun rewind() {
        cursor = 0
    }
}

/**
 * A source that repeats another source forever.
 *
 * The background music selection is a trimmed section that loops under a voice of any length. The loop
 * is cross-faded rather than cut: a hard seam in a bed that repeats every few seconds is the single most
 * audible artefact a background layer can have, and it is the reason this is a class instead of a
 * modulo in the mixer.
 */
class LoopSource(
    private val inner: PcmSource,
    /** Frames to loop over; the source must be rewindable. */
    private val loopFrames: Int,
    /** Frames of cross-fade at the seam. */
    crossfadeFrames: Int = 1_200,
) : PcmSource, Closeable {

    override val sampleRate: Int get() = inner.sampleRate
    override val frameCount: Int get() = -1

    private val crossfade = crossfadeFrames.coerceIn(0, loopFrames / 3)
    private val tail = if (crossfade > 0) FloatArray(crossfade * 2) else FloatArray(0)
    private val scratch = FloatArray(32_768 * 2)

    private var position = 0
    private var tailFrames = 0
    private var primed = false

    /** True when the wrapped source can be rewound, which every buffer and file source here can. */
    private val rewindable: (PcmSource) -> Unit = { source ->
        when (source) {
            is BufferSource -> source.rewind()
            is WavReader -> source.rewind()
            else -> Unit
        }
    }

    override fun read(out: FloatArray, offset: Int, frames: Int): Int {
        if (!primed) primeTail()
        if (loopFrames <= 0) return 0

        var written = 0
        while (written < frames) {
            val want = minOf(frames - written, loopFrames - position)
            if (want <= 0) {
                rewindable(inner)
                position = 0
                continue
            }
            var got = 0
            while (got < want) {
                val chunk = minOf(want - got, scratch.size / 2)
                val read = inner.read(scratch, 0, chunk)
                if (read <= 0) {
                    rewindable(inner)
                    if (got == 0) break
                    continue
                }
                System.arraycopy(scratch, 0, out, (offset + (written + got) * 2), read * 2)
                got += read
            }
            if (got == 0) break
            written += got
            position += got
        }

        if (crossfade > 0 && written > 0) applyCrossfade(out, offset, written)
        return written
    }

    /** Reads the first [crossfade] frames once, so the seam has material to fade against. */
    private fun primeTail() {
        primed = true
        if (crossfade <= 0) return
        var got = 0
        while (got < crossfade) {
            val read = inner.read(tail, got * 2, crossfade - got)
            if (read <= 0) break
            got += read
        }
        tailFrames = got
        rewindable(inner)
    }

    /**
     * Fades the head of each loop over the tail of the previous one.
     *
     * Applied on every pass except the first, which is why [passes] exists: the first loop is heard from
     * its natural beginning, and everything after it is blended.
     */
    private var passes = 0

    private fun applyCrossfade(out: FloatArray, offset: Int, frames: Int) {
        if (tailFrames == 0) return
        val start = position - frames
        val overlap = when {
            start < crossfade && passes > 0 -> crossfade - start
            else -> 0
        }
        if (overlap > 0) {
            val count = minOf(overlap, frames, tailFrames)
            for (frame in 0 until count) {
                val mix = frame.toFloat() / crossfade
                val index = (tailFrames - crossfade + frame).coerceIn(0, tailFrames - 1) * 2
                val target = (offset + frame * 2)
                out[target] = out[target] * mix + tail[index] * (1f - mix)
                out[target + 1] = out[target + 1] * mix + tail[index + 1] * (1f - mix)
            }
        }
        if (position + frames >= loopFrames) passes++
    }

    override fun close() = inner.close()
}

/**
 * Convenience over a plain float array, for tests and for the artwork/analysis paths.
 *
 * This is the only place audio is ever held in full, and it is used for short things: a bundled bed, a
 * waveform window, a unit-test fixture. Nothing in the record pipeline builds one.
 */
class FloatPcmBuffer(
    val samples: FloatArray,
    val sampleRate: Int,
    val channels: Int = 2,
) {
    val frameCount: Int get() = if (channels == 0) 0 else samples.size / channels

    val peak: Float
        get() {
            var peak = 0f
            for (sample in samples) {
                val magnitude = abs(sample)
                if (magnitude > peak) peak = magnitude
            }
            return peak
        }

    val durationMs: Long get() = frameCount * 1000L / sampleRate

    fun toSource(): PcmSource = BufferSource(samples, sampleRate, channels)

    companion object {
        fun silence(frames: Int, sampleRate: Int, channels: Int = 2) =
            FloatPcmBuffer(FloatArray(frames * channels), sampleRate, channels)
    }
}
