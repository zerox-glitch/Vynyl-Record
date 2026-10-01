package com.vynylrecord.turntable

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodes
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import com.vynylrecord.turntable.model.VinylStyle
import com.vynylrecord.turntable.ui.AppTab
import com.vynylrecord.turntable.ui.DemoTestTags
import com.vynylrecord.turntable.ui.ShellTestTags
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The demo surface, on a real device or emulator.
 *
 * These tests stay on the Compose side on purpose: the same assertions pass whether the 3D surface
 * or the static fallback is drawing, which is exactly the accessibility guarantee the module makes.
 *
 * The app opens on the Studio, so every test here walks to the deck first — through the same tab a
 * person taps, which means the shell's navigation is exercised on every run rather than in one test.
 */
class TurntableDemoScreenTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    /** Taps the 3D Deck tab and waits for the deck to have a record on it. */
    private fun waitForRecord() {
        waitForShell()
        rule.onNodeWithTag(ShellTestTags.tab(AppTab.DECK)).performClick()
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodesWithTagCount(DemoTestTags.SEEK) > 0
        }
        rule.waitForIdle()
    }

    private fun waitForShell() {
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodesWithTagCount(ShellTestTags.BOTTOM_BAR) > 0
        }
    }

    private fun onAllNodesWithTagCount(tag: String): Int =
        rule.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    @Test
    fun screenOpensWithTheSceneAndTransport() {
        waitForRecord()
        rule.onNodeWithTag(DemoTestTags.SCENE).assertIsDisplayed()
        rule.onNodeWithTag(DemoTestTags.PLAY_PAUSE).assertIsDisplayed()
        rule.onNodeWithTag(DemoTestTags.SEEK).assertIsDisplayed()
        rule.onNodeWithTag(DemoTestTags.SKIP_BACK).assertIsDisplayed()
        rule.onNodeWithTag(DemoTestTags.SKIP_FORWARD).assertIsDisplayed()
        rule.onNodeWithTag(DemoTestTags.CAMERA_RESET).assertIsDisplayed()
        rule.onNodeWithTag(DemoTestTags.PHASE).assertIsDisplayed()
    }

    @Test
    fun transportIsOperableWithoutTheThreeDScene() {
        waitForRecord()
        rule.onNodeWithTag(DemoTestTags.PLAY_PAUSE).assertIsEnabled()
        rule.onNodeWithTag(DemoTestTags.SEEK).assertIsEnabled()

        // Press play: the button must switch to "pause" immediately, without waiting for the
        // mechanism, so the control feels responsive.
        rule.onNodeWithTag(DemoTestTags.PLAY_PAUSE).performClick()
        rule.waitForIdle()
        // Immediate feedback matters: the button must flip to a pause affordance even though the
        // mechanism is still lowering the record.
        assertTrue(
            "the transport must report the request",
            rule.onAllNodes(hasContentDescription("Pause")).fetchSemanticsNodes().isNotEmpty(),
        )

        rule.onNodeWithTag(DemoTestTags.PLAY_PAUSE).performClick()
        rule.waitForIdle()
        assertTrue(
            "and back again",
            rule.onAllNodes(hasContentDescription("Play")).fetchSemanticsNodes().isNotEmpty(),
        )
    }

    @Test
    fun everyTransportControlHasAContentDescription() {
        waitForRecord()
        rule.onNodeWithContentDescription("Skip back 10 seconds").assertExists()
        rule.onNodeWithContentDescription("Skip forward 10 seconds").assertExists()
        rule.onNodeWithContentDescription("Reset camera").assertExists()
        rule.onNodeWithContentDescription("Full screen 3D").assertExists()
    }

    @Test
    fun vinylStylesCanBeSwitched() {
        waitForRecord()
        val target = VinylStyle.MIDNIGHT_SAPPHIRE
        rule.onNodeWithTag(DemoTestTags.styleChip(target)).performClick()
        rule.waitForIdle()
        // Selecting a chip keeps it in the tree; the demo must not crash on a style switch, which is
        // what exercises the material re-tint path on the GL thread.
        rule.onNodeWithTag(DemoTestTags.styleChip(target)).assertExists()
        rule.onNodeWithTag(DemoTestTags.STYLE_ROW).assertExists()
    }

    @Test
    fun cameraResetDoesNotThrow() {
        waitForRecord()
        rule.onNodeWithTag(DemoTestTags.CAMERA_RESET).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(DemoTestTags.SCENE).assertExists()
    }

    @Test
    fun fullScreenModeCanBeEnteredAndLeft() {
        waitForRecord()
        rule.onNodeWithTag(DemoTestTags.FULL_SCREEN).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(DemoTestTags.SCENE).assertIsDisplayed()
        rule.onNodeWithContentDescription("Leave full screen").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(DemoTestTags.SCENE).assertIsDisplayed()
    }

    @Test
    fun theSceneSurvivesBackgroundingAndRotation() {
        waitForRecord()
        rule.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        rule.waitForIdle()
        rule.onNodeWithTag(DemoTestTags.SCENE).assertExists()

        rule.activityRule.scenario.recreate()
        rule.waitForIdle()
        rule.onNodeWithTag(DemoTestTags.SCENE).assertExists()
        rule.onNodeWithTag(DemoTestTags.PLAY_PAUSE).assertExists()
    }

    @Test
    fun qualityAndPresetSelectorsArePresent() {
        waitForRecord()
        rule.onNodeWithTag(DemoTestTags.OPTIONS_TOGGLE).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(DemoTestTags.PRESET_ROW).assertExists()
        rule.onNodeWithTag(DemoTestTags.QUALITY_ROW).assertExists()
    }
}
