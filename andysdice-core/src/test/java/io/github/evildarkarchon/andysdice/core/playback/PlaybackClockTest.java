package io.github.evildarkarchon.andysdice.core.playback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The playback clock is advanced by hand here, as every test that plays something back advances it: no test
 * sleeps (ADR-0011).
 */
class PlaybackClockTest {

    @Test
    void startsAtZeroAtNormalSpeed() {
        PlaybackClock clock = new PlaybackClock();

        assertThat(clock.time()).isZero();
        assertThat(clock.speed()).isEqualTo(1);
    }

    @Test
    void advancesByTheRealTimeThatPassedAtNormalSpeed() {
        PlaybackClock clock = new PlaybackClock();

        clock.advance(0.25);
        clock.advance(1 / 60.0);

        assertThat(clock.time()).isCloseTo(0.25 + 1 / 60.0, within(1e-12));
    }

    @Test
    void aFasterSpeedCompressesOnlyTheTimeAdvancedAfterIt() {
        PlaybackClock clock = new PlaybackClock();
        clock.advance(0.5);

        clock.setBaseSpeed(4);
        clock.advance(0.25);

        // 0.5 s at x1, then 0.25 s of real time at x4 is a further second of playback.
        assertThat(clock.time()).isCloseTo(1.5, within(1e-12));
        assertThat(clock.speed()).isEqualTo(4);
    }

    @Test
    void aFastForwardFactorChangeAppliesOnlyFromTheNextAdvance() {
        PlaybackClock clock = new PlaybackClock();
        clock.setBaseSpeed(2);
        clock.advance(0.5);

        clock.setFastForwardFactor(4);
        assertThat(clock.time()).as("time already advanced is unchanged").isCloseTo(1.0, within(1e-12));
        clock.advance(0.25);
        clock.setFastForwardFactor(1);
        clock.advance(0.25);

        // 0.5 s at x2, then 0.25 s at x8, then 0.25 s back at the base x2.
        assertThat(clock.time()).isCloseTo(1.0 + 2.0 + 0.5, within(1e-12));
        assertThat(clock.speed()).isEqualTo(2);
    }

    @Test
    void itsSpeedIsTheBaseSpeedTimesTheFastForwardFactorInEitherOrderOfSetting() {
        PlaybackClock baseFirst = new PlaybackClock();
        baseFirst.setBaseSpeed(16);
        baseFirst.setFastForwardFactor(4);
        PlaybackClock factorFirst = new PlaybackClock();
        factorFirst.setFastForwardFactor(4);
        factorFirst.setBaseSpeed(16);

        assertThat(baseFirst.speed()).isEqualTo(64);
        assertThat(factorFirst.speed()).isEqualTo(64);
        assertThat(baseFirst.baseSpeed()).isEqualTo(16);
    }

    @Test
    void tellsItsListenersAfterEveryAdvanceInTheOrderTheyWereAdded() {
        PlaybackClock clock = new PlaybackClock();
        List<String> heard = new ArrayList<>();
        clock.addListener(c -> heard.add("first " + c.time()));
        clock.addListener(c -> heard.add("second " + c.time()));

        clock.advance(0.5);
        clock.advance(0.25);

        assertThat(heard).containsExactly("first 0.5", "second 0.5", "first 0.75", "second 0.75");
    }

    @Test
    void aRemovedListenerHearsNoMore() {
        PlaybackClock clock = new PlaybackClock();
        List<Double> heard = new ArrayList<>();
        PlaybackClock.Listener listener = c -> heard.add(c.time());
        clock.addListener(listener);
        clock.advance(0.5);

        clock.removeListener(listener);
        clock.advance(0.5);

        assertThat(heard).containsExactly(0.5);
    }

    @Test
    void neverRunsBackwardsOrStops() {
        PlaybackClock clock = new PlaybackClock();

        assertThatThrownBy(() -> clock.advance(-0.001)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> clock.advance(Double.NaN)).isInstanceOf(IllegalArgumentException.class);
        assertThat(clock.time()).isZero();
    }

    @Test
    void eitherFactorMustBePositiveAndFinite() {
        PlaybackClock clock = new PlaybackClock();

        for (double bad : new double[] {0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThatThrownBy(() -> clock.setBaseSpeed(bad)).as("base speed %s", bad)
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> clock.setFastForwardFactor(bad)).as("Fast-forward factor %s", bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(clock.speed()).as("a rejected factor changes nothing").isEqualTo(1);
    }
}
