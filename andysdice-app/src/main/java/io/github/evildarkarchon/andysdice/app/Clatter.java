package io.github.evildarkarchon.andysdice.app;

import io.github.evildarkarchon.andysdice.core.playback.PlaybackClock;
import io.github.evildarkarchon.andysdice.sound.AudioSink;
import io.github.evildarkarchon.andysdice.sound.ClockFollower;
import io.github.evildarkarchon.andysdice.sound.ImpactObserver;
import io.github.evildarkarchon.andysdice.sound.MixerTunables;
import io.github.evildarkarchon.andysdice.sound.SampleBank;
import io.github.evildarkarchon.andysdice.sound.SoundOutput;
import io.github.evildarkarchon.andysdice.sound.SoundThrow;
import io.github.evildarkarchon.andysdice.sound.StreamingMixer;
import io.github.evildarkarchon.andysdice.sound.TraySounds;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.random.RandomGenerator;

/**
 * Dice clatter in the app (#59, spec §3.5, ADR-0012): the sound module's {@link SoundOutput} fed with every Throw of
 * each Roll as it starts to play, at its place on the playback clock, sounding like the Tray the Roll was thrown
 * into. Only contacts
 * make sound; the Release, the reroll pickup and the UI give the output nothing to play.
 *
 * <p>No audio device means silence and one Notice for the run, however often the device is tried again.
 */
final class Clatter {

    /** Whether sound is muted (spec §3.2 "Settings tab"): unmuted by default. */
    static final Setting<Boolean> MUTED = Setting.flag("sound.muted", false);

    /** The volume, as a percentage (spec §3.2 "Settings tab"): 70% by default. */
    static final Setting<Integer> VOLUME = Setting.integer("sound.volume",
            (int) Math.round(StreamingMixer.DEFAULT_VOLUME * 100), 0, 100);

    /** The Notice's title when no audio device can be opened (spec §3.2 "Notices"). */
    static final String NO_DEVICE_TITLE = "No audio device";

    /** The Notice's detail. */
    static final String NO_DEVICE_DETAIL = "Rolls are silent. Sound is tried again at each Roll.";

    private Clatter() {}

    /**
     * Starts the app's sound output on real time: the built-in sample bank, the default device, the given sound
     * generator. Call it on the clock's thread (the JavaFX Application Thread), which the output follows.
     *
     * @param clock the playback clock
     * @param soundVariation the sound generator (spec §5), never the Face Value or Release one
     * @param opener opens the default device ({@code LineSink::open})
     * @param notices where the one "No audio device" Notice goes
     * @return the running output; close it when the app stops
     */
    static SoundOutput output(PlaybackClock clock, RandomGenerator soundVariation, AudioSink.Opener opener,
            Notices notices) {
        return output(clock, soundVariation, opener, notices, System::nanoTime);
    }

    /**
     * As {@link #output(PlaybackClock, RandomGenerator, AudioSink.Opener, Notices)}, on a given real-time source:
     * tests move it on as their fake device takes blocks.
     *
     * @param clock the playback clock
     * @param soundVariation the sound generator
     * @param opener opens the default device
     * @param notices where the one "No audio device" Notice goes
     * @param nanoTime the real-time source, in nanoseconds, that both the clock's follower and the audio thread read
     * @return the running output
     */
    static SoundOutput output(PlaybackClock clock, RandomGenerator soundVariation, AudioSink.Opener opener,
            Notices notices, LongSupplier nanoTime) {
        return output(clock, soundVariation, opener, notices, nanoTime, ImpactObserver.NONE);
    }

    /**
     * As {@link #output(PlaybackClock, RandomGenerator, AudioSink.Opener, Notices, LongSupplier)}, telling an
     * observer what the mixer does with each contact: tests hear which Tray's sound each impact was played in.
     *
     * @param clock the playback clock
     * @param soundVariation the sound generator
     * @param opener opens the default device
     * @param notices where the one "No audio device" Notice goes
     * @param nanoTime the real-time source, in nanoseconds, that both the clock's follower and the audio thread read
     * @param impacts told of every impact started or dropped, on the audio thread
     * @return the running output
     */
    static SoundOutput output(PlaybackClock clock, RandomGenerator soundVariation, AudioSink.Opener opener,
            Notices notices, LongSupplier nanoTime, ImpactObserver impacts) {
        Objects.requireNonNull(notices, "notices");
        AtomicBoolean noticed = new AtomicBoolean();
        ClockFollower follower = ClockFollower.follow(clock, nanoTime);
        // The output tells this on every failed try (at start-up and at each Throw); the Player hears of it once.
        SoundOutput.Listener unavailable = cause -> {
            if (noticed.compareAndSet(false, true)) {
                notices.post(NO_DEVICE_TITLE, NO_DEVICE_DETAIL);
            }
        };
        return SoundOutput.start(SampleBank::builtIn, soundVariation, follower, opener, unavailable, nanoTime,
                Duration.ZERO, MixerTunables.DEFAULTS, Objects.requireNonNull(impacts, "impacts"));
    }

    /**
     * Feeds a Roll session's Throws to the sound output: each chain as it starts to play, every Throw at the clock
     * time its Release plays, in the sound of the Tray the Roll was thrown into (#67), whatever Tray has been
     * picked since.
     *
     * @param session the Roll session
     * @param output the sound output
     */
    static void attach(RollSession session, SoundOutput output) {
        Objects.requireNonNull(output, "output");
        session.setOnChainShown((chain, start) -> output.play(throwsOf(chain, start)));
    }

    /**
     * Places every Throw of a chain on the playback clock for the mixer, in the sound of the chain's Tray.
     *
     * @param chain the chain of Throws
     * @param start the clock's time at the chain's first Release
     * @return one Throw to hear per planned Throw, in order, each with its thrown Dice's kinds
     */
    static List<SoundThrow> throwsOf(ChainPlayback chain, double start) {
        TraySounds sounds = TraySounds.of(chain.tray());
        List<SoundThrow> throwsOf = new ArrayList<>();
        for (int k = 0; k < chain.throwCount(); k++) {
            throwsOf.add(new SoundThrow(chain.plan().throwChain().get(k).recording(), chain.thrownKinds(k),
                    start + chain.throwStart(k), sounds));
        }
        return throwsOf;
    }
}
