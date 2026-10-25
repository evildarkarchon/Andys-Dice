#version 330 core
// PROTOTYPE. PBR shading from the renderer bake-off, plus three ways to draw an engraved, inked numeral:
//
//   A (uTechnique 0): one-step parallax offset + slope-derived normal (the bake-off baseline).
//   B (uTechnique 1): parallax occlusion mapping (ray march through the profile) + self-shadowing
//                     of the recess from the key light.
//   C (uTechnique 2): the vertex stage already displaced a dense mesh, so no UV offset here; the
//                     shadow map provides real self-shadowing.
//
// All three use the same atlas, the same slope-derived shading normal and the same ink mask, so the
// only difference is how the recess's depth reaches the screen.
in vec3 vPos;
in vec3 vN;
in vec3 vT;
in vec3 vB;
in vec2 vUv;
in vec4 vLightPos;

out vec4 fragColor;

uniform int uKind;              // 0 = die, 1 = felt, 2 = wood
uniform int uTechnique;         // 0 = A, 1 = B, 2 = C
uniform sampler2D uMask;        // die: r = ink coverage, g = engraving profile (0 face, 1 floor)
uniform sampler2D uSlope;       // die: d(profile)/d(mm) along u and v (image-down)
uniform sampler2D uAlbedoTex;   // felt / wood albedo, sRGB-encoded
uniform sampler2DShadow uShadow;
uniform float uShadowBias;

uniform vec3 uCamPos;
uniform vec3 uKeyDir;
uniform vec3 uKeyColor;
uniform vec3 uFillDir;
uniform vec3 uFillColor;

uniform vec3 uBase;
uniform vec3 uInk;
uniform float uRough;
uniform float uMetal;
uniform float uInkMetal;
uniform float uDepth;           // visible recess depth in mm (engraving depth less ink fill)
uniform float uMmPerUv;         // mm per unit of atlas uv on the flat faces

uniform vec4 uDice[16];          // xyz centre, w radius; for contact occlusion on the tray
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
            s += texture(uShadow, vec3(p.xy + vec2(x, y) * texel * 1.25, p.z - uShadowBias));
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

// Profile lookups inside loops must not use implicit derivatives (non-uniform control flow), so every
// atlas read takes the gradients of the unshifted UV.
float profileAt(vec2 uv, vec2 dx, vec2 dy) {
    return textureGrad(uMask, uv, dx, dy).g;
}

void main() {
    vec3 V = normalize(uCamPos - vPos);
    vec3 Ng = normalize(vN);
    vec3 N = Ng;
    vec3 albedo;
    float rough, metal;
    float cavity = 1.0;
    float keyOcclusion = 1.0;
    float ao = 1.0;
    vec3 sheen = vec3(0.0);

    if (uKind == 0) {
        // B is dP/dv (v image-down), so (T, B, N) is left-handed; transpose is still its inverse.
        mat3 TBN = mat3(normalize(vT), normalize(vB), Ng);
        mat3 toTS = transpose(TBN);
        vec3 vTS = toTS * V;
        vec2 dx = dFdx(vUv), dy = dFdy(vUv);
        float parUv = uDepth / uMmPerUv;   // visible depth in uv units
        vec2 uv = vUv;

        if (uTechnique == 0) {
            // One-step parallax, faded out at grazing angles where it smears glyphs.
            float p0 = profileAt(vUv, dx, dy);
            float fade = smoothstep(0.25, 0.65, vTS.z);
            uv = vUv - vTS.xy / max(vTS.z, 0.25) * p0 * parUv * fade;
        } else if (uTechnique == 1) {
            // Parallax occlusion: march down the view ray until it passes below the profile.
            float nz = max(vTS.z, 0.12);
            vec2 full = -vTS.xy / nz * parUv;
            float layers = mix(48.0, 12.0, clamp(vTS.z, 0.0, 1.0));
            float step = 1.0 / layers;
            float cur = 0.0;
            float p = profileAt(uv, dx, dy);
            for (int i = 0; i < 64 && cur < p; i++) {
                uv += full * step;
                cur += step;
                p = profileAt(uv, dx, dy);
            }
            // Interpolate between the last two samples for the crossing point.
            vec2 prevUv = uv - full * step;
            float after = p - cur;
            float before = profileAt(prevUv, dx, dy) - (cur - step);
            float w = after / (after - before + 1e-5);
            uv = mix(uv, prevUv, clamp(w, 0.0, 1.0));
            float hit = mix(cur, cur - step, clamp(w, 0.0, 1.0));

            // Self-shadow: march from the hit point toward the key light; any higher profile blocks it.
            vec3 lTS = toTS * uKeyDir;
            if (hit > 0.002 && lTS.z > 0.0) {
                const int S = 12;
                vec2 toLight = lTS.xy / max(lTS.z, 0.08) * parUv * hit / float(S);
                float d = hit, occ = 0.0;
                vec2 suv = uv;
                for (int i = 0; i < S; i++) {
                    suv += toLight;
                    d -= hit / float(S);
                    occ = max(occ, (d - profileAt(suv, dx, dy)) * 5.0);
                }
                keyOcclusion = 1.0 - clamp(occ, 0.0, 1.0);
            }
        }

        vec2 m = textureGrad(uMask, uv, dx, dy).rg;
        vec2 slope = textureGrad(uSlope, uv, dx, dy).rg;
        // Height z = -depth * profile, so the normal is (depth * dp/du, depth * dp/dv, 1) in (T, B, N).
        N = normalize(TBN * normalize(vec3(slope * uDepth, 1.0)));
        float ink = m.r;
        albedo = mix(uBase, uInk, ink);
        rough = mix(uRough, 0.6, ink);
        metal = mix(uMetal, uInkMetal, ink);
        // Recesses see less of the room; deeper visible recesses see less still.
        cavity = 1.0 - 0.55 * m.g * clamp(uDepth / 0.35, 0.0, 1.0);
    } else {
        albedo = pow(texture(uAlbedoTex, vUv).rgb, vec3(2.2));
        if (uKind == 1) {
            rough = 0.95;
            metal = 0.0;
            sheen = albedo * 1.6 * pow(1.0 - max(dot(N, V), 0.0), 4.0);
        } else {
            rough = 0.42;
            metal = 0.0;
        }
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

    float shadow = shadowFactor(vLightPos) * keyOcclusion;
    vec3 color = directLight(N, V, uKeyDir, uKeyColor, diffColor, f0, a) * shadow;
    color += directLight(N, V, uFillDir, uFillColor, diffColor, f0, a) * mix(1.0, keyOcclusion, 0.5);

    float NoV = max(dot(N, V), 1e-3);
    vec2 ab = envBRDF(rough, NoV);
    vec3 R = reflect(-V, N);
    vec3 specEnv = studioEnv(R, rough) * (f0 * ab.x + ab.y) * mix(0.35, 1.0, shadow);
    vec3 diffEnv = studioEnv(N, 1.0) * diffColor;
    color += (diffEnv + specEnv) * ao * cavity;
    color += sheen * (0.4 + 0.6 * shadow) * ao;

    fragColor = vec4(pow(aces(color), vec3(1.0 / 2.2)), 1.0);
}
