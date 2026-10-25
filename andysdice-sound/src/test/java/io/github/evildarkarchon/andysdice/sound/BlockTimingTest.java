package io.github.evildarkarchon.andysdice.sound;

import static io.github.evildarkarchon.andysdice.sound.Synthetic.floorHit;
import static io.github.evildarkarchon.andysdice.sound.Synthetic.recording;
import static io.github.evildarkarchon.andysdice.sound.Synthetic.throwOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.github.evildarkarchon.andysdice.core.playback.PlaybackClock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * The audio thread's block step (ADR-0012 "Sync"), {@link SoundOutput#writeNext}: each block pulled from the mixer is
 * rendered for the moment it will be heard (now, plus what the sink has queued, less the A/V offset) and written to
 * the sink. Driven here on the test thread, block by block, on a scripted real time.
 */
class BlockTimingTest {

    private static final long MS = 1_000_000L;

    /** A sink that keeps every block written and claims 40 ms queued. */
    private static final class FakeSink implements AudioSink {
        final List<short[]> blocks = new ArrayList<>();

        @Override
        public int queuedFrames() {
            return 1_920;
        }

        @Override
        public void write(short[] block) {
            blocks.add(block.clone());
        }

        @Override
        public void close() {
            // Nothing to release: the test only reads the blocks back.
        }
    }

    @Test
    void blocksAreRenderedForWhenTheyWillBeHeard() throws Exception {
        // Real time moves on 10 ms per block rendered; the clock was last advanced at 0, to 0.
        short[] heard = writeSixBlocks(Duration.ZERO);

        // Block k is heard 40 ms (the queue) after it is rendered at k × 10 ms, so it plays 0.04 + 0.01 k s of
        // playback: the 0.065 s contact lands 5 ms into block 2.
        assertThat(Synthetic.onset(heard, 0)).isCloseTo(2 * MixFormat.BLOCK_FRAMES + 240, within(1));
    }

    @Test
    void anAudioDelayRendersEachBlockForAnEarlierMoment() throws Exception {
        short[] heard = writeSixBlocks(Duration.ofMillis(20));

        // 20 ms of delay: block k plays 0.02 + 0.01 k s, so the contact is 5 ms into block 4.
        assertThat(Synthetic.onset(heard, 0)).isCloseTo(4 * MixFormat.BLOCK_FRAMES + 240, within(1));
    }

    /**
     * Writes six blocks of a Throw with one floor contact at 0.065 s, real time moving on 10 ms a block from 0, and
     * returns them end to end.
     */
    private static short[] writeSixBlocks(Duration audioDelay) throws SinkException {
        AtomicLong now = new AtomicLong(-10 * MS);
        ClockFollower follower = ClockFollower.follow(new PlaybackClock(), () -> 0);
        StreamingMixer mixer = new StreamingMixer(Synthetic.bankOf(Synthetic.constant(0.5f, 4_800)),
                new SplittableRandom(1));
        mixer.play(throwOf(recording(1, 0.2f, List.of(floorHit(0.065f, 0, 1f))), 0));
        FakeSink sink = new FakeSink();

        short[] block = new short[MixFormat.BLOCK_SAMPLES];
        for (int k = 0; k < 6; k++) {
            SoundOutput.writeNext(mixer, follower, sink, () -> now.addAndGet(10 * MS), audioDelay.toNanos(), block);
        }

        short[] heard = new short[6 * MixFormat.BLOCK_SAMPLES];
        for (int k = 0; k < 6; k++) {
            System.arraycopy(sink.blocks.get(k), 0, heard, k * MixFormat.BLOCK_SAMPLES, MixFormat.BLOCK_SAMPLES);
        }
        return heard;
    }
}
