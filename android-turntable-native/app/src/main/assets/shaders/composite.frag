#version 300 es
/*
 * Final composite: premultiplied scene over the procedural backdrop, then a light
 * FXAA-style edge-directed resolve, restrained grain and the vignette that binds the
 * frame together. Runs once per displayed frame on a full-screen triangle.
 */

#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

in vec2 vUv;

uniform sampler2D uSceneTexture;
uniform sampler2D uBackgroundTexture;
uniform vec2 uResolution;
uniform vec2 uSceneTexel;      // 1 / scene render target size
uniform float uFxaaStrength;   // 0 disables the edge resolve
uniform float uExposure;
uniform float uGrainStrength;
uniform float uVignetteStrength;
uniform int uGrainEnabled;

out vec4 outColor;

float luminance(vec3 c) {
    return dot(c, vec3(0.299, 0.587, 0.114));
}

float hash21(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

void main() {
    vec2 uv = vUv;
    vec4 scene = texture(uSceneTexture, uv);

    // Edge-directed resolve (FXAA-lite): measure the local luma gradient, pick the
    // dominant edge direction and blend two taps along it. Cheap, and enough to soften
    // silhouette stair-stepping when hardware MSAA is unavailable or too expensive.
    if (uFxaaStrength > 0.001) {
        float lumaCenter = luminance(scene.rgb);
        float lumaNorth = luminance(texture(uSceneTexture, uv + vec2(0.0, uSceneTexel.y)).rgb);
        float lumaSouth = luminance(texture(uSceneTexture, uv - vec2(0.0, uSceneTexel.y)).rgb);
        float lumaWest = luminance(texture(uSceneTexture, uv - vec2(uSceneTexel.x, 0.0)).rgb);
        float lumaEast = luminance(texture(uSceneTexture, uv + vec2(uSceneTexel.x, 0.0)).rgb);

        float lumaMin = min(lumaCenter, min(min(lumaNorth, lumaSouth), min(lumaWest, lumaEast)));
        float lumaMax = max(lumaCenter, max(max(lumaNorth, lumaSouth), max(lumaWest, lumaEast)));
        float contrast = lumaMax - lumaMin;

        if (contrast > 0.06) {
            vec2 direction = vec2(-((lumaNorth + lumaSouth) - 2.0 * lumaCenter),
                                   ((lumaWest + lumaEast) - 2.0 * lumaCenter));
            float reduce = max((lumaNorth + lumaSouth + lumaWest + lumaEast) * 0.25 * 0.5, 1.0 / 128.0);
            float inverse = 1.0 / (min(abs(direction.x), abs(direction.y)) + reduce);
            direction = clamp(direction * inverse, vec2(-4.0), vec2(4.0)) * uSceneTexel;
            vec4 alongPositive = texture(uSceneTexture, uv + direction * 0.5);
            vec4 alongNegative = texture(uSceneTexture, uv - direction * 0.5);
            float blend = clamp(contrast * 2.6, 0.0, 1.0) * uFxaaStrength;
            scene = mix(scene, (alongPositive + alongNegative) * 0.5, blend * 0.65);
        }
    }

    // Explicit exposure trim before compositing.
    scene.rgb *= uExposure;

    vec3 background = texture(uBackgroundTexture, uv).rgb;

    // Premultiplied over-composite, then restore the backdrop alpha for the framebuffer.
    vec3 color = scene.rgb + background * (1.0 - clamp(scene.a, 0.0, 1.0));

    if (uGrainEnabled == 1) {
        float grain = hash21(uv * uResolution) - 0.5;
        color += grain * uGrainStrength * mix(0.5, 1.0, 1.0 - luminance(color));
    }

    float radius = length((uv - 0.5) * vec2(uResolution.x / max(uResolution.y, 1.0), 1.0) * 1.05);
    color *= 1.0 - uVignetteStrength * smoothstep(0.32, 1.18, radius);

    outColor = vec4(max(color, vec3(0.0)), 1.0);
}
