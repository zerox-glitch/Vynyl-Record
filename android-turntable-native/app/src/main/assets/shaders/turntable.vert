#version 300 es
/*
 * Vynyl Turntable — main vertex stage.
 *
 * Vertex layout (interleaved, 11 floats / 44 bytes):
 *   location 0 : vec3 position  (model space, metres)
 *   location 1 : vec3 normal
 *   location 2 : vec2 texCoord  (for the record disc: x = angle/2PI, y = groove-normalised radius)
 *   location 3 : vec3 tangent   (circumferential on discs, lengthwise on brushed metal)
 *
 * The grooves are synthesised in the fragment stage from the interpolated UV, so the
 * vertex load is intentionally minimal: one matrix multiply plus attribute forwarding.
 */

layout(location = 0) in vec3 aPosition;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec2 aTexCoord;
layout(location = 3) in vec3 aTangent;

uniform mat4 uModelMatrix;
uniform mat4 uViewProjectionMatrix;
uniform mat3 uNormalMatrix;
uniform vec4 uUvTransform;   // xy = scale, zw = offset

out vec3 vWorldPos;
out vec3 vNormal;
out vec2 vTexCoord;
out vec3 vTangent;

void main() {
    vec4 worldPosition = uModelMatrix * vec4(aPosition, 1.0);

    vWorldPos = worldPosition.xyz;
    vNormal = normalize(uNormalMatrix * aNormal);
    vTangent = normalize(mat3(uModelMatrix) * aTangent);
    vTexCoord = aTexCoord * uUvTransform.xy + uUvTransform.zw;

    gl_Position = uViewProjectionMatrix * worldPosition;
}
