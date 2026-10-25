#version 330 core
// Rounded Die meshes (ticket #40): a plain ivory plastic, a key light, a fill and a Blinn highlight, enough to
// judge the rounding by eye. The uber-shader (#42, ADR-0009) replaces this look.

#include "srgb.glsl"

in vec3 vWorldPosition;
in vec3 vWorldNormal;

uniform vec3 uEye;            // camera position, world mm
uniform vec3 uKeyDirection;   // toward the key light, normalised
uniform vec3 uFillDirection;  // toward the fill light, normalised
uniform vec3 uColour;         // linear RGB

out vec4 fragColour;

void main() {
    vec3 n = normalize(vWorldNormal);
    vec3 v = normalize(uEye - vWorldPosition);
    float key = max(dot(n, uKeyDirection), 0.0);
    float fill = max(dot(n, uFillDirection), 0.0);
    vec3 h = normalize(uKeyDirection + v);
    // The highlight is what shows a fillet's curve: it runs along the edge as a thin bright band.
    float highlight = key > 0.0 ? pow(max(dot(n, h), 0.0), 80.0) : 0.0;
    vec3 linear = uColour * (0.12 + 0.75 * key + 0.25 * fill) + vec3(0.35 * highlight);
    // Opaque: the bridge hands JavaFX premultiplied pixels, and alpha 1 makes them plain ones.
    fragColour = vec4(linearToSrgb(linear), 1.0);
}
