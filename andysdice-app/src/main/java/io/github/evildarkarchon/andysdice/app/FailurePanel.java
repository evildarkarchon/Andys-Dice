package io.github.evildarkarchon.andysdice.app;

import java.util.Map;
import java.util.Objects;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.Clipboard;
import javafx.scene.input.DataFormat;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

/**
 * The runtime-failure panel (spec §3.2 "Runtime failure"), shown in place of the Tray once the session has failed:
 * "Andy's Dice hit a problem and has to close", the one-line cause, the log file's path, and two buttons, Copy
 * details and Quit. It is drawn by JavaFX alone, since the GL renderer may be what failed, and it uses only the
 * {@link Palette} pairs the rest of the Roll UI draws (text and muted text on the surface, buttons as everywhere).
 *
 * <p>Thread contract: a JavaFX node, made and used on the JavaFX Application Thread.
 */
final class FailurePanel extends VBox {

    /** The style class {@link FailureScreen#panelOf} finds the panel by. */
    static final String STYLE_CLASS = "failure-panel";

    /** The widest the panel's text runs, logical px, so a long cause wraps into a readable column. */
    private static final double MAX_WIDTH = 640;

    private final Label heading = new Label(Failure.HEADING);
    private final Label cause = new Label();
    private final Label logPath = new Label();
    private final Button copyButton = new Button("Copy details");
    private final Button quitButton = new Button("Quit");

    /**
     * Lays out the panel for a failure.
     *
     * @param failure what ended the session
     * @param quit what Quit does: ends the app with a non-zero exit status
     */
    FailurePanel(Failure failure, Runnable quit) {
        Objects.requireNonNull(failure, "failure");
        Objects.requireNonNull(quit, "quit");
        getStyleClass().add(STYLE_CLASS);
        setBackground(new Background(new BackgroundFill(Palette.SURFACE, null, null)));
        setPadding(new Insets(28, 32, 28, 32));
        setSpacing(14);
        setAlignment(Pos.CENTER_LEFT);
        setMaxWidth(MAX_WIDTH);
        setMaxHeight(USE_PREF_SIZE);

        heading.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");
        heading.setWrapText(true);
        cause.setText(failure.cause());
        cause.setWrapText(true);
        cause.setStyle("-fx-font-size: 15px;");
        logPath.setText(failure.logLine());
        logPath.setWrapText(true);
        // Muted, as secondary text is everywhere in the Roll UI; the stylesheet's .label rule would otherwise
        // override a plain setTextFill.
        logPath.setStyle("-fx-text-fill: " + css(Palette.TEXT_MUTED) + ";");

        copyButton.setOnAction(event -> Clipboard.getSystemClipboard()
                .setContent(Map.of(DataFormat.PLAIN_TEXT, failure.details())));
        quitButton.setOnAction(event -> quit.run());
        // Enter quits: Quit is the only way on from here.
        quitButton.setDefaultButton(true);
        HBox buttons = new HBox(8, copyButton, quitButton);
        buttons.setAlignment(Pos.CENTER_LEFT);

        getChildren().addAll(heading, cause, logPath, buttons);
    }

    /**
     * Returns the heading.
     *
     * @return the label reading {@value Failure#HEADING}
     */
    Label heading() {
        return heading;
    }

    /**
     * Returns the cause.
     *
     * @return the label holding the failure's one-line cause
     */
    Label cause() {
        return cause;
    }

    /**
     * Returns the line naming the log file.
     *
     * @return the label holding the log file's path
     */
    Label logPath() {
        return logPath;
    }

    /**
     * Returns Copy details.
     *
     * @return the button that copies the cause, the log path and the stack trace
     */
    Button copyButton() {
        return copyButton;
    }

    /**
     * Returns Quit.
     *
     * @return the button that ends the app
     */
    Button quitButton() {
        return quitButton;
    }

    /** A palette colour as CSS {@code #rrggbb}. */
    private static String css(Color colour) {
        return String.format(java.util.Locale.ROOT, "#%02x%02x%02x", Math.round(colour.getRed() * 255),
                Math.round(colour.getGreen() * 255), Math.round(colour.getBlue() * 255));
    }
}
