package com.vynylrecord.app.core.data.json

import com.vynylrecord.app.core.model.NoiseFilterShape
import com.vynylrecord.app.core.model.VinylControl
import com.vynylrecord.app.core.model.VinylControls
import com.vynylrecord.app.core.model.VinylPresetId
import com.vynylrecord.app.core.model.VinylRecipe

/**
 * The advanced controls, written down.
 *
 * A record remembers the exact recipe it was pressed with, including the knobs that were moved by hand.
 * That has to survive a restart, and — because a `.vynyl` bundle can be carried to another install where
 * the presets might have been retuned — it has to survive being read by a version of the app that has a
 * different default for a field it does not recognise.
 *
 * So decoding never fails and never trusts the document completely: it starts from the preset's own
 * recipe and overlays whatever the JSON provides, field by field. A bundle written by an older build
 * opens with sensible values for anything new, and a corrupted value is simply ignored rather than
 * producing a record that sounds like static.
 */
object RecipeJson {

    const val VERSION = 1

    fun encode(controls: VinylControls): String {
        val recipe = controls.recipe
        val document = JsonValue.obj(
            linkedMapOf<String, JsonValue>(
                "version" to JsonValue.of(VERSION),
                "moved" to JsonValue.of(controls.movedControls.map { it.name }),
                "drive" to JsonValue.of(recipe.saturationDrive),
                "mix" to JsonValue.of(recipe.saturationMix),
                "highPassHz" to JsonValue.of(recipe.highPassHz),
                "lowPassHz" to JsonValue.of(recipe.lowPassHz),
                "midGainDb" to JsonValue.of(recipe.midGainDb),
                "midFreqHz" to JsonValue.of(recipe.midFreqHz),
                "masterGain" to JsonValue.of(recipe.masterGain),
                "wowEnabled" to JsonValue.of(recipe.wowEnabled),
                "wowDepthCents" to JsonValue.of(recipe.wowDepthCents),
                "wowRatePerMin" to JsonValue.of(recipe.wowRatePerMin),
                "flutterEnabled" to JsonValue.of(recipe.flutterEnabled),
                "flutterDepthCents" to JsonValue.of(recipe.flutterDepthCents),
                "flutterRatePerMin" to JsonValue.of(recipe.flutterRatePerMin),
                "surfaceEnabled" to JsonValue.of(recipe.surfaceEnabled),
                "surfaceLevelDb" to JsonValue.of(recipe.surfaceLevelDb),
                "surfaceFilter" to JsonValue.of(recipe.surfaceFilter.id),
                "hissLevelDb" to JsonValue.of(recipe.hissLevelDb),
                "crackleEnabled" to JsonValue.of(recipe.crackleEnabled),
                "crackleDensityPerMin" to JsonValue.of(recipe.crackleDensityPerMin),
                "crackleIntensity" to JsonValue.of(recipe.crackleIntensity),
                "crackleBrightness" to JsonValue.of(recipe.crackleBrightness),
                "popsEnabled" to JsonValue.of(recipe.popsEnabled),
                "popsDensityPerMin" to JsonValue.of(recipe.popsDensityPerMin),
                "popsIntensity" to JsonValue.of(recipe.popsIntensity),
                "stereoEnabled" to JsonValue.of(recipe.stereoEnabled),
                "stereoWidth" to JsonValue.of(recipe.stereoWidth),
                "limiterCeil" to JsonValue.of(recipe.limiterCeil),
                "companderThresholdDb" to JsonValue.of(recipe.companderThresholdDb),
                "companderRatio" to JsonValue.of(recipe.companderRatio),
                "voiceWarmth" to JsonValue.of(recipe.voiceWarmth),
                "voicePresenceDb" to JsonValue.of(recipe.voicePresenceDb),
                "rumbleHz" to JsonValue.of(recipe.rumbleHz),
                "musicLevel" to JsonValue.of(recipe.musicLevel),
                "musicClarity" to JsonValue.of(recipe.musicClarity),
                "needleIntroMs" to JsonValue.of(recipe.needleIntroMs),
                "seedFromRecordingId" to JsonValue.of(recipe.seedFromRecordingId),
            ),
        )
        return document.write()
    }

    /**
     * Reads a recipe back, overlaid on [preset].
     *
     * Every field is optional. That is the whole point: this is the app reading a file that may have been
     * written by a different build, and the fallback for anything missing is "what this preset does now",
     * which is always a valid recipe.
     */
    fun decode(json: String?, preset: VinylPresetId): VinylControls {
        val fallback = VinylControls.of(preset)
        if (json.isNullOrBlank()) return fallback
        val document = JsonValue.parse(json)?.asObjOrNull() ?: return fallback

        val base = fallback.recipe
        val recipe = base.copy(
            saturationDrive = document.float("drive", base.saturationDrive),
            saturationMix = document.float("mix", base.saturationMix),
            highPassHz = document.float("highPassHz", base.highPassHz),
            lowPassHz = document.float("lowPassHz", base.lowPassHz),
            midGainDb = document.float("midGainDb", base.midGainDb),
            midFreqHz = document.float("midFreqHz", base.midFreqHz),
            masterGain = document.float("masterGain", base.masterGain),
            wowEnabled = document.bool("wowEnabled", base.wowEnabled),
            wowDepthCents = document.float("wowDepthCents", base.wowDepthCents),
            wowRatePerMin = document.float("wowRatePerMin", base.wowRatePerMin),
            flutterEnabled = document.bool("flutterEnabled", base.flutterEnabled),
            flutterDepthCents = document.float("flutterDepthCents", base.flutterDepthCents),
            flutterRatePerMin = document.float("flutterRatePerMin", base.flutterRatePerMin),
            surfaceEnabled = document.bool("surfaceEnabled", base.surfaceEnabled),
            surfaceLevelDb = document.float("surfaceLevelDb", base.surfaceLevelDb),
            surfaceFilter = NoiseFilterShape.fromId(document.string("surfaceFilter", base.surfaceFilter.id)),
            hissLevelDb = document.float("hissLevelDb", base.hissLevelDb),
            crackleEnabled = document.bool("crackleEnabled", base.crackleEnabled),
            crackleDensityPerMin = document.float("crackleDensityPerMin", base.crackleDensityPerMin),
            crackleIntensity = document.float("crackleIntensity", base.crackleIntensity),
            crackleBrightness = document.float("crackleBrightness", base.crackleBrightness),
            popsEnabled = document.bool("popsEnabled", base.popsEnabled),
            popsDensityPerMin = document.float("popsDensityPerMin", base.popsDensityPerMin),
            popsIntensity = document.float("popsIntensity", base.popsIntensity),
            stereoEnabled = document.bool("stereoEnabled", base.stereoEnabled),
            stereoWidth = document.float("stereoWidth", base.stereoWidth),
            limiterCeil = document.float("limiterCeil", base.limiterCeil),
            companderThresholdDb = document.float("companderThresholdDb", base.companderThresholdDb),
            companderRatio = document.float("companderRatio", base.companderRatio),
            voiceWarmth = document.float("voiceWarmth", base.voiceWarmth),
            voicePresenceDb = document.float("voicePresenceDb", base.voicePresenceDb),
            rumbleHz = document.float("rumbleHz", base.rumbleHz),
            musicLevel = document.float("musicLevel", base.musicLevel),
            musicClarity = document.float("musicClarity", base.musicClarity),
            needleIntroMs = document.int("needleIntroMs", base.needleIntroMs),
            seedFromRecordingId = document.bool("seedFromRecordingId", base.seedFromRecordingId),
        ).sanitised()

        // A control counts as moved only if this build still has such a control; an unknown name from a
        // future build is dropped rather than kept around to be written back out again.
        val moved = document.array("moved")?.strings()
            ?.mapNotNull { name -> VinylControl.entries.firstOrNull { it.name == name } }
            ?.toSet()
            ?: emptySet()

        return VinylControls(recipe = recipe, movedControls = moved)
    }

    /**
     * Clamps a decoded recipe into ranges the DSP can actually run.
     *
     * A hand-edited bundle, or one written by a build whose defaults were different, could otherwise ask
     * for a 1 Hz low-pass or a negative delay.
     */
    private fun VinylRecipe.sanitised(): VinylRecipe = copy(
        saturationDrive = saturationDrive.coerceIn(0f, 1f),
        saturationMix = saturationMix.coerceIn(0f, 1f),
        highPassHz = highPassHz.coerceIn(10f, 1_000f),
        lowPassHz = lowPassHz.coerceIn(2_000f, 22_000f),
        midGainDb = midGainDb.coerceIn(-12f, 12f),
        midFreqHz = midFreqHz.coerceIn(200f, 8_000f),
        masterGain = masterGain.coerceIn(0.1f, 4f),
        wowDepthCents = wowDepthCents.coerceIn(0f, 60f),
        // Rates are per minute, not per second: a preset that says 2 700 is 45 Hz of flutter, and a
        // clamp written in seconds here would silently flatten the motor to a 2 Hz wobble.
        wowRatePerMin = wowRatePerMin.coerceIn(3f, 18f),
        flutterDepthCents = flutterDepthCents.coerceIn(0f, 20f),
        flutterRatePerMin = flutterRatePerMin.coerceIn(1_800f, 3_600f),
        surfaceLevelDb = surfaceLevelDb.coerceIn(-80f, -6f),
        hissLevelDb = hissLevelDb.coerceIn(-80f, -6f),
        crackleDensityPerMin = crackleDensityPerMin.coerceIn(0f, 240f),
        crackleIntensity = crackleIntensity.coerceIn(0f, 1f),
        crackleBrightness = crackleBrightness.coerceIn(0f, 1f),
        popsDensityPerMin = popsDensityPerMin.coerceIn(0f, 30f),
        popsIntensity = popsIntensity.coerceIn(0f, 1f),
        stereoWidth = stereoWidth.coerceIn(0.5f, 1.25f),
        limiterCeil = limiterCeil.coerceIn(0.5f, 0.999f),
        companderThresholdDb = companderThresholdDb.coerceIn(-48f, 0f),
        companderRatio = companderRatio.coerceIn(1f, 12f),
        voiceWarmth = voiceWarmth.coerceIn(0f, 1f),
        voicePresenceDb = voicePresenceDb.coerceIn(-12f, 12f),
        rumbleHz = rumbleHz.coerceIn(20f, 200f),
        musicLevel = musicLevel.coerceIn(0f, 0.8f),
        musicClarity = musicClarity.coerceIn(0f, 1f),
        needleIntroMs = needleIntroMs.coerceIn(0, 2_000),
    )
}
