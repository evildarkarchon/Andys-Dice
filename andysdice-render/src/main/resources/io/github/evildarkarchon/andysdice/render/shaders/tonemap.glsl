// The prototypes' tone map: Narkowicz's ACES fit, then a 2.2 gamma. The lights are brighter than 1 (the studio key
// is 3), so linear light is compressed rather than clipped. Translucency (#43) inverts exactly this curve to relight
// the opaque pass it refracts, so anything drawn under the dice must use it too.

vec3 aces(vec3 x) {
    return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0);
}

// Linear scene light to the bytes the render target holds (shown by JavaFX as sRGB).
vec3 toneMap(vec3 linear) {
    return pow(aces(linear), vec3(1.0 / 2.2));
}

// The inverse of toneMap(), from the bytes back to linear light, so that translucent Dice (#43) can absorb and tint
// the opaque pass they refract as light rather than as display values. Undo the gamma, then take the positive root
// of y (2.43x^2 + 0.59x + 0.14) = 2.51x^2 + 0.03x. ACES saturates near 1, where the root runs off to infinity, so
// the input is capped just below that: the brightest pixels come back bright but finite.
vec3 unToneMap(vec3 display) {
    vec3 y = min(pow(display, vec3(2.2)), vec3(0.985));
    vec3 a = 2.43 * y - 2.51;
    vec3 b = 0.59 * y - 0.03;
    vec3 c = 0.14 * y;
    return (-b - sqrt(max(b * b - 4.0 * a * c, 0.0))) / (2.0 * a);
}
