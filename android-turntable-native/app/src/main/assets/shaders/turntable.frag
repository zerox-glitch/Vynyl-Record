#version 300 es
/*
 * Vynyl Turntable — main fragment stage.
 *
 * One locally authored BRDF-ish shading path is shared by every material in the scene.
 * Materials differ only through uniforms (see graphics/material/Material.kt):
 *   albedo / specular tint / roughness / metallic / clearcoat / fresnel / opacity
 * plus two procedural surface generators that only run for the materials that request them:
 *   - vinyl groove relief (analytic normal perturbation from a radial groove profile)
 *   - brushed metal streaks (anisotropy folded into roughness)
 *
 * Lighting: warm key directional light, amber point fill light, cool rim light and a
 * hemispheric ambient matched to the procedural studio backdrop. Contact shadows are
 * analytic (occluder projected along the key light) which keeps the renderer self
 * contained: no shadow maps, no external textures, no allocations at draw time.
 *
 * Coordinate notes: the record disc is generated with
 *   uv.x = angle / 2PI  (object space, so the pattern rotates with the platter)
 *   uv.y = normalised radius across the audio band (0 = inner, 1 = outer)
 * Tangent is circumferential and bitangent = cross(N, T) therefore points outward.
 */

#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

const float PI = 3.14159265359;
const float TWO_PI = 6.28318530718;

in vec3 vWorldPos;
in vec3 vNormal;
in vec2 vTexCoord;
in vec3 vTangent;

// ------------------------------------------------------------------ material

uniform vec3 uAlbedo;
uniform vec3 uSpecularTint;
uniform float uRoughness;
uniform float uMetallic;
uniform float uReflectance;
uniform float uClearcoat;
uniform float uFresnelBoost;
uniform float uOpacity;
uniform vec3 uEmissive;
uniform float uEmissionStrength;
uniform float uAmbientOcclusion;
uniform int uUseLabelTexture;
uniform sampler2D uLabelTexture;

// ------------------------------------------------------------------ procedural surface

uniform int uGrooveMode;            // 0 = none, 1 = vinyl grooves
uniform vec2 uDiscRadialRange;      // hole radius / outer radius of the disc (metres)
uniform vec2 uGrooveRange;          // inner / outer groove radius in metres
uniform float uGroovePitch;         // metres between groove centres (6.7e-5 for 33 1/3)
uniform float uGrooveDepth;         // metres of relief (artistically exaggerated)
uniform float uGrooveStrength;
uniform float uGrooveEccentricity;  // off-centre pressing wobble, fraction of radius
uniform float uGrooveModulation;    // lateral signal wobble amount
uniform int uBrushedMode;           // 0 = none, 1 = directional streaks
uniform float uBrushedStrength;
uniform float uBrushedScale;
uniform float uDetailLevel;         // 0..1 quality scaling for procedural work

// ------------------------------------------------------------------ scene

uniform vec3 uCameraPosition;
uniform vec3 uKeyDirection;         // surface -> light
uniform vec3 uKeyColor;
uniform vec3 uFillPosition;
uniform vec3 uFillColor;
uniform vec3 uRimDirection;         // surface -> light
uniform vec3 uRimColor;
uniform vec3 uAmbientFloor;
uniform vec3 uAmbientSky;

uniform vec4 uDiscShadow0;          // xy = centre xz, z = radius, w = plane height
uniform vec4 uDiscShadow1;
uniform vec4 uRectShadow;           // xy = centre xz, z = half extent x, w = half extent z
uniform float uShadowCeiling;       // fragments above this height skip contact shadows
uniform float uShadowStrength;
uniform float uShadowBias;

out vec4 outColor;

// ------------------------------------------------------------------ helpers

float hash21(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float valueNoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    float a = hash21(i);
    float b = hash21(i + vec2(1.0, 0.0));
    float c = hash21(i + vec2(0.0, 1.0));
    float d = hash21(i + vec2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

float fbm(vec2 p) {
    float sum = 0.0;
    float amplitude = 0.5;
    for (int i = 0; i < 3; i++) {
        sum += amplitude * valueNoise(p);
        p *= 2.03;
        amplitude *= 0.5;
    }
    return sum;
}

/*
 * Radial groove relief.
 *
 * `radial` is the normalised position across the audio band (0 = inner, 1 = outer),
 * `radius` the physical radius in metres and `angle` the object-space angle in radians.
 *
 * Real 33 1/3 rpm microgroove pitch is ~6.7e-5 m with a ~2.5e-5 m deep trough. At a
 * sensible viewing distance that is far below one fragment, so the trough depth is
 * exaggerated (uGrooveDepth) to keep the radial light response readable; the fwidth
 * based LOD in main() fades the term out wherever it would alias.
 *
 * Returns a relief height in [-1, 0]: 0 on the land (ridge), -1 at the trough bottom.
 */
const float BAND_GROOVES = 33.0;   // grooves per visible band: 33 * 6.7e-5 m = 2.2 mm
const float BAND_DEPTH = 0.45;     // banding amplitude as a fraction of the fine relief

float grooveHeight(float radial, float radius, float angle, float bandWeight) {
    // Off-centre pressing wobble keeps the pattern non-axisymmetric, so the relief (and
    // therefore the moving highlight) visibly turns with the platter.
    float wobble = cos(angle) * 0.62 + sin(angle * 2.0 + 1.1) * 0.28;
    float effectiveRadius = radius * (1.0 + uGrooveEccentricity * wobble);

    // Lateral signal modulation, two incommensurate carriers so the wobble never repeats.
    float signal = uGrooveModulation * (
        0.55 * sin(radial * 511.0 + radius * 1180.0) +
        0.30 * sin(radial * 977.0 - radius * 733.0 + 1.7) +
        0.15 * sin(radial * 1811.0 + radius * 401.0 + 4.1));

    float pitch = max(uGroovePitch, 1.0e-6);
    float grooveIndex = effectiveRadius / pitch;
    float phase = fract(grooveIndex);

    float centre = 0.5 + 0.16 * signal;
    float halfWidth = 0.225;
    float offset = (phase - centre) / halfWidth;

    float fine = 0.0;
    if (abs(offset) < 1.0) {
        float profile = cos(offset * PI * 0.5);
        fine = -profile * profile;   // 0 on the land, -1 at the trough bottom
    }

    // Coarse banding: BAND_GROOVES grooves summed into one resolvable ridge/trough pair.
    //
    // This is the term the eye actually reads at normal viewing distance. A single microgroove is
    // 67 um across and one fragment of a phone screen covers five of them, so the fine profile can
    // never be shown at a normal framing -- but the way light and shadow clump across a couple of
    // dozen grooves is exactly what makes a pressing look like a pressing in a photograph.
    float bandPitch = pitch * BAND_GROOVES;
    float bandPhase = effectiveRadius / bandPitch;
    // Amplitude drifts slowly across the side so the banding never reads as a printed target.
    float bandAmplitude = BAND_DEPTH * (0.72 + 0.28 * sin(effectiveRadius * 96.0));
    float band = (-0.5 + 0.5 * cos(bandPhase * TWO_PI)) * bandAmplitude;

    return mix(fine, band, clamp(bandWeight, 0.0, 1.0));
}

// Analytic soft shadow of a horizontal disc, projected onto this fragment along -keyLight.
float discShadow(vec3 position, vec3 lightTravel, vec2 centre, float radius, float planeHeight, float strength) {
    if (lightTravel.y >= -0.05 || position.y <= planeHeight + uShadowBias) {
        return 1.0;
    }
    float travel = (position.y - planeHeight) / -lightTravel.y;
    vec2 projected = position.xz + lightTravel.xz * travel;
    float distance = length(projected - centre) - radius;
    float penumbra = 0.028 + travel * 0.55;
    return mix(1.0, smoothstep(0.0, penumbra, distance), strength);
}

// Analytic soft shadow of a horizontal rounded rectangle (plinth onto the surface).
float rectShadow(vec3 position, vec3 lightTravel, vec2 centre, vec2 halfExtent, float planeHeight, float strength) {
    if (lightTravel.y >= -0.05 || position.y <= planeHeight + uShadowBias) {
        return 1.0;
    }
    float travel = (position.y - planeHeight) / -lightTravel.y;
    vec2 projected = position.xz + lightTravel.xz * travel;
    vec2 q = abs(projected - centre) - halfExtent;
    float distance = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0);
    float penumbra = 0.02 + travel * 0.8;
    return mix(1.0, smoothstep(0.0, penumbra, distance), strength);
}

vec3 fresnelSchlick(float cosTheta, vec3 f0, float roughness) {
    float f90 = clamp(dot(f0, vec3(1.0)) * 25.0, 0.0, 1.0) * (1.0 - roughness) + roughness;
    return f0 + (f90 - f0) * pow(clamp(1.0 - cosTheta, 0.0, 1.0), 5.0);
}

// Normalised Blinn-Phong specular: cheap, stable, and clamped so one light cannot blow out.
vec3 specularBrdf(vec3 normal, vec3 view, vec3 lightDir, vec3 f0, float roughness) {
    vec3 halfVector = normalize(view + lightDir);
    float specPower = exp2(11.0 * (1.0 - roughness) + 1.5);
    float distribution = pow(max(dot(normal, halfVector), 0.0), specPower) * (specPower + 8.0) / 25.13;
    float fresnel = dot(fresnelSchlick(max(dot(halfVector, view), 0.0), f0, roughness), vec3(0.3333));
    return vec3(distribution * fresnel);
}

void main() {
    vec3 normal = normalize(vNormal);
    if (!gl_FrontFacing) {
        normal = -normal;
    }

    vec3 albedo = uAlbedo;
    float roughness = clamp(uRoughness, 0.02, 1.0);
    float alpha = clamp(uOpacity, 0.0, 1.0);

    if (uUseLabelTexture == 1) {
        vec4 labelSample = texture(uLabelTexture, vTexCoord);
        albedo = mix(albedo, labelSample.rgb, 0.97);
        roughness = mix(roughness, 0.62, labelSample.a);
    }

    vec3 viewDir = normalize(uCameraPosition - vWorldPos);
    vec3 tangent = normalize(vTangent - normal * dot(normal, vTangent));
    vec3 bitangent = cross(normal, tangent);   // points outward (increasing radius)
    float detail = clamp(uDetailLevel, 0.0, 1.0);

    // ------------------------------------------------------------ procedural relief

    float grooveOcclusion = 1.0;
    float grooveSpecularModulation = 1.0;

    if (uGrooveMode == 1) {
        // The disc is generated with v = normalised radius over the whole pressing, so the
        // physical radius is recovered here and re-normalised into the audio band. Land
        // outside the band (label area, lead-out) simply gets no relief.
        float radial01 = clamp(vTexCoord.y, 0.0, 1.0);
        float angle = vTexCoord.x * TWO_PI;
        float radius = mix(uDiscRadialRange.x, uDiscRadialRange.y, radial01);
        float band = max(uGrooveRange.y - uGrooveRange.x, 1.0e-4);
        float bandPosition = (radius - uGrooveRange.x) / band;

        if (bandPosition >= 0.0 && bandPosition <= 1.0) {
            // Level of detail, in two terms:
            //  * fineFade keeps the 67 um relief only where a fragment is narrower than about one
            //    groove (a fragment is 0.35 mm across at the default framing -- five grooves -- so
            //    this only appears when the camera is pushed right in);
            //  * bandFade keeps the 2.2 mm banding until *that* becomes sub-pixel too.
            // Between them the disc always shows radial structure: individual grooves up close,
            // concentric sheen bands at normal viewing distance. (The old single window faded out
            // at about one groove per fragment, which on a phone happens at *every* camera
            // distance -- so the disc rendered as a plain black circle.)
            float groovesPerFragment = fwidth(radius) / max(uGroovePitch, 1.0e-6);
            float fineFade = 1.0 - smoothstep(0.75, 1.75, groovesPerFragment);
            float bandsPerFragment = groovesPerFragment / BAND_GROOVES;
            float bandFade = 1.0 - smoothstep(0.55, 1.70, bandsPerFragment);
            float bandWeight = bandFade * (1.0 - fineFade);
            float bandDetail = mix(0.55, 1.0, detail);
            float relief = uGrooveStrength * max(fineFade, bandDetail * bandWeight);

            if (relief > 0.002) {
                const float radialStep = 0.00035;   // metres, well inside one groove pitch
                float h0 = grooveHeight(bandPosition, radius, angle, bandWeight);
                float hp = grooveHeight((radius + radialStep - uGrooveRange.x) / band, radius + radialStep, angle, bandWeight);
                float hm = grooveHeight((radius - radialStep - uGrooveRange.x) / band, radius - radialStep, angle, bandWeight);

                // d(height)/d(radius): the grooves are concentric, so the whole relief
                // gradient lives along the radial (bitangent) direction.
                float slope = (hp - hm) / (2.0 * radialStep);
                float metricGradient = slope * uGrooveDepth;

                // Analytic bump: the surface leans against the height gradient.
                vec3 perturbed = normalize(normal - bitangent * metricGradient);
                normal = normalize(mix(normal, perturbed, relief));

                // Troughs sit in shadow: lands read brighter than the groove walls.
                grooveOcclusion = mix(1.0, 0.58 + 0.42 * (1.0 + h0), relief);
            }

            // Grooves are strongly anisotropic, so the specular response is swept by the
            // groove direction. Locked to object space, this is what makes the highlight
            // visibly travel around the disc as the platter turns.
            float sweep = 1.0 + 0.06 * cos(angle * 2.0 + radial01 * 61.0) * relief;
            // The radial sheen is the concentric ring pattern a pressing shows under a studio
            // light; without it the disc reads as a flat black mirror.
            float sheen = 1.0 + 0.13 * cos(radial01 * 210.0 + 0.6) * relief;
            grooveSpecularModulation = mix(1.0, sweep * sheen, max(detail, 0.5));
        }
    }

    if (uBrushedMode == 1) {
        vec2 anisotropicUv = vec2(dot(vWorldPos, tangent) * uBrushedScale,
                                  dot(vWorldPos, bitangent) * uBrushedScale * 0.07);
        float streaks = fbm(anisotropicUv);
        roughness = clamp(roughness + (streaks - 0.5) * uBrushedStrength * detail, 0.03, 1.0);
    }

    // ------------------------------------------------------------ soft contact shadows

    vec3 lightTravel = -uKeyDirection;
    float visibility = 1.0;
    if (vWorldPos.y < uShadowCeiling) {
        visibility *= discShadow(vWorldPos, lightTravel, uDiscShadow0.xy, uDiscShadow0.z, uDiscShadow0.w, uShadowStrength);
        visibility *= discShadow(vWorldPos, lightTravel, uDiscShadow1.xy, uDiscShadow1.z, uDiscShadow1.w, uShadowStrength * 0.7);
        visibility *= rectShadow(vWorldPos, lightTravel, uRectShadow.xy, uRectShadow.zw, 0.0, uShadowStrength);
    }
    visibility = max(visibility, 0.06);
    float occlusion = clamp(uAmbientOcclusion * visibility * grooveOcclusion, 0.0, 1.0);

    // ------------------------------------------------------------ lighting

    vec3 f0 = mix(vec3(uReflectance), albedo * uSpecularTint, uMetallic);
    vec3 diffuseColor = albedo * (1.0 - uMetallic) * (1.0 - uClearcoat * 0.35);

    // Warm key directional light.
    vec3 keyDiffuse = diffuseColor * uKeyColor * max(dot(normal, uKeyDirection), 0.0);
    vec3 keySpecular = specularBrdf(normal, viewDir, uKeyDirection, f0, roughness)
                     * uKeyColor * grooveSpecularModulation;

    // Amber point fill with inverse-square falloff.
    vec3 toFill = uFillPosition - vWorldPos;
    float fillDistance = length(toFill);
    vec3 fillDir = toFill / max(fillDistance, 1.0e-4);
    float fillFalloff = 1.0 / (1.0 + fillDistance * fillDistance * 3.2);
    vec3 fillRadiance = uFillColor * fillFalloff;
    vec3 fillDiffuse = diffuseColor * fillRadiance * max(dot(normal, fillDir), 0.0);
    vec3 fillSpecular = specularBrdf(normal, viewDir, fillDir, f0, roughness) * fillRadiance;

    // Cool rim light, weighted by grazing angle so it traces silhouettes.
    float rimTerm = pow(1.0 - clamp(dot(normal, viewDir), 0.0, 1.0), 3.0);
    vec3 rimDiffuse = diffuseColor * uRimColor * max(dot(normal, uRimDirection), 0.0) * 0.35;
    vec3 rimSpecular = fresnelSchlick(clamp(dot(normal, viewDir), 0.0, 1.0), f0, roughness)
                     * uRimColor * rimTerm * (0.85 + uFresnelBoost);

    // Hemispheric ambient matched to the procedural backdrop.
    vec3 ambient = mix(uAmbientFloor, uAmbientSky, clamp(normal.y * 0.5 + 0.5, 0.0, 1.0));
    vec3 ambientDiffuse = diffuseColor * ambient * occlusion * (1.0 + uFresnelBoost * 0.2);

    // Camera-facing wrap fill, standing in for the softbox the viewer never sees. A single
    // near-overhead key light puts everything on the top faces and leaves the sides of the plinth
    // as a black silhouette; this term keeps every surface the camera can see lit from somewhere.
    float wrapFacing = max(dot(normal, viewDir), 0.0);
    vec3 viewFill = diffuseColor * mix(uAmbientSky, uKeyColor, 0.22) * (0.05 + 0.50 * wrapFacing);

    vec3 color = keyDiffuse * visibility + keySpecular * mix(1.0, visibility, 0.65);
    color += fillDiffuse * visibility + fillSpecular * visibility;
    color += rimDiffuse * occlusion + rimSpecular * occlusion;
    color += ambientDiffuse;
    color += viewFill * visibility;

    // Clearcoat: a tight additive second lobe over the base material.
    if (uClearcoat > 0.001) {
        vec3 coat = specularBrdf(normal, viewDir, uKeyDirection, vec3(0.06), 0.08) * uClearcoat * 1.6;
        color += coat * mix(uKeyColor, uRimColor, 0.25) * visibility;
    }

    // Emissive response for indicator lamps and smoked-vinyl glow.
    color += uEmissive * uEmissionStrength;

    color = max(color, vec3(0.0));
    color = pow(color, vec3(1.0 / 2.2));   // linear -> sRGB

    outColor = vec4(color * alpha, alpha);  // premultiplied, blended over the backdrop
}
