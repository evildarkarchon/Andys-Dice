package io.github.evildarkarchon.andysdice.sound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import org.junit.jupiter.api.Test;

/**
 * The {@code javax.sound.sampled} sink (ADR-0012, ADR-0014), tested on a stand-in line since CI has no audio
 * device: it opens the mix format with a 40 ms queue by default, reports what is queued, and writes blocks as
 * little-endian 16-bit PCM.
 */
class LineSinkTest {

    /** A stand-in {@link SourceDataLine} that records what it is asked, built as a proxy of the interface. */
    private static final class FakeLine {
        final List<String> calls = new ArrayList<>();
        final ByteArrayOutputStream written = new ByteArrayOutputStream();
        AudioFormat format;
        int bufferSize;
        int available;
        int accept = Integer.MAX_VALUE;
        boolean refuseOpen;

        SourceDataLine line() {
            return (SourceDataLine) Proxy.newProxyInstance(LineSinkTest.class.getClassLoader(),
                    new Class<?>[] {SourceDataLine.class}, (proxy, method, args) -> {
                        calls.add(method.getName());
                        return switch (method.getName()) {
                            case "open" -> {
                                if (refuseOpen) {
                                    throw new LineUnavailableException("busy");
                                }
                                format = (AudioFormat) args[0];
                                bufferSize = (Integer) args[1];
                                yield null;
                            }
                            case "getBufferSize" -> bufferSize;
                            case "available" -> available;
                            case "write" -> {
                                int length = Math.min((Integer) args[2], accept);
                                written.write((byte[]) args[0], (Integer) args[1], length);
                                yield length;
                            }
                            case "toString" -> "FakeLine";
                            default -> null;
                        };
                    });
        }
    }

    @Test
    void itOpensTheMixFormatWithA40msQueueByDefault() throws SinkException {
        FakeLine fake = new FakeLine();

        try (LineSink sink = LineSink.open(fake.line(), LineSink.DEFAULT_QUEUE)) {
            assertThat(LineSink.DEFAULT_QUEUE).isEqualTo(Duration.ofMillis(40));
            assertThat(fake.format.getSampleRate()).isEqualTo(48_000f);
            assertThat(fake.format.getChannels()).isEqualTo(2);
            assertThat(fake.format.getSampleSizeInBits()).isEqualTo(16);
            assertThat(fake.format.getEncoding()).isEqualTo(AudioFormat.Encoding.PCM_SIGNED);
            assertThat(fake.format.isBigEndian()).isFalse();
            assertThat(fake.bufferSize).isEqualTo(1_920 * 4); // 40 ms of 48 kHz stereo 16-bit frames
            assertThat(fake.calls).containsSubsequence("open", "start");
            fake.available = fake.bufferSize;
            assertThat(sink.queuedFrames()).isZero();
        }
        assertThat(fake.calls).endsWith("close");
    }

    @Test
    void aLongerQueueCanBeAsked() throws SinkException {
        FakeLine fake = new FakeLine();
        try (LineSink sink = LineSink.open(fake.line(), Duration.ofMillis(60))) {
            assertThat(fake.bufferSize).isEqualTo(2_880 * 4);
            assertThat(sink.queuedFrames()).isEqualTo(2_880); // nothing heard yet: all of it queued
        }
    }

    @Test
    void itReportsTheFramesQueuedInTheLine() throws SinkException {
        FakeLine fake = new FakeLine();
        try (LineSink sink = LineSink.open(fake.line(), LineSink.DEFAULT_QUEUE)) {
            fake.available = 7_680 - 1_000 * 4;
            assertThat(sink.queuedFrames()).isEqualTo(1_000);
        }
    }

    @Test
    void blocksAreWrittenAsLittleEndianSigned16Bit() throws SinkException {
        FakeLine fake = new FakeLine();
        short[] block = new short[MixFormat.BLOCK_SAMPLES];
        block[0] = 0x1234;
        block[1] = -2;
        block[MixFormat.BLOCK_SAMPLES - 1] = Short.MIN_VALUE;

        try (LineSink sink = LineSink.open(fake.line(), LineSink.DEFAULT_QUEUE)) {
            sink.write(block);
        }

        byte[] bytes = fake.written.toByteArray();
        assertThat(bytes).hasSize(MixFormat.BLOCK_FRAMES * 4);
        assertThat(bytes).startsWith(0x34, 0x12, 0xFE, 0xFF);
        assertThat(bytes[bytes.length - 2]).isEqualTo((byte) 0x00);
        assertThat(bytes[bytes.length - 1]).isEqualTo((byte) 0x80);
    }

    @Test
    void aLineThatStopsAcceptingDataIsAFailure() throws SinkException {
        FakeLine fake = new FakeLine();
        try (LineSink sink = LineSink.open(fake.line(), LineSink.DEFAULT_QUEUE)) {
            fake.accept = 100; // what a line returns once it has been closed under us (a device unplugged)
            assertThatThrownBy(() -> sink.write(new short[MixFormat.BLOCK_SAMPLES]))
                    .isInstanceOf(SinkException.class);
        }
    }

    @Test
    void aLineThatCantOpenIsAFailureAndIsNotLeftOpen() {
        FakeLine fake = new FakeLine();
        fake.refuseOpen = true;

        assertThatThrownBy(() -> LineSink.open(fake.line(), LineSink.DEFAULT_QUEUE))
                .isInstanceOf(SinkException.class)
                .hasCauseInstanceOf(LineUnavailableException.class);
        assertThat(fake.calls).endsWith("close");
    }
}
