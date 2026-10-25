package io.github.evildarkarchon.andysdice.app;

import java.util.Objects;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;

/**
 * What a second launch shows (spec §3.6 "One instance"): while another copy holds the data folder's
 * {@link InstanceLock}, {@link Main} launches this instead of {@link AndysDice}. It says "Andy's Dice is already
 * running" in a small window of its own and exits once that is dismissed (OK, Enter, Esc or closing it), with
 * {@link #EXIT_STATUS}. It never talks to the running copy (ADR-0007).
 */
public final class AlreadyRunning extends Application {

    /** The message, as spec §3.6 words it. */
    static final String MESSAGE = "Andy's Dice is already running";

    /** The process's exit status after a second launch. */
    static final int EXIT_STATUS = 3;

    /** The style class of the OK button. */
    static final String OK_CLASS = "already-running-ok";

    /** Created by {@link Application#launch}. */
    public AlreadyRunning() {}

    /**
     * Shows the message until it is dismissed, on the calling thread, then returns.
     *
     * @param args the command line
     * @return {@link #EXIT_STATUS}
     */
    static int run(String[] args) {
        Application.launch(AlreadyRunning.class, args);
        return EXIT_STATUS;
    }

    @Override
    public void start(Stage stage) {
        show(stage, Platform::exit);
    }

    /**
     * Shows the message in a window: titled like the app, themed like it, with the OK button focused.
     *
     * @param stage the window to show it in
     * @param onDismissed run once, on the JavaFX thread, when the window is dismissed by any means
     */
    static void show(Stage stage, Runnable onDismissed) {
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(onDismissed, "onDismissed");
        Label message = new Label(MESSAGE);
        Button ok = new Button("OK");
        ok.getStyleClass().add(OK_CLASS);
        // Enter and Esc both dismiss it: there is nothing else to choose.
        ok.setDefaultButton(true);
        ok.setCancelButton(true);
        ok.setOnAction(event -> stage.close());
        VBox root = new VBox(16, message, ok);
        root.setAlignment(Pos.CENTER);
        root.setStyle("-fx-padding: 24 32 20 32;");
        root.getStyleClass().add(Stylesheet.ROOT_CLASS);
        root.getStylesheets().add(Stylesheet.uri());
        Scene scene = new Scene(root);
        scene.setFill(Palette.BACKGROUND);
        stage.setTitle(MainWindow.TITLE);
        stage.setScene(scene);
        stage.setResizable(false);
        boolean[] dismissed = {false};
        stage.addEventHandler(WindowEvent.WINDOW_HIDDEN, event -> {
            if (!dismissed[0]) {
                dismissed[0] = true;
                onDismissed.run();
            }
        });
        stage.show();
        stage.centerOnScreen();
        ok.requestFocus();
    }
}
