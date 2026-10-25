package io.github.evildarkarchon.andysdice.sound;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.LongSupplier;
import java.util.random.RandomGenerator;

/**
 * The app's dice sound (ADR-0012 "Lifecycle", "Failure means silence"): the mixer on the one audio thread, with a
 * line opened for each Throw that has contacts to hear and closed once the last impact's tail has been heard, so no
 * idle stream keeps Windows from sleeping.
 *
 * <p>The audio thread loads the sample bank when it starts, then opens and closes the device once to learn whether
 * there is one. After that it waits for Throws. A Throw arriving while a line is open joins the sound already
 * playing; Reroll Throws and a queued Roll are just later Throws on the same clock.
 *
 * <p>Sound is optional, so nothing here ever fails the app: no device means silence, and the {@link Listener} is
 * told each time a line can't be opened (the app posts its one Notice). A device that stops taking sound mid-Throw
 * (a headset unplugged) drops the sound in flight; the default device is tried again, silently, at the next
 * Throw. A bank that can't be loaded means silence for the whole run.
 *
 * <p>Threads: {@link #play}, the setters and {@link #close} may be called from any thread.
 */
public final class SoundOutput implements AutoCloseable {

    /** Told what happened to the device. */
    @FunctionalInterface
    public interface Listener {
        /**
         * No line could be opened: at start-up, or for a Throw. Called on the audio thread, every time.
         *
         * @param cause why
         */
        void unavailable(SinkException cause);
    }

    private static final Logger LOG = System.getLogger(SoundOutput.class.getName());

    private final Callable<SampleBank> bank;
    private final RandomGenerator soundRandom;
    private final ClockFollower follower;
    private final AudioSink.Opener opener;
    private final Listener listener;
    private final LongSupplier nanoTime;
    private final long delayNanos;
    private final MixerTunables tunables;
    private final ImpactObserver observer;
    private final LinkedBlockingQueue<List<SoundThrow>> pending = new LinkedBlockingQueue<>();
    private final Thread thread;

    // Set once the bank has loaded; the setters reach the mixer through it. Both sides write their own field, then
    // read the other's (all volatile), so a setting made while the bank loads is never lost.
    private volatile StreamingMixer mixer;
    private volatile double volume = StreamingMixer.DEFAULT_VOLUME;
    private volatile boolean muted;
    private volatile ScreenProjection projection = ScreenProjection.CENTRE;
    private volatile boolean closed;
    // Audio thread only: whether a device has been missing since the last open, so the log says it once.
    private boolean missing;

    private SoundOutput(Callable<SampleBank> bank, RandomGenerator soundRandom, ClockFollower follower,
            AudioSink.Opener opener, Listener listener, LongSupplier nanoTime, Duration audioDelay,
            MixerTunables tunables, ImpactObserver observer) {
        this.bank = Objects.requireNonNull(bank, "bank");
        this.soundRandom = Objects.requireNonNull(soundRandom, "soundRandom");
        this.follower = Objects.requireNonNull(follower, "follower");
        this.opener = Objects.requireNonNull(opener, "opener");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.delayNanos = audioDelay.toNanos();
        this.tunables = Objects.requireNonNull(tunables, "tunables");
        this.observer = Objects.requireNonNull(observer, "observer");
        this.thread = Thread.ofPlatform().name(AudioThread.NAME).daemon().unstarted(this::run);
    }

    /**
     * Starts the sound output on real time, with no A/V offset and the default tunables.
     *
     * @param bank loads the sample bank, on the audio thread ({@code SampleBank::builtIn} in the app)
     * @param soundRandom the sound generator: every variation choice draws from it and nothing else does
     * @param follower the playback clock as the audio thread sees it, on {@link System#nanoTime()}
     * @param opener opens a sink on the default device ({@code LineSink::open} in the app)
     * @param listener told when no device can be opened
     * @return the running output
     */
    public static SoundOutput start(Callable<SampleBank> bank, RandomGenerator soundRandom, ClockFollower follower,
            AudioSink.Opener opener, Listener listener) {
        return start(bank, soundRandom, follower, opener, listener, System::nanoTime, Duration.ZERO,
                MixerTunables.DEFAULTS, ImpactObserver.NONE);
    }

    /**
     * Starts the sound output.
     *
     * @param bank loads the sample bank, on the audio thread
     * @param soundRandom the sound generator
     * @param follower the playback clock as the audio thread sees it, on {@code nanoTime}'s time base
     * @param opener opens a sink on the default device
     * @param listener told when no device can be opened
     * @param nanoTime the real-time source, read once per block
     * @param audioDelay the A/V offset: how much later than its playback moment each sound is heard
     * @param tunables the mixer's shaping numbers
     * @param observer told, on the audio thread, of every impact started and every event dropped
     * @return the running output
     */
    public static SoundOutput start(Callable<SampleBank> bank, RandomGenerator soundRandom, ClockFollower follower,
            AudioSink.Opener opener, Listener listener, LongSupplier nanoTime, Duration audioDelay,
            MixerTunables tunables, ImpactObserver observer) {
        SoundOutput output = new SoundOutput(bank, soundRandom, follower, opener, listener, nanoTime, audioDelay,
                tunables, observer);
        output.thread.start();
        return output;
    }

    /**
     * Queues the Throws of a Roll to be heard, each at its place on the playback clock. If they have contacts and
     * no line is open, the audio thread opens one. After {@link #close}, or if the bank couldn't load, it does
     * nothing.
     *
     * @param throwsOf the accepted Attempts' Recordings, placed on the clock
     */
    public void play(List<SoundThrow> throwsOf) {
        List<SoundThrow> batch = List.copyOf(throwsOf);
        if (!closed && thread.isAlive() && !batch.isEmpty()) {
            pending.add(batch);
        }
    }

    /**
     * Sets the output volume; it applies to sound already playing.
     *
     * @param volume the linear gain, 0 to 1
     * @throws IllegalArgumentException if it is out of range
     */
    public void setVolume(double volume) {
        if (!(volume >= 0 && volume <= 1)) {
            throw new IllegalArgumentException("A volume lies in [0, 1]: " + volume);
        }
        this.volume = volume;
        StreamingMixer m = mixer;
        if (m != null) {
            m.setVolume(volume);
        }
    }

    /**
     * Returns the output volume.
     *
     * @return the linear gain, 0 to 1; {@link StreamingMixer#DEFAULT_VOLUME} until set
     */
    public double volume() {
        return volume;
    }

    /**
     * Mutes or unmutes the output, from the next block.
     *
     * @param muted {@code true} to mute
     */
    public void setMuted(boolean muted) {
        this.muted = muted;
        StreamingMixer m = mixer;
        if (m != null) {
            m.setMuted(muted);
        }
    }

    /**
     * Tests whether the output is muted.
     *
     * @return {@code true} if it is
     */
    public boolean muted() {
        return muted;
    }

    /**
     * Sets how contact points map across the screen; impacts starting from the next block are panned by it.
     *
     * @param projection the current camera's projection, an immutable snapshot
     */
    public void setProjection(ScreenProjection projection) {
        this.projection = Objects.requireNonNull(projection, "projection");
        StreamingMixer m = mixer;
        if (m != null) {
            m.setProjection(projection);
        }
    }

    /**
     * Stops the audio thread after the block it is on, closing any open line, and waits briefly for it to end.
     * What is still playing is dropped. Closing twice does nothing more.
     */
    @Override
    public void close() {
        closed = true;
        // Wakes the thread from waiting for a Throw; a block being written finishes first.
        thread.interrupt();
        try {
            if (!thread.join(Duration.ofSeconds(1))) {
                LOG.log(Level.WARNING, "The audio thread didn't stop within a second");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void run() {
        StreamingMixer m;
        try {
            m = new StreamingMixer(bank.call(), soundRandom, tunables, observer);
        } catch (Exception e) {
            // A packaging fault the tests should have caught; still only sound, so the app carries on silently.
            LOG.log(Level.ERROR, "The sample bank couldn't be loaded; carrying on without sound", e);
            pending.clear();
            return;
        }
        mixer = m;
        m.setVolume(volume);
        m.setMuted(muted);
        m.setProjection(projection);
        probe();
        short[] block = new short[MixFormat.BLOCK_SAMPLES];
        while (!closed) {
            List<SoundThrow> batch;
            try {
                batch = pending.take();
            } catch (InterruptedException e) {
                // close() interrupts to wake the wait; the loop condition sees it is closed.
                continue;
            }
            if (batch.stream().allMatch(t -> t.recording().contacts().isEmpty())) {
                // Only contacts make sound: a Throw with none to hear opens no line.
                continue;
            }
            AudioSink sink = open();
            if (sink == null) {
                // No device: this Throw is silent. The next one tries again.
                continue;
            }
            batch.forEach(m::play);
            try {
                playOut(m, sink, block);
            } catch (SinkException e) {
                // Applied before anything a later Throw queues, since only this thread hands Throws to the mixer.
                m.stop();
                LOG.log(Level.WARNING, "The audio device stopped taking sound; trying it again at the next Throw",
                        e);
            } finally {
                sink.close();
            }
        }
    }

    /**
     * Writes blocks until the mixer has nothing left to play and what was queued in the sink has been heard,
     * handing the mixer any Throw that arrives meanwhile.
     */
    private void playOut(StreamingMixer m, AudioSink sink, short[] block) throws SinkException {
        // Closing a line drops what it has queued, so once the mixer is idle a few silent blocks push the tail out
        // first: as many as the sink had queued, and one more.
        int silentLeft = -1;
        while (!closed) {
            for (List<SoundThrow> batch; (batch = pending.poll()) != null; ) {
                batch.forEach(m::play);
            }
            AudioThread.writeNext(m, follower, sink, nanoTime, delayNanos, block);
            if (!m.idle() || !pending.isEmpty()) {
                silentLeft = -1;
            } else if (silentLeft < 0) {
                silentLeft = Math.ceilDiv(sink.queuedFrames(), MixFormat.BLOCK_FRAMES) + 1;
            } else if (--silentLeft <= 0) {
                return;
            }
        }
    }

    /** Opens the device once at start-up and closes it again, so that a missing one is known (and noticed) early. */
    private void probe() {
        AudioSink sink = open();
        if (sink != null) {
            sink.close();
            LOG.log(Level.INFO, "Sound: the audio device opened");
        }
    }

    /** Opens a line, telling the listener and logging if there is no device; {@code null} then. */
    private AudioSink open() {
        try {
            AudioSink sink = opener.open();
            if (missing) {
                missing = false;
                LOG.log(Level.INFO, "Sound: the audio device is back");
            }
            return sink;
        } catch (SinkException e) {
            if (!missing) {
                missing = true;
                LOG.log(Level.WARNING, "Sound: no audio device could be opened; playing silently", e);
            } else {
                LOG.log(Level.DEBUG, "Sound: still no audio device", e);
            }
            listener.unavailable(e);
            return null;
        }
    }
}
