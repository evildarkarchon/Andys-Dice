#version 330 core
// PROTOTYPE. Bakes one z-slice of a Dice Set's swirl field into a 3D texture (swirl source "baked").
// The texture spans exactly one period of the field, so it wraps seamlessly with GL_REPEAT.
#include "noise.glsl"

out vec4 fragColor;

uniform float uSlice;     // z texel index of this layer
uniform float uSize;      // texels per side
uniform float uPeriod;    // noise cells per period
uniform float uWarp;
uniform float uBands;

void main() {
    vec3 t = (vec3(gl_FragCoord.xy, uSlice + 0.5)) / uSize;   // gl_FragCoord.xy is already at texel centres
    fragColor = vec4(swirlField(t * uPeriod, uPeriod, uWarp, uBands), 0.0, 0.0, 1.0);
}
