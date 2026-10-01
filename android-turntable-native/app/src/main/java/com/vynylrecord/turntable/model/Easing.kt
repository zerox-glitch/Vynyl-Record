package com.vynylrecord.turntable.model

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Time-based easing curves used by the animation state machine.
 *
 * Every curve takes and returns a normalised value; the animator always advances with a
 * real frame delta, so behaviour is identical at 30, 60 and 120 Hz.
 */
object Easing {

    fun linear(t: Float): Float = MathUtils.clamp01(t)

    fun smoothStep(t: Float): Float {
        val c = MathUtils.clamp01(t)
        return c * c * (3f - 2f * c)
    }

    fun easeInQuad(t: Float): Float = MathUtils.clamp01(t).pow(2)

    fun easeOutQuad(t: Float): Float {
        val c = MathUtils.clamp01(t)
        return 1f - (1f - c).pow(2)
    }

    fun easeInCubic(t: Float): Float = MathUtils.clamp01(t).pow(3)

    fun easeOutCubic(t: Float): Float {
        val c = MathUtils.clamp01(t)
        return 1f - (1f - c).pow(3)
    }

    fun easeInOutCubic(t: Float): Float {
        val c = MathUtils.clamp01(t)
        return if (c < 0.5f) 4f * c * c * c else 1f - (-2f * c + 2f).pow(3) / 2f
    }

    /**
     * Overshoot then settle: used for the record touching down on the mat, where a real
     * record rebounds very slightly through the felt.
     */
    fun easeOutBack(t: Float, overshoot: Float = 1.18f): Float {
        val c = MathUtils.clamp01(t)
        val c3 = (c - 1f).pow(3)
        return 1f + (overshoot + 1f) * c3 + overshoot * (c - 1f).pow(2)
    }

    /** Critically damped spring step, stable for any delta. */
    fun springStep(value: Float, target: Float, frequencyHz: Float, damping: Float, deltaSeconds: Float): Float {
        val omega = 2f * PI.toFloat() * frequencyHz
        val delta = deltaSeconds.coerceIn(0f, 0.05f)
        val velocity = (target - value) * omega
        val spring = (target - value) * omega * omega - velocity * 2f * damping * omega
        val newVelocity = velocity + spring * delta
        return value + newVelocity * delta
    }

    /** Decaying wobble used for the stylus touch-down micro movement. */
    fun dampedOscillation(t: Float, frequencyHz: Float, damping: Float): Float {
        val c = MathUtils.clamp01(t)
        return kotlin.math.exp(-damping * c * 6f) * sin(c * frequencyHz * 2f * PI.toFloat())
    }

    /** Soft tick used for the mechanism sounds of the state machine (visual accent only). */
    fun bump(t: Float): Float {
        val c = MathUtils.clamp01(t)
        return (1f - c) * cos(c * PI.toFloat() * 0.5f)
    }
}
