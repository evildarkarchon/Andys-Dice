#version 330 core
// PROTOTYPE. Shadow-map pass: depth only, from the key light. Displaces the dense mesh exactly like
// lit.vert, so variant C's recesses shadow themselves for real.
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec2 aUv;
uniform mat4 uModel;
uniform mat4 uLightVP;
uniform sampler2D uMask;
uniform float uDisplace;
uniform float uDisplaceLod;

void main() {
    vec3 p = aPos;
    if (uDisplace > 0.0) p -= aNormal * uDisplace * textureLod(uMask, aUv, uDisplaceLod).g;
    gl_Position = uLightVP * uModel * vec4(p, 1.0);
}
