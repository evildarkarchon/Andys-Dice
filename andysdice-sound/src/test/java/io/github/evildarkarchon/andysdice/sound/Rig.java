package io.github.evildarkarchon.andysdice.sound;

import io.github.evildarkarchon.andysdice.core.simulation.ContactEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;

/**
 * A mixer under test, driven block by block the way the audio thread drives it, but with the playback time of
 * each block worked out here instead of from a wall clock: every block is heard 10 ms of real time after the
 * last, so it covers {@code 10 ms × speed} of playback.
 *
 * <p>It records every impact started and every event dropped, and keeps all the output.
 */
final class Rig implements ImpactObserver {

    /** One dropped event. */
    record Drop(SoundThrow soundThrow, ContactEvent event, DropReason reason) {
    }

    final StreamingMixer mixer;
    final List<Impact> impacts = new ArrayList<>();
    final List<Drop> drops = new ArrayList<>();
    private short[] output = new short[0];
    private int frames;
    private double time;

    Rig(SampleBank bank, long seed, MixerTunables tunables) {
        mixer = new StreamingMixer(bank, new SplittableRandom(seed), tunables, this);
    }

    Rig(SampleBank bank, MixerTunables tunables) {
        this(bank, 1, tunables);
    }

    @Override
    public void started(Impact impact) {
        impacts.add(impact);
    }

    @Override
    public void dropped(SoundThrow soundThrow, ContactEvent event, DropReason reason) {
        drops.add(new Drop(soundThrow, event, reason));
    }

    /** The playback time the next block will be heard at. */
    double time() {
        return time;
    }

    /** Renders whole blocks covering about {@code realSeconds} of real time at {@code speed}. */
    Rig run(double realSeconds, double speed) {
        int blocks = (int) Math.round(realSeconds * MixFormat.SAMPLE_RATE / MixFormat.BLOCK_FRAMES);
        short[] block = new short[MixFormat.BLOCK_SAMPLES];
        for (int i = 0; i < blocks; i++) {
            mixer.render(time, speed, block);
            time += speed * MixFormat.BLOCK_FRAMES / MixFormat.SAMPLE_RATE;
            if (output.length < (frames + MixFormat.BLOCK_FRAMES) * 2) {
                output = Arrays.copyOf(output, Math.max(output.length * 2, (frames + MixFormat.BLOCK_FRAMES) * 2));
            }
            System.arraycopy(block, 0, output, frames * 2, block.length);
            frames += MixFormat.BLOCK_FRAMES;
        }
        return this;
    }

    /** Every frame rendered so far, interleaved left, right. */
    short[] output() {
        return Arrays.copyOf(output, frames * 2);
    }

    /** The impact that played {@code event}, failing if none did. */
    Impact impactOf(ContactEvent event) {
        return impacts.stream().filter(i -> i.event() == event).findFirst()
                .orElseThrow(() -> new AssertionError("no impact for " + event + "; dropped: " + drops));
    }
}
