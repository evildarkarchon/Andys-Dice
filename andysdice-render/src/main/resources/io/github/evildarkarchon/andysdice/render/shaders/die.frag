#version 330 core
// The Dice Set uber-shader (ticket #42, ADR-0009): one shader draws every Dice Set from its parameters. The body is
// a base colour, roughness and metallic, plus an optional model-space swirl (read from its baked 3D texture),
// flakes (speckle or glitter) and a pearl sheen; a solid resin is simply one whose extras are all zero. The
// Numerals are ADR-0008's engravings (engraving.glsl), filled with the Dice Set's opaque ink.
//
// From the tray prototype's die.frag. A translucent Dice Set (#43, the prototype's technique C) refracts the view
// ray into the body, finds where it leaves against the Die Kind's face planes (a Die is convex), marches the path
// for clouds and glitter, shows the far face's Numerals from behind, and refracts the exit ray onto a copy of the
// opaque pass (or reflects the room, past the critical angle). Shadows and the other Light Rigs are #45, the
// reroll glow #47.

#include "hash.glsl"
#include "engraving.glsl"
#include "env.glsl"
#include "tonemap.glsl"

in vec3 vWorldPosition;
in vec3 vWorldNormal;
in vec3 vWorldTangent;
in vec2 vUv;
in vec3 vModelPosition;

uniform mat4 uModel;          // rotation and translation only: mat3(uModel) turns model directions to world
uniform mat4 uViewProjection; // world to clip, to find where a refracted ray lands in the opaque pass

uniform vec3 uEye;            // camera position, world mm
uniform vec3 uKeyDirection;   // toward the key light, normalised
uniform vec3 uKeyColour;      // its radiance, linear RGB
uniform vec3 uFillDirection;  // toward the fill light, normalised
uniform vec3 uFillColour;

// The Dice Set, colours in linear RGB.
uniform vec3 uBase;
uniform float uRough;
uniform float uMetal;
uniform sampler3D uSwirlTex;  // the baked swirl field: one period, GL_REPEAT, single channel
uniform float uSwirlPeriodMm; // mm the swirl texture spans
uniform vec3 uSwirlColour;
uniform float uSwirlAmount;   // 0 = no swirl (and no texture bound)
uniform float uSwirlSoft;
uniform vec3 uFlakeColour;
uniform float uFlakeDensity;  // 0 = no flakes
uniform float uFlakeSize;     // mm per flake cell
uniform float uFlakeMetal;
uniform float uPearl;
uniform vec3 uInk;
uniform float uInkMetal;
uniform float uInkRough;
uniform float uTransmission;  // 0 = opaque (the translucent path is skipped), 1 = clear
uniform vec3 uTransmissionColour; // the colour of white light after 10 mm inside the body
uniform float uIor;
uniform float uCloud;         // how much light the body scatters

uniform vec3 uSeed;           // this Die's offset into the swirl and flake fields, mm, so no two Dice match
uniform float uGlow;          // 0..1: this Die is about to be picked up for a Reroll Throw (#47)

// Translucency only. The Die Kind's flat faces in model space, three vec4 each (NumeralLayout.faceTable()):
// the plane (n, n.centre), then the atlas map (U, u0) and (V, v0), so uv = (U.x + u0, V.x + v0). A d20 has 20.
uniform vec4 uFaces[60];
uniform int uFaceCount;
uniform sampler2D uBackground; // a copy of the frame after the opaque pass, as the render target holds it
uniform int uVolumeSteps;     // samples of the march through the body: a Quality Preset lever (ADR-0009: 8)

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

// How much of the swirl colour is at model-space point p (0..1), after the Dice Set's threshold.
float swirlAt(vec3 p) {
    if (uSwirlAmount <= 0.0) return 0.0;
    // An explicit LOD: no mip chain is baked, and implicit derivatives are undefined in non-uniform control flow.
    float f = textureLod(uSwirlTex, (p + uSeed) / uSwirlPeriodMm, 0.0).r;
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

// How far the model-space ray (p, d) runs before it leaves the Die, and the face it leaves through. A Die is
// convex, so that is the nearest of the planes the ray is heading out of. The rounded edges are ignored (a known
// v1 limit, ADR-0009): a ray that would leave through a fillet leaves through the flat face's plane beyond it.
float exitDistance(vec3 p, vec3 d, out int face) {
    float t = 1e4;
    face = 0;
    for (int i = 0; i < uFaceCount; i++) {
        vec4 plane = uFaces[i * 3];
        float facing = dot(plane.xyz, d);
        if (facing > 1e-4) {
            float ti = (plane.w - dot(plane.xyz, p)) / facing;
            if (ti < t) {
                t = ti;
                face = i;
            }
        }
    }
    return max(t, 0.0);
}

// What the eye sees through a translucent body at model-space point p, where the view ray enters along the
// model-space surface normal n: light carried from behind the Die, plus the light its clouds and glitter send to
// the eye. R turns model directions into the world; KL is toward the key light.
vec3 throughTheBody(vec3 p, vec3 n, vec3 viewModel, mat3 R, vec3 KL) {
    vec3 d = refract(-viewModel, n, 1.0 / uIor);
    // A grazing ray the maths can't bend (it can't really happen going into a denser body) goes straight in.
    if (dot(d, d) < 1e-6) d = -n;
    int face;
    float t = exitDistance(p, d, face);
    // Beer-Lambert: the colour given for 10 mm of body sets the absorption per mm.
    vec3 sigma = -log(max(uTransmissionColour, vec3(1e-3))) / 10.0;
    // The light that reaches the inside of the body to be scattered: the room above and a share of the key.
    vec3 inside = env(vec3(0.0, 1.0, 0.0), 1.0) + uKeyColour * 0.15;

    // March the body: swirl clouds absorb and scatter, glitter flakes glint. The swirl texture is sampled at an
    // explicit LOD, so the loop's non-uniform control flow is harmless.
    float dt = t / float(uVolumeSteps);
    vec3 Tr = vec3(1.0);
    vec3 scattered = vec3(0.0);
    vec3 glints = vec3(0.0);
    vec3 towardEye = -(R * d);
    for (int i = 0; i < uVolumeSteps; i++) {
        vec3 x = p + d * ((float(i) + 0.5) * dt);
        float sv = swirlAt(x);
        float k = (uCloud + 0.8 * sv) * 0.35;
        vec3 extinction = sigma + k;
        vec3 stepTr = exp(-extinction * dt);
        scattered += Tr * mix(uTransmissionColour, uSwirlColour, sv) * inside * 0.25 * k
                / max(extinction, vec3(1e-3)) * (1.0 - stepTr);
        vec3 fn;
        float fl = flakeAt(x, fn);
        if (fl > 0.0) {
            vec3 H = normalize(towardEye + KL);
            float g = pow(abs(dot(R * fn, H)), 40.0);
            glints += Tr * fl * uFlakeColour * (uKeyColour * g * 3.0 + inside * 0.2);
            Tr *= 1.0 - 0.6 * fl;
        }
        Tr *= stepTr;
    }

    // Leave through the far face. Its ink (seen from behind, so mirrored) blocks the view; elsewhere the ray
    // refracts out onto whatever the opaque pass drew, or reflects back in past the critical angle.
    vec3 e = p + d * t;
    vec3 outward = uFaces[face * 3].xyz;
    vec4 U = uFaces[face * 3 + 1];
    vec4 Vm = uFaces[face * 3 + 2];
    vec2 backUv = vec2(dot(U.xyz, e) + U.w, dot(Vm.xyz, e) + Vm.w);
    // One mip down: a far Numeral is seen blurred through the body, and an explicit LOD is needed in this branch.
    float backInk = textureLod(uMask, backUv, 1.0).r;
    vec3 o = refract(d, -outward, uIor);
    vec3 behind;
    if (dot(o, o) < 1e-6) {
        behind = env(R * reflect(d, -outward), uRough) * 0.7;
    } else {
        vec3 ow = R * o;
        behind = env(ow, uRough);
        vec3 ew = (uModel * vec4(e, 1.0)).xyz;
        // A ray heading down meets the floor (y = 0); where that lands on screen is what the opaque pass shows
        // there. Off screen, or heading up, the room stands in.
        if (ow.y < -0.02) {
            vec4 clip = uViewProjection * vec4(ew + ow * (-ew.y / ow.y), 1.0);
            vec2 suv = clip.xy / clip.w * 0.5 + 0.5;
            if (clip.w > 0.0 && all(greaterThan(suv, vec2(0.0))) && all(lessThan(suv, vec2(1.0)))) {
                behind = unToneMap(textureLod(uBackground, suv, 0.0).rgb);
            }
        }
    }
    behind = mix(behind, uInk * inside * 0.6, backInk);
    return behind * Tr + scattered + glints;
}

void main() {
    vec3 V = normalize(uEye - vWorldPosition);
    vec3 Ng = normalize(vWorldNormal);
    vec3 KL = uKeyDirection;

    // --- ADR-0008 Numerals: parallax occlusion through the atlas, with the recess's own shadow. ---
    Engraving e = engrave(vUv, vWorldTangent, Ng, V, KL);
    vec3 N = e.normal;
    float ink = e.ink;

    // --- The body at the surface: base, swirl, flakes. ---
    float s = swirlAt(vModelPosition);
    vec3 fnModel;
    float flake = flakeAt(vModelPosition, fnModel);
    vec3 albedo = mix(uBase, uSwirlColour, s);
    float rough = uRough, metal = uMetal;
    vec3 Nb = N;
    if (flake > 0.0) {
        albedo = mix(albedo, uFlakeColour, flake);
        metal = mix(metal, 1.0, flake * uFlakeMetal);
        rough = mix(rough, 0.18, flake * uFlakeMetal);
        // Glitter flakes lie at random angles, so each one catches the light on its own.
        vec3 fw = mat3(uModel) * fnModel;
        Nb = normalize(mix(N, fw * sign(dot(fw, N)), 0.7 * flake * uFlakeMetal));
    }

    // Ink is opaque paint over everything (ADR-0009), with a colour, metallic and roughness of its own, so it
    // also stops a translucent body's light where it lies.
    float tw = uTransmission > 0.0 ? uTransmission * (1.0 - ink) : 0.0;
    vec3 Ns = normalize(mix(Nb, N, ink));
    albedo = mix(albedo, uInk, ink);
    rough = mix(rough, uInkRough, ink);
    metal = mix(metal, uInkMetal, ink);

    // A metal has no diffuse at all: its colour is its reflectance (F0), so all it shows is what it reflects.
    // Light that goes through a translucent body isn't scattered back off its surface either.
    vec3 f0 = mix(vec3(0.04), albedo, metal);
    vec3 diffColour = albedo * (1.0 - metal) * (1.0 - tw);
    float a = max(rough * rough, 0.002);

    vec3 colour = directLight(Ns, V, KL, uKeyColour, diffColour, f0, a) * e.keyOcclusion;
    colour += directLight(Ns, V, uFillDirection, uFillColour, diffColour, f0, a) * mix(1.0, e.keyOcclusion, 0.5);

    // The environment, by the split-sum: this reflection is what keeps a metal from looking like grey plastic.
    float NoV = max(dot(Ns, V), 1e-3);
    vec2 ab = envBRDF(rough, NoV);
    vec3 Rv = reflect(-V, Ns);
    vec3 specEnv = env(Rv, rough) * (f0 * ab.x + ab.y);
    vec3 diffEnv = env(Ns, 1.0) * diffColour;
    colour += (diffEnv + specEnv) * e.cavity;

    // Pearl: a broad, colour-shifting sheen where the swirl is, like mica aligned in the pour.
    if (uPearl > 0.0 && s > 0.0) {
        float fres = pow(1.0 - NoV, 2.0);
        vec3 tint = mix(uSwirlColour, uSwirlColour.gbr, fres * 0.6);
        vec3 H = normalize(V + KL);
        float lobe = D_GGX(max(dot(N, H), 0.0), 0.12) * max(dot(N, KL), 0.0);
        colour += uPearl * s * (1.0 - ink) * tint
                * (lobe * 0.08 * uKeyColour * e.keyOcclusion + env(Rv, 0.6) * 0.12 + 0.05 * fres);
    }

    // Translucency, worked in model space where the face planes live. What the surface doesn't reflect (by the
    // same split-sum Fresnel the reflection used) goes in and comes back out as the view through the body.
    if (tw > 0.0) {
        mat3 R = mat3(uModel);
        mat3 toModel = transpose(R);
        float Fv = 0.04 * ab.x + ab.y;
        colour += throughTheBody(vModelPosition, toModel * N, toModel * V, R, KL) * tw * (1.0 - Fv);
    }

    // The reroll glow (#47, the tray prototype's): warm and weighted to the rim, so the Player sees which Dice go
    // back. Added last, over whatever the Dice Set drew, the view through a translucent body included.
    if (uGlow > 0.0) colour += uGlow * vec3(1.0, 0.70, 0.25) * (0.06 + 1.2 * pow(1.0 - max(dot(Ng, V), 0.0), 3.0));

    // Opaque: the bridge hands JavaFX premultiplied pixels, and alpha 1 makes them plain ones.
    fragColour = vec4(toneMap(colour), 1.0);
}
