#version 330 core
// Rounded Die meshes (ticket #40) with engraved, inked Numerals (#41, ADR-0008): a plain ivory plastic, a key
// light, a fill and a Blinn highlight, enough to judge the rounding and the Numerals by eye. The uber-shader
// (#42, ADR-0009) replaces this look; the engraving itself lives in engraving.glsl for it to include.

#include "srgb.glsl"
#include "engraving.glsl"

in vec3 vWorldPosition;
in vec3 vWorldNormal;
in vec3 vWorldTangent;
in vec2 vUv;

uniform vec3 uEye;            // camera position, world mm
uniform vec3 uKeyDirection;   // toward the key light, normalised
uniform vec3 uFillDirection;  // toward the fill light, normalised
uniform vec3 uColour;         // body, linear RGB
uniform vec3 uInk;            // ink, linear RGB

out vec4 fragColour;

void main() {
    vec3 ng = normalize(vWorldNormal);
    vec3 v = normalize(uEye - vWorldPosition);
    Engraving e = engrave(vUv, vWorldTangent, ng, v, uKeyDirection);
    vec3 n = e.normal;
    vec3 albedo = mix(uColour, uInk, e.ink);
    float key = max(dot(n, uKeyDirection), 0.0) * e.keyOcclusion;
    float fill = max(dot(n, uFillDirection), 0.0) * mix(1.0, e.keyOcclusion, 0.5);
    vec3 h = normalize(uKeyDirection + v);
    // The highlight is what shows a fillet's curve: it runs along the edge as a thin bright band. The ink is a
    // matt paint, so it barely shines.
    float highlight = key > 0.0 ? pow(max(dot(n, h), 0.0), 80.0) * e.keyOcclusion : 0.0;
    vec3 linear = albedo * (0.12 * e.cavity + 0.75 * key + 0.25 * fill)
            + vec3(0.35 * highlight * (1.0 - 0.85 * e.ink));
    // Opaque: the bridge hands JavaFX premultiplied pixels, and alpha 1 makes them plain ones.
    fragColour = vec4(linearToSrgb(linear), 1.0);
}
