package com.vynylrecord.turntable.vault

import com.vynylrecord.turntable.model.PlatterSpeed
import com.vynylrecord.turntable.model.RecordMetadata
import com.vynylrecord.turntable.model.RecordSide
import com.vynylrecord.turntable.model.VinylStyle
import java.util.Properties

/**
 * One pressed side in the vault: the audio, the label, the character and where it came from.
 *
 * A press owns a directory. The audio lives in it under [fileName] and the record's own description
 * lives beside it as `press.properties`. Keeping the two together means a press is self-contained —
 * deleting a folder removes the record, copying a folder copies the record, and there is no index
 * file to fall out of step with the audio on disk.
 *
 * The description is written with [Properties], which is a plain JVM class: this type and
 * [PressStore] are unit tested on the JVM with no device and no Android dependency.
 */
data class Press(
    val id: String,
    val metadata: RecordMetadata,
    val style: VinylStyle,
    val speed: PlatterSpeed,
    val recipe: PressRecipe,
    /** Name of the audio file inside this press's directory. */
    val fileName: String,
    val createdAtEpochMs: Long,
    val durationMs: Long,
    val byteCount: Long,
    /** Where the source came from: "Recorded in Studio", or the imported file's display name. */
    val sourceLabel: String,
) {

    /** Title as the vault and the now-playing card show it. */
    val displayTitle: String get() = metadata.title

    val durationLabel: String
        get() {
            val totalSeconds = durationMs / 1000L
            return "%d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
        }

    val sizeLabel: String
        get() = when {
            byteCount >= 1024L * 1024L -> "%.1f MB".format(byteCount / (1024f * 1024f))
            byteCount >= 1024L -> "%d KB".format(byteCount / 1024L)
            else -> "$byteCount B"
        }

    fun toProperties(): Properties = Properties().apply {
        setProperty(KEY_VERSION, VERSION.toString())
        setProperty(KEY_ID, id)
        setProperty(KEY_TITLE, metadata.title)
        setProperty(KEY_RECIPIENT, metadata.recipient)
        setProperty(KEY_SENDER, metadata.sender)
        setProperty(KEY_SIDE, metadata.side.name)
        metadata.date?.let { setProperty(KEY_DATE, it) }
        metadata.catalogue?.let { setProperty(KEY_CATALOGUE, it) }
        setProperty(KEY_STYLE, style.name)
        setProperty(KEY_SPEED, speed.name)
        setProperty(KEY_FILE, fileName)
        setProperty(KEY_CREATED, createdAtEpochMs.toString())
        setProperty(KEY_DURATION, durationMs.toString())
        setProperty(KEY_BYTES, byteCount.toString())
        setProperty(KEY_SOURCE, sourceLabel)
        with(recipe) {
            setProperty(KEY_CRACKLE, crackle.toString())
            setProperty(KEY_SURFACE, surfaceNoise.toString())
            setProperty(KEY_WOW, wowFlutter.toString())
            setProperty(KEY_WARMTH, warmth.toString())
            setProperty(KEY_ROOM, roomTone.toString())
            setProperty(KEY_HISS, hiss.toString())
            setProperty(KEY_SATURATION, saturation.toString())
            setProperty(KEY_TRIM, trimSilence.toString())
            setProperty(KEY_NORMALIZE, normalize.toString())
        }
    }

    companion object {
        val VERSION = 1

        private const val KEY_VERSION = "version"
        private const val KEY_ID = "id"
        private const val KEY_TITLE = "title"
        private const val KEY_RECIPIENT = "recipient"
        private const val KEY_SENDER = "sender"
        private const val KEY_SIDE = "side"
        private const val KEY_DATE = "date"
        private const val KEY_CATALOGUE = "catalogue"
        private const val KEY_STYLE = "style"
        private const val KEY_SPEED = "speed"
        private const val KEY_FILE = "file"
        private const val KEY_CREATED = "created"
        private const val KEY_DURATION = "duration"
        private const val KEY_BYTES = "bytes"
        private const val KEY_SOURCE = "source"
        private const val KEY_CRACKLE = "crackle"
        private const val KEY_SURFACE = "surface"
        private const val KEY_WOW = "wow"
        private const val KEY_WARMTH = "warmth"
        private const val KEY_ROOM = "room"
        private const val KEY_HISS = "hiss"
        private const val KEY_SATURATION = "saturation"
        private const val KEY_TRIM = "trim"
        private const val KEY_NORMALIZE = "normalize"

        /** The file, inside a press directory, that describes the press. */
        const val PROPERTIES_FILE = "press.properties"

        /**
         * Rebuilds a press from its stored description.
         *
         * Returns null rather than throwing when the file is damaged or was written by a newer
         * version: one unreadable record must not take the vault down with it.
         */
        fun fromProperties(properties: Properties): Press? = try {
            val version = properties.getProperty(KEY_VERSION)?.toIntOrNull() ?: VERSION
            if (version > VERSION) return null

            val title = properties.getProperty(KEY_TITLE)?.takeIf { it.isNotBlank() } ?: return null
            val fileName = properties.getProperty(KEY_FILE)?.takeIf { it.isNotBlank() } ?: return null
            val id = properties.getProperty(KEY_ID)?.takeIf { it.isNotBlank() } ?: return null

            Press(
                id = id,
                metadata = RecordMetadata(
                    title = title,
                    recipient = properties.getProperty(KEY_RECIPIENT).orEmpty().ifBlank { "You" },
                    sender = properties.getProperty(KEY_SENDER).orEmpty().ifBlank { "Vynyl" },
                    side = runCatching { RecordSide.valueOf(properties.getProperty(KEY_SIDE).orEmpty()) }
                        .getOrDefault(RecordSide.A),
                    date = properties.getProperty(KEY_DATE),
                    catalogue = properties.getProperty(KEY_CATALOGUE),
                ),
                style = runCatching { VinylStyle.valueOf(properties.getProperty(KEY_STYLE).orEmpty()) }
                    .getOrDefault(VinylStyle.DEFAULT),
                speed = runCatching { PlatterSpeed.valueOf(properties.getProperty(KEY_SPEED).orEmpty()) }
                    .getOrDefault(PlatterSpeed.THIRTY_THREE),
                recipe = PressRecipe(
                    crackle = properties.ratio(KEY_CRACKLE, 0.34f),
                    surfaceNoise = properties.ratio(KEY_SURFACE, 0.26f),
                    wowFlutter = properties.ratio(KEY_WOW, 0.22f),
                    warmth = properties.ratio(KEY_WARMTH, 0.55f),
                    roomTone = properties.ratio(KEY_ROOM, 0.22f),
                    hiss = properties.ratio(KEY_HISS, 0.14f),
                    saturation = properties.ratio(KEY_SATURATION, 0.32f),
                    trimSilence = properties.getProperty(KEY_TRIM)?.toBooleanStrictOrNull() ?: true,
                    normalize = properties.getProperty(KEY_NORMALIZE)?.toBooleanStrictOrNull() ?: true,
                ),
                fileName = fileName,
                createdAtEpochMs = properties.getProperty(KEY_CREATED)?.toLongOrNull() ?: 0L,
                durationMs = properties.getProperty(KEY_DURATION)?.toLongOrNull() ?: 0L,
                byteCount = properties.getProperty(KEY_BYTES)?.toLongOrNull() ?: 0L,
                sourceLabel = properties.getProperty(KEY_SOURCE).orEmpty().ifBlank { "Studio" },
            )
        } catch (error: IllegalArgumentException) {
            // A recipe field out of range is the only thing PressRecipe can throw; treat it as damage.
            null
        }

        private fun Properties.ratio(key: String, fallback: Float): Float =
            getProperty(key)?.toFloatOrNull()?.coerceIn(0f, 1f) ?: fallback
    }
}
