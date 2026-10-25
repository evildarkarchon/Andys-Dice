package io.github.evildarkarchon.andysdice.assembly;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The built {@code AndysDice.exe --smoke-test} (ADR-0005): it starts JavaFX, Jolt and the GL context, plays one
 * fixed Roll Expression at speed and exits 0, logging into {@code data\} beside the launcher, with no
 * native-access warning anywhere and nothing extracted to {@code ~/.openjfx/cache}.
 *
 * <p>It needs an OpenGL 3.3 context (Mesa in CI), so it is {@code gl}-tagged. One run serves every check.
 */
@Tag("gl")
class SmokeTestImageIT {

    private static final Duration TIMEOUT = Duration.ofMinutes(3);

    /** What the JVM, LWJGL or JOML print about native access or sun.misc.Unsafe, under warn or deny. */
    private static final Pattern NATIVE_ACCESS_WARNING = Pattern.compile(
            "(?i)native.access|restricted method|sun\\.misc\\.Unsafe|^WARNING: ", Pattern.MULTILINE);

    private static final Path OPENJFX_CACHE = Path.of(System.getProperty("user.home"), ".openjfx", "cache");

    private static BuiltImage.Run run;
    private static boolean openJfxCacheExistedBefore;

    @BeforeAll
    static void runTheSmokeTest() throws IOException, InterruptedException {
        Path image = BuiltImage.copy("smoke-test");
        openJfxCacheExistedBefore = Files.exists(OPENJFX_CACHE);
        run = BuiltImage.run(image, TIMEOUT, "--smoke-test");
    }

    @Test
    void exitsZeroAfterRollingTheFixedExpression() throws IOException {
        assertThat(run.exitStatus()).as(run.describe()).isZero();
        // The Roll Result as the result strip shows it: the canonical expression, then its total.
        assertThat(run.log()).containsPattern("Smoke test rolled \\S+ = -?\\d+");
    }

    /** The data folder beside the launcher was chosen, and the log says so and why. */
    @Test
    void logsIntoTheDataFolderBesideTheLauncher() throws IOException {
        Path data = run.image().resolve("data");

        assertThat(run.logFile()).as(run.describe()).isRegularFile();
        assertThat(run.log()).contains("Data folder: " + data + " (beside the launcher)");
    }

    /** ADR-0005's start-up steps, each on record: Prism's pipeline, Jolt and the GL context, the window. */
    @Test
    void logsThePrismPipelineTheGlDriverAndTheLaunchToWindowTime() throws IOException {
        String log = run.log();

        assertThat(log).as(run.describe()).contains("Prism pipeline name = ");
        assertThat(log).containsPattern("Jolt Physics started; OpenGL context: renderer: .+, vendor: .+, version: ");
        assertThat(log).containsPattern("Window shown \\d+ ms after launch");
    }

    /** LWJGL loads its own natives from app\natives through org.lwjgl.librarypath, extracting nothing. */
    @Test
    void lwjglLoadsItsNativesFromAppNatives() throws IOException {
        Path natives = run.image().resolve("app").resolve("natives");

        assertThat(run.log()).as(run.describe())
                .contains("Loaded from org.lwjgl.librarypath: " + natives.resolve("lwjgl.dll"))
                .contains("Loaded from org.lwjgl.librarypath: " + natives.resolve("lwjgl_opengl.dll"));
    }

    /**
     * The JVM prints its start-up warnings (an unknown module in the option set, say) to standard error before
     * the app's log exists, and the rest reach the log through its tee of standard error; neither has any.
     */
    @Test
    void logsNoNativeAccessWarnings() throws IOException {
        assertThat(NATIVE_ACCESS_WARNING.matcher(run.stderr()).find()).as(run.describe()).isFalse();
        assertThat(NATIVE_ACCESS_WARNING.matcher(run.log()).find()).as(run.describe()).isFalse();
    }

    /** Every JavaFX native comes from the image's runtime, so none is extracted to ~/.openjfx/cache. */
    @Test
    void createsNoOpenJfxCache() throws IOException {
        Path javafxBin = run.image().resolve("runtime").resolve("bin").resolve("javafx");
        List<String> loads = run.log().lines().filter(line -> line.contains("System.err: Loaded ")).toList();

        assertThat(loads).as("JavaFX's javafx.verbose report of its native loads").isNotEmpty()
                .allMatch(line -> line.contains("System.err: Loaded " + javafxBin), "loaded from " + javafxBin);
        assertThat(run.log()).doesNotContain(".openjfx");
        if (!openJfxCacheExistedBefore) {
            // On a dev machine an earlier dev run (Maven's JavaFX jars) may have made it already; on CI it must
            // still be absent.
            assertThat(OPENJFX_CACHE).doesNotExist();
        }
    }
}
