package io.github.evildarkarchon.andysdice.app;

import static io.github.evildarkarchon.andysdice.app.RollExpressions.valid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.github.evildarkarchon.andysdice.core.diceset.DiceSet;
import io.github.evildarkarchon.andysdice.core.diceset.DiceSetCatalogue;
import io.github.evildarkarchon.andysdice.core.facevalue.FaceValueGenerator;
import io.github.evildarkarchon.andysdice.core.facevalue.FaceValues;
import io.github.evildarkarchon.andysdice.core.geometry.DieKind;
import io.github.evildarkarchon.andysdice.core.geometry.UpFace;
import io.github.evildarkarchon.andysdice.core.planner.PlannedThrow;
import io.github.evildarkarchon.andysdice.core.planner.ThrowKind;
import io.github.evildarkarchon.andysdice.core.playback.PlaybackClock;
import io.github.evildarkarchon.andysdice.core.simulation.AttemptRequest;
import io.github.evildarkarchon.andysdice.core.simulation.DieKindBody;
import io.github.evildarkarchon.andysdice.core.simulation.Ending;
import io.github.evildarkarchon.andysdice.core.simulation.Recording;
import io.github.evildarkarchon.andysdice.core.simulation.Release;
import io.github.evildarkarchon.andysdice.core.simulation.TrayGeometry;
import io.github.evildarkarchon.andysdice.core.simulation.Vector3;
import io.github.evildarkarchon.andysdice.core.tray.BuiltInTrays;
import io.github.evildarkarchon.andysdice.core.tray.Tray;
import io.github.evildarkarchon.andysdice.render.DiceScene;
import io.github.evildarkarchon.andysdice.render.LightRig;
import io.github.evildarkarchon.andysdice.render.Pickup;
import io.github.evildarkarchon.andysdice.render.camera.CameraScript;
import io.github.evildarkarchon.andysdice.render.camera.TrayCamera;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.SplittableRandom;
import org.joml.Vector3d;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A Roll's whole lifecycle, driven only through the roll controller's interface (roll, cancel the Queued Roll,
 * abandon, set the Look, and advancing the playback clock) over a scripted simulator, scripted Face Values and a
 * clock advanced by hand: the whole chain of Throws is planned on the physics worker before anything moves, the
 * Throws then play in order with the other Dice lying still, and the Roll Result appears only once the last Throw is
 * at rest. A Roll asked for meanwhile waits as the Queued Roll and starts a beat after the reveal (or the failure),
 * and JavaFX's pulse is asked for exactly while something needs the clock. A Tray picked while Dice move is drawn
 * once they rest. No GL, no Jolt, no JavaFX, and no test sleeps (ADR-0011). It asserts only on the Roll's state
 * snapshots the controller publishes and on what the Tray and the pulse were told.
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
    private HandPulses pulses;
    /** Every snapshot the controller published, in order. */
    private List<RollState> published;

    @BeforeEach
    void setUp() {
        clock = new PlaybackClock();
        simulator = new ScriptedSimulator();
        physics = new HeldPhysics(simulator);
        tray = new RecordingTray();
        pulses = new HandPulses();
        published = new ArrayList<>();
    }

    /** The snapshot published last. */
    private RollState latest() {
        return published.getLast();
    }

    /** The status in the snapshot published last. */
    private RollStatus status() {
        return latest().status();
    }

    /** Whether the snapshot published last has a Roll under way. */
    private boolean underWay() {
        return latest().underWay();
    }

    /** The Queued Roll's text in the snapshot published last. */
    private Optional<String> queued() {
        return latest().queued().map(queuedRoll -> queuedRoll.expression().source());
    }

    /** Each status published, once each, in order. */
    private List<RollStatus> statuses() {
        List<RollStatus> statuses = new ArrayList<>();
        published.forEach(RollState.statusChanges(statuses::add));
        return statuses;
    }

    /** Each change of the Queued Roll's text across the published snapshots, from none at the start. */
    private List<Optional<String>> queuedChanges() {
        List<Optional<String>> changes = new ArrayList<>();
        Optional<String> last = Optional.empty();
        for (RollState state : published) {
            Optional<String> now = state.queued().map(queuedRoll -> queuedRoll.expression().source());
            if (!now.equals(last)) {
                changes.add(now);
                last = now;
            }
        }
        return changes;
    }

    /** A controller whose Face Value generator draws exactly these values, throwing into the default Tray. */
    private RollController controller(ScriptedRandom faces) {
        return controller(faces, BuiltInTrays.defaultTray());
    }

    /** A controller whose Face Value generator draws exactly these values, throwing into a given Tray. */
    private RollController controller(ScriptedRandom faces, Tray into) {
        RollController controller = new RollController(clock, pulses, physics, Runnable::run,
                new FaceValueGenerator(faces), new SplittableRandom(1), into, tray);
        controller.addStateListener(published::add);
        return controller;
    }

    @Test
    void theDiceAreThrownIntoTheChosenTraysShapeAndMaterials() {
        Tray walnut = BuiltInTrays.find("03-walnut-board").orElseThrow();
        Tray hexagon = BuiltInTrays.find("02-leather-hexagon").orElseThrow();
        RollController intoWalnut = controller(new ScriptedRandom(4), walnut);

        intoWalnut.roll(valid("1d6"));
        physics.finish();

        assertThat(simulator.requests).isNotEmpty().allSatisfy(request -> {
            assertThat(request.tray()).isEqualTo(walnut.geometry());
            assertThat(request.materials()).isEqualTo(walnut.materials());
        });

        simulator.requests.clear();
        playToRest(intoWalnut);
        RollController intoHexagon = controller(new ScriptedRandom(2), hexagon);
        intoHexagon.roll(valid("1d6"));
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

        controller.roll(valid("2d6"));
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
        for (int i = 0; underWay() && i < 600; i++) {
            clock.advance(FRAME);
        }
    }

    /** Advances the clock by whole 60 Hz frames of real time. */
    private void frames(int count) {
        for (int i = 0; i < count; i++) {
            clock.advance(FRAME);
        }
    }

    @Test
    void beforeAnyRollTheTrayIsEmptyAndTheControllerIsReady() {
        RollController controller = controller();

        assertThat(controller.state().status()).isInstanceOf(RollStatus.Ready.class);
        assertThat(tray.chains).isEmpty();
    }

    @Test
    void enterPlansTheWholeChainOnThePhysicsWorkerBeforeAnythingMoves() {
        // 4d6rr1: the first roll shows a 1, so one Reroll Throw of that Die follows the first Throw.
        RollController controller = controller(1, 4, 5, 6, 3);

        controller.roll(valid("4d6rr1kh3"));

        assertThat(status()).isInstanceOf(RollStatus.PreSimulating.class);
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
        controller.roll(valid("1d4+1d6+1d8+1d10+1d12+1d20+1d%"));
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
    void eachThrowIsInMotionFromItsReleaseUntilItsDiceRest() {
        // 4d6rr1 with these faces plans three Throws (see the test below).
        RollController controller = controller(1, 4, 1, 6, 1, 3, 2);
        controller.roll(valid("4d6rr1kh3"));
        physics.finish();

        ChainPlayback chain = tray.chains.getLast();
        List<PlannedThrow> throwChain = chain.plan().throwChain();
        assertThat(throwChain).hasSize(3);
        assertThat(chain.throwInMotion(-0.01)).as("before the first Release").isEqualTo(-1);
        for (int k = 0; k < throwChain.size(); k++) {
            double release = chain.throwStart(k);
            double rest = release + throwChain.get(k).recording().restTime();
            assertThat(chain.throwInMotion(release)).as("Throw %d at its Release", k).isEqualTo(k);
            assertThat(chain.throwInMotion((release + rest) / 2)).as("Throw %d tumbling", k).isEqualTo(k);
            assertThat(chain.throwInMotion(rest)).as("Throw %d at rest", k).isEqualTo(-1);
            assertThat(chain.throwInMotion(rest + 0.5)).as("after Throw %d, before the next", k).isEqualTo(-1);
        }
        assertThat(chain.throwInMotion(chain.restTime() + 1)).isEqualTo(-1);
    }

    @Test
    void rerollThrowsPlayInOrderWithTheOtherDiceLyingStill() {
        // 4d6rr1: rolls 0 and 2 show 1, so round 1 rerolls both; roll 0 shows 1 again, so round 2 rerolls it alone.
        RollController controller = controller(1, 4, 1, 6, 1, 3, 2);
        controller.roll(valid("4d6rr1kh3"));
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
        while (underWay() && clock.time() < 10) {
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
        controller.roll(valid("4d6rr1kh3"));
        physics.finish();
        ChainPlayback chain = tray.chains.getLast();

        while (clock.time() + FRAME < chain.restTime()) {
            clock.advance(FRAME);
            assertThat(tray.times.getLast()).isCloseTo(clock.time(), within(1e-9));
            assertThat(status()).isInstanceOf(RollStatus.Playing.class);
        }
        clock.advance(FRAME);

        // Rolls show 2 (after 1, 1), 4, 3 (after 1) and 6; the highest three make 13.
        RollStatus.Rested rested = (RollStatus.Rested) status();
        assertThat(rested.result().total()).isEqualTo(13);
        assertThat(rested.text()).isEqualTo("4d6rr1kh3 = 13");
        assertThat(statuses()).filteredOn(RollStatus.Rested.class::isInstance).hasSize(1);
    }

    @Test
    void theCameraFramesTheTrayWhileEachThrowTumblesSettlesInAtRestAndEasesOutBeforeTheNext() {
        // 4d6rr1: the first roll shows a 1, so one Reroll Throw of that Die follows the first Throw.
        RollController controller = controller(1, 4, 5, 6, 3);
        controller.roll(valid("4d6rr1kh3"));
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
        controller.roll(valid("4d6rr1kh3"));
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
        controller.roll(valid("4d6rr1kh3"));
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
        controller.roll(valid("2d6"));
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
        controller.roll(valid("1d6"));
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
        controller.roll(valid("1d6"));
        physics.finish();

        clock.advance(FRAME);

        assertThat(tray.times).hasSize(2);
        assertThat(tray.times.getLast()).isCloseTo(FRAME, within(1e-9));
    }

    @Test
    void theDiceStayOnTheTrayUntilTheNextRollIsReadyToPlay() {
        RollController controller = controller(4, 2);
        controller.roll(valid("1d6"));
        physics.finish();
        playToRest(controller);
        int drawn = tray.times.size();
        clock.advance(2);
        assertThat(status()).isInstanceOf(RollStatus.Rested.class);
        assertThat(tray.times).hasSize(drawn);

        controller.roll(valid("1d6"));
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
        controller.roll(valid("1d6+5"));
        physics.finish();
        playToRest(controller);

        assertThat(((RollStatus.Rested) status()).result().total()).isEqualTo(9);
    }

    @Test
    void aFailedRollMovesNothingKeepsThePreviousDiceAndShowsItsReason() {
        RollController controller = controller(4, 2, 5);
        controller.roll(valid("1d6"));
        physics.finish();
        playToRest(controller);
        int drawn = tray.times.size();

        // Every Attempt of the next Roll's first Throw times out.
        simulator.then(24, Ending.TIMED_OUT);
        controller.roll(valid("2d6"));
        physics.finish();
        clock.advance(1);

        RollStatus.Failed failed = (RollStatus.Failed) status();
        assertThat(failed.text()).isEqualTo(WOULDNT_SETTLE);
        assertThat(underWay()).isFalse();
        assertThat(tray.chains).hasSize(1);
        assertThat(tray.times).hasSize(drawn);
    }

    @Test
    void aRollPastTheRerollBackstopFailsWithNothingSimulated() {
        RollController controller = controller(new ScriptedRandom().thenRepeat(101, 1));

        controller.roll(valid("1d6rr1"));
        physics.finish();

        assertThat(status().text()).isEqualTo("Too many rerolls. Nothing was rolled.");
        assertThat(simulator.requests).isEmpty();
        assertThat(tray.chains).isEmpty();
    }

    @Test
    void aPhysicsErrorFailsTheRollRatherThanHangingIt() {
        RollController controller = controller(4);
        simulator.failNext();

        controller.roll(valid("1d6"));
        physics.finish();

        assertThat(status()).isInstanceOf(RollStatus.Failed.class);
        assertThat(underWay()).isFalse();
        assertThat(tray.chains).isEmpty();
    }

    // The Roll's state snapshot: published on every change, to every listener, in the order they were added.

    @Test
    void eachChangeIsOneSnapshotFromPreSimulatingThroughPlayingToRestThenSettledOnceTheCameraHasSettledIn() {
        RollController controller = controller(4);
        clock.advance(2.5);
        assertThat(controller.state().settled()).as("nothing rolled yet").isTrue();

        controller.roll(valid("1d6"));

        assertThat(published).hasSize(1);
        assertThat(status()).isInstanceOf(RollStatus.PreSimulating.class);
        assertThat(status().text()).isEqualTo("Rolling…");
        assertThat(latest().queued()).isEmpty();
        assertThat(latest().settled()).isFalse();
        // Under way, so camera gestures are blocked, from Pre-simulating through Playing.
        assertThat(latest().underWay()).isTrue();

        physics.finish();

        assertThat(published).hasSize(2);
        RollStatus.Playing playing = (RollStatus.Playing) status();
        assertThat(playing.chain()).isSameAs(tray.chains.getFirst());
        assertThat(playing.start()).as("the clock's time at its first Release").isEqualTo(2.5);
        assertThat(playing.text()).isEqualTo("Rolling…");
        assertThat(latest().settled()).isFalse();
        assertThat(latest().underWay()).isTrue();

        // The camera's settle-in on the Dice at rest outlasts the last Throw.
        tray.moving = true;
        playToRest(controller);
        assertThat(published).hasSize(3);
        assertThat(status()).isInstanceOf(RollStatus.Rested.class);
        assertThat(latest().underWay()).isFalse();
        assertThat(latest().settled()).isFalse();
        // Clock ticks that change nothing publish nothing.
        frames(10);
        assertThat(published).hasSize(3);

        tray.moving = false;
        frames(1);

        assertThat(published).hasSize(4);
        assertThat(latest().status()).isSameAs(published.get(2).status());
        assertThat(latest().settled()).isTrue();
        assertThat(controller.state()).isEqualTo(latest());
        frames(10);
        assertThat(published).hasSize(4);
    }

    @Test
    void theSnapshotHoldsTheQueuedRollWithItsSavedRollsNameAndIsNeverSettledWhileItWaits() {
        RollController controller = controller(4, 1, 2, 3, 4, 5, 6, 1, 2);
        controller.roll(valid("1d6"));

        controller.roll(valid("8d6"), Optional.of("Fireball"));

        assertThat(latest().queued()).contains(new RollRequest(valid("8d6"), Optional.of("Fireball")));
        assertThat(status()).isInstanceOf(RollStatus.PreSimulating.class);
        physics.finish();
        playToRest(controller);
        // At rest, but the Queued Roll waits out its beat.
        assertThat(status()).isInstanceOf(RollStatus.Rested.class);
        assertThat(latest().queued()).isPresent();
        frames(54);
        assertThat(status()).isInstanceOf(RollStatus.PreSimulating.class);
        assertThat(latest().queued()).isEmpty();
        // Not even for a moment as the Queued Roll took over from the Roll at rest.
        assertThat(published).noneMatch(RollState::settled);
    }

    @Test
    void everyListenerIsToldEverySnapshotInTheOrderTheyWereAddedAndNoneReplacesAnother() {
        RollController controller = controller(4);
        List<String> told = new ArrayList<>();
        controller.addStateListener(state -> told.add("second " + state.status().getClass().getSimpleName()));
        controller.addStateListener(state -> told.add("third " + state.status().getClass().getSimpleName()));

        controller.roll(valid("1d6"));
        physics.finish();

        assertThat(told).containsExactly("second PreSimulating", "third PreSimulating", "second Playing",
                "third Playing");
        // The first listener, added with the controller, still hears every one.
        assertThat(published).extracting(RollState::status)
                .extracting(status -> status.getClass().getSimpleName())
                .containsExactly("PreSimulating", "Playing");
    }

    @Test
    void aListenerThatRollsFromItsNotificationLeavesTheListenersAfterItToldInOrder() {
        RollController controller = controller(4, 2);
        // Rolls again the moment the first Roll rests, from inside that snapshot's notification.
        controller.addStateListener(RollState.statusChanges(status -> {
            if (status instanceof RollStatus.Rested && tray.chains.size() == 1) {
                controller.roll(valid("1d4"));
            }
        }));
        List<RollStatus> later = new ArrayList<>();
        controller.addStateListener(RollState.statusChanges(later::add));

        controller.roll(valid("1d6"));
        physics.finish();
        frames((int) Math.ceil(tray.chains.getFirst().restTime() / FRAME) + 1);

        assertThat(later).extracting(status -> status.getClass().getSimpleName())
                .containsExactly("PreSimulating", "Playing", "Rested", "PreSimulating");
        assertThat(statuses()).containsExactlyElementsOf(later);
    }

    // The Queued Roll (spec §3.2 "Entry"; GLOSSARY).

    @Test
    void aRollAskedForWhileOneIsUnderWayWaitsAsTheQueuedRoll() {
        RollController controller = controller(4, 2);
        controller.roll(valid("1d6"));

        // Still pre-simulating: it queues rather than starting a second Roll.
        controller.roll(valid("2d6"));
        assertThat(queued()).contains("2d6");
        assertThat(queuedChanges()).containsExactly(Optional.of("2d6"));
        physics.finish();
        assertThat(tray.chains).hasSize(1);

        // And while the first Roll plays, too: it plays on untouched, and nothing more is simulated.
        frames(3);
        assertThat(queued()).contains("2d6");
        assertThat(physics.held).isNull();
        assertThat(simulator.requests).hasSize(1);
        assertThat(statuses()).filteredOn(RollStatus.PreSimulating.class::isInstance).hasSize(1);
    }

    @Test
    void aRollWithNothingUnderWayRollsAtOnce() {
        RollController controller = controller(4, 2);
        controller.roll(valid("1d6"));
        physics.finish();
        playToRest(controller);

        controller.roll(valid("1d8"));

        assertThat(queued()).isEmpty();
        assertThat(queuedChanges()).isEmpty();
        assertThat(underWay()).isTrue();
        assertThat(physics.held).isNotNull();
    }

    @Test
    void theQueuedRollStartsABeatAfterTheRollResultIsRevealed() {
        assertThat(RollController.BEAT_SECONDS).as("spec §4: about 0.9 s").isEqualTo(0.9);
        RollController controller = controller(4, 6);
        controller.roll(valid("1d6"));
        physics.finish();
        controller.roll(valid("1d6+1"));
        playToRest(controller);
        double revealed = clock.time();
        assertThat(status().text()).isEqualTo("1d6 = 4");

        // Through the beat the Roll Result stays up and the Queued Roll waits.
        frames(51);
        assertThat(underWay()).isFalse();
        assertThat(queued()).contains("1d6+1");
        assertThat(status().text()).isEqualTo("1d6 = 4");
        assertThat(physics.held).isNull();

        frames(3);
        assertThat(clock.time() - revealed).isBetween(0.89, 0.92);
        assertThat(underWay()).isTrue();
        assertThat(queued()).isEmpty();
        assertThat(queuedChanges()).containsExactly(Optional.of("1d6+1"), Optional.empty());
        physics.finish();
        playToRest(controller);
        assertThat(status().text()).isEqualTo("1d6+1 = 7");
        assertThat(tray.chains).hasSize(2);
    }

    @Test
    void aLaterRollReplacesTheQueuedRoll() {
        RollController controller = controller(2, 5);
        controller.roll(valid("1d6"));
        controller.roll(valid("2d6"));
        controller.roll(valid("1d20+3"));
        physics.finish();

        assertThat(queued()).contains("1d20+3");
        playToRest(controller);
        // Asked for during the beat, it replaces the Queued Roll rather than jumping ahead of it.
        frames(20);
        controller.roll(valid("1d12"));
        assertThat(queued()).contains("1d12");
        assertThat(physics.held).isNull();
        frames(34);
        physics.finish();
        playToRest(controller);

        assertThat(status().text()).isEqualTo("1d12 = 5");
        assertThat(tray.chains).hasSize(2);
    }

    @Test
    void cancellingTheQueuedRollDropsItAndSaysWhetherThereWasOne() {
        RollController controller = controller(4, 2);
        controller.roll(valid("1d6"));
        assertThat(controller.cancelQueued()).isFalse();
        controller.roll(valid("2d6"));

        assertThat(controller.cancelQueued()).isTrue();

        assertThat(queued()).isEmpty();
        assertThat(queuedChanges()).containsExactly(Optional.of("2d6"), Optional.empty());
        assertThat(controller.cancelQueued()).isFalse();
        // The Roll under way plays on, and nothing follows it.
        assertThat(underWay()).isTrue();
        physics.finish();
        playToRest(controller);
        frames(120);
        assertThat(tray.chains).hasSize(1);
        assertThat(physics.held).isNull();
        assertThat(status().text()).isEqualTo("1d6 = 4");
    }

    @Test
    void aFailedRollStartsTheBeatFromItsFailureWithItsMessageShownThroughIt() {
        simulator.then(24, Ending.TIMED_OUT);
        RollController controller = controller(4, 3);
        controller.roll(valid("1d6"));
        controller.roll(valid("1d4"));

        // The first Roll fails in pre-simulation, before anything moved.
        physics.finish();
        assertThat(status().text()).isEqualTo(WOULDNT_SETTLE);
        assertThat(underWay()).isFalse();

        frames(51);
        assertThat(status().text()).isEqualTo(WOULDNT_SETTLE);
        assertThat(queued()).contains("1d4");
        frames(3);
        assertThat(status()).isInstanceOf(RollStatus.PreSimulating.class);
        assertThat(queued()).isEmpty();
        physics.finish();
        playToRest(controller);
        assertThat(status().text()).isEqualTo("1d4 = 3");
    }

    @Test
    void aQueuedRollThatFailsShowsItsOwnFailedRollMessage() {
        RollController controller = controller(4, 2, 5);
        controller.roll(valid("1d6"));
        physics.finish();
        // The first Roll is already simulated; every Attempt of the Queued Roll's Throw times out.
        simulator.then(24, Ending.TIMED_OUT);
        controller.roll(valid("2d6"));
        playToRest(controller);
        frames(54);
        physics.finish();

        assertThat(status().text()).isEqualTo(WOULDNT_SETTLE);
        assertThat(queued()).isEmpty();
        assertThat(underWay()).isFalse();
        // Nothing moved: the Tray keeps the first Roll's Dice.
        assertThat(tray.chains).hasSize(1);
    }

    @Test
    void aQueuedRollFromASavedRollKeepsItsNameWhenItStarts() {
        RollController controller = controller(4, 1, 2, 3, 4, 5, 6, 1, 2);
        controller.roll(valid("1d6"));
        controller.roll(valid("8d6"), Optional.of("Fireball"));
        physics.finish();
        playToRest(controller);
        frames(54);
        physics.finish();
        playToRest(controller);

        RollStatus.Rested rested = (RollStatus.Rested) status();
        assertThat(rested.text()).startsWith("8d6 = ");
        assertThat(rested.savedRollName()).contains("Fireball");
    }

    @Test
    void aQueuedRollReplacedByATypedOneLosesTheSavedRollsName() {
        RollController controller = controller(4, 3);
        controller.roll(valid("1d6"));
        controller.roll(valid("8d6"), Optional.of("Fireball"));
        controller.roll(valid("1d4"));
        physics.finish();
        playToRest(controller);
        frames(54);
        physics.finish();
        playToRest(controller);

        RollStatus.Rested rested = (RollStatus.Rested) status();
        assertThat(rested.text()).isEqualTo("1d4 = 3");
        assertThat(rested.savedRollName()).isEmpty();
    }

    @Test
    void fastForwardShortensTheBeatWithTheRestOfPlayback() {
        RollController controller = controller(4, 6);
        controller.roll(valid("1d6"));
        physics.finish();
        controller.roll(valid("1d6+1"));
        playToRest(controller);

        // ×4: the 0.9 s beat passes in a quarter of the real time, 14 frames rather than 54.
        clock.setFastForwardFactor(FastForward.SPEED);
        frames(13);
        assertThat(underWay()).isFalse();
        assertThat(queued()).contains("1d6+1");
        frames(1);
        assertThat(underWay()).isTrue();
        assertThat(queued()).isEmpty();
    }

    @Test
    void onTheAdvanceTheBeatEndsTheQueuedRollIsShownAtItsFirstReleaseAndTheClockKeepsRunning() {
        RollController controller = controller(4, 6);
        controller.roll(valid("1d6"));
        physics.finish();
        controller.roll(valid("1d6+1"));
        playToRest(controller);
        frames(53);
        // The worker answers at once, so the Queued Roll's chain arrives inside the advance that starts it.
        physics.setHolding(false);

        frames(1);

        assertThat(tray.chains).hasSize(2);
        // Drawn first and started after: the new chain is shown at its first Release and not redrawn that advance.
        assertThat(tray.times).containsExactly(0.0);
        assertThat(pulses.running).isTrue();
    }

    // JavaFX's pulse: asked for only while something needs the clock (spec §3.2; #46).

    @Test
    void thePulseStartsWhenTheChainStartsToPlayAndStopsOnceTheDiceRest() {
        RollController controller = controller(4);
        controller.roll(valid("1d6"));
        assertThat(pulses.running).as("pre-simulating: nothing moves yet").isFalse();

        physics.finish();
        assertThat(pulses.running).isTrue();
        assertThat(pulses.starts).hasValue(1);

        playToRest(controller);
        assertThat(pulses.running).isFalse();
        assertThat(pulses.starts).hasValue(1);
    }

    @Test
    void thePulseRunsOnWhileTheTrayIsStillMovingAfterTheRollIsOverThenStops() {
        RollController controller = controller(4);
        controller.roll(valid("1d6"));
        physics.finish();
        // The camera's settle-in on the Dice at rest outlasts the last Throw.
        tray.moving = true;
        playToRest(controller);
        assertThat(status()).isInstanceOf(RollStatus.Rested.class);
        assertThat(pulses.running).isTrue();

        frames(10);
        assertThat(pulses.running).isTrue();
        tray.moving = false;
        frames(1);
        assertThat(pulses.running).isFalse();
    }

    @Test
    void thePulseRunsThroughTheBeatWithNothingMovingThenStopsOnceTheQueuedRollRests() {
        RollController controller = controller(4, 6);
        controller.roll(valid("1d6"));
        physics.finish();
        controller.roll(valid("1d6+1"));
        playToRest(controller);

        for (int i = 0; i < 53; i++) {
            assertThat(pulses.running).as("frame %d of the beat", i).isTrue();
            frames(1);
        }
        frames(1);
        // The Queued Roll pre-simulates: the clock goes on running.
        assertThat(pulses.running).isTrue();
        physics.finish();
        playToRest(controller);
        assertThat(pulses.running).isFalse();
    }

    @Test
    void aRollThatFailsBeforeAnythingMovedWithNothingQueuedNeverAsksForThePulse() {
        simulator.failNext();
        RollController controller = controller(4);
        controller.roll(valid("1d6"));
        physics.finish();

        assertThat(status()).isInstanceOf(RollStatus.Failed.class);
        assertThat(pulses.starts).hasValue(0);
    }

    @Test
    void aRollThatFailsWithARollQueuedAsksForThePulseForTheBeat() {
        simulator.then(24, Ending.TIMED_OUT);
        RollController controller = controller(4, 3);
        controller.roll(valid("1d6"));
        controller.roll(valid("1d4"));
        physics.finish();

        // Nothing has moved, so nothing else would advance the clock the beat is timed on.
        assertThat(pulses.running).isTrue();
    }

    @Test
    void cancellingTheQueuedRollDuringTheBeatLetsThePulseStop() {
        RollController controller = controller(4, 6);
        controller.roll(valid("1d6"));
        physics.finish();
        controller.roll(valid("1d6+1"));
        playToRest(controller);
        assertThat(pulses.running).isTrue();

        controller.cancelQueued();

        assertThat(pulses.running).isFalse();
    }

    // Abandon: the session has failed (spec §3.2 "Runtime failure"). A Roll it cuts off is a Lost Roll (ADR-0007).

    @Test
    void abandoningARollInMotionLosesItWhereItIsNeverRevealedAndLaterTicksDrawNothing() {
        RollController controller = controller(4);
        controller.roll(valid("1d6"));
        physics.finish();
        frames(3);
        int drawn = tray.times.size();

        controller.abandon();

        RollStatus.Lost lost = (RollStatus.Lost) status();
        assertThat(lost.expression().source()).isEqualTo("1d6");
        // The strip shows nothing for it, so it no longer says "Rolling…".
        assertThat(lost.text()).isEmpty();
        // Nothing is left under way, and the status agrees with settled.
        assertThat(underWay()).isFalse();
        assertThat(latest().settled()).isTrue();
        assertThat(pulses.running).isFalse();
        // Far past the chain's rest: the Dice stay where they stopped, and nothing is revealed.
        frames(1800);
        assertThat(tray.times).hasSize(drawn);
        assertThat(status()).isSameAs(lost);
        assertThat(statuses()).noneMatch(RollStatus.Rested.class::isInstance);
        assertThat(tray.chains).hasSize(1);
    }

    @Test
    void abandoningWithARollQueuedDropsItAndNothingMoreRolls() {
        RollController controller = controller(4, 2, 3);
        controller.roll(valid("1d6"));
        physics.finish();
        controller.roll(valid("1d8"));
        frames(3);

        controller.abandon();

        assertThat(status()).isInstanceOf(RollStatus.Lost.class);
        assertThat(queued()).isEmpty();
        assertThat(queuedChanges()).containsExactly(Optional.of("1d8"), Optional.empty());
        // Nothing is left under way: the app has settled.
        assertThat(latest().settled()).isTrue();
        assertThat(pulses.running).isFalse();
        // Far past the chain's rest and the beat after it: nothing plays on, and nothing starts.
        frames(1800);
        assertThat(statuses()).noneMatch(RollStatus.Rested.class::isInstance);
        assertThat(tray.chains).hasSize(1);
        // Nor can one be asked for, nor queued.
        controller.roll(valid("1d4"));
        assertThat(physics.held).isNull();
        assertThat(queued()).isEmpty();
    }

    @Test
    void abandoningTwiceDoesNothingTheSecondTime() {
        RollController controller = controller(4, 2);
        controller.roll(valid("1d6"));
        physics.finish();
        controller.roll(valid("1d8"));
        controller.abandon();
        RollState after = latest();
        int before = published.size();

        controller.abandon();

        assertThat(published).hasSize(before);
        assertThat(controller.state()).isSameAs(after);
        assertThat(queuedChanges()).hasSize(2);
    }

    @Test
    void aRollStillPreSimulatingWhenTheSessionIsAbandonedIsLostAndNeverPlays() {
        RollController controller = controller(4);
        controller.roll(valid("1d6"));

        controller.abandon();

        RollStatus.Lost lost = (RollStatus.Lost) status();
        assertThat(lost.expression().source()).isEqualTo("1d6");
        assertThat(latest().settled()).isTrue();
        // The plan arrives after the session has ended.
        physics.finish();
        frames(600);

        assertThat(tray.chains).isEmpty();
        assertThat(status()).isSameAs(lost);
        assertThat(statuses()).noneMatch(RollStatus.Playing.class::isInstance);
        assertThat(pulses.starts).hasValue(0);
    }

    // The mid-Roll Look rule (spec §3.2 "Look tab"): a Tray picked while Dice move appears once they rest.

    private static final Tray FELT = BuiltInTrays.defaultTray();
    private static final Tray HEXAGON = BuiltInTrays.find("02-leather-hexagon").orElseThrow();
    private static final Tray WALNUT = BuiltInTrays.find("03-walnut-board").orElseThrow();
    private static final DiceSet ONYX = DiceSetCatalogue.builtIn().find("01-onyx").orElseThrow();
    private static final DiceSet RUBY = DiceSetCatalogue.builtIn().find("12-ruby").orElseThrow();

    @Test
    void aLookPickedWithNoRollUnderWayIsShownWholeAtOnceAndTheNextRollIsThrownIntoItsTray() {
        RollController controller = controller(4);

        controller.setLook(new Look(RUBY, HEXAGON, LightRig.LAMP));

        assertThat(tray.looks).containsExactly(new Look(RUBY, HEXAGON, LightRig.LAMP));
        controller.roll(valid("1d6"));
        physics.finish();
        assertThat(simulator.requests).isNotEmpty()
                .allSatisfy(request -> assertThat(request.tray()).isEqualTo(HEXAGON.geometry()));
        assertThat(tray.chains.getFirst().tray()).isEqualTo(HEXAGON);
    }

    @Test
    void aTrayPickedWhileTheRollPlaysIsShownOnceItRests() {
        RollController controller = controller(4);
        controller.setLook(new Look(ONYX, FELT, LightRig.STUDIO));
        controller.roll(valid("1d6"));
        physics.finish();
        frames(3);

        controller.setLook(new Look(ONYX, WALNUT, LightRig.STUDIO));

        // The Roll in motion stays in the Tray it was thrown into.
        assertThat(tray.looks.getLast().tray()).isEqualTo(FELT);
        while (underWay()) {
            assertThat(tray.looks.getLast().tray()).as("at %.3f s", clock.time()).isEqualTo(FELT);
            clock.advance(FRAME);
        }
        // Shown by the time the Roll Result is.
        assertThat(status()).isInstanceOf(RollStatus.Rested.class);
        assertThat(tray.looks.getLast()).isEqualTo(new Look(ONYX, WALNUT, LightRig.STUDIO));
    }

    @Test
    void aDiceSetOrLightRigPickedWhileTheRollPlaysIsShownAtOnce() {
        RollController controller = controller(4);
        controller.setLook(new Look(ONYX, FELT, LightRig.STUDIO));
        controller.roll(valid("1d6"));
        physics.finish();
        frames(3);

        controller.setLook(new Look(RUBY, FELT, LightRig.STUDIO));
        assertThat(tray.looks.getLast()).isEqualTo(new Look(RUBY, FELT, LightRig.STUDIO));
        controller.setLook(new Look(RUBY, FELT, LightRig.WINDOW));
        assertThat(tray.looks.getLast()).isEqualTo(new Look(RUBY, FELT, LightRig.WINDOW));

        // Picked with a Tray, they still show at once; only the Tray waits for the Dice to rest.
        controller.setLook(new Look(ONYX, HEXAGON, LightRig.LAMP));
        assertThat(tray.looks.getLast()).isEqualTo(new Look(ONYX, FELT, LightRig.LAMP));
        assertThat(status()).isInstanceOf(RollStatus.Playing.class);
    }

    @Test
    void aTrayPickedWhileTheRollPreSimulatesWaitsForThatRollToRestToo() {
        RollController controller = controller(4);
        controller.setLook(new Look(ONYX, FELT, LightRig.STUDIO));
        controller.roll(valid("1d6"));

        controller.setLook(new Look(ONYX, HEXAGON, LightRig.STUDIO));

        assertThat(status()).isInstanceOf(RollStatus.PreSimulating.class);
        assertThat(tray.looks.getLast().tray()).isEqualTo(FELT);
        physics.finish();
        // It was simulated in the felt Tray, and is drawn and heard in it.
        assertThat(simulator.requests).allSatisfy(request -> assertThat(request.tray()).isEqualTo(FELT.geometry()));
        assertThat(tray.chains.getFirst().tray()).isEqualTo(FELT);
        assertThat(tray.looks.getLast().tray()).isEqualTo(FELT);
        playToRest(controller);
        assertThat(tray.looks.getLast().tray()).isEqualTo(HEXAGON);
    }

    @Test
    void ofTwoTraysPickedInTurnWhileTheDiceMoveTheLastIsShownAtRestAndTheNextRollIsThrownIntoIt() {
        RollController controller = controller(4, 2);
        controller.setLook(new Look(ONYX, FELT, LightRig.STUDIO));
        controller.roll(valid("1d6"));
        physics.finish();
        frames(3);

        controller.setLook(new Look(ONYX, WALNUT, LightRig.STUDIO));
        frames(3);
        controller.setLook(new Look(ONYX, HEXAGON, LightRig.STUDIO));
        playToRest(controller);

        assertThat(tray.looks.getLast()).isEqualTo(new Look(ONYX, HEXAGON, LightRig.STUDIO));
        assertThat(tray.looks).extracting(Look::tray).doesNotContain(WALNUT);
        simulator.requests.clear();
        controller.roll(valid("1d6"));
        physics.finish();
        assertThat(simulator.requests).isNotEmpty()
                .allSatisfy(request -> assertThat(request.tray()).isEqualTo(HEXAGON.geometry()));
        assertThat(tray.chains.getLast().tray()).isEqualTo(HEXAGON);
    }

    @Test
    void aQueuedRollIsThrownIntoTheTrayPickedWhileTheRollAheadOfItPlayed() {
        RollController controller = controller(4, 2);
        controller.setLook(new Look(ONYX, FELT, LightRig.STUDIO));
        controller.roll(valid("1d6"));
        physics.finish();
        controller.roll(valid("1d4"));
        controller.setLook(new Look(ONYX, WALNUT, LightRig.STUDIO));
        playToRest(controller);
        simulator.requests.clear();

        frames(54);
        physics.finish();

        assertThat(simulator.requests).isNotEmpty()
                .allSatisfy(request -> assertThat(request.tray()).isEqualTo(WALNUT.geometry()));
        assertThat(tray.chains.getLast().tray()).isEqualTo(WALNUT);
        assertThat(tray.looks.getLast().tray()).isEqualTo(WALNUT);
    }

    @Test
    void aTrayPickedWhileTheRollFailsIsShownOnceTheFailedRollsMessageShows() {
        RollController controller = controller(4, 2);
        controller.setLook(new Look(ONYX, FELT, LightRig.STUDIO));
        // Every Attempt of the Roll's first Throw times out.
        simulator.then(24, Ending.TIMED_OUT);
        controller.roll(valid("2d6"));
        // Told the Tray at once, the moment the Failed Roll's message is published.
        List<Tray> drawnAsItFailed = new ArrayList<>();
        controller.addStateListener(RollState.statusChanges(status -> {
            if (status instanceof RollStatus.Failed) {
                drawnAsItFailed.add(tray.looks.getLast().tray());
            }
        }));

        controller.setLook(new Look(ONYX, WALNUT, LightRig.STUDIO));
        assertThat(tray.looks.getLast().tray()).isEqualTo(FELT);
        physics.finish();

        assertThat(status().text()).isEqualTo(WOULDNT_SETTLE);
        assertThat(drawnAsItFailed).containsExactly(WALNUT);
        assertThat(tray.looks.getLast()).isEqualTo(new Look(ONYX, WALNUT, LightRig.STUDIO));
    }

    @Test
    void aTrayPickedWhileTheRollPlaysIsShownWhenTheSessionIsAbandoned() {
        RollController controller = controller(4);
        controller.setLook(new Look(ONYX, FELT, LightRig.STUDIO));
        controller.roll(valid("1d6"));
        physics.finish();
        frames(3);
        controller.setLook(new Look(ONYX, WALNUT, LightRig.STUDIO));
        assertThat(tray.looks.getLast().tray()).isEqualTo(FELT);

        controller.abandon();

        assertThat(tray.looks.getLast()).isEqualTo(new Look(ONYX, WALNUT, LightRig.STUDIO));
    }

    @Test
    void abandoningBeforeAnyRollLeavesTheTrayReadyAndNothingRollsAfter() {
        RollController controller = controller(4);

        controller.abandon();

        // No Roll was cut off, so none is lost.
        assertThat(controller.state().status()).isInstanceOf(RollStatus.Ready.class);
        assertThat(controller.state().settled()).isTrue();
        controller.roll(valid("1d6"));
        assertThat(physics.held).isNull();
        assertThat(controller.state().status()).isInstanceOf(RollStatus.Ready.class);
        assertThat(tray.chains).isEmpty();
        assertThat(pulses.starts).hasValue(0);
    }

    @Test
    void abandoningARollAtRestKeepsItsResultAndNothingRollsAfter() {
        RollController controller = controller(4, 2);
        controller.roll(valid("1d6"));
        physics.finish();
        playToRest(controller);
        RollStatus rested = status();
        assertThat(rested).isInstanceOf(RollStatus.Rested.class);

        controller.abandon();

        // It was revealed before the session ended, so it is not lost.
        assertThat(controller.state().status()).isSameAs(rested);
        controller.roll(valid("1d8"));
        assertThat(physics.held).isNull();
        assertThat(controller.state().status()).isSameAs(rested);
        assertThat(tray.chains).hasSize(1);
    }

    @Test
    void abandoningAfterAFailedRollKeepsItsMessageAndNothingRollsAfter() {
        simulator.then(24, Ending.TIMED_OUT);
        RollController controller = controller(4, 2);
        controller.roll(valid("1d6"));
        physics.finish();
        RollStatus failed = status();
        assertThat(failed).isInstanceOf(RollStatus.Failed.class);

        controller.abandon();

        assertThat(controller.state().status()).isSameAs(failed);
        controller.roll(valid("1d8"));
        assertThat(physics.held).isNull();
        assertThat(controller.state().status()).isSameAs(failed);
    }

    @Test
    void abandoningWhileTheQueuedRollWaitsOutItsBeatDropsItAndItNeverStarts() {
        RollController controller = controller(4, 2);
        controller.roll(valid("1d6"));
        physics.finish();
        controller.roll(valid("1d8"));
        playToRest(controller);
        RollStatus rested = status();
        assertThat(rested).isInstanceOf(RollStatus.Rested.class);
        assertThat(queued()).contains("1d8");

        controller.abandon();

        assertThat(queued()).isEmpty();
        assertThat(status()).isSameAs(rested);
        assertThat(latest().settled()).isTrue();
        assertThat(pulses.running).isFalse();
        // Far past the beat: the Queued Roll never starts.
        frames(600);
        assertThat(physics.held).isNull();
        assertThat(status()).isSameAs(rested);
        assertThat(tray.chains).hasSize(1);
    }

    /** A Tray that remembers what it was asked to show, and reports itself moving when told to. */
    private static final class RecordingTray implements RollController.TrayView {
        final List<ChainPlayback> chains = new ArrayList<>();
        final List<Double> times = new ArrayList<>();
        /** Every look it was shown, oldest first. */
        final List<Look> looks = new ArrayList<>();
        /** What {@link #moving()} reports: set it to stand in for the camera's settle-in. */
        boolean moving;

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

        @Override
        public boolean moving() {
            return moving;
        }

        @Override
        public void showLook(Look look) {
            looks.add(look);
        }
    }
}
