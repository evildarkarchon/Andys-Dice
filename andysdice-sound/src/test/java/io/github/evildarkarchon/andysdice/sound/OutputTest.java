package io.github.evildarkarchon.andysdice.sound;

import static io.github.evildarkarchon.andysdice.sound.Synthetic.dieHit;
import static io.github.evildarkarchon.andysdice.sound.Synthetic.floorHit;
import static io.github.evildarkarchon.andysdice.sound.Synthetic.recording;
import static io.github.evildarkarchon.andysdice.sound.Synthetic.throwOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import io.github.evildarkarchon.andysdice.core.simulation.ContactEvent;
import io.github.evildarkarchon.andysdice.core.simulation.Recording;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The output stage (ADR-0012 "Controls", "Testing"): mute gives all zeros, the volume scales the output and
 * applies to sound already playing, and the soft limiter keeps a 20-Dice landing from ever clipping.
 */
class OutputTest {

    /** A busy Throw: 6 Dice, each hitting the floor every 70 ms for half a second. */
    private static Recording busy() {
        List<ContactEvent> events = new ArrayList<>();
        for (int die = 0; die < 6; die++) {
            for (int hit = 0; hit < 7; hit++) {
                events.add(floorHit(0.05f + 0.07f * hit + 0.005f * die, die, 0.2f + 0.1f * die));
            }
        }
        return recording(6, 0.6f, events);
    }

    private static Rig rig(long seed) {
        Rig rig = new Rig(Synthetic.bankOf(Synthetic.sine(440, 6_000)), seed, MixerTunables.DEFAULTS);
        rig.mixer.play(throwOf(busy(), 0));
        return rig;
    }

    private static int peak(short[] out) {
        int peak = 0;
        for (short sample : out) {
            peak = Math.max(peak, Math.abs(sample));
        }
        return peak;
    }

    @Test
    void theDefaultIsUnmutedAt70Percent() {
        StreamingMixer mixer = new StreamingMixer(Synthetic.bankOf(Synthetic.constant(0.1f, 8)),
                new java.util.SplittableRandom(1));
        assertThat(mixer.volume()).isEqualTo(0.7);
        assertThat(mixer.muted()).isFalse();
    }

    @Test
    void mutedOutputIsAllZerosAndUnmutingResumesTheSound() {
        Rig rig = rig(1);
        rig.mixer.setMuted(true);
        rig.run(0.3, 1);
        assertThat(peak(rig.output())).isZero();
        assertThat(rig.impacts).isNotEmpty(); // impacts kept their time while muted

        rig.mixer.setMuted(false);
        rig.run(0.4, 1);
        assertThat(peak(rig.output())).isPositive();
    }

    @Test
    void theVolumeScalesTheOutputIncludingSoundAlreadyPlaying() {
        Rig full = rig(9);
        full.mixer.setVolume(1);
        full.run(0.8, 1);
        Rig changed = rig(9);
        changed.mixer.setVolume(1);
        changed.run(0.2, 1);
        changed.mixer.setVolume(0.25);
        changed.run(0.6, 1);

        short[] reference = full.output();
        short[] scaled = changed.output();
        assertThat(peak(reference)).isGreaterThan(1_000);
        int from = (int) (0.2 * MixFormat.SAMPLE_RATE) * MixFormat.CHANNELS; // the first sample after the change
        for (int i = 0; i < reference.length; i++) {
            double expected = i < from ? reference[i] : reference[i] * 0.25;
            assertThat((double) scaled[i]).as("sample %d", i).isCloseTo(expected, within(1.0));
        }
    }

    @Test
    void aVolumeOutsideZeroToOneIsRefused() {
        StreamingMixer mixer = rig(1).mixer;
        assertThatThrownBy(() -> mixer.setVolume(1.01)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> mixer.setVolume(-0.01)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void quietSoundPassesTheLimiterUnchanged() {
        Rig rig = new Rig(Synthetic.bankOf(Synthetic.constant(0.4f, 480)), MixerTunables.DEFAULTS.withJitter(0, 0));
        rig.mixer.setVolume(1);
        ContactEvent hit = floorHit(0.1f, 0, 1f);
        rig.mixer.play(throwOf(recording(1, 0.2f, List.of(hit)), 0));
        rig.run(0.2, 1);

        int frame = (int) rig.impactOf(hit).frame() + 10;
        // 0.4 at full gain, centre-panned (1/√2), times the default headroom of 0.5: well under the knee.
        assertThat((double) rig.output()[2 * frame]).isCloseTo(0.4 * Math.sqrt(0.5) * 0.5 * Short.MAX_VALUE,
                within(1.0));
    }

    @ParameterizedTest
    @ValueSource(doubles = {1, 4})
    void aTwentyDiceFirstLandingNeverClips(double speed) {
        // Every Die lands at once, at well over full scale, with bounces and die-on-die knocks, on full-scale
        // slices, with gain jitter on and the volume at its top.
        List<ContactEvent> events = new ArrayList<>();
        for (int die = 0; die < 20; die++) {
            for (int bounce = 0; bounce < 3; bounce++) {
                events.add(floorHit(0.200f + 0.012f * bounce + 0.0005f * die, die, 5f));
            }
            events.add(dieHit(0.205f + 0.001f * die, die, (die + 1) % 20, 5f));
        }
        Rig rig = new Rig(Synthetic.bankOf(Synthetic.constant(1f, 9_600)), 3, MixerTunables.DEFAULTS);
        rig.mixer.setVolume(1);
        rig.mixer.play(throwOf(recording(20, 0.4f, events), 0));

        rig.run(0.5, speed);

        assertThat(rig.impacts.size()).isGreaterThanOrEqualTo(8);
        int peak = peak(rig.output());
        assertThat(peak).isLessThan(Short.MAX_VALUE);
        assertThat(peak).isGreaterThan(Short.MAX_VALUE / 2); // limited, not silenced
    }
}
