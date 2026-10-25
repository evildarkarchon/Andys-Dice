package io.github.evildarkarchon.andysdice.render;

import static org.lwjgl.opengl.GL11C.glViewport;
import static org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30C.glBindFramebuffer;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.DoubleConsumer;

/**
 * The one render thread (ADR-0002): it owns the app's only GL context and its one render surface, makes every
 * GL call, and draws on demand. Other threads, the JavaFX thread above all, only send it messages:
 * {@link #resize}, {@link #requestFrame} and {@link #submit}.
 *
 * <p>Each frame is drawn by the {@link FrameRenderer} into the render target (an FBO that is resized, never
 * recreated) and handed to the {@link FrameTransport}, which reads it back without waiting for the GPU. With
 * nothing requested the thread sleeps; it wakes for messages and, while frames are in flight, polls the
 * transport every millisecond.
 *
 * <p>Only one may be started per JVM, so there is one GL context per app run. It is torn down only by
 * {@link #close()} at exit.
 */
public final class RenderThread implements AutoCloseable {

    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static volatile Thread renderThread;

    // While idle the thread still wakes this often, to keep the context's hidden window answering the system.
    private static final long IDLE_POLL_MILLIS = 50;
    private static final long IN_FLIGHT_POLL_MILLIS = 1;
    private static final long NO_SIZE = -1;
    private static final Runnable WAKE = () -> {};

    private final FrameTransport transport;
    private final FrameRenderer renderer;
    private final LinkedBlockingQueue<Runnable> messages = new LinkedBlockingQueue<>();
    private final AtomicBoolean wakePending = new AtomicBoolean();
    private final AtomicBoolean frameRequested = new AtomicBoolean();
    // The latest size asked for, width in the high half and height in the low; NO_SIZE once applied.
    private final AtomicLong requestedSize = new AtomicLong(NO_SIZE);
    private volatile boolean running = true;
    // Set by close(), so the thread's end can be told apart from a death.
    private volatile boolean closing;
    private final CompletableFuture<Void> stopped = new CompletableFuture<>();
    private Thread thread;
    private GlDriver driver;
    // Told each measured frame's GPU time; null measures nothing.
    private volatile DoubleConsumer gpuTimeListener;

    // Render thread only.
    private GlContext context;
    private RenderTarget target;
    private GpuTimer gpuTimer;
    private boolean transportOpen;
    private boolean rendererReady;
    private int width;
    private int height;

    private RenderThread(FrameTransport transport, FrameRenderer renderer) {
        this.transport = transport;
        this.renderer = renderer;
    }

    /**
     * Starts the render thread: it creates the OpenGL 3.3 core context, the render target and the transport's
     * GL objects, and initialises the renderer. Returns once all of that has succeeded.
     *
     * @param transport where frames go
     * @param renderer what draws them
     * @return the running render thread
     * @throws GlUnavailableException if no OpenGL 3.3 core context can be created; the message names the
     *     requirement and the detected renderer and driver
     * @throws IllegalStateException if a render thread was already started in this JVM (one context per run)
     * @throws RuntimeException whatever the renderer's or transport's set-up threw, such as a shader that
     *     didn't compile
     */
    public static RenderThread start(FrameTransport transport, FrameRenderer renderer) {
        if (!STARTED.compareAndSet(false, true)) {
            throw new IllegalStateException("a render thread was already started; ADR-0002 allows one GL context"
                    + " per app run");
        }
        RenderThread rt = new RenderThread(transport, renderer);
        CompletableFuture<GlDriver> ready = new CompletableFuture<>();
        rt.thread = Thread.ofPlatform().name("andysdice-render").daemon(true).unstarted(() -> rt.run(ready));
        rt.thread.start();
        try {
            rt.driver = ready.join();
        } catch (CompletionException e) {
            switch (e.getCause()) {
                case RuntimeException cause -> throw cause;
                case Error cause -> throw cause;
                default -> throw e;
            }
        }
        return rt;
    }

    /**
     * Throws unless the caller is the render thread. Every GL-touching method outside a {@link FrameRenderer}
     * checks this, so a GL call from the wrong thread fails loudly instead of corrupting state.
     *
     * @throws IllegalStateException off the render thread
     */
    static void checkRenderThread() {
        if (Thread.currentThread() != renderThread) {
            throw new IllegalStateException("GL work must run on the render thread (ADR-0002), not on "
                    + Thread.currentThread().getName());
        }
    }

    /**
     * What the driver reports about the context, for the log.
     *
     * @return the context's renderer, vendor and version
     */
    public GlDriver driver() {
        return driver;
    }

    /**
     * Asks for the render target to become {@code width × height} physical pixels and for a frame at that
     * size. Any thread; never blocks. Sizes sent faster than frames are drawn coalesce to the latest.
     *
     * @param width the width in pixels; values below 1 are taken as 1
     * @param height the height in pixels; values below 1 are taken as 1
     */
    public void resize(int width, int height) {
        requestedSize.set(((long) Math.max(1, width) << 32) | Math.max(1, height));
        frameRequested.set(true);
        wakeUp();
    }

    /** Asks for a frame to be drawn and presented. Any thread; never blocks. Requests coalesce. */
    public void requestFrame() {
        frameRequested.set(true);
        wakeUp();
    }

    /**
     * Runs {@code task} on the render thread, before the next frame. This is how other threads hand the
     * renderer its messages (camera, Dice Set and so on) and how tests reach GL. Any thread; never blocks.
     *
     * @param task what to run; it may make GL calls
     * @param <T> the result type
     * @return the task's result, or its exception
     */
    public <T> CompletableFuture<T> submit(Callable<T> task) {
        CompletableFuture<T> result = new CompletableFuture<>();
        if (!running) {
            result.completeExceptionally(new IllegalStateException("the render thread has stopped"));
            return result;
        }
        messages.add(() -> {
            try {
                result.complete(task.call());
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        return result;
    }

    /**
     * Sets who is told how much GPU time each frame took to draw (ADR-0014), measured with GL timer queries around
     * the {@link FrameRenderer}'s {@code render} (the readback to the transport isn't included). Each frame drawn
     * while a listener is set is measured, and its time reported on the render thread, in frame order, usually a
     * frame or two after it was drawn: the thread collects results without ever waiting for the GPU, and a frame
     * that would have to wait is left unmeasured. The slow-GPU Notice and {@code --bench} listen here. Any thread.
     *
     * @param listener told each measured frame's GPU time in milliseconds, on the render thread; it must not block.
     *     {@code null} stops measuring, and results still to come for earlier frames are dropped. It replaces any
     *     earlier listener.
     */
    public void setOnGpuTime(DoubleConsumer listener) {
        gpuTimeListener = listener;
    }

    /**
     * Tells when the render thread has ended, and why. v1 is crash-only (spec §3.2 "Runtime failure"): a render
     * thread that ends without {@link #close()} is a dead one, and the app shows its failure panel. Nothing is
     * recovered, since no second render thread can be started in this JVM (ADR-0002).
     *
     * <p>Whatever ended the thread also still reaches the thread's uncaught-exception handler.
     *
     * @return a stage that completes normally once the thread has ended after {@link #close()}, and exceptionally
     *     when it died: with what a frame threw, with a {@link GlContextLostException} when the driver reported
     *     the context lost, or with an {@link IllegalStateException} if it ended for no reason it could see
     */
    public CompletionStage<Void> stopped() {
        return stopped.minimalCompletionStage();
    }

    /**
     * Stops the render thread and releases the renderer, the transport, the render target and the context, in
     * that order. For app exit only: no render thread can be started again in this JVM.
     */
    @Override
    public void close() {
        closing = true;
        running = false;
        wakeUp();
        try {
            thread.join(TimeUnit.SECONDS.toMillis(5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void wakeUp() {
        // One pending wake is enough; this keeps a burst of messages from filling the queue with no-ops.
        if (wakePending.compareAndSet(false, true)) {
            messages.add(WAKE);
        }
    }

    private void run(CompletableFuture<GlDriver> ready) {
        renderThread = Thread.currentThread();
        try {
            // The floor, unless a dev or test run raises it to force the fail-fast (GlVersion.REQUIRE_PROPERTY).
            context = GlContext.create(GlVersion.required(System.getProperty(GlVersion.REQUIRE_PROPERTY)));
            target = new RenderTarget();
            gpuTimer = new GpuTimer();
            transport.open(this::wakeUp);
            transportOpen = true;
            renderer.init();
            rendererReady = true;
        } catch (Throwable t) {
            running = false;
            release();
            ready.completeExceptionally(t);
            stopped.completeExceptionally(t);
            return;
        }
        ready.complete(context.driver());
        Throwable death = null;
        try {
            loop();
        } catch (Throwable t) {
            death = t;
            // Rethrown as well, so the thread's uncaught-exception handler (the app's failure guard) sees it.
            throw t;
        } finally {
            running = false;
            try {
                release();
            } finally {
                ended(death);
            }
        }
    }

    /** Completes {@link #stopped()}: normally after {@link #close()}, exceptionally otherwise. */
    private void ended(Throwable death) {
        if (death != null) {
            stopped.completeExceptionally(death);
        } else if (closing) {
            stopped.complete(null);
        } else {
            // The loop only returns by itself when the thread is interrupted.
            stopped.completeExceptionally(new IllegalStateException("the render thread stopped without being"
                    + " closed"));
        }
    }

    private void loop() {
        boolean busy = false;
        while (running) {
            Runnable message;
            try {
                message = messages.poll(busy ? IN_FLIGHT_POLL_MILLIS : IDLE_POLL_MILLIS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                return;
            }
            while (message != null) {
                if (message == WAKE) {
                    wakePending.set(false);
                } else {
                    message.run();
                }
                message = messages.poll();
            }
            context.pumpEvents();
            // A lost context draws nothing ever again, and ADR-0002 allows no second one: end the thread, which
            // the app shows as its runtime failure (spec §3.2). Asking is cheap and never waits for the GPU.
            String lost = context.lostReason();
            if (lost != null) {
                throw new GlContextLostException("the OpenGL driver reported the context lost: " + lost);
            }
            applyRequestedSize();
            boolean wanted = frameRequested.get() && width > 0;
            if (wanted && transport.canSubmit()) {
                // Cleared before drawing, so a request that arrives meanwhile gets a frame of its own.
                frameRequested.set(false);
                drawFrame();
                wanted = false;
            }
            // GPU times of frames the GPU has finished; never waits on one it hasn't.
            gpuTimer.poll(gpuTimeListener);
            // Still wanting a frame the transport can't take yet: poll again soon rather than sleep.
            busy = transport.pump() || wanted;
        }
    }

    private void applyRequestedSize() {
        long size = requestedSize.getAndSet(NO_SIZE);
        if (size == NO_SIZE) {
            return;
        }
        int w = (int) (size >>> 32);
        int h = (int) size;
        if (w != width || h != height) {
            target.resize(w, h);
            width = target.width();
            height = target.height();
        }
    }

    private void drawFrame() {
        target.bind();
        glViewport(0, 0, width, height);
        boolean timed = gpuTimeListener != null;
        if (timed) {
            gpuTimer.begin();
        }
        renderer.render(width, height);
        if (timed) {
            gpuTimer.end();
        }
        target.bindForReading();
        transport.submit(width, height);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    /** Releases whatever {@link #run} got as far as making, in reverse order. */
    private void release() {
        try {
            if (rendererReady) {
                renderer.dispose();
            }
            if (transportOpen) {
                transport.close();
            }
            if (gpuTimer != null) {
                gpuTimer.delete();
            }
            if (target != null) {
                target.delete();
            }
        } finally {
            if (context != null) {
                context.close();
            }
        }
    }
}
