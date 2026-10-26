package io.github.evildarkarchon.andysdice.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.github.evildarkarchon.andysdice.core.playback.PlaybackClock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * JavaFX's pulse runs at the monitor's rate (about 218 Hz on the machine #33 measured), but playback is paced
 * at 60 Hz (ADR-0014). The pacer turns pulse timestamps into 60 Hz advances of the playback clock by the real
 * time that passed. Timestamps are fed by hand.
 */
class PulsePacerTest {

    private static final long SECOND = 1_000_000_000L;

    @Test
    void theFirstPulseOnlyStartsTheCount() {
        PlaybackClock clock = new PlaybackClock();
        PulsePacer pacer = new PulsePacer(clock);

        pacer.pulse(5 * SECOND);

        assertThat(clock.time()).isZero();
    }

    @Test
    void aFastPulseAdvancesTheClockSixtyTimesASecondByTheRealTimeThatPassed() {
        PlaybackClock clock = new PlaybackClock();
        List<Double> advances = recordAdvances(clock);
        PulsePacer pacer = new PulsePacer(clock);

        long start = 7 * SECOND;
        long pulse = SECOND / 218;
        for (long now = start; now <= start + 2 * SECOND; now += pulse) {
            pacer.pulse(now);
        }

        assertThat(advances.size()).isBetween(119, 121);
        assertThat(clock.time()).isCloseTo(2, within(1.0 / 60));
        // Whole pulses apart, so no advance is shorter than 3 pulses or longer than 4.
        assertThat(intervals(advances)).allSatisfy(d -> assertThat(d).isBetween(2.9 / 218, 4.1 / 218));
    }

    @Test
    void aSixtyHertzPulseWithJitterAdvancesOnEveryPulse() {
        PlaybackClock clock = new PlaybackClock();
        List<Double> advances = recordAdvances(clock);
        PulsePacer pacer = new PulsePacer(clock);

        long now = SECOND;
        pacer.pulse(now);
        for (int i = 0; i < 60; i++) {
            // A pulse a millisecond early or late must still draw a frame.
            now += SECOND / 60 + (i % 2 == 0 ? -1_000_000 : 1_000_000);
            pacer.pulse(now);
        }

        assertThat(advances).hasSize(60);
        assertThat(clock.time()).isCloseTo((now - SECOND) / 1e9, within(1e-9));
    }

    @Test
    void afterAStallItCatchesUpInOneAdvanceAndKeepsItsPace() {
        PlaybackClock clock = new PlaybackClock();
        List<Double> advances = recordAdvances(clock);
        PulsePacer pacer = new PulsePacer(clock);

        pacer.pulse(0);
        pacer.pulse(SECOND / 2); // half a second with no pulse at all
        long now = SECOND / 2;
        for (int i = 0; i < 6; i++) {
            now += SECOND / 60;
            pacer.pulse(now);
        }

        assertThat(advances).hasSize(7);
        assertThat(advances.getFirst()).isCloseTo(0.5, within(1e-9));
    }

    @Test
    void afterARestartTheTimeWhileStoppedIsNotPlayedBack() {
        PlaybackClock clock = new PlaybackClock();
        PulsePacer pacer = new PulsePacer(clock);
        pacer.pulse(0);
        pacer.pulse(SECOND / 60);

        pacer.restart();
        pacer.pulse(10 * SECOND);
        pacer.pulse(10 * SECOND + SECOND / 60);

        assertThat(clock.time()).isCloseTo(2.0 / 60, within(1e-6));
    }

    @Test
    void theClockSpeedStillApplies() {
        PlaybackClock clock = new PlaybackClock();
        clock.setBaseSpeed(4);
        PulsePacer pacer = new PulsePacer(clock);

        pacer.pulse(0);
        pacer.pulse(SECOND / 60);

        assertThat(clock.time()).isCloseTo(4.0 / 60, within(1e-6));
    }

    /** Records the clock's time at every advance. */
    private static List<Double> recordAdvances(PlaybackClock clock) {
        List<Double> times = new ArrayList<>();
        clock.addListener(c -> times.add(c.time()));
        return times;
    }

    /** The playback time between successive advances, the first measured from 0. */
    private static List<Double> intervals(List<Double> times) {
        List<Double> out = new ArrayList<>();
        double previous = 0;
        for (double t : times) {
            out.add(t - previous);
            previous = t;
        }
        return out;
    }
}
