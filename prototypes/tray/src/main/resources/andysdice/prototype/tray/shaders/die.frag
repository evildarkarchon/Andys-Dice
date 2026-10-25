#version 330 core
// PROTOTYPE. One uber-shader for every Dice Set. The numerals are ADR-0008's parallax occlusion
// engravings (the numerals prototype's technique B, unchanged); the body is a Dice Set: base colour,
// roughness and metallic, plus an optional model-space swirl, flakes, pearl sheen and transmission.
//
// Copied from the materials prototype, where ADR-0009 picked translucency technique C; the renderer now
// always sets uTechnique = 2 (the A and B branches are left in place, unused). What changed here: the
// key light, reflection environment and shadows come from the tray prototype's shared light.glsl,
// env.glsl and shadow.glsl (so the dice follow the light rig), and uGlow marks a die about to be rerolled.
//
//   C (uTechnique 2): refraction. The ray is marched through the body (swirl clouds and glitter inside
//                     it), the far face's numeral shows through, and the exit ray picks up the real tray
//                     from a copy of the opaque pass (or the room, on total internal reflection).
#include "noise.glsl"

in vec3 vPos;
in vec3 vN;
in vec3 vT;
in vec3 vB;
in vec2 vUv;
in vec4 vLightPos;
in vec3 vObj;

out vec4 fragColor;

uniform int uTechnique;
uniform sampler2D uMask;        // r = ink coverage, g = engraving profile (0 face, 1 floor)
uniform sampler2D uSlope;       // d(profile)/d(mm) along u and v (image-down)
uniform sampler2D uBackground;  // C only: the opaque pass, tone-mapped and sRGB-encoded
uniform sampler3D uSwirlTex;    // baked swirl field, one period, GL_REPEAT

uniform vec3 uCamPos;
uniform mat4 uModel;
uniform mat4 uViewProj;

uniform float uDepth;           // visible recess depth in mm (engraving depth less ink fill)
uniform float uMmPerUv;         // mm per unit of atlas uv on the flat faces
uniform vec4 uFaces[60];        // per face: (n, d), (U, u0), (V, v0); see DieModel.faceTable()
uniform int uFaceCount;

// The Dice Set (colours linear).
uniform vec3 uBase;
uniform float uRough;
uniform float uMetal;
uniform vec3 uSwirlColor;
uniform float uSwirlPeriod;     // noise cells per PERIOD_MM
uniform float uSwirlWarp;
uniform float uSwirlBands;
uniform float uSwirlAmount;
uniform float uSwirlSoft;
uniform vec3 uFlakeColor;
uniform float uFlakeDensity;
uniform float uFlakeSize;
uniform float uFlakeMetal;
uniform float uPearl;
uniform float uTransmission;
uniform vec3 uAbsorb;           // colour of white light after 10 mm in the body
uniform float uIor;
uniform float uCloud;
uniform vec3 uInk;
uniform float uInkMetal;
uniform float uInkRough;

uniform vec3 uSeed;             // per-Die offset into the swirl and flake fields, in mm
uniform int uSwirlBaked;        // 0 = evaluate the field here, 1 = read the baked 3D texture
uniform float uGlow;            // 0..1: this die is about to be picked up for a Reroll Throw

#include "light.glsl"
#include "env.glsl"
#include "shadow.glsl"

const float PI = 3.14159265;
const float PERIOD_MM = 32.0;
const vec3 FELT_LIT = vec3(0.029, 0.10, 0.05) * 1.1;  // B's stand-in for whatever is behind the die
const int VOLUME_STEPS = 8;

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

vec3 aces(vec3 x) {
    return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0);
}

// Undoes aces() + gamma on the background copy, so the refracted tray can be lit and absorbed in linear
// light. Positive root of y * (2.43x^2 + 0.59x + 0.14) = 2.51x^2 + 0.03x.
vec3 unTonemap(vec3 srgb) {
    vec3 y = min(pow(srgb, vec3(2.2)), vec3(0.985));
    vec3 A = 2.43 * y - 2.51, B = 0.59 * y - 0.03, C = 0.14 * y;
    return (-B - sqrt(max(B * B - 4.0 * A * C, 0.0))) / (2.0 * A);
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

// How much of the swirl colour is at model-space point p (0..1), after the Dice Set's threshold.
float swirlAt(vec3 p) {
    if (uSwirlAmount <= 0.0) return 0.0;
    vec3 q = (p + uSeed) / PERIOD_MM;
    float f = uSwirlBaked != 0 ? textureLod(uSwirlTex, q, 0.0).r
                               : swirlField(q * uSwirlPeriod, uSwirlPeriod, uSwirlWarp, uSwirlBands);
    float t = 1.0 - uSwirlAmount;
    return smoothstep(t - uSwirlSoft, t + uSwirlSoft, f);
}

// Whether model-space point p is inside a flake (soft 0..1), and that flake's random orientation.
// One candidate flake per cell of uFlakeSize mm, kept with probability uFlakeDensity.
float flakeAt(vec3 p, out vec3 fn) {
    fn = vec3(0.0, 0.0, 1.0);
    if (uFlakeDensity <= 0.0) return 0.0;
    vec3 q = (p + uSeed) / uFlakeSize;
    vec3 c = floor(q);
    if (hash13(c + 17.0) >= uFlakeDensity) return 0.0;
    vec3 centre = c + 0.5 + (vec3(hash13(c + 3.1), hash13(c + 5.7), hash13(c + 9.3)) - 0.5) * 0.3;
    fn = normalize(vec3(hash13(c + 11.0), hash13(c + 13.0), hash13(c + 19.0)) - 0.5 + 1e-4);
    return 1.0 - smoothstep(0.26, 0.32, length(q - centre));
}

// Distance along the model-space ray (p, d) until it leaves the die, and the face it leaves through.
// The die is convex, so that is the nearest plane the ray is heading out of.
float exitDistance(vec3 p, vec3 d, out int face) {
    float t = 1e4;
    face = 0;
    for (int i = 0; i < uFaceCount; i++) {
        vec4 pl = uFaces[i * 3];
        float den = dot(pl.xyz, d);
        if (den > 1e-4) {
            float ti = (pl.w - dot(pl.xyz, p)) / den;
            if (ti < t) {
                t = ti;
                face = i;
            }
        }
    }
    return max(t, 0.0);
}

void main() {
    vec3 V = normalize(uCamPos - vPos);
    vec3 Ng = normalize(vN);
    // The model matrix may carry a uniform scale (a die shrinking away on a POP pickup); strip it so R
    // stays a rotation for the model-space translucency maths.
    mat3 R = mat3(uModel) / length(uModel[0].xyz);
    vec3 KL;
    vec3 KC = keyLight(vPos, KL);

    // --- ADR-0008 numerals: parallax occlusion mapping plus self-shadow. ---
    // B is dP/dv (v image-down), so (T, B, N) is left-handed; transpose is still its inverse.
    mat3 TBN = mat3(normalize(vT), normalize(vB), Ng);
    mat3 toTS = transpose(TBN);
    vec3 vTS = toTS * V;
    vec2 dx = dFdx(vUv), dy = dFdy(vUv);
    float parUv = uDepth / uMmPerUv;
    vec2 uv = vUv;
    float keyOcclusion = 1.0;
    {
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
        vec2 prevUv = uv - full * step;
        float after = p - cur;
        float before = profileAt(prevUv, dx, dy) - (cur - step);
        float w = after / (after - before + 1e-5);
        uv = mix(uv, prevUv, clamp(w, 0.0, 1.0));
        float hit = mix(cur, cur - step, clamp(w, 0.0, 1.0));
        vec3 lTS = toTS * KL;
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
    vec3 N = normalize(TBN * normalize(vec3(slope * uDepth, 1.0)));
    float ink = m.r;
    float cavity = 1.0 - 0.55 * m.g * clamp(uDepth / 0.35, 0.0, 1.0);

    // --- The body at the surface: base, swirl, flakes. ---
    float s = swirlAt(vObj);
    vec3 fnObj;
    float flake = flakeAt(vObj, fnObj);
    vec3 albedo = mix(uBase, uSwirlColor, s);
    float rough = uRough, metal = uMetal;
    vec3 Nb = N;
    if (flake > 0.0) {
        albedo = mix(albedo, uFlakeColor, flake);
        metal = mix(metal, 1.0, flake * uFlakeMetal);
        rough = mix(rough, 0.18, flake * uFlakeMetal);
        // Glitter flakes lie at random angles, so each one catches the light on its own.
        vec3 fw = R * fnObj;
        Nb = normalize(mix(N, fw * sign(dot(fw, N)), 0.7 * flake * uFlakeMetal));
    }

    // Ink is opaque paint over everything, including a translucent body.
    float tw = (uTechnique > 0 && uTransmission > 0.0) ? uTransmission * (1.0 - ink) : 0.0;
    vec3 Ns = normalize(mix(Nb, N, ink));
    albedo = mix(albedo, uInk, ink);
    rough = mix(rough, uInkRough, ink);
    metal = mix(metal, uInkMetal, ink);

    vec3 f0 = mix(vec3(0.04), albedo, metal);
    vec3 diffColor = albedo * (1.0 - metal) * (1.0 - tw);
    float a = max(rough * rough, 0.002);

    vec3 shC = shadowColor(vLightPos) * keyOcclusion;
    float shadow = dot(shC, vec3(0.333));
    vec3 color = directLight(Ns, V, KL, KC, diffColor, f0, a) * shC;
    color += directLight(Ns, V, uFillDir, uFillColor, diffColor, f0, a) * mix(1.0, keyOcclusion, 0.5);

    float NoV = max(dot(Ns, V), 1e-3);
    vec2 ab = envBRDF(rough, NoV);
    vec3 Rv = reflect(-V, Ns);
    vec3 specEnv = env(Rv, rough) * (f0 * ab.x + ab.y) * mix(0.35, 1.0, shadow);
    vec3 diffEnv = env(Ns, 1.0) * diffColor;
    color += (diffEnv + specEnv) * cavity;

    // Pearl: a broad, colour-shifting sheen where the swirl is, like mica aligned in the pour.
    if (uPearl > 0.0 && s > 0.0) {
        float fres = pow(1.0 - NoV, 2.0);
        vec3 tint = mix(uSwirlColor, uSwirlColor.gbr, fres * 0.6);
        vec3 H = normalize(V + KL);
        float lobe = D_GGX(max(dot(N, H), 0.0), 0.12) * max(dot(N, KL), 0.0);
        color += uPearl * s * (1.0 - ink) * tint
                * (lobe * 0.08 * KC * shadow + env(Rv, 0.6) * 0.12 + 0.05 * fres);
    }

    // --- Translucency (B and C). Worked in model space, where the face planes live. ---
    if (tw > 0.0) {
        mat3 Rt = transpose(R);
        vec3 nO = Rt * N, vO = Rt * V;
        vec3 d = refract(-vO, nO, 1.0 / uIor);
        if (dot(d, d) < 1e-6) d = -nO;
        int face;
        float t = exitDistance(vObj, d, face);
        vec3 sigma = -log(max(uAbsorb, vec3(1e-3))) / 10.0;          // absorption per mm
        vec3 inside = env(vec3(0.0, 1.0, 0.0), 1.0) + KC * 0.15 * shadow;  // light reaching the body
        vec3 Tr, scat = vec3(0.0), glints = vec3(0.0), behind;

        if (uTechnique == 1) {
            // One segment: constant clouds (the surface swirl stands in for the inside).
            float k = (uCloud + 0.8 * s) * 0.35;
            vec3 ext = sigma + k;
            Tr = exp(-ext * t);
            scat = mix(uAbsorb, uSwirlColor, s) * inside * 0.25 * k / max(ext, vec3(1e-3)) * (1.0 - Tr);
            behind = (R * d).y < 0.0 ? FELT_LIT : env(R * d, uRough);
        } else {
            // March the body: swirl clouds absorb and scatter, glitter flakes glint.
            float dt = t / float(VOLUME_STEPS);
            Tr = vec3(1.0);
            vec3 vw = -(R * d);
            for (int i = 0; i < VOLUME_STEPS; i++) {
                vec3 x = vObj + d * ((float(i) + 0.5) * dt);
                float sv = swirlAt(x);
                float k = (uCloud + 0.8 * sv) * 0.35;
                vec3 ext = sigma + k;
                vec3 stepT = exp(-ext * dt);
                scat += Tr * mix(uAbsorb, uSwirlColor, sv) * inside * 0.25 * k / max(ext, vec3(1e-3)) * (1.0 - stepT);
                vec3 fn;
                float fl = flakeAt(x, fn);
                if (fl > 0.0) {
                    vec3 H = normalize(vw + KL);
                    float g = pow(abs(dot(R * fn, H)), 40.0);
                    glints += Tr * fl * uFlakeColor * (KC * g * 3.0 + inside * 0.2);
                    Tr *= 1.0 - 0.6 * fl;
                }
                Tr *= stepT;
            }
            // Leave through the far face: its numeral (seen from behind) blocks the view, the rest
            // refracts out to the tray, or reflects back in if the angle is past critical.
            vec3 e = vObj + d * t;
            vec4 pl = uFaces[face * 3];
            vec2 buv = vec2(dot(uFaces[face * 3 + 1].xyz, e) + uFaces[face * 3 + 1].w,
                            dot(uFaces[face * 3 + 2].xyz, e) + uFaces[face * 3 + 2].w);
            float backInk = textureLod(uMask, buv, 1.0).r;
            vec3 o = refract(d, -pl.xyz, uIor);
            if (dot(o, o) < 1e-6) {
                behind = env(R * reflect(d, -pl.xyz), uRough) * 0.7;
            } else {
                vec3 ow = R * o;
                behind = env(ow, uRough);
                vec3 ew = (uModel * vec4(e, 1.0)).xyz;
                if (ow.y < -0.02) {
                    vec4 clip = uViewProj * vec4(ew + ow * (-ew.y / ow.y), 1.0);
                    vec2 suv = clip.xy / clip.w * 0.5 + 0.5;
                    if (clip.w > 0.0 && all(greaterThan(suv, vec2(0.0))) && all(lessThan(suv, vec2(1.0))))
                        behind = unTonemap(texture(uBackground, suv).rgb);
                }
            }
            behind = mix(behind, uInk * inside * 0.6, backInk);
        }
        float Fv = 0.04 * ab.x + ab.y;
        color += (behind * Tr + scat + glints) * tw * (1.0 - Fv);
    }

    // A warm rim-weighted glow on a die about to be rerolled, so the player sees which dice go back.
    if (uGlow > 0.0) color += uGlow * vec3(1.0, 0.70, 0.25) * (0.06 + 1.2 * pow(1.0 - NoV, 3.0));

    fragColor = vec4(pow(aces(color), vec3(1.0 / 2.2)), 1.0);
}
