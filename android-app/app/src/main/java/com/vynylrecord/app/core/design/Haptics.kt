package com.vynylrecord.app.core.design

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * The app's touch feedback.
 *
 * Six moments get a haptic, and no others. A record player is a physical object, and the moments that matter
 * to it are mechanical: the platter starting, the needle dropping, a preset clicking into place. Haptics on
 * every tap would make the ones that mean something invisible.
 *
 * Everything here respects the system's haptics switch and the app's own: if a user has turned vibration off
 * — or turned it off in Settings — nothing happens, silently. A "haptic" that fires against the system
 * setting is worse than none.
 */
object Haptics {

    /** The moments that deserve feedback, in the order a record passes through them. */
    enum class Moment(val label: String) {
        RECORD_START("Recording starts"),
        RECORD_PAUSE("Recording pauses"),
        PRESET("A preset is chosen"),
        NEEDLE_DROP("The needle lands"),
        COMPLETE("The press is finished"),
        ERROR("Something went wrong"),
        ;

        companion object {
            val ordered: List<Moment> = entries.toList()
        }
    }

    /**
     * Plays [moment] on [view], if anything should happen.
     *
     * Uses the platform's own constants wherever they exist, because those are the taps a device's own
     * tuning has been calibrated for. The two moments Android has no constant for — a needle landing and a
     * press finishing — are played through the vibrator with a shape that matches their meaning: a landing is
     * short and firm, a completion is two soft beats.
     */
    fun play(view: View, moment: Moment, enabled: Boolean = true) {
        if (!enabled) return
        if (!view.isHapticFeedbackEnabled) return
        when (moment) {
            Moment.RECORD_START -> view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            Moment.RECORD_PAUSE -> view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            Moment.PRESET -> view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            Moment.NEEDLE_DROP -> waveform(view, WAVEFORM_NEEDLE, -1)
            Moment.COMPLETE -> waveform(view, WAVEFORM_COMPLETE, -1, COMPLETE_AMPLITUDE)
            Moment.ERROR -> waveform(view, WAVEFORM_ERROR, -1)
        }
    }

    /** Plays a moment without a view, for a state change that happens off-screen (a finished press). */
    fun play(context: Context, moment: Moment, enabled: Boolean = true) {
        if (!enabled) return
        val vibrator = vibrator(context) ?: return
        if (!vibrator.hasVibrator()) return
        val effect = when (moment) {
            Moment.NEEDLE_DROP -> VibrationEffect.createWaveform(WAVEFORM_NEEDLE, -1)
            Moment.COMPLETE -> VibrationEffect.createWaveform(WAVEFORM_COMPLETE, -1)
            Moment.ERROR -> VibrationEffect.createWaveform(WAVEFORM_ERROR, -1)
            else -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
        }
        runCatching { vibrator.vibrate(effect) }
    }

    private fun waveform(view: View, timings: LongArray, repeat: Int, amplitude: Int = -1) {
        val vibrator = vibrator(view.context) ?: return
        val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (amplitude > 0) {
                VibrationEffect.createWaveform(timings, intArrayOf(amplitude, 0, amplitude / 2), repeat)
            } else {
                VibrationEffect.createWaveform(timings, repeat)
            }
        } else {
            @Suppress("DEPRECATION")
            null
        }
        if (effect != null) {
            runCatching { vibrator.vibrate(effect) }
        } else {
            @Suppress("DEPRECATION")
            runCatching { vibrator.vibrate(timings, repeat) }
        }
    }

    private fun vibrator(context: Context): Vibrator? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(VibratorManager::class.java)
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
    } catch (error: Exception) {
        null
    }

    /** A short, firm tap: the needle contacting the record. */
    private val WAVEFORM_NEEDLE = longArrayOf(0, 12)

    /** Two soft beats: a record is finished. */
    private val WAVEFORM_COMPLETE = longArrayOf(0, 24, 70, 24)

    /** Three quick taps: something went wrong. */
    private val WAVEFORM_ERROR = longArrayOf(0, 20, 60, 20, 60, 20)

    private const val COMPLETE_AMPLITUDE = 160
}
