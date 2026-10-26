package io.github.evildarkarchon.andysdice.sound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.github.evildarkarchon.andysdice.core.playback.PlaybackClock;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * The audio thread's view of the playback clock (ADR-0012 "Sync"): the time and speed of the last advance, read
 * on the clock's thread, carried forward to the moment a block will be heard.
 */
class ClockFollowerTest {

    private static final long MS = 1_000_000L;

    private final PlaybackClock clock = new PlaybackClock();
    private final AtomicLong nanos = new AtomicLong();
    private final ClockFollower follower = ClockFollower.follow(clock, nanos::get);

    private void advance(long atNanos, double realSeconds) {
        nanos.set(atNanos);
        clock.advance(realSeconds);
    }

    @Test
    void theTimeAtTheLastAdvanceIsCarriedForwardAtItsSpeed() {
        advance(1_000 * MS, 1.0);

        assertThat(follower.at(1_000 * MS).time()).isCloseTo(1.0, within(1e-12));
        assertThat(follower.at(1_030 * MS).time()).isCloseTo(1.03, within(1e-12));
        assertThat(follower.at(1_030 * MS).speed()).isEqualTo(1);
    }

    @Test
    void aSpeedChangeTakesEffectFromTheNextAdvance() {
        advance(1_000 * MS, 1.0);
        clock.setBaseSpeed(4);
        assertThat(follower.at(1_010 * MS).speed()).isEqualTo(1);

        advance(1_010 * MS, 0.01);

        ClockFollower.Timing timing = follower.at(1_030 * MS);
        assertThat(timing.speed()).isEqualTo(4);
        assertThat(timing.time()).isCloseTo(1.04 + 0.02 * 4, within(1e-12));
    }

    @Test
    void anIdleClockIsCarriedForwardNoFurtherThanTheLimit() {
        advance(1_000 * MS, 1.0);

        assertThat(follower.at(9_000 * MS).time())
                .isCloseTo(1.0 + ClockFollower.MAX_EXTRAPOLATION_SECONDS, within(1e-12));
        assertThat(follower.at(900 * MS).time()).isCloseTo(1.0, within(1e-12));
    }

    @Test
    void beforeAnyAdvanceItFollowsTheClockAsItWasMade() {
        assertThat(follower.at(0).time()).isZero();
        assertThat(follower.at(0).speed()).isEqualTo(1);
    }
}
