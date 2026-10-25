package io.github.evildarkarchon.andysdice.sound;

/**
 * One single-impact sample (ADR-0012 "Samples"): mono, at {@link MixFormat#SAMPLE_RATE}, as floats in
 * [-1, 1]. The sample bank turns its 16-bit WAVs into these when it loads; tests make synthetic ones.
 *
 * <p>Instances are immutable and safe to share between threads.
 */
public final class Slice {

    private final float[] samples;

    private Slice(float[] samples) {
        this.samples = samples;
    }

    /**
     * Makes a slice from mono samples.
     *
     * @param samples the samples at {@link MixFormat#SAMPLE_RATE}, each finite and in [-1, 1]; copied
     * @return the slice
     * @throws IllegalArgumentException if there are no samples or one is out of range
     */
    public static Slice of(float[] samples) {
        if (samples.length == 0) {
            throw new IllegalArgumentException("A slice needs at least one sample");
        }
        for (float sample : samples) {
            if (!(sample >= -1 && sample <= 1)) {
                throw new IllegalArgumentException("A slice's samples lie in [-1, 1], not " + sample);
            }
        }
        return new Slice(samples.clone());
    }

    /**
     * Returns how many samples it holds.
     *
     * @return the length in frames, at least 1
     */
    public int length() {
        return samples.length;
    }

    /**
     * Returns one sample.
     *
     * @param index the frame
     * @return the sample, in [-1, 1]
     * @throws ArrayIndexOutOfBoundsException if the index is out of range
     */
    public float sample(int index) {
        return samples[index];
    }
}
