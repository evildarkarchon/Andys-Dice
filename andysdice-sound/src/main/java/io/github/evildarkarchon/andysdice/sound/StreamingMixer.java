package io.github.evildarkarchon.andysdice.sound;

import io.github.evildarkarchon.andysdice.core.geometry.DieKind;
import io.github.evildarkarchon.andysdice.core.simulation.BodyKind;
import io.github.evildarkarchon.andysdice.core.simulation.ContactBody;
import io.github.evildarkarchon.andysdice.core.simulation.ContactEvent;
import io.github.evildarkarchon.andysdice.core.simulation.Recording;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.random.RandomGenerator;

/**
 * Our own streaming mixer (ADR-0012): it renders the impacts of the Throws it has been given, one block at a
 * time, for the playback time each block will be heard at.
 *
 * <p><b>Timing.</b> {@link #render} is handed the playback time at which the block's first frame will be heard
 * and the clock's current speed, so the block covers {@code 10 ms × speed} of playback. An event starts at the
 * frame its playback time falls on, and then plays at its own pitch in real time: at ×4 only the timing
 * compresses. A speed change re-maps every start not yet reached.
 *
 * <p><b>Shaping</b>, applied as it plays, since speed changes the density per real-time window:
 * <ul>
 *   <li>gain = clamp(J / J_max, 0, 1) × jitter, with separate J_max for die–die and die–tray;</li>
 *   <li>events below {@code J_max × cullFraction} are culled;</li>
 *   <li>events of the same body pair within the merge window (real time) merge into the loudest;</li>
 *   <li>at most {@code windowCap} impacts start in any cap window of real time, the quietest dropped first;</li>
 *   <li>nothing sounds after its Die is at rest;</li>
 *   <li>the mix goes through a soft limiter, then the volume.</li>
 * </ul>
 *
 * <p><b>Variation.</b> Each category's slices are shuffled with no repeat of the last few, with pitch and gain
 * jitter and a fixed pitch offset per Die Kind. Every random choice draws from the injected sound generator,
 * never the Face Value or Release ones (spec §5), so sound can't shift a result.
 *
 * <p><b>Threads.</b> {@link #render} and {@link #idle} belong to the one audio thread. {@link #play},
 * {@link #stop}, {@link #setVolume}, {@link #setMuted} and {@link #setProjection} may be called from any
 * thread; they take effect from the next block. Volume changes apply to impacts already playing.
 */
public final class StreamingMixer {

    /** The default volume (ADR-0012 "Controls"): 70%, unmuted. */
    public static final double DEFAULT_VOLUME = 0.7;

    private static final double BLOCK_SECONDS = (double) MixFormat.BLOCK_FRAMES / MixFormat.SAMPLE_RATE;

    /** Slack, in seconds, for "after its Die's rest": one sample, since rest is only known to a sample. */
    private static final double REST_SLACK = 1.0 / Recording.SAMPLE_RATE_HZ;

    /** A request from another thread, applied in order at the start of the next block. */
    private sealed interface Command permits Play, Stop {
    }

    private record Play(SoundThrow soundThrow) implements Command {
    }

    private record Stop() implements Command {
    }

    /** What has been decided about one event. */
    private enum State {
        /** Not looked at yet. */
        PENDING,
        /** The loudest of its merge cluster; the window cap hasn't seen it yet. */
        KEPT,
        /** Passed the window cap; starts when due. */
        ADMITTED,
        /** Started or dropped: nothing more to do. */
        DONE
    }

    /** The two bodies of a contact within one Throw, in a fixed order, so A–B and B–A are one pair. */
    private record Pair(ActiveThrow owner, ContactBody first, ContactBody second) {
    }

    private final SampleBank bank;
    private final RandomGenerator random;
    private final MixerTunables tunables;
    private final ImpactObserver observer;
    private final Queue<Command> commands = new ConcurrentLinkedQueue<>();
    private volatile double volume = DEFAULT_VOLUME;
    private volatile boolean muted;
    private volatile ScreenProjection projection = ScreenProjection.CENTRE;

    // Everything below is confined to the audio thread.
    private final List<ActiveThrow> active = new ArrayList<>();
    private final List<Voice> voices = new ArrayList<>();
    /** Start frames of recent impacts, oldest first, for the window cap. */
    private final ArrayDeque<Long> recentStarts = new ArrayDeque<>();
    private final Map<Pair, Long> lastStartOfPair = new HashMap<>();
    private final Map<SoundCategory, ArrayDeque<Integer>> recentSlices = new EnumMap<>(SoundCategory.class);
    private final float[] mix = new float[MixFormat.BLOCK_SAMPLES];
    /** The output frame at which the next block starts, counted from the first block. */
    private long blockFrame;

    /**
     * Makes a mixer with the default tunables that tells nobody what it does.
     *
     * @param bank the slices to play
     * @param soundRandom the sound generator: every variation choice draws from it and nothing else does
     */
    public StreamingMixer(SampleBank bank, RandomGenerator soundRandom) {
        this(bank, soundRandom, MixerTunables.DEFAULTS, ImpactObserver.NONE);
    }

    /**
     * Makes a mixer.
     *
     * @param bank the slices to play
     * @param soundRandom the sound generator: every variation choice draws from it and nothing else does
     * @param tunables the shaping numbers
     * @param observer told, on the audio thread, of every impact started and every event dropped
     */
    public StreamingMixer(SampleBank bank, RandomGenerator soundRandom, MixerTunables tunables,
            ImpactObserver observer) {
        this.bank = Objects.requireNonNull(bank, "bank");
        this.random = Objects.requireNonNull(soundRandom, "soundRandom");
        this.tunables = Objects.requireNonNull(tunables, "tunables");
        this.observer = Objects.requireNonNull(observer, "observer");
        for (SoundCategory category : SoundCategory.values()) {
            recentSlices.put(category, new ArrayDeque<>());
        }
    }

    /**
     * Queues a Throw to be heard. Its events sound as the playback time reaches them; any thread may call this.
     *
     * @param soundThrow the accepted Attempt's Recording, placed on the playback clock
     */
    public void play(SoundThrow soundThrow) {
        commands.add(new Play(Objects.requireNonNull(soundThrow, "soundThrow")));
    }

    /**
     * Drops every Throw queued before this call and silences every impact playing, from the next block on
     * (for example when the sink fails). Any thread may call this.
     */
    public void stop() {
        commands.add(new Stop());
    }

    /**
     * Sets the output volume; it applies to impacts already playing.
     *
     * @param volume the linear gain, 0 to 1
     * @throws IllegalArgumentException if it is out of range
     */
    public void setVolume(double volume) {
        if (!(volume >= 0 && volume <= 1)) {
            throw new IllegalArgumentException("A volume lies in [0, 1]: " + volume);
        }
        this.volume = volume;
    }

    /**
     * Returns the output volume.
     *
     * @return the linear gain, 0 to 1
     */
    public double volume() {
        return volume;
    }

    /**
     * Mutes or unmutes the output. Muted, every block is all zeros, but impacts keep their time, so unmuting
     * mid-Throw resumes where the sound has got to.
     *
     * @param muted {@code true} to mute
     */
    public void setMuted(boolean muted) {
        this.muted = muted;
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
    }

    /**
     * Tests whether the mixer has nothing left to play: no Throw with events to come, no impact still sounding
     * and nothing queued. Audio thread only.
     *
     * @return {@code true} when the next block would be silent and so would every one after it
     */
    public boolean idle() {
        return active.isEmpty() && voices.isEmpty() && commands.isEmpty();
    }

    /**
     * Renders the next block. Audio thread only.
     *
     * @param playbackTime the playback time, in seconds, at which the block's first frame will be heard
     * @param speed the playback clock's current speed against real time
     * @param out receives {@link MixFormat#BLOCK_FRAMES} frames, interleaved left and right
     * @throws IllegalArgumentException if the time or speed isn't usable or {@code out} isn't one block long
     */
    public void render(double playbackTime, double speed, short[] out) {
        if (!Double.isFinite(playbackTime) || !(speed > 0) || !Double.isFinite(speed)) {
            throw new IllegalArgumentException("A block needs a finite time and a positive speed: "
                    + playbackTime + ", " + speed);
        }
        if (out.length != MixFormat.BLOCK_SAMPLES) {
            throw new IllegalArgumentException("A block is " + MixFormat.BLOCK_SAMPLES + " samples, not "
                    + out.length);
        }
        applyCommands();
        double end = playbackTime + BLOCK_SECONDS * speed;
        // The window cap is decided one cap window (of real time) before an event is due, and ranks it
        // against everything known up to a second window beyond: so a loud event just after a burst of quiet
        // ones still takes a place from them, and "the quietest dropped first" holds across block edges.
        double decide = end + tunables.capWindow() * speed;
        double lookahead = decide + tunables.capWindow() * speed;
        pruneRecentStarts();
        List<Candidate> kept = new ArrayList<>();
        for (ActiveThrow at : active) {
            classify(at, lookahead, speed, kept);
        }
        admit(kept, playbackTime, speed, decide);
        startDue(playbackTime, speed, end);
        mixVoices();
        writeOut(out);
        active.removeIf(ActiveThrow::finished);
        blockFrame += MixFormat.BLOCK_FRAMES;
    }

    /**
     * Applies what other threads queued since the last block, in the order queued: a {@code Play} starts following
     * a Throw's events; a {@code Stop} drops every Throw and silences every voice at once. Audio thread only, at the
     * start of each block, so a block is always mixed from one consistent state.
     */
    private void applyCommands() {
        for (Command command; (command = commands.poll()) != null; ) {
            switch (command) {
                case Play(SoundThrow soundThrow) -> active.add(new ActiveThrow(soundThrow));
                case Stop() -> {
                    active.clear();
                    voices.clear();
                }
            }
        }
    }

    /**
     * Forgets the impact starts no decision from this block on can be affected by, so the start history stays a
     * few windows long however long the app runs. Every frame is counted in output frames, which run in real time.
     *
     * <ul>
     *   <li>{@code recentStarts} feeds the window cap. The earliest frame this block can still weigh is a late event
     *       started up to {@code lateTolerance} before the block ({@link #startDue} drops anything later than that,
     *       and {@link #admit} predicts frames down to the same bound), and a cap window holding that frame reaches
     *       back one cap window further. So a start before {@code blockFrame - capFrames - lateFrames} can never
     *       share a cap window with anything still to be decided.</li>
     *   <li>{@code lastStartOfPair} feeds the merge window, checked only for impacts started in this block or
     *       later, at {@code frame >= blockFrame}. A pair's last start before {@code blockFrame - mergeFrames} is
     *       already a whole merge window behind any of them.</li>
     * </ul>
     */
    private void pruneRecentStarts() {
        long oldest = blockFrame - capFrames() - lateFrames();
        while (!recentStarts.isEmpty() && recentStarts.peekFirst() < oldest) {
            recentStarts.pollFirst();
        }
        lastStartOfPair.values().removeIf(frame -> frame < blockFrame - mergeFrames());
    }

    /** Decides cull, rest and merge for every event before the horizon; collects the merge winners. */
    private void classify(ActiveThrow at, double horizon, double speed, List<Candidate> kept) {
        for (int i = at.open; i < at.events.size() && at.when(i) < horizon; i++) {
            if (at.states[i] == State.PENDING) {
                ContactEvent event = at.events.get(i);
                if (event.impulse() < fullScale(event) * tunables.cullFraction()) {
                    drop(at, i, DropReason.CULLED);
                } else if (afterRest(at, event)) {
                    drop(at, i, DropReason.AFTER_REST);
                } else {
                    mergeCluster(at, i, speed);
                }
            }
            if (at.states[i] == State.KEPT) {
                kept.add(new Candidate(at, i));
            }
        }
    }

    /**
     * Merges the chain of same-pair events starting at {@code first}, each within the merge window of the one
     * before, into its loudest (the earliest on a tie). The window is real time, so at the current speed it
     * spans {@code mergeWindow × speed} of playback.
     */
    private void mergeCluster(ActiveThrow at, int first, double speed) {
        Pair pair = pairOf(at, at.events.get(first));
        double window = tunables.mergeWindow() * speed;
        List<Integer> cluster = new ArrayList<>();
        cluster.add(first);
        double last = at.when(first);
        for (int j = first + 1; j < at.events.size() && at.when(j) - last <= window; j++) {
            ContactEvent event = at.events.get(j);
            if (at.states[j] == State.PENDING && eligible(at, event) && pairOf(at, event).equals(pair)) {
                cluster.add(j);
                last = at.when(j);
            }
        }
        int loudest = first;
        for (int j : cluster) {
            if (at.events.get(j).impulse() > at.events.get(loudest).impulse()) {
                loudest = j;
            }
        }
        for (int j : cluster) {
            if (j == loudest) {
                at.states[j] = State.KEPT;
            } else {
                drop(at, j, DropReason.MERGED);
            }
        }
    }

    /**
     * Puts the merge winners through the window cap, loudest first, against impacts already started or
     * admitted: each fits if no cap window holding it would then hold more than the cap. Only those due before
     * {@code decide} are settled (admitted or capped); later ones that fit hold their place for this ranking
     * only, and are ranked again next block.
     */
    private void admit(List<Candidate> kept, double playbackTime, double speed, double decide) {
        if (kept.isEmpty()) {
            return;
        }
        List<Long> frames = new ArrayList<>(recentStarts);
        for (ActiveThrow at : active) {
            for (int i = at.open; i < at.events.size(); i++) {
                if (at.states[i] == State.ADMITTED) {
                    frames.add(predictedFrame(at, i, playbackTime, speed));
                }
            }
        }
        kept.sort(Comparator.comparingDouble((Candidate c) -> -baseGain(c.owner.events.get(c.index)))
                .thenComparingDouble(c -> c.owner.when(c.index)));
        for (Candidate candidate : kept) {
            long frame = predictedFrame(candidate.owner, candidate.index, playbackTime, speed);
            boolean settle = candidate.owner.when(candidate.index) < decide;
            frames.add(frame);
            if (fitsCap(frames, frame)) {
                if (settle) {
                    candidate.owner.states[candidate.index] = State.ADMITTED;
                }
            } else {
                frames.remove(frames.size() - 1);
                if (settle) {
                    drop(candidate.owner, candidate.index, DropReason.CAPPED);
                }
            }
        }
    }

    /** Tests that every cap window holding {@code frame} holds no more than the cap. */
    private boolean fitsCap(List<Long> frames, long frame) {
        long window = capFrames();
        for (long start : frames) {
            if (start > frame - window && start <= frame) {
                int count = 0;
                for (long other : frames) {
                    if (other >= start && other < start + window) {
                        count++;
                    }
                }
                if (count > tunables.windowCap()) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Starts every admitted event due in this block, in time order. */
    private void startDue(double playbackTime, double speed, double end) {
        List<Candidate> due = new ArrayList<>();
        for (ActiveThrow at : active) {
            for (int i = at.open; i < at.events.size() && at.when(i) < end; i++) {
                if (at.states[i] == State.ADMITTED) {
                    due.add(new Candidate(at, i));
                }
            }
        }
        due.sort(Comparator.comparingDouble(c -> c.owner.when(c.index)));
        for (Candidate candidate : due) {
            ActiveThrow at = candidate.owner;
            int i = candidate.index;
            double offset = (at.when(i) - playbackTime) / speed * MixFormat.SAMPLE_RATE;
            if (offset < -lateFrames()) {
                drop(at, i, DropReason.LATE);
                continue;
            }
            int within = (int) Math.min(MixFormat.BLOCK_FRAMES - 1, Math.max(0, Math.floor(offset)));
            long frame = blockFrame + within;
            ContactEvent event = at.events.get(i);
            Pair pair = pairOf(at, event);
            Long previous = lastStartOfPair.get(pair);
            // A speed-up since the merge decision can bring a pair's impacts closer than the window: the
            // window holds in real time whatever the clock did.
            if (previous != null && frame - previous < mergeFrames()) {
                drop(at, i, DropReason.MERGED);
                continue;
            }
            if (startsWithin(frame) >= tunables.windowCap()) {
                drop(at, i, DropReason.CAPPED);
                continue;
            }
            start(at, i, frame, within);
            lastStartOfPair.put(pair, frame);
            recentStarts.addLast(frame);
        }
        for (ActiveThrow at : active) {
            at.advanceOpen();
        }
    }

    /** Counts the impacts started in the cap window that ends at {@code frame}. */
    private int startsWithin(long frame) {
        int count = 0;
        for (long start : recentStarts) {
            if (start > frame - capFrames() && start <= frame) {
                count++;
            }
        }
        return count;
    }

    /** Starts one impact: picks its slice, draws its jitter, pans it and adds its voice. */
    private void start(ActiveThrow at, int index, long frame, int within) {
        ContactEvent event = at.events.get(index);
        SoundCategory category = categoryOf(at.soundThrow, event);
        int slice = pickSlice(category);
        // The draws are always in this order and always from the sound generator: slice, pitch, gain.
        double pitchJitter = tunables.pitchJitter() * (2 * random.nextDouble() - 1);
        double gainJitterDb = tunables.gainJitterDb() * (2 * random.nextDouble() - 1);
        DieKind kind = at.soundThrow.kinds().get(event.die().index());
        double semitones = sizeOffset(kind) + pitchJitter;
        double gain = baseGain(event) * Math.pow(10, gainJitterDb / 20);
        double screenX = Math.clamp(projection.screenX(event.point()), -1.0, 1.0);
        double angle = (screenX * tunables.panWidth() + 1) * Math.PI / 4;
        double left = Math.cos(angle);
        double right = Math.sin(angle);
        voices.add(new Voice(bank.slices(category).get(slice), Math.pow(2, semitones / 12), gain * left,
                gain * right, within));
        at.states[index] = State.DONE;
        observer.started(new Impact(at.soundThrow, event, frame, category, slice, gain, semitones, left, right));
    }

    /** Picks a slice uniformly from those not among the category's last few. */
    private int pickSlice(SoundCategory category) {
        int count = bank.slices(category).size();
        ArrayDeque<Integer> recent = recentSlices.get(category);
        int memory = Math.min(tunables.shuffleMemory(), count - 1);
        while (recent.size() > memory) {
            recent.pollFirst();
        }
        int pick = random.nextInt(count - recent.size());
        int slice = -1;
        for (int k = 0; k <= pick; ) {
            slice++;
            if (!recent.contains(slice)) {
                k++;
            }
        }
        if (memory > 0) {
            if (recent.size() == memory) {
                recent.pollFirst();
            }
            recent.addLast(slice);
        }
        return slice;
    }

    /**
     * Sums every sounding voice's next block into {@code mix}, from silence, and retires each voice that has played
     * to its end. The sum is unlimited here; {@link #writeOut} limits it.
     */
    private void mixVoices() {
        java.util.Arrays.fill(mix, 0);
        for (Iterator<Voice> it = voices.iterator(); it.hasNext(); ) {
            if (it.next().mixInto(mix)) {
                it.remove();
            }
        }
    }

    /**
     * Turns the mixed block into 16-bit samples: the headroom, then the soft {@link #limit limiter} (so no sum of
     * impacts can clip), then the Player's volume, or silence while muted. Muting scales the output rather than
     * skipping the mix, so voices keep advancing and unmuting doesn't replay them.
     *
     * @param out receives the block, interleaved left and right
     */
    private void writeOut(short[] out) {
        double gain = muted ? 0 : volume;
        for (int i = 0; i < out.length; i++) {
            double level = limit(mix[i] * tunables.headroom()) * gain;
            out[i] = (short) Math.round(level * Short.MAX_VALUE);
        }
    }

    /**
     * The soft limiter: linear up to the knee, then a tanh curve with the same slope there that approaches,
     * but never reaches, the ceiling. So no sum of impacts can clip.
     */
    private double limit(double x) {
        double magnitude = Math.abs(x);
        double knee = tunables.limiterKnee();
        if (magnitude <= knee) {
            return x;
        }
        double room = tunables.limiterCeiling() - knee;
        return Math.signum(x) * (knee + room * Math.tanh((magnitude - knee) / room));
    }

    private boolean afterRest(ActiveThrow at, ContactEvent event) {
        return event.time() > at.rests[event.die().index()] + REST_SLACK;
    }

    private boolean eligible(ActiveThrow at, ContactEvent event) {
        return event.impulse() >= fullScale(event) * tunables.cullFraction() && !afterRest(at, event);
    }

    private double fullScale(ContactEvent event) {
        return dieOnDie(event) ? tunables.dieDieFullScale() : tunables.dieTrayFullScale();
    }

    private double baseGain(ContactEvent event) {
        return Math.clamp(event.impulse() / fullScale(event), 0.0, 1.0);
    }

    /** A Die Kind's fixed pitch offset: smaller Dice sound higher, the d6 not at all. */
    private double sizeOffset(DieKind kind) {
        return tunables.sizePitchSlope() * Math.log(DieKind.D6.inradius() / kind.inradius()) / Math.log(2);
    }

    private static boolean dieOnDie(ContactEvent event) {
        BodyKind other = event.other().kind();
        return other == BodyKind.DIE || other == BodyKind.FROZEN_DIE;
    }

    private static SoundCategory categoryOf(SoundThrow soundThrow, ContactEvent event) {
        return switch (event.other().kind()) {
            case FLOOR -> soundThrow.tray().floor();
            case WALL -> soundThrow.tray().wall();
            case DIE, FROZEN_DIE -> SoundCategory.DIE_ON_DIE;
        };
    }

    private static Pair pairOf(ActiveThrow at, ContactEvent event) {
        ContactBody die = event.die();
        ContactBody other = event.other();
        if (other.kind() == BodyKind.DIE && other.index() < die.index()) {
            return new Pair(at, other, die);
        }
        return new Pair(at, die, other);
    }

    private long predictedFrame(ActiveThrow at, int index, double playbackTime, double speed) {
        return blockFrame + (long) Math.floor((at.when(index) - playbackTime) / speed * MixFormat.SAMPLE_RATE);
    }

    private void drop(ActiveThrow at, int index, DropReason reason) {
        at.states[index] = State.DONE;
        observer.dropped(at.soundThrow, at.events.get(index), reason);
    }

    private long capFrames() {
        return Math.round(tunables.capWindow() * MixFormat.SAMPLE_RATE);
    }

    private long mergeFrames() {
        return Math.round(tunables.mergeWindow() * MixFormat.SAMPLE_RATE);
    }

    private long lateFrames() {
        return Math.round(tunables.lateTolerance() * MixFormat.SAMPLE_RATE);
    }

    /** One event awaiting a decision or its start. */
    private record Candidate(ActiveThrow owner, int index) {
    }

    /** A Throw being played, with a decision per event. Audio thread only. */
    private final class ActiveThrow {
        final SoundThrow soundThrow;
        final List<ContactEvent> events;
        final State[] states;
        final float[] rests;
        /** Every event before this index is done. */
        int open;

        ActiveThrow(SoundThrow soundThrow) {
            this.soundThrow = soundThrow;
            this.events = soundThrow.recording().contacts();
            this.states = new State[events.size()];
            java.util.Arrays.fill(states, State.PENDING);
            this.rests = DieRest.of(soundThrow.recording());
        }

        /** The playback time of an event. */
        double when(int index) {
            return soundThrow.start() + events.get(index).time();
        }

        void advanceOpen() {
            while (open < events.size() && states[open] == State.DONE) {
                open++;
            }
        }

        boolean finished() {
            return open == events.size();
        }
    }

    /** One impact sounding: its slice, resampled for its pitch, with its gain per channel. */
    private static final class Voice {
        private final Slice slice;
        private final double step;
        private final double left;
        private final double right;
        private double position;
        private int delay;

        Voice(Slice slice, double step, double left, double right, int delay) {
            this.slice = slice;
            this.step = step;
            this.left = left;
            this.right = right;
            this.delay = delay;
        }

        /**
         * Adds this voice's next block into the mix, by linear interpolation between the slice's samples.
         *
         * @return {@code true} when the slice has ended
         */
        boolean mixInto(float[] mix) {
            int length = slice.length();
            for (int frame = delay; frame < MixFormat.BLOCK_FRAMES; frame++) {
                if (position > length - 1) {
                    return true;
                }
                int index = (int) position;
                double fraction = position - index;
                double sample = slice.sample(index);
                if (fraction > 0 && index + 1 < length) {
                    sample += (slice.sample(index + 1) - sample) * fraction;
                }
                mix[2 * frame] += (float) (sample * left);
                mix[2 * frame + 1] += (float) (sample * right);
                position += step;
            }
            delay = 0;
            return position > length - 1;
        }
    }
}
