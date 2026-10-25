package io.github.evildarkarchon.andysdice.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Native context creation (ADR-0002): when the driver can't give the requested core context, creation fails
 * with a reason naming the requirement and what the driver did report. No machine lacks 3.3 here, so the test
 * asks for a version no driver has; the GL 3.3 failure itself is covered by the no-Mesa CI job (#35).
 *
 * <p>Runs on a thread of its own, as a context belongs to the thread that made it.
 */
@Tag("gl")
class GlContextTest {

    @Test
    void anUnavailableVersionFailsNamingTheRequirementAndTheDriver() throws Exception {
        GlUnavailableException e = CompletableFuture.supplyAsync(() -> catchThrowableOfType(
                        GlUnavailableException.class, () -> GlContext.create(new GlVersion(9, 9)).close()),
                        command -> Thread.ofPlatform().start(command))
                .get(30, TimeUnit.SECONDS);

        assertThat(e).isNotNull();
        assertThat(e.getMessage()).contains("OpenGL 9.9 core");
        // The legacy context that is always made first still says which driver this is.
        assertThat(e.driver()).as("%s", e).hasValueSatisfying(driver -> {
            assertThat(driver.renderer()).isNotBlank();
            assertThat(driver.vendor()).isNotBlank();
            assertThat(GlVersion.parse(driver.version())).isPresent();
        });
        assertThat(e.getMessage()).contains("renderer: " + e.driver().orElseThrow().renderer());
    }
}
