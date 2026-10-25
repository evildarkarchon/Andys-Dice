#version 330 core
// The Tray (ticket #44, ADR-0010): its floor, walls and the table around it. All static and already in world space
// (y-up, mm), so there is no model matrix.

layout(location = 0) in vec3 aPosition;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec2 aUv;       // in texture tiles, at a constant density in mm

uniform mat4 uViewProjection;

out vec3 vWorldPosition;
out vec3 vWorldNormal;
out vec2 vUv;

void main() {
    vWorldPosition = aPosition;
    vWorldNormal = aNormal;
    vUv = aUv;
    gl_Position = uViewProjection * vec4(aPosition, 1.0);
}
