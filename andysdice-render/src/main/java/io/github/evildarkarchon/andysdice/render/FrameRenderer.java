package io.github.evildarkarchon.andysdice.render;

/**
 * What the render thread draws: the scene's GL code. The render thread owns the context, the render target
 * and the transport; a {@code FrameRenderer} only draws into whatever framebuffer is bound.
 *
 * <p>Thread contract: every method is called on the render thread with the context current, and nowhere else.
 */
public interface FrameRenderer {

    /** Creates the scene's GL objects (shaders, meshes, textures). Called once, before the first frame. */
    void init();

    /**
     * Draws one frame into the bound framebuffer. The viewport already covers it, and depth testing is the
     * renderer's to set.
     *
     * @param width the framebuffer's width in pixels
     * @param height the framebuffer's height in pixels
     */
    void render(int width, int height);

    /** Releases the scene's GL objects. Called once, at exit. */
    void dispose();
}
