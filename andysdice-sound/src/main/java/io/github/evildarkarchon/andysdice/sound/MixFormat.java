package io.github.evildarkarchon.andysdice.sound;

import javax.sound.sampled.AudioFormat;

/**
 * The one format the mixer renders and the sink plays (ADR-0012 "Output", ADR-0014): 48 kHz, stereo,
 * signed 16-bit little-endian PCM, in blocks of 480 frames (10 ms).
 */
public final class MixFormat {

    /** Frames per second. */
    public static final int SAMPLE_RATE = 48_000;

    /** Channels per frame: left, then right. */
    public static final int CHANNELS = 2;

    /** Frames per rendered block: 10 ms (ADR-0014; a tunable, not a contract). */
    public static final int BLOCK_FRAMES = 480;

    /** Samples per rendered block, both channels interleaved. */
    public static final int BLOCK_SAMPLES = BLOCK_FRAMES * CHANNELS;

    /** Bytes per frame: two channels of 16 bits. */
    public static final int BYTES_PER_FRAME = CHANNELS * 2;

    private MixFormat() {
    }

    /**
     * Returns the format as {@code javax.sound.sampled} names it.
     *
     * @return 48 kHz stereo signed 16-bit little-endian PCM
     */
    static AudioFormat audioFormat() {
        return new AudioFormat(SAMPLE_RATE, 16, CHANNELS, true, false);
    }
}
