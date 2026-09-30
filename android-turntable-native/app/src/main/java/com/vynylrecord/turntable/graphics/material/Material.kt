package com.vynylrecord.turntable.graphics.material

import com.vynylrecord.turntable.model.ColorPacking
import com.vynylrecord.turntable.model.TurntableSpec
import com.vynylrecord.turntable.model.VinylStyle

/**
 * GPU material description.
 *
 * One instance maps 1:1 onto the uniforms consumed by `turntable.frag`. Values are held in
 * mutable arrays because the renderer uploads them directly every draw and must not allocate
 * inside the frame loop; changing style mutates a material in place instead of replacing it.
 *
 * Colours are **linear light**, not sRGB: [MaterialLibrary] converts the sRGB palette with
 * [ColorPacking.channelToLinear] so the shader maths behaves physically before the final gamma
 * encode.
 */
class Material(val name: String) {

    /** Base colour, linear RGB. */
    val albedo = floatArrayOf(0.5f, 0.5f, 0.5f)

    /** Specular tint for metals (multiplies the albedo used for `F0`). */
    val specularTint = floatArrayOf(1f, 1f, 1f)

    val roughness = Scalar(0.5f)
    val metallic = Scalar(0f)
    val reflectance = Scalar(0.05f)
    val clearcoat = Scalar(0f)
    val fresnelBoost = Scalar(0f)
    val opacity = Scalar(1f)
    val ambientOcclusion = Scalar(1f)

    val emissive = floatArrayOf(0f, 0f, 0f)
    val emissionStrength = Scalar(0f)

    /** When set, the label texture modulates the albedo (paper label). */
    val useLabelTexture = Flag(false)

    // ---------------------------------------------------------------- procedural surface

    val grooveMode = Flag(false)
    val grooveStrength = Scalar(0f)
    val grooveDepth = Scalar(TurntableSpec.GROOVE_DEPTH)
    val groovePitch = Scalar(TurntableSpec.GROOVE_PITCH)
    val grooveEccentricity = Scalar(TurntableSpec.GROOVE_ECCENTRICITY)
    val grooveModulation = Scalar(0.8f)

    /**
     * Hole radius and outer radius of the disc being shaded.
     *
     * Must match the annulus the record's top surface is actually built from
     * (`radialAnnulusTop` spans the spindle hole to the edge bevel), because the shader recovers a
     * physical radius from that mesh's normalised V coordinate. A mismatch offsets every groove.
     */
    val discRadialRange = floatArrayOf(
        TurntableSpec.SPINDLE_RADIUS + 0.0002f,
        TurntableSpec.RECORD_RADIUS - TurntableSpec.RECORD_EDGE_BEVEL_WIDTH,
    )

    /** Inner and outer radius of the grooved audio band. */
    val grooveRange = floatArrayOf(TurntableSpec.AUDIO_BAND_INNER, TurntableSpec.AUDIO_BAND_OUTER)

    val brushedMode = Flag(false)
    val brushedStrength = Scalar(0.2f)
    val brushedScale = Scalar(6f)

    /** `(scaleU, scaleV, offsetU, offsetV)` applied to the mesh UVs. */
    val uvTransform = floatArrayOf(1f, 1f, 0f, 0f)

    val isTransparent: Boolean get() = opacity.value < 0.995f

    /** Small mutable wrapper so materials stay object-identity stable across style changes. */
    class Scalar(@JvmField var value: Float)

    class Flag(@JvmField var value: Boolean)

    override fun toString(): String =
        "Material($name, rough=${roughness.value}, metallic=${metallic.value}, opacity=${opacity.value})"
}

/**
 * Material variants constructed once and driven by uniforms at draw time.
 *
 * Types are separated by *surface behaviour*, which is what the spec calls for: wood/lacquer,
 * brushed metal, black rubber, polished vinyl, paper label and brass all take different
 * roughness/metallic/clearcoat/fresnel combinations.
 */
object MaterialLibrary {

    /** Kinds of surface the scene draws. */
    enum class Kind {
        PLINTH_LACQUER,
        PLINTH_TRIM_BRASS,
        RUBBER_FOOT,
        PLATTER_CASTING,
        PLATTER_RIM_ALUMINIUM,
        STROBE_MARK,
        RUBBER_MAT,
        VINYL_RECORD,
        PAPER_LABEL,
        POLISHED_STEEL,
        PIVOT_HOUSING,
        BRASS_CONTROL,
        CONTROL_PLASTIC,
        INDICATOR_LAMP,
        GROUND_SURFACE,
    }

    /** Builds the full material set for a vinyl style. */
    fun buildSet(style: VinylStyle): Map<Kind, Material> {
        val materials = LinkedHashMap<Kind, Material>(Kind.entries.size)

        materials[Kind.PLINTH_LACQUER] = Material("plinth-lacquer").apply {
            // Dark walnut under satin lacquer. Deliberately not "near-black": a genuinely black
            // albedo cannot be lit at all, and on a phone screen it renders the whole deck as one
            // flat silhouette no matter what the lights do.
            ColorPacking.toLinearRgb(0xFF3A2416.toInt(), albedo)
            albedo[0] *= 1.45f
            albedo[1] *= 1.20f
            albedo[2] *= 0.92f
            roughness.value = 0.30f
            metallic.value = 0.0f
            reflectance.value = 0.07f
            clearcoat.value = 0.55f
            fresnelBoost.value = 0.12f
            ambientOcclusion.value = 0.95f
            // Very low frequency grain: the same anisotropic streak generator used for metal,
            // tuned so it reads as lacquered wood rather than brushed steel.
            brushedMode.value = true
            brushedStrength.value = 0.16f
            brushedScale.value = 5.5f
        }

        materials[Kind.PLINTH_TRIM_BRASS] = Material("plinth-trim-brass").apply {
            ColorPacking.toLinearRgb(0xFFB45309.toInt(), albedo, gain = 1.5f)
            roughness.value = 0.31f
            metallic.value = 1f
            reflectance.value = 0.35f
            brushedMode.value = true
            brushedStrength.value = 0.22f
            brushedScale.value = 42f
        }

        materials[Kind.RUBBER_FOOT] = Material("rubber-foot").apply {
            ColorPacking.toLinearRgb(0xFF121011.toInt(), albedo, gain = 1.2f)
            roughness.value = 0.86f
            metallic.value = 0f
            reflectance.value = 0.035f
            ambientOcclusion.value = 0.8f
        }

        materials[Kind.PLATTER_CASTING] = Material("platter-casting").apply {
            ColorPacking.toLinearRgb(0xFF23252A.toInt(), albedo, gain = 1.15f)
            roughness.value = 0.42f
            metallic.value = 0.72f
            reflectance.value = 0.22f
            brushedMode.value = true
            brushedStrength.value = 0.18f
            brushedScale.value = 60f
        }

        materials[Kind.PLATTER_RIM_ALUMINIUM] = Material("platter-rim-aluminium").apply {
            albedo[0] = 0.63f; albedo[1] = 0.645f; albedo[2] = 0.67f
            specularTint[0] = 0.98f; specularTint[1] = 0.99f; specularTint[2] = 1f
            roughness.value = 0.26f
            metallic.value = 1f
            reflectance.value = 0.42f
            brushedMode.value = true
            brushedStrength.value = 0.3f
            brushedScale.value = 120f
        }

        materials[Kind.STROBE_MARK] = Material("strobe-mark").apply {
            ColorPacking.toLinearRgb(0xFF0A0A0B.toInt(), albedo)
            roughness.value = 0.5f
            metallic.value = 0.2f
        }

        materials[Kind.RUBBER_MAT] = Material("rubber-mat").apply {
            ColorPacking.toLinearRgb(0xFF0D0C0C.toInt(), albedo, gain = 1.4f)
            roughness.value = 0.88f
            metallic.value = 0f
            reflectance.value = 0.03f
            ambientOcclusion.value = 0.75f
        }

        materials[Kind.VINYL_RECORD] = Material("vinyl-record").apply {
            applyVinylStyle(style)
        }

        materials[Kind.PAPER_LABEL] = Material("paper-label").apply {
            // The canvas bitmap supplies the colour; this is the paper substrate underneath.
            albedo[0] = 0.82f; albedo[1] = 0.80f; albedo[2] = 0.76f
            roughness.value = 0.72f
            metallic.value = 0f
            reflectance.value = 0.05f
            useLabelTexture.value = true
            ambientOcclusion.value = 0.9f
        }

        materials[Kind.POLISHED_STEEL] = Material("polished-steel").apply {
            albedo[0] = 0.74f; albedo[1] = 0.75f; albedo[2] = 0.78f
            roughness.value = 0.14f
            metallic.value = 1f
            reflectance.value = 0.45f
        }

        materials[Kind.PIVOT_HOUSING] = Material("pivot-housing").apply {
            ColorPacking.toLinearRgb(0xFF191A1D.toInt(), albedo, gain = 1.25f)
            roughness.value = 0.38f
            metallic.value = 0.8f
            reflectance.value = 0.25f
            brushedMode.value = true
            brushedStrength.value = 0.14f
            brushedScale.value = 90f
        }

        materials[Kind.BRASS_CONTROL] = Material("brass-control").apply {
            ColorPacking.toLinearRgb(0xFFB45309.toInt(), albedo, gain = 1.6f)
            roughness.value = 0.27f
            metallic.value = 1f
            reflectance.value = 0.4f
            brushedMode.value = true
            brushedStrength.value = 0.2f
            brushedScale.value = 80f
        }

        materials[Kind.CONTROL_PLASTIC] = Material("control-plastic").apply {
            ColorPacking.toLinearRgb(0xFF17161A.toInt(), albedo, gain = 1.15f)
            roughness.value = 0.40f
            metallic.value = 0.0f
            reflectance.value = 0.05f
            clearcoat.value = 0.25f
        }

        materials[Kind.INDICATOR_LAMP] = Material("indicator-lamp").apply {
            ColorPacking.toLinearRgb(style.palette.accent, albedo, gain = 1.2f)
            ColorPacking.toLinearRgb(style.palette.accent, emissive, gain = 1.35f)
            emissionStrength.value = 0f      // animated by the state machine
            roughness.value = 0.18f
            metallic.value = 0f
            reflectance.value = 0.06f
            clearcoat.value = 0.7f
        }

        materials[Kind.GROUND_SURFACE] = Material("ground-surface").apply {
            // Dark stone table matched to the backdrop's bottom stop, with a soft sheen so the
            // key light leaves a believable reflection.
            ColorPacking.toLinearRgb(0xFF33261D.toInt(), albedo, gain = 1.3f)
            roughness.value = 0.62f
            metallic.value = 0.08f
            reflectance.value = 0.09f
            ambientOcclusion.value = 1f
        }

        return materials
    }
}

/**
 * Applies everything that changes with [style] to the record material, in place.
 *
 * Kept as a top-level extension so the renderer can call it directly on the GL thread while a style
 * switch is drained: every field here is a plain float or flag, so no allocation happens and no
 * material object is rebuilt.
 */
fun Material.applyVinylStyle(style: VinylStyle) {
    // Gain is high on purpose: a record is a black dielectric, so its colour barely contributes --
    // what sells it is the specular response sweeping across the grooves. Lifting the albedo out of
    // pure black gives the groove term something to modulate, and the reflectance below gives the
    // highlight enough energy to read as vinyl rather than as a hole in the scene.
    ColorPacking.toLinearRgb(style.recordAlbedo, albedo, gain = 2.6f)
    // A touch of the label accent bleeds into the vinyl, as it does on tinted pressings.
    val accentBleed = 0.05f * style.accentStrength
    val accent = floatArrayOf(0f, 0f, 0f)
    ColorPacking.toLinearRgb(style.palette.accent, accent)
    albedo[0] += accent[0] * accentBleed
    albedo[1] += accent[1] * accentBleed
    albedo[2] += accent[2] * accentBleed

    specularTint[0] = 1f
    specularTint[1] = 1f
    specularTint[2] = 1f
    roughness.value = style.recordRoughness
    metallic.value = 0.0f
    reflectance.value = 0.10f
    // Polished pressings carry a clearcoat: that tight second highlight is what makes a
    // black record read as glossy rather than merely dark.
    clearcoat.value = 0.62f
    fresnelBoost.value = 0.22f
    opacity.value = style.recordOpacity
    ambientOcclusion.value = 0.92f
    grooveMode.value = true
    grooveStrength.value = style.grooveStrength
    grooveDepth.value = TurntableSpec.GROOVE_DEPTH
    groovePitch.value = TurntableSpec.GROOVE_PITCH
    grooveEccentricity.value = TurntableSpec.GROOVE_ECCENTRICITY
    grooveModulation.value = style.grooveModulation
    paintGrooveAccent(style)
}

/** Window/lead-out bands pick up the style's accent, as they do on real pressings. */
private fun Material.paintGrooveAccent(style: VinylStyle) {
    ColorPacking.toLinearRgb(style.palette.accent, emissive, gain = 0.02f * style.accentStrength)
    emissionStrength.value = if (style.accentStrength > 0.5f) 0.35f else 0.2f
}
