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
import com.vynylrecord.app.core.data.prefs.VynylSettings
import com.vynylrecord.app.core.design.VynylTheme
import com.vynylrecord.app.core.security.AppLock
import com.vynylrecord.app.feature.navigation.VynylNavHost
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app's four rooms, and the way between them.
 *
 * The bottom bar is the only navigation most people will ever use, so it is worth checking that each tab
 * leads to the screen it names, that the labels are the product's words rather than a placeholder, and that
 * the optional lock stands in front of all of it. The screens are the real ones: this composes the same
 * navigation host `MainActivity` does, against the app's own dependency graph.
 */
@RunWith(AndroidJUnit4::class)
class AppShellTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun openShell(
        settings: VynylSettings = VynylSettings(onboardingComplete = true),
        locked: Boolean = false,
        availability: AppLock.Availability = AppLock.Availability.OK,
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalVynylGraph provides VynylGraph.of(context)) {
                VynylTheme {
                    VynylNavHost(
                        settings = settings,
                        locked = locked,
                        lockAvailability = availability,
                        onUnlockRequested = {},
                        onActivity = { null },
                        pendingImportPath = null,
                        onImportConsumed = {},
                    )
                }
            }
        }
    }

    private fun tab(label: String) = compose.onNode(hasClickAction() and hasText(label))

    @Test
    fun the_master_vault_is_one_press_away_from_the_studio() {
        openShell()
        // The Studio is where the app opens, on the step that asks for the voice.
        compose.onNodeWithText("Speak the memory").assertIsDisplayed()

        tab("Master Vault").assertHeightIsAtLeast(48.dp).performClick()
        compose.waitForIdle()
        // The vault is the shelf, with the search field that filters it.
        compose.onNodeWithText("Every record you have pressed, kept on this phone.").assertIsDisplayed()
        compose.onNodeWithText("Search titles, names, dedications").assertIsDisplayed()
    }

    @Test
    fun the_sound_lab_lists_what_came_with_the_app() {
        openShell()
        tab("Sound Lab").performClick()
        compose.waitForIdle()
        // The bundled beds are registered by the graph on first use, so this is also the check that the
        // catalogue reaches the screen.
        compose.onNodeWithText("Add your own audio").assertIsDisplayed()
        compose.onNodeWithText("IN THE APP").assertIsDisplayed()
        compose.onNodeWithText("Nylon Guitar").assertIsDisplayed()
    }

    @Test
    fun the_settings_screen_shows_the_privacy_section_first() {
        openShell()
        tab("Settings").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Privacy").assertIsDisplayed()
        // The promise is on the screen, not only in the store listing.
        compose.onNodeWithText("Your recordings are files in this app's own storage.").assertIsDisplayed()
    }

    @Test
    fun the_four_tabs_are_the_four_places_the_app_has() {
        openShell()
        listOf("Studio", "Sound Lab", "Master Vault", "Settings").forEach { label ->
            tab(label).assertHeightIsAtLeast(48.dp)
        }
        // The player is not a tab: a record is opened from the vault, and there is nothing to play yet.
        compose.onNodeWithText("Player").assertDoesNotExist()
    }

    @Test
    fun going_back_to_the_studio_returns_to_the_capture_step() {
        openShell()
        tab("Master Vault").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Search titles, names, dedications").assertIsDisplayed()

        tab("Studio").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Speak the memory").assertIsDisplayed()
    }

    @Test
    fun the_lock_stands_in_front_of_everything_when_it_is_on() {
        openShell(
            settings = VynylSettings(onboardingComplete = true, biometricLock = true),
            locked = true,
            availability = AppLock.Availability.NO_SENSOR,
        )
        compose.onNodeWithText("Vynyl Record").assertIsDisplayed()
        compose.onNodeWithText("Your recordings are locked on this device.").assertIsDisplayed()
        // With no way to authenticate, the screen explains why and still offers a way forward: a user who
        // removed their fingerprint must not be locked out of their own recordings.
        compose.onNodeWithText("This device has no fingerprint sensor or screen lock set up").assertIsDisplayed()
        compose.onNode(hasClickAction() and hasText("Try again")).assertExists()
        // None of the app is reachable past the lock.
        compose.onNodeWithText("Speak the memory").assertDoesNotExist()
        compose.onNodeWithText("Master Vault").assertDoesNotExist()
    }

    @Test
    fun an_unlocked_shell_does_not_show_the_lock_screen() {
        openShell(settings = VynylSettings(onboardingComplete = true, biometricLock = false), locked = false)
        compose.onNodeWithText("Your recordings are locked on this device.").assertDoesNotExist()
        compose.onNodeWithText("Speak the memory").assertIsDisplayed()
    }
}
