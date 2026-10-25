package io.github.evildarkarchon.andysdice.physics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * Where our native loader finds Jolt's native (ADR-0004): in the natives directory the launcher (or the build)
 * names, unless a developer overrides the library file itself.
 */
class JoltLoaderTest {

    private static final String LIBRARY = System.mapLibraryName("joltjni");

    private static Properties properties(String... keysAndValues) {
        Properties p = new Properties();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            p.setProperty(keysAndValues[i], keysAndValues[i + 1]);
        }
        return p;
    }

    @Test
    void loadsThePlatformsLibraryFromTheNativesDirectory() {
        Path dir = Path.of("app", "natives").toAbsolutePath();
        assertThat(JoltLoader.libraryPath(properties(JoltLoader.NATIVES_DIR_PROPERTY, dir.toString())))
                .isEqualTo(dir.resolve(LIBRARY));
    }

    @Test
    void aDevOverrideNamesTheLibraryFileItself() {
        Path debug = Path.of("debug", "joltjni-debug.bin").toAbsolutePath();
        assertThat(JoltLoader.libraryPath(properties(
                        JoltLoader.NATIVES_DIR_PROPERTY, Path.of("app", "natives").toAbsolutePath().toString(),
                        JoltLoader.LIBRARY_OVERRIDE_PROPERTY, debug.toString())))
                .isEqualTo(debug);
    }

    @Test
    void aRelativeDirectoryIsResolvedToAnAbsolutePath() {
        assertThat(JoltLoader.libraryPath(properties(JoltLoader.NATIVES_DIR_PROPERTY, "target/natives")))
                .isAbsolute()
                .endsWithRaw(Path.of("target", "natives", LIBRARY));
    }

    @Test
    void withoutEitherPropertyItSaysWhichToSet() {
        assertThatThrownBy(() -> JoltLoader.libraryPath(properties()))
                .isInstanceOf(JoltStartupException.class)
                .hasMessageContaining(JoltLoader.NATIVES_DIR_PROPERTY);
    }

    @Test
    void aMissingLibraryFailsNamingThePlatformTheLibraryAndThePath() {
        Path missing = Path.of("no", "such", "dir").toAbsolutePath().resolve(LIBRARY);
        assertThatThrownBy(() -> JoltLoader.load(missing))
                .isInstanceOf(JoltStartupException.class)
                .hasMessageContaining(System.getProperty("os.name"))
                .hasMessageContaining(System.getProperty("os.arch"))
                .hasMessageContaining("jolt-jni")
                .hasMessageContaining(missing.toString());
    }
}
