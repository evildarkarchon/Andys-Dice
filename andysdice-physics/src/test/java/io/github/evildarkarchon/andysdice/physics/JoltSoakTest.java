package io.github.evildarkarchon.andysdice.physics;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.evildarkarchon.andysdice.core.facevalue.FaceValueGenerator;
import io.github.evildarkarchon.andysdice.core.planner.Soak;
import io.github.evildarkarchon.andysdice.core.planner.ThrowPlanner;
import io.github.evildarkarchon.andysdice.core.planner.ThrowStatistics;
import io.github.evildarkarchon.andysdice.core.tray.BuiltInTrays;
import io.github.evildarkarchon.andysdice.core.tray.Tray;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The soak (ADR-0005, ADR-0013, ADR-0014): {@link Soak#THROWS_PER_TRAY} Rolls of {@link Soak#EXPRESSION}, the
 * 20-Dice cap, into each built-in Tray through the real planner and real Jolt, failing on any of Soak's gates.
 * The flat-rest prototype's statistics harness ({@code FlatRest}), over the v1 pipeline.
 *
 * <p>Tagged {@code slow}: the normal build leaves it out, and {@code mvn verify -Psoak} runs it (the nightly job).
 * Each Tray's measured numbers are printed and written to {@code target/soak/}, which the nightly job publishes.
 */
@Tag("slow")
class JoltSoakTest {

    /** Where the per-Tray reports go, under the module's build directory (Surefire runs in the module). */
    private static final Path REPORTS = Path.of("target", "soak");

    /** Each Tray's run is seeded on its own, so one Tray's numbers don't depend on which ran before it. */
    private static final long SEED = 39;

    /** Every built-in Tray, as its shipped {@code .tray} file gives physics its geometry and materials (#44). */
    static Stream<Arguments> trays() {
        return BuiltInTrays.all().stream().map(tray -> Arguments.of(Named.of(tray.name(), tray)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("trays")
    void twentyDiceThrowsIntoTheTrayMeetEveryGate(Tray tray) throws IOException {
        long start = System.nanoTime();
        ThrowStatistics stats = TestWorker.get().submit(simulator -> {
            ThrowPlanner planner = new ThrowPlanner(simulator, tray.geometry(), tray.materials());
            SplittableRandom seeds = new SplittableRandom(SEED);
            ThrowStatistics run = new ThrowStatistics();
            for (int i = 0; i < Soak.THROWS_PER_TRAY; i++) {
                // Each Roll gets its own Face Value and Release generators (ADR-0003: two streams).
                run.add(planner.plan(Soak.EXPRESSION, new FaceValueGenerator(seeds.split()), seeds.split()));
            }
            return run;
        }).join();
        double seconds = (System.nanoTime() - start) / 1e9;

        String report = Soak.report(tray.name(), stats)
                + String.format(Locale.ROOT, "; %.1f s wall time", seconds);
        List<String> missed = Soak.violations(stats);
        System.out.println("[soak] " + report);
        missed.forEach(line -> System.out.println("[soak]   missed: " + line));
        Files.createDirectories(REPORTS);
        Files.write(REPORTS.resolve(slug(tray.name()) + ".txt"),
                Stream.concat(Stream.of(report), missed.stream().map(line -> "missed: " + line)).toList(),
                StandardCharsets.UTF_8);

        assertThat(missed).as(report).isEmpty();
    }

    /** A file-name form of a Tray's name: lower case, words joined by hyphens. */
    private static String slug(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
    }
}
