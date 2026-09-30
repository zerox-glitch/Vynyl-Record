package com.vynylrecord.turntable

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vynylrecord.turntable.player.TurntableViewModel
import com.vynylrecord.turntable.studio.StudioViewModel
import com.vynylrecord.turntable.ui.VynylAppRoot
import com.vynylrecord.turntable.ui.VynylTheme

/**
 * The application activity.
 *
 * It hosts the Compose tree and keeps the whole app in two view models — one for the deck and its
 * audio, one for the Studio and the vault — so that rotating the device does not interrupt playback,
 * a press, or a recording in progress. The reduced-motion preference is read inside the composition,
 * where the platform setting can be re-read after a configuration change.
 *
 * There is deliberately no `android:configChanges` in the manifest: the activity is recreated on
 * rotation so the renderer, surface and controller teardown path is exercised on every turn of the
 * device rather than being avoided.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val player: TurntableViewModel = viewModel()
            val studio: StudioViewModel = viewModel()
            VynylTheme {
                VynylAppRoot(player = player, studio = studio)
            }
        }
    }
}
