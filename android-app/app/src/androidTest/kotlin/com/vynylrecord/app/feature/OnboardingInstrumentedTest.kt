package com.vynylrecord.app.feature

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vynylrecord.app.LocalVynylGraph
import com.vynylrecord.app.VynylGraph
import com.vynylrecord.app.core.design.VynylTheme
import com.vynylrecord.app.feature.onboarding.OnboardingScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The first-run pages, as a user meets them.
 *
 * Onboarding is seen exactly once, by definition, so a mistake in it is a mistake almost nobody reports.
 * The three pages carry specified copy and one instruction — they explain that the app is local before the
 * user has a reason to trust it — and the controls have to be big enough to press.
 */
@RunWith(AndroidJUnit4::class)
class OnboardingInstrumentedTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var context: Context
    private var finished = 0

    private fun start() {
        context = ApplicationProvider.getApplicationContext()
        // The screen needs the app's graph, exactly as MainActivity provides it.
        compose.setContent {
            CompositionLocalProvider(LocalVynylGraph provides VynylGraph.of(context)) {
                VynylTheme {
                    OnboardingScreen(onFinished = { finished++ })
                }
            }
        }
    }

    private fun nextButton() = compose.onNode(hasClickAction() and hasText("Continue"))

    @Test
    fun the_first_page_says_what_the_app_is() {
        start()
        compose.onNodeWithText("A voice they can return to.").assertIsDisplayed()
        compose.onNodeWithText(
            "Record a few seconds of the people you love. It is pressed into wax on this phone, " +
                "and it stays on this phone.",
        ).assertIsDisplayed()
        // The relevant instruction is on the page before a permission is ever asked for.
        compose.onNodeWithText("Continue").assertIsDisplayed()
        assertEquals("finishing was called before anything was pressed", 0, finished)
    }

    @Test
    fun the_three_pages_advance_and_only_the_last_one_finishes() {
        start()

        nextButton().assertHeightIsAtLeast(48.dp).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Pressed locally.").assertIsDisplayed()
        compose.onNodeWithText(
            "The recording, the warmth, the crackle and the mastering all happen on the device. " +
                "No account, no upload, no waiting for a server.",
        ).assertIsDisplayed()

        nextButton().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Yours to keep.").assertIsDisplayed()
        compose.onNodeWithText(
            "Export a record as audio, artwork or a portable Vynyl bundle, and back up the whole shelf " +
                "whenever you like. Uninstalling without a backup removes records that were never exported.",
        ).assertIsDisplayed()

        // The last page's button is the one that means "start recording".
        compose.onNodeWithText("Continue").assertDoesNotExist()
        val finish = compose.onNode(hasClickAction() and hasText("Create first record"))
        finish.assertHeightIsAtLeast(48.dp).performClick()
        compose.waitForIdle()
        assertEquals(1, finished)
    }

    @Test
    fun skip_is_on_the_first_page_and_does_the_same_thing_as_finishing() {
        start()
        val skip = compose.onNode(hasClickAction() and hasText("Skip"))
        skip.assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        compose.waitForIdle()
        // Nobody should have to swipe through three pages to use the app they just installed.
        assertEquals(1, finished)
    }

    @Test
    fun the_user_cannot_finish_twice_from_one_press_of_the_last_button() {
        start()
        nextButton().performClick()
        compose.waitForIdle()
        nextButton().performClick()
        compose.waitForIdle()
        val finish = compose.onNode(hasClickAction() and hasText("Create first record"))
        finish.performClick()
        compose.waitForIdle()
        assertEquals(1, finished)
    }
}
