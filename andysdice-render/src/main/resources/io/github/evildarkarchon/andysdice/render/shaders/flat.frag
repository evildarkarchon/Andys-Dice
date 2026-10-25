#version 330 core
// The bridge's test scene (ticket #33): Lambert shading per face from one directional light.

#include "srgb.glsl"

flat in vec3 vNormal;

uniform vec3 uLightDirection; // toward the light, normalised
uniform vec3 uColour;         // linear RGB

out vec4 fragColour;

void main() {
    float lambert = max(dot(normalize(vNormal), uLightDirection), 0.0);
    vec3 linear = uColour * (0.2 + 0.8 * lambert);
    // Opaque: the bridge hands JavaFX premultiplied pixels, and alpha 1 makes them plain ones.
    fragColour = vec4(linearToSrgb(linear), 1.0);
}
