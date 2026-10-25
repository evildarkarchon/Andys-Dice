package io.github.evildarkarchon.andysdice.core.facevalue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.evildarkarchon.andysdice.core.geometry.DieKind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Face Value selection (ADR-0001, ADR-0005): every Face Value comes from the injected generator, one draw per
 * Die (one per Percentile Roll), and the draws are fair, checked by a chi-square test at a fixed seed.
 */
class FaceValueGeneratorTest {

    /** The fixed seed of every fairness test, so that each runs the same draws on every build. */
    private static final long SEED = 31;

    /** Expected draws per category in each chi-square test. */
    private static final int PER_CATEGORY = 1_000;

    /** Each Die's value is one draw from 1 to its face count, straight from the injected generator. */
    @ParameterizedTest
    @CsvSource({"D4, 4", "D6, 6", "D8, 8", "D10, 10", "D12, 12", "D20, 20"})
    void drawsEachFaceValueOnceFromTheInjectedGenerator(DieKind kind, int highest) {
        ScriptedRandom random = new ScriptedRandom(highest, 1);
        FaceValueGenerator generator = new FaceValueGenerator(random);

        assertThat(generator.faceValue(kind)).isEqualTo(highest);
        assertThat(generator.faceValue(kind)).isEqualTo(1);
        assertThat(random.calls).containsExactly(List.of(1, highest + 1), List.of(1, highest + 1));
    }

    /** A Percentile Roll is one draw from 1 to 100, which then splits across its two Dice. */
    @Test
    void drawsAPercentileRollAsOneValueFromOneToOneHundred() {
        ScriptedRandom random = new ScriptedRandom(100);
        FaceValueGenerator generator = new FaceValueGenerator(random);

        int value = generator.percentileRoll();

        assertThat(value).isEqualTo(100);
        assertThat(random.calls).containsExactly(List.of(1, 101));
        assertThat(DieKind.PERCENTILE_D10.numeral(FaceValues.percentileTensFace(value))).isEqualTo("00");
        assertThat(DieKind.D10.numeral(FaceValues.percentileUnitsFace(value))).isEqualTo("0");
    }

    @Test
    void rejectsThePercentileD10OnItsOwn() {
        FaceValueGenerator generator = new FaceValueGenerator(new SplittableRandom(SEED));

        assertThatThrownBy(() -> generator.faceValue(DieKind.PERCENTILE_D10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** The same seed gives the same Face Values. */
    @Test
    void repeatsItsDrawsForTheSameSeed() {
        FaceValueGenerator a = new FaceValueGenerator(new SplittableRandom(SEED));
        FaceValueGenerator b = new FaceValueGenerator(new SplittableRandom(SEED));

        for (int i = 0; i < 100; i++) {
            assertThat(a.faceValue(DieKind.D20)).isEqualTo(b.faceValue(DieKind.D20));
            assertThat(a.percentileRoll()).isEqualTo(b.percentileRoll());
        }
    }

    /**
     * Each Die Kind's faces come up uniformly: the chi-square statistic of {@code 1000 × N} draws, counted per
     * face through the Face Value mapping, stays under the 0.1% critical value for {@code N - 1} degrees of
     * freedom (standard table values). This catches a mapping that is one-to-one but skewed.
     */
    @ParameterizedTest
    @CsvSource({
        "D4, 16.266",
        "D6, 20.515",
        "D8, 24.322",
        "D10, 27.877",
        "D12, 31.264",
        "D20, 43.820",
    })
    void landsOnEveryFaceEvenly(DieKind kind, double critical) {
        FaceValueGenerator generator = new FaceValueGenerator(new SplittableRandom(SEED));
        long[] counts = new long[kind.faceCount()];
        for (int i = 0; i < PER_CATEGORY * kind.faceCount(); i++) {
            counts[FaceValues.face(kind, generator.faceValue(kind))]++;
        }

        assertThat(chiSquare(counts)).isLessThan(critical);
    }

    /**
     * A Percentile Roll's two Dice each come up evenly (9 degrees of freedom, critical value 27.877), and so do
     * its 100 values (99 degrees of freedom, critical value 148.230).
     */
    @Test
    void splitsPercentileRollsEvenlyAcrossBothDice() {
        FaceValueGenerator generator = new FaceValueGenerator(new SplittableRandom(SEED));
        long[] tens = new long[DieKind.PERCENTILE_D10.faceCount()];
        long[] units = new long[DieKind.D10.faceCount()];
        long[] values = new long[FaceValues.PERCENTILE_HIGHEST];
        for (int i = 0; i < PER_CATEGORY * FaceValues.PERCENTILE_HIGHEST; i++) {
            int value = generator.percentileRoll();
            tens[FaceValues.percentileTensFace(value)]++;
            units[FaceValues.percentileUnitsFace(value)]++;
            values[value - 1]++;
        }

        assertThat(chiSquare(tens)).as("percentile d10").isLessThan(27.877);
        assertThat(chiSquare(units)).as("d10").isLessThan(27.877);
        assertThat(chiSquare(values)).as("1-100").isLessThan(148.230);
    }

    /**
     * Fairness is tested only against seeded generators (ADR-0005), never against the app's
     * cryptographically-seeded source: no test source names that class.
     */
    @Test
    void noTestUsesTheCryptographicGenerator() throws IOException {
        // Split so that this file does not match its own search.
        String forbidden = "Secure" + "Random";
        List<Path> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src", "test", "java"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (Files.readString(file).contains(forbidden)) {
                    offenders.add(file);
                }
            }
        }
        assertThat(offenders).isEmpty();
    }

    /** Pearson's chi-square statistic of counts against a uniform expectation. */
    private static double chiSquare(long[] counts) {
        long total = 0;
        for (long c : counts) {
            total += c;
        }
        double expected = (double) total / counts.length;
        double sum = 0;
        for (long c : counts) {
            sum += (c - expected) * (c - expected) / expected;
        }
        return sum;
    }

    /**
     * A generator that answers bounded {@code nextInt} draws from a script and records each draw's bounds. Any
     * other kind of draw fails, so a test sees exactly which draws the code under test makes.
     */
    private static final class ScriptedRandom implements RandomGenerator {
        private final Deque<Integer> script = new ArrayDeque<>();
        final List<List<Integer>> calls = new ArrayList<>();

        ScriptedRandom(int... values) {
            for (int v : values) {
                script.add(v);
            }
        }

        @Override
        public int nextInt(int origin, int bound) {
            calls.add(List.of(origin, bound));
            return script.remove();
        }

        @Override
        public long nextLong() {
            throw new UnsupportedOperationException("only bounded nextInt is scripted");
        }
    }
}
