// PROTOTYPE. The light rig, shared by the tray and the dice. The key light casts the shadows and is
// either directional (uKeyPos.w == 0: xyz points towards the light) or a spot (uKeyPos.xyz is where it
// hangs, in mm). A spot is normalised so it delivers uKeyColor at uKeyRef mm, roughly the tray centre.
uniform vec4 uKeyPos;
uniform vec3 uKeyColor;
uniform vec3 uSpotDir;   // spot only: the way it points
uniform vec2 uSpotCos;   // spot only: cosines of the outer and inner cone half-angles
uniform float uKeyRef;
uniform vec3 uFillDir;
uniform vec3 uFillColor;

// Radiance arriving at pos from the key light; L is the unit direction towards it.
vec3 keyLight(vec3 pos, out vec3 L) {
    if (uKeyPos.w == 0.0) {
        L = uKeyPos.xyz;
        return uKeyColor;
    }
    vec3 d = uKeyPos.xyz - pos;
    float dist = length(d);
    L = d / dist;
    float cone = smoothstep(uSpotCos.x, uSpotCos.y, dot(-L, uSpotDir));
    return uKeyColor * (uKeyRef * uKeyRef) / (dist * dist) * cone;
}
