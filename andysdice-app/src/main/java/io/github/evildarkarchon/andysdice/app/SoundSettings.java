package io.github.evildarkarchon.andysdice.app;

import io.github.evildarkarchon.andysdice.sound.SoundOutput;
import java.util.Objects;
import javafx.geometry.Pos;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * The Settings tab's Sound section (spec §3.2 "Settings tab", ADR-0012 "Controls"): mute, and volume as a
 * percentage, unmuted at 70% by default. Both are saved in {@code settings.properties} as {@link Clatter#MUTED} and
 * {@link Clatter#VOLUME} and applied to the sound output at once, to sound already playing too. Both are ordinary
 * focus-traversable controls: Space toggles mute and the arrow keys move the volume by
 * {@value #VOLUME_STEP} points. Mute has no keyboard shortcut, because the Roll Expression box is always editable.
 *
 * <p>Thread contract: made and used on the JavaFX Application Thread.
 */
final class SoundSettings {

    /** How far one arrow key press moves the volume, in percentage points. */
    static final int VOLUME_STEP = 5;

    private final VBox node;
    private final CheckBox mute = new CheckBox("Mute sound");
    private final Slider volume = new Slider(0, 100, Clatter.VOLUME.fallback());
    private final Label volumeText = new Label();

    private SoundSettings(SettingsPane pane, SettingsStore settings, SoundOutput output) {
        Label volumeLabel = new Label("Volume");
        volumeLabel.setLabelFor(volume);
        volume.setAccessibleText("Volume");
        volume.setBlockIncrement(VOLUME_STEP);
        volume.setMajorTickUnit(10);
        // A click on the track glides the thumb; with reduced motion on it jumps (#62).
        ReducedMotion.instantTrackClicks(volume);
        HBox.setHgrow(volume, Priority.ALWAYS);
        // Wide enough for "100%", so the slider doesn't shift as the number grows.
        volumeText.setMinWidth(38);
        HBox volumeRow = new HBox(8, volumeLabel, volume, volumeText);
        volumeRow.setAlignment(Pos.CENTER_LEFT);
        node = pane.addSection("Sound", mute, volumeRow);
        node.getStyleClass().add("sound-settings");

        // The saved values first, then the listeners, so loading them writes nothing back.
        boolean muted = settings.get(Clatter.MUTED);
        int percent = settings.get(Clatter.VOLUME);
        mute.setSelected(muted);
        volume.setValue(percent);
        showVolume(percent);
        output.setMuted(muted);
        output.setVolume(percent / 100.0);
        mute.selectedProperty().addListener((property, was, now) -> {
            output.setMuted(now);
            settings.set(Clatter.MUTED, now);
        });
        volume.valueProperty().addListener((property, was, now) -> {
            // A drag moves through fractions; what is heard and saved is the whole percentage shown.
            int whole = (int) Math.round(now.doubleValue());
            showVolume(whole);
            output.setVolume(whole / 100.0);
            settings.set(Clatter.VOLUME, whole);
        });
    }

    /**
     * Builds the Sound section, applies the saved mute and volume to the output, and puts the section in the
     * Settings tab.
     *
     * @param ui Layout C, whose Settings tab gets the section
     * @param settings the live settings, where mute and volume are read and saved
     * @param output the sound output they control
     * @return the section
     */
    static SoundSettings attach(RollUi ui, SettingsStore settings, SoundOutput output) {
        // The tab's one column of sections (#60's SettingsPane); Sound goes after whatever is there already.
        return new SoundSettings(SettingsPane.of(ui), Objects.requireNonNull(settings, "settings"),
                Objects.requireNonNull(output, "output"));
    }

    private void showVolume(int percent) {
        volumeText.setText(percent + "%");
    }

    /**
     * Returns the section.
     *
     * @return its root, in the Settings tab
     */
    VBox node() {
        return node;
    }

    /**
     * Returns the mute control.
     *
     * @return the check box, selected while muted
     */
    CheckBox mute() {
        return mute;
    }

    /**
     * Returns the volume control.
     *
     * @return the slider, 0 to 100
     */
    Slider volume() {
        return volume;
    }

    /**
     * Returns the volume as shown beside the slider.
     *
     * @return the label, e.g. "70%"
     */
    Label volumeText() {
        return volumeText;
    }
}
