package io.github.evildarkarchon.andysdice.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.evildarkarchon.andysdice.core.expression.ParseResult;
import io.github.evildarkarchon.andysdice.core.expression.RollExpressionParser;
import io.github.evildarkarchon.andysdice.render.GpuBench;
import io.github.evildarkarchon.andysdice.render.QualityPreset;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The dev-only {@code --bench}'s verdict (ADR-0014, ticket #61), without GL or Jolt: the two release-blocking budgets
 * (the GPU time and Enter to first motion), what it prints against them, and the exit status a missed one gives.
 */
class BenchTest {

    @Test
    void itRollsTheRealisticWorstCaseTwentyDiceWithAnRrMatchingHalfTheirFaces() {
        // ADR-0014: 20 Dice, with an rr condition matching half the faces (a d20's 1 to 10).
        assertThat(Bench.EXPRESSION).isEqualTo("20d20rr<=10");
        assertThat(RollExpressionParser.parse(Bench.EXPRESSION)).isInstanceOf(ParseResult.Valid.class);
        assertThat(Bench.FIRST_MOTION_BUDGET).isEqualTo(Duration.ofMillis(150));
    }

    @Test
    void itIsHiddenBehindAFlagOfItsOwn() {
        assertThat(Bench.FLAG).isEqualTo("--bench").isNotEqualTo(SmokeTest.FLAG);
    }

    @Test
    void firstMotionIsJudgedOnTheMedianOfTheWarmRollsAfterTheColdOne() {
        Bench.FirstMotions motions = new Bench.FirstMotions(List.of(ms(400), ms(90), ms(200), ms(100), ms(110)));

        assertThat(motions.cold()).isEqualTo(ms(400));
        // The warm ones are 90, 200, 100 and 110: the median is between 100 and 110.
        assertThat(motions.median()).isEqualTo(ms(105));
        assertThat(motions.slowest()).isEqualTo(ms(200));
        assertThat(motions.meetsBudget()).isTrue();
        assertThat(new Bench.FirstMotions(List.of(ms(100), ms(151), ms(160), ms(90))).meetsBudget()).isFalse();
    }

    @Test
    void itExitsZeroOnlyWhenBothBudgetsAreMet() {
        Bench.FirstMotions quick = new Bench.FirstMotions(List.of(ms(300), ms(80), ms(90), ms(100)));
        Bench.FirstMotions slow = new Bench.FirstMotions(List.of(ms(300), ms(180), ms(190), ms(200)));

        assertThat(new Bench.Result(Duration.ofMillis(2400), quick, gpu(0.45)).exitStatus()).isZero();
        assertThat(new Bench.Result(Duration.ofMillis(2400), slow, gpu(0.45)).exitStatus())
                .isEqualTo(Bench.MISSED);
        assertThat(new Bench.Result(Duration.ofMillis(2400), quick, gpu(0.55)).exitStatus())
                .isEqualTo(Bench.MISSED);
        // Launch to window is logged and on the release checklist, but it isn't one of the bench's gates.
        assertThat(new Bench.Result(Duration.ofMillis(5000), quick, gpu(0.45)).exitStatus()).isZero();
        assertThat(Bench.MISSED).isNotZero().isNotEqualTo(AndysDice.STARTUP_FAILED)
                .isNotEqualTo(AndysDice.RUNTIME_FAILED).isNotEqualTo(SmokeTest.FAILED)
                .isNotEqualTo(AlreadyRunning.EXIT_STATUS);
    }

    @Test
    void itPrintsEachMeasureAgainstItsBudget() {
        Bench.FirstMotions slow = new Bench.FirstMotions(List.of(ms(300), ms(180), ms(190), ms(200)));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        new Bench.Result(Duration.ofMillis(2400), slow, gpu(0.45)).print(new PrintStream(bytes, true,
                StandardCharsets.UTF_8));
        String printed = bytes.toString(StandardCharsets.UTF_8);

        assertThat(printed).contains("Launch to window: 2400 ms (budget 3000 ms: met; not a bench gate)");
        assertThat(printed).contains("Enter to first motion, 20d20rr<=10: median 190 ms of 3 warm Rolls"
                + " (budget 150 ms: MISSED)");
        assertThat(printed).contains("cold 300 ms", "slowest 200 ms");
        assertThat(printed).contains("Medium 0.450 ms", "(budget 0.50 ms: met)");
        assertThat(printed).contains("Bench: MISSED");
    }

    private static Duration ms(long millis) {
        return Duration.ofMillis(millis);
    }

    /** A GPU report whose dearest scene takes {@code medium} ms at Medium. */
    private static GpuBench.Report gpu(double medium) {
        GpuBench.Scene scene = GpuBench.scenes().getFirst();
        Map<QualityPreset, GpuBench.GpuTime> byPreset = new EnumMap<>(QualityPreset.class);
        byPreset.put(QualityPreset.LOW, new GpuBench.GpuTime(scene, QualityPreset.LOW, medium / 2, 240));
        byPreset.put(QualityPreset.MEDIUM, new GpuBench.GpuTime(scene, QualityPreset.MEDIUM, medium, 240));
        byPreset.put(QualityPreset.HIGH, new GpuBench.GpuTime(scene, QualityPreset.HIGH, medium * 2, 240));
        return new GpuBench.Report(List.of(byPreset.get(QualityPreset.MEDIUM)), byPreset);
    }
}
