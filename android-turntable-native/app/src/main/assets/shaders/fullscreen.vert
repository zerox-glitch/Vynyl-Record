#version 300 es
// Shared full-screen triangle used by the backdrop and composite passes.
// No vertex buffer is bound: the corners come from gl_VertexID.
out vec2 vUv;

void main() {
    vec2 corner = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    vUv = corner;
    gl_Position = vec4(corner * 2.0 - 1.0, 0.0, 1.0);
}
