// The key light's shadow (ticket #45, ADR-0010), shared by the Tray and the Dice: the tray prototype's shadow.glsl,
// PCSS only. Percentage-closer soft shadows: a blocker search finds how far the occluder is above the receiver, and
// the filter widens with it, so a shadow is sharp where a Die touches the floor and soft further out, the way a
// light of some size behaves. One code path for every rig; each rig sets its own light size.
//
// Translucent Dice cast full, uncoloured shadows (ADR-0009, ADR-0010): there is one map, of every caster.

uniform mat4 uLightViewProjection;   // world mm to the key light's clip space
uniform sampler2DShadow uShadow;     // the shadow map, depth compare
uniform sampler2D uShadowRaw;        // the same depth texture through a non-comparing sampler object
uniform float uShadowBiasMm;
uniform int uLightPerspective;       // 0 orthographic (directional key), 1 perspective (spot)
uniform vec2 uLightNearFar;          // mm
uniform float uLightSize;            // tan(angular radius), or the spot's radius in mm
uniform float uUvPerMm;              // ortho: map uv per mm; perspective: uv per mm at 1 mm from the light
uniform int uShadowTaps;             // 1..16 taps of the disc below, in each PCSS loop: the Quality Preset's (ADR-0014)

const vec2 POISSON[16] = vec2[](
    vec2(-0.94201624, -0.39906216), vec2(0.94558609, -0.76890725), vec2(-0.09418410, -0.92938870),
    vec2(0.34495938, 0.29387760), vec2(-0.91588581, 0.45771432), vec2(-0.81544232, -0.87912464),
    vec2(-0.38277543, 0.27676845), vec2(0.97484398, 0.75648379), vec2(0.44323325, -0.97511554),
    vec2(0.53742981, -0.47373420), vec2(-0.26496911, -0.41893023), vec2(0.79197514, 0.19090188),
    vec2(-0.24188840, 0.99706507), vec2(-0.81409955, 0.91437590), vec2(0.19984126, 0.78641367),
    vec2(0.14383161, -0.14100790));

// Window-space depth (0..1) to millimetres from the light.
float lightDistance(float z) {
    float n = uLightNearFar.x, f = uLightNearFar.y;
    if (uLightPerspective == 0) return n + z * (f - n);
    float zn = z * 2.0 - 1.0;
    return 2.0 * n * f / (f + n - zn * (f - n));
}

// A bias of uShadowBiasMm, in window-space depth at distance d.
float depthBias(float d) {
    float n = uLightNearFar.x, f = uLightNearFar.y;
    if (uLightPerspective == 0) return uShadowBiasMm / (f - n);
    return uShadowBiasMm * f * n / ((f - n) * d * d);
}

float pcss(vec3 p, float bias) {
    vec2 texel = 1.0 / vec2(textureSize(uShadow, 0));
    float dR = lightDistance(p.z);
    float uvScale = uLightPerspective == 0 ? uUvPerMm : uUvPerMm / dR;
    // A random rotation per pixel turns the Poisson disc's banding into fine noise.
    float ang = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453) * 6.2831853;
    mat2 rot = mat2(cos(ang), sin(ang), -sin(ang), cos(ang));

    // Blocker search: anything up to 60 mm nearer the light could shade this point.
    float searchMm = uLightPerspective == 0 ? uLightSize * 60.0 : uLightSize * 60.0 / max(dR - 60.0, 1.0);
    float searchUv = clamp(searchMm * uvScale, 2.0 * texel.x, 48.0 * texel.x);
    float sum = 0.0, count = 0.0;
    // A cheaper preset takes the disc's first taps only; those already spread over all four quadrants.
    int taps = clamp(uShadowTaps, 1, 16);
    for (int i = 0; i < taps; i++) {
        float z = texture(uShadowRaw, p.xy + rot * POISSON[i] * searchUv).r;
        if (z < p.z - bias) {
            sum += lightDistance(z);
            count += 1.0;
        }
    }
    if (count == 0.0) return 1.0;
    float dB = sum / count;
    // The penumbra grows with the gap between the blocker and the receiver, scaled by the light's size.
    float penMm = uLightPerspective == 0 ? (dR - dB) * uLightSize : (dR - dB) / dB * uLightSize;
    float penUv = clamp(penMm * uvScale, 1.0 * texel.x, 48.0 * texel.x);
    float s = 0.0;
    for (int i = 0; i < taps; i++) s += texture(uShadow, vec3(p.xy + rot * POISSON[i] * penUv, p.z - bias));
    return s / float(taps);
}

// How much of the key light reaches world point pos: 1 lit, 0 in full shadow. Outside the map is lit.
float keyShadow(vec3 pos) {
    vec4 lp = uLightViewProjection * vec4(pos, 1.0);
    vec3 p = lp.xyz / lp.w * 0.5 + 0.5;
    if (p.x <= 0.0 || p.x >= 1.0 || p.y <= 0.0 || p.y >= 1.0 || p.z >= 1.0) return 1.0;
    return pcss(p, depthBias(lightDistance(p.z)));
}
