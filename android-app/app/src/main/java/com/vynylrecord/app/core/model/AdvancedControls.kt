package com.vynylrecord.app.core.model

/**
 * The advanced panel's knobs, as a view over [VinylRecipe].
 *
 * Picking a preset is the primary way to design a sound, but every value the preset sets is also
 * reachable by hand. Rather than keep a second copy of the numbers — which is how a "Customized" badge
 * ends up lying — this is a *projection*: it reads the recipe and writes the recipe back. There is
 * exactly one place a value lives.
 *
 * [VinylControls.overlay] applies only the knobs the user actually moved, so a preset changed later
 * still flows through.
 */
enum class VinylControl(
    val label: String,
    /** Shown under the slider: what moving it to the right actually does to the record. */
    val hint: String,
) {
    MUSIC_LEVEL("Music level", "How loud the background sits under the voice"),
    SURFACE_LEVEL("Surface level", "The continuous groove bed you hear between words"),
    CRACKLE("Crackle", "Density of small ticks"),
    POP_DENSITY("Pop density", "How often a louder pop lands"),
    HISS("Hiss", "Tape and amplifier hiss"),
    RUMBLE("Rumble", "Low-frequency motor noise kept in the mix"),
    VOICE_WARMTH("Voice warmth", "Weight under the voice"),
    VOICE_PRESENCE("Voice presence", "Consonants and closeness"),
    MUSIC_CLARITY("Music clarity", "How much high end the background keeps"),
    CRACKLE_BRIGHTNESS("Crackle brightness", "Dull thumps at the left, sharp ticks at the right"),
    WOW("Wow", "Slow pitch drift from an off-centre pressing"),
    FLUTTER("Flutter", "Faster, shallower speed wobble"),
    STEREO_WIDTH("Stereo width", "Mono at the left, wide at the right"),
    NEEDLE_INTRO("Needle intro delay", "Silence with the needle down before the voice starts"),
    ;

    /**
     * Where the preset's own value sits, as 0..1, and how to write a 0..1 value back into a recipe.
     *
     * The ranges are the ranges a person would expect from the labels: crackle 0..60 events a minute,
     * width 0.5..1.25 as the web renderer clamps it, and so on. Keeping the mapping in one place means a
     * slider can never write a value the DSP would reject.
     */
    fun read(recipe: VinylRecipe): Float = when (this) {
        MUSIC_LEVEL -> recipe.musicLevel / 0.8f
        SURFACE_LEVEL -> (recipe.surfaceLevelDb + 60f) / 42f
        CRACKLE -> recipe.crackleDensityPerMin / 60f
        POP_DENSITY -> recipe.popsDensityPerMin / 6f
        HISS -> (recipe.hissLevelDb + 60f) / 40f
        RUMBLE -> (140f - recipe.rumbleHz) / 90f
        VOICE_WARMTH -> recipe.voiceWarmth
        VOICE_PRESENCE -> (recipe.voicePresenceDb + 6f) / 12f
        MUSIC_CLARITY -> recipe.musicClarity
        CRACKLE_BRIGHTNESS -> recipe.crackleBrightness
        WOW -> recipe.wowDepthCents / 30f
        FLUTTER -> recipe.flutterDepthCents / 6f
        STEREO_WIDTH -> (recipe.stereoWidth - 0.5f) / 0.75f
        NEEDLE_INTRO -> recipe.needleIntroMs / 800f
    }.coerceIn(0f, 1f)

    /** Writes a 0..1 value into the recipe. */
    fun write(recipe: VinylRecipe, value: Float): VinylRecipe {
        val v = value.coerceIn(0f, 1f)
        return when (this) {
            MUSIC_LEVEL -> recipe.copy(musicLevel = v * 0.8f)
            SURFACE_LEVEL -> recipe.copy(surfaceEnabled = true, surfaceLevelDb = -60f + v * 42f)
            CRACKLE -> recipe.copy(crackleEnabled = true, crackleDensityPerMin = v * 60f)
            POP_DENSITY -> recipe.copy(popsEnabled = true, popsDensityPerMin = v * 6f)
            HISS -> recipe.copy(hissLevelDb = -60f + v * 40f)
            RUMBLE -> recipe.copy(rumbleHz = 140f - v * 90f)
            VOICE_WARMTH -> recipe.copy(voiceWarmth = v)
            VOICE_PRESENCE -> recipe.copy(voicePresenceDb = -6f + v * 12f)
            MUSIC_CLARITY -> recipe.copy(musicClarity = v)
            CRACKLE_BRIGHTNESS -> recipe.copy(crackleBrightness = v)
            WOW -> recipe.copy(wowEnabled = true, wowDepthCents = v * 30f)
            FLUTTER -> recipe.copy(flutterEnabled = true, flutterDepthCents = v * 6f)
            STEREO_WIDTH -> recipe.copy(stereoEnabled = true, stereoWidth = 0.5f + v * 0.75f)
            NEEDLE_INTRO -> recipe.copy(needleIntroMs = (v * 800f).toInt())
        }
    }

    /** How a value reads in the row's trailing label. */
    fun display(recipe: VinylRecipe): String = when (this) {
        MUSIC_LEVEL -> percent(recipe.musicLevel / 0.8f)
        SURFACE_LEVEL -> "${recipe.surfaceLevelDb.toInt()} dB"
        CRACKLE -> "${recipe.crackleDensityPerMin.toInt()}/min"
        POP_DENSITY -> "%.1f/min".format(recipe.popsDensityPerMin)
        HISS -> "${recipe.hissLevelDb.toInt()} dB"
        RUMBLE -> "${recipe.rumbleHz.toInt()} Hz"
        VOICE_WARMTH -> percent(recipe.voiceWarmth)
        VOICE_PRESENCE -> "%+.1f dB".format(recipe.voicePresenceDb)
        MUSIC_CLARITY -> percent(recipe.musicClarity)
        CRACKLE_BRIGHTNESS -> percent(recipe.crackleBrightness)
        WOW -> "%.1f cents".format(recipe.wowDepthCents)
        FLUTTER -> "%.1f cents".format(recipe.flutterDepthCents)
        STEREO_WIDTH -> "%.2f×".format(recipe.stereoWidth)
        NEEDLE_INTRO -> "${recipe.needleIntroMs} ms"
    }

    private fun percent(value: Float): String = "${(value.coerceIn(0f, 1f) * 100f).toInt()}%"

    companion object {
        /** The panel is laid out in this order; it reads as the pipeline runs. */
        val ordered: List<VinylControl> = entries.toList()
    }
}

/**
 * A recipe plus the controls the user moved by hand.
 *
 * Keeping the moved set separately is what lets "Customized" be honest: a record is customized when
 * something differs from the preset it was started from, and this records exactly that. Presetting a
 * record again — which the Studio offers as "Reset to preset" — clears it.
 */
data class VinylControls(
    val recipe: VinylRecipe = VinylPresetId.DEFAULT.recipe,
    val movedControls: Set<VinylControl> = emptySet(),
) {
    val isCustomized: Boolean get() = movedControls.isNotEmpty()

    fun with(control: VinylControl, value: Float): VinylControls = VinylControls(
        recipe = control.write(recipe, value),
        movedControls = if (control in movedControls) movedControls else movedControls + control,
    )

    fun resetTo(preset: VinylPresetId): VinylControls = VinylControls(preset.recipe, emptySet())

    fun value(control: VinylControl): Float = control.read(recipe)

    companion object {
        fun of(preset: VinylPresetId): VinylControls = VinylControls(preset.recipe, emptySet())
    }
}
