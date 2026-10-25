package io.github.evildarkarchon.andysdice.render;

import org.lwjgl.system.Platform;

/**
 * A native OpenGL core context, current on the thread that created it, with no visible surface: the renderer
 * draws into its own framebuffer objects (ADR-0002). Each platform makes it natively, never through GLFW, since
 * GLFW wants the main thread on macOS and JavaFX owns it: a hidden window plus WGL on Windows, with CGL and
 * EGL/GLX left possible behind this interface.
 *
 * <p>Thread contract: create, use, {@link #pumpEvents()} and {@link #close()} on one thread only, the render
 * thread.
 */
interface GlContext extends AutoCloseable {

    /**
     * Creates a core context of at least {@code required} and makes it current on the calling thread, with
     * LWJGL's {@code GLCapabilities} set for it.
     *
     * @param required the lowest acceptable core version; the app always asks for {@link GlVersion#FLOOR}
     * @return the current context
     * @throws GlUnavailableException if no such context can be made; the message names the requirement and what
     *     the driver reported
     */
    static GlContext create(GlVersion required) {
        if (Platform.get() == Platform.WINDOWS) {
            return WglContext.create(required);
        }
        throw new GlUnavailableException(required,
                "native context creation for " + Platform.get().getName() + " (CGL, EGL or GLX) isn't built yet",
                null, null);
    }

    /**
     * What the driver reports about this context.
     *
     * @return its {@code GL_RENDERER}, {@code GL_VENDOR} and {@code GL_VERSION}
     */
    GlDriver driver();

    /**
     * Handles any window-system messages queued for the context's hidden window. The render thread calls it
     * now and then so the window never looks hung to the system; a no-op where there is no window.
     */
    void pumpEvents();

    /** Releases the context and its window. Called once, at exit, on the thread that created it. */
    @Override
    void close();
}
