package io.github.evildarkarchon.andysdice.assembly;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The built launcher checks its command line before any window opens: {@code AndysDice.exe --smoke} (a typo for
 * {@code --smoke-test}) is rejected, logged and exits with the app's rejection status, 7, without starting JavaFX,
 * instead of opening a normal session. Nothing on the launcher's side adds an argument the app would then reject.
 *
 * <p>It needs no GL: a rejected launch never gets that far. So it isn't {@code gl}-tagged.
 */
class CommandLineImageIT {

    /** The app's {@code CommandLine.REJECTED_STATUS}. */
    private static final int REJECTED_STATUS = 7;

    // A rejected launch only starts the JVM and writes a line; a minute is ample even on a slow CI runner.
    private static final Duration TIMEOUT = Duration.ofMinutes(1);

    @Test
    void aMistypedFlagIsRejectedLoggedAndOpensNoWindow() throws IOException, InterruptedException {
        Path image = BuiltImage.copy("rejected-command-line");
        // Prism's and JavaFX's own reports, on from the JVM's start: had the toolkit started, the log's tee of
        // standard output and error would hold them (Prism's pipeline, glass.dll's load).
        Files.writeString(image.resolve("app").resolve("AndysDice.cfg"),
                System.lineSeparator() + "java-options=-Dprism.verbose=true" + System.lineSeparator()
                        + "java-options=-Djavafx.verbose=true" + System.lineSeparator(),
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        BuiltImage.Run run = BuiltImage.run(image, TIMEOUT, "--smoke");

        assertThat(run.exitStatus()).as(run.describe()).isEqualTo(REJECTED_STATUS);
        String log = run.log();
        // Recorded in the log, though the exe has no console: at WARNING, naming the argument and the accepted flags.
        assertThat(log).as(run.describe()).containsPattern("WARNING \\S+\\.Main: Rejected the command line: "
                + "\"--smoke\" .*accepts no arguments, or exactly one of --smoke-test, --bench");
        // And on standard error, for a dev run.
        assertThat(run.stderr()).as(run.describe()).contains("\"--smoke\"");
        // The toolkit never started: no Prism or JavaFX report, no start-up, no window.
        assertThat(log).as(run.describe()).doesNotContain("Prism", "System.err: Loaded ", "starting on Java",
                "Jolt Physics started", "Window shown", "Failure panel shown");
    }

    /**
     * The jpackage launcher passes only what it was given: its {@code .cfg} has no {@code [ArgOptions]} (jpackage's
     * {@code --arguments}), which it would put before the user's, so an empty command line reaches {@code main} empty.
     */
    @Test
    void theLauncherAddsNoArgumentsOfItsOwn() throws IOException {
        List<String> cfg = Files.readAllLines(BuiltImage.dir().resolve("app").resolve("AndysDice.cfg"));

        assertThat(cfg).noneMatch(line -> line.strip().equalsIgnoreCase("[ArgOptions]"))
                .noneMatch(line -> line.startsWith("arguments="));
    }
}
