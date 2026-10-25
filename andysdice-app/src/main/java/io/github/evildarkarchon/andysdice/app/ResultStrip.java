package io.github.evildarkarchon.andysdice.app;

import java.util.Objects;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;

/**
 * The result strip under the Tray (spec §3.2 "Layout"). In the shell it shows one line for the Roll's status: the
 * first-run hint, "Rolling…" while Dice move, the Roll Result at rest, or why nothing was rolled. Ticket #49 builds
 * the Breakdown here (the large total, a block per term with its Dice as chips, and the expression, time and
 * Throws on the right); {@link #text()} stays the strip's plain reading for tests.
 *
 * <p>Thread contract: a JavaFX node, used on the JavaFX Application Thread.
 */
final class ResultStrip extends StackPane {

    private final Label message = new Label();

    /** Creates an empty strip. */
    ResultStrip() {
        getStyleClass().add("result-strip");
        message.getStyleClass().add("result-message");
        message.setWrapText(true);
        setAlignment(Pos.CENTER_LEFT);
        setMinHeight(RollUi.RESULT_STRIP_HEIGHT);
        setPrefHeight(RollUi.RESULT_STRIP_HEIGHT);
        getChildren().add(message);
    }

    /**
     * Shows where the Roll stands.
     *
     * @param status the current status
     */
    void show(RollStatus status) {
        Objects.requireNonNull(status, "status");
        message.setText(status.text());
    }

    /**
     * Returns what the strip says, as plain text.
     *
     * @return the status line shown
     */
    String text() {
        return message.getText();
    }

    /**
     * Returns the label the status line is drawn in.
     *
     * @return the message label
     */
    Label message() {
        return message;
    }
}
