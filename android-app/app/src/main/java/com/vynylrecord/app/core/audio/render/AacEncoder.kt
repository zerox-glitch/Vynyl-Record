package com.vynylrecord.app.core.audio.render

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import com.vynylrecord.app.core.audio.dsp.PcmSink
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * M4A (AAC-LC) encoding, straight from the render loop.
 *
 * The share sheet is where a record leaves the app, and the format that plays everywhere — a phone, a car
 * stereo, a television, a messaging app — is M4A. WAV is offered as well, for keeping.
 *
 * The encoder is the platform's own `MediaCodec`, which is why this app needs no bundled encoder and no
 * patent question: there is no code in this repository that encodes or decodes AAC. The same is true of
 * the decoder used for imports.
 *
 * It is a [PcmSink], so the render chain writes into it exactly as it writes into the WAV writer. That
 * means the same audio path produces both formats; only the last stage differs.
 */
class AacEncoderSink(
    private val output: File,
    override val sampleRate: Int,
    private val bitRate: Int = 192_000,
    private val channels: Int = 2,
) : PcmSink {

    private val codec: MediaCodec
    private val muxer: MediaMuxer
    private val bufferInfo = MediaCodec.BufferInfo()
    private val byteScratch: ByteBuffer
    private var trackIndex = -1
    private var muxerStarted = false
    private var presentationFrames = 0L
    private var inputDone = false

    /** Non-null when the encoder could not be created; the caller falls back to WAV and says so. */
    var failure: String? = null
        private set

    init {
        output.parentFile?.mkdirs()
        val format = MediaFormat.createAudioFormat(MIME, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        codec = MediaCodec.createEncoderByType(MIME)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        byteScratch = ByteBuffer.allocateDirect(16_384 * 2).order(ByteOrder.nativeOrder())
    }

    override fun write(block: FloatArray, offset: Int, frames: Int) {
        var remaining = frames
        var cursor = offset
        while (remaining > 0) {
            val queueIndex = codec.dequeueInputBuffer(TIMEOUT_US)
            if (queueIndex < 0) continue
            val input = codec.getInputBuffer(queueIndex) ?: continue
            input.clear()
            val capacityFrames = input.capacity() / (2 * channels)
            val take = minOf(remaining, capacityFrames)
            byteScratch.clear()
            for (index in 0 until take * channels) {
                val value = block[cursor + index].coerceIn(-1f, 1f)
                byteScratch.putShort((value * 32_767f).toInt().toShort())
            }
            byteScratch.flip()
            input.put(byteScratch)
            codec.queueInputBuffer(queueIndex, 0, take * channels * 2, presentationFrames * 1_000_000L / sampleRate, 0)
            presentationFrames += take
            remaining -= take
            cursor += take * channels
            drain(false)
        }
    }

    override fun finish() {
        if (!inputDone) {
            val queueIndex = codec.dequeueInputBuffer(TIMEOUT_US * 4)
            if (queueIndex >= 0) {
                codec.queueInputBuffer(
                    queueIndex,
                    0,
                    0,
                    presentationFrames * 1_000_000L / sampleRate,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                )
            }
            inputDone = true
        }
        drain(true)
        release()
    }

    private fun drain(endOfStream: Boolean) {
        while (true) {
            val index = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return else continue
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerStarted) {
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                }

                index >= 0 -> {
                    val buffer = codec.getOutputBuffer(index)
                    val isConfig = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (buffer != null && bufferInfo.size > 0 && !isConfig) {
                        if (!muxerStarted) {
                            trackIndex = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                        buffer.position(bufferInfo.offset)
                        buffer.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, buffer, bufferInfo)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    private fun release() {
        runCatching {
            if (muxerStarted) muxer.stop()
        }.onFailure { Log.w(TAG, "muxer stop failed", it) }
        runCatching { muxer.release() }
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }

    /**
     * The sink contract says [finish] is called once. If the caller abandons the sink without finishing
     * — a cancelled render — [close] releases the platform resources and leaves a partial file for the
     * cleanup step to delete.
     */
    override fun close() {
        if (!inputDone) {
            runCatching { codec.stop() }
            runCatching { muxer.release() }
        } else {
            release()
        }
    }

    private companion object {
        const val MIME = MediaFormat.MIMETYPE_AUDIO_AAC
        const val TIMEOUT_US = 10_000L
        const val TAG = "VynylAacEncoder"
    }
}
