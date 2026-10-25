/**
 * Mesh and numeral-atlas generation, the LWJGL GL 3.3 core renderer and its shaders, native context creation
 * and the FBO/PBO bridge behind the transport seam, plus the gallery and {@code --bench} (spec §5, ADR-0002).
 *
 * <p>LWJGL makes the native calls, so the build's native-access option set grants {@code org.lwjgl} rather
 * than this module. Tests that need a GL 3.3 context carry the JUnit tag {@code gl}.
 */
module io.github.evildarkarchon.andysdice.render {
    requires io.github.evildarkarchon.andysdice.core;
}
