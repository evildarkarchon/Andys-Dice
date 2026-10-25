package io.github.evildarkarchon.andysdice.sound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import org.junit.jupiter.api.Test;

/** Reading the sample bank's WAVs: mono 16-bit PCM at the mix rate, and nothing else, with no conversion. */
class WavTest {

    /** A WAV file in {@code format} holding these little- or big-endian bytes as its frames. */
    private static ByteArrayInputStream wav(AudioFormat format, byte[] frames) throws IOException {
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        AudioSystem.write(new AudioInputStream(new ByteArrayInputStream(frames), format,
                frames.length / format.getFrameSize()), AudioFileFormat.Type.WAVE, file);
        return new ByteArrayInputStream(file.toByteArray());
    }

    private static AudioFormat format(float rate, int bits, int channels) {
        return new AudioFormat(rate, bits, channels, true, false);
    }

    @Test
    void readsSixteenBitSamplesIntoTheUnitRange() throws IOException {
        // Little-endian 16-bit: 16384, -32768, 32767, -1.
        byte[] frames = {0x00, 0x40, 0x00, (byte) 0x80, (byte) 0xff, 0x7f, (byte) 0xff, (byte) 0xff};

        Slice slice = Wav.read(wav(format(48_000, 16, 1), frames), "test.wav");

        assertThat(slice.length()).isEqualTo(4);
        assertThat(slice.sample(0)).isEqualTo(0.5f);
        assertThat(slice.sample(1)).isEqualTo(-1f);
        assertThat(slice.sample(2)).isEqualTo(32767 / 32768f);
        assertThat(slice.sample(3)).isEqualTo(-1 / 32768f);
    }

    @Test
    void refusesAnyOtherFormat() throws IOException {
        byte[] four = new byte[16];
        assertThatThrownBy(() -> Wav.read(wav(format(44_100, 16, 1), four), "rate.wav"))
                .isInstanceOf(IOException.class).hasMessageContaining("rate.wav");
        assertThatThrownBy(() -> Wav.read(wav(format(48_000, 16, 2), four), "stereo.wav"))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> Wav.read(wav(format(48_000, 24, 1), new byte[12]), "24-bit.wav"))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> Wav.read(wav(format(48_000, 16, 1), new byte[0]), "empty.wav"))
                .isInstanceOf(IOException.class).hasMessageContaining("no samples");
        assertThatThrownBy(() -> Wav.read(new ByteArrayInputStream("not a wav".getBytes()), "text.wav"))
                .isInstanceOf(IOException.class).hasMessageContaining("text.wav");
    }
}
