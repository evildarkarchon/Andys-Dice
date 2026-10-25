package io.github.evildarkarchon.andysdice.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.github.evildarkarchon.andysdice.core.facevalue.FaceValueGenerator;
import io.github.evildarkarchon.andysdice.core.facevalue.FaceValues;
import io.github.evildarkarchon.andysdice.core.geometry.DieKind;
import io.github.evildarkarchon.andysdice.core.geometry.UpFace;
import io.github.evildarkarchon.andysdice.core.playback.PlaybackClock;
import io.github.evildarkarchon.andysdice.core.simulation.Attempt;
import io.github.evildarkarchon.andysdice.core.simulation.AttemptRequest;
import io.github.evildarkarchon.andysdice.core.simulation.DieKindBody;
import io.github.evildarkarchon.andysdice.core.simulation.Ending;
import io.github.evildarkarchon.andysdice.core.simulation.Orientation;
import io.github.evildarkarchon.andysdice.core.simulation.Recording;
import io.github.evildarkarchon.andysdice.core.simulation.Release;
import io.github.evildarkarchon.andysdice.core.simulation.Simulator;
import io.github.evildarkarchon.andysdice.core.simulation.Vector3;
import io.github.evildarkarchon.andysdice.render.DiceScene;
import io.github.evildarkarchon.andysdice.render.ThrowPlayback;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Enter on {@code 1d6}, over a scripted simulator and a playback clock advanced by hand: nothing moves until the
 * Throw is pre-simulated, the Recording then plays at the clock's time, and the Roll Result appears only at rest.
 * No GL, no Jolt, no JavaFX, and no test sleeps (ADR-0011).
 */
class RollControllerTest {

    private static final long FACE_SEED = 34;
    private static final double FRAME = 1.0 / 60;

    /** Half a second of a cube falling flat onto the floor, with its +y face (the d6's 3) up. */
    private static final Recording FALL = fall(Orientation.IDENTITY);

    private PlaybackClock clock;
    private ScriptedSimulator simulator;
    private HeldPhysics physics;
    private RecordingTray tray;
    private List<RollStatus> statuses;
    private RollController controller;

    @BeforeEach
    void setUp() {
        clock = new PlaybackClock();
        simulator = new ScriptedSimulator();
        physics = new HeldPhysics(simulator);
        tray = new RecordingTray();
        statuses = new ArrayList<>();
        controller = new RollController(clock, physics, Runnable::run,
                new FaceValueGenerator(new SplittableRandom(FACE_SEED)), new SplittableRandom(1), tray);
        controller.setOnStatusChanged(statuses::add);
    }

    @Test
    void beforeAnyRollTheTrayIsEmptyAndTheControllerIsReady() {
        assertThat(controller.status()).isInstanceOf(RollStatus.Ready.class);
        assertThat(tray.shown).isEmpty();
    }

    @Test
    void enterPreSimulatesOnThePhysicsWorkerBeforeAnythingMoves() {
        simulator.script(atRest(FALL));

        controller.roll("1d6");

        assertThat(controller.status()).isInstanceOf(RollStatus.Rolling.class);
        assertThat(physics.held).isNotNull();
        assertThat(simulator.requests).isEmpty();
        assertThat(tray.shown).isEmpty();

        physics.finish();

        assertThat(simulator.requests).hasSize(1);
        AttemptRequest request = simulator.requests.getFirst();
        assertThat(request.dice()).containsExactly(DieKindBody.of(DieKind.D6));
        assertThat(request.release()).isInstanceOf(Release.Drawn.class);
        assertThat(request.frozen()).isEmpty();
        assertThat(tray.shown).containsExactly(FALL);
        assertThat(tray.times).containsExactly(0.0);
    }

    @Test
    void theRecordingPlaysAtTheClocksTimeAndTheResultAppearsOnlyAtRest() {
        simulator.script(atRest(FALL));
        controller.roll("1d6");
        physics.finish();

        while (clock.time() + FRAME < FALL.restTime()) {
            clock.advance(FRAME);
            assertThat(tray.times.getLast()).isCloseTo(clock.time(), within(1e-9));
            assertThat(controller.status()).isInstanceOf(RollStatus.Rolling.class);
        }
        clock.advance(FRAME);

        int expected = new FaceValueGenerator(new SplittableRandom(FACE_SEED)).faceValue(DieKind.D6);
        RollStatus.Rested rested = (RollStatus.Rested) controller.status();
        assertThat(rested.result().total()).isEqualTo(expected);
        assertThat(rested.expression().canonical()).isEqualTo("1d6");
        assertThat(statuses).filteredOn(RollStatus.Rested.class::isInstance).hasSize(1);
    }

    @Test
    void theClockStartingLaterThanZeroStillPlaysFromTheRelease() {
        clock.advance(12.5);
        simulator.script(atRest(FALL));
        controller.roll("1d6");
        physics.finish();

        clock.advance(FRAME);

        assertThat(tray.times).hasSize(2);
        assertThat(tray.times.getLast()).isCloseTo(FRAME, within(1e-9));
    }

    @Test
    void theCubeIsTurnedSoTheChosenFaceValueIsTheOneUpAtRest() {
        // Landed with a different face up each time; the Numbering Rotation must still show the chosen value.
        Orientation tipped = Orientation.about(new Vector3(1, 0, 0), (float) (Math.PI / 2));
        Recording fall = fall(tipped);
        simulator.script(atRest(fall));
        controller.roll("1d6");
        physics.finish();

        DiceScene.Die drawn = tray.playbacks.getLast().diceAt(fall.restTime()).getFirst();
        int value = new FaceValueGenerator(new SplittableRandom(FACE_SEED)).faceValue(DieKind.D6);
        UpFace up = UpFace.read(DieKind.D6, drawn.orientation());
        assertThat(drawn.kind()).isEqualTo(DieKind.D6);
        assertThat(up.face()).isEqualTo(FaceValues.face(DieKind.D6, value));
        assertThat(up.cocked()).isFalse();
    }

    @Test
    void theCubeStaysOnTheTrayUntilTheNextRollIsReadyToPlay() {
        simulator.script(atRest(FALL), atRest(fall(Orientation.about(new Vector3(0, 0, 1), 1.5708f))));
        controller.roll("1d6");
        physics.finish();
        for (int i = 0; i < 120; i++) {
            clock.advance(FRAME);
        }
        assertThat(controller.status()).isInstanceOf(RollStatus.Rested.class);

        controller.roll("1d6");
        clock.advance(FRAME);

        // Still pre-simulating: the first cube is left where it lies, and nothing is redrawn.
        assertThat(tray.shown).hasSize(1);
        physics.finish();
        assertThat(tray.shown).hasSize(2);
    }

    @Test
    void anInvalidRollExpressionRollsNothingAndSaysWhy() {
        controller.roll("1d6+");

        RollStatus.Refused refused = (RollStatus.Refused) controller.status();
        assertThat(refused.message()).isNotBlank();
        assertThat(physics.held).isNull();
        assertThat(tray.shown).isEmpty();
    }

    @Test
    void anExpressionOtherThanOneD6IsRefusedForNow() {
        controller.roll("2d6");
        controller.roll("1d20");

        assertThat(controller.status()).isInstanceOf(RollStatus.Refused.class);
        assertThat(physics.held).isNull();
    }

    @Test
    void aModifierIsAddedToTheResult() {
        simulator.script(atRest(FALL));
        controller.roll("1d6+5");
        physics.finish();
        clock.advance(1);

        int expected = new FaceValueGenerator(new SplittableRandom(FACE_SEED)).faceValue(DieKind.D6) + 5;
        assertThat(((RollStatus.Rested) controller.status()).result().total()).isEqualTo(expected);
    }

    @Test
    void attemptsThatDontComeToRestFlatAreRetriedSilently() {
        Recording cockedFall = fall(Orientation.about(new Vector3(1, 0, 0), 0.3f));
        simulator.script(new Attempt(Ending.COCKED, cockedFall, Set.of(0)), timedOut(), atRest(FALL));

        controller.roll("1d6");
        physics.finish();

        assertThat(simulator.requests).hasSize(3);
        assertThat(tray.shown).containsExactly(FALL);
    }

    @Test
    void whenNoAttemptComesToRestTheRollFailsAndTheTrayKeepsItsDice() {
        simulator.script(atRest(FALL));
        controller.roll("1d6");
        physics.finish();
        clock.advance(1);

        for (int i = 0; i < RollController.MAX_ATTEMPTS; i++) {
            simulator.script(timedOut());
        }
        controller.roll("1d6");
        physics.finish();

        assertThat(simulator.requests).hasSize(1 + RollController.MAX_ATTEMPTS);
        assertThat(controller.status()).isInstanceOf(RollStatus.Failed.class);
        assertThat(tray.shown).containsExactly(FALL);
    }

    @Test
    void aPhysicsErrorFailsTheRollRatherThanHangingIt() {
        simulator.script();

        controller.roll("1d6");
        physics.finish();

        assertThat(controller.status()).isInstanceOf(RollStatus.Failed.class);
        assertThat(tray.shown).isEmpty();
    }

    @Test
    void enterWhileARollIsUnderWayStartsNoSecondRoll() {
        simulator.script(atRest(FALL), atRest(FALL));
        controller.roll("1d6");
        controller.roll("1d6");
        physics.finish();

        controller.roll("1d6");
        assertThat(physics.held).isNull();
        assertThat(simulator.requests).hasSize(1);
    }

    /** One cube dropping from 5 cm to rest on the floor over half a second, ending in {@code end}. */
    private static Recording fall(Orientation end) {
        int samples = 61;
        float[] poses = new float[samples * 7];
        for (int s = 0; s < samples; s++) {
            float u = s / (float) (samples - 1);
            float[] pose = {0, 5 - 4.2f * u, 0, end.x() * u, end.y() * u, end.z() * u, 1 - (1 - end.w()) * u};
            float norm = (float) Math.sqrt(pose[3] * pose[3] + pose[4] * pose[4] + pose[5] * pose[5]
                    + pose[6] * pose[6]);
            for (int k = 3; k < 7; k++) {
                pose[k] /= norm;
            }
            System.arraycopy(pose, 0, poses, s * 7, 7);
        }
        return new Recording(1, poses, 0.5f, List.of());
    }

    private static Attempt atRest(Recording recording) {
        return new Attempt(Ending.AT_REST, recording, Set.of());
    }

    private static Attempt timedOut() {
        return new Attempt(Ending.TIMED_OUT, FALL, Set.of());
    }

    /** A simulator that answers each Attempt from a script, and fails once the script runs out. */
    private static final class ScriptedSimulator implements Simulator {
        final Deque<Attempt> script = new ArrayDeque<>();
        final List<AttemptRequest> requests = new ArrayList<>();

        void script(Attempt... attempts) {
            script.addAll(List.of(attempts));
        }

        @Override
        public Attempt simulate(AttemptRequest request) {
            requests.add(request);
            if (script.isEmpty()) {
                throw new IllegalStateException("The scripted simulator has no more Attempts");
            }
            return script.removeFirst();
        }
    }

    /** A physics worker that holds each job until the test lets it run, as if pre-simulating took a while. */
    private static final class HeldPhysics implements RollController.PhysicsJobs {
        private final Simulator simulator;
        Function<Simulator, Optional<ThrowPlayback>> held;
        private CompletableFuture<Optional<ThrowPlayback>> result;

        HeldPhysics(Simulator simulator) {
            this.simulator = simulator;
        }

        @Override
        public CompletableFuture<Optional<ThrowPlayback>> submit(
                Function<Simulator, Optional<ThrowPlayback>> job) {
            held = job;
            result = new CompletableFuture<>();
            return result;
        }

        /** Runs the held job, as the worker would, and completes its future. */
        void finish() {
            Function<Simulator, Optional<ThrowPlayback>> job = held;
            CompletableFuture<Optional<ThrowPlayback>> future = result;
            held = null;
            try {
                future.complete(job.apply(simulator));
            } catch (RuntimeException e) {
                future.completeExceptionally(e);
            }
        }
    }

    /** A Tray that remembers what it was asked to show. */
    private static final class RecordingTray implements RollController.TrayView {
        final List<Recording> shown = new ArrayList<>();
        final List<ThrowPlayback> playbacks = new ArrayList<>();
        final List<Double> times = new ArrayList<>();

        @Override
        public void show(ThrowPlayback playback) {
            shown.add(playback.recording());
            playbacks.add(playback);
            times.clear();
            times.add(0.0);
        }

        @Override
        public void showTime(double seconds) {
            times.add(seconds);
        }
    }
}
