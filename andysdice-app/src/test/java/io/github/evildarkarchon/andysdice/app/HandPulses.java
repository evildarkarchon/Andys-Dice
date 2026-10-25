package io.github.evildarkarchon.andysdice.app;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pulses that only count starts and stops, for tests that advance the playback clock themselves (ADR-0011: tests
 * never sleep and never run on JavaFX's own pulse).
 */
final class HandPulses implements Pulses {
    /** How many times playback asked for pulses. */
    final AtomicInteger starts = new AtomicInteger();
    /** Whether pulses are wanted now. */
    volatile boolean running;

    @Override
    public void start() {
        starts.incrementAndGet();
        running = true;
    }

    @Override
    public void stop() {
        running = false;
    }
}
