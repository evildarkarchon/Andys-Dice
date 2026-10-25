package io.github.evildarkarchon.andysdice.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * {@code GL_VERSION} strings as real drivers report them, and whether each meets the 3.3 floor (ADR-0002).
 * The version string is all a legacy 1.1 context (GDI Generic) can tell us, so the floor check reads it.
 */
class GlVersionTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            4.6.0 NVIDIA 595.79                    | 4 | 6
            4.6 (Core Profile) Mesa 26.2.4         | 4 | 6
            3.3.0 - Build 31.0.101.5186            | 3 | 3
            1.1.0                                  | 1 | 1
            4.1 Metal - 88.1                       | 4 | 1
            """)
    void readsMajorAndMinorFromTheStartOfTheString(String reported, int major, int minor) {
        assertThat(GlVersion.parse(reported)).contains(new GlVersion(major, minor));
    }

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {"null", "''", "OpenGL ES 3.2", "garbage"})
    void anUnreadableStringHasNoVersion(String reported) {
        assertThat(GlVersion.parse(reported)).isEqualTo(Optional.empty());
    }

    @ParameterizedTest
    @CsvSource({"3, 3, true", "4, 6, true", "4, 0, true", "3, 2, false", "2, 1, false", "1, 1, false"})
    void meetsTheFloorAtOrAbove33(int major, int minor, boolean meets) {
        assertThat(new GlVersion(major, minor).isAtLeast(GlVersion.FLOOR)).isEqualTo(meets);
    }

    /** Without the dev override the render thread asks for the floor, 3.3. */
    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {"null", "''", "'  '"})
    void theRequiredVersionIsTheFloorWithoutTheOverride(String override) {
        assertThat(GlVersion.required(override)).isEqualTo(new GlVersion(3, 3));
    }

    /** The override can raise the version asked for (to force the fail-fast on a capable GPU), never lower it. */
    @ParameterizedTest
    @CsvSource({"9.9, 9, 9", "4.6, 4, 6", "' 4.7 ', 4, 7", "3.3, 3, 3", "3.2, 3, 3", "1.1, 3, 3"})
    void theOverrideOnlyRaisesTheRequiredVersion(String override, int major, int minor) {
        assertThat(GlVersion.required(override)).isEqualTo(new GlVersion(major, minor));
    }

    @ParameterizedTest
    @CsvSource({"four", "4", "OpenGL 4.6"})
    void anUnreadableOverrideIsRefusedNamingTheProperty(String override) {
        assertThatThrownBy(() -> GlVersion.required(override))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("andysdice.gl.require");
    }
}
