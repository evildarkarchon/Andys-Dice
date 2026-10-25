package io.github.evildarkarchon.andysdice.core.playback;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The one master clock of playback (spec §3.1, ADR-0012, ADR-0014): the time on which Recordings play,
 * sound is mixed and Throw phases are reported.
 *
 * <p>It holds playback time in seconds and is moved on only by {@link #advance}, by the real time that passed
 * times the current {@link #speed()} (×4 while Fast-forward is on, ×1 otherwise). In the app something on
 * JavaFX's pulse advances it; tests advance it by hand, so nothing that plays back ever reads a wall clock or
 * sleeps (ADR-0011). Its time only ever increases.
 *
 * <p>Thread contract: confined to the thread that advances it (the JavaFX Application Thread in the app).
 * Listeners are told on that thread, after each advance. Something on another thread (the audio thread, the
 * render thread) is handed a time read on it, not the clock.
 */
public final class PlaybackClock {

    /** Something told every time the clock moves on. */
    @FunctionalInterface
    public interface Listener {
        /**
         * The clock has advanced; {@link PlaybackClock#time()} is the new time.
         *
         * @param clock the clock that advanced
         */
        void advanced(PlaybackClock clock);
    }

    // Copy-on-write so a listener may add or remove listeners (itself included) while being told.
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private double time;
    private double speed = 1;

    /** Creates a clock at time 0, running at ×1. */
    public PlaybackClock() {}

    /**
     * Returns the playback time.
     *
     * @return seconds of playback since the clock was made
     */
    public double time() {
        return time;
    }

    /**
     * Returns how fast playback runs against real time.
     *
     * @return the factor, 1 for normal speed
     */
    public double speed() {
        return speed;
    }

    /**
     * Sets how fast playback runs from the next {@link #advance} on; time already advanced is unchanged.
     *
     * @param speed the factor against real time, 1 for normal speed
     * @throws IllegalArgumentException if it isn't a positive, finite number (playback never stops or skips)
     */
    public void setSpeed(double speed) {
        if (!(speed > 0) || !Double.isFinite(speed)) {
            throw new IllegalArgumentException("A playback speed must be positive and finite: " + speed);
        }
        this.speed = speed;
    }

    /**
     * Moves the clock on by the real time that passed, scaled by the speed, then tells every listener in the
     * order they were added.
     *
     * @param realSeconds the real time since the last advance, in seconds; may be 0
     * @throws IllegalArgumentException if it is negative or not finite
     */
    public void advance(double realSeconds) {
        if (!(realSeconds >= 0) || !Double.isFinite(realSeconds)) {
            throw new IllegalArgumentException("The clock advances by a finite, non-negative time: " + realSeconds);
        }
        time += realSeconds * speed;
        for (Listener listener : listeners) {
            listener.advanced(this);
        }
    }

    /**
     * Adds a listener, told after every advance from now on.
     *
     * @param listener the listener
     */
    public void addListener(Listener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * Removes a listener; it is told nothing more. Removing one that was never added does nothing.
     *
     * @param listener the listener
     */
    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }
}
