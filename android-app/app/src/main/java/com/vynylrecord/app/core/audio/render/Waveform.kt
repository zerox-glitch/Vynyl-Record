package com.vynylrecord.app.core.audio.render

import com.vynylrecord.app.core.audio.dsp.DspMath
import com.vynylrecord.app.core.audio.dsp.PcmSource
import com.vynylrecord.app.core.audio.dsp.WavCodec
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A record's waveform, small enough to keep in the database row.
 *
 * The library draws a waveform on every card, and reading the master to draw it would mean decoding a
 * multi-megabyte file every time a list scrolls. So the peaks are extracted once, at render time, and
 * stored: first a full-resolution set in a sidecar file, then a compact set on the row itself.
 *
 * The compact set is deliberately *peaks with their positions*, not a resampling of the file: a waveform
 * that has been averaged smooths the very transients the picture is meant to show — the crackles and the
 * pops are the interesting part of a Vynyl record's waveform.
 */
data class Waveform(
    /** 0..1 magnitudes, evenly spaced across the record. */
    val peaks: List<Float>,
    val durationMs: Long,
    /** Where the loudest peak sits, 0..1, so the UI can put the needle at the interesting moment. */
    val loudestAt: Float,
) {
    val isEmpty: Boolean get() = peaks.isEmpty()

    companion object {
        /** How many points a library row keeps. 96 is enough for a card and costs 384 bytes. */
        const val COMPACT_POINTS = 96

        /** How many the full sidecar keeps, for the player's scrubbable waveform. */
        const val DETAILED_POINTS = 768

        val EMPTY = Waveform(emptyList(), 0L, 0f)
    }
}

/**
 * Reads peaks out of a rendered master.
 *
 * One pass, block at a time, no allocation beyond the buffer and the output list. For a three-minute
 * record this is a few milliseconds of work and it happens inside the render job, so the library never
 * pays for it.
 */
class WaveformExtractor(private val points: Int = Waveform.DETAILED_POINTS) {

    fun extract(file: File, expectedDurationMs: Long? = null): Waveform {
        val source = try {
            WavCodec.Reader(file)
        } catch (error: Exception) {
            return Waveform.EMPTY
        }
        source.use { reader ->
            val frames = reader.frameCount
            if (frames <= 0) return Waveform.EMPTY
            val count = points.coerceAtLeast(8)
            val framesPerPoint = max(1, frames / count)
            val peaks = FloatArray(count)
            val block = FloatArray(framesPerPoint * 2)
            var pointIndex = 0
            var framesRead = 0
            var loudest = 0f
            var loudestIndex = 0

            while (framesRead < frames && pointIndex < count) {
                var pointFrames = 0
                var pointPeak = 0f
                while (pointFrames < framesPerPoint && framesRead < frames) {
                    val want = minOf(framesPerPoint - pointFrames, block.size / 2)
                    val read = reader.read(block, 0, want)
                    if (read <= 0) break
                    for (index in 0 until read) {
                        val magnitude = max(abs(block[index * 2]), abs(block[index * 2 + 1]))
                        if (magnitude > pointPeak) pointPeak = magnitude
                    }
                    pointFrames += read
                    framesRead += read
                }
                peaks[pointIndex] = pointPeak
                if (pointPeak > loudest) {
                    loudest = pointPeak
                    loudestIndex = pointIndex
                }
                pointIndex++
            }

            // Normalise so a quiet recording still shows its shape; the absolute level lives on the row
            // as a separate measurement and should not be baked into the picture.
            val normaliser = if (loudest > 1e-6f) 1f / loudest else 0f
            val normalised = peaks.map { (it * normaliser).coerceIn(0f, 1f) }

            val duration = expectedDurationMs ?: (frames * 1000L / reader.sampleRate)
            return Waveform(
                peaks = normalised,
                durationMs = duration,
                loudestAt = loudestIndex.toFloat() / (count - 1).coerceAtLeast(1),
            )
        }
    }

    /** Reduces a detailed waveform to the compact set a library row keeps. */
    fun compact(waveform: Waveform): List<Float> {
        if (waveform.peaks.isEmpty()) return emptyList()
        if (waveform.peaks.size <= Waveform.COMPACT_POINTS) return waveform.peaks
        val ratio = waveform.peaks.size.toFloat() / Waveform.COMPACT_POINTS
        return (0 until Waveform.COMPACT_POINTS).map { index ->
            val from = (index * ratio).toInt()
            val to = ((index + 1) * ratio).toInt().coerceAtMost(waveform.peaks.size)
            var peak = 0f
            for (position in from until to) {
                if (waveform.peaks[position] > peak) peak = waveform.peaks[position]
            }
            peak
        }
    }
}

/**
 * Measurements taken during a render, kept so the record can describe itself later.
 *
 * These are not decoration: `voiceActiveRatio` is what tells a listener-facing summary whether a
 * detection threshold is right, and `noiseFloorDb` is what proves the surface bed is present at the
 * level the preset asked for — the check that catches "the crackle did not render", which is otherwise a
 * bug a user only notices as a vague feeling that a record sounds too clean.
 */
data class AudioMeasurements(
    val peakDb: Float,
    val rmsDb: Float,
    /** Fraction of frames above -45 dBFS: how much of the record has someone speaking on it. */
    val voiceActiveRatio: Float,
    /** The quietest twentieth of frames, in dB — the surface bed's own level. */
    val noiseFloorDb: Float,
    val durationMs: Long,
    val sampleRate: Int,
) {
    val summary: String
        get() = "peak %.1f dB, rms %.1f dB, %.0f%% voice, floor %.1f dB".format(
            peakDb, rmsDb, voiceActiveRatio * 100f, noiseFloorDb,
        )

    companion object {
        /**
         * Measures a source as it is read, without holding it.
         *
         * Called on the *finished master*, which is why the voice ratio is meaningful: it is measuring
         * the record, not the raw capture.
         */
        fun measure(source: PcmSource): AudioMeasurements {
            val frames = source.frameCount.coerceAtLeast(0)
            if (frames == 0) return AudioMeasurements(-120f, -120f, 0f, -120f, 0L, source.sampleRate)

            val block = FloatArray(8_192 * 2)
            val window = FloatArray(64)
            var windowPeak = 0f
            var windowCount = 0
            var windowIndex = 0
            var active = 0
            var windows = 0
            var sumSquares = 0.0
            var totalSamples = 0L
            var peak = 0f
            val windowLevels = ArrayList<Float>(256)

            while (true) {
                val read = source.read(block, 0, block.size / 2)
                if (read <= 0) break
                for (index in 0 until read) {
                    val left = block[index * 2]
                    val right = block[index * 2 + 1]
                    val magnitude = max(abs(left), abs(right))
                    if (magnitude > peak) peak = magnitude
                    sumSquares += left.toDouble() * left + right.toDouble() * right
                    totalSamples += 2
                    if (magnitude > windowPeak) windowPeak = magnitude
                    windowCount++
                    if (windowCount >= window.size) {
                        windowLevels += windowPeak
                        if (DspMath.linearToDb(windowPeak) > -45f) active++
                        windows++
                        windowPeak = 0f
                        windowCount = 0
                        windowIndex++
                        if (windowIndex >= 64) windowIndex = 0
                    }
                }
            }

            val rms = if (totalSamples == 0L) 0f else kotlin.math.sqrt(sumSquares / totalSamples).toFloat()
            val sorted = windowLevels.sorted()
            val quietCount = (sorted.size / 20).coerceAtLeast(1)
            val floorDb = if (sorted.isEmpty()) {
                -120f
            } else {
                sorted.take(quietCount).map { DspMath.linearToDb(it) }.average().toFloat()
            }

            return AudioMeasurements(
                peakDb = DspMath.linearToDb(peak),
                rmsDb = DspMath.linearToDb(rms),
                voiceActiveRatio = if (windows == 0) 0f else active.toFloat() / windows,
                noiseFloorDb = floorDb,
                durationMs = frames * 1000L / source.sampleRate,
                sampleRate = source.sampleRate,
            )
        }
    }
}

/**
 * The compact waveform's on-disk form.
 *
 * Kept as text because it is tiny (96 numbers), it survives a schema change without a migration, and a
 * reader opening a record's directory can see what is in it. A binary blob would be smaller by a few
 * hundred bytes and unreadable by the person debugging it.
 */
object WaveformFile {

    const val FILE_NAME = "waveform.json"

    fun write(file: File, waveform: Waveform) {
        file.parentFile?.mkdirs()
        val peaks = waveform.peaks.joinToString(",") { value -> (value * 1_000f).roundToInt().toString() }
        file.writeText(
            buildString {
                append("{\"version\":1,")
                append("\"points\":").append(waveform.peaks.size).append(',')
                append("\"durationMs\":").append(waveform.durationMs).append(',')
                append("\"loudestAt\":").append((waveform.loudestAt * 10_000f).roundToInt() / 10_000f).append(',')
                append("\"peaks\":[").append(peaks).append("]}")
            },
        )
    }

    fun read(file: File): Waveform {
        if (!file.isFile) return Waveform.EMPTY
        return try {
            val text = file.readText()
            val peaksPart = text.substringAfter("\"peaks\":[", "").substringBefore(']')
            val peaks = peaksPart.split(',').filter { it.isNotBlank() }.map { it.trim().toFloat() / 1_000f }
            Waveform(
                peaks = peaks,
                durationMs = text.substringAfter("\"durationMs\":", "0").takeWhile { it.isDigit() }.toLongOrNull() ?: 0L,
                loudestAt = text.substringAfter("\"loudestAt\":", "0").takeWhile { it.isDigit() || it == '.' }
                    .toFloatOrNull() ?: 0f,
            )
        } catch (error: Exception) {
            Waveform.EMPTY
        }
    }
}
