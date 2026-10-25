package io.github.evildarkarchon.andysdice.core.simulation;

import java.util.List;
import java.util.Objects;

/**
 * An Attempt's motion of every thrown Die and the contacts it made, in the simulator's engine-neutral terms
 * (ADR-0003). This is what playback and sound consume; nothing engine-specific crosses into it.
 *
 * <p>Sample {@code i} is taken {@code i / }{@value #SAMPLE_RATE_HZ}{@code  s} after the Release, so sample 0 is
 * the Release itself (ADR-0014: every 2nd step of a 240 Hz simulation). The last sample is the first one at or
 * after {@link #restTime()}, so the Recording always ends with every Die where it stopped.
 *
 * <p>Instances are immutable and safe to share between threads.
 */
public final class Recording {

    /** Samples per second of simulated time (ADR-0014). */
    public static final int SAMPLE_RATE_HZ = 120;

    /** Floats per Die per sample: position x, y, z (cm), then orientation x, y, z, w. */
    private static final int FLOATS_PER_POSE = 7;

    /** A small slack for float rounding when comparing times that should line up with samples. */
    private static final float TIME_EPSILON = 1e-5f;

    private final int dice;
    private final float[] poses;
    private final float restTime;
    private final List<ContactEvent> contacts;

    /**
     * Builds a Recording from raw sample data.
     *
     * @param dice how many Dice it records, in the request's order
     * @param poses per sample, per Die: x, y, z in cm, then the orientation's x, y, z, w; copied
     * @param restTime the simulated time, in seconds, at which every Die was at rest; for an Attempt that ended
     *     otherwise, the time it stopped. It lies within one sample before the last sample
     * @param contacts the contact events in time order, none after the last sample; copied
     * @throws IllegalArgumentException if the data isn't whole samples of every Die, the rest time doesn't fit
     *     the samples, or a contact is out of order, out of range or names a Die not recorded
     */
    public Recording(int dice, float[] poses, float restTime, List<ContactEvent> contacts) {
        if (dice < 1) {
            throw new IllegalArgumentException("A Recording must record at least one Die: " + dice);
        }
        int perSample = dice * FLOATS_PER_POSE;
        if (poses.length == 0 || poses.length % perSample != 0) {
            throw new IllegalArgumentException(
                    "Pose data of " + poses.length + " floats isn't whole samples of " + dice + " Dice");
        }
        this.dice = dice;
        this.poses = poses.clone();
        this.contacts = List.copyOf(contacts);

        float end = duration();
        if (!(restTime <= end + TIME_EPSILON) || !(restTime > end - 1f / SAMPLE_RATE_HZ - TIME_EPSILON)
                || restTime < 0) {
            throw new IllegalArgumentException("A rest time of " + restTime + " s doesn't fit samples ending at "
                    + end + " s: it must be within one sample before the last");
        }
        this.restTime = restTime;

        float previous = 0;
        for (ContactEvent contact : this.contacts) {
            if (contact.time() < previous || contact.time() > end + TIME_EPSILON) {
                throw new IllegalArgumentException("Contact at " + contact.time()
                        + " s is out of order or after the last sample at " + end + " s");
            }
            for (ContactBody body : List.of(contact.die(), contact.other())) {
                if (body.kind() == BodyKind.DIE && body.index() >= dice) {
                    throw new IllegalArgumentException("Contact names Die " + body.index() + " of " + dice);
                }
            }
            previous = contact.time();
        }
    }

    /**
     * Returns how many Dice this records.
     *
     * @return the Die count, in the request's order
     */
    public int dice() {
        return dice;
    }

    /**
     * Returns how many samples this holds, the Release's included.
     *
     * @return the sample count, at least 1
     */
    public int samples() {
        return poses.length / (dice * FLOATS_PER_POSE);
    }

    /**
     * Returns when a sample was taken.
     *
     * @param sample the sample index
     * @return its simulated time in seconds after the Release
     */
    public float sampleTime(int sample) {
        Objects.checkIndex(sample, samples());
        return sample / (float) SAMPLE_RATE_HZ;
    }

    /**
     * Returns the time of the last sample.
     *
     * @return seconds of simulated time the Recording covers
     */
    public float duration() {
        return (samples() - 1) / (float) SAMPLE_RATE_HZ;
    }

    /**
     * Returns a Die's pose at one sample.
     *
     * @param die the Die's index in the request's order
     * @param sample the sample index
     * @return the pose, in cm
     */
    public Pose pose(int die, int sample) {
        Objects.checkIndex(die, dice);
        Objects.checkIndex(sample, samples());
        int at = (sample * dice + die) * FLOATS_PER_POSE;
        return new Pose(
                new Vector3(poses[at], poses[at + 1], poses[at + 2]),
                new Orientation(poses[at + 3], poses[at + 4], poses[at + 5], poses[at + 6]));
    }

    /**
     * Returns where a Die ended.
     *
     * @param die the Die's index in the request's order
     * @return its pose at the last sample
     */
    public Pose finalPose(int die) {
        return pose(die, samples() - 1);
    }

    /**
     * Returns when every Die was at rest.
     *
     * @return seconds of simulated time after the Release; for an Attempt that didn't come to rest, when it
     *     stopped
     */
    public float restTime() {
        return restTime;
    }

    /**
     * Returns the contact events.
     *
     * @return the events in time order; unmodifiable
     */
    public List<ContactEvent> contacts() {
        return contacts;
    }
}
