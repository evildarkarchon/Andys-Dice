#version 330 core
// PROTOTYPE. The tray: floor, walls and the table around it. PBR from the renderer bake-off, plus a bump
// normal from the texture's height channel, a felt-style sheen, and contact occlusion under each die.
//
// The bump uses Mikkelsen's screen-space surface gradient (dFdx/dFdy of the height): no tangents and no
// mip-level bookkeeping, and it fades on its own as the texture minifies, which is one of the things
// being judged ("how much surface detail survives at the default render scale").
in vec3 vPos;
in vec3 vN;
in vec3 vT;
in vec3 vB;
in vec2 vUv;
in vec4 vLightPos;

out vec4 fragColor;

uniform sampler2D uAlbedoTex;   // rgb: albedo, sRGB-encoded; a: height
uniform vec3 uTint;             // multiplies the albedo (the table reuses the wall wood, darker)
uniform float uRough;
uniform float uSheen;           // felt's bright rim at grazing angles
uniform float uBumpMm;          // height of a = 1, in mm
uniform vec3 uCamPos;

uniform vec4 uDice[24];         // xyz centre, w radius; for contact occlusion
uniform int uDiceCount;
uniform int uContactAo;

#include "light.glsl"
#include "env.glsl"
#include "shadow.glsl"

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

vec2 envBRDF(float rough, float NoV) {
    const vec4 c0 = vec4(-1.0, -0.0275, -0.572, 0.022);
    const vec4 c1 = vec4(1.0, 0.0425, 1.04, -0.04);
    vec4 r = rough * c0 + c1;
    float a004 = min(r.x * r.x, exp2(-9.28 * NoV)) * r.x + r.y;
    return vec2(-1.04, 1.04) * a004 + r.zw;
}

vec3 aces(vec3 x) {
    return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0);
}

vec3 directLight(vec3 N, vec3 V, vec3 L, vec3 color, vec3 diffColor, vec3 f0, float a) {
    float NoL = max(dot(N, L), 0.0);
    if (NoL <= 0.0) return vec3(0.0);
    vec3 H = normalize(V + L);
    float NoV = max(dot(N, V), 1e-3);
    float NoH = max(dot(N, H), 0.0);
    vec3 F = F_Schlick(f0, max(dot(V, H), 0.0));
    vec3 spec = D_GGX(NoH, a) * V_Smith(NoV, NoL, a) * F;
    return (diffColor / PI * (1.0 - F) + spec) * color * NoL;
}

void main() {
    vec3 V = normalize(uCamPos - vPos);
    vec3 N = normalize(vN);
    vec4 tex = texture(uAlbedoTex, vUv);
    vec3 albedo = pow(tex.rgb, vec3(2.2)) * uTint;

    if (uBumpMm > 0.0) {
        float h = tex.a * uBumpMm;
        vec3 dpdx = dFdx(vPos), dpdy = dFdy(vPos);
        vec3 r1 = cross(dpdy, N), r2 = cross(N, dpdx);
        float det = dot(dpdx, r1);
        vec3 grad = sign(det) * (dFdx(h) * r1 + dFdy(h) * r2);
        N = normalize(abs(det) * N - grad);
    }

    float ao = 1.0;
    if (uContactAo != 0) {
        float occ = 0.0;
        for (int i = 0; i < uDiceCount; i++) {
            vec3 d = uDice[i].xyz - vPos;
            float dist = length(d);
            float r = uDice[i].w;
            occ += max(dot(N, d / dist), 0.0) * (r * r) / (dist * dist);
        }
        ao = 1.0 - clamp(occ, 0.0, 1.0) * 0.85;
    }

    float rough = uRough;
    vec3 f0 = vec3(0.04);
    vec3 diffColor = albedo;
    float a = max(rough * rough, 0.002);

    vec3 L;
    vec3 kc = keyLight(vPos, L);
    vec3 sh = shadowColor(vLightPos);
    vec3 color = directLight(N, V, L, kc, diffColor, f0, a) * sh;
    color += directLight(N, V, uFillDir, uFillColor, diffColor, f0, a);

    float NoV = max(dot(N, V), 1e-3);
    vec2 ab = envBRDF(rough, NoV);
    vec3 R = reflect(-V, N);
    float shade = dot(sh, vec3(0.333));
    vec3 specEnv = env(R, rough) * (f0 * ab.x + ab.y) * mix(0.35, 1.0, shade);
    vec3 diffEnv = env(N, 1.0) * diffColor;
    color += (diffEnv + specEnv) * ao;
    // Felt fibres catch light at grazing angles; lit by the key where it reaches, a little by the room.
    color += uSheen * albedo * pow(1.0 - NoV, 4.0) * (0.25 * env(N, 1.0) + 0.12 * kc * sh) * ao;

    fragColor = vec4(pow(aces(color), vec3(1.0 / 2.2)), 1.0);
}
