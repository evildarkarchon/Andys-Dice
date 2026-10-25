package io.github.evildarkarchon.andysdice.sound;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;

/**
 * Reads one slice of the sample bank from a WAV file (ADR-0012 "Samples"). The slices are authored as mono
 * 16-bit PCM at {@link MixFormat#SAMPLE_RATE}, so nothing is converted or resampled at run time; any other
 * format is refused.
 */
final class Wav {

    private Wav() {}

    /**
     * Reads a mono 16-bit little-endian PCM WAV at the mix rate as a slice.
     *
     * @param in the file's bytes; read to the end but not closed
     * @param name what to call the file in an error
     * @return the slice, each sample scaled from 16 bits into [-1, 1]
     * @throws IOException if it can't be read, isn't a WAV, isn't in that format or holds no samples
     */
    static Slice read(InputStream in, String name) throws IOException {
        // AudioSystem needs mark/reset to sniff the file type.
        try (AudioInputStream audio = AudioSystem.getAudioInputStream(new BufferedInputStream(in))) {
            AudioFormat format = audio.getFormat();
            if (!AudioFormat.Encoding.PCM_SIGNED.equals(format.getEncoding()) || format.getChannels() != 1
                    || format.getSampleSizeInBits() != 16 || format.isBigEndian()
                    || format.getSampleRate() != MixFormat.SAMPLE_RATE) {
                throw new IOException(name + " isn't a mono 16-bit " + MixFormat.SAMPLE_RATE + " Hz PCM WAV: "
                        + format);
            }
            byte[] bytes = audio.readAllBytes();
            int frames = bytes.length / 2;
            if (frames == 0) {
                throw new IOException(name + " holds no samples");
            }
            float[] samples = new float[frames];
            for (int i = 0; i < frames; i++) {
                short sample = (short) ((bytes[2 * i] & 0xff) | (bytes[2 * i + 1] << 8));
                samples[i] = sample / 32768f;
            }
            return Slice.of(samples);
        } catch (UnsupportedAudioFileException e) {
            throw new IOException(name + " isn't a WAV file", e);
        }
    }
}
