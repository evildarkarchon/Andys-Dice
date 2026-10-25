// The reflection environment, evaluated analytically (no cube map): what glossy and metal Dice see, from the tray
// prototype's env.glsl. Only its studio room for now, with its two softboxes where DiceScene puts the studio key
// and fill lights; ticket #45 adds the Lamp and Window rooms with the Light Rigs. Roughness widens the bright
// shapes, a cheap stand-in for prefiltering.

float softbox(vec3 d, vec3 c, vec2 halfSize, float w) {
    float cd = dot(d, c);
    if (cd <= 0.0) return 0.0;
    vec3 t = normalize(cross(c, vec3(0.0, 1.0, 0.001)));
    vec3 b = cross(t, c);
    vec2 p = vec2(dot(d, t), dot(d, b)) / cd;
    vec2 q = abs(p) - halfSize;
    float dist = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0);
    // Widening a box keeps its energy: the wider it blurs, the dimmer it gets.
    float energy = (halfSize.x * halfSize.y) / ((halfSize.x + w) * (halfSize.y + w));
    return (1.0 - smoothstep(-w, w, dist)) * energy;
}

// Radiance from direction d (unit, world), blurred for a surface of this roughness.
vec3 env(vec3 d, float rough) {
    float y = d.y;
    float w = 0.02 + rough * rough * 1.2;
    // A dim green floor below (the felt), a grey room around, a lighter ceiling.
    vec3 col = mix(vec3(0.012, 0.035, 0.02), vec3(0.16, 0.15, 0.14), smoothstep(-0.15, 0.25, y));
    col = mix(col, vec3(0.30, 0.31, 0.34), smoothstep(0.3, 0.95, y));
    col += softbox(d, normalize(vec3(-0.45, 0.85, -0.35)), vec2(0.38, 0.24), w) * vec3(7.0, 6.6, 6.0);
    col += softbox(d, normalize(vec3(0.7, 0.45, 0.5)), vec2(0.25, 0.42), w) * vec3(1.2, 1.4, 1.7);
    return col;
}
