package com.vynylrecord.app.core.audio.dsp

import com.vynylrecord.app.core.model.NoiseFilterShape
import com.vynylrecord.app.core.model.VinylRecipe
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The imperfection layer: the surface, the crackle, the pops and the needle itself.
 *
 * ## Why this is generated rather than sampled
 *
 * A looping crackle file is the worst way to add crackle, because a listener eventually hears the period
 * — usually within about twenty seconds, which is shorter than most of the recordings this app is for.
 * Everything here is synthesised from the record's own seed instead:
 *
 *  * the same record always produces the same pattern, so a re-render is bit-identical;
 *  * two different records never share a pattern, because the seed comes from the record's id;
 *  * the bed is exactly as long as the record, so there is no loop seam anywhere in the master.
 *
 * ## Streaming
 *
 * The web renderer built the whole bed in memory because it ran on a server with gigabytes to spare.
 * This runs on a phone, so the bed is rendered a block at a time. The *events* are planned up front —
 * a few hundred small objects, tens of kilobytes — and only the ones overlapping the current block are
 * synthesised. Every filter and every generator keeps its state across blocks, so the result is identical
 * to rendering in one pass while the memory cost stays constant.
 *
 * The algorithm is a faithful port of `lib/audio/vinylBed.ts`, including the Paul Kellet pink-noise
 * filter and both envelope shapes, so a `.vynyl` bundle exported from the web app sounds like the same
 * record here.
 */
class VinylBed(
    private val recipe: VinylRecipe,
    private val seed: Long,
    private val sampleRate: Int,
    private val totalFrames: Int,
) {

    /**
     * One crackle or pop, planned before rendering starts.
     *
     * The generator and the pop's smoothing filter live *on the event*, not in a map keyed by position:
     * a pop is 30 ms long and a small block is shorter than that, so an event routinely spans two blocks
     * and its noise must not restart at the seam.
     */
    private class Event(val startFrame: Int, val lengthFrames: Int, val amplitude: Float, val bodyHz: Float) {
        val endFrame: Int get() = startFrame + lengthFrames
        val isPop: Boolean get() = bodyHz > 0f
    }

    private val events: List<Event>
    private val eventRandom = HashMap<Int, Mulberry32>()
    private val popFilter = HashMap<Int, FloatArray>()

    private var nextEvent = 0
    private val active = ArrayList<Int>(16)

    // ---- one independent stream per layer
    private val surfaceRandom = Mulberry32(Seeds.child(seed, "surface"))
    private val crackleRandom = Mulberry32(Seeds.child(seed, "crackle"))
    private val popRandom = Mulberry32(Seeds.child(seed, "pops"))
    private val decorrelationRandom = Mulberry32(Seeds.child(seed, "stereo-correlation"))
    private val dustRandom = Mulberry32(Seeds.child(seed, "dust"))

    private val pinkLeft = PinkNoise(surfaceRandom)
    private val pinkRight = PinkNoise(surfaceRandom)
    private val hissLeft = PinkNoise(Mulberry32(Seeds.child(seed, "hiss-l")))
    private val hissRight = PinkNoise(Mulberry32(Seeds.child(seed, "hiss-r")))

    private val bedFilters = Array(2) { BedFilterShape(recipe.surfaceFilter, sampleRate) }
    private val hissFilters = Array(2) { BedFilterShape(NoiseFilterShape.HIGHPASS_FLAT, sampleRate) }
    private val needle = NeedleTexture(recipe, seed, sampleRate)

    private val surfaceLevel = if (recipe.surfaceEnabled) recipe.surfaceLinear else 0f
    private val hissLevel = recipe.hissLinear * 0.35f

    /** How many transients the seed actually produced. Reported in the render log. */
    val crackleCount: Int
    val popCount: Int

    init {
        val planned = ArrayList<Event>(256)
        if (recipe.crackleEnabled && recipe.crackleDensityPerMin > 0f) planCrackle(planned)
        if (recipe.popsEnabled && recipe.popsDensityPerMin > 0f) planPops(planned)

        val durationSeconds = totalFrames.toFloat() / sampleRate
        // The web version refuses events in the first and last 20 ms: a click on the very first sample
        // sounds like a damaged file, and a pop during the fade-out sounds like a bug.
        val kept = planned.filter { event ->
            val seconds = event.startFrame.toFloat() / sampleRate
            seconds > 0.02f && seconds < durationSeconds - 0.02f && event.endFrame <= totalFrames
        }.sortedBy { it.startFrame }

        events = kept
        crackleCount = kept.count { !it.isPop }
        popCount = kept.count { it.isPop }
        for (event in kept) {
            eventRandom[event.startFrame] = Mulberry32(Seeds.child(seed, "event:${event.startFrame}:${event.isPop}"))
        }
    }

    /** Ported from the web renderer: a jittered count, then events placed uniformly, then clusters. */
    private fun planCrackle(into: MutableList<Event>) {
        val durationSeconds = totalFrames.toFloat() / sampleRate
        val expected = (durationSeconds / 60f) * recipe.crackleDensityPerMin
        val jitter = 0.4f
        val count = (expected * (1f - jitter + crackleRandom.nextFloat() * jitter * 2f)).toInt().coerceAtLeast(0)

        repeat(count) {
            into += Event(
                startFrame = (crackleRandom.nextFloat() * durationSeconds * sampleRate).toInt(),
                lengthFrames = ((0.002f + crackleRandom.nextFloat() * 0.012f) * sampleRate).toInt().coerceAtLeast(8),
                amplitude = 0.05f + crackleRandom.nextFloat() * recipe.crackleIntensity * 0.85f,
                bodyHz = 0f,
            )
        }

        // Clusters: several ticks landing close together. Dust does not arrive evenly spaced, and
        // without clusters a high crackle density reads as a metronome rather than as a dirty record.
        val clusterCount = (recipe.crackleDensityPerMin / 6f).toInt().coerceIn(1, 6)
        repeat(clusterCount) {
            val anchor = crackleRandom.nextFloat() * durationSeconds
            val spread = 0.06f
            repeat(2 + crackleRandom.nextInt(4)) {
                into += Event(
                    startFrame = ((anchor + (crackleRandom.nextFloat() - 0.5f) * spread) * sampleRate).toInt()
                        .coerceAtLeast(0),
                    lengthFrames = ((0.001f + crackleRandom.nextFloat() * 0.006f) * sampleRate).toInt().coerceAtLeast(8),
                    amplitude = 0.10f + crackleRandom.nextFloat() * recipe.crackleIntensity * 0.7f,
                    bodyHz = 0f,
                )
            }
        }
    }

    /** Pops are rare, longer and tuned: a resonant body rather than a click. */
    private fun planPops(into: MutableList<Event>) {
        val durationSeconds = totalFrames.toFloat() / sampleRate
        val expected = (durationSeconds / 60f) * recipe.popsDensityPerMin
        val count = (expected + (popRandom.nextFloat() - 0.5f)).toInt().coerceAtLeast(0)
        repeat(count) {
            into += Event(
                startFrame = (popRandom.nextFloat() * durationSeconds * sampleRate).toInt(),
                lengthFrames = (0.03f * sampleRate).toInt(),
                amplitude = 0.15f + popRandom.nextFloat() * recipe.popsIntensity * 0.85f,
                bodyHz = 380f + popRandom.nextFloat() * 240f,
            )
        }
    }

    /**
     * Fills [out] with [frames] stereo frames starting at [fromFrame], interleaved and dry (no bed
     * ceiling applied yet — the caller mixes this with the voice and the limiter catches the sum).
     *
     * Blocks must be requested in increasing order. That is what makes a block-by-block render identical
     * to a single-pass one: the generators and filters are sequential.
     */
    fun render(fromFrame: Int, frames: Int, out: FloatArray) {
        java.util.Arrays.fill(out, 0, frames * 2, 0f)

        // ---- 1. continuous surface bed, tape hiss, and the slow stereo decorrelation
        if (surfaceLevel > 0f || hissLevel > 0f) {
            for (frame in 0 until frames) {
                val surfaceL = pinkLeft.next() * surfaceLevel
                val surfaceR = pinkRight.next() * surfaceLevel
                val shapedL = bedFilters[0].process(surfaceL) + hissFilters[0].process(hissLeft.next() * hissLevel)
                // The web bed offsets the right channel by up to ±17.5% per sample rather than
                // decorrelating the whole bed: it widens the image without hollowing the centre.
                val correlation = decorrelationRandom.nextFloat() * 0.35f - 0.175f
                val shapedR = bedFilters[1].process(surfaceR + correlation * surfaceLevel) +
                    hissFilters[1].process(hissRight.next() * hissLevel)
                out[frame * 2] = shapedL
                out[frame * 2 + 1] = shapedR
            }
        }

        // ---- 2. dust: a fine continuous texture, only where the recipe is old enough to have it
        val dustLevel = dustAmount()
        if (dustLevel > 0f) {
            for (frame in 0 until frames) {
                out[frame * 2] += dustRandom.nextGaussian() * dustLevel
                out[frame * 2 + 1] += dustRandom.nextGaussian() * dustLevel * 0.9f
            }
        }

        // ---- 3. transients, planned at construction and rendered as they arrive
        val blockEnd = fromFrame + frames
        while (nextEvent < events.size && events[nextEvent].startFrame < blockEnd) {
            active += nextEvent
            nextEvent++
        }
        if (active.isNotEmpty()) {
            var index = 0
            while (index < active.size) {
                val eventIndex = active[index]
                val event = events[eventIndex]
                renderEvent(eventIndex, event, fromFrame, frames, out)
                if (event.endFrame <= blockEnd) {
                    active.removeAt(index)
                } else {
                    index++
                }
            }
        }

        // ---- 4. the needle itself, at the very start of the record
        needle.render(fromFrame, frames, out)
    }

    /** How much fine dust the recipe implies, over and above the bed and the crackle. */
    private fun dustAmount(): Float {
        val grit = (recipe.crackleIntensity - 0.3f).coerceAtLeast(0f) * 0.012f
        val age = (recipe.saturationDrive - 0.4f).coerceAtLeast(0f) * 0.008f
        return grit + age
    }

    /**
     * One crackle or pop.
     *
     * A crackle is a decaying burst of noise — a broadband tick, tilted by the recipe's brightness. A
     * pop is a decaying sine with a sharp initial spike, smoothed by a one-pole, which is what gives it
     * the physical "thump with a body" character instead of sounding like a digital click.
     */
    private fun renderEvent(eventIndex: Int, event: Event, fromFrame: Int, frames: Int, out: FloatArray) {
        val start = maxOf(event.startFrame, fromFrame)
        val end = minOf(event.endFrame, fromFrame + frames)
        if (start >= end) return

        val random = eventRandom[eventIndex] ?: return
        val bodyStep = if (event.isPop) 2.0 * PI * event.bodyHz / sampleRate else 0.0
        val pin = exp(-1.0 / sampleRate)
        val filter = if (event.isPop) popFilter.getOrPut(eventIndex) { FloatArray(2) } else null
        val brightness = 0.6f + 0.4f * recipe.crackleBrightness

        for (frame in start until end) {
            val index = frame - event.startFrame
            val t = index.toFloat() / (event.lengthFrames - 1).coerceAtLeast(1)
            val envelope = if (event.isPop) {
                (exp(-t * 12.0) * sqrt(t + 0.05)).toFloat()
            } else {
                exp(-t * 24.0).toFloat()
            }

            val target = frame - fromFrame
            if (event.isPop) {
                val body = sin(bodyStep * index) * envelope * event.amplitude
                filter!![0] = (pin * filter[0] + (1f - pin) * body).toFloat()
                filter[1] = (pin * filter[1] + (1f - pin) * body).toFloat()
                out[target * 2] += filter[0]
                out[target * 2 + 1] += filter[1]
            } else {
                // Independent noise per channel: a crackle that is identical in both channels sits
                // dead centre and reads as a digital artefact rather than as a speck of dust.
                out[target * 2] += (random.nextFloat() * 2f - 1f) * brightness * envelope * event.amplitude
                out[target * 2 + 1] += (random.nextFloat() * 2f - 1f) * brightness * envelope * event.amplitude
            }
        }
    }
}

/**
 * The needle touching the record.
 *
 * A short broadband thump where the stylus lands, then a low rumble that decays away over the lead-in.
 * This is the one layer that appears nowhere else on the record, which is what makes the start of a
 * pressing sound like a physical object being started rather than like a file beginning.
 */
private class NeedleTexture(
    recipe: VinylRecipe,
    seed: Long,
    private val sampleRate: Int,
) {
    private val introFrames = (recipe.needleIntroMs / 1000f * sampleRate).toInt()
    private val contactFrames = (0.06f * sampleRate).toInt()
    private val contactRandom = Mulberry32(Seeds.child(seed, "needle-contact"))
    private val rumbleRandom = Mulberry32(Seeds.child(seed, "needle-rumble"))
    private val rumbleFilter = DspMath.OnePole().apply { lowPass(28f, sampleRate) }
    private var cursor = 0

    fun render(fromFrame: Int, frames: Int, out: FloatArray) {
        if (introFrames <= 0) return
        // Blocks arrive in order, so the cursor and fromFrame agree; if a caller ever seeks, the cursor
        // is realigned rather than replaying the thump in the wrong place.
        if (fromFrame != cursor) cursor = fromFrame
        val blockEnd = fromFrame + frames

        if (cursor < contactFrames && blockEnd > 0) {
            val start = maxOf(cursor, 0)
            val end = minOf(contactFrames, blockEnd)
            for (frame in start until end) {
                val t = frame.toFloat() / sampleRate
                val thump = contactRandom.nextGaussian() * exp(-70.0 * t).toFloat() * 0.22f
                val target = frame - fromFrame
                out[target * 2] += thump
                out[target * 2 + 1] += thump * 0.94f
            }
        }

        if (blockEnd > contactFrames && cursor < introFrames) {
            val start = maxOf(cursor, contactFrames)
            val end = minOf(introFrames, blockEnd)
            val span = (introFrames - contactFrames).coerceAtLeast(1)
            for (frame in start until end) {
                val progress = (frame - contactFrames).toFloat() / span
                val level = (1f - progress).coerceIn(0f, 1f) * 0.016f
                val noise = rumbleFilter.process(rumbleRandom.nextGaussian())
                val target = frame - fromFrame
                out[target * 2] += noise * level
                out[target * 2 + 1] += noise * level * 0.92f
            }
        }
        cursor = blockEnd
    }
}

/**
 * Paul Kellet's economy pink-noise filter, ported from `lib/audio/vinylBed.ts`.
 *
 * White noise shaped to roughly -3 dB per octave. A flat white bed reads as tape hiss; the pink tilt is
 * what reads as a *groove* — the low roar under the music that a listener hears between tracks.
 */
class PinkNoise(private val random: Mulberry32) {
    private var b0 = 0f
    private var b1 = 0f
    private var b2 = 0f
    private var b3 = 0f
    private var b4 = 0f
    private var b5 = 0f
    private var b6 = 0f

    fun next(): Float {
        val white = random.nextFloat() * 2f - 1f
        b0 = 0.99886f * b0 + white * 0.0555179f
        b1 = 0.99332f * b1 + white * 0.0750759f
        b2 = 0.96900f * b2 + white * 0.1538520f
        b3 = 0.86650f * b3 + white * 0.3104856f
        b4 = 0.55000f * b4 + white * 0.5329522f
        b5 = -0.7616f * b5 - white * 0.0168980f
        val value = (b0 + b1 + b2 + b3 + b4 + b5 + b6 + white * 0.5362f) * 0.11f
        b6 = white * 0.115926f
        return value
    }
}

/**
 * The surface bed's tone shaping, matching the web renderer's `stereoHighPass` / `stereoLowPass` pairs.
 *
 * FFmpeg's `highpass`/`lowpass` at Q=0.5 are second-order; the web bed used one-pole filters. Both are
 * audible and the difference is small but real — the one-pole version leaves more low end under a
 * `HIGHPASS_LOW` bed, which is the point of that shape. Ported as-is rather than "improved", because
 * the presets were tuned against it.
 */
class BedFilterShape(shape: NoiseFilterShape, sampleRate: Int) {
    private val highPass: DspMath.OnePole? = when (shape) {
        NoiseFilterShape.HIGHPASS_LOW -> DspMath.OnePole().apply { highPass(220f, sampleRate) }
        NoiseFilterShape.HIGHPASS_FLAT -> DspMath.OnePole().apply { highPass(80f, sampleRate) }
        NoiseFilterShape.FLAT -> DspMath.OnePole().apply { highPass(90f, sampleRate) }
        NoiseFilterShape.LOWPASS_ONLY -> null
    }

    private val lowPass: DspMath.OnePole? = when (shape) {
        NoiseFilterShape.LOWPASS_ONLY -> DspMath.OnePole().apply { lowPass(4_500f, sampleRate) }
        NoiseFilterShape.FLAT -> DspMath.OnePole().apply { lowPass(17_500f, sampleRate) }
        else -> null
    }

    fun process(input: Float): Float {
        var value = input
        highPass?.let { value = it.process(value) }
        lowPass?.let { value = it.process(value) }
        return value
    }

    companion object {
        /** The bed's peak never exceeds -1 dBFS, so it can never be what clips the master. */
        fun guard(value: Float): Float = DspMath.softClip(value, 0.894f)

        /** Written into the render log, so a preset's shaping is visible without listening to it. */
        fun describe(shape: NoiseFilterShape): String = when (shape) {
            NoiseFilterShape.FLAT -> "high-pass 90 Hz + low-pass 17.5 kHz"
            NoiseFilterShape.HIGHPASS_LOW -> "high-pass 220 Hz"
            NoiseFilterShape.HIGHPASS_FLAT -> "high-pass 80 Hz"
            NoiseFilterShape.LOWPASS_ONLY -> "low-pass 4.5 kHz"
        }
    }
}
