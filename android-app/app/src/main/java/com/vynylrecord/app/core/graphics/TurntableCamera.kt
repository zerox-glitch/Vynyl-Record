package com.vynylrecord.app.core.graphics

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * The camera that orbits the deck.
 *
 * Orbit rather than free-fly: this is a record player on a table, and a person looking at it wants to walk
 * around it, not fly through it. Yaw and pitch are enough, and clamping both keeps the deck from being viewed
 * from underneath — where it has no floor and would show its own innards.
 *
 * The camera holds no Android types and touches no UI, which is what makes the whole 3D layer testable on a
 * JVM: a test can orbit by degrees and assert the resulting matrix, with no emulator and no fingers.
 */
class TurntableCamera {

    /** Degrees around the deck. */
    var yawDegrees: Float = DEFAULT_YAW
        private set

    /** Degrees above the horizon, clamped to [MIN_PITCH]..[MAX_PITCH]. */
    var pitchDegrees: Float = DEFAULT_PITCH
        private set

    /** Distance from the target. */
    var distance: Float = DEFAULT_DISTANCE
        private set

    /** The point the camera looks at: just above the platter, where the record is. */
    val target = Vec3(0f, 0.06f, 0f)

    /** Where the camera is, rebuilt by [update]. */
    val position = Vec3(0f, 0.5f, 1.7f)

    /** The matrix the vertex shader multiplies world positions by. */
    val viewProjectionMatrix = Mat4()

    private val viewMatrix = Mat4()
    private val projectionMatrix = Mat4()
    private val up = Vec3(0f, 1f, 0f)

    /** True while a finger is down, which suppresses the idle drift. */
    var isBeingTouched: Boolean = false

    /** Drift around the deck when nobody has touched it for a while. */
    var autoOrbit: Boolean = false

    private var idleSeconds = 0f

    /** The four framings the player's button cycles through. */
    enum class Preset(val label: String, val yaw: Float, val pitch: Float, val distance: Float) {
        THREE_QUARTER("Three-quarter view", 34f, 26f, 1.62f),
        FRONT("Front view", 0f, 12f, 1.44f),
        OVERHEAD("Overhead view", 18f, 62f, 1.86f),
        CLOSE("Close on the record", 24f, 22f, 1.02f),
        ;

        companion object {
            /** The order the player cycles through. */
            val ordered: List<Preset> = entries.toList()

            /** The framing the deck opens on. */
            val default: Preset = THREE_QUARTER
        }
    }

    /** Applies a framing. The two axes are clamped rather than snapped so a gesture in progress stays smooth. */
    fun apply(preset: Preset) {
        yawDegrees = preset.yaw
        pitchDegrees = preset.pitch.coerceIn(MIN_PITCH, MAX_PITCH)
        distance = preset.distance.coerceIn(MIN_DISTANCE, MAX_DISTANCE)
    }

    /**
     * Applies the next framing, taking the shortest way around the circle.
     *
     * Cycling from a 350° camera to a 0° one must not spin the deck three hundred and fifty degrees: an
     * unbounded lerp would make the "front view" button look like a spin of the table.
     */
    fun cyclePreset(): Preset {
        val current = nearestPreset()
        val next = Preset.ordered[(Preset.ordered.indexOf(current) + 1) % Preset.ordered.size]
        val target = next.yaw
        var equivalent = target
        while (equivalent - yawDegrees > 180f) equivalent -= 360f
        while (yawDegrees - equivalent > 180f) equivalent += 360f
        yawDegrees = equivalent
        pitchDegrees = next.pitch.coerceIn(MIN_PITCH, MAX_PITCH)
        distance = next.distance.coerceIn(MIN_DISTANCE, MAX_DISTANCE)
        return next
    }

    /** The framing closest to where the camera is now, for the readout and for cycling. */
    fun nearestPreset(): Preset = Preset.ordered.minByOrNull { preset ->
        val yawDistance = kotlin.math.abs(wrapDegrees(preset.yaw - wrapDegrees(yawDegrees)))
        yawDistance + kotlin.math.abs(preset.pitch - pitchDegrees) * 0.8f + kotlin.math.abs(preset.distance - distance) * 40f
    } ?: Preset.default

    /** Resets to the default framing. */
    fun reset() {
        yawDegrees = Preset.default.yaw
        pitchDegrees = Preset.default.pitch
        distance = Preset.default.distance
    }

    /**
     * Orbits by a drag, in pixels.
     *
     * A finger's worth of drag is a fraction of a turn rather than a degree — 0.32° per pixel puts a full
     * revolution at about a full swipe across a phone, which is the ratio that feels like turning an object.
     */
    fun orbitBy(deltaXPixels: Float, deltaYPixels: Float) {
        yawDegrees = wrapDegrees(yawDegrees + deltaXPixels * DEGREES_PER_PIXEL_X)
        pitchDegrees = (pitchDegrees + deltaYPixels * DEGREES_PER_PIXEL_Y).coerceIn(MIN_PITCH, MAX_PITCH)
        idleSeconds = 0f
    }

    /** Zooms by a pinch factor: above one moves closer. */
    fun zoomBy(factor: Float) {
        if (factor <= 0f) return
        distance = (distance / factor).coerceIn(MIN_DISTANCE, MAX_DISTANCE)
        idleSeconds = 0f
    }

    /**
     * Advances the camera.
     *
     * @param deltaSeconds elapsed real time, so the drift is the same on a 60 Hz and a 120 Hz screen
     */
    fun update(deltaSeconds: Float) {
        if (isBeingTouched) {
            idleSeconds = 0f
        } else if (autoOrbit) {
            idleSeconds += deltaSeconds
            if (idleSeconds > IDLE_BEFORE_ORBIT_SECONDS) {
                yawDegrees = wrapDegrees(yawDegrees + deltaSeconds * DRIFT_DEGREES_PER_SECOND)
            }
        } else {
            idleSeconds = 0f
        }
    }

    /** Rebuilds the position and matrices for this frame's viewport. Called once per frame. */
    fun buildMatrices(width: Int, height: Int) {
        val yawRadians = Math.toRadians(yawDegrees.toDouble()).toFloat()
        val pitchRadians = Math.toRadians(pitchDegrees.toDouble()).toFloat()
        val horizontal = cos(pitchRadians) * distance
        position.set(
            target.x + sin(yawRadians) * horizontal,
            target.y + sin(pitchRadians) * distance,
            target.z + cos(yawRadians) * horizontal,
        )
        val aspect = if (height <= 0) 1f else width.toFloat() / height.toFloat()
        projectionMatrix.perspective(FIELD_OF_VIEW_DEGREES, max(0.2f, aspect), NEAR, FAR)
        viewMatrix.lookAt(position, target, up)
        viewProjectionMatrix.copyFrom(projectionMatrix).multiply(viewMatrix)
    }

    /** A readable state, for the debug readout. */
    fun describe(): String = "yaw %.0f° · pitch %.0f° · %.2f m".format(yawDegrees, pitchDegrees, distance)

    private fun wrapDegrees(value: Float): Float {
        var result = value % 360f
        if (result < 0f) result += 360f
        return result
    }

    companion object {
        const val FIELD_OF_VIEW_DEGREES = 34f
        const val NEAR = 0.02f
        const val FAR = 12f

        /** Nothing goes under the deck: below this the plinth has no bottom and the lamp is inside it. */
        const val MIN_PITCH = 8f
        const val MAX_PITCH = 78f

        const val MIN_DISTANCE = 0.55f
        const val MAX_DISTANCE = 3.4f

        const val DEFAULT_YAW = 34f
        const val DEFAULT_PITCH = 26f
        const val DEFAULT_DISTANCE = 1.62f

        const val DEGREES_PER_PIXEL_X = 0.32f
        const val DEGREES_PER_PIXEL_Y = 0.24f

        const val IDLE_BEFORE_ORBIT_SECONDS = 6f
        const val DRIFT_DEGREES_PER_SECOND = 5f

        /** The dimmest the lamp ever gets, so the deck is never a silhouette. */
        const val MIN_LAMP_INTENSITY = 0.18f
    }
}
