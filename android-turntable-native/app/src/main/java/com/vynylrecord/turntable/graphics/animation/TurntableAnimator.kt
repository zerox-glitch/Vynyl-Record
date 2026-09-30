package com.vynylrecord.turntable.graphics.animation

import com.vynylrecord.turntable.model.Easing
import com.vynylrecord.turntable.model.MathUtils
import com.vynylrecord.turntable.model.PlatterSpeed
import com.vynylrecord.turntable.model.TonearmGeometry
import com.vynylrecord.turntable.model.TurntableSpec
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.PI

/**
 * The mechanism.
 *
 * One time-based state machine drives the platter, the tonearm, the cueing lever, the record
 * insertion, the controls and the indicator lamp. It is completely frame-rate independent: every
 * value is integrated with the real delta, so 30 Hz, 60 Hz and 120 Hz produce the same motion, and a
 * dropped frame does not teleport anything.
 *
 * Ownership and threading: the animator is read every frame on the GL thread via [update], and
 * written from the UI thread through the `request*` intents. Every entry point synchronises on one
 * small lock; the outputs are plain fields because they are only ever consumed by the thread that
 * called [update].
 *
 * Ordering guarantee that matters: [onNeedleContact] fires on the frame where the stylus reaches the
 * record surface. The controller only starts audio from that callback, which is why playback can
 * never begin before the needle is visually down.
 */
class TurntableAnimator(baseConfig: AnimationConfig = AnimationConfig()) {

    // ------------------------------------------------------------------ outputs

    /** Current phase. Read-only outside this class. */
    var phase: VisualPhase = VisualPhase.IDLE
        private set

    /** Platter rotation in degrees, wrapped to `[0, 360)`. */
    @JvmField var platterAngleDeg: Float = 0f

    /** Current platter speed. Ramps up and down; never jumps. */
    @JvmField var platterRpm: Float = 0f

    /** Tonearm azimuth in degrees, measured from +X in the platter plane. */
    @JvmField var tonearmAngleDeg: Float = TurntableSpec.TONEARM_REST_ANGLE_DEG

    /** Cueing lever position: 1 = fully up, 0 = stylus on the record. */
    @JvmField var tonearmLift01: Float = 1f

    /** Record height above the mat, in metres. 0 when seated. */
    @JvmField var recordOffsetY: Float = 0f

    /** Normalised version of [recordOffsetY], 0 = seated, 1 = fully raised. */
    @JvmField var recordInsertion01: Float = 1f

    /** Small bounce applied while the record settles, in metres. Never negative. */
    @JvmField var recordSettleBounce: Float = 0f

    /** True while the stylus is physically touching the record. */
    @JvmField var needleContact: Boolean = false

    /** Micro-vibration of the stylus assembly while tracking, in metres. */
    @JvmField var needleMicroOffsetY: Float = 0f

    /** Indicator lamp brightness, 0..1. */
    @JvmField var lampLevel: Float = 0.06f

    /** Power button depression, 0..1. */
    @JvmField var powerButtonPress: Float = 0f

    /** Speed selector rotation, in degrees from the "33" detent. */
    @JvmField var speedSelectorAngleDeg: Float = 0f

    /** True once audio media is loaded, so the deck can show a record at all. */
    var hasRecord: Boolean = false
        private set

    /** Seconds of animation time, used only for pulsing effects. */
    @JvmField var elapsedSeconds: Float = 0f

    // ------------------------------------------------------------------ configuration

    private var config: AnimationConfig = baseConfig
    private var reducedMotion = false

    /** Fired on the frame the stylus lands. Audio starts from here. */
    var onNeedleContact: (() -> Unit)? = null

    /** Fired whenever [phase] changes; useful for announcements and tests. */
    var onPhaseChanged: ((VisualPhase) -> Unit)? = null

    var speed: PlatterSpeed = PlatterSpeed.THIRTY_THREE
        private set

    private val lock = Any()

    private var phaseElapsed = 0f
    private var stopDestination: VisualPhase = VisualPhase.IDLE
    private var liftDestination: LiftDestination = LiftDestination.NONE
    private var seekTargetProgress = 0f
    private var touchDownElapsed = Float.MAX_VALUE
    private var armTargetAngle = TurntableSpec.TONEARM_REST_ANGLE_DEG
    private var lastAudioProgress = 0f
    private var lampWasSet = false
    private var pendingPlay = false
    private var recordExpected = false

    private enum class LiftDestination {
        NONE,

        /** Park the arm but keep the platter at speed: resume is instant. */
        PAUSED,

        /** Full stop with the arm on its rest. */
        PARK,

        /** Side finished. */
        COMPLETE,

        /** Recoverable failure. */
        ERROR,
    }

    // ------------------------------------------------------------------ intents

    /** Called by the controller when media is prepared or removed. */
    fun setRecordAvailable(available: Boolean) {
        synchronized(lock) {
            recordExpected = available
            if (hasRecord == available) return
            hasRecord = available
            touchDownElapsed = Float.MAX_VALUE
            if (available) {
                enter(VisualPhase.LOADING_RECORD)
                recordInsertion01 = 1f
                recordOffsetY = RECORD_INSERTION_HEIGHT
            } else {
                pendingPlay = false
                enter(VisualPhase.IDLE)
                recordInsertion01 = 0f
                recordOffsetY = 0f
                speedSelectorAngleDeg = 0f
            }
        }
    }

    /** Start, or resume from [VisualPhase.PAUSED]. */
    fun requestPlay() {
        synchronized(lock) {
            if (!hasRecord) return
            when (phase) {
                VisualPhase.PAUSED -> {
                    // The arm is already over the groove; only the cueing lever moves.
                    enter(VisualPhase.NEEDLE_LOWERING)
                }
                VisualPhase.LOADING_RECORD, VisualPhase.RECORD_SETTLING -> {
                    // The record is still on its way down; start the moment it lands.
                    pendingPlay = true
                }
                VisualPhase.IDLE,
                VisualPhase.PLATTER_STOPPING,
                VisualPhase.COMPLETED,
                VisualPhase.ERROR,
                -> {
                    enter(VisualPhase.PLATTER_STARTING)
                }
                else -> Unit
            }
        }
    }

    /**
     * Lift the stylus. The platter keeps turning, which is what makes resuming instant and is also
     * what a real deck does - the motor is never switched off for a pause.
     */
    fun requestPause() {
        synchronized(lock) {
            when (phase) {
                VisualPhase.PLAYING, VisualPhase.SEEKING, VisualPhase.TONEARM_MOVING, VisualPhase.NEEDLE_LOWERING -> {
                    liftDestination = LiftDestination.PAUSED
                    enter(VisualPhase.NEEDLE_LIFTING)
                }
                VisualPhase.PLATTER_STARTING -> {
                    // Paused before the arm ever moved: park it properly.
                    liftDestination = LiftDestination.PARK
                    stopDestination = VisualPhase.IDLE
                    enter(VisualPhase.PLATTER_STOPPING)
                }
                else -> Unit
            }
        }
    }

    /** Full stop: arm returns to its rest, platter coasts down. */
    fun requestStop() {
        synchronized(lock) {
            when (phase) {
                VisualPhase.PLAYING, VisualPhase.SEEKING, VisualPhase.TONEARM_MOVING, VisualPhase.NEEDLE_LOWERING -> {
                    liftDestination = LiftDestination.PARK
                    stopDestination = VisualPhase.IDLE
                    enter(VisualPhase.NEEDLE_LIFTING)
                }
                VisualPhase.PAUSED -> {
                    liftDestination = LiftDestination.PARK
                    stopDestination = VisualPhase.IDLE
                    enter(VisualPhase.TONEARM_RETURNING)
                }
                VisualPhase.PLATTER_STARTING -> {
                    stopDestination = VisualPhase.IDLE
                    enter(VisualPhase.PLATTER_STOPPING)
                }
                else -> Unit
            }
        }
    }

    /** Seek: glide the arm to the new position, then keep playing. */
    fun requestSeek(progress: Float) {
        synchronized(lock) {
            seekTargetProgress = MathUtils.clamp01(progress)
            lastAudioProgress = seekTargetProgress
            when (phase) {
                VisualPhase.PLAYING -> {
                    armTargetAngle = TonearmGeometry.angleForProgress(seekTargetProgress)
                    enter(VisualPhase.SEEKING)
                }
                VisualPhase.PAUSED, VisualPhase.SEEKING -> {
                    armTargetAngle = TonearmGeometry.angleForProgress(seekTargetProgress)
                    if (phase == VisualPhase.PAUSED) enter(VisualPhase.SEEKING)
                }
                VisualPhase.IDLE, VisualPhase.COMPLETED, VisualPhase.ERROR -> {
                    // Nothing is playing: just move the parked arm's target so a later play starts there.
                    armTargetAngle = TonearmGeometry.angleForProgress(seekTargetProgress)
                }
                else -> Unit
            }
        }
    }

    fun requestCompleted() {
        synchronized(lock) {
            stopDestination = VisualPhase.COMPLETED
            when (phase) {
                VisualPhase.PLAYING, VisualPhase.SEEKING, VisualPhase.TONEARM_MOVING, VisualPhase.NEEDLE_LOWERING -> {
                    liftDestination = LiftDestination.COMPLETE
                    enter(VisualPhase.NEEDLE_LIFTING)
                }
                VisualPhase.PAUSED -> {
                    liftDestination = LiftDestination.COMPLETE
                    enter(VisualPhase.TONEARM_RETURNING)
                }
                VisualPhase.PLATTER_STARTING, VisualPhase.IDLE, VisualPhase.PLATTER_STOPPING -> {
                    enter(VisualPhase.PLATTER_STOPPING)
                }
                else -> Unit
            }
        }
    }

    /** Recoverable failure: park the mechanism, exactly like the end of a side. */
    fun requestError() {
        synchronized(lock) {
            stopDestination = VisualPhase.ERROR
            when (phase) {
                VisualPhase.PLAYING, VisualPhase.SEEKING, VisualPhase.TONEARM_MOVING, VisualPhase.NEEDLE_LOWERING -> {
                    liftDestination = LiftDestination.ERROR
                    enter(VisualPhase.NEEDLE_LIFTING)
                }
                VisualPhase.PAUSED, VisualPhase.IDLE -> {
                    liftDestination = LiftDestination.ERROR
                    enter(VisualPhase.TONEARM_RETURNING)
                }
                else -> Unit
            }
        }
    }

    /** Back to the parked state with the record still on the platter. */
    fun requestReset() {
        synchronized(lock) {
            touchDownElapsed = Float.MAX_VALUE
            lastAudioProgress = 0f
            seekTargetProgress = 0f
            pendingPlay = false
            armTargetAngle = TurntableSpec.TONEARM_REST_ANGLE_DEG
            stopDestination = VisualPhase.IDLE
            liftDestination = LiftDestination.PARK
            when (phase) {
                VisualPhase.PLAYING, VisualPhase.SEEKING, VisualPhase.TONEARM_MOVING, VisualPhase.NEEDLE_LOWERING -> {
                    enter(VisualPhase.NEEDLE_LIFTING)
                }
                else -> {
                    enter(VisualPhase.TONEARM_RETURNING)
                }
            }
        }
    }

    fun setSpeed(newSpeed: PlatterSpeed) {
        synchronized(lock) { speed = newSpeed }
    }

    fun setReducedMotion(enabled: Boolean) {
        synchronized(lock) {
            if (reducedMotion == enabled) return
            reducedMotion = enabled
            config = if (enabled) baseConfig.scaled(true) else baseConfig
        }
    }

    fun setConfig(newConfig: AnimationConfig) {
        synchronized(lock) {
            config = if (reducedMotion) newConfig.scaled(true) else newConfig
        }
    }

    // ------------------------------------------------------------------ frame update

    /**
     * Advances the mechanism. [deltaSeconds] is the real frame delta; it is clamped so a hitch
     * cannot make the record jump.
     */
    fun update(deltaSeconds: Float, audio: AudioSnapshot) {
        synchronized(lock) {
            val delta = deltaSeconds.coerceIn(0f, MAX_FRAME_DELTA)
            phaseElapsed += delta
            elapsedSeconds += delta
            if (touchDownElapsed < Float.MAX_VALUE) touchDownElapsed += delta
            // Media being prepared is the usual source of truth, but the controller's explicit
            // "a record is on the platter" intent wins during the frame or two before the first
            // synchronised snapshot arrives.
            hasRecord = audio.isPrepared || recordExpected

            updatePlatter(delta)
            advancePhase(delta, audio)
            updateControls(delta)
            updateNeedle(delta)
        }
    }

    private fun updatePlatter(delta: Float) {
        val nominal = nominalRpm()
        val target = when (phase) {
            VisualPhase.PLATTER_STARTING,
            VisualPhase.TONEARM_MOVING,
            VisualPhase.NEEDLE_LOWERING,
            VisualPhase.PLAYING,
            VisualPhase.SEEKING,
            VisualPhase.PAUSED,
            VisualPhase.NEEDLE_LIFTING,
            VisualPhase.TONEARM_RETURNING,
            -> nominal

            else -> 0f
        }

        // Constant acceleration in, constant deceleration out: a servo platter ramps speed instead of
        // snapping to it, and a switched-off motor coasts.
        val rate = if (target > platterRpm) {
            nominal / max(config.platterSpinUpSeconds, 1e-3f)
        } else {
            nominal / max(config.platterSpinDownSeconds, 1e-3f)
        }
        platterRpm = approach(platterRpm, target, rate * delta)
        platterAngleDeg = (platterAngleDeg + platterRpm * DEGREES_PER_RPM_SECOND * delta) % 360f
        if (platterAngleDeg < 0f) platterAngleDeg += 360f
    }

    private fun advancePhase(delta: Float, audio: AudioSnapshot) {
        when (phase) {
            VisualPhase.IDLE -> {
                holdArmAtRest(delta)
                // A record that has been placed stays seated; when nothing is loaded the renderer
                // simply does not draw the record group at all.
                recordInsertion01 = 0f
                recordOffsetY = 0f
                recordSettleBounce = 0f
                needleContact = false
            }

            VisualPhase.LOADING_RECORD -> {
                holdArmAtRest(delta)
                recordInsertion01 = 1f
                recordOffsetY = RECORD_INSERTION_HEIGHT
                if (phaseElapsed >= max(config.phaseSettleSeconds, MIN_PHASE_SECONDS)) {
                    enter(VisualPhase.RECORD_SETTLING)
                }
            }

            VisualPhase.RECORD_SETTLING -> {
                holdArmAtRest(delta)
                val duration = max(config.recordDropSeconds, 1e-3f)
                val t = (phaseElapsed / duration).coerceIn(0f, 1f)
                // Down fast, ease out into the mat, then a damped rebound that decays to nothing.
                val descent = 1f - Easing.easeOutCubic(t)
                val rebound = if (t > 0.68f) {
                    val reboundPhase = (t - 0.68f) / 0.32f
                    RECORD_REBOUND_METRES * Easing.dampedOscillation(reboundPhase, 1.7f, 4.2f)
                } else {
                    0f
                }
                recordInsertion01 = (descent + rebound / RECORD_INSERTION_HEIGHT).coerceIn(0f, 1f)
                recordOffsetY = max(0f, recordInsertion01 * RECORD_INSERTION_HEIGHT)
                recordSettleBounce = max(0f, rebound)
                if (t >= 1f) {
                    recordInsertion01 = 0f
                    recordOffsetY = 0f
                    recordSettleBounce = 0f
                    if (pendingPlay) {
                        pendingPlay = false
                        enter(VisualPhase.PLATTER_STARTING)
                    } else {
                        enter(VisualPhase.IDLE)
                    }
                }
            }

            VisualPhase.PLATTER_STARTING -> {
                holdArmAtRest(delta)
                recordInsertion01 = 0f
                recordOffsetY = 0f
                if (platterRpm >= nominalRpm() * PLATTER_SPEED_TOLERANCE) {
                    armTargetAngle = TonearmGeometry.angleForProgress(audio.progress)
                    enter(VisualPhase.TONEARM_MOVING)
                }
            }

            VisualPhase.TONEARM_MOVING -> {
                recordInsertion01 = 0f
                recordOffsetY = 0f
                val arrived = moveArmTowards(armTargetAngle, config.tonearmTravelSeconds, delta)
                if (arrived) enter(VisualPhase.NEEDLE_LOWERING)
                // The target keeps up with the audio in case playback is already running.
                if (abs(audio.progress - lastAudioProgress) > 0.001f) {
                    armTargetAngle = TonearmGeometry.angleForProgress(audio.progress)
                    lastAudioProgress = audio.progress
                }
            }

            VisualPhase.NEEDLE_LOWERING -> {
                val duration = max(config.needleLowerSeconds, 1e-3f)
                val t = (phaseElapsed / duration).coerceIn(0f, 1f)
                tonearmLift01 = 1f - Easing.easeInOutCubic(t)
                if (t >= 1f) {
                    tonearmLift01 = 0f
                    needleContact = true
                    touchDownElapsed = 0f
                    enter(VisualPhase.PLAYING)
                    onNeedleContact?.invoke()
                }
            }

            VisualPhase.PLAYING -> {
                needleContact = true
                tonearmLift01 = 0f
                // Track the audio position; the arm moves with real progress, not with wall time.
                armTargetAngle = TonearmGeometry.angleForProgress(audio.progress)
                lastAudioProgress = audio.progress
                moveArmTowards(armTargetAngle, TONEARM_TRACK_SECONDS, delta)
                // Audio stopped on its own (focus loss, unplugged headphones, error): lift the needle.
                if (!audio.isPlaying && !audio.isBuffering && !audio.isCompleted && touchDownElapsed > TOUCH_DOWN_GRACE_SECONDS) {
                    liftDestination = LiftDestination.PAUSED
                    enter(VisualPhase.NEEDLE_LIFTING)
                }
            }

            VisualPhase.PAUSED -> {
                needleContact = false
                tonearmLift01 = 1f
            }

            VisualPhase.SEEKING -> {
                needleContact = true
                tonearmLift01 = 0f
                armTargetAngle = TonearmGeometry.angleForProgress(seekTargetProgress)
                val arrived = moveArmTowards(armTargetAngle, config.seekTravelSeconds, delta)
                if (arrived || phaseElapsed >= config.seekTravelSeconds * SEEK_TIMEOUT_FACTOR) {
                    enter(VisualPhase.PLAYING)
                }
            }

            VisualPhase.NEEDLE_LIFTING -> {
                val duration = max(config.needleLiftSeconds, 1e-3f)
                val t = (phaseElapsed / duration).coerceIn(0f, 1f)
                tonearmLift01 = Easing.easeInOutCubic(t)
                needleContact = false
                if (t >= 1f) {
                    tonearmLift01 = 1f
                    when (liftDestination) {
                        LiftDestination.PAUSED -> {
                            enter(VisualPhase.PAUSED)
                        }
                        LiftDestination.PARK, LiftDestination.COMPLETE, LiftDestination.ERROR -> {
                            enter(VisualPhase.TONEARM_RETURNING)
                        }
                        LiftDestination.NONE -> enter(VisualPhase.PAUSED)
                    }
                }
            }

            VisualPhase.TONEARM_RETURNING -> {
                needleContact = false
                tonearmLift01 = 1f
                val arrived = moveArmTowards(TurntableSpec.TONEARM_REST_ANGLE_DEG, config.tonearmReturnSeconds, delta)
                if (arrived) enter(VisualPhase.PLATTER_STOPPING)
            }

            VisualPhase.PLATTER_STOPPING -> {
                needleContact = false
                if (platterRpm <= SPIN_DOWN_TOLERANCE_RPM) {
                    platterRpm = 0f
                    enter(stopDestination)
                }
            }

            VisualPhase.COMPLETED -> {
                needleContact = false
                tonearmLift01 = 1f
            }

            VisualPhase.ERROR -> {
                needleContact = false
                tonearmLift01 = 1f
            }
        }
    }

    private fun updateControls(delta: Float) {
        // Power button: held down while the mechanism is running.
        val targetPress = when (phase) {
            VisualPhase.IDLE, VisualPhase.COMPLETED, VisualPhase.ERROR -> 0f
            VisualPhase.PLATTER_STOPPING -> 0.35f
            else -> 1f
        }
        powerButtonPress = MathUtils.damp(powerButtonPress, targetPress, BUTTON_DAMPING, delta)

        // Indicator lamp: solid while running, a slow pulse while paused, a fast pulse on error.
        val targetLamp = when (phase) {
            VisualPhase.IDLE -> if (hasRecord) 0.32f else 0.06f
            VisualPhase.PAUSED -> 0.42f + 0.22f * pulse(0.75f)
            VisualPhase.ERROR -> 0.55f + 0.35f * pulse(2.4f)
            VisualPhase.COMPLETED -> 0.28f
            VisualPhase.PLATTER_STOPPING, VisualPhase.TONEARM_RETURNING -> 0.45f
            else -> 1f
        }
        lampLevel = MathUtils.damp(lampLevel, targetLamp, LAMP_DAMPING, delta)
        if (!lampWasSet) {
            lampLevel = targetLamp
            lampWasSet = true
        }

        // Speed selector knob.
        val selectorTarget = if (speed == PlatterSpeed.FORTY_FIVE) SPEED_SELECTOR_SWING_DEG else 0f
        speedSelectorAngleDeg = MathUtils.damp(speedSelectorAngleDeg, selectorTarget, CONTROLS_DAMPING, delta)
    }

    private fun updateNeedle(delta: Float) {
        if (!needleContact || platterRpm <= SPIN_DOWN_TOLERANCE_RPM) {
            touchDownElapsed = Float.MAX_VALUE
            needleMicroOffsetY = MathUtils.damp(needleMicroOffsetY, 0f, NEEDLE_SETTLE_DAMPING, delta)
            return
        }

        // Stylus riding the groove: a fast, tiny vertical flutter whose amplitude follows platter
        // speed, plus one decaying thump when it first touches down.
        val speedRatio = (platterRpm / nominalRpm()).coerceIn(0f, 1.4f)
        val flutter = NEEDLE_FLUTTER_METRES * speedRatio * (
            0.6f * sin(elapsedSeconds * TWO_PI * NEEDLE_FLUTTER_HZ) +
                0.4f * sin(elapsedSeconds * TWO_PI * NEEDLE_FLUTTER_HZ * 2.7f + 1.1f)
            )
        val thump = if (touchDownElapsed < TOUCH_DOWN_SECONDS) {
            val t = touchDownElapsed / TOUCH_DOWN_SECONDS
            TOUCH_DOWN_METRES * Easing.dampedOscillation(t, 12f, 5.5f)
        } else {
            0f
        }
        needleMicroOffsetY = flutter + thump
    }

    private fun pulse(hertz: Float): Float = 0.5f + 0.5f * sin(elapsedSeconds * TWO_PI * hertz)

    // ------------------------------------------------------------------ helpers

    private fun nominalRpm(): Float = speed.rpm

    private fun approach(current: Float, target: Float, maxStep: Float): Float {
        val difference = target - current
        return when {
            abs(difference) <= maxStep -> target
            difference > 0f -> current + maxStep
            else -> current - maxStep
        }
    }

    /** Moves the arm toward [targetDegrees] at a rate derived from [durationSeconds], returning true on arrival. */
    private fun moveArmTowards(targetDegrees: Float, durationSeconds: Float, delta: Float): Boolean {
        val difference = targetDegrees - tonearmAngleDeg
        if (abs(difference) <= ARM_ARRIVAL_TOLERANCE_DEG) {
            tonearmAngleDeg = targetDegrees
            return true
        }
        val rate = max(durationSeconds, 1e-3f)
        val degreesPerSecond = max(abs(difference) / rate, TONEARM_MIN_DEGREES_PER_SECOND)
        val step = degreesPerSecond * delta
        tonearmAngleDeg = if (abs(difference) <= step) targetDegrees else tonearmAngleDeg + step * if (difference > 0f) 1f else -1f
        return abs(targetDegrees - tonearmAngleDeg) <= ARM_ARRIVAL_TOLERANCE_DEG
    }

    private fun holdArmAtRest(delta: Float) {
        tonearmLift01 = 1f
        if (tonearmAngleDeg != TurntableSpec.TONEARM_REST_ANGLE_DEG) {
            moveArmTowards(TurntableSpec.TONEARM_REST_ANGLE_DEG, config.tonearmReturnSeconds, delta)
        }
    }

    private fun enter(next: VisualPhase) {
        if (phase == next) return
        phase = next
        phaseElapsed = 0f
        onPhaseChanged?.invoke(next)
    }

    /** Current phase, for tests and diagnostics. */
    val currentPhase: VisualPhase get() = synchronized(lock) { phase }

    /** Progress the arm would report, used by the static fallback and by tests. */
    fun tonearmProgress(): Float =
        synchronized(lock) { TonearmGeometry.progressForAngle(tonearmAngleDeg) }

    companion object {
        /** Height the record is held at before it drops, in metres. */
        const val RECORD_INSERTION_HEIGHT = 0.022f

        private const val MAX_FRAME_DELTA = 0.1f
        private const val MIN_PHASE_SECONDS = 0.02f
        private const val DEGREES_PER_RPM_SECOND = 6f
        private const val PLATTER_SPEED_TOLERANCE = 0.985f
        private const val SPIN_DOWN_TOLERANCE_RPM = 0.35f
        private const val ARM_ARRIVAL_TOLERANCE_DEG = 0.12f
        private const val TONEARM_MIN_DEGREES_PER_SECOND = 6f
        private const val RECORD_REBOUND_METRES = 0.0009f
        private const val NEEDLE_FLUTTER_METRES = 0.00006f
        private const val NEEDLE_FLUTTER_HZ = 31.5f
        private const val TOUCH_DOWN_METRES = 0.00035f
        private const val TOUCH_DOWN_SECONDS = 0.28f
        private const val TOUCH_DOWN_GRACE_SECONDS = 0.45f
        private const val SPEED_SELECTOR_SWING_DEG = 22f
        private const val BUTTON_DAMPING = 16f
        private const val LAMP_DAMPING = 7f
        private const val CONTROLS_DAMPING = 11f
        private const val NEEDLE_SETTLE_DAMPING = 14f
        private const val SEEK_TIMEOUT_FACTOR = 2.4f
        private const val TONEARM_TRACK_SECONDS = 0.35f
        private const val TWO_PI = (PI * 2.0).toFloat()
    }
}
