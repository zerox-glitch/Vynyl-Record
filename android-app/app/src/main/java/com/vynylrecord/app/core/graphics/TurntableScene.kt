package com.vynylrecord.app.core.graphics

import com.vynylrecord.app.core.model.VinylStyleId
import com.vynylrecord.app.core.model.toLinearRgb
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * What the deck is doing, as one of fourteen states.
 *
 * A turntable has a small number of things it can be doing, and each looks different in specific ways: the
 * arm is parked or it is over the record, the platter is turning or coasting to a stop, the lamp is bright or
 * dim. Naming those states — rather than juggling booleans — is what makes "the audio starts when the needle
 * lands" expressible: the needle's contact is a property of the state, and the state's timing is the only
 * clock involved.
 */
enum class DeckState(val label: String) {
    /** Nothing loaded: the platter is still, the arm is on its rest, the lamp is low. */
    IDLE("Idle"),

    /** A record is being placed: it descends onto the platter. */
    LOADING("Placing the record"),

    /** Loaded and cued: the needle hovers above the lead-in. */
    READY("Cued"),

    /** The needle is travelling down. The audio starts when this finishes. */
    CUEING("Lowering the needle"),

    /** Playing: the platter turns at 33⅓ rpm and the arm tracks inwards. */
    PLAYING("Playing"),

    /** Held: the platter coasts to a stop, the needle stays down, the groove goes quiet. */
    PAUSED("Paused"),

    /** Scrubbing: the platter turns with the drag and the arm follows the playhead. */
    SEEKING("Seeking"),

    /** The side has run out: the arm sits in the run-out as the platter slows. */
    ENDED("Finished"),

    /** The needle lifting off. */
    LIFTING("Lifting the needle"),

    /** The arm swinging back to its rest. */
    RETURNING("Returning the arm"),

    /** A press is running: the deck waits, and the lamp breathes with the render's progress. */
    RENDERING("Pressing a record"),

    /** A press just finished: the new record settles onto the platter with a lift of the lamp. */
    COMPLETE("Ready to play"),

    /** Something failed: the lamp goes ruby and the arm stays parked. */
    ERROR("Needs attention"),

    /** The surface is hidden or the screen is off: everything dims and the platter coasts down. */
    POWERED_DOWN("Sleeping"),
    ;

    /** True when audio is expected to be coming out of this state. */
    val isPlaying: Boolean get() = this == PLAYING

    /** True when the platter is being driven by the motor rather than coasting. */
    val isPowered: Boolean get() = this == PLAYING || this == SEEKING || this == LOADING || this == COMPLETE

    companion object {
        /** In the order the state machine passes through them, which is the order the docs list them. */
        val ordered: List<DeckState> = entries.toList()

        /** The state that matches a transport position, for a caller that only knows play/pause/stop. */
        fun forTransport(isPlaying: Boolean, isBuffering: Boolean, hasRecord: Boolean, hasError: Boolean): DeckState = when {
            hasError -> ERROR
            isBuffering -> READY
            isPlaying -> PLAYING
            hasRecord -> READY
            else -> IDLE
        }
    }
}

/**
 * The deck's pose at one instant.
 *
 * Filled in place, every frame, and read by the renderer. A mutable object rather than an immutable data
 * class on purpose: this is computed sixty times a second, and allocating a new pose each frame is exactly
 * the steady garbage that turns into a stutter every few seconds on a phone with a small heap.
 */
class DeckPose {
    /** Platter rotation in radians, including the coast-down between states. */
    var platterRotation = 0f

    /** The record's rotation. Equal to the platter's except while a record is being placed. */
    var discRotation = 0f

    /** The disc's height above the platter, in scene units. Non-zero only while loading. */
    var discLift = 0f

    /** How far along its travel the arm has swung, 0 at the rest position and 1 at the run-out. */
    var armTravel = 0f

    /** The arm's height, 0 with the stylus on the record and 1 at the rest height. */
    var armLift = 1f

    /** How much the groove is being read: 0 silent, 1 playing. Drives the sheen and the needle contact. */
    var needleContact = 0f

    /** The lamp's intensity multiplier. */
    var lampIntensity = 1f

    /** 0 for the amber lamp, 1 for the ruby one; the renderer mixes between them. */
    var lampWarmth = 0f

    /** Extra sheen on the disc, used to show a press in progress. */
    var discSheen = 0f

    /** The press's progress, 0..1, which the lamp and the knobs follow. */
    var pressProgress = 0f

    /** Copies another pose, so a state can be captured and restored without allocating. */
    fun set(other: DeckPose) {
        platterRotation = other.platterRotation
        discRotation = other.discRotation
        discLift = other.discLift
        armTravel = other.armTravel
        armLift = other.armLift
        needleContact = other.needleContact
        lampIntensity = other.lampIntensity
        lampWarmth = other.lampWarmth
        discSheen = other.discSheen
        pressProgress = other.pressProgress
    }
}

/**
 * The animation.
 *
 * Everything here is driven by elapsed seconds, never by a frame count. That is the difference between a
 * record that turns at 33⅓ rpm on a 120 Hz phone and one that turns at 66 on it: a frame-counted animation
 * runs at whatever rate the device happens to render at.
 *
 * The platter has inertia. Starting and stopping are ramps rather than switches, because a platter that snaps
 * to full speed looks like a video and one that accelerates looks like a motor — and because the coast-down
 * is what makes "pause" feel like a pause rather than a cut.
 */
class TurntableAnimator {

    var state: DeckState = DeckState.IDLE
        private set

    /** Where the needle is along the side, 0 at the lead-in and 1 at the run-out. */
    var playheadFraction: Float = 0f
        private set

    /** Render progress, 0..1, which the pressing states follow. */
    var renderProgress: Float = 0f

    /** When true, transition times are cut short and the drift is dropped. */
    var reducedMotion: Boolean = false

    private val pose = DeckPose()

    /** The pose after the most recent [update]; valid until the next call. */
    val currentPose: DeckPose get() = pose

    private var elapsedSeconds = 0f
    private var stateSeconds = 0f
    private var platterVelocity = 0f
    private var targetVelocity = 0f
    private var armTravelTarget = 0f
    private var armLiftTarget = 1f
    private var lampTarget = TurntableAnimator.LAMP_LOW
    private var warmthTarget = 0f

    /** True once the needle is on the record and the audio should be audible. */
    val needleIsDown: Boolean get() = pose.needleContact >= 0.999f

    /**
     * Moves to a new state.
     *
     * @param immediate skips the transition, for the first frame after a screen opens where a ramp would be
     *        a lie about something the user did not do
     */
    fun setState(next: DeckState, immediate: Boolean = false) {
        if (next == state && !immediate) return
        state = next
        stateSeconds = 0f
        when (next) {
            DeckState.PLAYING -> {
                targetVelocity = SPINNING_VELOCITY
                armLiftTarget = 0f
                lampTarget = LAMP_BRIGHT
                warmthTarget = 0f
            }

            DeckState.SEEKING -> {
                targetVelocity = SPINNING_VELOCITY * 1.35f
                armLiftTarget = 0f
                lampTarget = LAMP_BRIGHT
            }

            DeckState.CUEING -> {
                // The platter turns slowly while the needle comes down, as a real one does when you cue a
                // record by hand: the groove is moving before it is being read.
                targetVelocity = SPINNING_VELOCITY * 0.35f
                armLiftTarget = 0f
                lampTarget = LAMP_BRIGHT
            }

            DeckState.READY -> {
                targetVelocity = 0f
                armLiftTarget = 0.85f
                lampTarget = LAMP_MEDIUM
            }

            DeckState.LOADING -> {
                targetVelocity = SPINNING_VELOCITY * 0.6f
                armLiftTarget = 1f
                armTravelTarget = 0f
                lampTarget = LAMP_MEDIUM
            }

            DeckState.PAUSED -> {
                targetVelocity = 0f
                // The needle stays on the record while paused: lifting it would put a thump at each end of
                // the pause, and a pause that thumps is not a pause.
                armLiftTarget = 0f
                lampTarget = LAMP_MEDIUM
            }

            DeckState.ENDED -> {
                targetVelocity = 0f
                armLiftTarget = 0f
                lampTarget = LAMP_MEDIUM
            }

            DeckState.LIFTING -> {
                targetVelocity = 0f
                armLiftTarget = 0.9f
                lampTarget = LAMP_MEDIUM
            }

            DeckState.RETURNING -> {
                targetVelocity = 0f
                armLiftTarget = 0.9f
                armTravelTarget = 0f
                lampTarget = LAMP_MEDIUM
            }

            DeckState.RENDERING -> {
                targetVelocity = 0f
                armLiftTarget = 1f
                armTravelTarget = 0f
                lampTarget = LAMP_BRIGHT
            }

            DeckState.COMPLETE -> {
                targetVelocity = SPINNING_VELOCITY
                armLiftTarget = 0.9f
                armTravelTarget = 0f
                lampTarget = LAMP_FLARE
            }

            DeckState.ERROR -> {
                targetVelocity = 0f
                armLiftTarget = 1f
                armTravelTarget = 0f
                lampTarget = LAMP_MEDIUM
                warmthTarget = 1f
            }

            DeckState.POWERED_DOWN -> {
                targetVelocity = 0f
                armLiftTarget = 1f
                lampTarget = TurntableAnimator.LAMP_OFF
            }

            DeckState.IDLE -> {
                targetVelocity = 0f
                armLiftTarget = 1f
                armTravelTarget = 0f
                lampTarget = LAMP_LOW
                warmthTarget = 0f
            }
        }
        if (immediate) {
            platterVelocity = targetVelocity
            pose.armLift = armLiftTarget
            pose.armTravel = armTravelTarget
            pose.lampIntensity = lampTarget
            pose.needleContact = if (armLiftTarget <= 0.001f) 1f else 0f
            pose.discLift = 0f
        }
    }

    /** Sets where the needle sits along the side; the arm follows it. */
    fun setPlayhead(fraction: Float) {
        playheadFraction = fraction.coerceIn(0f, 1f)
        if (state == DeckState.PLAYING || state == DeckState.PAUSED || state == DeckState.SEEKING || state == DeckState.ENDED) {
            armTravelTarget = ARM_TRAVEL_START + (ARM_TRAVEL_END - ARM_TRAVEL_START) * playheadFraction
        }
    }

    fun setRenderProgress(progress: Float) {
        renderProgress = progress.coerceIn(0f, 1f)
    }

    /**
     * Advances the animation.
     *
     * @param deltaSeconds real elapsed time, clamped inside so a device that was asleep does not jump the
     *        platter forward by a hundred turns on the frame it wakes up
     */
    fun update(deltaSeconds: Float): DeckPose {
        val dt = deltaSeconds.coerceIn(0f, MAX_STEP_SECONDS)
        elapsedSeconds += dt
        stateSeconds += dt

        updatePlatter(dt)
        updateArm(dt)
        updateLamp(dt)

        pose.pressProgress = if (state == DeckState.RENDERING) renderProgress else 0f
        pose.discSheen = when (state) {
            DeckState.RENDERING -> 0.35f + 0.25f * kotlin.math.sin(elapsedSeconds * 1.4f)
            DeckState.COMPLETE -> 0.55f * max(0f, 1f - stateSeconds / 1.2f)
            DeckState.PAUSED, DeckState.ERROR -> 0.08f
            else -> 0.16f + 0.05f * kotlin.math.sin(elapsedSeconds * 0.6f)
        }
        // The needle comes down over the cueing window, which is what the audio start is keyed to.
        if (state == DeckState.CUEING) {
            pose.needleContact = (stateSeconds / CUE_SECONDS).coerceIn(0f, 1f)
        } else if (armLiftTarget <= 0.001f && (state == DeckState.PLAYING || state == DeckState.PAUSED || state == DeckState.SEEKING || state == DeckState.ENDED)) {
            pose.needleContact = 1f
        } else if (state == DeckState.LIFTING) {
            pose.needleContact = (1f - stateSeconds / LIFT_SECONDS).coerceIn(0f, 1f)
        } else if (state == DeckState.RETURNING || state == DeckState.IDLE || state == DeckState.POWERED_DOWN || state == DeckState.RENDERING || state == DeckState.ERROR) {
            pose.needleContact = 0f
        }
        return pose
    }

    private fun updatePlatter(dt: Float) {
        val delta = targetVelocity - platterVelocity
        if (abs(delta) > 1e-4f) {
            // Starting is quick and stopping is slow, which is the asymmetry a real motor and its bearing
            // have; a symmetric ramp reads as a video scrubbing backwards.
            val rate = if (delta > 0f) START_RAMP else STOP_RAMP
            val step = min(abs(delta), rate * dt)
            platterVelocity = if (delta > 0f) platterVelocity + step else platterVelocity - step
        } else {
            platterVelocity = targetVelocity
        }
        if (platterVelocity < 0.02f && targetVelocity == 0f) platterVelocity = 0f

        pose.platterRotation = wrapRadians(pose.platterRotation + platterVelocity * dt)
        pose.discRotation = pose.platterRotation

        // The record descends onto the platter while loading, and is handed over to the spindle.
        pose.discLift = when (state) {
            DeckState.LOADING -> max(0f, 1f - stateSeconds / LOAD_SECONDS) * DISC_LIFT_HEIGHT
            else -> 0f
        }
    }

    private fun updateArm(dt: Float) {
        val response = if (reducedMotion) 1f else halfLifeResponse(dt, ARM_HALF_LIFE_SECONDS)
        pose.armLift += (armLiftTarget - pose.armLift) * response
        pose.armTravel += (armTravelTarget - pose.armTravel) * response

        // A record that has been lifted is parked once the arm has travelled back; a record that has run out
        // stays where it is until the user does something.
        if (state == DeckState.LIFTING && pose.armLift >= 0.85f) {
            setState(DeckState.RETURNING)
        } else if (state == DeckState.RETURNING && pose.armTravel <= 0.02f && pose.armLift >= 0.85f) {
            setState(DeckState.READY)
        }
        // The platter finishing its coast-down after the side has run out parks the arm.
        if (state == DeckState.ENDED && platterVelocity <= 0f) {
            setState(DeckState.LIFTING)
        }
    }

    private fun updateLamp(dt: Float) {
        val response = if (reducedMotion) 1f else halfLifeResponse(dt, LAMP_HALF_LIFE_SECONDS)
        val breathing = when (state) {
            // While a press runs, the lamp breathes with the render: the deck is doing something, and the
            // light is how the room knows.
            DeckState.RENDERING -> 0.85f + 0.25f * kotlin.math.sin(elapsedSeconds * 1.6f) * (0.5f + renderProgress)
            DeckState.ERROR -> 0.75f + 0.25f * kotlin.math.sin(elapsedSeconds * 2.4f)
            DeckState.COMPLETE -> LAMP_FLARE * max(0f, 1f - stateSeconds / 1.4f) + 0.2f
            DeckState.PLAYING -> LAMP_BRIGHT * (0.97f + 0.03f * kotlin.math.sin(elapsedSeconds * 0.7f))
            else -> lampTarget
        }
        pose.lampIntensity += (breathing - pose.lampIntensity) * response
        pose.lampWarmth += (warmthTarget - pose.lampWarmth) * halfLifeResponse(dt, 1.2f)
    }

    /** The fraction of the way to a target this frame gets, for a filter with the given half-life. */
    private fun halfLifeResponse(dt: Float, halfLifeSeconds: Float): Float {
        if (dt <= 0f || halfLifeSeconds <= 0f) return 0f
        return (1f - Math.pow(0.5, (dt / halfLifeSeconds).toDouble()).toFloat()).coerceIn(0f, 1f)
    }

    /** A summary for the debug readout. */
    fun describe(): String =
        "${state.label} · %.1f rpm · arm %.0f%%".format(platterVelocity / TWO_PI * 60f, pose.armTravel * 100f)

    private fun wrapRadians(value: Float): Float {
        var result = value % TWO_PI
        if (result < 0f) result += TWO_PI
        return result
    }

    companion object {
        private const val TWO_PI = (2.0 * PI).toFloat()

        /** 33⅓ rpm in radians per second. */
        const val SPINNING_VELOCITY = (33.333f / 60f) * (2.0 * PI).toFloat()

        /** Radians per second squared, expressed as radians per second of ramp. */
        const val START_RAMP = 6.5f
        const val STOP_RAMP = 3.2f

        /** Arm travel in scene units: the lead-in groove and the run-out. */
        const val ARM_TRAVEL_START = 0.18f
        const val ARM_TRAVEL_END = 1f

        /** The record sits this far above the platter while it is being placed. */
        const val DISC_LIFT_HEIGHT = 0.06f

        /** How long each mechanical transition takes. */
        const val CUE_SECONDS = 0.55f
        const val LIFT_SECONDS = 0.42f
        const val LOAD_SECONDS = 1.6f

        /** Filter half-lives, in seconds. */
        const val ARM_HALF_LIFE_SECONDS = 0.07f
        const val LAMP_HALF_LIFE_SECONDS = 0.35f

        /** The lamp's levels. */
        const val LAMP_OFF = 0.12f
        const val LAMP_LOW = 0.42f
        const val LAMP_MEDIUM = 0.78f
        const val LAMP_BRIGHT = 1f
        const val LAMP_FLARE = 1.45f

        /** A delta larger than this is a resume from background, not a frame. */
        const val MAX_STEP_SECONDS = 1f / 20f
    }
}

/**
 * A material, as the shader sees it.
 *
 * These numbers are the *look* of the deck: a lacquered plinth, machined aluminium, a felt mat, black vinyl
 * whose grooves catch the light, brass detailing and, for the two styles that ask for it, translucent
 * pressing. Kept in one table rather than scattered through the renderer's draw calls.
 */
data class DeckMaterial(
    val baseColor: FloatArray,
    val specularColor: FloatArray,
    val roughness: Float,
    val metallic: Float,
    val alpha: Float = 1f,
    val transmission: Float = 0f,
    val grooveAmount: Float = 0f,
    val sheen: Float = 0f,
    val usesLabel: Boolean = false,
) {
    /** Materials are compared only in tests, which is why this is explicit rather than generated. */
    override fun equals(other: Any?): Boolean {
        if (other !is DeckMaterial) return false
        return baseColor.contentEquals(other.baseColor) &&
            specularColor.contentEquals(other.specularColor) &&
            roughness == other.roughness &&
            metallic == other.metallic &&
            alpha == other.alpha &&
            transmission == other.transmission &&
            grooveAmount == other.grooveAmount &&
            sheen == other.sheen &&
            usesLabel == other.usesLabel
    }

    override fun hashCode(): Int {
        var result = baseColor.contentHashCode()
        result = 31 * result + specularColor.contentHashCode()
        result = 31 * result + roughness.hashCode()
        result = 31 * result + metallic.hashCode()
        result = 31 * result + alpha.hashCode()
        result = 31 * result + transmission.hashCode()
        result = 31 * result + grooveAmount.hashCode()
        result = 31 * result + sheen.hashCode()
        result = 31 * result + usesLabel.hashCode()
        return result
    }
}

/** The parts of the deck, in draw order. */
enum class DeckPart(val label: String) {
    PLINTH("plinth"),
    FEET("feet"),
    PLATTER("platter"),
    PLATTER_RIM("platter rim"),
    MAT("mat"),
    RECORD("record"),
    GROOVES("grooves"),
    LABEL("label"),
    SPINDLE("spindle"),
    ARM_BASE("arm base"),
    ARM("tonearm"),
    COUNTERWEIGHT("counterweight"),
    HEADSHELL("headshell"),
    STYLUS("stylus"),
    KNOBS("controls"),
    LAMP("lamp"),
    ;

    companion object {
        /** The order a frame draws them in, back to front and bottom to top. */
        val ordered: List<DeckPart> = entries.toList()
    }
}

/**
 * The deck's dimensions, in metres.
 *
 * A 12-inch record is 0.30 m across, an LP's label is 0.10 m, a platter is 0.33 m and a plinth is about
 * 0.45 × 0.38 × 0.09. Using the real sizes is why the camera can sit at a believable distance and why the
 * tonearm's geometry reads correctly; the scene is a real object rather than a collection of shapes that
 * happen to look plausible.
 */
object DeckDimensions {
    // Plinth
    const val PLINTH_WIDTH = 0.45f
    const val PLINTH_DEPTH = 0.38f
    const val PLINTH_HEIGHT = 0.075f
    const val PLINTH_BEVEL = 0.012f
    const val PLINTH_TOP = PLINTH_HEIGHT / 2f

    // Platter
    const val PLATTER_RADIUS = 0.155f
    const val PLATTER_HEIGHT = 0.022f
    const val PLATTER_Y = PLINTH_TOP + PLATTER_HEIGHT / 2f

    // Mat
    const val MAT_RADIUS = 0.150f
    const val MAT_THICKNESS = 0.004f
    const val MAT_Y = PLATTER_Y + PLATTER_HEIGHT / 2f + MAT_THICKNESS / 2f

    // Record
    const val RECORD_RADIUS = 0.1524f
    const val RECORD_LABEL_RADIUS = 0.050f
    const val RECORD_THICKNESS = 0.0022f
    const val RECORD_BOTTOM = MAT_Y + MAT_THICKNESS / 2f
    const val RECORD_CENTRE_Y = RECORD_BOTTOM + RECORD_THICKNESS / 2f

    // Spindle
    const val SPINDLE_RADIUS = 0.0035f
    const val SPINDLE_HEIGHT = 0.024f
    const val SPINDLE_Y = RECORD_CENTRE_Y + SPINDLE_HEIGHT / 2f

    // Tonearm
    const val ARM_PIVOT_X = 0.145f
    const val ARM_PIVOT_Z = -0.105f
    const val ARM_PIVOT_Y = PLINTH_TOP + 0.042f
    const val ARM_RADIUS = 0.006f
    const val ARM_REST_TRAVEL = 0.0f
    /** The stylus' distance from the platter's centre at the lead-in and at the run-out. */
    const val STYLUS_LEAD_IN_RADIUS = 0.146f
    const val STYLUS_RUN_OUT_RADIUS = 0.058f

    // Controls
    const val KNOB_RADIUS = 0.019f
    const val KNOB_HEIGHT = 0.014f
    const val KNOB_ONE_X = -0.155f
    const val KNOB_ONE_Z = 0.145f
    const val KNOB_TWO_X = 0.155f
    const val KNOB_TWO_Z = 0.145f

    // Lamp
    const val LAMP_RADIUS = 0.032f
    const val LAMP_HEIGHT = 0.12f
    const val LAMP_X = 0f
    const val LAMP_Y = 0.55f
    const val LAMP_Z = 0.05f

    /** The stylus' position for a given arm travel, in the plinth's own coordinates. */
    fun stylusPosition(travel: Float, lift: Float, out: Vec3): Vec3 {
        // The arm swings about its pivot; the travel fraction is mapped onto the two groove radii.
        val clamped = travel.coerceIn(0f, 1f)
        val angle = ARM_REST_ANGLE + clamped * ARM_SWEEP_RADIANS
        val radius = STYLUS_LEAD_IN_RADIUS + (STYLUS_RUN_OUT_RADIUS - STYLUS_LEAD_IN_RADIUS) * clamped
        val x = -radius * kotlin.math.sin(angle)
        val z = -radius * kotlin.math.cos(angle)
        return out.set(x, RECORD_CENTRE_Y + RECORD_THICKNESS / 2f + lift * ARM_LIFT_HEIGHT, z)
    }

    /** How high the stylus sits above the record when it is fully raised. */
    const val ARM_LIFT_HEIGHT = 0.022f

    /** The arm's rest angle and how far it sweeps across the record, in radians. */
    const val ARM_REST_ANGLE = -0.42f
    const val ARM_SWEEP_RADIANS = 0.72f

    /** The three control points of the tonearm's curve, in the arm's own local space. */
    fun armCurve(): List<Vec3> = listOf(
        Vec3(0f, 0f, 0f),
        Vec3(-0.075f, 0.006f, 0.075f),
        Vec3(-0.150f, 0.002f, 0.150f),
        Vec3(-0.215f, -0.010f, 0.205f),
    )

    /** The four feet, placed just inside the plinth's corners. */
    val FEET = listOf(
        Pair(-0.185f, -0.145f),
        Pair(0.185f, -0.145f),
        Pair(-0.185f, 0.145f),
        Pair(0.185f, 0.145f),
    )

    const val FOOT_RADIUS = 0.016f
    const val FOOT_HEIGHT = 0.018f
}

/**
 * The lighting rig.
 *
 * A warm key light from above and in front, a cold fill from behind so nothing goes to black, and the lamp,
 * which is a real light in the scene rather than a tint on the materials: it is placed above the platter and
 * its falloff is what makes the deck's colours change as the camera orbits.
 */
object DeckLighting {
    val KEY_DIRECTION = floatArrayOf(-0.42f, 0.84f, 0.34f)
    val KEY_COLOR = floatArrayOf(1.05f, 0.95f, 0.82f)
    val FILL_COLOR = floatArrayOf(0.16f, 0.19f, 0.26f)
    val LAMP_COLOR = floatArrayOf(1.0f, 0.66f, 0.28f)
    val LAMP_ERROR_COLOR = floatArrayOf(0.86f, 0.23f, 0.18f)
    val AMBIENT = floatArrayOf(0.055f, 0.050f, 0.048f)
    const val LAMP_POWER = 0.34f
}

/** The fixed materials the deck is built from, independent of the record's finish. */
object DeckMaterials {
    val PLINTH = DeckMaterial(
        baseColor = floatArrayOf(0.055f, 0.048f, 0.042f),
        specularColor = floatArrayOf(0.42f, 0.28f, 0.12f),
        roughness = 0.30f,
        metallic = 0.20f,
    )
    val ALUMINIUM = DeckMaterial(
        baseColor = floatArrayOf(0.60f, 0.585f, 0.555f),
        specularColor = floatArrayOf(0.78f, 0.76f, 0.72f),
        roughness = 0.26f,
        metallic = 0.92f,
    )
    val BRASS = DeckMaterial(
        baseColor = floatArrayOf(0.72f, 0.44f, 0.13f),
        specularColor = floatArrayOf(0.95f, 0.72f, 0.30f),
        roughness = 0.32f,
        metallic = 0.85f,
    )
    val STEEL = DeckMaterial(
        baseColor = floatArrayOf(0.52f, 0.52f, 0.55f),
        specularColor = floatArrayOf(0.82f, 0.82f, 0.86f),
        roughness = 0.20f,
        metallic = 0.95f,
    )
    val FELT = DeckMaterial(
        baseColor = floatArrayOf(0.075f, 0.062f, 0.052f),
        specularColor = floatArrayOf(0.10f, 0.09f, 0.08f),
        roughness = 0.95f,
        metallic = 0.0f,
    )
    val STYLUS = DeckMaterial(
        baseColor = floatArrayOf(0.80f, 0.80f, 0.82f),
        specularColor = floatArrayOf(1f, 1f, 1f),
        roughness = 0.10f,
        metallic = 1.0f,
    )

    /** The record, for a given finish: the disc's colour comes from the style, not from a texture. */
    fun record(style: VinylStyleId): DeckMaterial = DeckMaterial(
        baseColor = style.baseColor.toLinearRgb(),
        specularColor = style.grooveColor.toLinearRgb(),
        roughness = 0.17f,
        metallic = 0.35f,
        alpha = if (style.translucent) 0.86f else 1f,
        transmission = if (style.translucent) 0.8f else 0f,
        grooveAmount = 0.30f,
    )

    /** The sheen pass over the grooves, whose opacity follows the camera and the deck's state. */
    fun grooveSheen(style: VinylStyleId, sheen: Float): DeckMaterial = DeckMaterial(
        baseColor = style.grooveColor.toLinearRgb(),
        specularColor = style.brassAccent.toLinearRgb(),
        roughness = 0.10f,
        metallic = 0.55f,
        alpha = (0.06f * (1f + sheen)).coerceIn(0f, 0.4f),
        grooveAmount = 0.22f,
        sheen = sheen,
    )

    /** The label: a locally drawn bitmap, so the material only says "sample the texture here". */
    fun label(style: VinylStyleId): DeckMaterial = DeckMaterial(
        baseColor = style.labelColor.toLinearRgb(),
        specularColor = floatArrayOf(0.35f, 0.34f, 0.32f),
        roughness = 0.62f,
        metallic = 0f,
        usesLabel = true,
    )

    fun lamp(color: FloatArray, intensity: Float): DeckMaterial = DeckMaterial(
        baseColor = color,
        specularColor = color,
        roughness = 0.45f,
        metallic = 0.1f,
        alpha = 0.55f + 0.45f * min(1f, intensity),
    )
}
