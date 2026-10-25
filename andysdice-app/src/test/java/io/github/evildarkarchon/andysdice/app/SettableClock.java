package io.github.evildarkarchon.andysdice.app;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;

/**
 * A wall clock a test sets by hand: the "now" the Roll History stamps a revealed Roll with and reads "Today" from,
 * in a time zone the test chooses. It never moves by itself. Thread-safe.
 */
final class SettableClock extends Clock {

    private volatile Instant now;
    private final ZoneId zone;

    /**
     * Makes a clock stopped at an instant.
     *
     * @param now the instant it reads
     * @param zone the time zone it stands in for the system's
     */
    SettableClock(Instant now, ZoneId zone) {
        this.now = Objects.requireNonNull(now, "now");
        this.zone = Objects.requireNonNull(zone, "zone");
    }

    /**
     * Sets what the clock reads.
     *
     * @param instant the new now
     */
    void set(Instant instant) {
        now = Objects.requireNonNull(instant, "instant");
    }

    /**
     * Moves the clock on.
     *
     * @param by how far
     */
    void advance(Duration by) {
        now = now.plus(by);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId other) {
        return new SettableClock(now, other);
    }

    @Override
    public Instant instant() {
        return now;
    }
}
