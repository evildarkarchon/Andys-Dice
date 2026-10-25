#version 330 core
// PROTOTYPE. Vertex stage for the tray, the walls and the table (all static, model = identity).
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

void main() {
    vec4 wp = uModel * vec4(aPos, 1.0);
    mat3 m = mat3(uModel);
    vPos = wp.xyz;
    vN = m * aNormal;
    vT = m * aTangent;
    vB = m * aBitangent;
    vUv = aUv;
    vLightPos = uLightVP * wp;
    gl_Position = uProj * uView * wp;
}
