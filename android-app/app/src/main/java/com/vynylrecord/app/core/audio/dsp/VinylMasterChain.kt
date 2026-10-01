package com.vynylrecord.app.core.audio.dsp

import com.vynylrecord.app.core.model.VinylRecipe
import kotlin.math.abs

/**
 * The record press, in the order the stages are heard.
 *
 * ```
 *   voice ─┐
 *          ├─ mix (ducked) ─ voice cleanup ─ saturation ─ compression ─ wow & flutter ─ vinyl EQ ─┐
 *   music ─┘                                                                                     │
 *                                                                                               ▼
 *   master ◀─ limiter ◀─ stereo ─┤ raw ◀─ dust ◀─ needle ◀─ pops ◀─ crackle ◀─ surface ◀─────────┘
 * ```
 *
 * Three things about this class are deliberate.
 *
 * **It is a block processor.** Nothing here holds a whole record. The chain is built once and then fed
 * blocks by the render job, so peak memory is the block buffer and every delay line is sized from its
 * own settings rather than from the length of the recording.
 *
 * **The order is the design.** Saturation runs before compression so the compressor hears the harmonics
 * the saturation created, which is what a tape machine does. Wow and flutter run *after* the voice stages
 * so the pitch drift applies to the finished tone instead of being smeared by the filters that follow it.
 * The surface, crackle and pops are added last so they sit *on* the record rather than being compressed
 * along with the music: a compressed pop sounds like a malfunction, while a pop that lands on top sounds
 * like a record.
 *
 * **It never allocates per block beyond what it is given.** The scratch buffers are fields, sized once.
 * A three-minute side goes through this class without the garbage collector being involved at all, which
 * is what keeps a render from stuttering the UI on a device with 2 GB of RAM.
 */
class VinylMasterChain(
    private val recipe: VinylRecipe,
    private val seed: Long,
    private val sampleRate: Int,
    private val totalFrames: Int,
) {

    // ---- voice band: rumble out of the way, then the preset's presence and warmth
    private val voiceHighPass = DspMath.Biquad().apply { highPass(recipe.highPassHz, 0.5f, sampleRate) }
    private val voiceRumble = DspMath.Biquad().apply {
        highPass(recipe.rumbleHz.coerceIn(20f, 200f), 0.707f, sampleRate)
    }
    private val voicePresence = DspMath.Biquad().apply {
        peaking(recipe.midFreqHz.coerceIn(200f, 8_000f), 2f, recipe.midGainDb, sampleRate)
    }
    private val voiceWarmth = DspMath.OnePole().apply { lowPass(warmthCutoff(), sampleRate) }
    private val musicTilt = MusicTilt(recipe.musicClarity, sampleRate)

    // ---- character
    private val saturator = Saturator(recipe.saturationDrive, recipe.saturationMix, sampleRate)
    private val voiceCompressor = Compressor(
        thresholdDb = -18f * (1f - recipe.saturationDrive) - 8f,
        ratio = 4f,
        attackSeconds = 0.004f,
        releaseSeconds = 0.12f,
        sampleRate = sampleRate,
        makeupGain = 1f + recipe.saturationDrive * 0.8f,
    )
    private val compander = Compressor(
        thresholdDb = recipe.companderThresholdDb,
        ratio = recipe.companderRatio,
        attackSeconds = 0.015f,
        releaseSeconds = 0.2f,
        sampleRate = sampleRate,
        makeupGain = 1f,
    )
    private val wowFlutter = WowFlutter(
        wowDepthCents = if (recipe.wowEnabled) recipe.wowDepthCents else 0f,
        wowRatePerMin = recipe.wowRatePerMin,
        flutterDepthCents = if (recipe.flutterEnabled) recipe.flutterDepthCents else 0f,
        flutterRatePerMin = recipe.flutterRatePerMin,
        sampleRate = sampleRate,
    )

    // ---- vinyl band
    private val vinylHighPass = DspMath.Biquad().apply { highPass(recipe.highPassHz, 0.5f, sampleRate) }
    private val vinylLowPass = DspMath.Biquad().apply { lowPass(recipe.lowPassHz, 0.5f, sampleRate) }
    private val midTilt = DspMath.Biquad().apply {
        // A small dip after the main roll-off: the low-pass alone leaves the mids boxy on the older
        // presets, and this is the shape that makes them read as "old" rather than as "filtered".
        peaking(1_200f, 1.2f, -recipe.saturationDrive * 1.2f, sampleRate)
    }

    // ---- the record itself
    private val bed = VinylBed(recipe, seed, sampleRate, totalFrames)

    // ---- the master bus
    private val width = StereoWidth(if (recipe.stereoEnabled) recipe.stereoWidth.coerceIn(0.5f, 1.25f) else 1f)
    private val limiter = Limiter(recipe.limiterCeil.coerceIn(0.5f, 0.999f), sampleRate)
    private val ducking = Ducker(maxDuck = 0.72f, sampleRate = sampleRate)
    private val masterGain = recipe.masterGain

    // ---- scratch, allocated once
    private var scratchFrames = 0
    private var bedBlock = FloatArray(0)
    private val musicLevel = recipe.musicLevel

    /** Frames processed so far, for progress reporting. */
    var framesProcessed: Int = 0
        private set

    /** Peak of the finished master, measured as it goes and reported by the render validator. */
    var outputPeak: Float = 0f
        private set

    /** The largest ceiling overshoot the limiter had to catch, in dB. Used when a preset is too hot. */
    var limiterReductionDb: Float = 0f
        private set

    /**
     * Processes one block.
     *
     * @param voice interleaved stereo voice, or null when this block is bed only
     * @param music interleaved stereo background, already trimmed and looped, or null
     * @param frames how many stereo frames this block holds
     * @param out receives the finished interleaved stereo master; must hold `frames * 2` samples
     */
    fun process(voice: FloatArray?, music: FloatArray?, frames: Int, out: FloatArray) {
        if (frames <= 0) return
        ensureScratch(frames)
        val blockStart = framesProcessed

        // ---- 1. the voice chain. The background is deliberately *not* in this loop: the web renderer
        //         mixed its music in after the voice DSP, and running a bed through the voice's
        //         compressor and wow/flutter is what makes a background sound like it is being played
        //         through the same broken machine as the voice. The music gets its own tilt and ducking.
        for (frame in 0 until frames) {
            val index = frame * 2
            val voiceLeft = voice?.get(index) ?: 0f
            val voiceRight = voice?.get(index + 1) ?: 0f

            var left = voiceWarmth.process(voiceRumble.process(voiceHighPass.process(voiceLeft)))
            var right = voiceWarmth.process(voiceRumble.process(voiceHighPass.process(voiceRight)))
            left = voicePresence.process(left)
            right = voicePresence.process(right)

            left = saturator.process(left)
            right = saturator.process(right)
            left = voiceCompressor.process(left)
            right = voiceCompressor.process(right)
            left = compander.process(left)
            right = compander.process(right)

            // Wow and flutter in one pass: two independent resamplers would compound into exactly the
            // seasick artefact both stages exist to avoid.
            wowFlutter.process(left, right, out, index)

            out[index] = midTilt.process(vinylLowPass.process(vinylHighPass.process(out[index])))
            out[index + 1] = midTilt.process(vinylLowPass.process(vinylHighPass.process(out[index + 1])))

            // ---- 2. the background, tilted and ducked under the voice that is driving the sidechain
            if (music != null && musicLevel > 0f) {
                val duck = ducking.gainFor(maxOf(abs(voiceLeft), abs(voiceRight)))
                out[index] += musicTilt.process(music[index]) * duck * musicLevel
                out[index + 1] += musicTilt.process(music[index + 1]) * duck * musicLevel
            }
        }

        // ---- 3. the record: surface, crackle, pops, dust and the needle, once for the whole block
        bed.render(blockStart, frames, bedBlock)
        for (index in 0 until frames * 2) {
            out[index] += bedBlock[index]
        }

        // ---- 4. image, level, ceiling, fades
        for (frame in 0 until frames) {
            val index = frame * 2
            width.process(out[index], out[index + 1], out, index)
            out[index] *= masterGain
            out[index + 1] *= masterGain
        }

        limiterBlock(out, frames)
        applyEndFades(blockStart, frames, out)
        framesProcessed += frames
    }

    private fun ensureScratch(frames: Int) {
        if (frames <= scratchFrames) return
        scratchFrames = frames
        bedBlock = FloatArray(frames * 2)
    }

    private fun limiterBlock(out: FloatArray, frames: Int) {
        // The limiter's own look-ahead is a small circular buffer, so it can run frame by frame; doing
        // it in place here is what keeps its gain envelope continuous across block boundaries.
        for (frame in 0 until frames) {
            val index = frame * 2
            limiter.process(out[index], out[index + 1], out, index)
        }
        for (index in 0 until frames * 2) {
            val value = DspMath.sanitize(out[index])
            out[index] = value
            val magnitude = abs(value)
            if (magnitude > outputPeak) outputPeak = magnitude
            if (magnitude > recipe.limiterCeil) {
                val overshoot = DspMath.linearToDb(magnitude / recipe.limiterCeil)
                if (overshoot > limiterReductionDb) limiterReductionDb = overshoot
            }
        }
    }

    /**
     * The head and tail fades: 10 ms in, 300 ms out, matching the web renderer's chain.
     *
     * A record must not start or end on a click, and the tail lets the run-out groove fade instead of
     * being cut off — the difference between "the recording ended" and "the file ran out".
     */
    private fun applyEndFades(blockStartFrame: Int, frames: Int, out: FloatArray) {
        val fadeInFrames = (0.010f * sampleRate).toInt()
        val fadeOutFrames = (0.300f * sampleRate).toInt()
        val fadeOutStart = (totalFrames - fadeOutFrames).coerceAtLeast(0)

        for (frame in 0 until frames) {
            val absolute = blockStartFrame + frame
            var gain = 1f
            if (absolute < fadeInFrames && fadeInFrames > 0) {
                gain *= absolute.toFloat() / fadeInFrames
            }
            if (absolute >= fadeOutStart && fadeOutFrames > 0) {
                gain *= ((totalFrames - absolute).toFloat() / fadeOutFrames).coerceIn(0f, 1f)
            }
            if (gain != 1f) {
                out[frame * 2] *= gain
                out[frame * 2 + 1] *= gain
            }
        }
    }

    /**
     * How much low end the preset's warmth setting keeps.
     *
     * The web chain expressed warmth as saturation drive alone. Here the saturation stage and the warmth
     * filter are separate, so a preset can be warm without being distorted — which is exactly what
     * "Old Family Record" needs: audibly old, still clearly your grandmother.
     */
    private fun warmthCutoff(): Float = (12_000f - recipe.voiceWarmth * 8_500f).coerceIn(2_500f, 18_000f)

    /** Diagnostics for the render log and the tests. */
    fun diagnostics(): Diagnostics = Diagnostics(
        crackleCount = bed.crackleCount,
        popCount = bed.popCount,
        outputPeak = outputPeak,
        limiterReductionDb = limiterReductionDb,
        surfaceShape = BedFilterShape.describe(recipe.surfaceFilter),
    )

    data class Diagnostics(
        val crackleCount: Int,
        val popCount: Int,
        val outputPeak: Float,
        val limiterReductionDb: Float,
        val surfaceShape: String,
    )
}

/**
 * A gentle high shelf for the background bed, so ducking does not turn the music to mud.
 *
 * This is a taste control rather than a spec'd filter: the job is to keep a little air on the music while
 * the voice owns the mids, so a background track stays recognisable underneath a speaking voice instead
 * of becoming a low rumble.
 */
class MusicTilt(private val clarity: Float, sampleRate: Int) {
    private val low = DspMath.OnePole().apply { lowPass(2_600f, sampleRate) }

    fun process(input: Float): Float {
        val body = low.process(input)
        val air = input - body
        // clarity 0 = dark and soft, clarity 1 = the music keeps its full top end
        return body + air * (0.35f + clarity.coerceIn(0f, 1f) * 0.65f)
    }
}
