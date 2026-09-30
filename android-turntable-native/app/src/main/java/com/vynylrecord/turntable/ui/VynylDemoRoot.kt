package com.vynylrecord.turntable.ui

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vynylrecord.turntable.player.TurntableController

/**
 * Glue between the controller and the demo screen.
 *
 * The record is loaded once per composition; if the process is recreated the sample is already in
 * app-private storage, so loading is a file open rather than a copy.
 */
@Composable
fun VynylDemoRoot(
    controller: TurntableController,
    modifier: Modifier = Modifier,
) {
    val uiState by controller.state.collectAsStateWithLifecycle()
    val reducedMotion = rememberReducedMotion()

    LaunchedEffect(controller) {
        if (!uiState.hasRecord) controller.loadBundled()
    }

    // The platform's "remove animations" switch drives both the camera and the mechanism.
    LaunchedEffect(reducedMotion) {
        controller.setReducedMotion(reducedMotion)
    }

    TurntableDemoScreen(
        uiState = uiState,
        controller = controller,
        modifier = modifier,
    )
}

/**
 * Reads the system animator scale once per composition.
 *
 * Reading the setting is the only "environment" the app consults: no analytics, no identifiers, no
 * content observers. If the OEM blocks the read, animations stay on.
 */
@Composable
private fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        try {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f
        } catch (error: Throwable) {
            false
        }
    }
}
