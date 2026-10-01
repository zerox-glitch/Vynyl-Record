package com.vynylrecord.turntable.model

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Small, dependency-free scalar helpers shared by the renderer and the animation code. */
object MathUtils {

    const val TWO_PI = 2.0 * PI

    fun clamp(value: Float, min: Float, max: Float): Float = when {
        value < min -> min
        value > max -> max
        else -> value
    }

    fun clamp(value: Double, min: Double, max: Double): Double = when {
        value < min -> min
        value > max -> max
        else -> value
    }

    fun clamp01(value: Float): Float = clamp(value, 0f, 1f)

    fun lerp(start: Float, end: Float, t: Float): Float = start + (end - start) * t

    fun lerp(start: Double, end: Double, t: Double): Double = start + (end - start) * t

    fun smoothStep(edge0: Float, edge1: Float, value: Float): Float {
        if (edge1 == edge0) return if (value < edge0) 0f else 1f
        val t = clamp01((value - edge0) / (edge1 - edge0))
        return t * t * (3f - 2f * t)
    }

    fun degToRad(degrees: Float): Float = (degrees * PI / 180.0).toFloat()

    fun radToDeg(radians: Float): Float = (radians * 180.0 / PI).toFloat()

    /** Exponentially smoothed approach that is stable for any frame delta. */
    fun damp(current: Float, target: Float, rate: Float, deltaSeconds: Float): Float {
        if (rate <= 0f) return target
        val factor = 1f - kotlin.math.exp(-rate * deltaSeconds)
        return current + (target - current) * factor
    }

    /** Shortest signed angular difference in degrees, in (-180, 180]. */
    fun shortestAngleDelta(fromDegrees: Float, toDegrees: Float): Float {
        var delta = (toDegrees - fromDegrees) % 360f
        if (delta > 180f) delta -= 360f
        if (delta <= -180f) delta += 360f
        return delta
    }

    /** Frame-rate independent exponential decay of a spring-like value in [0, 1]. */
    fun settle(value: Float, target: Float, stiffness: Float, deltaSeconds: Float): Float {
        val delta = target - value
        if (abs(delta) < 1e-5f) return target
        return value + delta * clamp01(stiffness * deltaSeconds)
    }

    fun sinDegrees(degrees: Float): Float = sin(degToRad(degrees)).toFloat()

    fun cosDegrees(degrees: Float): Float = cos(degToRad(degrees)).toFloat()
}
