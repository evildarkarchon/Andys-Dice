package io.github.evildarkarchon.andysdice.app;

import io.github.evildarkarchon.andysdice.render.QualityPreset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.VBox;

/**
 * The Settings tab's Quality Preset (spec §3.2, ADR-0014): one choice of Low, Medium (the default) or High, as three
 * radio buttons, with no Custom and no per-lever control. The choice is the Player's persisted setting
 * ({@link #PRESET} in {@code settings.properties}; an unknown value reads as Medium): it is applied at start-up and
 * at once whenever the Player picks another, and nothing else ever changes it (the slow-GPU Notice only suggests a
 * lower one).
 *
 * <p>Thread contract: made and used on the JavaFX Application Thread.
 */
final class QualityPresetChooser {

    /** The persisted preset: {@code quality.preset}, written by name; unknown or unreadable values give Medium. */
    static final Setting<QualityPreset> PRESET = Setting.choice("quality.preset", QualityPreset.class,
            QualityPreset.DEFAULT);

    private final SettingsStore settings;
    private final Consumer<QualityPreset> apply;
    private final ToggleGroup group = new ToggleGroup();
    private final List<RadioButton> buttons = new ArrayList<>();
    private final VBox section;
    private QualityPreset selected;

    private QualityPresetChooser(SettingsPane pane, SettingsStore settings, Consumer<QualityPreset> apply) {
        this.settings = settings;
        this.apply = apply;
        selected = settings.get(PRESET);
        VBox choices = new VBox();
        choices.setSpacing(4);
        for (QualityPreset preset : QualityPreset.values()) {
            RadioButton button = new RadioButton(preset.displayName());
            button.setToggleGroup(group);
            button.setUserData(preset);
            button.setAccessibleText("Quality Preset " + preset.displayName());
            button.setSelected(preset == selected);
            buttons.add(button);
        }
        choices.getChildren().addAll(buttons);
        section = pane.addSection("Quality Preset", choices);
        // Listened to after the saved choice is selected, so start-up neither saves nor applies it twice.
        group.selectedToggleProperty().addListener((property, was, now) -> {
            if (now != null) {
                choose((QualityPreset) now.getUserData());
            }
        });
    }

    /**
     * Puts the Quality Preset section in a Roll UI's Settings tab, selects the saved preset (Medium on a first run or
     * when the saved value is unknown) and applies it once.
     *
     * @param ui the Roll UI
     * @param settings the live settings, which the choice is read from and saved to
     * @param apply told the preset at start-up and each time the Player picks another (the renderer's preset and the
     *     render-target cap)
     * @return the chooser
     */
    static QualityPresetChooser attach(RollUi ui, SettingsStore settings, Consumer<QualityPreset> apply) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(apply, "apply");
        QualityPresetChooser chooser = new QualityPresetChooser(SettingsPane.of(ui), settings, apply);
        apply.accept(chooser.selected);
        return chooser;
    }

    /**
     * Returns the chosen preset.
     *
     * @return the preset in force
     */
    QualityPreset selected() {
        return selected;
    }

    /**
     * Returns the three choices.
     *
     * @return the radio buttons for Low, Medium and High, in that order
     */
    List<RadioButton> buttons() {
        return List.copyOf(buttons);
    }

    /**
     * Returns the Settings tab section the choices are in.
     *
     * @return the section, its title then the choices
     */
    VBox section() {
        return section;
    }

    /** Applies and saves a preset the Player picked, unless it is the one in force. */
    private void choose(QualityPreset preset) {
        if (preset == selected) {
            return;
        }
        selected = preset;
        settings.set(PRESET, preset);
        apply.accept(preset);
    }
}
