package com.vynylrecord.turntable

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.vynylrecord.turntable.graphics.RenderQuality
import com.vynylrecord.turntable.graphics.animation.VisualPhase
import com.vynylrecord.turntable.model.VinylStyle
import com.vynylrecord.turntable.player.TurntableUiState
import com.vynylrecord.turntable.ui.DemoTestTags
import com.vynylrecord.turntable.ui.StaticTurntableFallback
import com.vynylrecord.turntable.ui.VynylTheme
import org.junit.Rule
import org.junit.Test
import androidx.compose.ui.platform.testTag

/**
 * The no-OpenGL-ES-3.0 path, rendered on its own.
 *
 * A device without ES 3.0 still has to show a record player, so the fallback is tested directly
 * rather than only through the activity: it must draw in every mechanism phase without throwing,
 * including the phases where the arm is mid-flight.
 */
class StaticFallbackTest {

    @get:Rule
    val rule = createComposeRule()

    private fun state(phase: VisualPhase, playing: Boolean = false) = TurntableUiState(
        hasRecord = true,
        isPlaying = playing,
        visualPhase = phase,
        positionMs = 12_000L,
        durationMs = 60_000L,
        vinylStyle = VinylStyle.IMPERIAL_GOLD_MASTER,
        quality = RenderQuality.LOW,
        isEs3Available = false,
    )

    @Test
    fun drawsInEveryPhase() {
        var phase by mutableStateOf(VisualPhase.IDLE)
        rule.setContent {
            VynylTheme {
                StaticTurntableFallback(
                    uiState = state(phase),
                    modifier = Modifier.fillMaxSize().testTag(DemoTestTags.SCENE),
                )
            }
        }
        rule.waitForIdle()
        for (candidate in VisualPhase.entries) {
            phase = candidate
            rule.waitForIdle()
            rule.onNodeWithTag(DemoTestTags.SCENE).assertExists()
        }
    }

    @Test
    fun drawsWhilePlayingAndSeeking() {
        rule.setContent {
            VynylTheme {
                StaticTurntableFallback(
                    uiState = state(VisualPhase.PLAYING, playing = true),
                    modifier = Modifier.fillMaxSize().testTag(DemoTestTags.SCENE),
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag(DemoTestTags.SCENE).assertExists()
    }
}
