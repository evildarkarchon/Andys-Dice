package io.github.evildarkarchon.andysdice.render;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicLong;
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelBuffer;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;

/**
 * The JavaFX end of the frame bridge (ADR-0002): a ring of three {@code BYTE_BGRA_PRE} buffers, each wrapped in
 * a public-API {@link PixelBuffer} and {@link WritableImage}, shown one at a time in an {@link ImageView}. It
 * makes no GL calls; the render thread fills buffers and the JavaFX thread only presents them.
 *
 * <p>The ring holds the frame on screen, the frame it just replaced, and one for the render thread to fill:
 * <ol>
 *   <li>Any thread {@link #acquire}s a free buffer of the wanted size and fills it, top row first.</li>
 *   <li>{@link #publish} makes it the pending frame. A pending frame that was never shown is simply recycled.
 *   </li>
 *   <li>At the next pulse, the JavaFX thread marks the pending buffer dirty with {@code updateBuffer} and sets
 *       its image on the view.</li>
 *   <li>The frame it replaced is not handed back until the pulse after that. JavaFX's renderer may still be
 *       drawing the previous pulse (and reading that buffer) while the JavaFX thread runs this one; a scene's
 *       next pulse waits for that rendering to finish before it syncs, so one pulse later nothing reads it.</li>
 * </ol>
 * So at most one frame is presented per pulse, which paces the render thread to JavaFX's 60 Hz pulse
 * (ADR-0014). Changing size just starts handing out buffers of the new size; buffers of the old size are
 * dropped as they come back.
 *
 * <p>Thread safety: {@link #acquire}, {@link #publish} and {@link #framesPresented} may be called from any
 * thread; everything else happens on the JavaFX Application Thread.
 */
final class FramePresenter {

    /** The buffer on screen, the one just replaced, and one being filled. */
    static final int BUFFERS = 3;

    /** One buffer of the ring, with the JavaFX objects that wrap it. */
    static final class Frame {
        private final int width;
        private final int height;
        private final ByteBuffer pixels;
        // Made on the JavaFX thread the first time this frame is presented, then reused.
        private PixelBuffer<ByteBuffer> pixelBuffer;
        private WritableImage image;

        private Frame(int width, int height) {
            this.width = width;
            this.height = height;
            this.pixels = ByteBuffer.allocateDirect(width * height * 4);
        }

        /** @return the width in pixels */
        int width() {
            return width;
        }

        /** @return the height in pixels */
        int height() {
            return height;
        }

        /**
         * The pixels to fill: {@code width * height} premultiplied BGRA pixels, top row first, no row padding.
         * Only the holder of an acquired, unpublished frame may write them.
         *
         * @return the frame's buffer
         */
        ByteBuffer pixels() {
            return pixels;
        }

        private void present(ImageView view) {
            if (pixelBuffer == null) {
                pixelBuffer = new PixelBuffer<>(width, height, pixels, PixelFormat.getByteBgraPreInstance());
                image = new WritableImage(pixelBuffer);
            }
            // The pixels were written before publish; updateBuffer only tells JavaFX the whole buffer changed.
            Rectangle2D all = new Rectangle2D(0, 0, width, height);
            pixelBuffer.updateBuffer(buffer -> all);
            view.setImage(image);
        }
    }

    private final ImageView view;
    private final Runnable onBufferFreed;
    private final AtomicLong presented = new AtomicLong();
    private final AnimationTimer pulse = new AnimationTimer() {
        @Override
        public void handle(long now) {
            onPulse();
        }
    };

    // Guarded by this.
    private final Deque<Frame> free = new ArrayDeque<>();
    private int handedOut;
    private Frame pending;
    private Frame front;
    private Frame retiring;
    private boolean pulsing;

    /**
     * Creates the ring for {@code view}.
     *
     * @param view where frames are shown; only ever touched on the JavaFX thread
     * @param onBufferFreed called, on the JavaFX thread, whenever a buffer comes back to the ring, so a waiting
     *     render thread can be woken; must not block
     */
    FramePresenter(ImageView view, Runnable onBufferFreed) {
        this.view = view;
        this.onBufferFreed = onBufferFreed;
    }

    /**
     * Takes a free buffer of the given size to fill.
     *
     * @param width the frame width in pixels, at least 1
     * @param height the frame height in pixels, at least 1
     * @return the buffer, or {@code null} if all three are in use; one comes back at a later pulse
     */
    synchronized Frame acquire(int width, int height) {
        if (live() >= BUFFERS) {
            return null;
        }
        Frame frame = null;
        while (!free.isEmpty() && frame == null) {
            Frame candidate = free.pop();
            if (candidate.width == width && candidate.height == height) {
                frame = candidate;
            }
            // A buffer of an old size is dropped here; the garbage collector frees its memory.
        }
        handedOut++;
        return frame != null ? frame : new Frame(width, height);
    }

    /**
     * Hands a filled buffer to the JavaFX thread to show at the next pulse. The caller must not touch it again.
     *
     * @param frame a buffer from {@link #acquire}, filled
     */
    void publish(Frame frame) {
        boolean startPulse;
        synchronized (this) {
            handedOut--;
            if (pending != null) {
                // Superseded before it was ever shown, so nothing in JavaFX refers to it yet.
                free.push(pending);
            }
            pending = frame;
            startPulse = !pulsing;
            pulsing = true;
        }
        if (startPulse) {
            // AnimationTimer may only be started on the JavaFX thread.
            Platform.runLater(pulse::start);
        }
    }

    /**
     * How many frames the JavaFX thread has put on screen so far.
     *
     * @return the count
     */
    long framesPresented() {
        return presented.get();
    }

    /** Runs once per pulse, on the JavaFX thread, while there is a frame to show or a buffer to give back. */
    private void onPulse() {
        Frame show;
        boolean freed;
        synchronized (this) {
            freed = retiring != null;
            if (freed) {
                free.push(retiring);
                retiring = null;
            }
            show = pending;
            pending = null;
            if (show != null) {
                retiring = front;
                front = show;
            } else if (retiring == null) {
                // Nothing to show and nothing to give back: stop asking for pulses until the next publish.
                pulse.stop();
                pulsing = false;
            }
        }
        if (show != null) {
            show.present(view);
            presented.incrementAndGet();
        }
        if (freed) {
            onBufferFreed.run();
        }
    }

    private int live() {
        return handedOut + (pending != null ? 1 : 0) + (front != null ? 1 : 0) + (retiring != null ? 1 : 0);
    }
}
