#version 330 core
// PROTOTYPE. Vertex stage for the dice. Like lit.vert, plus the model-space position: every material
// pattern (swirl, flakes) is a solid texture in model space, so it moves with the Die and runs on
// across edges the way a real cast resin does.
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec2 aUv;
layout(location = 3) in vec3 aTangent;   // dP/du
layout(location = 4) in vec3 aBitangent; // dP/dv, v growing image-down

uniform mat4 uModel;
uniform mat4 uView;
uniform mat4 uProj;
uniform mat4 uLightVP;

out vec3 vPos;
out vec3 vN;
out vec3 vT;
out vec3 vB;
out vec2 vUv;
out vec4 vLightPos;
out vec3 vObj;

void main() {
    vec4 wp = uModel * vec4(aPos, 1.0);
    // Models are rigid (rotation + translation), so mat3(uModel) is a valid normal matrix.
    mat3 m = mat3(uModel);
    vPos = wp.xyz;
    vN = m * aNormal;
    vT = m * aTangent;
    vB = m * aBitangent;
    vUv = aUv;
    vObj = aPos;
    vLightPos = uLightVP * wp;
    gl_Position = uProj * uView * wp;
}
