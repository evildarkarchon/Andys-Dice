// The reflection environment, evaluated analytically (no cube map): what glossy, metal and translucent Dice and the
// varnished wood see. One room per Light Rig (ticket #45, ADR-0010), from the tray prototype's env.glsl: the studio's
// grey room and two softboxes (where the Studio key and fill lights are), the lamp's dark warm room with its bright
// opening overhead, and the window's cool room with one big window on the key side and the sky blue overhead. The
// lamp and window sit where the key light is, so this reads light.glsl's uniforms. Roughness widens the bright
// shapes, a cheap stand-in for prefiltering.

#include "light.glsl"

uniform int uEnvironment;   // the room: 0 studio, 1 the lamp's, 2 the window's (LightRig.room())

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
    if (uEnvironment == 1) {
        // A dark room with warm walls; the lamp's bright opening hangs overhead, seen from the Tray's middle.
        vec3 col = mix(vec3(0.004, 0.003, 0.002), vec3(0.03, 0.022, 0.015), smoothstep(-0.1, 0.7, y));
        col += softbox(d, normalize(uKeyPosition.xyz), vec2(0.07, 0.07), w) * vec3(40.0, 29.0, 18.0);
        return col;
    }
    if (uEnvironment == 2) {
        // A cool room lit by one big window on the key side, sky blue overhead.
        vec3 col = mix(vec3(0.025, 0.028, 0.032), vec3(0.10, 0.11, 0.13), smoothstep(-0.2, 0.8, y));
        col += vec3(0.05, 0.08, 0.13) * smoothstep(0.5, 1.0, y);
        col += softbox(d, uKeyPosition.xyz, vec2(0.6, 0.42), w) * vec3(6.0, 6.6, 7.5);
        return col;
    }
    // A dim green floor below (the felt), a grey room around, a lighter ceiling.
    vec3 col = mix(vec3(0.012, 0.035, 0.02), vec3(0.16, 0.15, 0.14), smoothstep(-0.15, 0.25, y));
    col = mix(col, vec3(0.30, 0.31, 0.34), smoothstep(0.3, 0.95, y));
    col += softbox(d, normalize(vec3(-0.45, 0.85, -0.35)), vec2(0.38, 0.24), w) * vec3(7.0, 6.6, 6.0);
    col += softbox(d, normalize(vec3(0.7, 0.45, 0.5)), vec2(0.25, 0.42), w) * vec3(1.2, 1.4, 1.7);
    return col;
}
