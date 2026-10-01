package com.vynylrecord.turntable.graphics

import com.vynylrecord.turntable.model.TurntableSpec

/**
 * Framings the user can jump to. Angles are spherical around [target]:
 * `azimuth` rotates about +Y (negative values look from the front-right), `elevation` is degrees
 * above the horizon and `distance` is metres from the target.
 */
enum class CameraPreset(
    val displayName: String,
    val azimuth: Float,
    val elevation: Float,
    val distance: Float,
    val targetX: Float,
    val targetY: Float,
    val targetZ: Float,
) {
    /** The default three-quarter view: record left, tonearm right, deck in perspective. */
    HERO(
        displayName = "Hero",
        azimuth = -34f,
        elevation = 26f,
        distance = 0.62f,
        targetX = -0.02f,
        targetY = TurntableSpec.PLINTH_TOP + 0.012f,
        targetZ = 0.004f,
    ),

    /** Straight down: shows the whole deck and how the arm tracks. */
    TOP(
        displayName = "Top",
        azimuth = 0f,
        elevation = 74f,
        distance = 0.58f,
        targetX = -0.01f,
        targetY = TurntableSpec.RECORD_TOP,
        targetZ = 0f,
    ),

    /** Close on the headshell and stylus. */
    NEEDLE(
        displayName = "Needle",
        azimuth = -18f,
        elevation = 22f,
        distance = 0.215f,
        // Mid-travel of the stylus, so the close-up stays framed for a whole side.
        targetX = TurntableSpec.PLATTER_CENTER_X + 0.073f,
        targetY = TurntableSpec.RECORD_TOP + 0.005f,
        targetZ = TurntableSpec.PLATTER_CENTER_Z + 0.074f,
    ),

    /** Close on the printed label. */
    LABEL(
        displayName = "Label",
        azimuth = -22f,
        elevation = 42f,
        distance = 0.235f,
        targetX = TurntableSpec.PLATTER_CENTER_X,
        targetY = TurntableSpec.LABEL_TOP,
        targetZ = TurntableSpec.PLATTER_CENTER_Z,
    );

    val pose: FloatArray
        get() = floatArrayOf(azimuth, elevation, distance, targetX, targetY, targetZ)

    companion object {
        val DEFAULT: CameraPreset = HERO

        fun fromNameOrDefault(name: String?): CameraPreset =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
