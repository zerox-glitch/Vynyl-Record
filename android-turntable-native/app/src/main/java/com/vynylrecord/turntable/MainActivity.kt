package com.vynylrecord.turntable

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vynylrecord.turntable.player.TurntableViewModel
import com.vynylrecord.turntable.ui.VynylDemoRoot
import com.vynylrecord.turntable.ui.VynylTheme

/**
 * The demo activity.
 *
 * It hosts the Compose tree and keeps the player in a [TurntableViewModel] so rotation does not
 * interrupt playback; the reduced-motion preference is read inside the composition, where the
 * platform setting can be re-read after a configuration change.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val viewModel: TurntableViewModel = viewModel()
            VynylTheme {
                VynylDemoRoot(controller = viewModel.controller)
            }
        }
    }
}
