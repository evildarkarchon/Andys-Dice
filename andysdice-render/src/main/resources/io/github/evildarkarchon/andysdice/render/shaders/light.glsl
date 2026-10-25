// The chosen Light Rig's lights (ticket #45, ADR-0010), shared by the Tray and the Dice: the tray prototype's
// light.glsl. The key light casts the shadows and is either directional (uKeyPosition.w == 0: xyz points toward it)
// or a spot (xyz is where it hangs, in mm). A spot delivers uKeyColour at uKeyReference mm, about the Tray's middle,
// and falls off with the square of distance around that. The fill casts nothing.
//
// Both env.glsl and the shaders that use it include this, so it is guarded against being spliced in twice.
#ifndef LIGHT_GLSL
#define LIGHT_GLSL

uniform vec4 uKeyPosition;
uniform vec3 uKeyColour;      // linear RGB
uniform vec3 uSpotDirection;  // spot only: the unit direction it points
uniform vec2 uSpotCos;        // spot only: cosines of the outer and inner cone half-angles
uniform float uKeyReference;  // spot only: the distance, in mm, at which it delivers uKeyColour
uniform vec3 uFillDirection;  // toward the fill light, normalised
uniform vec3 uFillColour;     // linear RGB

// Radiance arriving at world point pos from the key light; L is the unit direction toward it.
vec3 keyLight(vec3 pos, out vec3 L) {
    if (uKeyPosition.w == 0.0) {
        L = uKeyPosition.xyz;
        return uKeyColour;
    }
    vec3 d = uKeyPosition.xyz - pos;
    float dist = length(d);
    L = d / dist;
    float cone = smoothstep(uSpotCos.x, uSpotCos.y, dot(-L, uSpotDirection));
    return uKeyColour * (uKeyReference * uKeyReference) / (dist * dist) * cone;
}

#endif
