package com.vynylrecord.turntable.graphics.animation

/**
 * Durations for the animation state machine, in seconds.
 *
 * Values are chosen from what the mechanism actually does: a servo platter reaches 33 1/3 in about
 * a second, a damped cueing lever takes ~350 ms to lower the stylus, and a motor coasts for about
 * a second after power is removed.
 *
 * [reducedMotionScale] shortens every *non-essential* transition when the platform reports
 * reduced-motion preferences; the record insertion and stylus cueing keep their identity because
 * they carry information, but they speed up.
 */
data class AnimationConfig(
    val recordDropSeconds: Float = 0.62f,
    val recordSettleSeconds: Float = 0.20f,
    val platterSpinUpSeconds: Float = 1.05f,
    val platterSpinDownSeconds: Float = 0.95f,
    val tonearmTravelSeconds: Float = 0.85f,
    val tonearmReturnSeconds: Float = 0.75f,
    val needleLowerSeconds: Float = 0.36f,
    val needleLiftSeconds: Float = 0.30f,
    val seekTravelSeconds: Float = 0.26f,
    val phaseSettleSeconds: Float = 0.08f,
    val reducedMotionScale: Float = 0.55f,
) {
    fun scaled(reducedMotion: Boolean): AnimationConfig =
        if (!reducedMotion) this else copy(
            recordDropSeconds = recordDropSeconds * reducedMotionScale,
            recordSettleSeconds = recordSettleSeconds * reducedMotionScale,
            platterSpinUpSeconds = platterSpinUpSeconds * reducedMotionScale,
            platterSpinDownSeconds = platterSpinDownSeconds * reducedMotionScale,
            tonearmTravelSeconds = tonearmTravelSeconds * reducedMotionScale,
            tonearmReturnSeconds = tonearmReturnSeconds * reducedMotionScale,
            needleLowerSeconds = needleLowerSeconds * reducedMotionScale,
            needleLiftSeconds = needleLiftSeconds * reducedMotionScale,
            seekTravelSeconds = seekTravelSeconds * reducedMotionScale,
        )
}
