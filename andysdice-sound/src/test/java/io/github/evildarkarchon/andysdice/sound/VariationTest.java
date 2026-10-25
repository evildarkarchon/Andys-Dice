package io.github.evildarkarchon.andysdice.sound;

import static io.github.evildarkarchon.andysdice.sound.Synthetic.floorHit;
import static io.github.evildarkarchon.andysdice.sound.Synthetic.recording;
import static io.github.evildarkarchon.andysdice.sound.Synthetic.throwOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.github.evildarkarchon.andysdice.core.geometry.DieKind;
import io.github.evildarkarchon.andysdice.core.simulation.ContactBody;
import io.github.evildarkarchon.andysdice.core.simulation.ContactEvent;
import io.github.evildarkarchon.andysdice.core.simulation.Recording;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Variation (ADR-0012): each category's slices shuffled with no repeat of the last 3, ±1 semitone of pitch
 * jitter, ±1.5 dB of gain jitter and a fixed pitch offset per Die Kind, every choice from the sound generator.
 */
class VariationTest {

    /** {@code count} floor hits of Die 0, 60 ms apart (never merged), alternately on the floor and a wall. */
    private static List<ContactEvent> hits(int count, boolean alternateWall) {
        List<ContactEvent> events = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ContactBody other = alternateWall && i % 2 == 1 ? ContactBody.wall() : ContactBody.floor();
            events.add(new ContactEvent(0.05f + 0.06f * i, ContactBody.die(0), other, 0.5f, Synthetic.ORIGIN));
        }
        return events;
    }

    private static Rig run(SampleBank bank, long seed, MixerTunables tunables, List<ContactEvent> events) {
        Rig rig = new Rig(bank, seed, tunables);
        float last = events.getLast().time();
        rig.mixer.play(throwOf(recording(1, last + 0.1f, events), 0));
        return rig.run(last + 0.3, 1);
    }

    @Test
    void aCategorysSlicesNeverRepeatAnyOfTheLastThree() {
        Rig rig = run(Synthetic.distinctBank(8), 7, MixerTunables.DEFAULTS, hits(200, true));

        Map<SoundCategory, List<Integer>> byCategory = rig.impacts.stream().collect(Collectors.groupingBy(
                Impact::category, () -> new EnumMap<>(SoundCategory.class),
                Collectors.mapping(Impact::slice, Collectors.toList())));
        assertThat(byCategory).containsOnlyKeys(SoundCategory.FELT_FLOOR, SoundCategory.WOOD_WALL);
        for (List<Integer> slices : byCategory.values()) {
            assertThat(slices).hasSize(100);
            for (int i = 1; i < slices.size(); i++) {
                assertThat(slices.subList(Math.max(0, i - 3), i)).doesNotContain(slices.get(i));
            }
            assertThat(slices).containsAll(List.of(0, 1, 2, 3, 4, 5, 6, 7));
        }
    }

    @Test
    void theOutputPlaysThePickedSlice() {
        Rig rig = run(Synthetic.distinctBank(8), 3, MixerTunables.DEFAULTS.withJitter(0, 0), hits(10, false));

        short[] out = rig.output();
        // Slice k of the felt floor holds (k + 1) / 1000, so its level, through the pan, the headroom (0.5) and
        // the default volume (0.7), says k.
        for (Impact impact : rig.impacts) {
            int frame = (int) impact.frame() + 5;
            double level = out[2 * frame] / (Short.MAX_VALUE * impact.left() * impact.gain() * 0.5 * 0.7);
            assertThat(level).isCloseTo((impact.slice() + 1) / 1000.0, within(0.0002));
        }
    }

    @Test
    void pitchAndGainJitterStayWithinOneSemitoneAndOneAndAHalfDecibels() {
        Rig rig = run(Synthetic.distinctBank(8), 11, MixerTunables.DEFAULTS, hits(200, false));

        double[] semitones = rig.impacts.stream().mapToDouble(Impact::semitones).toArray();
        double[] decibels = rig.impacts.stream().mapToDouble(i -> 20 * Math.log10(i.gain() / 0.5)).toArray();
        assertThat(Arrays.stream(semitones).boxed().toList()).allSatisfy(s -> assertThat(s).isBetween(-1.0, 1.0));
        assertThat(Arrays.stream(decibels).boxed().toList()).allSatisfy(db -> assertThat(db).isBetween(-1.5, 1.5));
        // And they really vary across the range.
        assertThat(Arrays.stream(semitones).max().orElseThrow() - Arrays.stream(semitones).min().orElseThrow())
                .isGreaterThan(1.6);
        assertThat(Arrays.stream(decibels).max().orElseThrow() - Arrays.stream(decibels).min().orElseThrow())
                .isGreaterThan(2.4);
    }

    @Test
    void eachDieKindHasAFixedPitchOffsetFromItsSizeSmallerDiceHigher() {
        DieKind[] kinds = DieKind.values();
        List<ContactEvent> events = new ArrayList<>();
        for (int round = 0; round < 3; round++) {
            for (int die = 0; die < kinds.length; die++) {
                events.add(floorHit(0.05f + 0.06f * (round * kinds.length + die), die, 0.5f));
            }
        }
        float last = events.getLast().time();
        Recording recording = recording(kinds.length, last + 0.1f, events);
        Rig rig = new Rig(Synthetic.distinctBank(8), 5, MixerTunables.DEFAULTS.withJitter(0, 0));
        rig.mixer.play(new SoundThrow(recording, List.of(kinds), 0, Synthetic.FELT_AND_WOOD));
        rig.run(last + 0.3, 1);

        Map<DieKind, List<Double>> offsets = new EnumMap<>(DieKind.class);
        for (Impact impact : rig.impacts) {
            offsets.computeIfAbsent(kinds[impact.event().die().index()], k -> new ArrayList<>())
                    .add(impact.semitones());
        }
        assertThat(offsets).hasSize(kinds.length);
        offsets.values().forEach(list -> assertThat(list).hasSize(3).containsOnly(list.getFirst()));
        double d4 = offsets.get(DieKind.D4).getFirst();
        double d8 = offsets.get(DieKind.D8).getFirst();
        double d6 = offsets.get(DieKind.D6).getFirst();
        double d12 = offsets.get(DieKind.D12).getFirst();
        double d20 = offsets.get(DieKind.D20).getFirst();
        assertThat(d6).isZero();
        assertThat(offsets.get(DieKind.D10).getFirst()).isZero(); // the same 8 mm inradius as the d6
        assertThat(d4).isGreaterThan(d8);
        assertThat(d8).isGreaterThan(d6);
        assertThat(d6).isGreaterThan(d12);
        assertThat(d12).isGreaterThan(d20);
    }

    @Test
    void aHigherPitchIsPlayedByResamplingSoTheSliceIsShorter() {
        Rig rig = new Rig(Synthetic.bankOf(Synthetic.constant(0.5f, 4_800)),
                MixerTunables.DEFAULTS.withJitter(0, 0));
        ContactEvent hit = floorHit(0.1f, 0, 0.5f);
        rig.mixer.play(new SoundThrow(recording(1, 0.2f, List.of(hit)), List.of(DieKind.D4), 0,
                Synthetic.FELT_AND_WOOD));
        rig.run(0.3, 1);

        Impact impact = rig.impactOf(hit);
        short[] out = rig.output();
        int frames = 0;
        for (int frame = (int) impact.frame(); out[2 * frame] != 0; frame++) {
            frames++;
        }
        assertThat((double) frames).isCloseTo(4_800 / Math.pow(2, impact.semitones() / 12), within(2.0));
    }

    @Test
    void everyChoiceComesFromTheSoundGeneratorSoItsSeedDecidesThem() {
        List<ContactEvent> events = hits(60, true);
        Rig first = run(Synthetic.distinctBank(8), 42, MixerTunables.DEFAULTS, events);
        Rig again = run(Synthetic.distinctBank(8), 42, MixerTunables.DEFAULTS, events);
        Rig other = run(Synthetic.distinctBank(8), 43, MixerTunables.DEFAULTS, events);

        assertThat(again.output()).isEqualTo(first.output());
        assertThat(again.impacts).extracting(Impact::slice, Impact::semitones, Impact::gain)
                .isEqualTo(first.impacts.stream().map(i -> org.assertj.core.groups.Tuple.tuple(
                        i.slice(), i.semitones(), i.gain())).toList());
        assertThat(other.output()).isNotEqualTo(first.output());
    }
}
