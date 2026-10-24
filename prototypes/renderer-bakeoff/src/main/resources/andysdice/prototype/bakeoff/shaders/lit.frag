#version 330 core
// PROTOTYPE. Physically based shading for the embedded-renderer variants.
//
// - GGX / Smith / Schlick microfacet BRDF with roughness and metalness.
// - A procedural "studio" environment (gradient + two softboxes) for reflections and ambient,
//   pre-blurred analytically by roughness instead of with prefiltered cube maps.
// - Key-light shadow map with 5x5 hardware-PCF taps.
// - Engraved numerals: parallax-offset UVs + normal map + cavity darkening, ink in the recess.
// - Analytic sphere occlusion from every die onto the tray, for contact darkening.
in vec3 vPos;
in vec3 vN;
in vec3 vT;
in vec3 vB;
in vec2 vUv;
in vec4 vLightPos;

out vec4 fragColor;

uniform int uKind;              // 0 = die, 1 = felt, 2 = wood
uniform sampler2D uMask;        // die: r = ink coverage, g = height (1 face, 0 recess floor)
uniform sampler2D uNormalMap;   // die: tangent-space normals (u right, v down, n out)
uniform sampler2D uAlbedoTex;   // felt / wood albedo, sRGB-encoded
uniform sampler2DShadow uShadow;

uniform vec3 uCamPos;
uniform vec3 uKeyDir;
uniform vec3 uKeyColor;
uniform vec3 uFillDir;
uniform vec3 uFillColor;
uniform float uEnvStrength;

uniform vec3 uBase;
uniform vec3 uInk;
uniform float uRough;
uniform float uMetal;
uniform float uInkMetal;
uniform float uParallax;        // engraving depth in UV units

uniform vec4 uDice[20];         // xyz centre, w radius; for contact occlusion on the tray
uniform int uDiceCount;

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

// Rectangular light in direction c; edge width w fakes the blur rough surfaces see, and the
// intensity is scaled down as it spreads so rough reflections don't gain energy.
float softbox(vec3 d, vec3 c, vec2 halfSize, float w) {
    float cd = dot(d, c);
    if (cd <= 0.0) return 0.0;
    vec3 t = normalize(cross(c, vec3(0.0, 1.0, 0.001)));
    vec3 b = cross(t, c);
    vec2 p = vec2(dot(d, t), dot(d, b)) / cd;
    vec2 q = abs(p) - halfSize;
    float dist = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0);
    float energy = (halfSize.x * halfSize.y) / ((halfSize.x + w) * (halfSize.y + w));
    return (1.0 - smoothstep(-w, w, dist)) * energy;
}

vec3 studioEnv(vec3 d, float rough) {
    float y = d.y;
    // Below the horizon is the felt tray itself, above it a dim room with a brighter ceiling.
    vec3 col = mix(vec3(0.012, 0.035, 0.02), vec3(0.16, 0.15, 0.14), smoothstep(-0.15, 0.25, y));
    col = mix(col, vec3(0.30, 0.31, 0.34), smoothstep(0.3, 0.95, y));
    float w = 0.02 + rough * rough * 1.2;
    col += softbox(d, normalize(vec3(-0.45, 0.85, -0.35)), vec2(0.38, 0.24), w) * vec3(7.0, 6.6, 6.0);
    col += softbox(d, normalize(vec3(0.7, 0.45, 0.5)), vec2(0.25, 0.42), w) * vec3(1.2, 1.4, 1.7);
    return col;
}

float shadowFactor(vec4 lp) {
    vec3 p = lp.xyz / lp.w * 0.5 + 0.5;
    if (p.x <= 0.0 || p.x >= 1.0 || p.y <= 0.0 || p.y >= 1.0 || p.z >= 1.0) return 1.0;
    vec2 texel = 1.0 / vec2(textureSize(uShadow, 0));
    float s = 0.0;
    for (int x = -2; x <= 2; x++)
        for (int y = -2; y <= 2; y++)
            s += texture(uShadow, vec3(p.xy + vec2(x, y) * texel * 1.25, p.z - 0.0004));
    return s / 25.0;
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
    vec3 Ng = normalize(vN);
    vec3 N = Ng;
    vec3 albedo;
    float rough, metal;
    float cavity = 1.0;
    float ao = 1.0;
    vec3 sheen = vec3(0.0);

    if (uKind == 0) {
        mat3 TBN = mat3(normalize(vT), normalize(vB), Ng);
        vec3 vTS = transpose(TBN) * V;
        // One-step parallax: shift the lookup towards the viewer by how deep the texel sits.
        // Faded out at grazing angles, where one-step parallax smears glyphs into squiggles.
        float h0 = texture(uMask, vUv).g;
        float grazingFade = smoothstep(0.25, 0.65, vTS.z);
        vec2 uv = vUv - vTS.xy / max(vTS.z, 0.25) * (1.0 - h0) * uParallax * grazingFade;
        vec2 m = texture(uMask, uv).rg;
        float ink = m.r;
        vec3 nTS = texture(uNormalMap, uv).xyz * 2.0 - 1.0;
        N = normalize(TBN * nTS);
        albedo = mix(uBase, uInk, ink);
        rough = mix(uRough, 0.55, ink);
        metal = mix(uMetal, uInkMetal, ink);
        // Recess walls see less of the room; ink at the bottom is shadowed a little too.
        cavity = mix(0.45, 1.0, m.g) * mix(1.0, 0.85, ink);
    } else {
        albedo = pow(texture(uAlbedoTex, vUv).rgb, vec3(2.2));
        if (uKind == 1) {
            rough = 0.95;
            metal = 0.0;
            // Felt fibres back-scatter at grazing angles: a cheap sheen rim.
            sheen = albedo * 1.6 * pow(1.0 - max(dot(N, V), 0.0), 4.0);
        } else {
            rough = 0.42;
            metal = 0.0;
        }
        // Analytic sphere occlusion (cos * (r/d)^2) from each die: contact darkening under dice.
        float occ = 0.0;
        for (int i = 0; i < uDiceCount; i++) {
            vec3 d = uDice[i].xyz - vPos;
            float dist = length(d);
            float r = uDice[i].w;
            occ += max(dot(N, d / dist), 0.0) * (r * r) / (dist * dist);
        }
        ao = 1.0 - clamp(occ, 0.0, 1.0) * 0.85;
    }

    vec3 f0 = mix(vec3(0.04), albedo, metal);
    vec3 diffColor = albedo * (1.0 - metal);
    float a = max(rough * rough, 0.002);

    float shadow = shadowFactor(vLightPos);
    vec3 color = directLight(N, V, uKeyDir, uKeyColor, diffColor, f0, a) * shadow;
    color += directLight(N, V, uFillDir, uFillColor, diffColor, f0, a);

    float NoV = max(dot(N, V), 1e-3);
    vec2 ab = envBRDF(rough, NoV);
    vec3 R = reflect(-V, N);
    // Reflections of the key softbox are occluded where the key light is shadowed.
    vec3 specEnv = studioEnv(R, rough) * (f0 * ab.x + ab.y) * mix(0.35, 1.0, shadow);
    vec3 diffEnv = studioEnv(N, 1.0) * diffColor;
    color += (diffEnv + specEnv) * uEnvStrength * ao * cavity;
    color += sheen * (0.4 + 0.6 * shadow) * ao;

    fragColor = vec4(pow(aces(color), vec3(1.0 / 2.2)), 1.0);
}
