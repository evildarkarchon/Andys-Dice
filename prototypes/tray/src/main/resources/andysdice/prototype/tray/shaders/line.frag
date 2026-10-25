#version 330 core
// PROTOTYPE. Flat-coloured overlay lines; already display-referred, so no tone mapping.
uniform vec3 uColor;
out vec4 fragColor;

void main() {
    fragColor = vec4(uColor, 1.0);
}
