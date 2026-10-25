package io.github.evildarkarchon.andysdice.render;

import static org.lwjgl.opengl.GL11C.GL_PACK_ALIGNMENT;
import static org.lwjgl.opengl.GL11C.glPixelStorei;
import static org.lwjgl.opengl.GL11C.glReadPixels;
import static org.lwjgl.opengl.GL12C.GL_BGRA;
import static org.lwjgl.opengl.GL12C.GL_UNSIGNED_INT_8_8_8_8_REV;
import static org.lwjgl.opengl.GL15C.glBindBuffer;
import static org.lwjgl.opengl.GL21C.GL_PIXEL_PACK_BUFFER;

import java.nio.IntBuffer;
import java.util.concurrent.CompletableFuture;
import org.lwjgl.BufferUtils;

/**
 * A transport for stills instead of a window: when asked, it reads the next frame back synchronously into a
 * {@link Still}. The gallery and the renderer's {@code gl} tests use it; it stalls the GPU on every capture,
 * which is fine for a dev tool and wrong for the app (which uses {@link FrameView#transport()}).
 *
 * <p>Frames nobody asked for are dropped. Thread contract: the {@link FrameTransport} methods run on the render
 * thread; {@link #take} may be called from any thread.
 */
final class CaptureTransport implements FrameTransport {

    // Render thread only: the capture the next submitted frame completes.
    private CompletableFuture<Still> pending;

    /** Creates a transport with nothing to capture yet. */
    CaptureTransport() {}

    /**
     * Changes the scene and captures the first frame drawn after the change. Any thread; never blocks.
     *
     * <p>The change, the arming of the capture and the frame request all run in one render-thread message, so
     * the captured frame is drawn after the change: a frame already requested (by a resize, say) can't be
     * captured in its place, and the request can't be used up by a frame drawn before the capture was armed.
     *
     * @param renderer the render thread this transport was started with
     * @param change what to do to the scene first, on the render thread
     * @return the still, or the exception the change or the capture failed with
     */
    CompletableFuture<Still> take(RenderThread renderer, Runnable change) {
        return renderer.submit(() -> {
            if (pending != null) {
                throw new IllegalStateException("a still is already being taken");
            }
            change.run();
            CompletableFuture<Still> still = new CompletableFuture<>();
            pending = still;
            renderer.requestFrame();
            return still;
        }).thenCompose(still -> still);
    }

    @Override
    public void open(Runnable wakeUp) {
        RenderThread.checkRenderThread();
    }

    @Override
    public boolean canSubmit() {
        RenderThread.checkRenderThread();
        return true;
    }

    @Override
    public void submit(int width, int height) {
        RenderThread.checkRenderThread();
        if (pending == null) {
            return;
        }
        CompletableFuture<Still> still = pending;
        pending = null;
        try {
            still.complete(read(width, height));
        } catch (RuntimeException e) {
            still.completeExceptionally(e);
        }
    }

    @Override
    public boolean pump() {
        RenderThread.checkRenderThread();
        return false;
    }

    @Override
    public void close() {
        RenderThread.checkRenderThread();
        if (pending != null) {
            pending.completeExceptionally(new IllegalStateException("the render thread stopped"));
            pending = null;
        }
    }

    /** Reads the bound read framebuffer into a still, flipping GL's bottom-up rows. Waits for the GPU. */
    private static Still read(int width, int height) {
        IntBuffer buffer = BufferUtils.createIntBuffer(width * height);
        // Into client memory, not a pixel-pack buffer; rows of 4-byte pixels need no padding.
        glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
        glPixelStorei(GL_PACK_ALIGNMENT, 4);
        // BGRA as 8_8_8_8_REV packs each pixel into one int as 0xAARRGGBB, which is ARGB with no swizzle.
        glReadPixels(0, 0, width, height, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, buffer);
        int[] pixels = new int[width * height];
        for (int row = 0; row < height; row++) {
            buffer.get((height - 1 - row) * width, pixels, row * width, width);
        }
        return new Still(width, height, pixels);
    }
}
