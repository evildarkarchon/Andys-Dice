#version 330 core
// PROTOTYPE. Translucent casters' shadow pass (tinted mode): depth from the fixed pipeline, plus the
// colour light keeps after passing through this die's body.
uniform vec3 uTint;
out vec4 fragColor;

void main() {
    fragColor = vec4(uTint, 1.0);
}
