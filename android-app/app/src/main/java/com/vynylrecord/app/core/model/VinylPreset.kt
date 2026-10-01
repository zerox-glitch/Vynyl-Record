package com.vynylrecord.app.core.model

/**
 * The spectral shape of the broadband surface bed.
 *
 * Ported from the web renderer's `NoiseFilterShape`. Each preset picks one, and the choice is audible:
 * `FLAT` keeps the low dust, `HIGHPASS_FLAT` is the bright hiss of a clean pressing.
 */
enum class NoiseFilterShape(val id: String) {
    FLAT("flat"),
    HIGHPASS_LOW("highpass-low"),
    HIGHPASS_FLAT("highpass-flat"),
    LOWPASS_ONLY("lowpass-only"),
    ;

    companion object {
        fun fromId(id: String?): NoiseFilterShape =
            entries.firstOrNull { it.id == id } ?: FLAT
    }
}

/**
 * One complete audio recipe: everything the renderer needs to turn a voice into a pressing.
 *
 * The field set and the units are the web application's `VinylPreset` interfaces taken field for field,
 * with six additions the native engine needs because it does the whole mix itself rather than handing a
 * filter graph to FFmpeg:
 *
 *  * [musicLevel]      — the background bed's gain, which the web app kept as a separate admin setting.
 *  * [hissLevelDb]     — tape hiss, which is a different layer from the groove bed.
 *  * [voicePresenceDb] — the extra presence band the voice chain needs on top of the preset's mid bell.
 *  * [musicClarity]    — how much high end the background keeps, so ducking does not make it muddy.
 *  * [crackleBrightness] — the tilt of a crackle transient, from a dull thump to an HF tick.
 *  * [needleIntroMs]   — the needle-drop lead-in before the voice starts.
 *
 * Every field is a plain number in the units a human would use (Hz, dB, cents, events per minute), not
 * a normalised knob. That is deliberate: the values are serialised into `.vynyl` bundles and read back
 * years later, so they have to stay meaningful even if the DSP is retuned.
 */
data class VinylRecipe(
    // ---- saturation
    val saturationDrive: Float,
    val saturationMix: Float,
    // ---- tone
    val highPassHz: Float,
    val lowPassHz: Float,
    val midGainDb: Float,
    val midFreqHz: Float,
    val masterGain: Float,
    // ---- motor
    val wowEnabled: Boolean,
    val wowDepthCents: Float,
    val wowRatePerMin: Float,
    val flutterEnabled: Boolean,
    val flutterDepthCents: Float,
    val flutterRatePerMin: Float,
    // ---- surface
    val surfaceEnabled: Boolean,
    val surfaceLevelDb: Float,
    val surfaceFilter: NoiseFilterShape,
    val hissLevelDb: Float,
    // ---- transients
    val crackleEnabled: Boolean,
    val crackleDensityPerMin: Float,
    val crackleIntensity: Float,
    val crackleBrightness: Float,
    val popsEnabled: Boolean,
    val popsDensityPerMin: Float,
    val popsIntensity: Float,
    // ---- image
    val stereoEnabled: Boolean,
    val stereoWidth: Float,
    // ---- dynamics
    val limiterCeil: Float,
    val companderThresholdDb: Float,
    val companderRatio: Float,
    // ---- voice band, applied before the vinyl EQ
    val voiceWarmth: Float,
    val voicePresenceDb: Float,
    val rumbleHz: Float,
    // ---- mix
    val musicLevel: Float,
    val musicClarity: Float,
    // ---- the record's own physical start
    val needleIntroMs: Int,
    /**
     * When true the noise / crackle / pop seeds are derived from the record's UUID, so two records made
     * from the same voice with the same preset do not share an identical pattern of pops. This is true
     * for every shipped preset, and it is what makes a re-render of the same record bit-identical while
     * still making the whole library sound like individual records.
     */
    val seedFromRecordingId: Boolean,
) {
    /** Kept for the renderer: dBFS to linear, once, instead of at every block. */
    val surfaceLinear: Float get() = dbToLinear(surfaceLevelDb)

    val hissLinear: Float get() = dbToLinear(hissLevelDb)

    companion object {
        fun dbToLinear(db: Float): Float = Math.pow(10.0, (db / 20.0)).toFloat()
    }
}

/**
 * The five pressings, with the values from `lib/audio/presets.ts`.
 *
 * These are not sliders with names attached. Each one is the complete recipe the renderer runs, and the
 * gradient between them is the product: a fresh pressing, a well-kept seventies record, something out of
 * an attic, a family tape, and an archival transfer that is barely holding together — with speech still
 * intelligible at the far end of it.
 */
enum class VinylPresetId(
    val id: String,
    val displayName: String,
    val description: String,
    val recipe: VinylRecipe,
) {
    CLEAN_VINYL(
        id = "clean_vinyl",
        displayName = "Clean Vinyl",
        description = "A fresh pressing: warm, present, almost-imperceptible crackle.",
        recipe = VinylRecipe(
            saturationDrive = 0.08f,
            saturationMix = 0.5f,
            highPassHz = 90f,
            lowPassHz = 16_500f,
            midGainDb = -1f,
            midFreqHz = 3_200f,
            masterGain = 1.0f,
            wowEnabled = true, wowDepthCents = 3f, wowRatePerMin = 3.4f,
            flutterEnabled = true, flutterDepthCents = 0.6f, flutterRatePerMin = 2_400f,
            surfaceEnabled = true, surfaceLevelDb = -42f, surfaceFilter = NoiseFilterShape.HIGHPASS_FLAT,
            hissLevelDb = -52f,
            crackleEnabled = true, crackleDensityPerMin = 10f, crackleIntensity = 0.20f, crackleBrightness = 0.62f,
            popsEnabled = true, popsDensityPerMin = 0.4f, popsIntensity = 0.55f,
            stereoEnabled = true, stereoWidth = 1.00f,
            limiterCeil = 0.96f, companderThresholdDb = -24f, companderRatio = 1.6f,
            voiceWarmth = 0.18f, voicePresenceDb = 0.5f, rumbleHz = 80f,
            musicLevel = 0.16f, musicClarity = 0.85f,
            needleIntroMs = 90,
            seedFromRecordingId = true,
        ),
    ),
    WARM_VINTAGE(
        id = "warm_vintage",
        displayName = "Warm Vintage",
        description = "A well-loved record from the 70s. Visible breath, denser bed.",
        recipe = VinylRecipe(
            saturationDrive = 0.28f,
            saturationMix = 0.75f,
            highPassHz = 75f,
            lowPassHz = 14_200f,
            midGainDb = 1.5f,
            midFreqHz = 2_800f,
            masterGain = 0.92f,
            wowEnabled = true, wowDepthCents = 6f, wowRatePerMin = 3.3f,
            flutterEnabled = true, flutterDepthCents = 1.2f, flutterRatePerMin = 2_700f,
            surfaceEnabled = true, surfaceLevelDb = -34f, surfaceFilter = NoiseFilterShape.HIGHPASS_FLAT,
            hissLevelDb = -44f,
            crackleEnabled = true, crackleDensityPerMin = 22f, crackleIntensity = 0.38f, crackleBrightness = 0.55f,
            popsEnabled = true, popsDensityPerMin = 0.9f, popsIntensity = 0.65f,
            stereoEnabled = true, stereoWidth = 0.95f,
            limiterCeil = 0.94f, companderThresholdDb = -22f, companderRatio = 2.2f,
            voiceWarmth = 0.42f, voicePresenceDb = 0.8f, rumbleHz = 70f,
            musicLevel = 0.18f, musicClarity = 0.75f,
            needleIntroMs = 140,
            seedFromRecordingId = true,
        ),
    ),
    DUSTY_RECORD(
        id = "dusty_record",
        displayName = "Dusty Record",
        description = "A record pulled out of an attic sleeve. Audible grit throughout.",
        recipe = VinylRecipe(
            saturationDrive = 0.45f,
            saturationMix = 0.85f,
            highPassHz = 70f,
            lowPassHz = 12_600f,
            midGainDb = 0f,
            midFreqHz = 2_400f,
            masterGain = 0.90f,
            wowEnabled = true, wowDepthCents = 8f, wowRatePerMin = 3.2f,
            flutterEnabled = true, flutterDepthCents = 1.8f, flutterRatePerMin = 2_900f,
            surfaceEnabled = true, surfaceLevelDb = -28f, surfaceFilter = NoiseFilterShape.HIGHPASS_LOW,
            hissLevelDb = -38f,
            crackleEnabled = true, crackleDensityPerMin = 38f, crackleIntensity = 0.55f, crackleBrightness = 0.46f,
            popsEnabled = true, popsDensityPerMin = 1.6f, popsIntensity = 0.75f,
            stereoEnabled = true, stereoWidth = 0.92f,
            limiterCeil = 0.94f, companderThresholdDb = -22f, companderRatio = 2.5f,
            voiceWarmth = 0.55f, voicePresenceDb = 0.2f, rumbleHz = 70f,
            musicLevel = 0.17f, musicClarity = 0.70f,
            needleIntroMs = 180,
            seedFromRecordingId = true,
        ),
    ),
    OLD_FAMILY_RECORD(
        id = "old_family_record",
        displayName = "Old Family Record",
        description = "A mid-century voice memo rescued from a magnetic tape.",
        recipe = VinylRecipe(
            saturationDrive = 0.55f,
            saturationMix = 0.95f,
            highPassHz = 65f,
            lowPassHz = 11_200f,
            midGainDb = -2f,
            midFreqHz = 1_800f,
            masterGain = 0.94f,
            wowEnabled = true, wowDepthCents = 12f, wowRatePerMin = 3.1f,
            flutterEnabled = true, flutterDepthCents = 2.6f, flutterRatePerMin = 3_300f,
            surfaceEnabled = true, surfaceLevelDb = -28f, surfaceFilter = NoiseFilterShape.FLAT,
            hissLevelDb = -34f,
            crackleEnabled = true, crackleDensityPerMin = 28f, crackleIntensity = 0.45f, crackleBrightness = 0.36f,
            popsEnabled = true, popsDensityPerMin = 1.0f, popsIntensity = 0.65f,
            stereoEnabled = true, stereoWidth = 0.88f,
            limiterCeil = 0.93f, companderThresholdDb = -21f, companderRatio = 3.0f,
            // More presence than the mid bell implies: the low-pass is doing the vintage work, and
            // without this the consonants start to disappear, which is the one thing that may not
            // happen to a family recording.
            voiceWarmth = 0.72f, voicePresenceDb = 2.4f, rumbleHz = 65f,
            musicLevel = 0.15f, musicClarity = 0.62f,
            needleIntroMs = 240,
            seedFromRecordingId = true,
        ),
    ),
    RARE_ARCHIVAL(
        id = "rare_archival",
        displayName = "Rare / Archival",
        description = "A domestic tape transferred barely in time. Old, cherished, listenable.",
        recipe = VinylRecipe(
            saturationDrive = 0.65f,
            saturationMix = 1.0f,
            highPassHz = 60f,
            lowPassHz = 9_800f,
            midGainDb = -3f,
            midFreqHz = 1_500f,
            masterGain = 0.92f,
            wowEnabled = true, wowDepthCents = 18f, wowRatePerMin = 2.9f,
            flutterEnabled = true, flutterDepthCents = 3.0f, flutterRatePerMin = 3_600f,
            surfaceEnabled = true, surfaceLevelDb = -24f, surfaceFilter = NoiseFilterShape.FLAT,
            hissLevelDb = -30f,
            crackleEnabled = true, crackleDensityPerMin = 48f, crackleIntensity = 0.65f, crackleBrightness = 0.30f,
            popsEnabled = true, popsDensityPerMin = 2.0f, popsIntensity = 0.80f,
            stereoEnabled = true, stereoWidth = 0.85f,
            limiterCeil = 0.92f, companderThresholdDb = -20f, companderRatio = 3.5f,
            voiceWarmth = 0.80f, voicePresenceDb = 3.0f, rumbleHz = 60f,
            musicLevel = 0.13f, musicClarity = 0.55f,
            needleIntroMs = 320,
            seedFromRecordingId = true,
        ),
    ),
    ;

    /** The three words a card shows under the name. Ported from `presetFlavor`. */
    val character: String
        get() {
            val r = recipe
            val parts = mutableListOf<String>()
            parts += when {
                r.saturationDrive >= 0.4f -> "tube warmth"
                r.saturationDrive >= 0.2f -> "gentle tape"
                else -> "clean press"
            }
            if (r.lowPassHz <= 10_500f) parts += "soft highs"
            if (r.wowDepthCents >= 8f) parts += "wandering wow"
            if (r.surfaceLevelDb >= -30f) parts += "audible surface"
            when {
                r.crackleIntensity >= 0.45f -> parts += "crackle"
                r.crackleIntensity >= 0.2f -> parts += "light crackle"
            }
            if (r.popsDensityPerMin >= 1.4f) parts += "occasional pops"
            return parts.joinToString(" • ")
        }

    /**
     * How much character this pressing has, 0..1, for the badge on a library row.
     *
     * Derived from the recipe rather than stored, so it can never drift away from what the record
     * actually sounds like.
     */
    val age: Float
        get() {
            // How much high end the recipe removed, against how much grit it added.
            val tilt = 1f - ((recipe.lowPassHz - 9_000f) / 8_000f).coerceIn(0f, 1f)
            val texture = (recipe.crackleIntensity + recipe.popsIntensity) / 2f
            return ((tilt * 0.55f) + (texture * 0.45f)).coerceIn(0f, 1f)
        }

    /** Warmth is the low-mid tilt the recipe actually applies. */
    val warmth: Float get() = (recipe.voiceWarmth * 0.6f + recipe.saturationDrive * 0.4f).coerceIn(0f, 1f)

    /** Texture is the audible imperfection, surface plus transients. */
    val texture: Float
        get() = (((recipe.surfaceLevelDb + 60f) / 36f).coerceIn(0f, 1f) * 0.5f +
            ((recipe.crackleIntensity + recipe.popsIntensity) / 2f) * 0.5f).coerceIn(0f, 1f)

    companion object {
        val all: List<VinylPresetId> = entries.toList()

        /** "Warm Vintage" — the web app's `DEFAULT_VINYL_PRESET_ID`. */
        val DEFAULT: VinylPresetId = WARM_VINTAGE

        fun fromId(id: String?): VinylPresetId =
            entries.firstOrNull { it.id == id } ?: DEFAULT

        /**
         * Ported from `FILTER_TO_VINYL_PRESET`: the four older preset names the web database still
         * stores, so an old `.vynyl` bundle or an old backup opens on the right recipe.
         */
        fun fromLegacyFilterId(id: String?): VinylPresetId? = when (id) {
            "clean" -> CLEAN_VINYL
            "gramophone" -> WARM_VINTAGE
            "radio" -> OLD_FAMILY_RECORD
            "tape" -> DUSTY_RECORD
            else -> null
        }
    }
}
