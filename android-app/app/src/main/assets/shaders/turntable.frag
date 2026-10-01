#version 300 es
// Vynyl Record — turntable fragment shader.
//
// This shader exists for one reason: the grooves. A record drawn as a flat black disc with a picture on it
// looks like a coaster, and a record with a painted-on texture looks like a sticker. What makes vinyl read
// as vinyl is that the light gathers into concentric bands that shift with the eye, because each groove is
// a shallow V whose walls catch the key light at different angles.
//
// So the surface normal is perturbed radially by a function of the radius: `fract(radius * pitch)` gives the
// rings, a little noise in the angle gives the wobble a real pressing has, and the perturbed normal then
// goes through the same lighting as everything else. The result is resolution-independent — it does not
// shimmer when the disc gets smaller or the camera gets closer — and it costs one `sin`.
//
// Everything else in the scene (plinth lacquer, machined aluminium, felt mat, brass) uses the same shading
// with different uniforms, which is why the deck holds together as one object rather than as a pile of
// materials.

precision highp float;

const float PI = 3.14159265359;

in vec3 vWorldPosition;
in vec3 vNormal;
in vec2 vUv;
in vec3 vLocalPosition;

out vec4 fragColor;

uniform vec3 uCameraPosition;

// ---- material
uniform vec3 uBaseColor;
uniform vec3 uSpecularColor;
uniform float uRoughness;
uniform float uMetallic;
uniform float uAlpha;
uniform float uTransmission;
uniform float uGrooveAmount;
uniform float uSheen;
uniform float uUseLabel;

// ---- lights
uniform vec3 uKeyLightDirection;
uniform vec3 uKeyLightColor;
uniform vec3 uFillLightColor;
uniform float uAmbient;
uniform vec3 uLampColor;
uniform float uLampIntensity;
uniform vec3 uLampPosition;

uniform float uTime;
uniform sampler2D uLabelTexture;

// A cheap hash and value noise. Written out rather than sampled from a texture so the pressing's grain is
// the same at every resolution and costs one texture unit less than it would.
float hash(vec2 p) {
    p = fract(p * vec2(233.34, 851.73));
    p += dot(p, p + 23.45);
    return fract(p.x * p.y);
}

float valueNoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    float a = hash(i);
    float b = hash(i + vec2(1.0, 0.0));
    float c = hash(i + vec2(0.0, 1.0));
    float d = hash(i + vec2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

// The groove profile: a sawtooth in the radius, wobbled by noise. The noise is what keeps the rings from
// looking like a moiré pattern when the disc is far away.
float grooveProfile(float radius, float angle) {
    float wobble = valueNoise(vec2(angle * 9.0, radius * 60.0)) * 0.35;
    float phase = radius * 260.0 + wobble;
    return fract(phase) - 0.5;
}

vec3 perturbForGrooves(vec3 normal, vec3 localPosition, float radius) {
    if (radius < 0.0001) return normal;
    float angle = atan(localPosition.z, localPosition.x);
    float slope = grooveProfile(radius, angle);
    // The perturbation is radial: the groove wall tips inwards or outwards, never along the circumference.
    vec3 radial = normalize(vec3(localPosition.x, 0.0, localPosition.z));
    return normalize(normal + radial * slope * uGrooveAmount);
}

float distributionGGX(vec3 n, vec3 h, float roughness) {
    float a = roughness * roughness;
    float a2 = a * a;
    float nDotH = max(dot(n, h), 0.0);
    float d = nDotH * nDotH * (a2 - 1.0) + 1.0;
    return a2 / max(PI * d * d, 1e-4);
}

float geometrySchlick(float nDotV, float roughness) {
    float r = roughness + 1.0;
    float k = (r * r) / 8.0;
    return nDotV / (nDotV * (1.0 - k) + k);
}

vec3 fresnelSchlick(float cosTheta, vec3 f0) {
    return f0 + (1.0 - f0) * pow(clamp(1.0 - cosTheta, 0.0, 1.0), 5.0);
}

vec3 shade(vec3 normal, vec3 viewDirection, vec3 lightDirection, vec3 lightColor, vec3 f0, float roughness) {
    vec3 l = normalize(lightDirection);
    vec3 h = normalize(viewDirection + l);
    float nDotL = max(dot(normal, l), 0.0);
    if (nDotL <= 0.0) return vec3(0.0);
    float nDotV = max(dot(normal, viewDirection), 0.0001);
    float d = distributionGGX(normal, h, roughness);
    float g = geometrySchlick(nDotV, roughness) * geometrySchlick(nDotL, roughness);
    vec3 f = fresnelSchlick(max(dot(h, viewDirection), 0.0), f0);
    vec3 specular = (d * g * f) / max(4.0 * nDotV * nDotL, 0.001);
    vec3 diffuse = (vec3(1.0) - f) * (1.0 - uMetallic);
    return (diffuse * uBaseColor / PI + specular) * lightColor * nDotL;
}

void main() {
    vec3 normal = normalize(vNormal);
    vec3 baseColor = uBaseColor;
    float roughness = uRoughness;
    float alpha = uAlpha;

    float radius = length(vLocalPosition.xz);
    if (uGrooveAmount > 0.0001) {
        normal = perturbForGrooves(normal, vLocalPosition, radius);
    }

    // The label is a locally drawn bitmap: the record's title, dedication and occasion, typeset exactly as
    // they are on the sleeve. Sampled only where the label is, so the disc's own shading is untouched.
    if (uUseLabel > 0.5) {
        vec4 label = texture(uLabelTexture, vUv);
        baseColor = mix(baseColor, label.rgb, label.a);
        // Paper is rougher than vinyl: the label should not mirror the lamp back at the viewer.
        roughness = mix(roughness, 0.62, label.a);
    } else if (uSheen > 0.001) {
        // The sheen pass: a translucent film over the grooves whose opacity follows the camera, which is what
        // makes the light run around the record as the deck is orbited.
        float grazing = pow(1.0 - abs(dot(normal, normalize(uCameraPosition - vWorldPosition))), 2.0);
        alpha = clamp(uAlpha * (0.35 + 1.4 * grazing) * (1.0 + uSheen), 0.0, 0.75);
    }

    // Dust and pressing grain, in object space so it does not swim when the camera moves.
    float grain = valueNoise(vLocalPosition.xz * 380.0) * 0.5 + valueNoise(vLocalPosition.xz * 90.0) * 0.5;
    roughness = clamp(roughness + (grain - 0.5) * 0.06, 0.03, 1.0);

    vec3 viewDirection = normalize(uCameraPosition - vWorldPosition);
    vec3 f0 = mix(vec3(0.04), uSpecularColor, uMetallic);

    vec3 color = shade(normal, viewDirection, uKeyLightDirection, uKeyLightColor, f0, roughness);
    color += shade(normal, viewDirection, -uKeyLightDirection, uFillLightColor, f0, roughness);

    // The lamp: a warm point light over the platter whose intensity the deck's state drives. Its falloff is
    // inverse-square, softened so the plinth does not go completely black at the far corner.
    if (uLampIntensity > 0.001) {
        vec3 toLamp = uLampPosition - vWorldPosition;
        float distanceToLamp = length(toLamp);
        vec3 lampDirection = toLamp / max(distanceToLamp, 0.0001);
        float attenuation = uLampIntensity / (1.0 + distanceToLamp * distanceToLamp * 0.6);
        color += shade(normal, viewDirection, lampDirection, uLampColor * attenuation, f0, roughness);
    }

    color += baseColor * uAmbient;

    // A rim term so edges separate from the background: without it the black record against a black room has
    // no silhouette at all.
    float rim = pow(1.0 - max(dot(normal, viewDirection), 0.0), 3.0);
    color += uLampColor * rim * (0.06 + 0.30 * uTransmission);

    // Smoked vinyl: translucent at a distance, denser face-on. Alpha is a function of the viewing angle
    // rather than a constant, which is what makes it read as a thick material rather than as a faded one.
    if (uTransmission > 0.01) {
        float facing = abs(dot(normal, viewDirection));
        float smoked = mix(0.52, 0.94, pow(1.0 - facing, 1.4));
        alpha = clamp(alpha * mix(1.0, smoked, uTransmission), 0.30, 1.0);
    }

    // Filmic roll-off: highlights ease into white rather than clipping, which is what stops the sheen from
    // looking like a sticker.
    color = color / (color + vec3(1.0));
    color = pow(color, vec3(1.0 / 2.2));

    fragColor = vec4(color, clamp(alpha, 0.0, 1.0));
}
