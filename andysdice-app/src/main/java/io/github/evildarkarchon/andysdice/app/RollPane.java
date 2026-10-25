package io.github.evildarkarchon.andysdice.app;

import io.github.evildarkarchon.andysdice.render.FrameView;
import java.util.Objects;
import java.util.function.Consumer;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;

/**
 * The walking skeleton's window content: the Roll Expression box above the Tray, and the result strip below
 * it. Enter in the box rolls what it holds. Ticket #48 replaces this with Layout C (spec §3.2).
 *
 * <p>Thread contract: a JavaFX node, used on the JavaFX Application Thread.
 */
final class RollPane extends BorderPane {

    private final TextField expressionBox = new TextField();
    private final Label resultStrip = new Label();

    /**
     * Lays out the box, the Tray and the strip.
     *
     * @param tray the view the Tray is rendered into
     * @param onRoll told what the box holds each time Enter is pressed in it
     */
    RollPane(FrameView tray, Consumer<String> onRoll) {
        Objects.requireNonNull(onRoll, "onRoll");
        expressionBox.setPromptText("4d6rr1kh3");
        expressionBox.setOnAction(event -> onRoll.accept(expressionBox.getText()));
        BorderPane.setMargin(expressionBox, new Insets(8));
        BorderPane.setMargin(resultStrip, new Insets(8));
        setTop(expressionBox);
        setCenter(tray);
        setBottom(resultStrip);
    }

    /**
     * Returns the Roll Expression box.
     *
     * @return the text field Enter rolls from
     */
    TextField expressionBox() {
        return expressionBox;
    }

    /**
     * Returns the result strip.
     *
     * @return the label under the Tray
     */
    Label resultStrip() {
        return resultStrip;
    }

    /**
     * Shows where the Roll stands in the result strip.
     *
     * @param status the current status
     */
    void showStatus(RollStatus status) {
        resultStrip.setText(status.text());
    }
}
