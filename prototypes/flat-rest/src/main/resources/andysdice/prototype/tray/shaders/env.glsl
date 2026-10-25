// PROTOTYPE. The reflection environment, one per light rig, evaluated analytically (no cube map): what
// glossy dice, metal and the varnished wood see. Needs light.glsl first (the lamp and window sit where
// the key light is). Roughness widens the bright shapes, a cheap stand-in for prefiltering.
uniform int uEnv;   // 0 studio, 1 lamp room, 2 window

float softbox(vec3 d, vec3 c, vec2 halfSize, float w) {
    float cd = dot(d, c);
    if (cd <= 0.0) return 0.0;
    vec3 t = normalize(cross(c, vec3(0.0, 1.0, 0.001)));
    vec3 b = cross(t, c);
    vec2 p = vec2(dot(d, t), dot(d, b)) / cd;
    vec2 q = abs(p) - halfSize;
    float dist = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0);
    float energy = (halfSize.x * halfSize.y) / ((halfSize.x + w) * (halfSize.y + w));
    return (1.0 - smoothstep(-w, w, dist)) * energy;
}

vec3 env(vec3 d, float rough) {
    float y = d.y;
    float w = 0.02 + rough * rough * 1.2;
    if (uEnv == 1) {
        // A dark room with warm walls; the lamp's bright opening hangs overhead.
        vec3 col = mix(vec3(0.004, 0.003, 0.002), vec3(0.03, 0.022, 0.015), smoothstep(-0.1, 0.7, y));
        col += softbox(d, normalize(uKeyPos.xyz), vec2(0.07, 0.07), w) * vec3(40.0, 29.0, 18.0);
        return col;
    }
    if (uEnv == 2) {
        // A cool room lit by one big window on the key side, sky blue overhead.
        vec3 col = mix(vec3(0.025, 0.028, 0.032), vec3(0.10, 0.11, 0.13), smoothstep(-0.2, 0.8, y));
        col += vec3(0.05, 0.08, 0.13) * smoothstep(0.5, 1.0, y);
        col += softbox(d, uKeyPos.xyz, vec2(0.6, 0.42), w) * vec3(6.0, 6.6, 7.5);
        return col;
    }
    vec3 col = mix(vec3(0.012, 0.035, 0.02), vec3(0.16, 0.15, 0.14), smoothstep(-0.15, 0.25, y));
    col = mix(col, vec3(0.30, 0.31, 0.34), smoothstep(0.3, 0.95, y));
    col += softbox(d, normalize(vec3(-0.45, 0.85, -0.35)), vec2(0.38, 0.24), w) * vec3(7.0, 6.6, 6.0);
    col += softbox(d, normalize(vec3(0.7, 0.45, 0.5)), vec2(0.25, 0.42), w) * vec3(1.2, 1.4, 1.7);
    return col;
}
