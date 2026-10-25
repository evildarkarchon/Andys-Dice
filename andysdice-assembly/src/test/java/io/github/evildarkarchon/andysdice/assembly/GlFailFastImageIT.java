package io.github.evildarkarchon.andysdice.assembly;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The built app's GL fail-fast (ADR-0002), forced on a machine that has OpenGL 3.3: a copy of the image whose
 * launcher also sets render's dev override {@code -Dandysdice.gl.require} to a version no driver offers. The
 * real driver is asked and refuses, as GDI Generic's 1.1 refuses 3.3 in CI's no-Mesa job, which checks the
 * unforced path. The launch must show the failure panel naming the requirement and the renderer, vendor and
 * version the driver reported (spec §3.2 "Runtime failure"), log the same, and exit non-zero.
 *
 * <p>A plain launch would wait on the panel for a Player to quit, so this one adds {@code --smoke-test}, whose
 * failure panel exits at once; CI's no-Mesa job checks the plain launch by closing the window, as a Player would.
 *
 * <p>It needs a GL driver to refuse, so it is {@code gl}-tagged.
 */
@Tag("gl")
class GlFailFastImageIT {

    private static final Duration TIMEOUT = Duration.ofMinutes(2);

    @Test
    void aGpuBelowTheRequirementExitsNonZeroNamingItAndTheDetectedDriver() throws IOException, InterruptedException {
        Path image = BuiltImage.copy("gl-fail-fast");
        // The launcher reads the [JavaOptions] section, which is the .cfg's last; an appended entry joins it.
        Files.writeString(image.resolve("app").resolve("AndysDice.cfg"),
                System.lineSeparator() + "java-options=-Dandysdice.gl.require=99.0" + System.lineSeparator(),
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        BuiltImage.Run run = BuiltImage.run(image, TIMEOUT, "--smoke-test");

        assertThat(run.exitStatus()).as(run.describe()).isEqualTo(1);
        String log = run.log();
        assertThat(log).as(run.describe()).contains("Start-up failed: Andy's Dice needs OpenGL 99.0 core, but ");
        assertThat(log).containsPattern("\\(renderer: (?!unknown).+, vendor: (?!unknown).+, version: \\d+\\.\\d+");
        // The panel says the same: the requirement, then the renderer and the driver.
        assertThat(log).containsPattern("Failure panel shown: Andy's Dice needs OpenGL 99\\.0 core, but .+"
                + "\\(renderer: (?!unknown).+, vendor: (?!unknown).+, version: \\d+\\.\\d+");
        // Only the panel's window opened: the Roll UI never got going.
        assertThat(log).doesNotContain("Window shown");
    }
}
