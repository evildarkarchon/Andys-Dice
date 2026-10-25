package io.github.evildarkarchon.andysdice.app;

import io.github.evildarkarchon.andysdice.core.playback.PlaybackClock;
import java.util.Objects;

/**
 * Paces playback at 60 Hz from JavaFX's pulse (ADR-0014), whatever rate the pulse runs at: JavaFX pulses at the
 * monitor's refresh rate, about 218 Hz on a high-refresh monitor, so counting pulses would play back too fast.
 *
 * <p>Each pulse's timestamp is handed to {@link #pulse}. When a 60 Hz frame is due it advances the playback
 * clock by the real time since the last frame, and the clock's listeners redraw. Frames are due on a fixed
 * 60 Hz schedule, so a pulse rate that isn't a multiple of 60 alternates between whole pulses and still averages
 * 60; a frame may come up to {@value #EARLY_MILLIS} ms early, so a 60 Hz pulse's jitter never drops one.
 *
 * <p>Thread contract: used on the JavaFX Application Thread only, like the clock it advances.
 */
final class PulsePacer {

    /** The frame period: 1/60 s. */
    static final long FRAME_NANOS = 1_000_000_000L / 60;

    /** How early a pulse may be and still count as the frame due next. */
    private static final long EARLY_MILLIS = 2;

    private static final long EARLY_NANOS = EARLY_MILLIS * 1_000_000;

    private final PlaybackClock clock;
    private boolean started;
    private long lastFrame;
    private long due;

    /**
     * Creates a pacer for a clock; the first pulse it is given starts its count.
     *
     * @param clock the playback clock to advance
     */
    PulsePacer(PlaybackClock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Handles one pulse, advancing the clock if a frame is due.
     *
     * @param nowNanos the pulse's timestamp, as {@code AnimationTimer.handle} gets it
     */
    void pulse(long nowNanos) {
        if (!started) {
            started = true;
            lastFrame = nowNanos;
            due = nowNanos + FRAME_NANOS;
            return;
        }
        if (nowNanos < due - EARLY_NANOS) {
            return;
        }
        long elapsed = nowNanos - lastFrame;
        lastFrame = nowNanos;
        due += FRAME_NANOS;
        // After a stall (a long GC, a dragged window) don't fire a burst of frames to catch up: the one advance
        // above already covered the real time that passed, so start the schedule again from now.
        if (due <= nowNanos) {
            due = nowNanos + FRAME_NANOS;
        }
        clock.advance(elapsed / 1e9);
    }

    /**
     * Forgets the last frame, so the next pulse only starts the count again. Called when the pulses resume after
     * a pause, so the time nothing was playing isn't played back.
     */
    void restart() {
        started = false;
    }
}
