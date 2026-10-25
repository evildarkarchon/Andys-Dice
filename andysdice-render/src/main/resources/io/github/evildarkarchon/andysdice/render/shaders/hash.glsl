// A cheap 3D -> 1D hash in [0, 1) (Dave Hoskins' hash13), from the materials prototype. The swirl's noise lattice
// (noise.glsl) and the uber-shader's flakes (die.frag) both draw from it.
float hash13(vec3 p) {
    p = fract(p * 0.1031);
    p += dot(p, p.zyx + 31.32);
    return fract((p.x + p.y) * p.z);
}
