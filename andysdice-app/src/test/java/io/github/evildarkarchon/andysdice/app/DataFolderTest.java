package io.github.evildarkarchon.andysdice.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The data folder (spec §3.6, ADR-0004): {@code data\} beside the launcher, or {@code %LOCALAPPDATA%\AndysDice}
 * when that isn't writable, chosen once at start-up, with an app-owned system property overriding both for dev
 * runs and tests. The choice is fed its system properties, environment and writability check by hand.
 */
class DataFolderTest {

    private static final Path LAUNCHER = Path.of("C:/Games/AndysDice/AndysDice.exe");
    private static final Path BESIDE = Path.of("C:/Games/AndysDice/data");
    private static final Path LOCAL = Path.of("C:/Users/andy/AppData/Local");

    private static final Map<String, String> PACKAGED = Map.of("jpackage.app-path", LAUNCHER.toString());
    private static final Map<String, String> WINDOWS_ENV = Map.of("LOCALAPPDATA", LOCAL.toString());

    @Test
    void theDataFolderBesideTheLauncherWinsWhenItIsWritable() {
        DataFolder chosen = DataFolder.choose(PACKAGED, WINDOWS_ENV, writableOnly(BESIDE, LOCAL.resolve("AndysDice")));

        assertThat(chosen.folder()).isEqualTo(BESIDE);
        assertThat(chosen.reason()).contains("beside the launcher");
    }

    /** Unzipped into Program Files, say: the folder beside the launcher can't be written. */
    @Test
    void anUnwritableFolderBesideTheLauncherFallsBackToLocalAppData() {
        DataFolder chosen = DataFolder.choose(PACKAGED, WINDOWS_ENV, writableOnly(LOCAL.resolve("AndysDice")));

        assertThat(chosen.folder()).isEqualTo(LOCAL.resolve("AndysDice"));
        assertThat(chosen.reason()).contains(BESIDE.toString()).contains("isn't writable");
    }

    @Test
    void theOverridePropertyWinsOverBoth() {
        Path override = Path.of("D:/dev/andysdice-data");
        DataFolder chosen = DataFolder.choose(
                Map.of("jpackage.app-path", LAUNCHER.toString(), "andysdice.data.dir", override.toString()),
                WINDOWS_ENV, writableOnly(BESIDE, LOCAL.resolve("AndysDice"), override));

        assertThat(chosen.folder()).isEqualTo(override.toAbsolutePath());
        assertThat(chosen.reason()).contains("andysdice.data.dir");
    }

    /** A dev run outside the image has no launcher to sit beside, so it goes straight to the fallback. */
    @Test
    void withoutALauncherTheFallbackIsChosen() {
        DataFolder chosen = DataFolder.choose(Map.of(), WINDOWS_ENV, any -> true);

        assertThat(chosen.folder()).isEqualTo(LOCAL.resolve("AndysDice"));
        assertThat(chosen.reason()).contains("no launcher");
    }

    /** Outside Windows (the build stays Linux- and mac-capable) the fallback is under the home folder. */
    @Test
    void withoutLocalAppDataTheFallbackIsUnderTheHomeFolder() {
        DataFolder chosen = DataFolder.choose(Map.of("user.home", "/home/andy", "os.name", "Linux"), Map.of(),
                any -> true);

        assertThat(chosen.folder()).isEqualTo(Path.of("/home/andy/.local/share/AndysDice").toAbsolutePath());
    }

    @Test
    void aWritableFolderIsCreatedAndLeftWithoutAProbeFile(@TempDir Path temp) throws IOException {
        Path folder = temp.resolve("AndysDice/data");

        assertThat(DataFolder.isWritable(folder)).isTrue();

        assertThat(folder).isDirectory();
        try (var entries = Files.list(folder)) {
            assertThat(entries).isEmpty();
        }
    }

    @Test
    void aFolderThatCantBeCreatedIsNotWritable(@TempDir Path temp) throws IOException {
        Path file = Files.writeString(temp.resolve("in-the-way"), "a file, not a folder");

        assertThat(DataFolder.isWritable(file.resolve("data"))).isFalse();
    }

    private static Predicate<Path> writableOnly(Path... folders) {
        Set<Path> writable = Set.of(folders);
        return writable::contains;
    }
}
