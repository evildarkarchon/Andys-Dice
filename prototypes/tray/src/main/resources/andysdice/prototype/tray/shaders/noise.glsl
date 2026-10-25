// PROTOTYPE. Periodic 3D value noise and the swirl field, shared (via the renderer's #include) by
// die.frag, which evaluates it live (swirl source "shader"), and bake.frag, which writes the very
// same field into a 3D texture (swirl source "baked").
//
// The lattice is hashed modulo `period` cells, so the field repeats every `period` cells. That is what
// lets one period of it live in a 3D texture that wraps with GL_REPEAT, and lets per-Die variation be a
// plain offset into the field.

float hash13(vec3 p) {
    p = fract(p * 0.1031);
    p += dot(p, p.zyx + 31.32);
    return fract((p.x + p.y) * p.z);
}

float vnoise(vec3 x, float period) {
    vec3 i = floor(x), f = fract(x);
    vec3 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
    vec3 a = mod(i, period), b = mod(i + 1.0, period);
    float n000 = hash13(a);
    float n100 = hash13(vec3(b.x, a.y, a.z));
    float n010 = hash13(vec3(a.x, b.y, a.z));
    float n110 = hash13(vec3(b.x, b.y, a.z));
    float n001 = hash13(vec3(a.x, a.y, b.z));
    float n101 = hash13(vec3(b.x, a.y, b.z));
    float n011 = hash13(vec3(a.x, b.y, b.z));
    float n111 = hash13(b);
    return mix(mix(mix(n000, n100, u.x), mix(n010, n110, u.x), u.y),
               mix(mix(n001, n101, u.x), mix(n011, n111, u.x), u.y), u.z);
}

// Four octaves; each doubles the frequency and the period, so the sum keeps the base period.
float fbm(vec3 x, float period) {
    float v = 0.0, a = 0.5;
    for (int o = 0; o < 4; o++) {
        v += a * vnoise(x, period);
        x *= 2.0;
        period *= 2.0;
        a *= 0.5;
    }
    return v / 0.9375;
}

// The swirl field in [0, 1] at lattice coordinate x (one unit = one noise cell). Domain-warped fBm:
// three fBm lookups displace a fourth, which reads as two resins folded into each other. bands > 0
// runs the result through a sine, turning soft clouds into ribbons. The fBm sum clusters around 0.5,
// so the result is stretched to use the whole range before the Dice Set's threshold is applied.
float swirlField(vec3 x, float period, float warp, float bands) {
    vec3 q = vec3(fbm(x, period), fbm(x + vec3(5.2, 1.3, 7.1), period), fbm(x + vec3(2.7, 9.2, 3.4), period));
    float f = fbm(x + warp * 2.0 * (q - 0.5), period);
    f = clamp((f - 0.5) * 2.4 + 0.5, 0.0, 1.0);
    if (bands > 0.0) f = 0.5 + 0.5 * sin(6.2831853 * bands * f);
    return f;
}
