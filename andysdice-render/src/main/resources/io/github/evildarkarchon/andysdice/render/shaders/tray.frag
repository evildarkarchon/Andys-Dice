#version 330 core
// The Tray's surfaces (ticket #44, ADR-0010): a procedural, tiling texture per surface kind (felt, leather, walnut),
// tinted by the Tray file's colour, with a bump normal from its height channel and felt's sheen at grazing angles.
// The tray prototype's lit.frag, lit like the Dice (the Studio key and fill, GGX, the analytic room) and tone-mapped
// with the same curve, which #43's translucency inverts for whatever lies under the Dice. Shadows and contact
// occlusion come with the Light Rigs (#45).
//
// The bump uses Mikkelsen's screen-space surface gradient (dFdx/dFdy of the height): no tangents and no mip-level
// bookkeeping, and it fades on its own as the texture minifies.

#include "tonemap.glsl"
#include "env.glsl"

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
uniform vec3 uKeyColour;      // its radiance, linear RGB
uniform vec3 uFillDirection;  // toward the fill light, normalised
uniform vec3 uFillColour;

out vec4 fragColour;

const float PI = 3.14159265;

float D_GGX(float NoH, float a) {
    float a2 = a * a;
    float d = NoH * NoH * (a2 - 1.0) + 1.0;
    return a2 / (PI * d * d);
}

float V_Smith(float NoV, float NoL, float a) {
    float k = a * 0.5;
    float gv = NoV / (NoV * (1.0 - k) + k);
    float gl = NoL / (NoL * (1.0 - k) + k);
    return gv * gl / max(4.0 * NoV * NoL, 1e-4);
}

vec3 F_Schlick(vec3 f0, float VoH) {
    return f0 + (1.0 - f0) * pow(1.0 - VoH, 5.0);
}

// Karis' analytic fit of the split-sum environment BRDF (no LUT texture needed).
vec2 envBRDF(float rough, float NoV) {
    const vec4 c0 = vec4(-1.0, -0.0275, -0.572, 0.022);
    const vec4 c1 = vec4(1.0, 0.0425, 1.04, -0.04);
    vec4 r = rough * c0 + c1;
    float a004 = min(r.x * r.x, exp2(-9.28 * NoV)) * r.x + r.y;
    return vec2(-1.04, 1.04) * a004 + r.zw;
}

// One light's GGX specular plus Lambert diffuse; the diffuse share shrinks by what the Fresnel term reflects.
vec3 directLight(vec3 N, vec3 V, vec3 L, vec3 colour, vec3 diffColour, vec3 f0, float a) {
    float NoL = max(dot(N, L), 0.0);
    if (NoL <= 0.0) return vec3(0.0);
    vec3 H = normalize(V + L);
    float NoV = max(dot(N, V), 1e-3);
    float NoH = max(dot(N, H), 0.0);
    vec3 F = F_Schlick(f0, max(dot(V, H), 0.0));
    vec3 spec = D_GGX(NoH, a) * V_Smith(NoV, NoL, a) * F;
    return (diffColour / PI * (1.0 - F) + spec) * colour * NoL;
}

void main() {
    vec3 V = normalize(uEye - vWorldPosition);
    vec3 N = normalize(vWorldNormal);
    vec4 texel = texture(uSurface, vUv);
    vec3 albedo = uTint * texel.r * uShadeRange;

    if (uBumpMm > 0.0) {
        float h = texel.g * uBumpMm;
        vec3 dpdx = dFdx(vWorldPosition);
        vec3 dpdy = dFdy(vWorldPosition);
        vec3 r1 = cross(dpdy, N);
        vec3 r2 = cross(N, dpdx);
        float det = dot(dpdx, r1);
        vec3 gradient = sign(det) * (dFdx(h) * r1 + dFdy(h) * r2);
        N = normalize(abs(det) * N - gradient);
    }
    // The walls are drawn without culling; light whichever side the camera sees.
    if (dot(N, V) < 0.0) {
        N = -N;
    }

    float rough = uRoughness;
    vec3 f0 = vec3(0.04);
    float a = max(rough * rough, 0.002);

    vec3 colour = directLight(N, V, uKeyDirection, uKeyColour, albedo, f0, a);
    colour += directLight(N, V, uFillDirection, uFillColour, albedo, f0, a);

    float NoV = max(dot(N, V), 1e-3);
    vec2 ab = envBRDF(rough, NoV);
    vec3 R = reflect(-V, N);
    vec3 specEnv = env(R, rough) * (f0 * ab.x + ab.y);
    vec3 diffEnv = env(N, 1.0) * albedo;
    colour += diffEnv + specEnv;
    // Felt fibres catch light at grazing angles; lit by the key where it reaches, a little by the room.
    float keyFacing = max(dot(N, uKeyDirection), 0.0);
    colour += uSheen * albedo * pow(1.0 - NoV, 4.0) * (0.25 * env(N, 1.0) + 0.12 * uKeyColour * keyFacing);

    // Opaque: the bridge hands JavaFX premultiplied pixels, and alpha 1 makes them plain ones.
    fragColour = vec4(toneMap(colour), 1.0);
}
