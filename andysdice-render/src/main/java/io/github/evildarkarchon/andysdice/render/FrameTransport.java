package io.github.evildarkarchon.andysdice.render;

/**
 * The transport seam (ADR-0002): how a finished frame gets from the render thread's framebuffer to JavaFX.
 * The v1 transport reads frames back through a ring of pixel-pack buffers into a double-buffered
 * {@code PixelBuffer} ({@link FrameView#transport()}); a zero-copy one could replace it without touching the
 * renderer.
 *
 * <p>Thread contract: every method is called on the render thread, with the GL context current, and throws
 * {@link IllegalStateException} if called from any other thread. Implementations may hand frames to the JavaFX
 * thread however they like, but must never block on it.
 */
public interface FrameTransport {

    /**
     * Prepares the transport's GL objects. Called once, before any other method.
     *
     * @param wakeUp may be called from any thread when {@link #pump()} has new work, for example when JavaFX
     *     gives a buffer back, so the render thread doesn't wait out its poll interval
     */
    void open(Runnable wakeUp);

    /**
     * Whether {@link #submit} can take a frame now. When it can't, the render thread keeps the frame dirty and
     * tries again after {@link #pump()}.
     *
     * @return {@code true} if a frame can be submitted
     */
    boolean canSubmit();

    /**
     * Starts delivering the frame in the bound read framebuffer's colour attachment 0. Must not wait for the
     * GPU: the copy completes asynchronously, through {@link #pump()}.
     *
     * @param width the frame's width in pixels
     * @param height the frame's height in pixels
     * @throws IllegalStateException if called off the render thread, or when {@link #canSubmit()} is false
     */
    void submit(int width, int height);

    /**
     * Moves submitted frames along: finished copies go to JavaFX. Never blocks.
     *
     * @return {@code true} while any frame is still on its way, so the render thread should call again soon
     */
    boolean pump();

    /** Releases the transport's GL objects. Called once, at exit. */
    void close();
}
