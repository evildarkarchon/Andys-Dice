package io.github.evildarkarchon.andysdice.sound;

import static io.github.evildarkarchon.andysdice.sound.Synthetic.floorHit;
import static io.github.evildarkarchon.andysdice.sound.Synthetic.recording;
import static io.github.evildarkarchon.andysdice.sound.Synthetic.throwOf;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.evildarkarchon.andysdice.core.playback.PlaybackClock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The app's sound output (ADR-0012 "Lifecycle", "Failure means silence"): the bank loads on the one audio thread,
 * a line opens for a Throw that has contacts to hear and closes once the last impact's tail has been heard, and a
 * missing or failed device means silence, tried again at the next Throw.
 */
class SoundOutputTest {

    private static final long MS = 1_000_000L;

    /** Real time as the audio thread sees it: each block written moves it on 10 ms, as a device would. */
    private final AtomicLong now = new AtomicLong();
    private final FakeOpener opener = new FakeOpener();
    private final List<SinkException> unavailable = new CopyOnWriteArrayList<>();
    private SoundOutput output;

    /** A sink that keeps every block, queues nothing and can be told to fail. */
    private final class FakeSink implements AudioSink {
        final List<short[]> blocks = new CopyOnWriteArrayList<>();
        volatile boolean closed;
        volatile int failAfter = Integer.MAX_VALUE;

        @Override
        public int queuedFrames() {
            return 0;
        }

        @Override
        public void write(short[] block) throws SinkException {
            if (blocks.size() >= failAfter) {
                throw new SinkException("unplugged", null);
            }
            blocks.add(block.clone());
            now.addAndGet(10 * MS);
        }

        @Override
        public void close() {
            closed = true;
        }

        boolean heardSomething() {
            return blocks.stream().anyMatch(block -> Synthetic.onset(block, 0) >= 0);
        }
    }

    /** Opens fake sinks, or fails as a machine with no device does. */
    private static final class FakeOpener implements AudioSink.Opener {
        final List<FakeSink> sinks = new CopyOnWriteArrayList<>();
        final AtomicInteger attempts = new AtomicInteger();
        volatile boolean noDevice;
        volatile int failNextAfter = Integer.MAX_VALUE;
        private SoundOutputTest test;

        @Override
        public AudioSink open() throws SinkException {
            attempts.incrementAndGet();
            if (noDevice) {
                throw new SinkException("no device", null);
            }
            FakeSink sink = test.new FakeSink();
            sink.failAfter = failNextAfter;
            failNextAfter = Integer.MAX_VALUE;
            sinks.add(sink);
            return sink;
        }
    }

    private SoundOutput start(Callable<SampleBank> bank) {
        opener.test = this;
        PlaybackClock clock = new PlaybackClock();
        ClockFollower follower = ClockFollower.follow(clock, now::get);
        output = SoundOutput.start(bank, new SplittableRandom(3), follower, opener, unavailable::add, now::get,
                Duration.ZERO, MixerTunables.DEFAULTS, ImpactObserver.NONE);
        return output;
    }

    private SoundOutput start() {
        return start(() -> Synthetic.bankOf(Synthetic.constant(0.5f, 480)));
    }

    @AfterEach
    void close() {
        if (output != null) {
            output.close();
        }
    }

    /** A Throw with one floor contact early enough to be heard before the follower stops carrying time on. */
    private static SoundThrow clatter() {
        return throwOf(recording(1, 0.08f, List.of(floorHit(0.02f, 0, 1f))), 0);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as("waited 10 s").isLessThan(deadline);
            // A poll, not a wait on time: the conditions are the fake sink's state as the audio thread leaves it
            // (opened, written to, closed), which SoundOutput signals nowhere a test could block on.
            Thread.sleep(1);
        }
    }

    @Test
    void theBankLoadsOnTheAudioThreadAndTheDeviceIsTriedOnceAtStartUp() throws Exception {
        AtomicReference<String> loadedOn = new AtomicReference<>();
        start(() -> {
            loadedOn.set(Thread.currentThread().getName());
            return Synthetic.bankOf(Synthetic.constant(0.5f, 480));
        });

        await(() -> opener.sinks.size() == 1 && opener.sinks.getFirst().closed);
        assertThat(loadedOn.get()).isEqualTo(AudioThread.NAME);
        assertThat(opener.sinks.getFirst().blocks).as("the probe plays nothing").isEmpty();
        assertThat(unavailable).isEmpty();
    }

    @Test
    void aThrowOpensTheLineWhichClosesOnceTheLastImpactsTailHasBeenHeard() throws Exception {
        start();
        await(() -> opener.attempts.get() == 1);

        output.play(List.of(clatter()));

        await(() -> opener.sinks.size() == 2 && opener.sinks.get(1).closed);
        FakeSink line = opener.sinks.get(1);
        assertThat(line.heardSomething()).isTrue();
        assertThat(Synthetic.onset(line.blocks.getLast(), 0)).as("closed after the tail, not during it")
                .isEqualTo(-1);
        assertThat(line.blocks).as("not kept open once idle").hasSizeLessThan(20);
    }

    @Test
    void aThrowWithNoContactsToHearOpensNoLine() throws Exception {
        start();
        await(() -> opener.attempts.get() == 1);

        output.play(List.of(throwOf(recording(1, 0.08f, List.of()), 0)));
        output.play(List.of(clatter()));

        await(() -> opener.sinks.size() == 2 && opener.sinks.get(1).closed);
        assertThat(opener.attempts.get()).as("the probe, then only the Throw with a contact").isEqualTo(2);
    }

    @Test
    void noDeviceMeansSilenceAndIsTriedAgainAtEachThrow() throws Exception {
        opener.noDevice = true;
        start();
        await(() -> unavailable.size() == 1);

        output.play(List.of(clatter()));
        await(() -> unavailable.size() == 2);
        opener.noDevice = false;
        output.play(List.of(clatter()));

        await(() -> opener.sinks.size() == 1 && opener.sinks.getFirst().closed);
        assertThat(opener.attempts.get()).isEqualTo(3);
        assertThat(opener.sinks.getFirst().heardSomething()).as("the device that came back plays").isTrue();
        assertThat(unavailable).hasSize(2);
    }

    @Test
    void aSinkThatFailsMidThrowIsDroppedSilentlyAndTheDeviceIsTriedAgainAtTheNextThrow() throws Exception {
        start();
        await(() -> opener.attempts.get() == 1);
        opener.failNextAfter = 1;

        output.play(List.of(clatter()));
        await(() -> opener.sinks.size() == 2 && opener.sinks.get(1).closed);
        output.play(List.of(clatter()));

        await(() -> opener.sinks.size() == 3 && opener.sinks.get(2).closed);
        assertThat(opener.sinks.get(2).heardSomething()).isTrue();
        assertThat(unavailable).as("a lost device is retried silently").isEmpty();
    }

    @Test
    void mutedOutputIsSilentAndVolumeAndMuteSetBeforeTheBankLoadsStillApply() throws Exception {
        start();
        output.setMuted(true);
        output.setVolume(0.25);
        await(() -> opener.attempts.get() == 1);

        output.play(List.of(clatter()));

        await(() -> opener.sinks.size() == 2 && opener.sinks.get(1).closed);
        assertThat(opener.sinks.get(1).heardSomething()).isFalse();
        assertThat(output.muted()).isTrue();
        assertThat(output.volume()).isEqualTo(0.25);
    }

    @Test
    void volumeScalesTheOutput() throws Exception {
        List<Integer> peaks = new ArrayList<>();
        for (double volume : new double[] {1, 0.5}) {
            now.set(0);
            opener.sinks.clear();
            opener.attempts.set(0);
            start();
            output.setVolume(volume);
            await(() -> opener.attempts.get() == 1);
            output.play(List.of(clatter()));
            await(() -> opener.sinks.size() == 2 && opener.sinks.get(1).closed);
            peaks.add(opener.sinks.get(1).blocks.stream().flatMapToInt(b -> {
                int[] values = new int[b.length];
                for (int i = 0; i < b.length; i++) {
                    values[i] = Math.abs(b[i]);
                }
                return java.util.Arrays.stream(values);
            }).max().orElseThrow());
            output.close();
        }
        assertThat(peaks.get(1)).isCloseTo(peaks.get(0) / 2, org.assertj.core.api.Assertions.within(2));
    }

    @Test
    void aBankThatCantLoadMeansSilenceWithoutFailing() throws Exception {
        start(() -> {
            throw new java.io.IOException("missing slice");
        });

        output.play(List.of(clatter()));
        output.close();

        assertThat(opener.attempts.get()).isZero();
    }

    @Test
    void closingStopsTheAudioThread() throws Exception {
        AtomicReference<Thread> audio = new AtomicReference<>();
        start(() -> {
            audio.set(Thread.currentThread());
            return Synthetic.bankOf(Synthetic.constant(0.5f, 480));
        });
        await(() -> opener.attempts.get() == 1);

        output.close();

        assertThat(audio.get().isAlive()).isFalse();
        output.play(List.of(clatter()));
        assertThat(opener.attempts.get()).isEqualTo(1);
    }
}
