package io.github.evildarkarchon.andysdice.app;

import java.util.Objects;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;

/**
 * The Settings tab's content (spec §3.2 "Settings tab"): a column of titled sections, the Quality Preset's (#60) and
 * Sound's (#59), each added by the piece that owns it. {@link #of(RollUi)} finds the pane in the tab or puts one
 * there, so whichever piece is wired first makes it and the order the sections appear in is the order they are
 * added.
 *
 * <p>Thread contract: a JavaFX node, used on the JavaFX Application Thread.
 */
final class SettingsPane extends VBox {

    /** The style class of the pane; each section is {@code settings-section}, its title {@code settings-heading}. */
    static final String STYLE_CLASS = "settings-pane";

    private SettingsPane() {
        getStyleClass().add(STYLE_CLASS);
        setSpacing(18);
        setStyle("-fx-padding: 14 14 14 14;");
    }

    /**
     * Returns the Settings tab's pane, putting an empty one in the tab first if it has none yet.
     *
     * @param ui the Roll UI whose Settings tab it is
     * @return the tab's one settings pane
     */
    static SettingsPane of(RollUi ui) {
        Objects.requireNonNull(ui, "ui");
        // RollUi gives each tab an empty StackPane to fill (#48); the pane goes in it, inside a scroll pane so a
        // short window can still reach every section.
        Pane content = (Pane) ui.settingsTab().getContent();
        for (Node child : content.getChildren()) {
            if (child instanceof ScrollPane scroll && scroll.getContent() instanceof SettingsPane pane) {
                return pane;
            }
        }
        SettingsPane pane = new SettingsPane();
        ScrollPane scroll = new ScrollPane(pane);
        scroll.setFitToWidth(true);
        // The sidebar's own surface shows through, as in the other tabs.
        scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        content.getChildren().add(scroll);
        return pane;
    }

    /**
     * Adds a titled section at the end of the pane.
     *
     * @param title the section's title, e.g. "Quality Preset"
     * @param children what the section holds, under its title
     * @return the section, a column holding the title and then the children
     */
    VBox addSection(String title, Node... children) {
        Label heading = new Label(Objects.requireNonNull(title, "title"));
        heading.getStyleClass().add("settings-heading");
        heading.setStyle("-fx-font-weight: bold;");
        VBox section = new VBox(heading);
        section.getStyleClass().add("settings-section");
        section.setSpacing(6);
        section.getChildren().addAll(children);
        getChildren().add(section);
        return section;
    }
}
