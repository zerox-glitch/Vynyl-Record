package com.vynylrecord.turntable.graphics

import com.vynylrecord.turntable.model.MathUtils
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.cos

/**
 * Orbit camera with inertia, damping and hard limits.
 *
 * State is spherical (`azimuth`, `elevation`, `distance`) around a mutable target on the deck.
 * Every mutator is synchronized because touches arrive on the UI thread while [update] and
 * [writeTo] run on the GL thread; the critical sections are a handful of floats, so contention is
 * irrelevant next to a frame.
 *
 * Limits exist to keep the renderer honest:
 *  * elevation never drops below [MIN_ELEVATION_DEG], so the camera can never see under the floor
 *    or clip through the plinth's underside;
 *  * distance is bounded so the near plane cannot enter the platter or the tonearm;
 *  * panning is clamped to the deck, so the turntable cannot be pushed out of frame.
 */
class CameraRig {

    /** Snapshot passed to the GL thread; pooled by the renderer to avoid allocations. */
    class Snapshot {
        @JvmField var azimuthDeg = 0f
        @JvmField var elevationDeg = 0f
        @JvmField var distance = 0f
        @JvmField var targetX = 0f
        @JvmField var targetY = 0f
        @JvmField var targetZ = 0f
        @JvmField var fovYDegrees = DEFAULT_FOV_Y
    }

    private val lock = Any()

    private var azimuthDeg = CameraPreset.HERO.azimuth
    private var elevationDeg = CameraPreset.HERO.elevation
    private var distance = CameraPreset.HERO.distance
    private var targetX = CameraPreset.HERO.targetX
    private var targetY = CameraPreset.HERO.targetY
    private var targetZ = CameraPreset.HERO.targetZ

    private var azimuthVelocity = 0f
    private var elevationVelocity = 0f
    private var distanceVelocity = 0f
    private var panVelocityX = 0f
    private var panVelocityZ = 0f

    private var dragging = false
    private var idleSeconds = 0f
    private var autoOrbit = true
    private var reducedMotion = false

    /** Set when the user has interacted: the idle auto-orbit waits for [IDLE_BEFORE_ORBIT_SECONDS]. */
    @Volatile
    var hasUserInteracted = false
        private set

    fun setReducedMotion(enabled: Boolean) {
        synchronized(lock) {
            reducedMotion = enabled
            if (enabled) {
                azimuthVelocity = 0f
                elevationVelocity = 0f
                distanceVelocity = 0f
                panVelocityX = 0f
                panVelocityZ = 0f
            }
        }
    }

    fun setAutoOrbitEnabled(enabled: Boolean) {
        synchronized(lock) { autoOrbit = enabled }
    }

    fun beginDrag() {
        synchronized(lock) {
            dragging = true
            idleSeconds = 0f
            azimuthVelocity = 0f
            elevationVelocity = 0f
            hasUserInteracted = true
        }
    }

    /**
     * One-finger orbit. Movement is normalised by the viewport height so the on-screen response
     * is identical on a phone and a tablet.
     *
     * @param deltaX pixels moved horizontally (dragging right should reveal the right side).
     * @param deltaY pixels moved vertically.
     */
    fun drag(deltaX: Float, deltaY: Float, viewportHeight: Int) {
        synchronized(lock) {
            val scale = ORBIT_DEGREES_PER_SCREEN / viewportHeight.coerceAtLeast(1)
            val deltaAzimuth = -deltaX * scale
            val deltaElevation = deltaY * scale
            azimuthDeg = normalizeAzimuth(azimuthDeg + deltaAzimuth)
            elevationDeg = MathUtils.clamp(elevationDeg + deltaElevation, MIN_ELEVATION_DEG, MAX_ELEVATION_DEG)
            // Track velocity in degrees per second using the gesture's instantaneous rate.
            azimuthVelocity = -deltaX * scale * INERTIA_GAIN
            elevationVelocity = deltaY * scale * INERTIA_GAIN
            idleSeconds = 0f
        }
    }

    fun endDrag() {
        synchronized(lock) {
            dragging = false
            idleSeconds = 0f
            if (reducedMotion) {
                azimuthVelocity = 0f
                elevationVelocity = 0f
            }
        }
    }

    /** Pinch zoom. [factor] > 1 moves away, < 1 moves closer. */
    fun zoomBy(factor: Float) {
        synchronized(lock) {
            val target = MathUtils.clamp(distance * factor, MIN_DISTANCE, MAX_DISTANCE)
            distanceVelocity = (target - distance) / ZOOM_RESPONSE_SECONDS
            distance = target
            idleSeconds = 0f
            hasUserInteracted = true
        }
    }

    /** Two-finger pan: slides the target across the deck plane, clamped to the plinth. */
    fun pan(deltaX: Float, deltaY: Float, viewportHeight: Int) {
        synchronized(lock) {
            val worldPerPixel = (distance * PAN_SENSITIVITY) / viewportHeight.coerceAtLeast(1)
            val azimuthRadians = MathUtils.degToRad(azimuthDeg)
            val rightX = cos(azimuthRadians)
            val rightZ = -sin(azimuthRadians)
            val forwardX = sin(azimuthRadians)
            val forwardZ = cos(azimuthRadians)
            targetX = MathUtils.clamp(
                targetX - rightX * deltaX * worldPerPixel + forwardX * deltaY * worldPerPixel,
                -PAN_LIMIT_X, PAN_LIMIT_X,
            )
            targetZ = MathUtils.clamp(
                targetZ - rightZ * deltaX * worldPerPixel + forwardZ * deltaY * worldPerPixel,
                -PAN_LIMIT_Z, PAN_LIMIT_Z,
            )
            targetY = MathUtils.clamp(targetY, MIN_TARGET_Y, MAX_TARGET_Y)
            panVelocityX = 0f
            panVelocityZ = 0f
            idleSeconds = 0f
            hasUserInteracted = true
        }
    }

    /** Snaps or eases back to the default hero framing. */
    fun reset(instant: Boolean) {
        applyPreset(CameraPreset.HERO, instant)
    }

    fun applyPreset(preset: CameraPreset, instant: Boolean) {
        synchronized(lock) {
            if (instant) {
                azimuthDeg = preset.azimuth
                elevationDeg = preset.elevation
                distance = preset.distance
                targetX = preset.targetX
                targetY = preset.targetY
                targetZ = preset.targetZ
                azimuthVelocity = 0f
                elevationVelocity = 0f
                distanceVelocity = 0f
            } else {
                // Ease by setting velocities towards the preset; update() integrates and clamps.
                val seconds = if (reducedMotion) PRESET_REDUCED_SECONDS else PRESET_SECONDS
                azimuthVelocity = MathUtils.shortestAngleDelta(azimuthDeg, preset.azimuth) / seconds
                elevationVelocity = (preset.elevation - elevationDeg) / seconds
                distanceVelocity = (preset.distance - distance) / seconds
                panVelocityX = (preset.targetX - targetX) / seconds
                panVelocityZ = (preset.targetZ - targetZ) / seconds
                pendingTargetY = preset.targetY
                pendingTargetSeconds = seconds
            }
            idleSeconds = 0f
        }
    }

    private var pendingTargetY = Float.NaN
    private var pendingTargetSeconds = 0.5f

    /**
     * Advances inertia, easing and the idle auto-orbit. Frame-rate independent: every term is
     * integrated with the real delta, so behaviour is identical at 30, 60 or 120 Hz.
     */
    fun update(deltaSeconds: Float) {
        val delta = deltaSeconds.coerceIn(0f, 0.05f)
        synchronized(lock) {
            if (dragging) {
                idleSeconds = 0f
                return
            }

            val decay = exp(-INERTIA_DAMPING * delta)
            val zoomDecay = exp(-ZOOM_DAMPING * delta)
            val presetDecay = exp(-PRESET_DAMPING * delta)

            // Orbit inertia from the last gesture, or easing towards a preset.
            if (azimuthVelocity != 0f || elevationVelocity != 0f) {
                azimuthDeg = normalizeAzimuth(azimuthDeg + azimuthVelocity * delta)
                elevationDeg = MathUtils.clamp(
                    elevationDeg + elevationVelocity * delta,
                    MIN_ELEVATION_DEG,
                    MAX_ELEVATION_DEG,
                )
                azimuthVelocity *= decay
                elevationVelocity *= decay
                if (kotlin.math.abs(azimuthVelocity) < PRESET_SETTLE_EPSILON) azimuthVelocity = 0f
                if (kotlin.math.abs(elevationVelocity) < PRESET_SETTLE_EPSILON) elevationVelocity = 0f
            }

            // Distance easing (pinch releases and camera presets both land here).
            if (distanceVelocity != 0f) {
                distance = MathUtils.clamp(distance + distanceVelocity * delta, MIN_DISTANCE, MAX_DISTANCE)
                distanceVelocity *= zoomDecay
                if (kotlin.math.abs(distanceVelocity) < 0.0015f) distanceVelocity = 0f
            }

            // Target easing across the deck.
            if (panVelocityX != 0f || panVelocityZ != 0f) {
                targetX = MathUtils.clamp(targetX + panVelocityX * delta, -PAN_LIMIT_X, PAN_LIMIT_X)
                targetZ = MathUtils.clamp(targetZ + panVelocityZ * delta, -PAN_LIMIT_Z, PAN_LIMIT_Z)
                panVelocityX *= presetDecay
                panVelocityZ *= presetDecay
                if (kotlin.math.abs(panVelocityX) < 1e-4f) panVelocityX = 0f
                if (kotlin.math.abs(panVelocityZ) < 1e-4f) panVelocityZ = 0f
            }

            if (!pendingTargetY.isNaN()) {
                targetY = MathUtils.damp(targetY, pendingTargetY, 1f / pendingTargetSeconds, delta)
                if (kotlin.math.abs(targetY - pendingTargetY) < 1e-4f) {
                    targetY = pendingTargetY
                    pendingTargetY = Float.NaN
                }
            } else {
                targetY = MathUtils.clamp(targetY, MIN_TARGET_Y, MAX_TARGET_Y)
            }

            // Idle auto-orbit: slow, and suppressed entirely by reduced-motion settings.
            idleSeconds += delta
            if (!hasUserInteracted) idleSeconds = IDLE_BEFORE_ORBIT_SECONDS + 1f
            if (autoOrbit && !reducedMotion && idleSeconds > IDLE_BEFORE_ORBIT_SECONDS) {
                azimuthDeg = normalizeAzimuth(azimuthDeg + IDLE_ORBIT_DEGREES_PER_SECOND * delta)
            }
        }
    }

    fun writeTo(snapshot: Snapshot) {
        synchronized(lock) {
            snapshot.azimuthDeg = azimuthDeg
            snapshot.elevationDeg = elevationDeg
            snapshot.distance = distance
            snapshot.targetX = targetX
            snapshot.targetY = targetY
            snapshot.targetZ = targetZ
            snapshot.fovYDegrees = DEFAULT_FOV_Y
        }
    }

    /** True when the rig is still moving; used to keep rendering while others would idle. */
    fun isSettled(): Boolean = synchronized(lock) {
        dragging || azimuthVelocity != 0f || elevationVelocity != 0f || distanceVelocity != 0f ||
            (autoOrbit && !reducedMotion)
    }

    fun positionOf(snapshot: Snapshot, outXyz: FloatArray) {
        val azimuth = MathUtils.degToRad(snapshot.azimuthDeg)
        val elevation = MathUtils.degToRad(snapshot.elevationDeg)
        val horizontal = cos(elevation) * snapshot.distance
        outXyz[0] = snapshot.targetX + horizontal * sin(azimuth)
        outXyz[1] = snapshot.targetY + sin(elevation) * snapshot.distance
        outXyz[2] = snapshot.targetZ + horizontal * cos(azimuth)
    }

    private fun normalizeAzimuth(degrees: Float): Float {
        var value = degrees % 360f
        if (value > 180f) value -= 360f
        if (value < -180f) value += 360f
        return value
    }

    companion object {
        const val MIN_ELEVATION_DEG = 6f
        const val MAX_ELEVATION_DEG = 82f
        const val MIN_DISTANCE = 0.20f
        const val MAX_DISTANCE = 1.60f
        const val PAN_LIMIT_X = 0.30f
        const val PAN_LIMIT_Z = 0.26f
        const val MIN_TARGET_Y = 0.02f
        const val MAX_TARGET_Y = 0.30f
        const val DEFAULT_FOV_Y = 34f

        private const val ORBIT_DEGREES_PER_SCREEN = 165f
        private const val INERTIA_GAIN = 3.2f
        private const val INERTIA_DAMPING = 5.4f
        private const val ZOOM_RESPONSE_SECONDS = 0.22f
        private const val ZOOM_DAMPING = 9f
        private const val PRESET_SECONDS = 0.55f
        private const val PRESET_REDUCED_SECONDS = 0.16f
        private const val PRESET_DAMPING = 6f
        private const val PRESET_SETTLE_EPSILON = 0.02f
        private const val PAN_SENSITIVITY = 0.9f
        private const val IDLE_BEFORE_ORBIT_SECONDS = 9f
        private const val IDLE_ORBIT_DEGREES_PER_SECOND = 4.2f
    }
}
