package io.github.evildarkarchon.andysdice.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.evildarkarchon.andysdice.render.QualityPreset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javafx.scene.Node;
import javafx.scene.control.Control;
import javafx.scene.control.RadioButton;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The Settings tab's Quality Preset (spec §3.2 "Settings tab", ADR-0014): Low, Medium (the default) or High, with
 * no Custom and no per-lever control; the choice is applied at once, saved in {@code settings.properties} under
 * {@link QualityPresetChooser#PRESET} and restored at the next launch, an unknown saved value falls back to Medium,
 * and the slow-GPU Notice never changes it. Each "launch" is a fresh {@link Persistence} and {@link SettingsStore}
 * over the test's data folder.
 */
class QualityPresetSettingTest extends HeadlessUiTest {

    private final List<Notice> persistenceNotices = new CopyOnWriteArrayList<>();
    private final List<QualityPreset> applied = new CopyOnWriteArrayList<>();
    private Persistence persistence;
    private SettingsStore settings;
    private QualityPresetChooser chooser;

    @AfterEach
    void closePersistence() {
        if (persistence != null) {
            persistence.close();
        }
    }

    @Test
    void theSettingsTabOffersLowMediumAndHighWithMediumAtFirstRun() throws Exception {
        launch();

        assertThat(fromFx(() -> chooser.buttons().stream().map(RadioButton::getText).toList()))
                .containsExactly("Low", "Medium", "High");
        assertThat(fromFx(() -> chooser.selected())).isEqualTo(QualityPreset.MEDIUM);
        assertThat(fromFx(() -> chooser.buttons().get(1).isSelected())).isTrue();
        assertThat(applied).as("the first run's preset goes to the renderer").containsExactly(QualityPreset.MEDIUM);
        // The three choices are the section's only controls: no Custom, no per-lever control.
        assertThat(fromFx(() -> controlsIn(chooser.section()))).containsExactlyElementsOf(
                fromFx(() -> List.<Control>copyOf(chooser.buttons())));
        assertThat(fromFx(() -> isInSettingsTab(chooser.section()))).isTrue();
    }

    @Test
    void choosingAPresetAppliesItAndTheNextLaunchRestoresIt() throws Exception {
        launch();

        onFx(() -> chooser.buttons().get(2).fire());

        assertThat(applied).containsExactly(QualityPreset.MEDIUM, QualityPreset.HIGH);
        assertThat(fromFx(() -> chooser.selected())).isEqualTo(QualityPreset.HIGH);
        persistence.flush();
        assertThat(Files.readString(settingsFile(), StandardCharsets.UTF_8)).contains("quality.preset=HIGH");

        relaunch();

        assertThat(fromFx(() -> chooser.selected())).isEqualTo(QualityPreset.HIGH);
        assertThat(applied).containsExactly(QualityPreset.HIGH);
    }

    @Test
    void choosingThePresetAlreadyChosenChangesNothing() throws Exception {
        launch();

        onFx(() -> chooser.buttons().get(1).fire());

        assertThat(applied).containsExactly(QualityPreset.MEDIUM);
        assertThat(fromFx(() -> chooser.selected())).isEqualTo(QualityPreset.MEDIUM);
    }

    @Test
    void anUnknownSavedPresetFallsBackToMedium() throws Exception {
        Files.writeString(settingsFile(), "#andysdice settings.properties 1\nquality.preset=Ultra\n",
                StandardCharsets.UTF_8);

        launch();

        assertThat(fromFx(() -> chooser.selected())).isEqualTo(QualityPreset.MEDIUM);
        assertThat(applied).containsExactly(QualityPreset.MEDIUM);
        assertThat(persistenceNotices).as("a bad value is not a bad file").isEmpty();
    }

    @Test
    void aSavedPresetIsReadIgnoringCase() throws Exception {
        Files.writeString(settingsFile(), "#andysdice settings.properties 1\nquality.preset=low\n",
                StandardCharsets.UTF_8);

        launch();

        assertThat(fromFx(() -> chooser.selected())).isEqualTo(QualityPreset.LOW);
    }

    @Test
    void theSlowGpuNoticeSuggestsALowerPresetButNeverChangesIt() throws Exception {
        launch();
        onFx(() -> chooser.buttons().get(2).fire());
        SlowGpuWatch watch = new SlowGpuWatch(session.notices()::post, chooser::selected);

        onFx(() -> {
            watch.showing("a Throw");
            for (int i = 0; i < 40; i++) {
                watch.frame(30);
            }
            watch.showing(null);
        });

        assertThat(fromFx(() -> session.notices().all().stream().map(Notice::detail).toList()))
                .singleElement().asString().contains("Medium Quality Preset");
        assertThat(fromFx(() -> chooser.selected())).isEqualTo(QualityPreset.HIGH);
        assertThat(applied).containsExactly(QualityPreset.MEDIUM, QualityPreset.HIGH);
        persistence.flush();
        assertThat(Files.readString(settingsFile(), StandardCharsets.UTF_8)).contains("quality.preset=HIGH");
    }

    /** Starts this launch's persistence and puts the chooser in the Settings tab, as the app does. */
    private void launch() throws Exception {
        persistence = new Persistence(dataFolder, persistenceNotices::add);
        settings = new SettingsStore(persistence);
        onFx(() -> chooser = QualityPresetChooser.attach(ui(), settings, applied::add));
    }

    /** Ends this launch and starts the next over the same folder, in a fresh Roll UI's Settings tab. */
    private void relaunch() throws Exception {
        persistence.close();
        applied.clear();
        persistence = new Persistence(dataFolder, persistenceNotices::add);
        settings = new SettingsStore(persistence);
        onFx(() -> chooser = QualityPresetChooser.attach(new RollUi(new javafx.scene.layout.Region()), settings,
                applied::add));
    }

    private Path settingsFile() {
        return dataFolder.resolve("settings.properties");
    }

    /** Every control under a node a Player can operate (not its labels), in scene-graph order. */
    private static List<Control> controlsIn(Node node) {
        List<Control> found = new java.util.ArrayList<>();
        collect(node, found);
        return found;
    }

    private static void collect(Node node, List<Control> found) {
        if (node instanceof Control control) {
            if (!(control instanceof javafx.scene.control.Label)) {
                found.add(control);
            }
            // A control's own skin parts aren't separate choices.
            return;
        }
        if (node instanceof javafx.scene.Parent parent) {
            parent.getChildrenUnmodifiable().forEach(child -> collect(child, found));
        }
    }

    /**
     * Whether a section is in the Settings tab's content: in the scroll pane there, which holds the tab's settings
     * pane. (Walking up the parents won't do: an unselected tab's scroll pane has no skin yet, so its content has
     * no parent.)
     */
    private boolean isInSettingsTab(Node section) {
        javafx.scene.layout.Pane content = (javafx.scene.layout.Pane) ui().settingsTab().getContent();
        return content.getChildren().stream()
                .filter(javafx.scene.control.ScrollPane.class::isInstance)
                .map(child -> ((javafx.scene.control.ScrollPane) child).getContent())
                .anyMatch(pane -> pane instanceof SettingsPane settingsPane
                        && settingsPane.getChildren().contains(section));
    }
}
