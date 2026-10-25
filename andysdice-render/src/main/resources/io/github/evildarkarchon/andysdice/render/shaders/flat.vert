#version 330 core
// The bridge's test scene (ticket #33): positions and per-face normals, no textures.

layout(location = 0) in vec3 aPosition;
layout(location = 1) in vec3 aNormal;

uniform mat4 uModel;
uniform mat4 uViewProjection;

// Flat: the face's one normal, not interpolated, so each face reads as a single tone.
flat out vec3 vNormal;

void main() {
    vNormal = mat3(uModel) * aNormal;
    gl_Position = uViewProjection * uModel * vec4(aPosition, 1.0);
}
