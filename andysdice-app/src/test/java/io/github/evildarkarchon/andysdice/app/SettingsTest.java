package io.github.evildarkarchon.andysdice.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code settings.properties} (spec §3.6, ADR-0007): a {@code java.util.Properties} file of flat keys under the
 * version header. An unknown key is ignored, and a bad value falls back to its own key's default without
 * discarding the rest. Pure Java, through {@link Persistence} on a temp folder.
 */
class SettingsTest {

    private enum Preset { LOW, MEDIUM, HIGH }

    private static final Setting<Boolean> MUTED = Setting.flag("sound.muted", false);
    private static final Setting<Integer> VOLUME = Setting.integer("sound.volume", 70, 0, 100);
    private static final Setting<Preset> PRESET = Setting.choice("quality.preset", Preset.class, Preset.MEDIUM);
    private static final Setting<String> DICE_SET = Setting.text("look.diceSet", "ivory");

    @TempDir
    Path folder;

    private final List<Notice> notices = new CopyOnWriteArrayList<>();
    private Persistence persistence;

    @BeforeEach
    void start() {
        persistence = new Persistence(folder, notices::add);
    }

    @AfterEach
    void stop() {
        persistence.close();
    }

    @Test
    void noFileMeansEveryDefault() {
        SettingsStore settings = new SettingsStore(persistence);

        assertThat(settings.get(MUTED)).isFalse();
        assertThat(settings.get(VOLUME)).isEqualTo(70);
        assertThat(settings.get(PRESET)).isEqualTo(Preset.MEDIUM);
        assertThat(settings.get(DICE_SET)).isEqualTo("ivory");
        assertThat(folder.resolve(Settings.FILE_NAME)).doesNotExist();
    }

    @Test
    void whatIsSetLoadsBackInTheNextRun() {
        SettingsStore settings = new SettingsStore(persistence);
        settings.set(MUTED, true);
        settings.set(VOLUME, 35);
        settings.set(PRESET, Preset.HIGH);
        settings.set(DICE_SET, "Obsidian & gold: #1");
        persistence.close();

        persistence = new Persistence(folder, notices::add);
        SettingsStore again = new SettingsStore(persistence);
        assertThat(again.get(MUTED)).isTrue();
        assertThat(again.get(VOLUME)).isEqualTo(35);
        assertThat(again.get(PRESET)).isEqualTo(Preset.HIGH);
        assertThat(again.get(DICE_SET)).isEqualTo("Obsidian & gold: #1");
        assertThat(notices).isEmpty();
    }

    @Test
    void theFileIsAHeaderThenSortedKeys() throws IOException {
        SettingsStore settings = new SettingsStore(persistence);
        settings.set(VOLUME, 35);
        settings.set(MUTED, true);
        persistence.flush();

        assertThat(Files.readString(folder.resolve(Settings.FILE_NAME), StandardCharsets.UTF_8))
                .isEqualTo("#andysdice settings.properties 1\nsound.muted=true\nsound.volume=35\n");
    }

    @Test
    void settingTheSameValueAgainWritesNothing() throws IOException {
        SettingsStore settings = new SettingsStore(persistence);
        settings.set(VOLUME, 35);
        persistence.flush();
        Files.delete(folder.resolve(Settings.FILE_NAME));

        settings.set(VOLUME, 35);
        persistence.flush();

        assertThat(folder.resolve(Settings.FILE_NAME)).doesNotExist();
    }

    @Test
    void anUnknownKeyIsIgnored() throws IOException {
        write("#andysdice settings.properties 1\nfrom.the.future=yes\nsound.volume=20\n");

        SettingsStore settings = new SettingsStore(persistence);

        assertThat(settings.get(VOLUME)).isEqualTo(20);
        assertThat(notices).isEmpty();
        assertThat(folder.resolve(Settings.FILE_NAME)).exists();
    }

    @Test
    void aBadValueFallsBackToItsDefaultAlone() throws IOException {
        write("#andysdice settings.properties 1\nsound.muted=perhaps\nsound.volume=140\nquality.preset=Ultra\n"
                + "look.diceSet=jade\n");

        SettingsStore settings = new SettingsStore(persistence);

        assertThat(settings.get(MUTED)).isFalse();
        assertThat(settings.get(VOLUME)).isEqualTo(70);
        assertThat(settings.get(PRESET)).isEqualTo(Preset.MEDIUM);
        assertThat(settings.get(DICE_SET)).isEqualTo("jade");
        assertThat(notices).isEmpty();
    }

    @Test
    void valuesAreReadLeniently() throws IOException {
        write("#andysdice settings.properties 1\nsound.muted = TRUE \nsound.volume=  0\nquality.preset=low\n");

        SettingsStore settings = new SettingsStore(persistence);

        assertThat(settings.get(MUTED)).isTrue();
        assertThat(settings.get(VOLUME)).isZero();
        assertThat(settings.get(PRESET)).isEqualTo(Preset.LOW);
    }

    @Test
    void settingNullPutsAKeyBackToItsDefault() {
        SettingsStore settings = new SettingsStore(persistence);
        settings.set(VOLUME, 10);

        settings.set(VOLUME, null);

        assertThat(settings.get(VOLUME)).isEqualTo(70);
        assertThat(settings.current().values()).doesNotContainKey("sound.volume");
    }

    @Test
    void aFileThatIsntPropertiesIsSetAside() throws IOException {
        // A malformed \\u escape is the one thing Properties.load rejects.
        write("#andysdice settings.properties 1\nsound.volume=\\uZZZZ\n");

        SettingsStore settings = new SettingsStore(persistence);

        assertThat(settings.get(VOLUME)).isEqualTo(70);
        assertThat(folder.resolve(Settings.FILE_NAME)).doesNotExist();
        assertThat(notices).singleElement()
                .satisfies(notice -> assertThat(notice.title()).isEqualTo("settings.properties was set aside"));
    }

    @Test
    void aNewerSettingsFileIsSetAside() throws IOException {
        write("#andysdice settings.properties 2\nsound.volume=20\n");

        SettingsStore settings = new SettingsStore(persistence);

        assertThat(settings.get(VOLUME)).isEqualTo(70);
        assertThat(notices).singleElement().satisfies(notice -> assertThat(notice.detail()).contains("newer"));
    }

    private void write(String text) throws IOException {
        Files.writeString(folder.resolve(Settings.FILE_NAME), text, StandardCharsets.UTF_8);
    }
}
