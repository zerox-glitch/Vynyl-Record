package com.vynylrecord.app.core.audio.dsp

/**
 * The pseudo-random generator every procedural layer is driven from.
 *
 * This is Mulberry32, ported bit for bit from the web renderer's `lib/audio/random.ts`, including
 * JavaScript's `Math.imul` (a wrapping 32-bit signed multiply, which is exactly what Kotlin's `Int`
 * multiply does). Keeping the algorithm identical matters for more than tidiness: a `.vynyl` bundle
 * exported from the web app carries a seed, and importing it here should reproduce the same pattern of
 * pops and crackles rather than a differently-seeded one.
 *
 * It is deliberately *not* a cryptographic generator. Nothing here is a secret; the requirement is
 * only that the same input produces the same output on every device, forever.
 */
class Mulberry32(seed: Long) {

    private var state: Int = seed.toInt()

    /** A uniform value in [0, 1). */
    fun nextFloat(): Float {
        state += 0x6D2B79F5
        var t = state
        t = (t xor (t ushr 15)) * (t or 1)
        t = t xor (t + (t xor (t ushr 7)) * (t or 61))
        return ((t xor (t ushr 14)).toUInt().toFloat() / 4_294_967_296f)
    }

    /** A uniform value in [0, 1). */
    fun nextDouble(): Double = nextFloat().toDouble()

    fun nextInt(bound: Int): Int {
        if (bound <= 0) return 0
        return (nextDouble() * bound).toInt().coerceIn(0, bound - 1)
    }

    fun range(min: Float, max: Float): Float = min + (max - min) * nextFloat()

    fun chance(probability: Float): Boolean = nextFloat() < probability

    /**
     * Standard normal, by the Box-Muller transform.
     *
     * Used for the noise beds: uniform white noise sounds slightly hollow, and the beds are meant to
     * read as a physical surface rather than as a synthesised hiss.
     */
    fun nextGaussian(): Float {
        val u1 = (nextDouble()).coerceAtLeast(1e-12)
        val u2 = nextDouble()
        val magnitude = kotlin.math.sqrt(-2.0 * kotlin.math.ln(u1))
        return (magnitude * kotlin.math.cos(2.0 * Math.PI * u2)).toFloat()
    }
}

/**
 * The seed arithmetic from the web renderer.
 *
 * `childSeed` is what keeps the layers from sounding like each other: the surface, the crackle, the pops
 * and the stereo decorrelation all start from the record's seed but diverge immediately. Without it,
 * three streams reading from one generator interleave and the result is subtly correlated — the classic
 * "all the beds sound the same" bug.
 */
object Seeds {

    /** FNV-1a. Stable across machines, runs and languages. */
    fun hash(input: String): Long {
        var hash = 0x811C9DC5L
        for (character in input) {
            hash = hash xor character.code.toLong()
            hash = (hash * 0x01000193L) and 0xFFFFFFFFL
        }
        return hash
    }

    /** Mixes a parent seed with a label to derive an independent downstream stream. */
    fun child(parent: Long, label: String): Long {
        var s = parent.toInt()
        s = s xor hash(label).toInt()
        // xorshift mix, matching the web implementation
        s = s xor (s ushr 16)
        s *= 0x85EBCA6B.toInt()
        s = s xor (s ushr 13)
        s *= 0xC2B2AE35.toInt()
        s = s xor (s ushr 16)
        return s.toLong() and 0xFFFFFFFFL
    }

    fun child(parent: Long, index: Int): Long = child(parent, "idx:$index")
}
