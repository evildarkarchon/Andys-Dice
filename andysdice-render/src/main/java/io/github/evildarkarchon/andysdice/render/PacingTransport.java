package io.github.evildarkarchon.andysdice.render;

import static org.lwjgl.opengl.GL11C.glFlush;
import static org.lwjgl.opengl.GL32C.GL_ALREADY_SIGNALED;
import static org.lwjgl.opengl.GL32C.GL_CONDITION_SATISFIED;
import static org.lwjgl.opengl.GL32C.GL_SYNC_GPU_COMMANDS_COMPLETE;
import static org.lwjgl.opengl.GL32C.GL_WAIT_FAILED;
import static org.lwjgl.opengl.GL32C.glClientWaitSync;
import static org.lwjgl.opengl.GL32C.glDeleteSync;
import static org.lwjgl.opengl.GL32C.glFenceSync;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * A transport whose frames go nowhere, for the standalone GPU bench: it reads nothing back, but fences each frame and
 * takes a new one only while fewer than {@value #IN_FLIGHT} are still on the GPU, as {@link PixelBufferTransport}'s
 * ring paces the app.
 *
 * <p>Without that bound nothing ever makes the render thread wait for the GPU, so it queues frames far faster than
 * the GPU draws them, and each frame's timer query result comes back seconds late (the bench saw a dozen results in
 * 30 s). With it, frames still go back to back and results arrive a frame or two after their frame.
 *
 * <p>Thread contract: as {@link FrameTransport}, render thread only.
 */
final class PacingTransport implements FrameTransport {

    /** Frames that may be on the GPU at once: one being drawn, one queued behind it. */
    static final int IN_FLIGHT = 2;

    // Oldest first; fences signal in submission order.
    private final Deque<Long> fences = new ArrayDeque<>();

    /** Creates the transport. */
    PacingTransport() {}

    @Override
    public void open(Runnable wakeUp) {
        RenderThread.checkRenderThread();
    }

    @Override
    public boolean canSubmit() {
        RenderThread.checkRenderThread();
        return fences.size() < IN_FLIGHT;
    }

    @Override
    public void submit(int width, int height) {
        RenderThread.checkRenderThread();
        fences.add(glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0));
        // Flushed so the fence (and the frame's timer query) reaches the GPU without anyone waiting on it.
        glFlush();
    }

    @Override
    public boolean pump() {
        RenderThread.checkRenderThread();
        while (!fences.isEmpty()) {
            long fence = fences.peek();
            int status = glClientWaitSync(fence, 0, 0);
            if (status == GL_WAIT_FAILED) {
                throw new IllegalStateException("glClientWaitSync failed on a bench frame's fence");
            }
            if (status != GL_ALREADY_SIGNALED && status != GL_CONDITION_SATISFIED) {
                break;
            }
            glDeleteSync(fence);
            fences.pop();
        }
        // While frames are on the GPU, the render thread polls again within a millisecond.
        return !fences.isEmpty();
    }

    @Override
    public void close() {
        RenderThread.checkRenderThread();
        fences.forEach(fence -> glDeleteSync(fence));
        fences.clear();
    }
}
