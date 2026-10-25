#version 330 core
// PROTOTYPE. Shared vertex stage for every lit object (dice, felt, wood walls).
//
// Variant C pushes the dense die mesh into real recessed geometry here: each flat-face vertex moves
// inward along its face normal by the visible depth times the engraving profile. Fillet and cap
// vertices sample the atlas's blank cell (profile 0), so they never move.
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec2 aUv;
layout(location = 3) in vec3 aTangent;   // dP/du
layout(location = 4) in vec3 aBitangent; // dP/dv, v growing image-down

uniform mat4 uModel;
uniform mat4 uView;
uniform mat4 uProj;
uniform mat4 uLightVP;
uniform sampler2D uMask;   // g = engraving profile (0 face, 1 recess floor)
uniform float uDisplace;   // mm; > 0 only when drawing the dense mesh
uniform float uDisplaceLod; // atlas mip that matches the mesh's vertex spacing

out vec3 vPos;
out vec3 vN;
out vec3 vT;
out vec3 vB;
out vec2 vUv;
out vec4 vLightPos;

void main() {
    vec3 p = aPos;
    if (uDisplace > 0.0) p -= aNormal * uDisplace * textureLod(uMask, aUv, uDisplaceLod).g;
    vec4 wp = uModel * vec4(p, 1.0);
    // Models are rigid (rotation + translation), so mat3(uModel) is a valid normal matrix.
    mat3 m = mat3(uModel);
    vPos = wp.xyz;
    vN = m * aNormal;
    vT = m * aTangent;
    vB = m * aBitangent;
    vUv = aUv;
    vLightPos = uLightVP * wp;
    gl_Position = uProj * uView * wp;
}
