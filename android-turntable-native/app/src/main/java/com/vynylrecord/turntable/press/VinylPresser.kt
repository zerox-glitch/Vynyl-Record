package com.vynylrecord.turntable.press

import com.vynylrecord.turntable.vault.PressRecipe
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

/**
 * Cuts a side: turns a digital recording into something that sounds like it was pressed and played.
 *
 * Every stage is baked into the output file — this is not an effect rack applied during playback. A
 * side pressed here sounds the same in this app, in a file manager, or on a laptop five years from
 * now, which is the point: a voice note should not need the app that made it in order to keep its
 * character.
 *
 * The chain, in order:
 *
 * ```
 *   trim silence ─▶ lead-in ramp ─▶ normalise ─▶ warmth ─▶ saturation ─▶ wow & flutter
 *        ─▶ surface noise + hiss ─▶ crackle ─▶ room tone ─▶ rumble filter ─▶ limiter ─▶ run-out
 * ```
 *
 * It is deterministic for a given seed, which is what makes it testable: the same recipe on the same
 * input produces the same samples, so a test can assert on the noise floor, the pitch drift and the
 * duration rather than merely that "something happened".
 *
 * Pure Kotlin, no Android dependency: the whole chain runs in JVM unit tests.
 */
class VinylPresser(private val seed: Long = 0x5EED1234L) {

    /** Names of the stages, in order, for the progress caption in the Studio. */
    val stageNames: List<String> = listOf(
        "Trimming the master",
        "Warming the cut",
        "Driving the cutter",
        "Setting the platter speed",
        "Laying the groove floor",
        "Cutting the crackle",
        "Adding room tone",
        "Lacquering the side",
    )

    /**
     * Presses [input] with [recipe].
     *
     * **The input buffer is consumed.** A three-minute side is roughly 32 MB in float PCM, so the
     * chain mutates the caller's array instead of copying it at every stage; the caller passes
     * ownership and must not read the input afterwards. Every stage that shortens the side shifts the
     * audio down inside that one buffer, and only the wow-and-flutter resampler allocates, because a
     * time-varying resample cannot be done in place.
     *
     * [onProgress] receives 0..1 and the name of the stage being worked on. It is called once per
     * stage boundary and periodically inside the long ones, so a progress bar keeps moving on a
     * three-minute side without any stage having to know about threads.
     */
    fun press(
        input: PcmAudio,
        recipe: PressRecipe,
        onProgress: (Float, String) -> Unit = { _, _ -> },
    ): PcmAudio {
        if (input.frameCount == 0) return input
        val channels = input.channels
        val rate = input.sampleRate
        val stageCount = stageNames.size

        // Progress is reported against the whole chain, so "half way through the crackle stage"
        // still moves the bar. 0.30, 0.55 and 0.65 are the shares those long stages own.
        fun report(stage: Int, fraction: Float = 1f) {
            onProgress(
                ((stage + fraction.coerceIn(0f, 1f)) / stageCount).coerceIn(0f, 1f),
                stageNames[stage],
            )
        }

        // -------------------------------------------------------------- 1. trim and ramp
        report(0, 0f)
        var audio = if (recipe.trimSilence) trimSilence(input, channels) else input
        audio = applyLeadIn(audio, channels, rate)

        report(0)

        // -------------------------------------------------------------- 2. level and tone
        report(1, 0f)
        if (recipe.normalize) audio = normalize(audio, target = 0.84f)
        val samples = audio.samples
        if (recipe.warmth > 0.01f) {
            applyWarmth(samples, channels, audio.frameCount, rate, recipe.warmth)
            report(1, 0.7f)
        }
        if (recipe.saturation > 0.01f) {
            applySaturation(samples, audio.frameCount * channels, recipe.saturation)
            report(2, 0.5f)
        }
        report(2)

        // -------------------------------------------------------------- 3. wow and flutter
        report(3, 0f)
        // The frame count is carried, not inferred: after the trim the buffer is longer than the side.
        var worked = PcmAudio(rate, channels, samples, audio.frameCount)
        if (recipe.wowFlutter > 0.005f) {
            worked = applyWowAndFlutter(worked, recipe.wowFlutter)
        }
        report(3)

        // -------------------------------------------------------------- 4. surface and space
        val random = Random(seed)
        val noisy = worked.samples // already the caller's buffer: mutate it
        val liveFrames = worked.frameCount
        var narrated = false
        if (recipe.surfaceNoise > 0.005f || recipe.hiss > 0.005f) {
            applySurfaceNoise(noisy, channels, liveFrames, rate, recipe.surfaceNoise, recipe.hiss, random) { fraction ->
                if (!narrated) report(4, fraction)
            }
            narrated = true
        }
        if (recipe.crackle > 0.005f) {
            applyCrackle(noisy, channels, liveFrames, rate, recipe.crackle, random) { fraction -> report(5, fraction) }
        }
        var textured = PcmAudio(rate, channels, noisy, liveFrames)
        if (recipe.roomTone > 0.01f) {
            report(6, 0.2f)
            textured = applyRoomTone(textured, recipe.roomTone)
        }
        report(6)

        // -------------------------------------------------------------- 5. finish
        report(7, 0f)
        val finished = textured.samples
        val frames = textured.frameCount
        removeRumble(finished, channels, frames, rate)
        applyRunOut(finished, channels, frames, rate)
        softCeiling(finished, frames * channels)
        report(7)

        return PcmAudio(rate, channels, finished, frames)
    }

    // ------------------------------------------------------------------ trimming and ramps

    /**
     * Trims silence from both ends.
     *
     * The threshold is deliberately low (-54 dBFS) and measured over a short window, so a breath
     * before the first word survives and a noisy room's floor does not defeat the trim.
     */
    private fun trimSilence(input: PcmAudio, channels: Int): PcmAudio {
        val samples = input.samples
        val frames = input.frameCount
        val window = max(1, input.sampleRate / 100) // 10 ms
        val threshold = 0.002f
        val padding = input.sampleRate / 10 // keep 100 ms either side

        var firstLoud = -1
        var frame = 0
        while (frame < frames) {
            val end = min(frame + window, frames)
            var peak = 0f
            for (index in frame until end) {
                for (channel in 0 until channels) {
                    val magnitude = abs(samples[index * channels + channel])
                    if (magnitude > peak) peak = magnitude
                }
            }
            if (peak > threshold) {
                firstLoud = frame
                break
            }
            frame = end
        }
        if (firstLoud < 0) return input // all silence: nothing to trim towards

        var lastLoud = frames
        frame = frames
        while (frame > 0) {
            val start = max(0, frame - window)
            var peak = 0f
            for (index in start until frame) {
                for (channel in 0 until channels) {
                    val magnitude = abs(samples[index * channels + channel])
                    if (magnitude > peak) peak = magnitude
                }
            }
            if (peak > threshold) {
                lastLoud = frame
                break
            }
            frame = start
        }

        val start = max(0, firstLoud - padding)
        val end = min(frames, lastLoud + padding)
        val kept = end - start
        if (kept >= frames) return input

        // Shift the tail down inside the caller's buffer rather than allocating a second copy of a
        // side that can be tens of megabytes.
        if (start > 0) {
            java.lang.System.arraycopy(samples, start * channels, samples, 0, kept * channels)
        }
        return input.withFrameCount(kept)
    }

    /** The lead-in: the stylus lands and the music fades up, so the side does not start with a click. */
    private fun applyLeadIn(input: PcmAudio, channels: Int, rate: Int): PcmAudio {
        val frames = input.frameCount
        val ramp = min(frames / 4, rate / 3) // up to 330 ms
        if (ramp <= 0) return input
        val samples = input.samples // the trim stage above already owns this buffer
        for (frame in 0 until ramp) {
            val gain = frame.toFloat() / ramp
            for (channel in 0 until channels) samples[frame * channels + channel] *= gain
        }
        return PcmAudio(rate, channels, samples)
    }

    /**
     * The run-out. Applied last, after the noise and the room have been added, so their tails are
     * faded with everything else instead of being cut off by a hard edge.
     */
    private fun applyRunOut(samples: FloatArray, channels: Int, frames: Int, rate: Int) {
        val ramp = min(frames / 3, rate)
        for (index in 0 until ramp) {
            val frame = frames - 1 - index
            if (frame < 0) break
            val gain = index.toFloat() / ramp
            for (channel in 0 until channels) samples[frame * channels + channel] *= gain
        }
    }

    /** Scales in place. [PcmAudio.peak] only reads the live frames, so a trimmed side is measured
     *  over what is actually there. */
    private fun normalize(input: PcmAudio, target: Float): PcmAudio {
        val peak = input.peak
        if (peak <= 1e-6f) return input
        val gain = target / peak
        if (abs(gain - 1f) < 1e-3f) return input
        val samples = input.samples
        for (index in 0 until input.frameCount * input.channels) samples[index] *= gain
        return input
    }

    // ------------------------------------------------------------------ tone

    /**
     * A one-pole low-pass blended back over the dry signal, plus a low-shelf lift.
     *
     * This is much of what separates a voice note from a record: bandwidth that stops short of the
     * top end, and a little more weight under the fundamental.
     */
    private fun applyWarmth(samples: FloatArray, channels: Int, frames: Int, rate: Int, amount: Float) {
        val cutoff = 9000f - 5200f * amount
        val coefficient = 1f - exp(-2.0 * Math.PI * cutoff / rate).toFloat()
        val shelfCoefficient = 1f - exp(-2.0 * Math.PI * 320.0 / rate).toFloat()

        val lowPassState = FloatArray(channels)
        val shelfState = FloatArray(channels)
        val dryShare = 1f - amount * 0.75f

        for (frame in 0 until frames) {
            val index = frame * channels
            for (channel in 0 until channels) {
                val dry = samples[index + channel]
                lowPassState[channel] += coefficient * (dry - lowPassState[channel])
                shelfState[channel] += shelfCoefficient * (dry - shelfState[channel])
                samples[index + channel] = dry * dryShare + lowPassState[channel] * (amount * 0.75f) +
                    shelfState[channel] * (amount * 0.22f)
            }
        }
    }

    /** Soft saturation: the cutter's own compression, and the reason loud passages thicken. */
    private fun applySaturation(samples: FloatArray, count: Int, amount: Float) {
        val drive = 1f + amount * 3.2f
        val norm = 1f / tanh(drive)
        for (index in 0 until count) {
            samples[index] = tanh(samples[index] * drive) * norm
        }
    }

    /**
     * Off-centre pressing plus a belt-driven platter: the read head moves at a slowly drifting rate.
     *
     * Wow is the slow term (0.62 Hz — once per revolution, the rise and fall of a warped pressing);
     * flutter is the fast one (5.8 Hz, from the motor). Both are inaudible as "pitch" when they are
     * small and unmistakably "record" when they are not.
     */
    private fun applyWowAndFlutter(input: PcmAudio, amount: Float): PcmAudio {
        val wow = 0.0065 * amount
        val flutter = 0.0018 * amount
        val drift = Random(seed xor 0x9E3779B9L)
        var driftValue = 0.0
        return input.resampleWith(baseRatio = 1.0) { frame ->
            val seconds = frame / input.sampleRate
            driftValue += (drift.nextFloat() - 0.5) * 0.00002
            driftValue *= 0.99999
            val wobble = sin(2.0 * Math.PI * 0.62 * seconds) * wow +
                sin(2.0 * Math.PI * 5.8 * seconds + 0.7) * flutter +
                driftValue * amount
            1.0 + wobble
        }
    }

    // ------------------------------------------------------------------ surfaces

    /**
     * The bed the music sits on: groove roar (filtered noise under 1.4 kHz), hiss (the top end), and
     * a slow amplitude drift so it breathes instead of sounding like a noise generator.
     */
    private fun applySurfaceNoise(
        samples: FloatArray,
        channels: Int,
        frames: Int,
        rate: Int,
        surfaceAmount: Float,
        hissAmount: Float,
        random: Random,
        onProgress: (Float) -> Unit,
    ) {
        val roarCoefficient = 1f - exp(-2.0 * Math.PI * 1400.0 / rate).toFloat()
        val roarLevel = surfaceAmount * 0.055f
        val hissLevel = hissAmount * 0.020f
        val roar = FloatArray(channels)
        val hiss = FloatArray(channels)
        val drift = FloatArray(channels)

        for (frame in 0 until frames) {
            if (frame and 0x3FFFF == 0) onProgress(frame.toFloat() / frames)
            val index = frame * channels
            val breathe = 0.72f + 0.28f * sin(2.0 * Math.PI * 0.13 * frame / rate).toFloat()
            for (channel in 0 until channels) {
                val white = random.nextFloat() * 2f - 1f
                roar[channel] += roarCoefficient * (white - roar[channel])
                hiss[channel] = hiss[channel] * 0.55f + white * 0.45f
                drift[channel] = drift[channel] * 0.9999f + roar[channel] * 0.0001f
                samples[index + channel] += roar[channel] * roarLevel * breathe +
                    (hiss[channel] - roar[channel]) * hissLevel * 0.6f +
                    drift[channel] * surfaceAmount * 0.6f
            }
        }
    }

    /**
     * Ticks and pops, as a Poisson process: many small ticks with occasional louder pops, each a
     * short exponential ping that lands on both channels with an independent sign and level so it has
     * a place in the stereo image rather than sitting dead centre.
     */
    private fun applyCrackle(
        samples: FloatArray,
        channels: Int,
        frames: Int,
        rate: Int,
        amount: Float,
        random: Random,
        onProgress: (Float) -> Unit,
    ) {
        val perSecond = 4f + 60f * amount
        val probability = perSecond / rate
        val level = FloatArray(channels)
        val decay = FloatArray(channels)

        for (frame in 0 until frames) {
            if (frame and 0x3FFFF == 0) onProgress(frame.toFloat() / frames)
            val index = frame * channels
            for (channel in 0 until channels) {
                if (random.nextFloat() < probability) {
                    // 15 % of hits are pops: louder, and slow enough to have a pitch of their own.
                    val pop = random.nextFloat() < 0.15f
                    val amplitude = if (pop) {
                        0.35f + random.nextFloat() * 0.5f
                    } else {
                        0.05f + random.nextFloat() * 0.22f
                    } * amount
                    level[channel] = if (random.nextBoolean()) amplitude else -amplitude
                    decay[channel] = if (pop) 0.99985f else 0.9993f
                }
                if (level[channel] != 0f) {
                    samples[index + channel] += level[channel]
                    level[channel] *= decay[channel]
                    if (abs(level[channel]) < 1e-5f) level[channel] = 0f
                }
            }
        }
    }

    /**
     * A small Schroeder reverb: four parallel comb filters into two allpass sections, per channel.
     *
     * The tail is what says "this was played in a room" rather than "this was rendered on a laptop".
     * It is mixed in at a low level so it colours the side instead of washing it out.
     */
    private fun applyRoomTone(input: PcmAudio, amount: Float): PcmAudio {
        val channels = input.channels
        val samples = input.samples
        val frames = input.frameCount
        if (frames < 4096) return input

        val combDelays = intArrayOf(1557, 1617, 1491, 1422)
        val feedback = 0.72f + 0.16f * amount
        val combs = Array(channels) { channel ->
            Array(combDelays.size) { index -> CombFilter(combDelays[index] + channel * 23, feedback) }
        }
        val allpassDelays = intArrayOf(225, 556)
        val allpasses = Array(channels) { channel ->
            Array(allpassDelays.size) { index -> AllPassFilter(allpassDelays[index] + channel * 11, 0.5f) }
        }

        val wet = amount * 0.42f
        for (frame in 0 until frames) {
            val index = frame * channels
            for (channel in 0 until channels) {
                val dry = samples[index + channel]
                var sum = 0f
                for (comb in combs[channel]) sum += comb.process(dry)
                sum /= combDelays.size
                var wetSample = sum
                for (allpass in allpasses[channel]) wetSample = allpass.process(wetSample)
                samples[index + channel] = dry * (1f - wet * 0.35f) + wetSample * wet
            }
        }
        return PcmAudio(input.sampleRate, channels, samples)
    }

    /** Kills DC and anything under 25 Hz: a cutter head cannot cut it, and it wastes headroom. */
    private fun removeRumble(samples: FloatArray, channels: Int, frames: Int, rate: Int) {
        val coefficient = 1f - exp(-2.0 * Math.PI * 25.0 / rate).toFloat()
        val low = FloatArray(channels)
        for (frame in 0 until frames) {
            val index = frame * channels
            for (channel in 0 until channels) {
                low[channel] += coefficient * (samples[index + channel] - low[channel])
                samples[index + channel] -= low[channel]
            }
        }
    }

    /**
     * Final safety net, applied per sample rather than to the side as a whole.
     *
     * Scaling the whole side down to fit a loud transient is what makes a heavily crackled record
     * *quieter and duller* than a clean one — the pops set the gain for the music. There is nothing
     * "wrong" with a pop that reaches the ceiling, so instead of turning everything down the peaks are
     * bent: everything below [threshold] is untouched and everything above it compresses into the last
     * tenth, asymptotically approaching full scale without ever crossing it.
     */
    private fun softCeiling(samples: FloatArray, count: Int, threshold: Float = 0.90f) {
        val headroom = 1f - threshold
        for (index in 0 until count) {
            val sample = samples[index]
            val magnitude = abs(sample)
            if (magnitude <= threshold) continue
            val excess = (magnitude - threshold) / headroom
            val shaped = threshold + headroom * tanh(excess)
            samples[index] = if (sample < 0f) -shaped else shaped
        }
    }

    private class CombFilter(delaySamples: Int, private val feedback: Float) {
        private val buffer = FloatArray(max(1, delaySamples))
        private var index = 0

        fun process(input: Float): Float {
            val delayed = buffer[index]
            buffer[index] = input + delayed * feedback
            index++
            if (index == buffer.size) index = 0
            return delayed
        }
    }

    private class AllPassFilter(delaySamples: Int, private val gain: Float) {
        private val buffer = FloatArray(max(1, delaySamples))
        private var index = 0

        fun process(input: Float): Float {
            val delayed = buffer[index]
            buffer[index] = input + delayed * gain
            index++
            if (index == buffer.size) index = 0
            return delayed - input * gain
        }
    }
}
