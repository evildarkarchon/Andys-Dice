package io.github.evildarkarchon.andysdice.sound;

import java.time.Duration;
import java.util.Objects;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

/**
 * The {@code javax.sound.sampled} sink: one {@link SourceDataLine} of the default device, in the mix format
 * (ADR-0012). Its buffer is the sink's queue, 40 ms by default (ADR-0014); if the release checklist hears
 * glitches it goes up to 60 ms before the OpenAL fallback is considered.
 *
 * <p>A line opened without a size gets 500 ms on Windows (research dice-sound §2.2), so the size is always
 * given. Audio thread only.
 */
public final class LineSink implements AudioSink {

    /** The sink's queue by default (ADR-0014; a tunable). */
    public static final Duration DEFAULT_QUEUE = Duration.ofMillis(40);

    private final SourceDataLine line;
    private final byte[] bytes = new byte[MixFormat.BLOCK_FRAMES * MixFormat.BYTES_PER_FRAME];
    private boolean closed;

    private LineSink(SourceDataLine line) {
        this.line = line;
    }

    /**
     * Opens the default device with the default queue.
     *
     * @return the open, started sink
     * @throws SinkException if no device can play the mix format
     */
    public static LineSink open() throws SinkException {
        return open(DEFAULT_QUEUE);
    }

    /**
     * Opens the default device.
     *
     * @param queue how much sound to queue ahead of what is heard
     * @return the open, started sink
     * @throws SinkException if no device can play the mix format
     */
    public static LineSink open(Duration queue) throws SinkException {
        queueBytes(queue); // refuse a bad queue before taking a device
        SourceDataLine line;
        try {
            line = AudioSystem.getSourceDataLine(MixFormat.audioFormat());
        } catch (LineUnavailableException | IllegalArgumentException | SecurityException e) {
            // IllegalArgumentException: no installed device supports the format (or there is no device).
            throw new SinkException("No audio device can play " + MixFormat.audioFormat(), e);
        }
        return open(line, queue);
    }

    /**
     * Opens and starts a given line, closing it again if that fails.
     *
     * @param line the line, not yet open
     * @param queue how much sound to queue ahead of what is heard
     * @return the open, started sink
     * @throws SinkException if the line can't be opened in the mix format
     * @throws IllegalArgumentException if the queue isn't positive
     */
    static LineSink open(SourceDataLine line, Duration queue) throws SinkException {
        Objects.requireNonNull(line, "line");
        int bufferBytes = queueBytes(queue);
        try {
            line.open(MixFormat.audioFormat(), bufferBytes);
            line.start();
            return new LineSink(line);
        } catch (LineUnavailableException | IllegalArgumentException | IllegalStateException
                | SecurityException e) {
            line.close();
            throw new SinkException("The audio line couldn't be opened", e);
        }
    }

    /** The line buffer for a queue: whole frames of the mix format. */
    private static int queueBytes(Duration queue) {
        if (queue.isNegative() || queue.isZero()) {
            throw new IllegalArgumentException("A sink's queue must be positive: " + queue);
        }
        long frames = Math.round(queue.toNanos() * (double) MixFormat.SAMPLE_RATE / 1e9);
        return Math.toIntExact(frames * MixFormat.BYTES_PER_FRAME);
    }

    @Override
    public int queuedFrames() {
        return Math.max(0, line.getBufferSize() - line.available()) / MixFormat.BYTES_PER_FRAME;
    }

    @Override
    public void write(short[] block) throws SinkException {
        if (block.length != MixFormat.BLOCK_SAMPLES) {
            throw new IllegalArgumentException("A block is " + MixFormat.BLOCK_SAMPLES + " samples, not "
                    + block.length);
        }
        for (int i = 0; i < block.length; i++) {
            bytes[2 * i] = (byte) block[i];
            bytes[2 * i + 1] = (byte) (block[i] >> 8);
        }
        // write blocks until everything is queued; it returns short only if the line was stopped or closed
        // under it, which is how a lost device shows.
        int written = line.write(bytes, 0, bytes.length);
        if (written < bytes.length) {
            throw new SinkException("The audio line took " + written + " of " + bytes.length + " bytes", null);
        }
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            line.close();
        }
    }
}
