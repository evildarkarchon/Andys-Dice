package io.github.evildarkarchon.andysdice.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** The shader loader's {@code #include} splicing, which GLSL 3.30 lacks (render.md). No GL needed. */
class ShadersTest {

    @Test
    void splicesIncludesRecursivelyInPlace() {
        String source = Shaders.source("test-main.glsl");

        assertThat(source).doesNotContain("#include");
        assertThat(source).startsWith("#version 330 core\n");
        assertThat(source.indexOf("float leaf()")).isLessThan(source.indexOf("float part()"));
        assertThat(source.indexOf("float part()")).isLessThan(source.indexOf("void main()"));
    }

    @Test
    void anIncludeCycleIsAnError() {
        assertThatThrownBy(() -> Shaders.source("test-cycle.glsl"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cycle");
    }

    @Test
    void aMissingShaderIsAnError() {
        assertThatThrownBy(() -> Shaders.source("no-such.glsl"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no-such.glsl");
    }
}
