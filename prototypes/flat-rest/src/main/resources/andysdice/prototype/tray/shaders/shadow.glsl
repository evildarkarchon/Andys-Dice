// PROTOTYPE. Shadows from the key light's shadow map, shared by the tray and the dice.
//
//   PCF  (uShadowMode 0): a fixed 5x5 filter, so every shadow edge has the same width.
//   PCSS (uShadowMode 1): percentage-closer soft shadows. A blocker search finds how far the occluder
//        is above the receiver, and the filter widens with it: sharp where a die touches the tray,
//        soft further out, the way a real light of some size behaves.
//
// With tinted translucent shadows on, translucent dice are drawn into a second map (depth plus colour)
// instead of the opaque one, and light through them is filtered by their colour.
uniform sampler2DShadow uShadow;     // opaque casters, depth compare
uniform sampler2D uShadowRaw;        // the same depth texture through a non-comparing sampler object
uniform sampler2DShadow uShadowT;    // translucent casters (tinted mode only)
uniform sampler2D uShadowTint;       // their colour (linear), where they are nearest the light
uniform int uTintedShadows;
uniform int uShadowMode;
uniform float uShadowBiasMm;
uniform int uLightPersp;             // 0 orthographic (directional key), 1 perspective (spot)
uniform vec2 uLightNearFar;          // mm
uniform float uLightSize;            // tan(angular radius), or the spot's radius in mm
uniform float uUvPerMm;              // ortho: map uv per mm; persp: uv per mm at 1 mm from the light

const vec2 POISSON[16] = vec2[](
    vec2(-0.94201624, -0.39906216), vec2(0.94558609, -0.76890725), vec2(-0.09418410, -0.92938870),
    vec2(0.34495938, 0.29387760), vec2(-0.91588581, 0.45771432), vec2(-0.81544232, -0.87912464),
    vec2(-0.38277543, 0.27676845), vec2(0.97484398, 0.75648379), vec2(0.44323325, -0.97511554),
    vec2(0.53742981, -0.47373420), vec2(-0.26496911, -0.41893023), vec2(0.79197514, 0.19090188),
    vec2(-0.24188840, 0.99706507), vec2(-0.81409955, 0.91437590), vec2(0.19984126, 0.78641367),
    vec2(0.14383161, -0.14100790));

// Window-space depth (0..1) to millimetres from the light.
float lightDist(float z) {
    float n = uLightNearFar.x, f = uLightNearFar.y;
    if (uLightPersp == 0) return n + z * (f - n);
    float zn = z * 2.0 - 1.0;
    return 2.0 * n * f / (f + n - zn * (f - n));
}

// A bias of uShadowBiasMm, in window-space depth at distance d.
float depthBias(float d) {
    float n = uLightNearFar.x, f = uLightNearFar.y;
    if (uLightPersp == 0) return uShadowBiasMm / (f - n);
    return uShadowBiasMm * f * n / ((f - n) * d * d);
}

float pcf5(vec3 p, float bias) {
    vec2 texel = 1.0 / vec2(textureSize(uShadow, 0));
    float s = 0.0;
    for (int x = -2; x <= 2; x++)
        for (int y = -2; y <= 2; y++)
            s += texture(uShadow, vec3(p.xy + vec2(x, y) * texel * 1.25, p.z - bias));
    return s / 25.0;
}

float pcss(vec3 p, float bias) {
    vec2 texel = 1.0 / vec2(textureSize(uShadow, 0));
    float dR = lightDist(p.z);
    float uvScale = uLightPersp == 0 ? uUvPerMm : uUvPerMm / dR;
    // A random rotation per pixel turns the Poisson disc's banding into fine noise.
    float ang = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453) * 6.2831853;
    mat2 rot = mat2(cos(ang), sin(ang), -sin(ang), cos(ang));

    // Blocker search: anything up to 60 mm nearer the light could shade this point.
    float searchMm = uLightPersp == 0 ? uLightSize * 60.0 : uLightSize * 60.0 / max(dR - 60.0, 1.0);
    float searchUv = clamp(searchMm * uvScale, 2.0 * texel.x, 48.0 * texel.x);
    float sum = 0.0, count = 0.0;
    for (int i = 0; i < 16; i++) {
        float z = texture(uShadowRaw, p.xy + rot * POISSON[i] * searchUv).r;
        if (z < p.z - bias) {
            sum += lightDist(z);
            count += 1.0;
        }
    }
    if (count == 0.0) return 1.0;
    float dB = sum / count;
    float penMm = uLightPersp == 0 ? (dR - dB) * uLightSize : (dR - dB) / dB * uLightSize;
    float penUv = clamp(penMm * uvScale, 1.0 * texel.x, 48.0 * texel.x);
    float s = 0.0;
    for (int i = 0; i < 16; i++) s += texture(uShadow, vec3(p.xy + rot * POISSON[i] * penUv, p.z - bias));
    return s / 16.0;
}

// How much of the key light reaches lp (light-space position), per colour channel.
vec3 shadowColor(vec4 lp) {
    vec3 p = lp.xyz / lp.w * 0.5 + 0.5;
    if (p.x <= 0.0 || p.x >= 1.0 || p.y <= 0.0 || p.y >= 1.0 || p.z >= 1.0) return vec3(1.0);
    float bias = depthBias(lightDist(p.z));
    float o = uShadowMode == 0 ? pcf5(p, bias) : pcss(p, bias);
    vec3 c = vec3(o);
    if (uTintedShadows != 0) {
        vec2 texel = 1.0 / vec2(textureSize(uShadowT, 0));
        float t = 0.0;
        for (int x = -1; x <= 1; x++)
            for (int y = -1; y <= 1; y++)
                t += texture(uShadowT, vec3(p.xy + vec2(x, y) * texel * 1.5, p.z - bias));
        t /= 9.0;
        c *= mix(texture(uShadowTint, p.xy).rgb, vec3(1.0), t);
    }
    return c;
}
