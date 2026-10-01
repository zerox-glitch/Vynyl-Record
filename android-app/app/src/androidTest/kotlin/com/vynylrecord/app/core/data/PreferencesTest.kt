package com.vynylrecord.app.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vynylrecord.app.core.data.prefs.AudioQuality
import com.vynylrecord.app.core.data.prefs.GraphicsQuality
import com.vynylrecord.app.core.data.prefs.OutputFormat
import com.vynylrecord.app.core.data.prefs.VynylPreferences
import com.vynylrecord.app.core.data.prefs.VynylSettings
import com.vynylrecord.app.core.model.VinylPresetId
import com.vynylrecord.app.core.model.VinylStyleId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app's switches, on a device.
 *
 * DataStore's contract is the interesting part: a write made on the Settings screen has to be visible to a
 * collector in the Studio, and a value the user never touched has to come back as the recommended one. The
 * clamping is checked too, because a stored value out of range is a bug that only shows up in another
 * screen's arithmetic.
 */
@RunWith(AndroidJUnit4::class)
class PreferencesTest {

    private lateinit var context: Context
    private lateinit var preferences: VynylPreferences

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        preferences = VynylPreferences(context)
        preferences.resetToDefaults()
        // The preference file outlives a single test class, so the first-run flag is cleared rather than
        // assumed; the onboarding test sets it back on purpose.
        preferences.setOnboardingComplete(false)
    }

    @Test
    fun a_switch_starts_at_the_recommended_value() = runBlocking {
        val settings = preferences.current()
        assertEquals(VynylSettings(), settings)
        assertFalse("a fresh install has already seen the onboarding", settings.onboardingComplete)
        assertFalse("the optional lock starts off", settings.biometricLock)
        assertEquals(AudioQuality.HIGH, settings.audioQuality)
        assertEquals(OutputFormat.M4A, settings.outputFormat)
        assertEquals(44_100, settings.sampleRate)
        assertEquals(VinylPresetId.WARM_VINTAGE, settings.defaultPreset)
        assertEquals(GraphicsQuality.MEDIUM, settings.graphicsQuality)
        assertTrue("shadows are part of the look and start on", settings.shadows)
        assertTrue(settings.haptics)
        assertEquals(0.18f, settings.defaultMusicLevel, 1e-4f)
    }

    @Test
    fun a_write_is_visible_to_the_next_read_and_to_the_flow() = runBlocking {
        preferences.setAudioQuality(AudioQuality.STUDIO)
        assertEquals(AudioQuality.STUDIO, preferences.current().audioQuality)
        // The screens collect the flow rather than asking for values one at a time, so this is the path
        // that actually has to work.
        assertEquals(AudioQuality.STUDIO, preferences.settings.first().audioQuality)

        preferences.setDefaultPreset(VinylPresetId.RARE_ARCHIVAL)
        preferences.setOutputFormat(OutputFormat.BOTH)
        preferences.setGraphicsQuality(GraphicsQuality.LOW)
        preferences.setReducedMotion(true)
        preferences.setAutoOrbit(true)
        preferences.setShowFps(true)
        preferences.setLastStyle(VinylStyleId.SMOKED_OBSIDIAN)
        preferences.setIncludeSourceInExport(true)
        preferences.setAutoRenderOnComplete(false)
        preferences.setHapticsEnabled(false)
        preferences.setGridLayout(false)
        preferences.setOnboardingComplete(true)

        val settings = preferences.current()
        assertEquals(VinylPresetId.RARE_ARCHIVAL, settings.defaultPreset)
        assertEquals(OutputFormat.BOTH, settings.outputFormat)
        assertEquals(GraphicsQuality.LOW, settings.graphicsQuality)
        assertTrue(settings.reducedMotion)
        assertTrue(settings.autoOrbit)
        assertTrue(settings.showFps)
        assertEquals(VinylStyleId.SMOKED_OBSIDIAN, settings.lastStyle)
        assertTrue(settings.includeSourceInExport)
        assertFalse(settings.autoRenderOnComplete)
        assertFalse(settings.haptics)
        assertFalse(settings.gridLayout)
        assertTrue(settings.onboardingComplete)
    }

    @Test
    fun the_timeouts_and_levels_are_kept_in_range() = runBlocking {
        preferences.setLockTimeoutSeconds(99_999)
        assertTrue("a timeout of an hour is the most the app allows", preferences.current().lockTimeoutSeconds <= 3_600)
        preferences.setLockTimeoutSeconds(-5)
        assertTrue(preferences.current().lockTimeoutSeconds >= 0)

        preferences.setDefaultMusicLevel(5f)
        assertTrue("a bed louder than the voice is not a bed", preferences.current().defaultMusicLevel <= 0.8f)
        preferences.setDefaultMusicLevel(-1f)
        assertEquals(0f, preferences.current().defaultMusicLevel, 1e-4f)

        preferences.setSenderName("a".repeat(200))
        assertTrue("a name is not an essay", preferences.current().senderName.length <= 80)
    }

    @Test
    fun onboarding_is_shown_once_and_resetting_settings_does_not_show_it_again() = runBlocking {
        preferences.setOnboardingComplete(true)
        preferences.setDefaultPreset(VinylPresetId.DUSTY_RECORD)
        preferences.setReducedMotion(true)

        preferences.resetToDefaults()

        val settings = preferences.current()
        assertTrue("resetting the settings showed the onboarding again", settings.onboardingComplete)
        // Everything else really is back to the recommended value.
        assertFalse(settings.reducedMotion)
        assertEquals(VinylPresetId.WARM_VINTAGE, settings.defaultPreset)
        assertEquals(VynylSettings(onboardingComplete = true), settings)
    }

    @Test
    fun an_unknown_stored_id_falls_back_instead_of_crashing() {
        // A setting written by an older build is a string in a file; reading it must never be able to stop
        // the app from opening.
        assertEquals(VinylPresetId.DEFAULT, VinylPresetId.fromId("a_preset_from_the_future"))
        assertEquals(VinylPresetId.DEFAULT, VinylPresetId.fromId(null))
        assertEquals(VinylStyleId.DEFAULT, VinylStyleId.fromId("chartreuse"))
        assertEquals(AudioQuality.DEFAULT, AudioQuality.fromId("lossless_please"))
        assertEquals(OutputFormat.DEFAULT, OutputFormat.fromId("flac"))
        assertEquals(GraphicsQuality.DEFAULT, GraphicsQuality.fromId("ultra"))
        // And every real id is round-tripped, because an id is what is written to the file.
        AudioQuality.entries.forEach { quality ->
            assertEquals(quality, AudioQuality.fromId(quality.id))
            assertTrue(quality.bitRate in 64_000..320_000)
        }
        OutputFormat.entries.forEach { format -> assertEquals(format, OutputFormat.fromId(format.id)) }
        GraphicsQuality.entries.forEach { quality ->
            assertEquals(quality, GraphicsQuality.fromId(quality.id))
            assertTrue("a quality scale of ${quality.renderScale} is not a scale", quality.renderScale in 0.5f..1.5f)
        }
    }

    @Test
    fun the_private_lock_settings_survive_a_write() = runBlocking {
        preferences.setBiometricLock(true)
        preferences.setLockTimeoutSeconds(45)
        preferences.setHideNotificationDetail(true)
        val settings = preferences.current()
        assertTrue(settings.biometricLock)
        assertEquals(45, settings.lockTimeoutSeconds)
        assertTrue(settings.hideNotificationDetail)
    }
}
