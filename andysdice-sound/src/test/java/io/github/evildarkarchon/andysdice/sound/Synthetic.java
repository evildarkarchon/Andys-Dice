package io.github.evildarkarchon.andysdice.sound;

import io.github.evildarkarchon.andysdice.core.geometry.DieKind;
import io.github.evildarkarchon.andysdice.core.simulation.ContactBody;
import io.github.evildarkarchon.andysdice.core.simulation.ContactEvent;
import io.github.evildarkarchon.andysdice.core.simulation.Recording;
import io.github.evildarkarchon.andysdice.core.simulation.Vector3;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Synthetic samples, contact events and Recordings for the mixer's tests: no sample bank and no simulator, so
 * every expected value can be worked out by hand.
 */
final class Synthetic {

    /** A world point that projects nowhere in particular (the default projection centres everything). */
    static final Vector3 ORIGIN = Vector3.ZERO;

    /** The Tray surfaces every synthetic Throw is played on. */
    static final TraySounds FELT_AND_WOOD = new TraySounds(SoundCategory.FELT_FLOOR, SoundCategory.WOOD_WALL);

    private Synthetic() {
    }

    /** A slice holding one value throughout, so an impact's onset and level read straight off the output. */
    static Slice constant(float value, int length) {
        float[] samples = new float[length];
        java.util.Arrays.fill(samples, value);
        return Slice.of(samples);
    }

    /** A sine slice of {@code hz} at full scale. */
    static Slice sine(double hz, int length) {
        float[] samples = new float[length];
        for (int i = 0; i < length; i++) {
            samples[i] = (float) Math.sin(2 * Math.PI * hz * i / MixFormat.SAMPLE_RATE);
        }
        return Slice.of(samples);
    }

    /** A bank with the same one slice in every category. */
    static SampleBank bankOf(Slice slice) {
        Map<SoundCategory, List<Slice>> slices = new EnumMap<>(SoundCategory.class);
        for (SoundCategory category : SoundCategory.values()) {
            slices.put(category, List.of(slice));
        }
        return new SampleBank(slices);
    }

    /**
     * A bank with {@code count} slices per category, slice {@code k} of category {@code c} a constant
     * {@code (c * count + k + 1) / 1000}, so the slice an impact used reads off its level.
     */
    static SampleBank distinctBank(int count) {
        Map<SoundCategory, List<Slice>> slices = new EnumMap<>(SoundCategory.class);
        for (SoundCategory category : SoundCategory.values()) {
            List<Slice> list = new ArrayList<>();
            for (int k = 0; k < count; k++) {
                list.add(constant((category.ordinal() * count + k + 1) / 1000f, 64));
            }
            slices.put(category, list);
        }
        return new SampleBank(slices);
    }

    /** A thrown Die striking the floor. */
    static ContactEvent floorHit(float time, int die, float impulse) {
        return new ContactEvent(time, ContactBody.die(die), ContactBody.floor(), impulse, ORIGIN);
    }

    /** A thrown Die striking another thrown Die. */
    static ContactEvent dieHit(float time, int die, int other, float impulse) {
        return new ContactEvent(time, ContactBody.die(die), ContactBody.die(other), impulse, ORIGIN);
    }

    /**
     * A Recording of {@code dice} Dice that all keep moving until {@code restTime}, then lie still, with these
     * contacts (sorted by time here, so a test can list them in any order).
     */
    static Recording recording(int dice, float restTime, List<ContactEvent> contacts) {
        float[] rests = new float[dice];
        java.util.Arrays.fill(rests, restTime);
        return recording(rests, contacts);
    }

    /**
     * A Recording in which Die {@code d} moves (drops 0.1 cm a sample) until the first sample at or after
     * {@code rests[d]}, then lies still; the whole Recording rests at the latest of them.
     */
    static Recording recording(float[] rests, List<ContactEvent> contacts) {
        float last = 0;
        for (float rest : rests) {
            last = Math.max(last, rest);
        }
        int lastSample = (int) Math.ceil(last * Recording.SAMPLE_RATE_HZ - 1e-4);
        int dice = rests.length;
        float[] poses = new float[(lastSample + 1) * dice * 7];
        for (int sample = 0; sample <= lastSample; sample++) {
            for (int die = 0; die < dice; die++) {
                int stops = (int) Math.ceil(rests[die] * Recording.SAMPLE_RATE_HZ - 1e-4);
                int at = (sample * dice + die) * 7;
                poses[at] = die * 3;
                poses[at + 1] = 10 - 0.1f * Math.min(sample, stops);
                poses[at + 6] = 1;
            }
        }
        List<ContactEvent> sorted = new ArrayList<>(contacts);
        sorted.sort((a, b) -> Float.compare(a.time(), b.time()));
        return new Recording(dice, poses, last, sorted);
    }

    /** A Throw of d6s, played from playback time {@code start}. */
    static SoundThrow throwOf(Recording recording, double start) {
        return new SoundThrow(recording, Collections.nCopies(recording.dice(), DieKind.D6), start, FELT_AND_WOOD);
    }

    /** The index of the first frame whose left or right sample isn't zero, or -1 if all are silent. */
    static int onset(short[] interleaved, int fromFrame) {
        for (int frame = fromFrame; frame < interleaved.length / 2; frame++) {
            if (interleaved[2 * frame] != 0 || interleaved[2 * frame + 1] != 0) {
                return frame;
            }
        }
        return -1;
    }
}
