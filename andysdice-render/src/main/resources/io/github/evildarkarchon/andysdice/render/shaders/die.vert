#version 330 core
// Rounded Die meshes (ticket #40) with their engraving attributes (#41): model-space positions, smooth normals,
// the atlas UV and the tangent dP/du, in mm. The uber-shader (#42) takes over the shading; the vertex stage stays
// this simple. It also passes the model-space position: a Dice Set's swirl and flakes are solid textures in the
// Die's own frame (ADR-0009), so they run on across edges and move with the Die.

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
out vec3 vModelPosition;

void main() {
    vec4 world = uModel * vec4(aPosition, 1.0);
    vModelPosition = aPosition;
    vWorldPosition = world.xyz;
    vWorldNormal = mat3(uModel) * aNormal;
    vWorldTangent = mat3(uModel) * aTangent;
    vUv = aUv;
    gl_Position = uViewProjection * world;
}
