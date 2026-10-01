package com.vynylrecord.turntable.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tonearm has to be believable: the stylus must stay inside the audio band, and the cheap linear
 * progress mapping the animator uses every frame must stay within a fraction of a degree of the exact
 * two-circle solution.
 */
class TonearmGeometryTest {

    @Test
    fun theSweepRunsFromTheOuterEdgeToTheInnerGroove() {
        assertTrue("the arm parks outside the record", TurntableSpec.TONEARM_REST_ANGLE_DEG < TurntableGeometryLeadIn())
        assertEquals(TurntableSpec.AUDIO_BAND_OUTER, TonearmGeometry.tipRadiusForAngle(TonearmGeometry.LEAD_IN_ANGLE_DEG), 1e-4f)
        assertEquals(TurntableSpec.AUDIO_BAND_INNER, TonearmGeometry.tipRadiusForAngle(TonearmGeometry.RUN_OUT_ANGLE_DEG), 1e-4f)
        assertEquals(
            TonearmGeometry.RUN_OUT_ANGLE_DEG - TonearmGeometry.LEAD_IN_ANGLE_DEG,
            TonearmGeometry.SWEEP_DEG,
            1e-4f,
        )
        assertTrue("the sweep is a real arc", TonearmGeometry.SWEEP_DEG > 10f)
    }

    private fun TurntableGeometryLeadIn(): Float = TonearmGeometry.LEAD_IN_ANGLE_DEG

    @Test
    fun progressMapsMonotonicallyOnToTheAudioBand() {
        var previous = -1f
        var progress = 0f
        while (progress <= 1f) {
            val angle = TonearmGeometry.angleForProgress(progress)
            assertTrue("angle must increase with progress", angle > previous)
            val radius = TonearmGeometry.tipRadiusForAngle(angle)
            assertTrue("the stylus must stay in the band, radius=$radius", radius in (TurntableSpec.AUDIO_BAND_INNER - 1e-3f)..(TurntableSpec.AUDIO_BAND_OUTER + 1e-3f))
            previous = angle
            progress += 0.05f
        }
        assertEquals(TonearmGeometry.LEAD_IN_ANGLE_DEG, TonearmGeometry.angleForProgress(0f), 1e-4f)
        assertEquals(TonearmGeometry.RUN_OUT_ANGLE_DEG, TonearmGeometry.angleForProgress(1f), 1e-4f)
    }

    @Test
    fun progressSurvivesARoundTrip() {
        var progress = 0f
        while (progress <= 1f) {
            assertEquals(progress, TonearmGeometry.progressForAngle(TonearmGeometry.angleForProgress(progress)), 1e-4f)
            progress += 0.1f
        }
    }

    @Test
    fun theLinearMappingStaysWithinAFractionOfADegreeOfTheExactSolution() {
        var progress = 0f
        while (progress <= 1f) {
            val exactAngle = TonearmGeometry.angleForTipRadius(
                MathUtils.lerp(TurntableSpec.AUDIO_BAND_OUTER, TurntableSpec.AUDIO_BAND_INNER, progress),
            )
            assertEquals(exactAngle, TonearmGeometry.angleForProgress(progress), 0.5f)
            progress += 0.05f
        }
    }

    @Test
    fun theStylusNeverLeavesTheRecordSurface() {
        val tip = Vec3()
        var progress = 0f
        while (progress <= 1f) {
            TonearmGeometry.tipPosition(TonearmGeometry.angleForProgress(progress), tip)
            val dx = tip.x - TurntableSpec.PLATTER_CENTER_X
            val dz = tip.z - TurntableSpec.PLATTER_CENTER_Z
            val radius = kotlin.math.sqrt(dx * dx + dz * dz)
            assertTrue("tip radius $radius must stay inside the pressing", radius < TurntableSpec.RECORD_RADIUS)
            assertTrue("tip radius $radius must stay outside the label", radius > TurntableSpec.LABEL_RADIUS)
            assertTrue("the stylus sits on the record", tip.y < TurntableSpec.RECORD_TOP + 0.002f)
            progress += 0.1f
        }
    }

    @Test
    fun theParkedArmClearsThePlatterAndEveryControl() {
        val tip = Vec3()
        TonearmGeometry.tipPosition(TurntableSpec.TONEARM_REST_ANGLE_DEG, tip)
        val centreDistance = kotlin.math.hypot(
            tip.x - TurntableSpec.PLATTER_CENTER_X,
            tip.z - TurntableSpec.PLATTER_CENTER_Z,
        )
        assertTrue("the parked stylus must not sit over the platter", centreDistance > TurntableSpec.PLATTER_RADIUS)

        val controls = listOf(
            floatArrayOf(TurntableSpec.POWER_BUTTON_X, TurntableSpec.POWER_BUTTON_Z, TurntableSpec.POWER_BUTTON_RADIUS),
            floatArrayOf(TurntableSpec.SPEED_SELECTOR_X, TurntableSpec.SPEED_SELECTOR_Z, TurntableSpec.SPEED_SELECTOR_RADIUS),
            floatArrayOf(TurntableSpec.KNOB_X, TurntableSpec.KNOB_Z, TurntableSpec.KNOB_RADIUS),
            floatArrayOf(TurntableSpec.INDICATOR_X, TurntableSpec.INDICATOR_Z, TurntableSpec.INDICATOR_RADIUS),
        )
        for (control in controls) {
            val distance = kotlin.math.hypot(tip.x - control[0], tip.z - control[1])
            assertTrue("the stylus must not foul a front control", distance > control[2] + 0.02f)
        }
    }

    @Test
    fun progressFromRadiusInvertsProgressFromAngle() {
        var progress = 0f
        while (progress <= 1f) {
            val radius = MathUtils.lerp(TurntableSpec.AUDIO_BAND_OUTER, TurntableSpec.AUDIO_BAND_INNER, progress)
            assertEquals(progress, TonearmGeometry.progressForRadius(radius), 0.02f)
            progress += 0.1f
        }
    }

    @Test
    fun theSpecIsSelfConsistent() {
        assertEquals(TurntableSpec.PLINTH_BOTTOM + TurntableSpec.PLINTH_HEIGHT, TurntableSpec.PLINTH_TOP, 1e-6f)
        assertEquals(TurntableSpec.PLINTH_TOP + TurntableSpec.PLATTER_HEIGHT, TurntableSpec.PLATTER_TOP, 1e-6f)
        assertEquals(TurntableSpec.PLATTER_TOP + TurntableSpec.MAT_THICKNESS, TurntableSpec.MAT_TOP, 1e-6f)
        assertEquals(TurntableSpec.MAT_TOP + TurntableSpec.RECORD_THICKNESS, TurntableSpec.RECORD_TOP, 1e-6f)
        assertEquals(TurntableSpec.RECORD_TOP + TurntableSpec.LABEL_THICKNESS, TurntableSpec.LABEL_TOP, 1e-6f)
        assertTrue("the record must overhang the mat", TurntableSpec.RECORD_RADIUS > TurntableSpec.MAT_RADIUS)
        assertTrue("the platter must be wider than the record", TurntableSpec.PLATTER_RADIUS > TurntableSpec.RECORD_RADIUS)
        assertTrue("the label sits inside the audio band", TurntableSpec.LABEL_RADIUS < TurntableSpec.AUDIO_BAND_INNER)
        assertEquals(TurntableSpec.NOMINAL_RPM * 6f, TurntableSpec.degreesPerSecondFor(PlatterSpeed.THIRTY_THREE), 1e-3f)
        assertEquals(270f, TurntableSpec.degreesPerSecondFor(PlatterSpeed.FORTY_FIVE), 1e-3f)
    }
}
