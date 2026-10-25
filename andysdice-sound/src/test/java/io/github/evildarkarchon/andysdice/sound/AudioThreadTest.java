package io.github.evildarkarchon.andysdice.sound;

import static io.github.evildarkarchon.andysdice.sound.Synthetic.floorHit;
import static io.github.evildarkarchon.andysdice.sound.Synthetic.recording;
import static io.github.evildarkarchon.andysdice.sound.Synthetic.throwOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.github.evildarkarchon.andysdice.core.playback.PlaybackClock;
import java.time.Duration;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * The one audio thread (ADR-0012 "Lifecycle"): it pulls blocks from the mixer, each rendered for the moment it
 * will be heard (now plus what the sink has queued), and writes them to the sink, until closed or the sink
 * fails.
 */
class AudioThreadTest {

    private static final long MS = 1_000_000L;

    /** A sink that hands each block to the test, holding at most two, and claims 40 ms queued. */
    private static final class FakeSink implements AudioSink {
        final BlockingQueue<short[]> blocks = new ArrayBlockingQueue<>(2);
        final CountDownLatch closed = new CountDownLatch(1);
        volatile Thread writer;
        volatile int failAfter = Integer.MAX_VALUE;
        private int writes;

        @Override
        public int queuedFrames() {
            return 1_920;
        }

        @Override
        public void write(short[] block) throws SinkException {
            writer = Thread.currentThread();
            if (writes++ >= failAfter) {
                throw new SinkException("unplugged", null);
            }
            try {
                blocks.put(block.clone());
            } catch (InterruptedException e) {
                // Closing interrupts the audio thread; a real line is unblocked by closing it instead.
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }

    @Test
    void blocksAreRenderedForWhenTheyWillBeHeard() throws Exception {
        PlaybackClock clock = new PlaybackClock();
        // Real time moves on 10 ms per block the thread renders; the clock was last advanced at 0, to 0.
        AtomicLong now = new AtomicLong(-10 * MS);
        ClockFollower follower = ClockFollower.follow(clock, () -> 0);
        StreamingMixer mixer = new StreamingMixer(Synthetic.bankOf(Synthetic.constant(0.5f, 4_800)),
                new SplittableRandom(1));
        mixer.play(throwOf(recording(1, 0.2f, List.of(floorHit(0.065f, 0, 1f))), 0));
        FakeSink sink = new FakeSink();

        short[] heard = new short[6 * MixFormat.BLOCK_SAMPLES];
        try (AudioThread thread = AudioThread.start(mixer, follower, sink, () -> now.addAndGet(10 * MS),
                Duration.ZERO)) {
            for (int block = 0; block < 6; block++) {
                short[] next = sink.blocks.poll(5, TimeUnit.SECONDS);
                assertThat(next).isNotNull();
                System.arraycopy(next, 0, heard, block * MixFormat.BLOCK_SAMPLES, next.length);
            }
            assertThat(sink.writer.getName()).isEqualTo(AudioThread.NAME);
            assertThat(sink.writer.isDaemon()).isTrue();
            assertThat(thread.running()).isTrue();
        }

        // Block k is heard 40 ms (the queue) after it is rendered at k × 10 ms, so it plays 0.04 + 0.01 k s of
        // playback: the 0.065 s contact lands 5 ms into block 2.
        assertThat(Synthetic.onset(heard, 0)).isCloseTo(2 * MixFormat.BLOCK_FRAMES + 240, within(1));
        assertThat(sink.closed.await(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void aFailedSinkEndsTheThreadSilentlyAndDropsTheSound() throws Exception {
        StreamingMixer mixer = new StreamingMixer(Synthetic.bankOf(Synthetic.constant(0.5f, 4_800)),
                new SplittableRandom(1));
        mixer.play(throwOf(recording(1, 0.2f, List.of(floorHit(0.065f, 0, 1f))), 0));
        FakeSink sink = new FakeSink();
        sink.failAfter = 1;

        try (AudioThread thread = AudioThread.start(mixer, ClockFollower.follow(new PlaybackClock(), () -> 0),
                sink, () -> 0, Duration.ZERO)) {
            assertThat(sink.closed.await(5, TimeUnit.SECONDS)).isTrue();
            thread.join(Duration.ofSeconds(5));
            assertThat(thread.failed()).isTrue();
            assertThat(thread.running()).isFalse();
        }
        short[] block = new short[MixFormat.BLOCK_SAMPLES];
        mixer.render(0.07, 1, block);
        assertThat(mixer.idle()).isTrue(); // the sound in flight was dropped
    }

    @Test
    void anAudioDelayRendersEachBlockForAnEarlierMoment() throws Exception {
        AtomicLong now = new AtomicLong(-10 * MS);
        StreamingMixer mixer = new StreamingMixer(Synthetic.bankOf(Synthetic.constant(0.5f, 4_800)),
                new SplittableRandom(1));
        mixer.play(throwOf(recording(1, 0.2f, List.of(floorHit(0.065f, 0, 1f))), 0));
        FakeSink sink = new FakeSink();

        short[] heard = new short[6 * MixFormat.BLOCK_SAMPLES];
        try (AudioThread thread = AudioThread.start(mixer, ClockFollower.follow(new PlaybackClock(), () -> 0),
                sink, () -> now.addAndGet(10 * MS), Duration.ofMillis(20))) {
            for (int block = 0; block < 6; block++) {
                System.arraycopy(sink.blocks.poll(5, TimeUnit.SECONDS), 0, heard,
                        block * MixFormat.BLOCK_SAMPLES, MixFormat.BLOCK_SAMPLES);
            }
            assertThat(thread.failed()).isFalse();
        }

        // 20 ms of delay: block k plays 0.02 + 0.01 k s, so the contact is 5 ms into block 4.
        assertThat(Synthetic.onset(heard, 0)).isCloseTo(4 * MixFormat.BLOCK_FRAMES + 240, within(1));
    }
}
