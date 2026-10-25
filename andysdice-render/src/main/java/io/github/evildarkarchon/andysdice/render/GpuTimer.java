package io.github.evildarkarchon.andysdice.render;

import static org.lwjgl.opengl.GL15C.GL_QUERY_RESULT;
import static org.lwjgl.opengl.GL15C.GL_QUERY_RESULT_AVAILABLE;
import static org.lwjgl.opengl.GL15C.glBeginQuery;
import static org.lwjgl.opengl.GL15C.glDeleteQueries;
import static org.lwjgl.opengl.GL15C.glEndQuery;
import static org.lwjgl.opengl.GL15C.glGenQueries;
import static org.lwjgl.opengl.GL15C.glGetQueryObjecti;
import static org.lwjgl.opengl.GL33C.GL_TIME_ELAPSED;
import static org.lwjgl.opengl.GL33C.glGetQueryObjecti64;

import java.util.function.DoubleConsumer;

/**
 * Measures the GPU time of each frame with GL 3.3 core timer queries ({@code GL_TIME_ELAPSED}, ADR-0014), the
 * tray prototype's pattern: a small ring of queries, each read only once the driver says its result is available,
 * so measuring never waits for the GPU. A frame that finds every query of the ring still in flight goes unmeasured
 * rather than stalling.
 *
 * <p>Results come back in frame order, usually a frame or two after the frame was drawn.
 *
 * <p>Render thread only. Time-elapsed queries can't nest, so nothing drawn between {@link #begin} and {@link #end}
 * may use one.
 */
final class GpuTimer {

    /** Queries in the ring: as many frames as can be in flight before one goes unmeasured. */
    private static final int RING = 4;

    private static final double NANOS_PER_MILLI = 1e6;

    private final int[] queries = new int[RING];
    // The ring holds pending queries from oldest (at head) to newest; count of them pending.
    private int head;
    private int pending;
    // The query begun by begin() for end() to end, or -1 when this frame isn't measured.
    private int open = -1;

    /** Makes the ring's queries. */
    GpuTimer() {
        RenderThread.checkRenderThread();
        glGenQueries(queries);
    }

    /** Starts timing a frame, unless every query is still waiting on the GPU. */
    void begin() {
        if (pending == RING) {
            open = -1;
            return;
        }
        open = queries[(head + pending) % RING];
        glBeginQuery(GL_TIME_ELAPSED, open);
    }

    /** Stops timing the frame {@link #begin} started, if it started one. */
    void end() {
        if (open < 0) {
            return;
        }
        glEndQuery(GL_TIME_ELAPSED);
        pending++;
        open = -1;
    }

    /**
     * Hands over every result the GPU has finished, oldest first, without waiting for any that it hasn't.
     *
     * @param listener told each frame's GPU time in milliseconds, or {@code null} to drop the results
     */
    void poll(DoubleConsumer listener) {
        while (pending > 0) {
            int query = queries[head];
            if (glGetQueryObjecti(query, GL_QUERY_RESULT_AVAILABLE) == 0) {
                // Queries finish in order, so nothing newer is ready either.
                return;
            }
            long nanos = glGetQueryObjecti64(query, GL_QUERY_RESULT);
            head = (head + 1) % RING;
            pending--;
            if (listener != null) {
                listener.accept(nanos / NANOS_PER_MILLI);
            }
        }
    }

    /** Deletes the queries. */
    void delete() {
        RenderThread.checkRenderThread();
        glDeleteQueries(queries);
    }
}
