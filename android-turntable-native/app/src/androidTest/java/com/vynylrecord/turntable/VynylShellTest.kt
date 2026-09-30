package com.vynylrecord.turntable

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.vynylrecord.turntable.ui.AppTab
import com.vynylrecord.turntable.ui.DemoTestTags
import com.vynylrecord.turntable.ui.SettingsTestTags
import com.vynylrecord.turntable.ui.ShellTestTags
import com.vynylrecord.turntable.ui.SoundLabTestTags
import com.vynylrecord.turntable.ui.StudioTestTags
import com.vynylrecord.turntable.ui.VaultTestTags
import com.vynylrecord.turntable.studio.StudioStage
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The shell, on a device.
 *
 * These tests are about the promises the app makes before any audio exists: it opens on the Studio,
 * every tab is reachable and reachable back, the Vault starts empty, and the Studio will not press a
 * record it cannot label. They are deliberately shallow — the depth is in the JVM tests, which can
 * run the press chain on real samples.
 */
class VynylShellTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun count(tag: String): Int = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    private fun waitForShell(): VynylShellTest {
        rule.waitUntil(timeoutMillis = 10_000) { count(ShellTestTags.BOTTOM_BAR) > 0 }
        rule.waitForIdle()
        return this
    }

    /** Walks to a tab the way a person does. (Not named `open`: that is a Kotlin modifier.) */
    private fun goTo(tab: AppTab) {
        rule.onNodeWithTag(ShellTestTags.tab(tab)).performClick()
        rule.waitForIdle()
    }

    @Test
    fun opensOnTheStudioWithAllFiveTabs() {
        waitForShell()
        rule.onNodeWithTag(StudioTestTags.SCENE).assertIsDisplayed()
        for (tab in AppTab.entries) {
            rule.onNodeWithTag(ShellTestTags.tab(tab)).assertExists()
        }
        assertTrue(
            "the deck must not be the launch screen any more",
            count(DemoTestTags.SEEK) == 0,
        )
    }

    @Test
    fun everyTabOpensAndTheStudioCanBeReturnedTo() {
        waitForShell()
        rule.onNodeWithTag(StudioTestTags.RECORD_BUTTON).assertIsDisplayed()
        rule.onNodeWithTag(StudioTestTags.IMPORT_BUTTON).assertIsDisplayed()

        goTo(AppTab.LAB)
        rule.onNodeWithTag(SoundLabTestTags.SCENE).assertIsDisplayed()

        goTo(AppTab.VAULT)
        rule.onNodeWithTag(VaultTestTags.SCENE).assertIsDisplayed()

        goTo(AppTab.SETTINGS)
        rule.onNodeWithTag(SettingsTestTags.SCENE).assertIsDisplayed()

        goTo(AppTab.DECK)
        rule.onNodeWithTag(DemoTestTags.SCENE).assertIsDisplayed()

        goTo(AppTab.STUDIO)
        rule.onNodeWithTag(StudioTestTags.SCENE).assertIsDisplayed()
    }

    @Test
    fun theVaultStartsEmptyAndSaysSo() {
        waitForShell()
        goTo(AppTab.VAULT)
        // A fresh install has pressed nothing, and an empty screen that explains itself is the
        // difference between "nothing here yet" and "this is broken".
        rule.onNodeWithTag(VaultTestTags.EMPTY).assertExists()
    }

    @Test
    fun theStudioWalksThroughItsFourSteps() {
        waitForShell()
        rule.onNodeWithTag(StudioTestTags.SCENE).assertIsDisplayed()

        // Continue is disabled until there is something to engrave, so the step strip is the way
        // through: each step must render on its own.
        for (stage in StudioStage.ordered) {
            rule.onNodeWithTag(StudioTestTags.stageTag(stage)).performClick()
            rule.waitForIdle()
            rule.onNodeWithTag(StudioTestTags.SCENE).assertExists()
        }

        // The dedication step is the one with fields to fill in.
        rule.onNodeWithTag(StudioTestTags.stageTag(StudioStage.DEDICATE))
            .performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(StudioTestTags.TITLE_FIELD).performTextInput("Late Night Letter")
        rule.onNodeWithTag(StudioTestTags.RECIPIENT_FIELD).performTextInput("Mum")
        rule.waitForIdle()
        rule.onNodeWithTag(StudioTestTags.TITLE_FIELD).assertIsDisplayed()
    }

    @Test
    fun theStudioRefusesToPressWithoutAMaster() {
        waitForShell()
        // The last step is where the press button lives. With no capture and no import there is
        // nothing to engrave, so the button must be present and refused rather than absent - a
        // control that vanishes teaches the user nothing.
        goTo(AppTab.STUDIO)
        rule.onNodeWithTag(StudioTestTags.stageTag(StudioStage.ATMOSPHERE)).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(StudioTestTags.PRESS).assertExists()
        rule.onNodeWithTag(StudioTestTags.PRESS).assertIsNotEnabled()
    }

    @Test
    fun theRecordingAndImportControlsAreNamed() {
        waitForShell()
        rule.onNodeWithContentDescription("Start recording").assertExists()
        rule.onNodeWithText("Or import local voice recording").assertExists()
    }
}
