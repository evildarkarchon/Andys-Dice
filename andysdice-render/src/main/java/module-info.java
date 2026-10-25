/**
 * Mesh and numeral-atlas generation, the LWJGL GL 3.3 core renderer and its shaders, native context creation
 * and the FBO/PBO bridge behind the transport seam, plus the gallery and {@code --bench} (spec §5, ADR-0002).
 *
 * <p>LWJGL makes the native calls, so the build's native-access option set grants {@code org.lwjgl} (and
 * {@code org.lwjgl.opengl}, whose GL entry points are JNI natives) rather than this module. Tests that need a
 * GL 3.3 context carry the JUnit tag {@code gl}.
 */
module io.github.evildarkarchon.andysdice.render {
    // Transitive: DiceScene's API takes core's Die Kinds and poses (and, through core, JOML's vectors).
    requires transitive io.github.evildarkarchon.andysdice.core;
    requires org.lwjgl;
    requires org.lwjgl.opengl;
    // FrameView is a JavaFX Region, so JavaFX's graphics module is part of this module's API.
    requires transitive javafx.graphics;
    // The gallery writes its stills with ImageIO; the numeral atlas (#41) rasterises with java.awt.geom.
    requires java.desktop;

    exports io.github.evildarkarchon.andysdice.render;
}
