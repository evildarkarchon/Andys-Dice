#version 330 core
// Bakes one z-slice of a Dice Set's swirl field into its 3D texture (ADR-0009). The texture spans exactly one
// period of the field, so it wraps seamlessly with GL_REPEAT.
#include "noise.glsl"

out vec4 fragColour;

uniform float uSlice;     // z texel index of this layer
uniform float uSize;      // texels per side
uniform float uPeriod;    // noise cells per period
uniform float uWarp;
uniform float uBands;

void main() {
    // gl_FragCoord.xy is already at texel centres; the slice's centre is half a texel in.
    vec3 t = vec3(gl_FragCoord.xy, uSlice + 0.5) / uSize;
    fragColour = vec4(swirlField(t * uPeriod, uPeriod, uWarp, uBands), 0.0, 0.0, 1.0);
}
