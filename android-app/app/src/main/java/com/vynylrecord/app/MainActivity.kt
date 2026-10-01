package com.vynylrecord.app

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.vynylrecord.app.core.data.prefs.VynylSettings
import com.vynylrecord.app.core.design.VynylTheme
import com.vynylrecord.app.core.design.reducedMotionEnabled
import com.vynylrecord.app.core.security.AppLock
import com.vynylrecord.app.core.storage.VynylBundle
import com.vynylrecord.app.feature.navigation.VynylNavHost
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** The shared graph, so a screen can reach the repositories without every call site passing them down. */
val LocalVynylGraph = staticCompositionLocalOf<VynylGraph> { error("VynylGraph was not provided") }

/**
 * The app's single activity.
 *
 * One activity, because the whole app is one task: a record being pressed, a record being played and a setting
 * being changed are all part of the same thing, and the back stack is the navigation. The activity's other job
 * is the two things that belong to the window rather than to a screen — the splash and the lock.
 *
 * ## What arrives from outside
 *
 * Two intents can open this activity: the launcher, and a `.vynyl` bundle from a file manager or a share
 * sheet. The bundle's Uri is handed to the navigation graph, which routes the user to the import sheet with
 * that file already selected. Nothing is imported without the user seeing what is in it.
 */
class MainActivity : ComponentActivity() {

    private lateinit var lock: AppLock

    /** The bundle waiting to be imported, if the activity was opened with one. */
    private var pendingImport by mutableStateOf<String?>(null)

    /** True while the app is behind its own lock, which the navigation gate turns into the lock screen. */
    private var locked by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val graph = VynylGraph.of(this)
        lock = AppLock(this)

        // The splash stays until the first frame of real content is ready. Reading the preference that
        // decides between onboarding, the lock and the Studio is a single DataStore read, so this is a frame
        // or two, not a held splash screen.
        var ready by mutableStateOf(false)
        splash.setKeepOnScreenCondition { !ready }

        pendingImport = importUriFrom(intent)

        lifecycleScope.launch {
            graph.preferences.settings.collectLatest { ready = true }
        }

        setContent {
            val settings by graph.preferences.settings.collectAsStateWithLifecycle(initialValue = VynylSettings())
            // The device's capabilities do not change while the app is open, so this is asked once rather
            // than on every recomposition — it is a Binder call into the biometric service.
            val availability = remember { lock.availability() }

            // The app's own switch, or the system's animation scale when it is set to "off" — a user who has
            // asked their phone for less motion has asked this app for less motion too.
            val reducedMotion = settings.reducedMotion || reducedMotionEnabled()
            VynylTheme(reducedMotion = reducedMotion) {
                CompositionLocalProvider(LocalVynylGraph provides graph) {
                    VynylNavHost(
                        settings = settings,
                        locked = locked,
                        lockAvailability = availability,
                        onUnlockRequested = { activity -> lock.prompt(activity) { unlocked -> if (unlocked) locked = false } },
                        onActivity = { this@MainActivity },
                        pendingImportPath = pendingImport,
                        onImportConsumed = { pendingImport = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        importUriFrom(intent)?.let { pendingImport = it }
    }

    override fun onStart() {
        super.onStart()
        // Coming back to a locked app shows the lock screen; the authentication prompt itself is raised by the
        // screen's own button, so a user sees where they are before a fingerprint dialog appears over it.
        lifecycleScope.launch {
            val settings = VynylGraph.of(this@MainActivity).preferences.current()
            locked = lock.shouldLock(settings.biometricLock, settings.lockTimeoutSeconds)
        }
    }

    override fun onStop() {
        lock.onBackgrounded()
        // A record that is playing in the background is not this app's job: playing stops when the player
        // screen goes away, which is honest about being an in-app player.
        super.onStop()
    }

    override fun onDestroy() {
        lock.cancelPrompt()
        super.onDestroy()
    }

    /** Keeps the screen awake while a press is running, which is the one time a user wants that. */
    fun keepScreenOn(keep: Boolean) {
        if (keep) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /**
     * The `.vynyl` file this activity was opened with, if any.
     *
     * Only the path is kept. The file is opened by the import sheet, which shows the user what is inside
     * before it is read — a bundle is the one input in this app that comes from outside, and a user should
     * be able to see what they are importing.
     */
    private fun importUriFrom(intent: Intent?): String? {
        if (intent == null) return null
        val uri = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM) as? android.net.Uri
            else -> null
        } ?: return null
        val type = intent.type
        val looksLikeBundle = type == null || type == VynylBundle.MIME_TYPE || type == "application/zip" ||
            uri.toString().endsWith(".${VynylBundle.EXTENSION}", ignoreCase = true)
        if (!looksLikeBundle) return null
        return uri.toString()
    }

    companion object {
        /** Set by the render notification: "open the record that was just pressed". */
        const val EXTRA_RECORD_ID = "com.vynylrecord.app.extra.RECORD_ID"
    }
}
