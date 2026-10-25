package io.github.evildarkarchon.andysdice.app;

import io.github.evildarkarchon.andysdice.core.expression.DiceGroup;
import io.github.evildarkarchon.andysdice.core.expression.FaceValueChain;
import io.github.evildarkarchon.andysdice.core.expression.ParseResult;
import io.github.evildarkarchon.andysdice.core.expression.RollEvaluator;
import io.github.evildarkarchon.andysdice.core.expression.RollExpression;
import io.github.evildarkarchon.andysdice.core.expression.RollExpressionParser;
import io.github.evildarkarchon.andysdice.core.expression.RollResult;
import io.github.evildarkarchon.andysdice.core.facevalue.FaceValueGenerator;
import io.github.evildarkarchon.andysdice.core.facevalue.FaceValues;
import io.github.evildarkarchon.andysdice.core.geometry.DieKind;
import io.github.evildarkarchon.andysdice.core.geometry.NumberingRotation;
import io.github.evildarkarchon.andysdice.core.playback.PlaybackClock;
import io.github.evildarkarchon.andysdice.core.simulation.Attempt;
import io.github.evildarkarchon.andysdice.core.simulation.AttemptRequest;
import io.github.evildarkarchon.andysdice.core.simulation.Ending;
import io.github.evildarkarchon.andysdice.core.simulation.Orientation;
import io.github.evildarkarchon.andysdice.core.simulation.Recording;
import io.github.evildarkarchon.andysdice.core.simulation.Release;
import io.github.evildarkarchon.andysdice.core.simulation.Simulator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.random.RandomGenerator;
import org.joml.Quaterniondc;

/**
 * Runs a Roll from Enter to rest (spec §3.1) for the walking skeleton, which rolls {@code 1d6} with an optional
 * Modifier: parse, choose the Face Value, pre-simulate the cube's Throw on the physics worker, play its
 * Recording back on the playback clock, and show the Roll Result only once the cube is at rest. The cube then
 * stays on the Tray until the next Roll is ready to play.
 *
 * <p>The Face Value is chosen before anything is simulated (ADR-0001), and the cube is drawn turned by its
 * Numbering Rotation, so whichever face Jolt landed up shows the chosen value. An Attempt that doesn't come to
 * rest flat is retried silently, up to {@value #MAX_ATTEMPTS} times (ADR-0013); past that the Roll fails and
 * the Tray keeps its Dice. Ticket #36's planner takes over the Attempts (and adds Cocked Rethrows), and #38
 * widens this to any Roll Expression.
 *
 * <p>Thread contract: everything here runs on one thread, the one that advances the clock (the JavaFX
 * Application Thread in the app). Only the pre-simulation job runs on the physics worker, and its result is
 * handed back through the given executor.
 */
final class RollController {

    /** The Attempts a Throw gets to come to rest flat before the Roll fails (spec §4). */
    static final int MAX_ATTEMPTS = 24;

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
        CompletableFuture<Optional<Planned>> submit(Function<Simulator, Optional<Planned>> job);
    }

    /** Where the Throw is drawn: the Tray. */
    interface TrayView {
        /**
         * Puts a Throw on the Tray at its Release, in place of the Dice there.
         *
         * @param recording the Throw's Recording
         * @param turns each Die's Numbering Rotation, in the Recording's order
         */
        void show(Recording recording, List<Orientation> turns);

        /**
         * Draws the Throw on the Tray at a moment of its playback.
         *
         * @param seconds playback time since the Throw's Release
         */
        void showTime(double seconds);
    }

    /**
     * A pre-simulated Throw ready to play: its Recording, with each Die's Numbering Rotation.
     *
     * @param recording the accepted Attempt's Recording
     * @param turns each Die's Numbering Rotation
     */
    record Planned(Recording recording, List<Orientation> turns) {

        /** Copies the turns. */
        Planned {
            Objects.requireNonNull(recording, "recording");
            turns = List.copyOf(turns);
        }
    }

    /** The Throw now playing, with when it started on the clock and the Roll Result it reveals at rest. */
    private record Playing(RollExpression expression, RollResult result, double start, double restTime) {}

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
     * @param faceValues the Face Value generator (ADR-0001)
     * @param releases the Release generator (ADR-0003); used only on the physics worker, one Roll at a time
     * @param tray where the Throw is drawn
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
     * Rolls what was typed, as Enter does. An invalid Roll Expression, or one the skeleton can't roll yet, rolls
     * nothing and says why. While a Roll is under way this does nothing.
     *
     * @param input the Roll Expression as typed
     */
    void roll(String input) {
        if (underWay) {
            // A Roll typed during another becomes the Queued Roll in #49; the skeleton just ignores it.
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
        if (!isOneD6(expression)) {
            setStatus(new RollStatus.Refused(input, "This build rolls only 1d6, with an optional Modifier."));
            return;
        }

        // ADR-0001: the Face Value is chosen first; physics only animates a Throw that shows it.
        int value = faceValues.faceValue(DieKind.D6);
        RollResult result = RollEvaluator.evaluate(expression, List.of(FaceValueChain.of(value)));
        int targetFace = FaceValues.face(DieKind.D6, value);

        underWay = true;
        setStatus(new RollStatus.Rolling(expression));
        RandomGenerator releaseGenerator = releases;
        CompletableFuture<Optional<Planned>> planned;
        try {
            planned = physics.submit(simulator -> plan(simulator, releaseGenerator, targetFace));
        } catch (RuntimeException e) {
            // A worker that has been closed rejects the job outright rather than through the future.
            planned = CompletableFuture.failedFuture(e);
        }
        planned.whenCompleteAsync((throwPlan, error) -> arrived(expression, result, throwPlan, error), fxThread);
    }

    /**
     * Pre-simulates the cube's Throw on the physics worker: Attempts with Releases drawn from the Release
     * generator until one comes to rest flat, then the Numbering Rotation that shows the target face.
     *
     * @return the Throw ready to play, or empty if no Attempt came to rest flat
     */
    private static Optional<Planned> plan(Simulator simulator, RandomGenerator releases, int targetFace) {
        AttemptRequest request = new AttemptRequest(List.of(SkeletonCube.INSTANCE), new Release.Drawn(releases),
                List.of(), SkeletonTray.GEOMETRY, SkeletonTray.MATERIALS);
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            Attempt result = simulator.simulate(request);
            if (result.ending() == Ending.AT_REST) {
                Orientation landed = result.recording().finalPose(0).orientation();
                Quaterniondc turn = NumberingRotation.of(SkeletonCube.upFace(landed), targetFace).rotation();
                return Optional.of(new Planned(result.recording(), List.of(new Orientation(
                        (float) turn.x(), (float) turn.y(), (float) turn.z(), (float) turn.w()))));
            }
            LOG.log(System.Logger.Level.DEBUG, "Attempt {0} of {1} ended {2}; retrying", attempt, MAX_ATTEMPTS,
                    result.ending());
        }
        // #36's planner keeps the best Attempt and makes a Cocked Rethrow here; the skeleton just fails.
        return Optional.empty();
    }

    /** The pre-simulation is done: play its Throw, or fail the Roll leaving the Tray as it was. */
    private void arrived(RollExpression expression, RollResult result, Optional<Planned> planned,
            Throwable error) {
        if (error != null || planned.isEmpty()) {
            underWay = false;
            String reason = error != null ? describe(error)
                    : "no Attempt came to rest flat in " + MAX_ATTEMPTS + " tries";
            LOG.log(System.Logger.Level.WARNING, "Roll of " + expression.canonical() + " failed: " + reason, error);
            setStatus(new RollStatus.Failed(expression, reason));
            return;
        }
        Recording recording = planned.get().recording();
        tray.show(recording, planned.get().turns());
        // Playback starts now on the clock, wherever the clock has got to.
        playing = new Playing(expression, result, clock.time(), recording.restTime());
    }

    /** The clock moved on: draw the Throw at its new time, and reveal the result once every Die is at rest. */
    private void advanced() {
        Playing now = playing;
        if (now == null) {
            return;
        }
        double elapsed = clock.time() - now.start();
        tray.showTime(elapsed);
        if (elapsed >= now.restTime()) {
            playing = null;
            underWay = false;
            setStatus(new RollStatus.Rested(now.expression(), now.result()));
        }
    }

    private void setStatus(RollStatus next) {
        status = next;
        onStatusChanged.accept(next);
    }

    /** Whether the expression is one plain d6, with or without Modifiers: all the skeleton can Throw. */
    private static boolean isOneD6(RollExpression expression) {
        List<DiceGroup> groups = expression.diceGroups();
        // The canonical form carries any keep (or reroll) clause, so only a plain single d6 reads "1d6".
        return groups.size() == 1 && groups.getFirst().canonical().equals("1d6");
    }

    private static String describe(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause()
                : error;
        return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
    }
}
