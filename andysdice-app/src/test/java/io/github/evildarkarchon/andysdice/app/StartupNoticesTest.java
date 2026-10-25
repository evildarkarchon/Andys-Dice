package io.github.evildarkarchon.andysdice.app;

import static io.github.evildarkarchon.andysdice.app.HeadlessUiTest.fromFx;
import static io.github.evildarkarchon.andysdice.app.HeadlessUiTest.onFx;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.evildarkarchon.andysdice.core.playback.PlaybackClock;
import io.github.evildarkarchon.andysdice.core.tray.BuiltInTrays;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.SplittableRandom;
import javafx.application.Platform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Start-up's persistence Notices reach the bell (spec §3.6, §3.2 "Notices"): the app makes its one {@link Notices}
 * before the session, loads the settings with it, and hands the same one to the {@link RollSession}, as
 * {@code AndysDice.start} does. Headless, with the stand-ins {@code HeadlessUiTest} uses.
 */
class StartupNoticesTest {

    @TempDir
    Path dataFolder;

    private Persistence persistence;

    @BeforeAll
    static void startToolkit() {
        HeadlessUiTest.startToolkit();
    }

    @AfterEach
    void closePersistence() {
        if (persistence != null) {
            persistence.close();
        }
    }

    @Test
    void aSettingsFileSetAsideAtStartUpShowsBehindTheBell() throws Exception {
        Files.writeString(dataFolder.resolve(Settings.FILE_NAME), "#andysdice settings.properties 99\n",
                StandardCharsets.UTF_8);
        PlaybackClock clock = new PlaybackClock();
        Generators generators = new Generators(new SplittableRandom(1), new SplittableRandom(7),
                new SplittableRandom(9));

        RollSession session = fromFx(() -> {
            Notices notices = new Notices();
            persistence = new Persistence(dataFolder, notices::post);
            new SettingsStore(persistence);
            return new RollSession(clock, new HandPulses(), new HeldPhysics(new ScriptedSimulator()),
                    Platform::runLater, generators, BuiltInTrays.defaultTray(), new StandInTray(clock), notices);
        });
        // The writer thread's post arrives through Platform.runLater; this block runs after it.
        onFx(() -> {});

        assertThat(fromFx(() -> session.notices().all())).singleElement()
                .satisfies(notice -> assertThat(notice.title()).isEqualTo("settings.properties was set aside"));
        assertThat(fromFx(() -> session.ui().bell().getText())).isEqualTo("1");
    }
}
