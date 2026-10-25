package io.github.evildarkarchon.andysdice.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.github.evildarkarchon.andysdice.core.facevalue.FaceValueGenerator;
import io.github.evildarkarchon.andysdice.core.facevalue.FaceValues;
import io.github.evildarkarchon.andysdice.core.geometry.DieKind;
import io.github.evildarkarchon.andysdice.core.geometry.UpFace;
import io.github.evildarkarchon.andysdice.core.planner.PlannedThrow;
import io.github.evildarkarchon.andysdice.core.planner.RollPlan;
import io.github.evildarkarchon.andysdice.core.planner.ThrowKind;
import io.github.evildarkarchon.andysdice.core.playback.PlaybackClock;
import io.github.evildarkarchon.andysdice.core.simulation.Attempt;
import io.github.evildarkarchon.andysdice.core.simulation.AttemptRequest;
import io.github.evildarkarchon.andysdice.core.simulation.DieKindBody;
import io.github.evildarkarchon.andysdice.core.simulation.Ending;
import io.github.evildarkarchon.andysdice.core.simulation.Recording;
import io.github.evildarkarchon.andysdice.core.simulation.Release;
import io.github.evildarkarchon.andysdice.core.simulation.Simulator;
import io.github.evildarkarchon.andysdice.core.simulation.TrayGeometry;
import io.github.evildarkarchon.andysdice.core.simulation.Vector3;
import io.github.evildarkarchon.andysdice.core.tray.BuiltInTrays;
import io.github.evildarkarchon.andysdice.core.tray.Tray;
import io.github.evildarkarchon.andysdice.render.DiceScene;
import io.github.evildarkarchon.andysdice.render.Pickup;
import io.github.evildarkarchon.andysdice.render.camera.CameraScript;
import io.github.evildarkarchon.andysdice.render.camera.TrayCamera;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Enter on a Roll Expression, over a scripted simulator, scripted Face Values and a playback clock advanced by
 * hand: the whole chain of Throws is planned on the physics worker before anything moves, the Throws then play in
 * order with the other Dice lying still, and the Roll Result appears only once the last Throw is at rest. No GL,
 * no Jolt, no JavaFX, and no test sleeps (ADR-0011).
 */
class RollControllerTest {

    private static final double FRAME = 1.0 / 60;

    /** The spec's message for a Roll whose Dice wouldn't settle (spec §3.2). */
    private static final String WOULDNT_SETTLE =
            "The Dice wouldn't settle. Nothing was rolled. Press Enter to try again.";

    private PlaybackClock clock;
    private ScriptedSimulator simulator;
    private HeldPhysics physics;
    private RecordingTray tray;
    private List<RollStatus> statuses;

    @BeforeEach
    void setUp() {
        clock = new PlaybackClock();
        simulator = new ScriptedSimulator();
        physics = new HeldPhysics(simulator);
        tray = new RecordingTray();
        statuses = new ArrayList<>();
    }

    /** A controller whose Face Value generator draws exactly these values, throwing into the default Tray. */
    private RollController controller(ScriptedRandom faces) {
        return controller(faces, BuiltInTrays.defaultTray());
    }

    /** A controller whose Face Value generator draws exactly these values, throwing into a given Tray. */
    private RollController controller(ScriptedRandom faces, Tray into) {
        RollController controller = new RollController(clock, physics, Runnable::run, new FaceValueGenerator(faces),
                new SplittableRandom(1), into, tray);
        controller.setOnStatusChanged(statuses::add);
        return controller;
    }

    @Test
    void theDiceAreThrownIntoTheChosenTraysShapeAndMaterials() {
        Tray walnut = BuiltInTrays.find("03-walnut-board").orElseThrow();
        Tray hexagon = BuiltInTrays.find("02-leather-hexagon").orElseThrow();
        RollController intoWalnut = controller(new ScriptedRandom(4), walnut);

        intoWalnut.roll("1d6");
        physics.finish();

        assertThat(simulator.requests).isNotEmpty().allSatisfy(request -> {
            assertThat(request.tray()).isEqualTo(walnut.geometry());
            assertThat(request.materials()).isEqualTo(walnut.materials());
        });

        simulator.requests.clear();
        playToRest(intoWalnut);
        RollController intoHexagon = controller(new ScriptedRandom(2), hexagon);
        intoHexagon.roll("1d6");
        physics.finish();

        assertThat(simulator.requests).isNotEmpty().allSatisfy(request -> {
            assertThat(request.tray()).isEqualTo(hexagon.geometry());
            assertThat(request.materials()).isEqualTo(hexagon.materials());
        });
    }

    @Test
    void anEscapedDieIsNeverShown() {
        // The first two Attempts end with a Die over the wall (ADR-0010); only the third, at rest, is ever played.
        simulator.then(2, Ending.ESCAPED);
        RollController controller = controller(3, 5);

        controller.roll("2d6");
        physics.finish();

        assertThat(simulator.requests).hasSize(3);
        ChainPlayback chain = tray.chains.getFirst();
        assertThat(chain.plan().throwChain().getFirst().attempts()).isEqualTo(3);
        TrayGeometry geometry = BuiltInTrays.defaultTray().geometry();
        for (double t = 0; t <= chain.restTime() + FRAME; t += FRAME) {
            for (DiceScene.Die die : chain.diceAt(t)) {
                // Drawn in mm; the Tray's outline is in cm.
                assertThat(geometry.depthInside((float) die.position().x() / 10, (float) die.position().z() / 10))
                        .as("a Die at %.3f s", t).isPositive();
            }
        }
    }

    private RollController controller(int... faces) {
        return controller(new ScriptedRandom(faces));
    }

    /** Advances the clock a frame at a time until the Roll is no longer under way, or ten seconds pass. */
    private void playToRest(RollController controller) {
        for (int i = 0; controller.underWay() && i < 600; i++) {
            clock.advance(FRAME);
        }
    }

    @Test
    void beforeAnyRollTheTrayIsEmptyAndTheControllerIsReady() {
        RollController controller = controller();

        assertThat(controller.status()).isInstanceOf(RollStatus.Ready.class);
        assertThat(tray.chains).isEmpty();
    }

    @Test
    void enterPlansTheWholeChainOnThePhysicsWorkerBeforeAnythingMoves() {
        // 4d6rr1: the first roll shows a 1, so one Reroll Throw of that Die follows the first Throw.
        RollController controller = controller(1, 4, 5, 6, 3);

        controller.roll("4d6rr1kh3");

        assertThat(controller.status()).isInstanceOf(RollStatus.Rolling.class);
        assertThat(physics.held).isNotNull();
        assertThat(simulator.requests).isEmpty();
        assertThat(tray.chains).isEmpty();

        physics.finish();

        // Both Throws were simulated in that one job, before the Tray was given anything to play.
        assertThat(simulator.requests).hasSize(2);
        AttemptRequest first = simulator.requests.getFirst();
        assertThat(first.dice()).containsExactly(DieKindBody.of(DieKind.D6), DieKindBody.of(DieKind.D6),
                DieKindBody.of(DieKind.D6), DieKindBody.of(DieKind.D6));
        assertThat(first.release()).isInstanceOf(Release.Drawn.class);
        assertThat(first.frozen()).isEmpty();
        assertThat(simulator.requests.get(1).dice()).hasSize(1);
        assertThat(simulator.requests.get(1).frozen()).hasSize(3);
        assertThat(tray.chains).hasSize(1);
        assertThat(tray.chains.getFirst().plan().throwChain()).hasSize(2);
        assertThat(tray.times).containsExactly(0.0);
    }

    @Test
    void eachDieIsItsOwnDieKindTurnedSoTheChosenFaceIsUpAtRest() {
        RollController controller = controller(3, 5, 7, 9, 11, 19, 42);
        controller.roll("1d4+1d6+1d8+1d10+1d12+1d20+1d%");
        physics.finish();

        ChainPlayback chain = tray.chains.getLast();
        List<DiceScene.Die> atRest = chain.diceAt(chain.restTime());
        assertThat(atRest).extracting(DiceScene.Die::kind).containsExactly(DieKind.D4, DieKind.D6, DieKind.D8,
                DieKind.D10, DieKind.D12, DieKind.D20, DieKind.PERCENTILE_D10, DieKind.D10);
        int[] faces = {
            FaceValues.face(DieKind.D4, 3), FaceValues.face(DieKind.D6, 5), FaceValues.face(DieKind.D8, 7),
            FaceValues.face(DieKind.D10, 9), FaceValues.face(DieKind.D12, 11), FaceValues.face(DieKind.D20, 19),
            FaceValues.percentileTensFace(42), FaceValues.percentileUnitsFace(42)
        };
        for (int die = 0; die < atRest.size(); die++) {
            DiceScene.Die drawn = atRest.get(die);
            UpFace up = UpFace.read(drawn.kind(), drawn.orientation());
            assertThat(up.face()).as("die %d (%s)", die, drawn.kind()).isEqualTo(faces[die]);
            assertThat(up.cocked()).isFalse();
        }
    }

    @Test
    void rerollThrowsPlayInOrderWithTheOtherDiceLyingStill() {
        // 4d6rr1: rolls 0 and 2 show 1, so round 1 rerolls both; roll 0 shows 1 again, so round 2 rerolls it alone.
        RollController controller = controller(1, 4, 1, 6, 1, 3, 2);
        controller.roll("4d6rr1kh3");
        physics.finish();

        ChainPlayback chain = tray.chains.getLast();
        List<PlannedThrow> throwChain = chain.plan().throwChain();
        assertThat(throwChain).extracting(PlannedThrow::kind)
                .containsExactly(ThrowKind.FIRST, ThrowKind.REROLL, ThrowKind.REROLL);
        assertThat(throwChain).extracting(PlannedThrow::thrown)
                .containsExactly(List.of(0, 1, 2, 3), List.of(0, 2), List.of(0));

        List<Integer> order = new ArrayList<>();
        List<DiceScene.Die> before = chain.diceAt(0);
        boolean[] movedInThrow = new boolean[throwChain.size()];
        while (controller.underWay() && clock.time() < 10) {
            clock.advance(FRAME);
            double t = tray.times.getLast();
            int playing = chain.throwAt(t);
            if (order.isEmpty() || order.getLast() != playing) {
                order.add(playing);
            }
            List<DiceScene.Die> now = chain.diceAt(t);
            for (int frozen : throwChain.get(playing).frozen()) {
                assertThat(now.get(frozen)).as("Frozen Die %d at %.3f s in Throw %d", frozen, t, playing)
                        .isEqualTo(before.get(frozen));
            }
            for (int thrown : throwChain.get(playing).thrown()) {
                movedInThrow[playing] |= !now.get(thrown).equals(before.get(thrown));
            }
            before = now;
        }

        assertThat(order).containsExactly(0, 1, 2);
        assertThat(movedInThrow).containsOnly(true);
        // The last Throw leaves its Die where its Recording ends, in the renderer's mm.
        Recording last = throwChain.getLast().recording();
        Vector3 end = last.finalPose(0).position();
        assertThat(before.get(0).position().distance(new Vector3d(end.x() * 10, end.y() * 10, end.z() * 10)))
                .isLessThan(1e-3);
    }

    @Test
    void theRollResultAppearsOnlyAfterTheLastThrowIsAtRest() {
        RollController controller = controller(1, 4, 1, 6, 1, 3, 2);
        controller.roll("4d6rr1kh3");
        physics.finish();
        ChainPlayback chain = tray.chains.getLast();

        while (clock.time() + FRAME < chain.restTime()) {
            clock.advance(FRAME);
            assertThat(tray.times.getLast()).isCloseTo(clock.time(), within(1e-9));
            assertThat(controller.status()).isInstanceOf(RollStatus.Rolling.class);
        }
        clock.advance(FRAME);

        // Rolls show 2 (after 1, 1), 4, 3 (after 1) and 6; the highest three make 13.
        RollStatus.Rested rested = (RollStatus.Rested) controller.status();
        assertThat(rested.result().total()).isEqualTo(13);
        assertThat(rested.text()).isEqualTo("4d6rr1kh3 = 13");
        assertThat(statuses).filteredOn(RollStatus.Rested.class::isInstance).hasSize(1);
    }

    @Test
    void theCameraFramesTheTrayWhileEachThrowTumblesSettlesInAtRestAndEasesOutBeforeTheNext() {
        // 4d6rr1: the first roll shows a 1, so one Reroll Throw of that Die follows the first Throw.
        RollController controller = controller(1, 4, 5, 6, 3);
        controller.roll("4d6rr1kh3");
        physics.finish();
        ChainPlayback chain = tray.chains.getLast();
        double firstRest = chain.plan().throwChain().getFirst().recording().restTime();

        // Between the first Throw at rest and the Reroll Throw's Release: the settle-in, the ease back out, and
        // only then the reroll pickup (spec §3.2: "the camera eases out, the Cocked Dice glow and lift").
        assertThat(chain.throwStart(1) - firstRest).isCloseTo(
                TrayCamera.SETTLE_SECONDS + TrayCamera.EASE_OUT_SECONDS + Pickup.SECONDS, within(1e-9));
        // Within that gap, until the pickup, the first Throw's Dice lie where it left them; after the chain, where
        // the last one did.
        List<DiceScene.Die> afterFirst = chain.diceAt(chain.throwStart(1) - Pickup.SECONDS - 0.01);
        List<DiceScene.Die> afterLast = chain.diceAt(chain.restTime() + 1);
        CameraScript script = chain.cameraScript();
        assertThat(script.throwStarts()).containsExactly(0.0, chain.throwStart(1));
        assertThat(script.cues()).containsExactly(
                new CameraScript.Cue(0, new CameraScript.Shot.WholeTray(), TrayCamera.EASE_OUT_SECONDS),
                new CameraScript.Cue(firstRest, new CameraScript.Shot.Resting(afterFirst), TrayCamera.SETTLE_SECONDS),
                new CameraScript.Cue(firstRest + TrayCamera.SETTLE_SECONDS, new CameraScript.Shot.WholeTray(),
                        TrayCamera.EASE_OUT_SECONDS),
                new CameraScript.Cue(chain.restTime(), new CameraScript.Shot.Resting(afterLast),
                        TrayCamera.SETTLE_SECONDS));
    }

    @Test
    void onceTheCameraHasEasedOutOnlyTheDiceGoingBackGlowAndLiftToTheirRelease() {
        // 4d6rr1: the first roll shows a 1, so Die 0 alone goes back for the Reroll Throw.
        RollController controller = controller(1, 4, 5, 6, 3);
        controller.roll("4d6rr1kh3");
        physics.finish();
        ChainPlayback chain = tray.chains.getLast();
        double firstRest = chain.plan().throwChain().getFirst().recording().restTime();
        // The pickup is the last part of the gap before the Reroll Throw, once the camera has eased out.
        double pickupStart = chain.throwStart(1) - Pickup.SECONDS;
        assertThat(pickupStart).isCloseTo(firstRest + TrayCamera.SETTLE_SECONDS + TrayCamera.EASE_OUT_SECONDS,
                within(1e-9));
        List<DiceScene.Die> lying = chain.diceAt(firstRest + FRAME);
        DiceScene.Die release = chain.diceAt(chain.throwStart(1)).get(0);

        // While the camera settles in and eases out, nothing moves or glows.
        for (double t = firstRest + FRAME; t < pickupStart - 1e-9; t += FRAME) {
            assertThat(chain.diceAt(t)).as("at %.3f s", t).isEqualTo(lying);
        }
        double brightest = 0;
        double highest = Double.NEGATIVE_INFINITY;
        for (double t = pickupStart; t < chain.throwStart(1); t += FRAME / 4) {
            List<DiceScene.Die> now = chain.diceAt(t);
            // The Frozen Dice lie where they are, unlit, all through the pickup.
            assertThat(now.subList(1, 4)).as("at %.3f s", t).isEqualTo(lying.subList(1, 4));
            DiceScene.Die going = now.get(0);
            assertThat(going).isEqualTo(Pickup.at(lying.get(0), release, t - pickupStart, false));
            brightest = Math.max(brightest, going.glow());
            highest = Math.max(highest, going.position().y());
        }
        assertThat(brightest).as("it glows").isGreaterThan(0.9);
        // Up out of the Tray on its way to the Release, above both where it lay and where it is let go.
        assertThat(highest).isGreaterThan(Math.max(lying.get(0).position().y(), release.position().y()) + 10);
        // And it is at its Release as the Reroll Throw starts, so nothing jumps.
        assertThat(chain.diceAt(chain.throwStart(1) - 1e-6).get(0).position().distance(release.position()))
                .isLessThan(1e-3);
        assertThat(release.glow()).isZero();
    }

    @Test
    void withReducedMotionThePickupGlowsSteadilyAndLiftsAtTwiceTheSpeedOnTheSameTimeline() {
        RollController controller = controller(1, 4, 5, 6, 3);
        controller.roll("4d6rr1kh3");
        physics.finish();
        ChainPlayback chain = tray.chains.getLast();
        double pickupStart = chain.throwStart(1) - Pickup.SECONDS;
        DiceScene.Die lying = chain.diceAt(pickupStart - 0.01).get(0);
        DiceScene.Die release = chain.diceAt(chain.throwStart(1)).get(0);

        for (double t = pickupStart; t < chain.throwStart(1); t += FRAME / 4) {
            List<DiceScene.Die> now = chain.diceAt(t, true);
            assertThat(now.get(0)).isEqualTo(Pickup.at(lying, release, t - pickupStart, true));
            assertThat(now.subList(1, 4)).isEqualTo(chain.diceAt(t, false).subList(1, 4));
        }
        // Reduced motion leaves every Throw where it was.
        assertThat(chain.diceAt(chain.throwStart(1), true)).isEqualTo(chain.diceAt(chain.throwStart(1), false));
    }

    @Test
    void aCockedRethrowPlaysTheSamePickupAsARerollThrow() {
        // Every Attempt of the first Throw cocks Die 0, so a Cocked Rethrow throws it alone again.
        simulator.then(24, Ending.COCKED);
        RollController controller = controller(4, 2);
        controller.roll("2d6");
        physics.finish();
        ChainPlayback chain = tray.chains.getLast();
        assertThat(chain.plan().throwChain()).extracting(PlannedThrow::kind)
                .containsExactly(ThrowKind.FIRST, ThrowKind.COCKED_RETHROW);
        double firstRest = chain.plan().throwChain().getFirst().recording().restTime();
        assertThat(chain.throwStart(1) - firstRest).isCloseTo(
                TrayCamera.SETTLE_SECONDS + TrayCamera.EASE_OUT_SECONDS + Pickup.SECONDS, within(1e-9));

        double pickupStart = chain.throwStart(1) - Pickup.SECONDS;
        List<DiceScene.Die> lying = chain.diceAt(pickupStart - 0.01);
        DiceScene.Die release = chain.diceAt(chain.throwStart(1)).get(0);
        for (double t = pickupStart; t < chain.throwStart(1); t += FRAME / 4) {
            List<DiceScene.Die> now = chain.diceAt(t);
            assertThat(now.get(0)).isEqualTo(Pickup.at(lying.get(0), release, t - pickupStart, false));
            assertThat(now.get(1)).isEqualTo(lying.get(1));
        }
    }

    @Test
    void aRollOfOneThrowSettlesInOnceItsDiceRest() {
        RollController controller = controller(4);
        controller.roll("1d6");
        physics.finish();
        ChainPlayback chain = tray.chains.getLast();

        assertThat(chain.cameraScript().throwStarts()).containsExactly(0.0);
        assertThat(chain.cameraScript().cues()).extracting(CameraScript.Cue::time).containsExactly(0.0,
                chain.restTime());
    }

    @Test
    void theClockStartingLaterThanZeroStillPlaysFromTheRelease() {
        RollController controller = controller(4);
        clock.advance(12.5);
        controller.roll("1d6");
        physics.finish();

        clock.advance(FRAME);

        assertThat(tray.times).hasSize(2);
        assertThat(tray.times.getLast()).isCloseTo(FRAME, within(1e-9));
    }

    @Test
    void theDiceStayOnTheTrayUntilTheNextRollIsReadyToPlay() {
        RollController controller = controller(4, 2);
        controller.roll("1d6");
        physics.finish();
        playToRest(controller);
        int drawn = tray.times.size();
        clock.advance(2);
        assertThat(controller.status()).isInstanceOf(RollStatus.Rested.class);
        assertThat(tray.times).hasSize(drawn);

        controller.roll("1d6");
        clock.advance(FRAME);

        // Still pre-simulating: the first Dice are left where they lie, and nothing is redrawn.
        assertThat(tray.chains).hasSize(1);
        assertThat(tray.times).hasSize(drawn);
        physics.finish();
        assertThat(tray.chains).hasSize(2);
    }

    @Test
    void aModifierIsAddedToTheResult() {
        RollController controller = controller(4);
        controller.roll("1d6+5");
        physics.finish();
        playToRest(controller);

        assertThat(((RollStatus.Rested) controller.status()).result().total()).isEqualTo(9);
    }

    @Test
    void anInvalidRollExpressionRollsNothingAndSaysWhy() {
        RollController controller = controller();

        controller.roll("1d6+");

        RollStatus.Refused refused = (RollStatus.Refused) controller.status();
        assertThat(refused.message()).isNotBlank();
        assertThat(physics.held).isNull();
        assertThat(tray.chains).isEmpty();
    }

    @Test
    void aFailedRollMovesNothingKeepsThePreviousDiceAndShowsItsReason() {
        RollController controller = controller(4, 2, 5);
        controller.roll("1d6");
        physics.finish();
        playToRest(controller);
        int drawn = tray.times.size();

        // Every Attempt of the next Roll's first Throw times out.
        simulator.then(24, Ending.TIMED_OUT);
        controller.roll("2d6");
        physics.finish();
        clock.advance(1);

        RollStatus.Failed failed = (RollStatus.Failed) controller.status();
        assertThat(failed.text()).isEqualTo(WOULDNT_SETTLE);
        assertThat(controller.underWay()).isFalse();
        assertThat(tray.chains).hasSize(1);
        assertThat(tray.times).hasSize(drawn);
    }

    @Test
    void aRollPastTheRerollBackstopFailsWithNothingSimulated() {
        RollController controller = controller(new ScriptedRandom().thenRepeat(101, 1));

        controller.roll("1d6rr1");
        physics.finish();

        assertThat(controller.status().text()).isEqualTo("Too many rerolls. Nothing was rolled.");
        assertThat(simulator.requests).isEmpty();
        assertThat(tray.chains).isEmpty();
    }

    @Test
    void aPhysicsErrorFailsTheRollRatherThanHangingIt() {
        RollController controller = controller(4);
        simulator.failNext();

        controller.roll("1d6");
        physics.finish();

        assertThat(controller.status()).isInstanceOf(RollStatus.Failed.class);
        assertThat(controller.underWay()).isFalse();
        assertThat(tray.chains).isEmpty();
    }

    @Test
    void enterWhileARollIsUnderWayStartsNoSecondRoll() {
        RollController controller = controller(4, 2, 3);
        controller.roll("1d6");
        controller.roll("1d6");
        physics.finish();

        controller.roll("1d6");
        assertThat(physics.held).isNull();
        assertThat(simulator.requests).hasSize(1);
    }

    /**
     * A simulator that ends each Attempt as scripted (at rest once the script runs out) with a 50/120 s
     * Recording of every thrown Die falling from its Release to lie flat on a face, at a position unique to the
     * Attempt and the Die. Its Recordings never move Frozen Dice, as a real one's never do.
     */
    private static final class ScriptedSimulator implements Simulator {
        private static final int SAMPLES = 51;
        final Deque<Ending> script = new ArrayDeque<>();
        final List<AttemptRequest> requests = new ArrayList<>();
        private boolean failNext;

        /** Scripts the next {@code times} Attempts to end in {@code ending}; a cocked one cocks request Die 0. */
        void then(int times, Ending ending) {
            for (int i = 0; i < times; i++) {
                script.add(ending);
            }
        }

        /** Makes the next Attempt throw, as a crashed engine would. */
        void failNext() {
            failNext = true;
        }

        @Override
        public Attempt simulate(AttemptRequest request) {
            if (failNext) {
                failNext = false;
                throw new IllegalStateException("the scripted simulator was told to fail");
            }
            int call = requests.size();
            requests.add(request);
            Ending ending = script.isEmpty() ? Ending.AT_REST : script.poll();
            int dice = request.dice().size();
            float[] poses = new float[SAMPLES * dice * 7];
            for (int die = 0; die < dice; die++) {
                DieKind kind = ((DieKindBody) request.dice().get(die)).kind();
                Vector3 from = new Vector3(3 * die - 6, 8, -5);
                // An escaped Attempt ends with its Dice over the far wall, outside the Tray and below its floor.
                Vector3 to = ending == Ending.ESCAPED
                        ? new Vector3(3 * die, -2, 25)
                        : new Vector3(call, (float) kind.inradius() / 10, die);
                Quaterniond end = flat(kind, (call * 7 + die * 3) % kind.faceCount(), call + die);
                for (int s = 0; s < SAMPLES; s++) {
                    double u = s / (double) (SAMPLES - 1);
                    Quaterniond q = new Quaterniond().nlerp(end, u);
                    int at = (s * dice + die) * 7;
                    poses[at] = (float) (from.x() + (to.x() - from.x()) * u);
                    poses[at + 1] = (float) (from.y() + (to.y() - from.y()) * u);
                    poses[at + 2] = (float) (from.z() + (to.z() - from.z()) * u);
                    poses[at + 3] = (float) q.x;
                    poses[at + 4] = (float) q.y;
                    poses[at + 5] = (float) q.z;
                    poses[at + 6] = (float) q.w;
                }
            }
            // At rest on the last sample. 50/120 s doesn't survive the float round trip exactly, as a real
            // Recording's length often doesn't, so reading the pose "at the duration" lands just short of it.
            Recording recording = new Recording(dice, poses, (SAMPLES - 1) / (float) Recording.SAMPLE_RATE_HZ,
                    List.of());
            return new Attempt(ending, recording, ending == Ending.COCKED ? Set.of(0) : Set.of());
        }

        /** Lying flat on {@code face}, turned about the vertical; a d4 lies on the face it reads. */
        private static Quaterniond flat(DieKind kind, int face, double yaw) {
            Vector3dc normal = kind.solid().faces().get(face).normal();
            Vector3d target = new Vector3d(0, kind == DieKind.D4 ? -1 : 1, 0);
            Quaterniond q = new Quaterniond().rotationY(yaw).mul(new Quaterniond().rotationTo(normal, target));
            // The short way from the Release's identity, so the fall turns the least.
            return q.w < 0 ? new Quaterniond(-q.x, -q.y, -q.z, -q.w) : q;
        }
    }

    /** A physics worker that holds each job until the test lets it run, as if pre-simulating took a while. */
    private static final class HeldPhysics implements RollController.PhysicsJobs {
        private final Simulator simulator;
        Function<Simulator, RollPlan> held;
        private CompletableFuture<RollPlan> result;

        HeldPhysics(Simulator simulator) {
            this.simulator = simulator;
        }

        @Override
        public CompletableFuture<RollPlan> submit(Function<Simulator, RollPlan> job) {
            held = job;
            result = new CompletableFuture<>();
            return result;
        }

        /** Runs the held job, as the worker would, and completes its future. */
        void finish() {
            Function<Simulator, RollPlan> job = held;
            CompletableFuture<RollPlan> future = result;
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
        final List<ChainPlayback> chains = new ArrayList<>();
        final List<Double> times = new ArrayList<>();

        @Override
        public void show(ChainPlayback chain) {
            chains.add(chain);
            times.clear();
            times.add(0.0);
        }

        @Override
        public void showTime(double seconds) {
            times.add(seconds);
        }
    }
}
