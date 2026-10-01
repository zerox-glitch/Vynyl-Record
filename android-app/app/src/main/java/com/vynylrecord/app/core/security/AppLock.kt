package com.vynylrecord.app.core.security

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The optional lock.
 *
 * A record app holds recordings of people's voices — dedications, messages to children, a grandmother's
 * recipe read aloud. On a shared phone that is the most private thing in the app, and this is the switch
 * that keeps it closed: a fingerprint, a face, or the device's own PIN as a fallback.
 *
 * ## The fallback is not an afterthought
 *
 * A phone with no enrolled fingerprint has to still open. `DEVICE_CREDENTIAL` in the allowed authenticators
 * means the same prompt accepts the device PIN, pattern or password, so enabling the lock can never lock a
 * user out of their own recordings. Where the device has neither biometrics nor a credential — rare, and
 * usually an emulator — the setting refuses to turn on rather than accepting a switch that does nothing.
 *
 * ## The timeout
 *
 * Locking the moment the app leaves the foreground is hostile: a user checking a message and coming back
 * would re-authenticate every time. Locking never is pointless. The timeout is a setting, defaults to thirty
 * seconds, and is measured from the moment the app went to the background.
 */
class AppLock(private val context: Context) {

    sealed interface State {
        /** No lock configured, or already unlocked for this session. */
        data object Unlocked : State

        /** Waiting for authentication. */
        data object Locked : State

        /** A prompt is on screen. */
        data object Prompting : State

        /** The lock is configured but this device cannot present a prompt right now. */
        data class Unavailable(val reason: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Unlocked)
    val state: StateFlow<State> = _state.asStateFlow()

    private var backgroundedAt: Long = 0L
    private var prompt: BiometricPrompt? = null

    /** Whether this device can present the prompt at all. */
    fun availability(): Availability {
        val manager = BiometricManager.from(context)
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        return when (manager.canAuthenticate(authenticators)) {
            BiometricManager.BIOMETRIC_SUCCESS -> Availability.OK
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> Availability.NO_SENSOR
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> Availability.BUSY
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> Availability.NOTHING_ENROLLED
            else -> Availability.UNAVAILABLE
        }
    }

    enum class Availability(val message: String, val canEnable: Boolean) {
        OK("Available on this device", true),
        NO_SENSOR("This device has no fingerprint sensor or screen lock set up", false),
        NOTHING_ENROLLED("Add a fingerprint or a screen lock first, then turn this on", false),
        BUSY("The sensor is busy right now; try again in a moment", true),
        UNAVAILABLE("The lock cannot be used on this device", false),
    }

    /** Called when the app leaves the foreground. */
    fun onBackgrounded() {
        backgroundedAt = SystemClock.elapsedRealtime()
    }

    /**
     * Called when the app returns.
     *
     * @param enabled the user's preference
     * @param timeoutSeconds how long the app may stay in the background before re-locking
     * @return true when the caller should show the lock screen
     */
    fun shouldLock(enabled: Boolean, timeoutSeconds: Int): Boolean {
        if (!enabled) {
            _state.value = State.Unlocked
            return false
        }
        val away = if (backgroundedAt == 0L) Long.MAX_VALUE else SystemClock.elapsedRealtime() - backgroundedAt
        val shouldLock = away >= timeoutSeconds.coerceAtLeast(0) * 1000L
        if (shouldLock) _state.value = State.Locked
        return shouldLock
    }

    /** Shows the system prompt. */
    fun prompt(activity: FragmentActivity, onResult: (Boolean) -> Unit) {
        val availability = availability()
        if (!availability.canEnable) {
            _state.value = State.Unavailable(availability.message)
            onResult(true)
            return
        }
        _state.value = State.Prompting
        val executor = ContextCompat.getMainExecutor(activity)
        val built = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    _state.value = State.Unlocked
                    onResult(true)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // A cancel by the user is a decision, not a failure: they get back into the app, since
                    // the data is theirs and the lock is a convenience. A repeated cancel is a real choice.
                    val cancelled = errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                        errorCode == BiometricPrompt.ERROR_CANCELED
                    _state.value = if (cancelled) State.Unlocked else State.Locked
                    Log.i(TAG, "lock dismissed ($errorCode): $errString")
                    onResult(cancelled)
                }

                override fun onAuthenticationFailed() {
                    // A finger that did not read is not an error; the prompt stays up and says so.
                    _state.value = State.Prompting
                }
            },
        )
        prompt = built
        built.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock Vynyl Record")
                .setSubtitle("Your recordings are locked on this device")
                .setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                )
                .setConfirmationRequired(false)
                .build(),
        )
    }

    fun lockNow() {
        _state.value = State.Locked
    }

    fun unlock() {
        _state.value = State.Unlocked
        backgroundedAt = 0L
    }

    fun cancelPrompt() {
        prompt?.cancelAuthentication()
        prompt = null
    }

    private companion object {
        const val TAG = "VynylAppLock"
    }
}
