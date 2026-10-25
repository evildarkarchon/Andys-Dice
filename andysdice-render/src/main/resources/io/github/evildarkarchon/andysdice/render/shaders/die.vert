#version 330 core
// Rounded Die meshes (ticket #40): model-space positions and smooth normals, in mm. The uber-shader (#42)
// takes over the shading; the vertex stage stays this simple.

layout(location = 0) in vec3 aPosition;
layout(location = 1) in vec3 aNormal;

uniform mat4 uModel;          // rotation and translation only, so mat3(uModel) turns normals too
uniform mat4 uViewProjection;

out vec3 vWorldPosition;
out vec3 vWorldNormal;

void main() {
    vec4 world = uModel * vec4(aPosition, 1.0);
    vWorldPosition = world.xyz;
    vWorldNormal = mat3(uModel) * aNormal;
    gl_Position = uViewProjection * world;
}
