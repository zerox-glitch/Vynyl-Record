package com.vynylrecord.turntable.model

/**
 * Physical dimensions of the reference turntable, in metres, with Y up.
 *
 * Everything the renderer draws is derived from these values, so the scene stays coherent:
 * the record really is 12 inch, the mat really does sit on the platter, the stylus really
 * does land on the record surface, and the plinth really is a 460 x 360 mm deck.
 *
 * Deck heights
 * ------------
 *   floor .............. 0.000
 *   foot top / plinth base ... 0.022
 *   plinth top (deck) ... 0.107
 *   platter top ......... 0.127
 *   mat top ............. 0.131
 *   record top .......... 0.1332
 *   spindle top ......... 0.137
 */
object TurntableSpec {

    // ------------------------------------------------------------------ deck / plinth

    const val FLOOR_Y = 0f
    const val FOOT_HEIGHT = 0.022f
    const val PLINTH_BOTTOM = FOOT_HEIGHT
    const val PLINTH_HEIGHT = 0.085f
    const val PLINTH_TOP = PLINTH_BOTTOM + PLINTH_HEIGHT       // 0.107

    const val PLINTH_WIDTH = 0.46f
    const val PLINTH_DEPTH = 0.36f
    const val PLINTH_CORNER_RADIUS = 0.02f
    const val PLINTH_TOP_BEVEL = 0.006f

    /** Purely visual inset plate on the plinth top around the platter. */
    const val PLINTH_INSET_HEIGHT = 0.0012f

    const val FOOT_RADIUS = 0.019f
    const val FOOT_TOP_RADIUS = 0.013f
    const val FOOT_INSET_X = 0.185f
    const val FOOT_INSET_Z = 0.135f

    // ------------------------------------------------------------------ platter

    const val PLATTER_CENTER_X = -0.068f
    const val PLATTER_CENTER_Z = -0.020f
    const val PLATTER_RADIUS = 0.156f
    const val PLATTER_HEIGHT = 0.020f
    const val PLATTER_TOP = PLINTH_TOP + PLATTER_HEIGHT          // 0.127
    const val PLATTER_RIM_HEIGHT = 0.009f
    const val PLATTER_BEVEL = 0.0035f

    /**
     * Real mats are a little smaller than a 12 inch record, which leaves a ring of bare platter
     * around the record. That ring is where the strobe marks live.
     */
    const val MAT_RADIUS = 0.146f
    const val MAT_THICKNESS = 0.004f
    const val MAT_TOP = PLATTER_TOP + MAT_THICKNESS              // 0.131
    const val MAT_EDGE_RADIUS = 0.0015f

    // ------------------------------------------------------------------ record

    /** 12 inch LP: 152.4 mm radius, 2.2 mm thick with the classic raised outer edge. */
    const val RECORD_RADIUS = 0.1524f
    const val RECORD_THICKNESS = 0.0022f
    const val RECORD_BOTTOM = MAT_TOP
    const val RECORD_TOP = RECORD_BOTTOM + RECORD_THICKNESS      // 0.1332
    const val RECORD_EDGE_BEVEL_WIDTH = 0.0025f
    const val RECORD_EDGE_BEVEL_HEIGHT = 0.0006f

    /** Audio band: lead-in groove to run-out groove. */
    const val AUDIO_BAND_OUTER = 0.1465f
    const val AUDIO_BAND_INNER = 0.0625f
    const val GROOVE_PITCH = 6.7e-5f
    /** Relief height used for shading. Exaggerated ~14x; see the shader notes. */
    const val GROOVE_DEPTH = 0.00035f
    const val GROOVE_ECCENTRICITY = 0.0012f

    /** Paper label: 100 mm across with a 7.25 mm spindle hole. */
    const val LABEL_RADIUS = 0.050f
    const val LABEL_HOLE_RADIUS = 0.0037f
    const val LABEL_THICKNESS = 0.0004f
    const val LABEL_TOP = RECORD_TOP + LABEL_THICKNESS           // 0.1336

    // ------------------------------------------------------------------ spindle

    const val SPINDLE_RADIUS = 0.0036f
    const val SPINDLE_TOP = 0.137f
    const val SPINDLE_COLLAR_RADIUS = 0.0075f
    const val SPINDLE_COLLAR_HEIGHT = 0.0022f

    // ------------------------------------------------------------------ tonearm

    /** Pivot sits 62 mm right and 42 mm behind the record centre, 41 mm above the deck. */
    const val TONEARM_PIVOT_X = 0.162f
    const val TONEARM_PIVOT_Y = 0.148f
    const val TONEARM_PIVOT_Z = -0.062f

    /** Headshell offset angle: 23 degrees, as on a real 9 inch arm. */
    const val TONEARM_HEADSHELL_OFFSET_DEG = 23f
    const val TONEARM_TUBE_RADIUS = 0.0055f
    const val TONEARM_TUBE_LENGTH = 0.15f
    const val TONEARM_COUNTERWEIGHT_RADIUS = 0.019f
    const val TONEARM_REST_ANGLE_DEG = 96f
    const val TONEARM_LIFT_MAX_DEG = 2.8f

    // ------------------------------------------------------------------ controls

    const val POWER_BUTTON_RADIUS = 0.015f
    const val POWER_BUTTON_X = -0.150f
    const val POWER_BUTTON_Z = 0.158f

    const val SPEED_SELECTOR_RADIUS = 0.011f
    const val SPEED_SELECTOR_X = -0.104f
    const val SPEED_SELECTOR_Z = 0.160f

    const val KNOB_RADIUS = 0.014f
    const val KNOB_X = -0.055f
    const val KNOB_Z = 0.158f

    const val INDICATOR_RADIUS = 0.0045f
    const val INDICATOR_X = -0.192f
    const val INDICATOR_Z = 0.160f

    // ------------------------------------------------------------------ playback

    /** 33 1/3 rpm. */
    const val NOMINAL_RPM = 33.3333f
    /** 45 rpm, selected by the speed selector. */
    const val HIGH_RPM = 45f

    fun nominalRpmFor(speed: PlatterSpeed): Float = when (speed) {
        PlatterSpeed.THIRTY_THREE -> NOMINAL_RPM
        PlatterSpeed.FORTY_FIVE -> HIGH_RPM
    }

    fun degreesPerSecondFor(speed: PlatterSpeed): Float = nominalRpmFor(speed) * 6f

    /** Convenience: world position of the record centre on the deck plane. */
    fun platterCenterX(): Float = PLATTER_CENTER_X

    fun platterCenterZ(): Float = PLATTER_CENTER_Z
}

/** Platter speed selected by the speed selector control. */
enum class PlatterSpeed(val displayName: String, val rpm: Float) {
    THIRTY_THREE("33\u2153", TurntableSpec.NOMINAL_RPM),
    FORTY_FIVE("45", TurntableSpec.HIGH_RPM);

    fun toggled(): PlatterSpeed = if (this == THIRTY_THREE) FORTY_FIVE else THIRTY_THREE
}
