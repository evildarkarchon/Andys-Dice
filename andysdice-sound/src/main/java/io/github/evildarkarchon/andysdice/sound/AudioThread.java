package io.github.evildarkarchon.andysdice.sound;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * The one audio thread (spec §5, ADR-0012 "Lifecycle"): it pulls blocks from the mixer and writes them to the
 * sink, so the JavaFX, render and physics threads never touch the sink.
 *
 * <p>Each block is rendered for the moment it will be heard: now, plus what the sink has queued, less the A/V
 * offset. Writing a block waits while the sink's queue is full, so the sink paces the loop.
 *
 * <p>If the sink fails, the thread logs it, drops the sound in flight ({@link StreamingMixer#stop}) and ends;
 * it never throws (ADR-0012 "Failure means silence"). Whoever started it may start another, on a newly opened
 * sink, at the next Throw. The thread closes its sink when it ends.
 *
 * <p>The app plays through {@link SoundOutput}, which runs the same block step ({@link #writeNext}) on its own
 * thread of this name, opening a sink for each Throw and closing it after the last impact's tail.
 */
public final class AudioThread implements AutoCloseable {

    /** The thread's name. */
    public static final String NAME = "andysdice-audio";

    private static final Logger LOG = System.getLogger(AudioThread.class.getName());

    private final StreamingMixer mixer;
    private final ClockFollower follower;
    private final AudioSink sink;
    private final LongSupplier nanoTime;
    private final long delayNanos;
    private final Thread thread;
    private volatile boolean running = true;
    private volatile boolean failed;

    private AudioThread(StreamingMixer mixer, ClockFollower follower, AudioSink sink, LongSupplier nanoTime,
            Duration audioDelay) {
        this.mixer = Objects.requireNonNull(mixer, "mixer");
        this.follower = Objects.requireNonNull(follower, "follower");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.delayNanos = audioDelay.toNanos();
        this.thread = Thread.ofPlatform().name(NAME).daemon().unstarted(this::run);
    }

    /**
     * Starts the audio thread on real time with no A/V offset.
     *
     * @param mixer the mixer to pull blocks from
     * @param follower the playback clock as the audio thread sees it, on {@link System#nanoTime()}
     * @param sink an open sink; the thread owns it from now on and closes it when it ends
     * @return the running thread
     */
    public static AudioThread start(StreamingMixer mixer, ClockFollower follower, AudioSink sink) {
        return start(mixer, follower, sink, System::nanoTime, Duration.ZERO);
    }

    /**
     * Starts the audio thread.
     *
     * @param mixer the mixer to pull blocks from
     * @param follower the playback clock as the audio thread sees it, on {@code nanoTime}'s time base
     * @param sink an open sink; the thread owns it from now on and closes it when it ends
     * @param nanoTime the real-time source, read once per block
     * @param audioDelay the A/V offset (ADR-0012, set on the release checklist): how much later than its
     *     playback moment each sound is heard; positive makes audio later
     * @return the running thread
     */
    public static AudioThread start(StreamingMixer mixer, ClockFollower follower, AudioSink sink,
            LongSupplier nanoTime, Duration audioDelay) {
        AudioThread audio = new AudioThread(mixer, follower, sink, nanoTime, audioDelay);
        audio.thread.start();
        return audio;
    }

    private void run() {
        short[] block = new short[MixFormat.BLOCK_SAMPLES];
        try {
            while (running) {
                writeNext(mixer, follower, sink, nanoTime, delayNanos, block);
            }
        } catch (SinkException e) {
            failed = true;
            mixer.stop();
            LOG.log(Level.WARNING, "The audio device stopped taking sound; carrying on silently", e);
        } finally {
            running = false;
            sink.close();
        }
    }

    /**
     * Renders the next block for the moment it will be heard (now, plus what the sink has queued, less the A/V
     * offset) and writes it, waiting while the sink's queue is full. Audio thread only; {@link SoundOutput}'s
     * loop shares it.
     *
     * @param block a scratch block, overwritten
     * @throws SinkException if the sink stopped taking sound
     */
    static void writeNext(StreamingMixer mixer, ClockFollower follower, AudioSink sink, LongSupplier nanoTime,
            long delayNanos, short[] block) throws SinkException {
        long queued = sink.queuedFrames() * 1_000_000_000L / MixFormat.SAMPLE_RATE;
        ClockFollower.Timing heard = follower.at(nanoTime.getAsLong() + queued - delayNanos);
        mixer.render(heard.time(), heard.speed(), block);
        sink.write(block);
    }

    /**
     * Tests whether the thread is still pulling blocks.
     *
     * @return {@code false} once it has been closed or its sink failed
     */
    public boolean running() {
        return running;
    }

    /**
     * Tests whether the thread ended because its sink failed.
     *
     * @return {@code true} if the sink failed
     */
    public boolean failed() {
        return failed;
    }

    /**
     * Waits for the thread to end.
     *
     * @param timeout how long to wait at most
     * @return {@code true} if it has ended
     * @throws InterruptedException if the waiting thread is interrupted
     */
    public boolean join(Duration timeout) throws InterruptedException {
        return thread.join(timeout);
    }

    /**
     * Stops the thread after the block it is on and waits briefly for it to end, closing the sink. What the
     * sink still has queued is dropped.
     */
    @Override
    public void close() {
        running = false;
        thread.interrupt();
        try {
            if (!thread.join(Duration.ofSeconds(1))) {
                LOG.log(Level.WARNING, "The audio thread didn't stop within a second");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
