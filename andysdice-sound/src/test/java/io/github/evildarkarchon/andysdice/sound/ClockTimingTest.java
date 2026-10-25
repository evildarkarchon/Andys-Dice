package io.github.evildarkarchon.andysdice.sound;

import static io.github.evildarkarchon.andysdice.sound.Synthetic.floorHit;
import static io.github.evildarkarchon.andysdice.sound.Synthetic.onset;
import static io.github.evildarkarchon.andysdice.sound.Synthetic.recording;
import static io.github.evildarkarchon.andysdice.sound.Synthetic.throwOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.github.evildarkarchon.andysdice.core.simulation.ContactEvent;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The mixer follows the playback clock (ADR-0012 "Sync"): each block is rendered for the playback time it will be
 * heard at and the clock's current speed, so a speed change mid-Throw re-maps every later start time, and ×4
 * compresses the timing while each sample keeps its natural pitch.
 */
class ClockTimingTest {

    private static final MixerTunables NO_JITTER = MixerTunables.DEFAULTS.withJitter(0, 0);

    private static Rig rig() {
        Rig rig = new Rig(Synthetic.bankOf(Synthetic.constant(0.5f, 240)), NO_JITTER);
        rig.mixer.setVolume(1);
        return rig;
    }

    @Test
    void anImpactStartsAtItsEventsTimeAtNormalSpeed() {
        Rig rig = rig();
        rig.mixer.play(throwOf(recording(1, 1f, List.of(floorHit(0.2f, 0, 1f))), 0));

        rig.run(0.5, 1);

        // 0.2 s after the Throw's start, at 48 kHz.
        assertThat(onset(rig.output(), 0)).isCloseTo(9_600, within(1));
    }

    @Test
    void aThrowStartsWhereverThePlaybackClockSaysItDoes() {
        Rig rig = rig();
        rig.mixer.play(throwOf(recording(1, 1f, List.of(floorHit(0.2f, 0, 1f))), 0.25));

        rig.run(0.6, 1);

        assertThat(onset(rig.output(), 0)).isCloseTo(21_600, within(1)); // (0.25 + 0.2) s
    }

    @Test
    void aSpeedChangeMidThrowRemapsLaterStartTimes() {
        ContactEvent early = floorHit(0.2f, 0, 1f);
        ContactEvent late = floorHit(0.6f, 0, 1f);
        Rig steady = rig();
        steady.mixer.play(throwOf(recording(1, 1f, List.of(early, late)), 0));
        steady.run(1.0, 1);
        Rig shifted = rig();
        shifted.mixer.play(throwOf(recording(1, 1f, List.of(early, late)), 0));
        shifted.run(0.3, 1).run(0.3, 4); // Shift held from 0.3 s of playback on

        assertThat(steady.impactOf(late).frame()).isCloseTo(28_800, within(1L));
        // Before the change nothing moves; after it the remaining 0.3 s of playback take 0.075 s of real time.
        assertThat(shifted.impactOf(early).frame()).isCloseTo(9_600, within(1L));
        assertThat(shifted.impactOf(late).frame()).isCloseTo(18_000, within(1L));
    }

    @Test
    void fastForwardCompressesTimingWithoutRaisingPitch() {
        Rig rig = new Rig(Synthetic.bankOf(Synthetic.sine(1_000, 4_800)), NO_JITTER);
        rig.mixer.setVolume(1);
        ContactEvent first = floorHit(0.4f, 0, 1f);
        ContactEvent second = floorHit(0.8f, 0, 1f);
        rig.mixer.play(throwOf(recording(1, 1f, List.of(first, second)), 0));

        rig.run(0.5, 4);

        // 0.4 s of playback apart is 0.1 s of real time apart at ×4.
        assertThat(rig.impactOf(second).frame() - rig.impactOf(first).frame()).isCloseTo(4_800, within(2L));
        // The first slice still plays for its natural 100 ms (4,800 frames) and keeps its 1 kHz: about 200
        // zero crossings, not the 800 a ×4 pitch would give.
        short[] out = rig.output();
        int start = (int) rig.impactOf(first).frame();
        int crossings = 0;
        for (int frame = start + 1; frame < start + 4_800; frame++) {
            if ((out[2 * frame - 2] < 0) != (out[2 * frame] < 0)) {
                crossings++;
            }
        }
        assertThat(crossings).isBetween(195, 205);
    }
}
