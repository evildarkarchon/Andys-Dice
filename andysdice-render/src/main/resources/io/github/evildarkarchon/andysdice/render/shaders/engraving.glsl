// ADR-0008 Numerals (ticket #41): an engraving traced by parallax occlusion through the Die Kind's atlas profile,
// plus a short march toward the key light so each recess shadows itself. The numerals prototype's technique B,
// unchanged in substance. Factored out of die.frag so that the Dice Set uber-shader (#42) and translucency (#43)
// include it rather than copy it: call engrave() first, then shade with its normal, ink and occlusion.
//
// Needs a vertex stage that passes the atlas UV and the world-space tangent (dP/du); the bitangent dP/dv is
// cross(T, N), because v grows image-down (EngravedVertices).

uniform sampler2D uMask;   // r = ink coverage, g = engraving profile (0 face, 1 recess floor)
uniform sampler2D uSlope;  // d(profile)/d(mm) along u and along v (image-down)
uniform float uDepth;      // visible recess depth in mm: the engraving's depth less the ink film
uniform float uMmPerUv;    // mm on a flat face per unit of atlas UV
// The Quality Preset's sample counts (ADR-0014; ADR-0008's full quality is 12-48 and 12):
uniform vec2 uParallaxSamples;    // profile samples looking straight at a face (x) and at grazing angles (y), <= 64
uniform int uParallaxShadowSamples; // samples of the self-shadow march toward the key light

// What the engraving does to one pixel of a Die.
struct Engraving {
    vec2 uv;             // the atlas UV where the view ray meets the recess
    vec3 normal;         // world-space shading normal, tilted by the recess walls
    float ink;           // 0 bare surface .. 1 fully inked
    float profile;       // 0 on the face .. 1 on the recess floor
    float keyOcclusion;  // 1 lit .. 0 shadowed by the recess's own rim, toward the key light
    float cavity;        // ambient occlusion from the recess's depth
};

// Profile lookups inside loops must not use implicit derivatives (non-uniform control flow), so every atlas
// read takes the gradients of the unshifted UV.
float engravingProfile(vec2 uv, vec2 dx, vec2 dy) {
    return textureGrad(uMask, uv, dx, dy).g;
}

// Traces the engraving at one pixel.
//   uv0       the interpolated atlas UV
//   tangent   the interpolated world tangent, dP/du (any length; re-orthogonalised here)
//   n         the unit world geometric normal
//   v         the unit direction from the surface toward the eye
//   l         the unit direction toward the key light
Engraving engrave(vec2 uv0, vec3 tangent, vec3 n, vec3 v, vec3 l) {
    // Fillets and caps carry arbitrary tangents that can nearly cancel across a triangle; their profile is
    // flat, so any frame does, but it must not be NaN.
    vec3 t = tangent - n * dot(n, tangent);
    float tl = dot(t, t);
    if (tl < 1e-12) {
        t = abs(n.x) < 0.9 ? cross(n, vec3(1.0, 0.0, 0.0)) : cross(n, vec3(0.0, 1.0, 0.0));
        tl = dot(t, t);
    }
    t *= inversesqrt(tl);
    vec3 b = cross(t, n);
    // (T, B, N) is left-handed because v grows image-down; orthonormal, so the transpose still inverts it.
    mat3 tbn = mat3(t, b, n);
    mat3 toTangent = transpose(tbn);
    vec3 vTs = toTangent * v;
    vec2 dx = dFdx(uv0);
    vec2 dy = dFdy(uv0);
    float parUv = uDepth / uMmPerUv;

    Engraving e;
    vec2 uv = uv0;
    e.keyOcclusion = 1.0;
    {
        // Steep parallax: more layers at grazing angles, where the ray crosses more of the profile.
        float nz = max(vTs.z, 0.12);
        vec2 full = -vTs.xy / nz * parUv;
        float layers = mix(uParallaxSamples.y, uParallaxSamples.x, clamp(vTs.z, 0.0, 1.0));
        float stepSize = 1.0 / layers;
        float cur = 0.0;
        float p = engravingProfile(uv, dx, dy);
        for (int i = 0; i < 64 && cur < p; i++) {
            uv += full * stepSize;
            cur += stepSize;
            p = engravingProfile(uv, dx, dy);
        }
        // Occlusion mapping: interpolate between the last two layers for a smooth hit.
        vec2 prevUv = uv - full * stepSize;
        float after = p - cur;
        float before = engravingProfile(prevUv, dx, dy) - (cur - stepSize);
        float w = clamp(after / (after - before + 1e-5), 0.0, 1.0);
        uv = mix(uv, prevUv, w);
        float hit = mix(cur, cur - stepSize, w);
        // Self-shadow: march from the hit toward the key light and see whether the rim is in the way.
        vec3 lTs = toTangent * l;
        if (hit > 0.002 && lTs.z > 0.0) {
            int SHADOW_STEPS = max(uParallaxShadowSamples, 1);
            vec2 toLight = lTs.xy / max(lTs.z, 0.08) * parUv * hit / float(SHADOW_STEPS);
            float d = hit;
            float occ = 0.0;
            vec2 suv = uv;
            for (int i = 0; i < SHADOW_STEPS; i++) {
                suv += toLight;
                d -= hit / float(SHADOW_STEPS);
                occ = max(occ, (d - engravingProfile(suv, dx, dy)) * 5.0);
            }
            e.keyOcclusion = 1.0 - clamp(occ, 0.0, 1.0);
        }
    }
    vec2 m = textureGrad(uMask, uv, dx, dy).rg;
    vec2 slope = textureGrad(uSlope, uv, dx, dy).rg;
    e.uv = uv;
    e.normal = normalize(tbn * normalize(vec3(slope * uDepth, 1.0)));
    e.ink = m.r;
    e.profile = m.g;
    e.cavity = 1.0 - 0.55 * m.g * clamp(uDepth / 0.35, 0.0, 1.0);
    return e;
}
