package io.github.evildarkarchon.andysdice.render;

import java.io.Serial;

/**
 * The driver reported the render thread's GL context lost, after a GPU reset or a driver update. The render thread
 * ends with it: nothing is recovered in-process, since ADR-0002 allows one GL context per app run, and the app shows
 * its runtime-failure panel (spec §3.2 "Runtime failure").
 */
public final class GlContextLostException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates the failure.
     *
     * @param message what the driver reported, in a few words
     */
    public GlContextLostException(String message) {
        super(message);
    }
}
