package io.github.evildarkarchon.andysdice.core.planner;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.evildarkarchon.andysdice.core.expression.RollExpressionCaps;
import io.github.evildarkarchon.andysdice.core.facevalue.FaceValueGenerator;
import io.github.evildarkarchon.andysdice.core.geometry.DieKind;
import io.github.evildarkarchon.andysdice.core.simulation.ContactMaterial;
import io.github.evildarkarchon.andysdice.core.simulation.Ending;
import io.github.evildarkarchon.andysdice.core.simulation.FakeSimulator;
import io.github.evildarkarchon.andysdice.core.simulation.Simulator;
import io.github.evildarkarchon.andysdice.core.simulation.TrayGeometry;
import io.github.evildarkarchon.andysdice.core.simulation.TrayMaterials;
import java.util.SplittableRandom;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * The soak's workload and gates (spec §4, ADR-0013, ADR-0014), checked over the scripted fake simulator. The
 * real soak, over Jolt, is the {@code slow}-tagged test in {@code physics}.
 */
class SoakTest {

    private static final TrayGeometry BOX = TrayGeometry.rectangle(15, 10, 3);
    private static final TrayMaterials FELT =
            TrayMaterials.of(new ContactMaterial(0.45f, 0.15f), new ContactMaterial(0.45f, 0.5f), 0.6f);

    private static RollPlan roll(Simulator simulator, long seed) {
        return new ThrowPlanner(simulator, BOX, FELT).plan(Soak.EXPRESSION,
                new FaceValueGenerator(new SplittableRandom(seed)), new SplittableRandom(seed));
    }

    /** Statistics of {@code throwsCount} soak Rolls, each over a fresh fake scripted by {@code script}. */
    private static ThrowStatistics run(int throwsCount, Supplier<FakeSimulator> script) {
        ThrowStatistics stats = new ThrowStatistics();
        for (int i = 0; i < throwsCount; i++) {
            stats.add(roll(script.get(), i));
        }
        return stats;
    }

    @Test
    void theGatesAreSpecSection4s() {
        assertThat(Soak.THROWS_PER_TRAY).isEqualTo(1_000);
        assertThat(Soak.P95_REST_SECONDS).isEqualTo(1.5f);
        assertThat(Soak.MEAN_ATTEMPTS).isEqualTo(2.5);
        assertThat(Soak.COCKED_RETHROWS).isZero();
    }

    @Test
    void theWorkloadIsTheTwentyDiceCapMixOfTheFlatRestPrototype() {
        RollPlan.Planned roll = (RollPlan.Planned) roll(new FakeSimulator(), 1);

        assertThat(roll.dice()).hasSize(RollExpressionCaps.MAX_DICE_PER_THROW);
        // Params.DICE[5]: d20 x3, d12 x2, d10 x2, the percentile d10, d8 x3, d6 x6, d4 x3.
        assertThat(roll.dice()).extracting(PlannedDie::kind).containsExactlyInAnyOrder(
                DieKind.D20, DieKind.D20, DieKind.D20, DieKind.D12, DieKind.D12, DieKind.D10, DieKind.D10,
                DieKind.PERCENTILE_D10, DieKind.D8, DieKind.D8, DieKind.D8, DieKind.D6, DieKind.D6, DieKind.D6,
                DieKind.D6, DieKind.D6, DieKind.D6, DieKind.D4, DieKind.D4, DieKind.D4);
        assertThat(roll.throwChain()).hasSize(1);
    }

    @Test
    void aFullRunWithinEveryGatePasses() {
        // Every Attempt rests at once in the fake: 1 Attempt per Throw, no Cocked Rethrows.
        ThrowStatistics stats = run(Soak.THROWS_PER_TRAY, FakeSimulator::new);

        assertThat(Soak.violations(stats)).isEmpty();
    }

    @Test
    void tooFewThrowsFailTheSoak() {
        ThrowStatistics stats = run(10, FakeSimulator::new);

        assertThat(Soak.violations(stats)).singleElement().asString().contains("10 Throws", "1000");
    }

    @Test
    void tooManyAttemptsFailTheSoak() {
        // Three Attempts per Throw, two of them escaping: a mean of 3.
        ThrowStatistics stats = run(Soak.THROWS_PER_TRAY, () -> new FakeSimulator().then(2, Ending.ESCAPED));

        assertThat(Soak.violations(stats)).singleElement().asString().contains("mean Attempts", "3.00", "2.5");
    }

    @Test
    void anyCockedRethrowFailsTheSoak() {
        ThrowStatistics stats = run(Soak.THROWS_PER_TRAY, FakeSimulator::new);
        stats.add(roll(new FakeSimulator().thenCocked(ThrowPlanner.ATTEMPTS_PER_THROW, 3), 9));

        assertThat(Soak.violations(stats)).anySatisfy(v -> assertThat(v).contains("1 Cocked Rethrow"));
    }

    @Test
    void anyFailedRollFailsTheSoak() {
        ThrowStatistics stats = run(Soak.THROWS_PER_TRAY, FakeSimulator::new);
        stats.add(roll(new FakeSimulator().then(ThrowPlanner.ATTEMPTS_PER_THROW, Ending.TIMED_OUT), 9));

        assertThat(Soak.violations(stats)).singleElement().asString().contains("1 Failed Roll");
    }

    @Test
    void theReportNamesTheMeasuredGateNumbers() {
        ThrowStatistics stats = run(20, FakeSimulator::new);

        assertThat(Soak.report("felt", stats)).contains("felt", "20 Throws", "p95 time to rest 0.008 s",
                "mean Attempts 1.00", "Cocked Rethrows 0");
    }
}
