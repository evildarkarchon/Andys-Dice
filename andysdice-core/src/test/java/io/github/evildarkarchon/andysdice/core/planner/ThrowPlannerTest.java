package io.github.evildarkarchon.andysdice.core.planner;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.evildarkarchon.andysdice.core.expression.FaceValueChain;
import io.github.evildarkarchon.andysdice.core.expression.Span;
import io.github.evildarkarchon.andysdice.core.facevalue.FaceValueGenerator;
import io.github.evildarkarchon.andysdice.core.geometry.DieKind;
import io.github.evildarkarchon.andysdice.core.geometry.NumberingRotation;
import io.github.evildarkarchon.andysdice.core.geometry.UpFace;
import io.github.evildarkarchon.andysdice.core.simulation.ContactMaterial;
import io.github.evildarkarchon.andysdice.core.simulation.AttemptRequest;
import io.github.evildarkarchon.andysdice.core.simulation.DieBody;
import io.github.evildarkarchon.andysdice.core.simulation.DieKindBody;
import io.github.evildarkarchon.andysdice.core.simulation.Ending;
import io.github.evildarkarchon.andysdice.core.simulation.FakeSimulator;
import io.github.evildarkarchon.andysdice.core.simulation.FrozenDie;
import io.github.evildarkarchon.andysdice.core.simulation.Orientation;
import io.github.evildarkarchon.andysdice.core.simulation.Release;
import io.github.evildarkarchon.andysdice.core.simulation.Simulator;
import io.github.evildarkarchon.andysdice.core.simulation.TrayGeometry;
import io.github.evildarkarchon.andysdice.core.simulation.TrayMaterials;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import org.joml.Quaterniond;
import org.junit.jupiter.api.Test;

/**
 * The Roll pipeline (spec §3.1: parse, choose, pre-simulate) over the scripted fake simulator: every rule of
 * ADR-0001, ADR-0006 and ADR-0013 about how a Roll's chain of Throws is planned.
 */
class ThrowPlannerTest {

    private static final TrayGeometry BOX = TrayGeometry.rectangle(15, 10, 3);
    private static final TrayMaterials FELT =
            TrayMaterials.of(new ContactMaterial(0.45f, 0.15f), new ContactMaterial(0.45f, 0.5f), 0.6f);

    private static ThrowPlanner planner(Simulator simulator) {
        return new ThrowPlanner(simulator, BOX, FELT);
    }

    /** The Die Kind of each body in a list the planner handed the simulator. */
    private static List<DieKind> kinds(List<DieBody> bodies) {
        return bodies.stream().map(body -> ((DieKindBody) body).kind()).toList();
    }

    private static FaceValueGenerator faceValues(ScriptedRandom values) {
        return new FaceValueGenerator(values);
    }

    private static RollPlan.Planned planned(RollPlan plan) {
        assertThat(plan).isInstanceOf(RollPlan.Planned.class);
        return (RollPlan.Planned) plan;
    }

    /** The Numeral a thrown Die shows at the end of a Throw's Recording, drawn turned by its rotation. */
    private static String numeralShown(RollPlan.Planned roll, PlannedThrow step, int slot) {
        int die = step.thrown().get(slot);
        DieKind kind = roll.dice().get(die).kind();
        Orientation o = step.recording().finalPose(slot).orientation();
        NumberingRotation rotation = step.rotations().get(slot);
        Quaterniond drawn = rotation.applyTo(new Quaterniond(o.x(), o.y(), o.z(), o.w()), new Quaterniond());
        return UpFace.read(kind, drawn).numeral();
    }

    @Test
    void aRollWithoutRerollsIsOneFirstThrowWhoseDiceShowTheirChosenValues() {
        FakeSimulator fake = new FakeSimulator();

        RollPlan.Planned roll = planned(planner(fake).plan("2d6+1d8+3", faceValues(new ScriptedRandom(4, 6, 7)),
                new SplittableRandom(1)));

        assertThat(roll.chains()).containsExactly(FaceValueChain.of(4), FaceValueChain.of(6), FaceValueChain.of(7));
        assertThat(roll.result().total()).isEqualTo(20);
        assertThat(roll.dice()).extracting(PlannedDie::kind).containsExactly(DieKind.D6, DieKind.D6, DieKind.D8);
        assertThat(roll.throwChain()).hasSize(1);
        PlannedThrow first = roll.throwChain().getFirst();
        assertThat(first.kind()).isEqualTo(ThrowKind.FIRST);
        assertThat(first.thrown()).containsExactly(0, 1, 2);
        assertThat(first.frozen()).isEmpty();
        assertThat(first.attempts()).isEqualTo(1);
        assertThat(List.of(numeralShown(roll, first, 0), numeralShown(roll, first, 1), numeralShown(roll, first, 2)))
                .containsExactly("4", "6", "7");
        assertThat(fake.requests()).hasSize(1);
    }

    @Test
    void anInvalidRollExpressionChoosesAndSimulatesNothing() {
        FakeSimulator fake = new FakeSimulator();
        ScriptedRandom values = new ScriptedRandom(3);

        RollPlan plan = planner(fake).plan("2d6+1d7", faceValues(values), new SplittableRandom(1));

        assertThat(plan).isInstanceOf(RollPlan.Invalid.class);
        assertThat(((RollPlan.Invalid) plan).error().span()).isEqualTo(new Span(5, 7));
        assertThat(values.draws()).isZero();
        assertThat(fake.calls()).isEmpty();
    }

    @Test
    void aPercentileRollIsTwoDiceThatReadTensAndUnits() {
        RollPlan.Planned roll = planned(planner(new FakeSimulator()).plan("2d%",
                faceValues(new ScriptedRandom(100, 37)), new SplittableRandom(2)));

        assertThat(roll.dice()).containsExactly(new PlannedDie(DieKind.PERCENTILE_D10, 0),
                new PlannedDie(DieKind.D10, 0), new PlannedDie(DieKind.PERCENTILE_D10, 1),
                new PlannedDie(DieKind.D10, 1));
        PlannedThrow first = roll.throwChain().getFirst();
        assertThat(List.of(numeralShown(roll, first, 0), numeralShown(roll, first, 1), numeralShown(roll, first, 2),
                numeralShown(roll, first, 3))).containsExactly("00", "0", "30", "7");
        assertThat(roll.result().total()).isEqualTo(137);
    }

    @Test
    void escapesAndTimeOutsAreSimulatedAgainSilentlyUpToTheLastAttempt() {
        FakeSimulator fake = new FakeSimulator().then(10, Ending.ESCAPED).then(13, Ending.TIMED_OUT);

        RollPlan.Planned roll = planned(planner(fake).plan("3d6", faceValues(new ScriptedRandom(1, 2, 3)),
                new SplittableRandom(3)));

        assertThat(ThrowPlanner.ATTEMPTS_PER_THROW).isEqualTo(24);
        assertThat(fake.calls()).hasSize(24);
        assertThat(roll.throwChain()).singleElement().satisfies(only -> {
            assertThat(only.kind()).isEqualTo(ThrowKind.FIRST);
            assertThat(only.attempts()).isEqualTo(24);
            assertThat(only.recording()).isSameAs(fake.calls().getLast().attempt().recording());
            assertThat(only.cocked()).isEmpty();
        });
        assertThat(fake.requests()).allSatisfy(request -> {
            assertThat(request.dice()).hasSize(3);
            assertThat(request.release()).isInstanceOf(Release.Drawn.class);
        });
    }

    @Test
    void aThrowWithNoAttemptAtRestIsAFailedRollThatWouldNotSettle() {
        FakeSimulator fake = new FakeSimulator().then(12, Ending.ESCAPED).then(12, Ending.TIMED_OUT);

        RollPlan plan = planner(fake).plan("1d20", faceValues(new ScriptedRandom(20)), new SplittableRandom(4));

        assertThat(plan).isInstanceOfSatisfying(RollPlan.Failed.class,
                failed -> assertThat(failed.reason()).isEqualTo(FailureReason.WOULDNT_SETTLE));
        assertThat(fake.calls()).hasSize(ThrowPlanner.ATTEMPTS_PER_THROW);
    }

    @Test
    void theCapsAreTheSpecsContracts() {
        assertThat(ThrowPlanner.ATTEMPTS_PER_THROW).isEqualTo(24);
        assertThat(ThrowPlanner.COCKED_RETHROWS_PER_THROW).isEqualTo(3);
        assertThat(ThrowPlanner.MAX_REROLL_ROUNDS).isEqualTo(100);
    }

    @Test
    void afterTwentyFourCockedAttemptsTheFewestCockedIsKeptAndOnlyItsCockedDiceAreRethrown() {
        FakeSimulator fake = new FakeSimulator()
                .thenCocked(5, 0, 1, 2)
                .thenCocked(1, 1, 3) // the 6th Attempt: the first with the fewest Cocked Dice
                .thenCocked(1, 0, 2) // as few, but later
                .then(4, Ending.ESCAPED)
                .thenCocked(13, 0, 1, 2, 3);

        RollPlan.Planned roll = planned(planner(fake).plan("4d6",
                faceValues(new ScriptedRandom(1, 2, 3, 4)), new SplittableRandom(5)));

        assertThat(fake.calls()).hasSize(25);
        assertThat(roll.throwChain()).hasSize(2);
        PlannedThrow first = roll.throwChain().get(0);
        assertThat(first.kind()).isEqualTo(ThrowKind.FIRST);
        assertThat(first.attempts()).isEqualTo(24);
        assertThat(first.recording()).isSameAs(fake.calls().get(5).attempt().recording());
        assertThat(first.cocked()).containsExactlyInAnyOrder(1, 3);
        assertThat(List.of(numeralShown(roll, first, 0), numeralShown(roll, first, 2))).containsExactly("1", "3");

        PlannedThrow rethrow = roll.throwChain().get(1);
        assertThat(rethrow.kind()).isEqualTo(ThrowKind.COCKED_RETHROW);
        assertThat(rethrow.thrown()).containsExactly(1, 3);
        assertThat(rethrow.frozen()).containsExactly(0, 2);
        assertThat(rethrow.attempts()).isEqualTo(1);
        assertThat(rethrow.cocked()).isEmpty();
        assertThat(List.of(numeralShown(roll, rethrow, 0), numeralShown(roll, rethrow, 1))).containsExactly("2", "4");

        AttemptRequest request = fake.requests().getLast();
        assertThat(request.dice()).hasSize(2);
        assertThat(request.frozen()).extracting(FrozenDie::pose).containsExactly(
                first.recording().finalPose(0), first.recording().finalPose(2));

        // A Cocked Rethrow replaces no Face Value: no chain gains a step.
        assertThat(roll.chains()).containsExactly(FaceValueChain.of(1), FaceValueChain.of(2), FaceValueChain.of(3),
                FaceValueChain.of(4));
        assertThat(roll.result().total()).isEqualTo(10);
        assertThat(roll.cockedRethrows()).isEqualTo(1);
    }

    @Test
    void aThrowMayNeedThreeCockedRethrows() {
        FakeSimulator fake = new FakeSimulator().then(3 * 24, Ending.COCKED);

        RollPlan.Planned roll = planned(planner(fake).plan("2d8", faceValues(new ScriptedRandom(8, 5)),
                new SplittableRandom(6)));

        assertThat(roll.throwChain()).extracting(PlannedThrow::kind).containsExactly(ThrowKind.FIRST,
                ThrowKind.COCKED_RETHROW, ThrowKind.COCKED_RETHROW, ThrowKind.COCKED_RETHROW);
        assertThat(roll.cockedRethrows()).isEqualTo(ThrowPlanner.COCKED_RETHROWS_PER_THROW);
        assertThat(roll.throwChain().getLast().thrown()).containsExactly(0);
        assertThat(roll.throwChain().getLast().frozen()).containsExactly(1);
        assertThat(numeralShown(roll, roll.throwChain().getLast(), 0)).isEqualTo("8");
        assertThat(roll.chains()).containsExactly(FaceValueChain.of(8), FaceValueChain.of(5));
    }

    @Test
    void aFourthFailingCockedRethrowIsAFailedRollThatWouldNotSettle() {
        FakeSimulator fake = new FakeSimulator().then(4 * 24, Ending.COCKED);

        RollPlan plan = planner(fake).plan("2d8", faceValues(new ScriptedRandom(8, 5)), new SplittableRandom(6));

        assertThat(plan).isInstanceOfSatisfying(RollPlan.Failed.class,
                failed -> assertThat(failed.reason()).isEqualTo(FailureReason.WOULDNT_SETTLE));
        assertThat(fake.calls()).hasSize(4 * 24);
        assertThat(fake.scriptLeft()).isZero();
    }

    @Test
    void aCockedRethrowWithNoAttemptAtRestIsAFailedRoll() {
        FakeSimulator fake = new FakeSimulator().then(24, Ending.COCKED).then(24, Ending.ESCAPED);

        RollPlan plan = planner(fake).plan("2d8", faceValues(new ScriptedRandom(8, 5)), new SplittableRandom(6));

        assertThat(plan).isInstanceOfSatisfying(RollPlan.Failed.class,
                failed -> assertThat(failed.reason()).isEqualTo(FailureReason.WOULDNT_SETTLE));
        assertThat(fake.calls()).hasSize(48);
    }

    @Test
    void allTheDiceRerolledInARoundGoTogetherWithTheRestFrozenWhereTheyLay() {
        FakeSimulator fake = new FakeSimulator();
        // First Throw 1, 5, 2; round 1 rerolls the d6 (1) and the d8 (2 < 3): 1 and 7; round 2 the d6 again: 4.
        ScriptedRandom values = new ScriptedRandom(1, 5, 2, 1, 7, 4);

        RollPlan.Planned roll = planned(planner(fake).plan("2d6rr1+1d8ro<3", faceValues(values),
                new SplittableRandom(7)));

        assertThat(roll.chains()).containsExactly(FaceValueChain.of(1, 1, 4), FaceValueChain.of(5),
                FaceValueChain.of(2, 7));
        assertThat(roll.result().total()).isEqualTo(16);
        assertThat(roll.throwChain()).extracting(PlannedThrow::kind)
                .containsExactly(ThrowKind.FIRST, ThrowKind.REROLL, ThrowKind.REROLL);
        PlannedThrow first = roll.throwChain().get(0);
        PlannedThrow round1 = roll.throwChain().get(1);
        PlannedThrow round2 = roll.throwChain().get(2);
        assertThat(round1.thrown()).containsExactly(0, 2);
        assertThat(round1.frozen()).containsExactly(1);
        assertThat(round2.thrown()).containsExactly(0);
        assertThat(round2.frozen()).containsExactly(1, 2);
        assertThat(List.of(numeralShown(roll, first, 0), numeralShown(roll, round1, 0), numeralShown(roll, round2, 0)))
                .containsExactly("1", "1", "4");
        assertThat(numeralShown(roll, round1, 1)).isEqualTo("7");

        List<AttemptRequest> requests = fake.requests();
        assertThat(kinds(requests.get(1).dice())).containsExactly(DieKind.D6, DieKind.D8);
        assertThat(requests.get(1).frozen()).extracting(FrozenDie::pose)
                .containsExactly(first.recording().finalPose(1));
        assertThat(kinds(requests.get(2).frozen().stream().map(FrozenDie::body).toList()))
                .containsExactly(DieKind.D6, DieKind.D8);
        assertThat(requests.get(2).frozen()).extracting(FrozenDie::pose)
                .containsExactly(first.recording().finalPose(1), round1.recording().finalPose(1));
    }

    @Test
    void aCockedRethrowOfARerolledDieShowsItsRerolledValueAndAddsNoStep() {
        FakeSimulator fake = new FakeSimulator().then(1, Ending.AT_REST).then(24, Ending.COCKED);

        RollPlan.Planned roll = planned(planner(fake).plan("1d6rr1+1d6", faceValues(new ScriptedRandom(1, 3, 6)),
                new SplittableRandom(8)));

        assertThat(roll.throwChain()).extracting(PlannedThrow::kind)
                .containsExactly(ThrowKind.FIRST, ThrowKind.REROLL, ThrowKind.COCKED_RETHROW);
        PlannedThrow rethrow = roll.throwChain().getLast();
        assertThat(rethrow.thrown()).containsExactly(0);
        assertThat(rethrow.frozen()).containsExactly(1);
        assertThat(numeralShown(roll, rethrow, 0)).isEqualTo("6");
        assertThat(roll.chains()).containsExactly(FaceValueChain.of(1, 6), FaceValueChain.of(3));
    }

    @Test
    void bothDiceOfAPercentileRollAndBothAdvantageDiceAreRerolledTogether() {
        FakeSimulator fake = new FakeSimulator();
        RollPlan.Planned percentile = planned(planner(fake).plan("1d%ro<=10",
                faceValues(new ScriptedRandom(5, 70)), new SplittableRandom(9)));

        PlannedThrow reroll = percentile.throwChain().getLast();
        assertThat(reroll.kind()).isEqualTo(ThrowKind.REROLL);
        assertThat(reroll.thrown()).containsExactly(0, 1);
        assertThat(List.of(numeralShown(percentile, reroll, 0), numeralShown(percentile, reroll, 1)))
                .containsExactly("70", "0");

        RollPlan.Planned advantage = planned(planner(new FakeSimulator()).plan("1d20ro1 adv",
                faceValues(new ScriptedRandom(1, 1, 12, 9)), new SplittableRandom(9)));
        assertThat(advantage.throwChain()).hasSize(2);
        assertThat(advantage.throwChain().getLast().thrown()).containsExactly(0, 1);
        assertThat(advantage.result().total()).isEqualTo(12);
    }

    @Test
    void passingTheHundredRoundBackstopIsAFailedRollWithNothingSimulated() {
        FakeSimulator fake = new FakeSimulator();
        // The first Throw and 100 rounds all show 1, so a 101st round would be needed.
        ScriptedRandom values = new ScriptedRandom().thenRepeat(ThrowPlanner.MAX_REROLL_ROUNDS + 1, 1);

        RollPlan plan = planner(fake).plan("1d4rr1", faceValues(values), new SplittableRandom(10));

        assertThat(plan).isInstanceOfSatisfying(RollPlan.Failed.class,
                failed -> assertThat(failed.reason()).isEqualTo(FailureReason.TOO_MANY_REROLLS));
        assertThat(fake.calls()).isEmpty();
    }

    @Test
    void aHundredRoundsAreAllowedAndCockedRethrowsCountNothingTowardsTheBackstop() {
        // The first Throw's 24 Attempts end cocked, then its Cocked Rethrow and 99 Reroll Throws come to rest,
        // and the 100th Reroll Throw's 24 Attempts end cocked too.
        FakeSimulator fake = new FakeSimulator().then(24, Ending.COCKED)
                .then(ThrowPlanner.MAX_REROLL_ROUNDS, Ending.AT_REST)
                .then(24, Ending.COCKED);
        ScriptedRandom values = new ScriptedRandom().thenRepeat(ThrowPlanner.MAX_REROLL_ROUNDS, 1).then(3);

        RollPlan.Planned roll = planned(planner(fake).plan("1d4rr1", faceValues(values), new SplittableRandom(11)));

        assertThat(roll.chains().getFirst().values()).hasSize(ThrowPlanner.MAX_REROLL_ROUNDS + 1).endsWith(3);
        assertThat(roll.throwChain()).filteredOn(t -> t.kind() == ThrowKind.REROLL)
                .hasSize(ThrowPlanner.MAX_REROLL_ROUNDS);
        assertThat(roll.cockedRethrows()).isEqualTo(2);
        assertThat(numeralShown(roll, roll.throwChain().getLast(), 0)).isEqualTo("3");
    }

    @Test
    void everyFaceValueIsChosenBeforeAnySimulation() {
        FakeSimulator fake = new FakeSimulator().then(3, Ending.ESCAPED).then(24, Ending.COCKED);
        // First Throw 1, 1; round 1 rerolls both: 1, 3; round 2 rerolls the first: 5.
        ScriptedRandom values = new ScriptedRandom(1, 1, 1, 3, 5);
        List<Integer> drawsSeen = new ArrayList<>();
        Simulator watching = request -> {
            drawsSeen.add(values.draws());
            return fake.simulate(request);
        };

        RollPlan.Planned roll = planned(planner(watching).plan("2d6rr1", faceValues(values), new SplittableRandom(12)));

        assertThat(roll.chains()).containsExactly(FaceValueChain.of(1, 1, 5), FaceValueChain.of(1, 3));
        assertThat(drawsSeen).hasSize(fake.calls().size()).containsOnly(5);
    }

    @Test
    void aFixedSeedGivesTheSameFaceValuesHoweverManyRetriesHappen() {
        String expression = "4d6rr1kh3+1d20+2d%";
        RollPlan.Planned calm = planned(planner(new FakeSimulator()).plan(expression,
                new FaceValueGenerator(new SplittableRandom(42)), new SplittableRandom(99)));
        FakeSimulator stormy = new FakeSimulator().then(7, Ending.ESCAPED).then(5, Ending.TIMED_OUT)
                .then(24, Ending.COCKED).thenCocked(24, 0).then(2, Ending.ESCAPED);
        RollPlan.Planned retried = planned(planner(stormy).plan(expression,
                new FaceValueGenerator(new SplittableRandom(42)), new SplittableRandom(99)));

        assertThat(stormy.calls().size()).isGreaterThan(50);
        assertThat(retried.chains()).isEqualTo(calm.chains());
        assertThat(retried.result()).isEqualTo(calm.result());
    }

    @Test
    void theWholeChainIsSimulatedBeforeThePlanIsReturnedAndALateFailureFailsTheRoll() {
        FakeSimulator fake = new FakeSimulator().then(1, Ending.AT_REST).then(24, Ending.TIMED_OUT);

        RollPlan plan = planner(fake).plan("1d6rr1+1d8", faceValues(new ScriptedRandom(1, 4, 2)),
                new SplittableRandom(13));

        // The first Throw came to rest, but its Reroll Throw never did: nothing of the Roll is shown.
        assertThat(plan).isInstanceOfSatisfying(RollPlan.Failed.class,
                failed -> assertThat(failed.reason()).isEqualTo(FailureReason.WOULDNT_SETTLE));
        assertThat(fake.calls()).hasSize(25);

        FakeSimulator calm = new FakeSimulator().then(2, Ending.ESCAPED);
        RollPlan.Planned roll = planned(planner(calm).plan("1d6rr1+1d8", faceValues(new ScriptedRandom(1, 4, 2)),
                new SplittableRandom(13)));
        int simulated = calm.calls().size();
        assertThat(roll.throwChain()).extracting(PlannedThrow::attempts).containsExactly(3, 1);
        assertThat(simulated).isEqualTo(4);
        assertThat(roll.throwChain().getLast().cocked()).isEqualTo(Set.of());
    }
}
