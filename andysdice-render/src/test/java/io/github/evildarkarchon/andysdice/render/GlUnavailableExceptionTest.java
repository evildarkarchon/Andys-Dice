package io.github.evildarkarchon.andysdice.render;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The fail-fast's reason (ADR-0002, spec §3 "Runtime failure"): it names the OpenGL 3.3 core requirement and
 * the renderer, vendor and version the driver reported, so the start-up panel and the log can show them.
 */
class GlUnavailableExceptionTest {

    @Test
    void namesTheRequirementAndTheDetectedDriver() {
        var e = new GlUnavailableException("the driver offers only OpenGL 1.1.0",
                new GlDriver("GDI Generic", "Microsoft Corporation", "1.1.0"));

        assertThat(e.getMessage())
                .contains("OpenGL 3.3 core")
                .contains("the driver offers only OpenGL 1.1.0")
                .contains("renderer: GDI Generic")
                .contains("vendor: Microsoft Corporation")
                .contains("version: 1.1.0");
        assertThat(e.driver()).hasValueSatisfying(d -> assertThat(d.renderer()).isEqualTo("GDI Generic"));
    }

    @Test
    void saysSoWhenNoContextAtAllCouldReportADriver() {
        var e = new GlUnavailableException("no OpenGL pixel format", null);

        assertThat(e.getMessage()).contains("OpenGL 3.3 core").contains("no OpenGL pixel format")
                .contains("renderer: unknown");
        assertThat(e.driver()).isEmpty();
    }
}
