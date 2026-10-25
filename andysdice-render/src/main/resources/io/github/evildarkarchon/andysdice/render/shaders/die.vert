#version 330 core
// Rounded Die meshes (ticket #40) with their engraving attributes (#41): model-space positions, smooth normals,
// the atlas UV and the tangent dP/du, in mm. The uber-shader (#42) takes over the shading; the vertex stage stays
// this simple.

layout(location = 0) in vec3 aPosition;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec2 aUv;
layout(location = 3) in vec3 aTangent;

uniform mat4 uModel;          // rotation and translation only, so mat3(uModel) turns normals and tangents too
uniform mat4 uViewProjection;

out vec3 vWorldPosition;
out vec3 vWorldNormal;
out vec3 vWorldTangent;
out vec2 vUv;

void main() {
    vec4 world = uModel * vec4(aPosition, 1.0);
    vWorldPosition = world.xyz;
    vWorldNormal = mat3(uModel) * aNormal;
    vWorldTangent = mat3(uModel) * aTangent;
    vUv = aUv;
    gl_Position = uViewProjection * world;
}
