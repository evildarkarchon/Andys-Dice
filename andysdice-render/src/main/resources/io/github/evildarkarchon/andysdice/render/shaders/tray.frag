#version 330 core
// The Tray's surfaces (ticket #44, ADR-0010): a procedural, tiling texture per surface kind (felt, leather, walnut),
// tinted by the Tray file's colour, with a bump normal from its height channel and felt's sheen at grazing angles.
// Lit by the Dice's own key and fill so the two match; the Light Rigs' PCSS shadows and reflection environments
// (#45) replace this lighting.
//
// The bump uses Mikkelsen's screen-space surface gradient (dFdx/dFdy of the height), as the tray prototype's
// lit.frag: no tangents, and it fades on its own as the texture minifies.

#include "srgb.glsl"

in vec3 vWorldPosition;
in vec3 vWorldNormal;
in vec2 vUv;

uniform sampler2D uSurface;   // r: shade / uShadeRange (linear), g: height 0..1
uniform float uShadeRange;    // the shade that r = 1 stands for
uniform vec3 uTint;           // the surface's colour, linear RGB
uniform float uRoughness;
uniform float uSheen;         // felt's bright rim at grazing angles
uniform float uBumpMm;        // the height of g = 1, in mm
uniform vec3 uEye;            // camera position, world mm
uniform vec3 uKeyDirection;   // toward the key light, normalised
uniform vec3 uFillDirection;  // toward the fill light, normalised

out vec4 fragColour;

void main() {
    vec3 n = normalize(vWorldNormal);
    vec3 v = normalize(uEye - vWorldPosition);
    vec4 texel = texture(uSurface, vUv);
    vec3 albedo = uTint * texel.r * uShadeRange;

    if (uBumpMm > 0.0) {
        float h = texel.g * uBumpMm;
        vec3 dpdx = dFdx(vWorldPosition);
        vec3 dpdy = dFdy(vWorldPosition);
        vec3 r1 = cross(dpdy, n);
        vec3 r2 = cross(n, dpdx);
        float det = dot(dpdx, r1);
        vec3 gradient = sign(det) * (dFdx(h) * r1 + dFdy(h) * r2);
        n = normalize(abs(det) * n - gradient);
    }
    // Seen from below (the underside of a wall's top), light the side the camera is on.
    if (dot(n, v) < 0.0) {
        n = -n;
    }

    float key = max(dot(n, uKeyDirection), 0.0);
    float fill = max(dot(n, uFillDirection), 0.0);
    // A normalised Blinn lobe whose width follows the roughness: felt barely shines, finished walnut does.
    float a = max(uRoughness * uRoughness, 0.002);
    float shininess = 2.0 / (a * a) - 2.0;
    vec3 h = normalize(uKeyDirection + v);
    float specular = key > 0.0 ? pow(max(dot(n, h), 0.0), shininess) * (shininess + 8.0) / 25.13 : 0.0;
    float fresnel = 0.04 + 0.96 * pow(1.0 - max(dot(n, v), 0.0), 5.0);
    float nv = max(dot(n, v), 1e-3);

    vec3 linear = albedo * (0.14 + 0.75 * key + 0.25 * fill)
            + vec3(fresnel * specular * key * 0.6)
            + uSheen * albedo * pow(1.0 - nv, 4.0) * (0.25 + 0.12 * key);
    // Opaque: the bridge hands JavaFX premultiplied pixels, and alpha 1 makes them plain ones.
    fragColour = vec4(linearToSrgb(linear), 1.0);
}
