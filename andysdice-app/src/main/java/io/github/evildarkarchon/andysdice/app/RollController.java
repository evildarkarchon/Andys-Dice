package io.github.evildarkarchon.andysdice.app;

import io.github.evildarkarchon.andysdice.core.expression.ParseResult;
import io.github.evildarkarchon.andysdice.core.expression.RollExpression;
import io.github.evildarkarchon.andysdice.core.expression.RollExpressionParser;
import io.github.evildarkarchon.andysdice.core.facevalue.FaceValueGenerator;
import io.github.evildarkarchon.andysdice.core.planner.RollPlan;
import io.github.evildarkarchon.andysdice.core.planner.ThrowPlanner;
import io.github.evildarkarchon.andysdice.core.playback.PlaybackClock;
import io.github.evildarkarchon.andysdice.core.simulation.Simulator;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.random.RandomGenerator;

/**
 * Runs a Roll from Enter to rest (spec §3.1) for any Roll Expression: parse, then hand the Roll to
 * {@code core}'s {@link ThrowPlanner} on the physics worker, which chooses every Face Value and pre-simulates the
 * whole chain of Throws (first Throw, Reroll Throws, Cocked Rethrows) before anything moves; then play that chain
 * back on the playback clock as a {@link ChainPlayback}, and show the Roll Result only once the last Throw is at
 * rest. The Dice then stay on the Tray until the next Roll is ready to play.
 *
 * <p>A Failed Roll (the Dice wouldn't settle, or too many rerolls) is known before anything moves: the Tray
 * keeps the previous Roll's Dice and the status carries the spec's message. So does a pre-simulation that broke.
 *
 * <p>Thread contract: everything here runs on one thread, the one that advances the clock (the JavaFX
 * Application Thread in the app). Only the planning job runs on the physics worker, and its result is handed
 * back through the given executor. The Face Value and Release generators are drawn from only inside that job,
 * one Roll at a time (a second Enter while a Roll is under way is ignored), so the worker is their one user.
 */
final class RollController {

    private static final System.Logger LOG = System.getLogger(RollController.class.getName());

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
    }

    /** The chain now playing, with when it started on the clock and the expression it reveals at rest. */
    private record Playing(RollExpression expression, ChainPlayback chain, double start) {}

    private final PlaybackClock clock;
    private final PhysicsJobs physics;
    private final Executor fxThread;
    private final FaceValueGenerator faceValues;
    private final RandomGenerator releases;
    private final TrayView tray;

    private Consumer<RollStatus> onStatusChanged = status -> {};
    private RollStatus status = new RollStatus.Ready();
    private boolean underWay;
    private Playing playing;

    /**
     * Creates a controller and starts it listening to the clock.
     *
     * @param clock the playback clock, advanced on this controller's thread
     * @param physics where pre-simulation runs
     * @param fxThread runs the pre-simulation's result on this controller's thread ({@code Platform::runLater})
     * @param faceValues the Face Value generator (ADR-0001); used only on the physics worker, one Roll at a time
     * @param releases the Release generator (ADR-0003); used only on the physics worker, one Roll at a time
     * @param tray where the Roll is drawn
     */
    RollController(PlaybackClock clock, PhysicsJobs physics, Executor fxThread, FaceValueGenerator faceValues,
            RandomGenerator releases, TrayView tray) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.physics = Objects.requireNonNull(physics, "physics");
        this.fxThread = Objects.requireNonNull(fxThread, "fxThread");
        this.faceValues = Objects.requireNonNull(faceValues, "faceValues");
        this.releases = Objects.requireNonNull(releases, "releases");
        this.tray = Objects.requireNonNull(tray, "tray");
        clock.addListener(c -> advanced());
    }

    /**
     * Returns where the current Roll stands.
     *
     * @return the status the result strip shows
     */
    RollStatus status() {
        return status;
    }

    /**
     * Sets who is told each time the status changes.
     *
     * @param listener the listener; it replaces any earlier one
     */
    void setOnStatusChanged(Consumer<RollStatus> listener) {
        onStatusChanged = Objects.requireNonNull(listener, "listener");
    }

    /**
     * Tells whether a Roll is being pre-simulated or played back.
     *
     * @return {@code true} from Enter until the Roll is at rest or has failed
     */
    boolean underWay() {
        return underWay;
    }

    /**
     * Rolls what was typed, as Enter does. An invalid Roll Expression rolls nothing and says why, without
     * reaching the physics worker. While a Roll is under way this does nothing.
     *
     * @param input the Roll Expression as typed
     */
    void roll(String input) {
        if (underWay) {
            // A Roll typed during another becomes the Queued Roll in #49; until then it is ignored.
            return;
        }
        RollExpression expression;
        switch (RollExpressionParser.parse(input)) {
            case ParseResult.Invalid invalid -> {
                setStatus(new RollStatus.Refused(input, invalid.error().message()));
                return;
            }
            case ParseResult.Valid valid -> expression = valid.expression();
        }

        underWay = true;
        setStatus(new RollStatus.Rolling(expression));
        CompletableFuture<RollPlan> planned;
        try {
            // Choose and pre-simulate the whole chain on the worker before anything plays (ADR-0001, ADR-0014).
            planned = physics.submit(simulator -> new ThrowPlanner(simulator, SkeletonTray.GEOMETRY,
                    SkeletonTray.MATERIALS).plan(expression, faceValues, releases));
        } catch (RuntimeException e) {
            // A worker that has been closed rejects the job outright rather than through the future.
            planned = CompletableFuture.failedFuture(e);
        }
        planned.whenCompleteAsync((plan, error) -> arrived(expression, plan, error), fxThread);
    }

    /** The pre-simulation is done: play its chain, or fail the Roll leaving the Tray as it was. */
    private void arrived(RollExpression expression, RollPlan plan, Throwable error) {
        if (error != null) {
            fail(RollStatus.Failed.broken(expression, describe(error)), error);
            return;
        }
        switch (plan) {
            case RollPlan.Planned planned -> {
                ChainPlayback chain = new ChainPlayback(planned);
                tray.show(chain);
                // Playback starts now on the clock, wherever the clock has got to.
                playing = new Playing(expression, chain, clock.time());
            }
            case RollPlan.Failed failed -> fail(RollStatus.Failed.of(expression, failed.reason()), null);
            // The expression parsed here already, so the planner never sees an invalid one.
            case RollPlan.Invalid invalid -> fail(RollStatus.Failed.broken(expression, invalid.error().message()),
                    null);
        }
    }

    /** Ends the Roll as failed: nothing was drawn, so the Tray still holds the previous Roll's Dice. */
    private void fail(RollStatus.Failed failed, Throwable error) {
        underWay = false;
        LOG.log(System.Logger.Level.WARNING, "Roll of " + failed.expression().canonical() + " failed: "
                + failed.message(), error);
        setStatus(failed);
    }

    /** The clock moved on: draw the chain at its new time, and reveal the result once the last Throw rests. */
    private void advanced() {
        Playing now = playing;
        if (now == null) {
            return;
        }
        double elapsed = clock.time() - now.start();
        tray.showTime(elapsed);
        if (elapsed >= now.chain().restTime()) {
            playing = null;
            underWay = false;
            setStatus(new RollStatus.Rested(now.expression(), now.chain().plan().result()));
        }
    }

    private void setStatus(RollStatus next) {
        status = next;
        onStatusChanged.accept(next);
    }

    private static String describe(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause()
                : error;
        return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
    }
}
