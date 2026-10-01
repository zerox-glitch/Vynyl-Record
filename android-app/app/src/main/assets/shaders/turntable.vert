#version 300 es
// Vynyl Record — turntable vertex shader.
//
// One program draws the whole scene: plinth, feet, platter, rim, mat, disc, label, grooves, spindle, arm,
// counterweight, headshell, stylus, knobs and lamp. Materials differ by uniforms rather than by shader,
// which keeps a frame to one program switch and means a part can be re-materialled without touching GL.
//
// Only two attributes are needed. The UVs for the label are derived from the vertex's own local position,
// and the grooves are computed in the fragment shader from the distance to the disc's axis — which is what
// a groove actually is. That removes a texture unit, a buffer and a class of seam bugs.

precision highp float;

layout(location = 0) in vec3 aPosition;
layout(location = 1) in vec3 aNormal;

uniform mat4 uModelMatrix;
uniform mat4 uViewProjectionMatrix;
uniform mat3 uNormalMatrix;
uniform vec2 uUvScale;
uniform vec2 uUvOffset;

out vec3 vWorldPosition;
out vec3 vNormal;
out vec2 vUv;
out vec3 vLocalPosition;

void main() {
    vec4 world = uModelMatrix * vec4(aPosition, 1.0);
    vWorldPosition = world.xyz;

    // The normal matrix is the inverse transpose of the model's upper-left 3×3: without it a non-uniformly
    // scaled part would light up as though its normals had been scaled with it.
    vNormal = normalize(uNormalMatrix * aNormal);

    vLocalPosition = aPosition;
    vUv = aPosition.xz * uUvScale + uUvOffset;

    gl_Position = uViewProjectionMatrix * world;
}
