package com.vynylrecord.turntable.model

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Tonearm kinematics for a 9 inch pivoted arm.
 *
 * The arm is modelled as a rigid bar of [EFFECTIVE_LENGTH] metres that pivots about the
 * vertical axis through [TurntableSpec.TONEARM_PIVOT_X]/[TurntableSpec.TONEARM_PIVOT_Z].
 * Azimuth is measured in the XZ plane as `atan2(dz, dx)`, so 0 degrees points along +X,
 * 90 degrees points along +Z (towards the viewer) and 180 degrees points along -X.
 *
 * [angleForTipRadius] inverts the pivot/radius problem with the two-circle intersection:
 * the stylus must sit `radius` from the record centre *and* exactly [EFFECTIVE_LENGTH]
 * from the pivot, which is exactly how a real arm tracks a groove. The lead-in and
 * run-out angles are derived from it, so geometry can never drift from the spec table.
 */
object TonearmGeometry {

    /** Effective length: pivot to stylus, 200 mm. */
    const val EFFECTIVE_LENGTH = 0.20f

    /** Distance from the pivot to the record centre, in metres. */
    val PIVOT_TO_CENTER_DISTANCE: Float = hypot(
        TurntableSpec.PLATTER_CENTER_X - TurntableSpec.TONEARM_PIVOT_X,
        TurntableSpec.PLATTER_CENTER_Z - TurntableSpec.TONEARM_PIVOT_Z,
    )

    /** Azimuth with the stylus at the lead-in groove (outer edge of the audio band). */
    val LEAD_IN_ANGLE_DEG: Float = angleForTipRadius(TurntableSpec.AUDIO_BAND_OUTER)

    /** Azimuth with the stylus in the run-out groove (inner edge of the audio band). */
    val RUN_OUT_ANGLE_DEG: Float = angleForTipRadius(TurntableSpec.AUDIO_BAND_INNER)

    /** Total sweep of the arm across a side of vinyl, in degrees. */
    val SWEEP_DEG: Float = RUN_OUT_ANGLE_DEG - LEAD_IN_ANGLE_DEG

    private const val TWO_PI_DEG = 360f

    /**
     * Azimuth (degrees, normalised to [0, 360)) at which the stylus sits [radius] metres
     * from the record centre.
     */
    fun angleForTipRadius(radius: Float): Float {
        val pivotX = TurntableSpec.TONEARM_PIVOT_X
        val pivotZ = TurntableSpec.TONEARM_PIVOT_Z
        val dx = TurntableSpec.PLATTER_CENTER_X - pivotX
        val dz = TurntableSpec.PLATTER_CENTER_Z - pivotZ
        val distance = max(hypot(dx, dz), 1e-5f)

        // Distance from the pivot to the radical line of the two circles.
        val along = (distance * distance + EFFECTIVE_LENGTH * EFFECTIVE_LENGTH - radius * radius) / (2f * distance)
        val clampedAlong = MathUtils.clamp(along, -EFFECTIVE_LENGTH, EFFECTIVE_LENGTH)
        val heightSquared = EFFECTIVE_LENGTH * EFFECTIVE_LENGTH - clampedAlong * clampedAlong
        val height = if (heightSquared > 0f) sqrt(heightSquared) else 0f

        val unitX = dx / distance
        val unitZ = dz / distance
        // Perpendicular pointing away from the pivots' left side: selects the branch that
        // sweeps across the front of the record, matching a real tonearm.
        val perpendicularX = unitZ
        val perpendicularZ = -unitX

        val baseX = pivotX + clampedAlong * unitX
        val baseZ = pivotZ + clampedAlong * unitZ
        val tipX = baseX + height * perpendicularX
        val tipZ = baseZ + height * perpendicularZ

        var degrees = MathUtils.radToDeg(atan2(tipZ - pivotZ, tipX - pivotX))
        while (degrees < 0f) degrees += TWO_PI_DEG
        while (degrees >= TWO_PI_DEG) degrees -= TWO_PI_DEG
        return degrees
    }

    /** Radial distance of the stylus from the record centre at a given azimuth. */
    fun tipRadiusForAngle(angleDeg: Float): Float {
        val radians = MathUtils.degToRad(angleDeg)
        val tipX = TurntableSpec.TONEARM_PIVOT_X + EFFECTIVE_LENGTH * cos(radians)
        val tipZ = TurntableSpec.TONEARM_PIVOT_Z + EFFECTIVE_LENGTH * sin(radians)
        return hypot(tipX - TurntableSpec.PLATTER_CENTER_X, tipZ - TurntableSpec.PLATTER_CENTER_Z)
    }

    /** Stylus position in world space for a given azimuth. */
    fun tipPosition(angleDeg: Float, out: Vec3): Vec3 {
        val radians = MathUtils.degToRad(angleDeg)
        out.x = TurntableSpec.TONEARM_PIVOT_X + EFFECTIVE_LENGTH * cos(radians)
        out.y = TurntableSpec.RECORD_TOP
        out.z = TurntableSpec.TONEARM_PIVOT_Z + EFFECTIVE_LENGTH * sin(radians)
        return out
    }

    /**
     * Azimuth for normalised playback progress (0 = lead-in, 1 = run-out).
     *
     * A real arm sweeps almost linearly in azimuth between the lead-in and run-out radii:
     * the chord/angle relation is close to linear over a single side, and the exact inverse
     * [angleForTipRadius] is used to derive the endpoints. [TonearmGeometryTest] asserts the
     * two agree to better than half a degree across the side.
     */
    fun angleForProgress(progress: Float): Float =
        LEAD_IN_ANGLE_DEG + MathUtils.clamp01(progress) * SWEEP_DEG

    /** Inverse of [angleForProgress]: normalised progress for an azimuth. */
    fun progressForAngle(angleDeg: Float): Float {
        if (SWEEP_DEG <= 0f) return 0f
        return MathUtils.clamp01((angleDeg - LEAD_IN_ANGLE_DEG) / SWEEP_DEG)
    }

    /** Normalised progress for a stylus radius: 0 at the lead-in, 1 at the run-out. */
    fun progressForRadius(radius: Float): Float {
        val span = TurntableSpec.AUDIO_BAND_OUTER - TurntableSpec.AUDIO_BAND_INNER
        if (span <= 0f) return 0f
        return MathUtils.clamp01((TurntableSpec.AUDIO_BAND_OUTER - radius) / span)
    }
}
