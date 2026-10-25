package io.github.evildarkarchon.andysdice.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Guards the {@code gl} tag's wiring. Tests that need an OpenGL 3.3 context carry {@code @Tag("gl")}; a build
 * without GL (the {@code no-gl} profile, used by the Ubuntu CI job) excludes them by tag rather than letting
 * them skip themselves (ADR-0005).
 *
 * <p>This test is tagged {@code gl} but needs no context. It runs, and passes, in every build that includes
 * {@code gl} tests. If a build that excludes them runs it anyway, the tag filter is broken and it fails loudly.
 */
@Tag("gl")
class GlTagGuardTest {

    @Test
    void runsOnlyInBuildsThatIncludeGlTests() {
        String excluded = System.getProperty("andysdice.test.excludedGroups", "");
        assertThat(Arrays.stream(excluded.split(",")).map(String::strip))
                .as("a gl-tagged test ran in a build that excludes the gl tag (andysdice.test.excludedGroups=%s)",
                        excluded)
                .doesNotContain("gl");
    }
}
