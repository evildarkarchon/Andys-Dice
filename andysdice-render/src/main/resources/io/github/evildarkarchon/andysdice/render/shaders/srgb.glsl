// Linear RGB to sRGB-encoded, the exact piecewise curve. The render target is plain RGBA8 and JavaFX shows
// its bytes as sRGB, so shaders encode their own output.
vec3 linearToSrgb(vec3 c) {
    c = clamp(c, 0.0, 1.0);
    return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, step(0.0031308, c));
}
