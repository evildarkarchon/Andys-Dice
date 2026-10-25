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
