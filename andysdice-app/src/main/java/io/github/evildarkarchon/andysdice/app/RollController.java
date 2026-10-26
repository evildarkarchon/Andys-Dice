package io.github.evildarkarchon.andysdice.app;

import io.github.evildarkarchon.andysdice.core.expression.RollExpression;
import io.github.evildarkarchon.andysdice.core.facevalue.FaceValueGenerator;
import io.github.evildarkarchon.andysdice.core.planner.RollPlan;
import io.github.evildarkarchon.andysdice.core.planner.ThrowPlanner;
import io.github.evildarkarchon.andysdice.core.playback.PlaybackClock;
import io.github.evildarkarchon.andysdice.core.simulation.Simulator;
import io.github.evildarkarchon.andysdice.core.simulation.TrayGeometry;
import io.github.evildarkarchon.andysdice.core.simulation.TrayMaterials;
import io.github.evildarkarchon.andysdice.core.tray.Tray;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.random.RandomGenerator;

/**
 * Runs a Roll from Enter to rest (spec §3.1) for any Roll Expression, which arrives parsed (entry in the box is the
 * one place a Player's input is parsed and refused): hand the Roll to {@code core}'s {@link ThrowPlanner} on the
 * physics worker, which chooses every Face Value and pre-simulates the
 * whole chain of Throws (first Throw, Reroll Throws, Cocked Rethrows) before anything moves; then play that chain
 * back on the playback clock as a {@link ChainPlayback}, and show the Roll Result only once the last Throw is at
 * rest. The Dice then stay on the Tray until the next Roll is ready to play.
 *
 * <p>A Failed Roll (the Dice wouldn't settle, or too many rerolls) is known before anything moves: the Tray
 * keeps the previous Roll's Dice and the status carries the spec's message. So does a pre-simulation that broke.
 *
 * <p>It also holds the Queued Roll (spec §3.2 "Entry"): a Roll asked for while one is under way waits behind it and
 * starts about {@value #BEAT_SECONDS} s after its Roll Result is revealed, or after it fails. There is at most one;
 * a later request replaces it, and {@link #cancelQueued} (Esc) drops it. Rolling from the box, a Saved Roll or a Roll
 * History entry all come through {@link #roll}, so they queue the same way. The beat is timed on the playback clock,
 * so tests advance it by hand and Fast-forward shortens it with the rest of playback.
 *
 * <p>And it alone asks for JavaFX's pulse ({@link Pulses}): from the moment a chain starts to play, and through a
 * Queued Roll's beat even with nothing moving, until no Roll is under way, no Queued Roll waits and the Tray has
 * stopped {@linkplain TrayView#moving moving} (the camera settled in), so an idle app doesn't pulse at the monitor's
 * refresh rate.
 *
 * <p>It listens to the clock once, and handles each advance in a fixed order: draw the playing Roll at its new time;
 * reveal it once the last Throw is at rest; start the Queued Roll once its beat is over; then decide whether the
 * pulse is still needed. No rule here depends on the order listeners were added to the clock.
 *
 * <p>What it tells the rest of the app is one immutable {@link RollState} snapshot (the status, the Queued Roll and
 * whether the app has settled), published on every change, a {@code settled} flip on a clock tick included, to
 * listeners that are only ever added and are told in the order they were added ({@link #addStateListener}).
 *
 * <p>Thread contract: everything here runs on one thread, the one that advances the clock (the JavaFX
 * Application Thread in the app). Only the planning job runs on the physics worker, and its result is handed
 * back through the given executor. The Face Value and Release generators are drawn from only inside that job,
 * one Roll at a time (a Roll asked for while one is under way waits as the Queued Roll), so the worker is their one
 * user.
 */
final class RollController {

    private static final System.Logger LOG = System.getLogger(RollController.class.getName());

    /** The beat between a reveal and the Queued Roll's start, playback seconds (spec §4: a tunable, about 0.9 s). */
    static final double BEAT_SECONDS = 0.9;

    /** Where pre-simulation runs: the physics worker, in the app. */
    @FunctionalInterface
    interface PhysicsJobs {
        /**
         * Runs a job on the physics worker, handing it the simulator.
         *
         * @param job the job
         * @return its result, completed exceptionally if it threw
         */
        CompletableFuture<RollPlan> submit(Function<Simulator, RollPlan> job);
    }

    /** Where the Roll is drawn: the Tray. */
    interface TrayView {
        /**
         * Puts a Roll's chain of Throws on the Tray at its first Release, in place of the Dice there.
         *
         * @param chain the pre-simulated chain, with each Die's Die Kind and Numbering Rotations
         */
        void show(ChainPlayback chain);

        /**
         * Draws the chain on the Tray at a moment of its playback.
         *
         * @param seconds playback time since the first Throw's Release
         */
        void showTime(double seconds);

        /**
         * Tells whether the Tray still needs the playback clock after the Roll is over, so the pulse keeps running:
         * the GL Tray's camera settling in on the Dice at rest (#46), for one.
         *
         * @return {@code true} while something on the Tray is still moving on the clock
         */
        boolean moving();
    }

    /**
     * The chain now playing, with when it started on the clock, the expression it reveals at rest and the name of
     * the Saved Roll it was rolled from, if any (#56).
     */
    private record InPlay(RollExpression expression, Optional<String> savedRollName, ChainPlayback chain,
            double start) {}

    private final PlaybackClock clock;
    private final Pulses pulses;
    private final PhysicsJobs physics;
    private final Executor fxThread;
    private final FaceValueGenerator faceValues;
    private final RandomGenerator releases;
    private Tray throwInto;
    private final TrayView tray;

    /** Told every snapshot, in the order added; only ever added to. */
    private final List<Consumer<RollState>> listeners = new ArrayList<>();
    /** Snapshots published but not yet told to every listener; see {@link #publish}. */
    private final Queue<RollState> untold = new ArrayDeque<>();
    /** Whether {@link #publish} is telling the listeners now. */
    private boolean telling;
    private RollStatus status = new RollStatus.Ready();
    /** The snapshot published last, or the first one, made with the controller. */
    private RollState state;
    private boolean underWay;
    private InPlay playing;
    /** The Queued Roll, or {@code null}. */
    private RollState.QueuedRoll queued;
    /** When on the clock the Queued Roll starts; NaN until the Roll ahead of it has ended. */
    private double queuedStartsAt = Double.NaN;
    /** Whether this controller has asked for the pulse and not yet let it go; it is the pulse's one user. */
    private boolean pulsing;
    /** Set once the session has failed ({@link #abandon}); never cleared. */
    private boolean abandoned;

    /**
     * Creates a controller and starts it listening to the clock.
     *
     * @param clock the playback clock, advanced on this controller's thread
     * @param pulses what advances the clock in the app, stopped; this controller alone starts and stops it
     * @param physics where pre-simulation runs
     * @param fxThread runs the pre-simulation's result on this controller's thread ({@code Platform::runLater})
     * @param faceValues the Face Value generator (ADR-0001); used only on the physics worker, one Roll at a time
     * @param releases the Release generator (ADR-0003); used only on the physics worker, one Roll at a time
     * @param throwInto the chosen Tray (ADR-0010), whose shape and physics materials every Throw is simulated in;
     *     the view draws the same Tray
     * @param tray where the Roll is drawn
     */
    RollController(PlaybackClock clock, Pulses pulses, PhysicsJobs physics, Executor fxThread,
            FaceValueGenerator faceValues, RandomGenerator releases, Tray throwInto, TrayView tray) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.pulses = Objects.requireNonNull(pulses, "pulses");
        this.physics = Objects.requireNonNull(physics, "physics");
        this.fxThread = Objects.requireNonNull(fxThread, "fxThread");
        this.faceValues = Objects.requireNonNull(faceValues, "faceValues");
        this.releases = Objects.requireNonNull(releases, "releases");
        this.throwInto = Objects.requireNonNull(throwInto, "throwInto");
        this.tray = Objects.requireNonNull(tray, "tray");
        state = snapshot();
        clock.addListener(c -> advanced());
    }

    /**
     * Returns the Roll's state as it stands now: the snapshot published last, or before any change, the first one.
     *
     * @return the current snapshot
     */
    RollState state() {
        return state;
    }

    /**
     * Adds a listener told each snapshot published from now on, after the listeners added before it. Listeners are
     * only ever added, so none unhooks another; a listener is not told the current snapshot, which {@link #state}
     * returns.
     *
     * @param listener told every snapshot, in the order they were published, on this controller's thread
     */
    void addStateListener(Consumer<RollState> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * Returns the Tray Rolls are thrown into.
     *
     * @return the chosen Tray
     */
    Tray tray() {
        return throwInto;
    }

    /**
     * Changes the Tray the next Roll is thrown into, as the Look tab does (#57). A Roll already pre-simulating or
     * playing keeps the Tray it was planned in: its Throws were simulated there.
     *
     * @param tray the chosen Tray
     */
    void setTray(Tray tray) {
        throwInto = Objects.requireNonNull(tray, "tray");
    }

    /**
     * Rolls a Roll Expression, as Enter does: at once if nothing is under way, else as the Queued Roll in place of
     * any earlier one. A request while a Queued Roll waits out its beat replaces it too, rather than jumping ahead of
     * it.
     *
     * @param expression the Roll Expression, already parsed
     */
    void roll(RollExpression expression) {
        roll(expression, Optional.empty());
    }

    /**
     * Rolls a Roll Expression as {@link #roll(RollExpression)} does, recording the name of the Saved Roll it came
     * from in the {@link RollStatus.Rested} it reveals (spec §3.2 "Saved tab": the Roll is recorded with that name),
     * even when it waits as the Queued Roll first.
     *
     * @param expression the Roll Expression, already parsed
     * @param savedRollName the Saved Roll's name, or empty for a Roll typed or taken from the History
     */
    void roll(RollExpression expression, Optional<String> savedRollName) {
        Objects.requireNonNull(expression, "expression");
        Objects.requireNonNull(savedRollName, "savedRollName");
        if (abandoned) {
            // The session has failed (abandon): nothing rolls or queues behind the failure panel.
            return;
        }
        if (underWay || queued != null) {
            queued = new RollState.QueuedRoll(expression, savedRollName);
            publish();
            return;
        }
        start(expression, savedRollName);
    }

    /**
     * Drops the Queued Roll, as Esc does when nothing else takes it.
     *
     * @return whether there was one to drop
     */
    boolean cancelQueued() {
        if (queued == null) {
            return false;
        }
        clearQueued();
        // With nothing left to wait for, a beat no longer needs the pulse.
        updatePulse();
        publish();
        return true;
    }

    /** Starts a Roll now: pre-simulates its whole chain on the physics worker. */
    private void start(RollExpression expression, Optional<String> savedRollName) {
        underWay = true;
        setStatus(new RollStatus.PreSimulating(expression));
        // The Tray this Roll is planned in, taken now: one picked while it pre-simulates is the next Roll's. Its
        // chain carries this one (#67), so the Roll is heard in the Tray it was simulated in.
        Tray plannedIn = throwInto;
        CompletableFuture<RollPlan> planned;
        try {
            // Choose and pre-simulate the whole chain on the worker before anything plays (ADR-0001, ADR-0014).
            // An Escaped Die's Attempt is never kept (ADR-0003), so only Attempts inside the walls ever play.
            TrayGeometry geometry = plannedIn.geometry();
            TrayMaterials materials = plannedIn.materials();
            planned = physics.submit(simulator -> new ThrowPlanner(simulator, geometry, materials)
                    .plan(expression, faceValues, releases));
        } catch (RuntimeException e) {
            // A worker that has been closed rejects the job outright rather than through the future.
            planned = CompletableFuture.failedFuture(e);
        }
        planned.whenCompleteAsync((plan, error) -> arrived(expression, savedRollName, plannedIn, plan, error),
                fxThread);
    }

    /**
     * Ends the session's Rolling for good, as the failure panel does (spec §3.2 "Runtime failure"): a Roll in motion
     * stops where it is and is never revealed, so it never joins the Roll History (ADR-0007: a Roll interrupted
     * before it rests is lost, because it was never shown), and one still pre-simulating never plays. The Queued
     * Roll is dropped, so nothing starts behind the panel once its beat is up. Nothing is recovered in-process: every
     * later {@link #roll} is ignored. Calling it again does nothing.
     */
    void abandon() {
        if (abandoned) {
            return;
        }
        abandoned = true;
        playing = null;
        underWay = false;
        if (queued != null) {
            clearQueued();
        }
        updatePulse();
        publish();
    }

    /**
     * The pre-simulation is done: play its chain, or fail the Roll leaving the Tray as it was.
     *
     * @param plannedIn the Tray the Roll was planned in, which its chain carries
     */
    private void arrived(RollExpression expression, Optional<String> savedRollName, Tray plannedIn, RollPlan plan,
            Throwable error) {
        if (abandoned) {
            // The session failed while this Roll pre-simulated: it was never shown, so it is simply lost.
            return;
        }
        if (error != null) {
            fail(RollStatus.Failed.broken(expression, describe(error)), error);
            return;
        }
        switch (plan) {
            case RollPlan.Planned planned -> {
                ChainPlayback chain = new ChainPlayback(planned, plannedIn);
                tray.show(chain);
                // Playback starts now on the clock, wherever the clock has got to.
                double start = clock.time();
                playing = new InPlay(expression, savedRollName, chain, start);
                // Now something moves, the clock must run.
                updatePulse();
                // Published once the Tray has the chain, with the clock time of its first Release, which the dice
                // sound places its Throws from (#59).
                setStatus(new RollStatus.Playing(expression, chain, start));
            }
            case RollPlan.Failed failed -> fail(RollStatus.Failed.of(expression, failed.reason()), null);
            // The expression arrived parsed, so the planner never sees an invalid one.
            case RollPlan.Invalid invalid -> fail(RollStatus.Failed.broken(expression, invalid.error().message()),
                    null);
        }
    }

    /** Ends the Roll as failed: nothing was drawn, so the Tray still holds the previous Roll's Dice. */
    private void fail(RollStatus.Failed failed, Throwable error) {
        underWay = false;
        LOG.log(System.Logger.Level.WARNING, "Roll of " + failed.expression().canonical() + " failed: "
                + failed.message(), error);
        // A Queued Roll waits out the beat from the failure, with the Failed Roll's message shown through it.
        ended();
        setStatus(failed);
        // A Roll that failed in pre-simulation never started the pulse, but a Queued Roll's beat needs it.
        updatePulse();
    }

    /**
     * The clock moved on. In this order, which is the one rule here about ordering: draw the playing chain at its
     * new time; reveal the Roll once its last Throw is at rest; start the Queued Roll once its beat is over (so a
     * chain it shows this advance is drawn from its first Release, not redrawn); and only then decide whether the
     * pulse is still needed, with everything this advance changed already in place. Last, the snapshot is published
     * if the advance changed nothing else but {@code settled} (the Tray stopped moving: the camera has settled in).
     */
    private void advanced() {
        InPlay now = playing;
        if (now != null) {
            double elapsed = clock.time() - now.start();
            tray.showTime(elapsed);
            if (elapsed >= now.chain().restTime()) {
                reveal(now);
            }
        }
        startQueuedIfDue();
        updatePulse();
        publish();
    }

    /** The last Throw is at rest: show the Roll Result, and start a Queued Roll's beat. */
    private void reveal(InPlay now) {
        playing = null;
        underWay = false;
        ended();
        // Every Throw of the chain counts, Cocked Rethrows included (spec §3.2). A Roll from a Saved Roll
        // carries its name (#56), which the result strip shows and the Roll History records.
        setStatus(new RollStatus.Rested(now.expression(), now.chain().plan().result(),
                now.chain().throwCount(), now.savedRollName()));
    }

    /** The Roll under way has ended, revealed or failed: a Queued Roll's beat starts now. */
    private void ended() {
        if (queued != null) {
            queuedStartsAt = clock.time() + BEAT_SECONDS;
        }
    }

    /** Starts the Queued Roll if its beat is over. */
    private void startQueuedIfDue() {
        // A hair of slack: 54 frames of 1/60 s add up to a shade under 0.9 s in floating point.
        if (queued == null || underWay || Double.isNaN(queuedStartsAt)
                || clock.time() < queuedStartsAt - 1e-9) {
            return;
        }
        RollState.QueuedRoll next = queued;
        // Not published on its own: start publishes the new status with the Queued Roll gone, in one snapshot, so
        // nothing sees the app settled for a moment between the Roll at rest and the one taking over from it.
        clearQueued();
        start(next.expression(), next.savedRollName());
    }

    /** Drops the Queued Roll and its beat. It publishes nothing; the caller does, once its change is complete. */
    private void clearQueued() {
        queued = null;
        queuedStartsAt = Double.NaN;
    }

    /**
     * Asks for the pulse, or lets it go: it is needed while a chain plays and while a Queued Roll waits out its beat
     * (the beat is timed on the clock, even with nothing moving). It is let go once no Roll is under way, no Queued
     * Roll waits and the Tray has stopped moving (the camera's settle-in). While a Roll only pre-simulates it is left
     * as it is: nothing moves yet, but the last Roll's settle-in may still be easing.
     */
    private void updatePulse() {
        if (playing != null || (queued != null && !underWay)) {
            if (!pulsing) {
                pulsing = true;
                pulses.start();
            }
        } else if (!underWay && queued == null && !tray.moving()) {
            if (pulsing) {
                pulsing = false;
                pulses.stop();
            }
        }
    }

    /** Moves the status on and publishes it: every new status is a change, even one equal to the last. */
    private void setStatus(RollStatus next) {
        status = next;
        publish();
    }

    /** The Roll's state as it stands now; settled once nothing is under way or queued and the Tray is still. */
    private RollState snapshot() {
        return new RollState(status, Optional.ofNullable(queued), !underWay && queued == null && !tray.moving());
    }

    /**
     * Publishes the Roll's state if it changed since the last snapshot: a new status (told apart by identity), a
     * different Queued Roll, or {@code settled} flipping. Each listener is told in the order it was added.
     *
     * <p>A listener may act on the controller while being told (roll, say, the moment a Roll rests), which publishes
     * again from inside this call. That snapshot waits until every listener has been told the one before it, so all
     * of them hear every snapshot in the same order, rather than later listeners hearing the newer one first and
     * then the stale one.
     */
    private void publish() {
        RollState next = snapshot();
        if (next.status() == state.status() && next.queued().equals(state.queued())
                && next.settled() == state.settled()) {
            return;
        }
        state = next;
        untold.add(next);
        if (telling) {
            return;
        }
        telling = true;
        try {
            for (RollState told = untold.poll(); told != null; told = untold.poll()) {
                // By index: a listener added while one is being told is told this snapshot too, not a failure.
                for (int i = 0; i < listeners.size(); i++) {
                    listeners.get(i).accept(told);
                }
            }
        } finally {
            telling = false;
            // Empty unless a listener threw; what it left untold is stale by the next change, so it is dropped.
            untold.clear();
        }
    }

    private static String describe(Throwable error) {
        Throwable cause = Failure.unwrap(error);
        return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
    }
}
