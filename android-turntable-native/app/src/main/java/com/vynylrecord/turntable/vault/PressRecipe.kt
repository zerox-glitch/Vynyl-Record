package com.vynylrecord.turntable.vault

/**
 * How a side was cut: the character the lathe left behind.
 *
 * Every field is a normalised 0..1 knob rather than a raw DSP parameter, because these values are
 * driven by sliders, stored in the vault index and shown to the user. The mapping to actual DSP
 * constants lives in [com.vynylrecord.turntable.press.VinylPresser]; keeping the two apart means the
 * stored recipe stays meaningful even if the processing is retuned later.
 *
 * A recipe is *baked* into the pressed file. Nothing here is applied during playback, so a pressing
 * sounds the same in this app, in a file manager, or anywhere else the exported WAV is played.
 *
 * The data class carries no Android dependency and is pure data, so it can be unit tested and
 * serialised without a device.
 */
data class PressRecipe(
    /** Density and level of surface crackle: the ticks and pops of a played record. */
    val crackle: Float = 0.34f,
    /** The continuous groove bed — the quiet roar you hear between tracks. */
    val surfaceNoise: Float = 0.26f,
    /** Pitch instability from an off-centre pressing and a belt-driven platter, in percent. */
    val wowFlutter: Float = 0.22f,
    /** Tonal tilt: 0 is flat and digital, 1 is dark, rounded and valve-like. */
    val warmth: Float = 0.55f,
    /** Tail of room: how much of the space the recording was cut in comes back with it. */
    val roomTone: Float = 0.22f,
    /** Tape and amplifier hiss. */
    val hiss: Float = 0.14f,
    /** How hard the signal leans into the cutter: 0 is clean, 1 is audibly saturated. */
    val saturation: Float = 0.32f,
    /** Trim silence from the head and tail, then lay in a lead-in and a run-out groove. */
    val trimSilence: Boolean = true,
    /** Bring the peak up to just below full scale, as a mastering engineer would. */
    val normalize: Boolean = true,
) {

    init {
        require(crackle in 0f..1f) { "crackle must be 0..1" }
        require(surfaceNoise in 0f..1f) { "surfaceNoise must be 0..1" }
        require(wowFlutter in 0f..1f) { "wowFlutter must be 0..1" }
        require(warmth in 0f..1f) { "warmth must be 0..1" }
        require(roomTone in 0f..1f) { "roomTone must be 0..1" }
        require(hiss in 0f..1f) { "hiss must be 0..1" }
        require(saturation in 0f..1f) { "saturation must be 0..1" }
    }

    /** True when nothing would be added to the source at all. */
    val isMaster: Boolean
        get() = crackle == 0f && surfaceNoise == 0f && wowFlutter == 0f && warmth == 0f &&
            roomTone == 0f && hiss == 0f && saturation == 0f

    /** How heavily this side is treated, for the "1 of 6" style badge on a vault row. */
    val character: Float
        get() = (crackle + surfaceNoise + wowFlutter + warmth + roomTone + hiss + saturation) / 7f

    /** Short human description: "warm, crackled, steady". Never empty. */
    fun describe(): String {
        val parts = buildList {
            if (warmth >= 0.6f) add("warm") else if (warmth <= 0.2f) add("bright")
            if (crackle >= 0.6f) add("crackled") else if (crackle >= 0.25f) add("lightly crackled")
            if (surfaceNoise >= 0.55f) add("noisy") else if (surfaceNoise <= 0.12f) add("quiet floor")
            if (wowFlutter >= 0.6f) add("wobbly") else if (wowFlutter <= 0.1f) add("steady pitch")
            if (roomTone >= 0.55f) add("roomy")
            if (saturation >= 0.6f) add("driven")
        }
        return if (parts.isEmpty()) "clean master" else parts.joinToString(", ")
    }

    companion object {
        /** A flat, unprocessed transfer: the source exactly as it was captured. */
        val MASTER = PressRecipe(
            crackle = 0f,
            surfaceNoise = 0.05f,
            wowFlutter = 0.02f,
            warmth = 0.15f,
            roomTone = 0f,
            hiss = 0f,
            saturation = 0.08f,
        )

        /**
         * The house cut: a well-kept 33 in a quiet room. This is the default a new side is pressed
         * with, and the character the bundled demonstration pressing uses.
         */
        val STUDIO = PressRecipe()

        val all: List<Preset> = Preset.entries

        /**
         * Named characters offered as chips in the Studio and the Sound Lab.
         *
         * The names are the ones a person would use to describe the sound, not DSP terminology, so a
         * chip can be picked without knowing what a comb filter is.
         */
        enum class Preset(val displayName: String, val blurb: String, val recipe: PressRecipe) {
            CLEAN_MASTER(
                displayName = "Clean Master",
                blurb = "Flat transfer, as recorded",
                recipe = MASTER,
            ),
            HOUSE_CUT(
                displayName = "House Cut",
                blurb = "A well-kept 33 in a quiet room",
                recipe = STUDIO,
            ),
            WARM_DUB(
                displayName = "Warm Dub",
                blurb = "Dark, rolling, a little distorted",
                recipe = PressRecipe(
                    crackle = 0.30f,
                    surfaceNoise = 0.34f,
                    wowFlutter = 0.40f,
                    warmth = 0.88f,
                    roomTone = 0.46f,
                    hiss = 0.22f,
                    saturation = 0.58f,
                ),
            ),
            ATTIC_FIND(
                displayName = "Attic Find",
                blurb = "Played a hundred times, loved every one",
                recipe = PressRecipe(
                    crackle = 0.72f,
                    surfaceNoise = 0.62f,
                    wowFlutter = 0.48f,
                    warmth = 0.70f,
                    roomTone = 0.30f,
                    hiss = 0.44f,
                    saturation = 0.40f,
                ),
            ),
            RADIO_BROADCAST(
                displayName = "Radio Broadcast",
                blurb = "Compressed, mid-focused, hissy",
                recipe = PressRecipe(
                    crackle = 0.22f,
                    surfaceNoise = 0.46f,
                    wowFlutter = 0.12f,
                    warmth = 0.42f,
                    roomTone = 0.10f,
                    hiss = 0.66f,
                    saturation = 0.70f,
                ),
            ),
            ARCHIVE_TRANSFER(
                displayName = "Archive Transfer",
                blurb = "Museum playback, noise floor and all",
                recipe = PressRecipe(
                    crackle = 0.46f,
                    surfaceNoise = 0.52f,
                    wowFlutter = 0.62f,
                    warmth = 0.62f,
                    roomTone = 0.36f,
                    hiss = 0.36f,
                    saturation = 0.30f,
                ),
            ),
        }

        /** Nearest preset to a recipe, so the Studio can highlight the chip a hand-tuned side matches. */
        fun closestPreset(recipe: PressRecipe): Preset = Preset.entries.minBy { distance(it.recipe, recipe) }

        private fun distance(a: PressRecipe, b: PressRecipe): Float =
            kotlin.math.abs(a.crackle - b.crackle) +
                kotlin.math.abs(a.surfaceNoise - b.surfaceNoise) +
                kotlin.math.abs(a.wowFlutter - b.wowFlutter) +
                kotlin.math.abs(a.warmth - b.warmth) +
                kotlin.math.abs(a.roomTone - b.roomTone) +
                kotlin.math.abs(a.hiss - b.hiss) +
                kotlin.math.abs(a.saturation - b.saturation)
    }
}
