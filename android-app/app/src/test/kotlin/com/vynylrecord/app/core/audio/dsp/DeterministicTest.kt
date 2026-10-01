package com.vynylrecord.app.core.audio.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The generator behind every "random" thing the press does.
 *
 * A record is expected to be reproducible: pressing the same recording twice must produce the same
 * crackle positions, and two different recordings must not share a pattern. That contract lives here, so
 * it is tested here rather than through the renderer.
 */
class DeterministicTest {

    @Test
    fun `the same seed produces the same stream`() {
        val first = Mulberry32(0x5EED_1234L)
        val second = Mulberry32(0x5EED_1234L)
        repeat(512) {
            assertEquals(first.nextFloat(), second.nextFloat(), 0f)
        }
    }

    @Test
    fun `different seeds diverge`() {
        val first = Mulberry32(1L)
        val second = Mulberry32(2L)
        val differences = (0 until 64).count { first.nextFloat() != second.nextFloat() }
        assertEquals(64, differences)
    }

    @Test
    fun `floats stay inside the unit interval`() {
        val random = Mulberry32(99L)
        repeat(20_000) {
            val value = random.nextFloat()
            assertTrue("value $value escaped [0,1)", value >= 0f && value < 1f)
        }
    }

    @Test
    fun `the stream is flat enough for a noise bed`() {
        val random = Mulberry32(7L)
        val buckets = IntArray(10)
        val draws = 200_000
        repeat(draws) { buckets[(random.nextFloat() * 10f).toInt().coerceIn(0, 9)]++ }
        val expected = draws / 10
        val worst = buckets.maxOf { kotlin.math.abs(it - expected) }.toFloat() / expected
        assertTrue("worst bucket deviates by ${worst * 100}%", worst < 0.05f)
    }

    @Test
    fun `nextInt respects its bound`() {
        val random = Mulberry32(4242L)
        repeat(5_000) {
            val value = random.nextInt(7)
            assertTrue(value in 0 until 7)
        }
        assertEquals(0, random.nextInt(1))
    }

    @Test
    fun `range stays inside its bounds and covers them`() {
        val random = Mulberry32(11L)
        var low = Float.MAX_VALUE
        var high = -Float.MAX_VALUE
        repeat(20_000) {
            val value = random.range(-0.25f, 0.75f)
            assertTrue(value >= -0.25f && value < 0.75f)
            low = minOf(low, value)
            high = maxOf(high, value)
        }
        assertTrue("range never approached its floor", low < -0.20f)
        assertTrue("range never approached its ceiling", high > 0.70f)
    }

    @Test
    fun `chance is proportional to its probability`() {
        val random = Mulberry32(5L)
        val hits = (0 until 100_000).count { random.chance(0.25f) }
        assertTrue("got $hits hits out of 100000", hits in 24_000..26_000)
        assertTrue(random.chance(1f))
        assertEquals(0, (0 until 1_000).count { Mulberry32(3L).chance(0f) })
    }

    @Test
    fun `the gaussian generator is centred and bounded`() {
        val random = Mulberry32(31337L)
        val draws = 200_000
        var sum = 0.0
        var worst = 0.0
        repeat(draws) {
            val value = random.nextGaussian().toDouble()
            sum += value
            worst = maxOf(worst, kotlin.math.abs(value))
            assertTrue("gaussian produced " + value + " which is not finite", value.isFinite())
        }
        val mean = sum / draws
        assertTrue("mean was " + mean, kotlin.math.abs(mean) < 0.02)
        assertTrue("tail reached " + worst, worst < 8.0)
    }

    @Test
    fun `Seeds hash is stable and spreads`() {
        assertEquals(Seeds.hash("record-one"), Seeds.hash("record-one"))
        assertNotEquals(Seeds.hash("record-one"), Seeds.hash("record-two"))
        val hashes = (0 until 500).map { Seeds.hash("record-$it") }.toSet()
        assertEquals(500, hashes.size)
    }

    @Test
    fun `child seeds are independent per label and per index`() {
        val parent = Seeds.hash("57d1c1c0-0000-4000-8000-000000000000")
        val labels = listOf("surface", "crackle", "pops", "dust", "stereo-correlation")
        val children = labels.map { Seeds.child(parent, it) }
        assertEquals(labels.size, children.toSet().size)
        assertTrue(children.none { it == parent })

        val indexed = (0 until 64).map { Seeds.child(parent, it) }
        assertEquals(64, indexed.toSet().size)
        assertEquals(Seeds.child(parent, 7), Seeds.child(parent, "idx:7"))
    }

    @Test
    fun `a record and its rerender share a seed`() {
        val id = "0f0e0d0c-1111-4222-8333-444455556666"
        val seed = Seeds.hash(id)
        val first = Mulberry32(Seeds.child(seed, "crackle"))
        val second = Mulberry32(Seeds.child(Seeds.hash(id), "crackle"))
        repeat(32) { assertEquals(first.nextFloat(), second.nextFloat(), 0f) }
    }
}
