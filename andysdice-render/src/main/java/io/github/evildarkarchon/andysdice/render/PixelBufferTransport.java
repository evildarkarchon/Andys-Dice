package io.github.evildarkarchon.andysdice.render;

import static org.lwjgl.opengl.GL11C.GL_PACK_ALIGNMENT;
import static org.lwjgl.opengl.GL11C.glFlush;
import static org.lwjgl.opengl.GL11C.glPixelStorei;
import static org.lwjgl.opengl.GL11C.glReadPixels;
import static org.lwjgl.opengl.GL12C.GL_BGRA;
import static org.lwjgl.opengl.GL12C.GL_UNSIGNED_INT_8_8_8_8_REV;
import static org.lwjgl.opengl.GL15C.GL_STREAM_READ;
import static org.lwjgl.opengl.GL15C.glBindBuffer;
import static org.lwjgl.opengl.GL15C.glBufferData;
import static org.lwjgl.opengl.GL15C.glDeleteBuffers;
import static org.lwjgl.opengl.GL15C.glGenBuffers;
import static org.lwjgl.opengl.GL15C.glUnmapBuffer;
import static org.lwjgl.opengl.GL21C.GL_PIXEL_PACK_BUFFER;
import static org.lwjgl.opengl.GL30C.GL_MAP_READ_BIT;
import static org.lwjgl.opengl.GL30C.glMapBufferRange;
import static org.lwjgl.opengl.GL32C.GL_ALREADY_SIGNALED;
import static org.lwjgl.opengl.GL32C.GL_CONDITION_SATISFIED;
import static org.lwjgl.opengl.GL32C.GL_SYNC_GPU_COMMANDS_COMPLETE;
import static org.lwjgl.opengl.GL32C.GL_WAIT_FAILED;
import static org.lwjgl.opengl.GL32C.glClientWaitSync;
import static org.lwjgl.opengl.GL32C.glDeleteSync;
import static org.lwjgl.opengl.GL32C.glFenceSync;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * The v1 transport (ADR-0002): {@code glReadPixels} into a ring of three pixel-pack buffers, each fenced, then,
 * once the GPU has finished, a copy into a {@link FramePresenter} buffer for JavaFX.
 *
 * <p>Pixels are read as {@code GL_BGRA} / {@code GL_UNSIGNED_INT_8_8_8_8_REV}, which is byte for byte JavaFX's
 * {@code BYTE_BGRA_PRE} on every little-endian machine, so there is no swizzle. GL's rows run bottom-up, so the
 * copy into the JavaFX buffer reverses them; it costs the same as a straight copy, and JavaFX gets an upright
 * image with no transform on the view.
 *
 * <p>Thread contract: as {@link FrameTransport}, render thread only. The presenter it feeds is shared with the
 * JavaFX thread and is the only thing the two threads touch.
 */
final class PixelBufferTransport implements FrameTransport {

    /** Deep enough that the GPU can work on one readback while another is copied out (render.md, 2-3). */
    static final int RING = 3;

    /** One pixel-pack buffer of the ring and the readback it holds, if any. */
    private static final class Slot {
        final int buffer;
        long capacity;
        long fence;
        int width;
        int height;

        Slot(int buffer) {
            this.buffer = buffer;
        }
    }

    private final FramePresenter presenter;
    private final Deque<Slot> idle = new ArrayDeque<>();
    // Oldest first. Fences signal in submission order, so only the head ever needs waiting on.
    private final Deque<Slot> inFlight = new ArrayDeque<>();
    private volatile Runnable wakeUp = () -> {};
    private boolean open;

    /**
     * Creates the transport feeding {@code presenter}.
     *
     * @param presenter the JavaFX end of the bridge
     */
    PixelBufferTransport(FramePresenter presenter) {
        this.presenter = presenter;
    }

    /** Called by the presenter, on the JavaFX thread, when it gives a buffer back. */
    void bufferFreed() {
        wakeUp.run();
    }

    @Override
    public void open(Runnable wakeUp) {
        RenderThread.checkRenderThread();
        this.wakeUp = wakeUp;
        for (int i = 0; i < RING; i++) {
            idle.add(new Slot(glGenBuffers()));
        }
        open = true;
    }

    @Override
    public boolean canSubmit() {
        RenderThread.checkRenderThread();
        return open && !idle.isEmpty();
    }

    @Override
    public void submit(int width, int height) {
        RenderThread.checkRenderThread();
        if (!canSubmit()) {
            throw new IllegalStateException("every pixel-pack buffer is in flight");
        }
        Slot slot = idle.pop();
        long bytes = (long) width * height * 4;
        glBindBuffer(GL_PIXEL_PACK_BUFFER, slot.buffer);
        if (slot.capacity != bytes) {
            // Only on a size change: the buffer keeps its storage from frame to frame otherwise.
            glBufferData(GL_PIXEL_PACK_BUFFER, bytes, GL_STREAM_READ);
            slot.capacity = bytes;
        }
        glPixelStorei(GL_PACK_ALIGNMENT, 4);
        // With a pack buffer bound, the last argument is an offset into it, and the call returns at once.
        glReadPixels(0, 0, width, height, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, 0L);
        glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
        slot.fence = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        slot.width = width;
        slot.height = height;
        // Flushed so the fence is sure to signal without anyone waiting on it with the flush bit.
        glFlush();
        inFlight.add(slot);
    }

    @Override
    public boolean pump() {
        RenderThread.checkRenderThread();
        // Only the newest finished readback is worth showing; older finished ones are dropped uncopied.
        Slot newest = null;
        while (!inFlight.isEmpty() && finished(inFlight.peek())) {
            if (newest != null) {
                recycle(newest);
            }
            newest = inFlight.pop();
        }
        if (newest != null) {
            FramePresenter.Frame frame = presenter.acquire(newest.width, newest.height);
            if (frame == null) {
                // JavaFX hasn't given a buffer back yet; keep the readback and try at the next pump.
                inFlight.addFirst(newest);
                return true;
            }
            copyOut(newest, frame);
            recycle(newest);
            presenter.publish(frame);
        }
        return !inFlight.isEmpty();
    }

    @Override
    public void close() {
        RenderThread.checkRenderThread();
        open = false;
        for (Slot slot : inFlight) {
            glDeleteSync(slot.fence);
            glDeleteBuffers(slot.buffer);
        }
        for (Slot slot : idle) {
            glDeleteBuffers(slot.buffer);
        }
        inFlight.clear();
        idle.clear();
    }

    /** Whether the GPU has finished the slot's readback. A finished slot's fence has already been deleted. */
    private static boolean finished(Slot slot) {
        if (slot.fence == 0) {
            return true;
        }
        int status = glClientWaitSync(slot.fence, 0, 0);
        if (status == GL_WAIT_FAILED) {
            throw new IllegalStateException("glClientWaitSync failed on a readback fence");
        }
        if (status == GL_ALREADY_SIGNALED || status == GL_CONDITION_SATISFIED) {
            glDeleteSync(slot.fence);
            slot.fence = 0;
            return true;
        }
        return false;
    }

    /** Copies a finished readback into a JavaFX buffer, turning GL's bottom-up rows top-down. */
    private static void copyOut(Slot slot, FramePresenter.Frame frame) {
        int stride = slot.width * 4;
        glBindBuffer(GL_PIXEL_PACK_BUFFER, slot.buffer);
        ByteBuffer mapped = glMapBufferRange(GL_PIXEL_PACK_BUFFER, 0, slot.capacity, GL_MAP_READ_BIT);
        if (mapped == null) {
            glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
            throw new IllegalStateException("couldn't map a readback buffer");
        }
        ByteBuffer pixels = frame.pixels();
        for (int row = 0; row < slot.height; row++) {
            pixels.put(row * stride, mapped, (slot.height - 1 - row) * stride, stride);
        }
        glUnmapBuffer(GL_PIXEL_PACK_BUFFER);
        glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
    }

    private void recycle(Slot slot) {
        idle.add(slot);
    }
}
