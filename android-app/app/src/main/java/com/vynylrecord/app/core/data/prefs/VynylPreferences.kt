package com.vynylrecord.app.core.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.vynylrecord.app.core.model.VinylPresetId
import com.vynylrecord.app.core.model.VinylStyleId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The app's one preference file.
 *
 * A delegate property rather than a field, because DataStore insists on a single instance per file: two
 * instances over the same path throw when the second one opens. Declaring it at file level means every
 * caller in the process — the graph, a test, a settings screen — gets the same store.
 */
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "vynyl_settings")

/**
 * Everything the app remembers between launches that is not a record.
 *
 * Preferences rather than database rows, because none of this is a library: it is the state of the app's
 * switches. DataStore gives the two properties that matter here — a write from the Settings screen is
 * visible to a collector in the Studio without any plumbing, and a corrupt or missing file yields
 * defaults rather than a crash.
 *
 * Every value has a default that is *the recommended one*, so a fresh install is already set up well and
 * the Settings screen's "reset" is a real instruction rather than a game of guesswork.
 */
class VynylPreferences(private val context: Context) {

    val store: DataStore<Preferences> get() = context.dataStore

    val flow: Flow<Preferences> get() = store.data

    /** True once the three onboarding pages have been seen. Shown once, as promised. */
    val onboardingComplete: Flow<Boolean> = store.data.map { it[ONBOARDING] ?: false }

    val settings: Flow<VynylSettings> = store.data.map { it.toSettings() }

    suspend fun current(): VynylSettings = store.data.first().toSettings()

    suspend fun setOnboardingComplete(complete: Boolean) = edit { it[ONBOARDING] = complete }

    // ---- privacy
    suspend fun setBiometricLock(enabled: Boolean) = edit { it[BIOMETRIC] = enabled }
    suspend fun setLockTimeoutSeconds(seconds: Int) = edit { it[LOCK_TIMEOUT] = seconds.coerceIn(0, 3_600) }
    suspend fun setHideNotificationDetail(hide: Boolean) = edit { it[HIDE_NOTIFICATION] = hide }

    // ---- audio
    suspend fun setAudioQuality(quality: AudioQuality) = edit { it[AUDIO_QUALITY] = quality.id }
    suspend fun setOutputFormat(format: OutputFormat) = edit { it[OUTPUT_FORMAT] = format.id }
    suspend fun setSampleRate(rate: Int) = edit { it[SAMPLE_RATE] = rate }
    suspend fun setDefaultPreset(preset: VinylPresetId) = edit { it[DEFAULT_PRESET] = preset.id }
    suspend fun setDefaultMusicLevel(level: Float) = edit { it[DEFAULT_MUSIC_LEVEL] = level.coerceIn(0f, 0.8f) }
    suspend fun setIncludeSourceInExport(include: Boolean) = edit { it[INCLUDE_SOURCE] = include }
    suspend fun setAutoRenderOnComplete(auto: Boolean) = edit { it[AUTO_RENDER] = auto }

    // ---- graphics
    suspend fun setGraphicsQuality(quality: GraphicsQuality) = edit { it[GRAPHICS] = quality.id }
    suspend fun setReducedMotion(reduced: Boolean) = edit { it[REDUCED_MOTION] = reduced }
    suspend fun setAutoOrbit(orbit: Boolean) = edit { it[AUTO_ORBIT] = orbit }
    suspend fun setShadows(shadows: Boolean) = edit { it[SHADOWS] = shadows }
    suspend fun setShowFps(show: Boolean) = edit { it[SHOW_FPS] = show }

    // ---- defaults for a new record
    suspend fun setLastStyle(style: VinylStyleId) = edit { it[LAST_STYLE] = style.id }
    suspend fun setLastOccasion(occasion: String) = edit { it[LAST_OCCASION] = occasion }
    suspend fun setSenderName(name: String) = edit { it[SENDER_NAME] = name.take(80) }
    suspend fun setGridLayout(grid: Boolean) = edit { it[GRID_LAYOUT] = grid }
    suspend fun setHapticsEnabled(enabled: Boolean) = edit { it[HAPTICS] = enabled }

    suspend fun resetToDefaults() = store.edit { preferences ->
        val onboarding = preferences[ONBOARDING]
        preferences.clear()
        // Onboarding is not a preference in the same sense: someone who has seen the three pages and then
        // resets their settings should not be shown them again.
        if (onboarding != null) preferences[ONBOARDING] = onboarding
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        store.edit(block)
    }

}

/**
 * Resolution for the turntable.
 *
 * The choice is a trade between battery and sharpness, and the names are the ones a person would use:
 * "Battery", "Balanced", "Sharp". The renderer scales its own buffer and shadow work from this.
 */
enum class GraphicsQuality(val id: String, val label: String, val description: String, val renderScale: Float) {
    LOW("low", "Battery", "Fewer segments and a smaller label texture: steady on older phones", 0.6f),
    MEDIUM("medium", "Balanced", "Sharp on most phones, with soft shadows", 1.0f),
    HIGH("high", "Sharp", "The densest meshes and the largest label texture; warm on long use", 1.4f),
    ;

    companion object {
        val DEFAULT = MEDIUM
        fun fromId(id: String?): GraphicsQuality = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/** How hard the encoder works. The bit rate is the only thing that changes. */
enum class AudioQuality(val id: String, val label: String, val description: String, val bitRate: Int) {
    STANDARD("standard", "Compact", "128 kbps — smallest files, still clean speech", 128_000),
    HIGH("high", "Balanced", "192 kbps — the default, and what the exports use", 192_000),
    STUDIO("studio", "Studio", "256 kbps — as good as AAC gets for a voice", 256_000),
    ;

    companion object {
        val DEFAULT = HIGH
        fun fromId(id: String?): AudioQuality = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/** What an export produces. The master is always WAV; this decides what leaves the device. */
enum class OutputFormat(val id: String, val label: String, val description: String) {
    M4A("m4a", "M4A", "Plays anywhere: phones, cars, message apps"),
    WAV("wav", "WAV", "Uncompressed, larger, exact"),
    BOTH("both", "Both", "M4A to send and WAV to keep"),
    ;

    companion object {
        val DEFAULT = M4A
        fun fromId(id: String?): OutputFormat = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/**
 * Every preference, as one object.
 *
 * Screens read this rather than picking values out of the store one at a time, which keeps the Settings
 * screen and the screen it configures reading the same thing in the same order.
 */
data class VynylSettings(
    val onboardingComplete: Boolean = false,
    val biometricLock: Boolean = false,
    val lockTimeoutSeconds: Int = 30,
    val hideNotificationDetail: Boolean = false,
    val audioQuality: AudioQuality = AudioQuality.DEFAULT,
    val outputFormat: OutputFormat = OutputFormat.DEFAULT,
    val sampleRate: Int = 44_100,
    val defaultPreset: VinylPresetId = VinylPresetId.DEFAULT,
    val defaultMusicLevel: Float = 0.18f,
    val includeSourceInExport: Boolean = false,
    val autoRenderOnComplete: Boolean = true,
    val graphicsQuality: GraphicsQuality = GraphicsQuality.DEFAULT,
    val reducedMotion: Boolean = false,
    val autoOrbit: Boolean = false,
    val shadows: Boolean = true,
    val showFps: Boolean = false,
    val lastStyle: VinylStyleId = VinylStyleId.DEFAULT,
    val lastOccasion: String = "",
    val senderName: String = "",
    val haptics: Boolean = true,
    /** The Vault's layout: the shelf of sleeves, or the list that shows more of the metadata. */
    val gridLayout: Boolean = true,

)

private fun Preferences.toSettings(): VynylSettings = VynylSettings(
    onboardingComplete = this[ONBOARDING] ?: false,
    biometricLock = this[BIOMETRIC] ?: false,
    lockTimeoutSeconds = this[LOCK_TIMEOUT] ?: 30,
    hideNotificationDetail = this[HIDE_NOTIFICATION] ?: false,
    audioQuality = AudioQuality.fromId(this[AUDIO_QUALITY]),
    outputFormat = OutputFormat.fromId(this[OUTPUT_FORMAT]),
    sampleRate = this[SAMPLE_RATE] ?: 44_100,
    defaultPreset = VinylPresetId.fromId(this[DEFAULT_PRESET]),
    defaultMusicLevel = this[DEFAULT_MUSIC_LEVEL] ?: 0.18f,
    includeSourceInExport = this[INCLUDE_SOURCE] ?: false,
    autoRenderOnComplete = this[AUTO_RENDER] ?: true,
    graphicsQuality = GraphicsQuality.fromId(this[GRAPHICS]),
    reducedMotion = this[REDUCED_MOTION] ?: false,
    autoOrbit = this[AUTO_ORBIT] ?: false,
    shadows = this[SHADOWS] ?: true,
    showFps = this[SHOW_FPS] ?: false,
    lastStyle = VinylStyleId.fromId(this[LAST_STYLE]),
    lastOccasion = this[LAST_OCCASION] ?: "",
    senderName = this[SENDER_NAME] ?: "",
    haptics = this[HAPTICS] ?: true,
    gridLayout = this[GRID_LAYOUT] ?: true,
)

/**
 * The preference keys, in one place.
 *
 * They are file-level rather than private to the class because the mapper below and the writers above must
 * agree on the spelling of every name; a rename in one and not the other would silently reset a setting
 * to its default, which is exactly the class of bug that is invisible until a user complains that their
 * turntable stopped orbiting.
 */
private val ONBOARDING = booleanPreferencesKey("onboarding_complete")
private val BIOMETRIC = booleanPreferencesKey("biometric_lock")
private val LOCK_TIMEOUT = intPreferencesKey("lock_timeout_seconds")
private val HIDE_NOTIFICATION = booleanPreferencesKey("hide_notification_detail")
private val AUDIO_QUALITY = stringPreferencesKey("audio_quality")
private val OUTPUT_FORMAT = stringPreferencesKey("output_format")
private val SAMPLE_RATE = intPreferencesKey("sample_rate")
private val DEFAULT_PRESET = stringPreferencesKey("default_preset")
private val DEFAULT_MUSIC_LEVEL = floatPreferencesKey("default_music_level")
private val INCLUDE_SOURCE = booleanPreferencesKey("include_source_in_export")
private val AUTO_RENDER = booleanPreferencesKey("auto_render_on_complete")
private val GRAPHICS = stringPreferencesKey("graphics_quality")
private val REDUCED_MOTION = booleanPreferencesKey("reduced_motion")
private val AUTO_ORBIT = booleanPreferencesKey("auto_orbit")
private val SHADOWS = booleanPreferencesKey("shadows")
private val SHOW_FPS = booleanPreferencesKey("show_fps")
private val LAST_STYLE = stringPreferencesKey("last_style")
private val LAST_OCCASION = stringPreferencesKey("last_occasion")
private val SENDER_NAME = stringPreferencesKey("sender_name")
private val HAPTICS = booleanPreferencesKey("haptics")
private val GRID_LAYOUT = booleanPreferencesKey("grid_layout")
