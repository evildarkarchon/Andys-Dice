package io.github.evildarkarchon.andysdice.sound;

import io.github.evildarkarchon.andysdice.core.playback.PlaybackClock;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * The audio thread's view of the playback clock (ADR-0012 "Sync"). The clock is confined to its own thread
 * (the JavaFX Application Thread), so this listens to it there and publishes each advance's time and speed,
 * stamped with the real time it happened. The audio thread then asks what the playback time will be when a
 * block is heard, carrying the last advance forward at its speed.
 *
 * <p>An idle clock (the app stops pulsing between Rolls) is carried forward no further than
 * {@link #MAX_EXTRAPOLATION_SECONDS}, so the next Throw, which starts at the clock's current time, isn't
 * already in the past when the clock moves on again.
 */
public final class ClockFollower implements PlaybackClock.Listener {

    /** How far past the last advance, in seconds of real time, the clock is carried forward at most. */
    public static final double MAX_EXTRAPOLATION_SECONDS = 0.1;

    /**
     * The playback clock as it will read at some moment.
     *
     * @param time the playback time, in seconds
     * @param speed the speed against real time at that moment
     */
    public record Timing(double time, double speed) {
    }

    /** One advance of the clock, and when it happened. */
    private record Snapshot(double time, double speed, long nanos) {
    }

    private final LongSupplier nanoTime;
    private volatile Snapshot snapshot;

    private ClockFollower(PlaybackClock clock, LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
        this.snapshot = new Snapshot(clock.time(), clock.speed(), nanoTime.getAsLong());
    }

    /**
     * Starts following a clock. Call it on the clock's thread.
     *
     * @param clock the playback clock
     * @param nanoTime the real-time source, in nanoseconds ({@code System::nanoTime} in the app); the audio
     *     thread must ask {@link #at} on the same time base
     * @return the follower, already listening to the clock
     */
    public static ClockFollower follow(PlaybackClock clock, LongSupplier nanoTime) {
        Objects.requireNonNull(clock, "clock");
        ClockFollower follower = new ClockFollower(clock, Objects.requireNonNull(nanoTime, "nanoTime"));
        clock.addListener(follower);
        return follower;
    }

    @Override
    public void advanced(PlaybackClock clock) {
        snapshot = new Snapshot(clock.time(), clock.speed(), nanoTime.getAsLong());
    }

    /**
     * Returns the playback time and speed at a real-time moment. Any thread may call this.
     *
     * @param nanos the moment, on the follower's time base
     * @return the last advance carried forward to it at the last advance's speed, by no less than nothing
     *     and no more than {@link #MAX_EXTRAPOLATION_SECONDS} of real time
     */
    public Timing at(long nanos) {
        Snapshot last = snapshot;
        double real = Math.clamp((nanos - last.nanos()) / 1e9, 0.0, MAX_EXTRAPOLATION_SECONDS);
        return new Timing(last.time() + real * last.speed(), last.speed());
    }
}
