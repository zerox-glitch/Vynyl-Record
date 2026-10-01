package com.vynylrecord.turntable.graphics.animation

/**
 * What the mechanism is doing.
 *
 * These describe the *hardware*, which is deliberately not the same as the audio transport: the
 * tonearm keeps travelling while the audio is still silent, and the platter keeps turning while the
 * needle is lifted. The controller maps between the two.
 */
enum class VisualPhase {
    /** Nothing loaded, platter still, arm on its rest. */
    IDLE,

    /** Record placed on the spindle above the platter. */
    LOADING_RECORD,

    /** Record falling the last centimetres and settling on the mat. */
    RECORD_SETTLING,

    /** Motor engaged, platter accelerating to the selected speed. */
    PLATTER_STARTING,

    /** Arm travelling from its rest to the lead-in groove. */
    TONEARM_MOVING,

    /** Cueing lever falling: the stylus descends onto the record. */
    NEEDLE_LOWERING,

    /** Stylus in the groove, platter at speed, arm tracking playback progress. */
    PLAYING,

    /** Stylus lifted, platter still turning at speed, ready to resume instantly. */
    PAUSED,

    /** Arm gliding to a new position after a seek; audio is already at the new time. */
    SEEKING,

    /** Cueing lever rising. */
    NEEDLE_LIFTING,

    /** Arm travelling back to its rest. */
    TONEARM_RETURNING,

    /** Motor off, platter coasting to a stop. */
    PLATTER_STOPPING,

    /** Side finished: arm parked, platter stopped. */
    COMPLETED,

    /** Recoverable failure: the mechanism parks exactly like [COMPLETED]. */
    ERROR,
}
