#version 330 core
// The shadow-map pass (ticket #45): depth only, from the key light. Dice draw with their model matrix, the Tray's
// walls (already in world space) with the identity. Position is attribute 0 in both GlMesh and the Tray's parts.

layout(location = 0) in vec3 aPosition;

uniform mat4 uModel;
uniform mat4 uLightViewProjection;

void main() {
    gl_Position = uLightViewProjection * uModel * vec4(aPosition, 1.0);
}
