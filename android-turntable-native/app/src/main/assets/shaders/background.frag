#version 300 es
/*
 * Procedural studio backdrop — the "locally generated environment".
 *
 * Renders a warm cream-to-dusty-rose cyclorama with a soft radial key bloom, a subtle
 * floor plane with a gentle reflection of the amber key, restrained film grain and a
 * cinematic vignette. Everything is analytic: no HDRI, no environment map, no downloads.
 */

#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

in vec2 vUv;

uniform vec2 uResolution;
uniform float uTime;
uniform vec3 uBackdropTop;       // warm cream
uniform vec3 uBackdropMid;       // dusty rose
uniform vec3 uBackdropBottom;    // deep stone shadow
uniform vec3 uGlowColor;         // accent bloom tinted by the vinyl style
uniform vec2 uGlowPosition;      // normalised screen position of the bloom
uniform float uGlowStrength;
uniform float uGrainStrength;
uniform float uVignetteStrength;
uniform float uHorizon;          // normalised screen height of the floor line
uniform float uFloorFade;        // how quickly the floor darkens away from the camera
uniform int uGrainEnabled;
uniform int uFloorEnabled;
uniform int uAnimate;            // 0 disables the (static) noise drift

out vec4 outColor;

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

void main() {
    vec2 uv = vUv;
    float aspect = uResolution.x / max(uResolution.y, 1.0);
    vec2 centered = vec2((uv.x - 0.5) * aspect, uv.y - 0.5);

    // ---------------------------------------------------------------- cyclorama gradient
    float vertical = smoothstep(0.0, 1.0, uv.y);
    vec3 color = mix(uBackdropBottom, uBackdropMid, smoothstep(0.0, 0.55, vertical));
    color = mix(color, uBackdropTop, smoothstep(0.42, 1.0, vertical));

    // Gentle horizontal falloff keeps the centre of frame the brightest.
    float horizontal = 1.0 - smoothstep(0.25, 1.15, abs(centered.x));
    color *= mix(0.82, 1.06, horizontal);

    // ---------------------------------------------------------------- floor plane
    if (uFloorEnabled == 1) {
        float horizon = uHorizon;
        float floorMask = 1.0 - smoothstep(horizon - 0.02, horizon + 0.006, uv.y);
        if (floorMask > 0.001) {
            float depth = clamp((horizon - uv.y) / max(horizon, 0.001), 0.0, 1.0);
            vec3 floorColor = mix(uBackdropBottom * 1.35, uBackdropBottom * 0.55, pow(depth, 0.7));
            // Amber bounce from the key light smeared across the surface.
            float bounce = exp(-depth * 5.5) * 0.35;
            floorColor += uGlowColor * bounce * 0.5;
            // Coarse perspective grid of soft noise instead of a tiled texture.
            float grain = valueNoise(vec2(centered.x * 3.0, depth * 26.0)) * 0.045;
            floorColor += grain;
            floorColor *= mix(1.0, 1.0 - uFloorFade, depth);
            color = mix(color, floorColor, floorMask);
        }
    }

    // ---------------------------------------------------------------- radial soft light
    vec2 toGlow = (centered - uGlowPosition);
    float glowDistance = length(toGlow * vec2(1.0, 1.25));
    float glow = exp(-glowDistance * glowDistance * 4.2);
    color += uGlowColor * glow * uGlowStrength;

    // Wide, dim halo so the key light feels like it is lighting the room.
    color += uGlowColor * exp(-glowDistance * 1.15) * uGlowStrength * 0.22;

    // ---------------------------------------------------------------- film grain
    if (uGrainEnabled == 1) {
        vec2 grainUv = uv * uResolution / max(uResolution.y, 1.0) * 1.35;
        float drift = uAnimate == 1 ? uTime * 0.35 : 0.0;
        float lum = dot(color, vec3(0.299, 0.587, 0.114));
        float grain = valueNoise(grainUv * 220.0 + vec2(drift, drift * 0.7)) - 0.5;
        float weight = uGrainStrength * mix(0.55, 1.0, 1.0 - lum);
        color += grain * weight;
    }

    // ---------------------------------------------------------------- vignette
    float radius = length(centered * vec2(0.92, 1.0));
    float vignette = 1.0 - uVignetteStrength * smoothstep(0.35, 1.25, radius);
    color *= vignette;

    color = max(color, vec3(0.0));
    outColor = vec4(color, 1.0);
}
