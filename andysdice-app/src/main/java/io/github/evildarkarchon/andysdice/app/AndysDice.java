package io.github.evildarkarchon.andysdice.app;

import io.github.evildarkarchon.andysdice.core.playback.PlaybackClock;
import io.github.evildarkarchon.andysdice.physics.JoltStartupException;
import io.github.evildarkarchon.andysdice.render.GlUnavailableException;
import java.security.SecureRandom;
import java.util.SplittableRandom;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;

/**
 * Andy's Dice: the JavaFX application. In the walking skeleton (ticket #34) it shows the Roll Expression box,
 * the Tray and the result strip; typing {@code 1d6} and pressing Enter Throws the cube with real Jolt and plays
 * it back on the playback clock, which JavaFX's pulse advances at 60 Hz.
 *
 * <p>Without Jolt's native or an OpenGL 3.3 core context it prints why and exits with status 1.
 */
public final class AndysDice extends Application {

    private Skeleton skeleton;

    /** Created by {@link Application#launch}. */
    public AndysDice() {}

    /**
     * Launches the app.
     *
     * @param args ignored
     */
    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        PlaybackClock clock = new PlaybackClock();
        // Spec §6: the Face Value and Release generators are seeded independently from SecureRandom.
        SecureRandom seeds = new SecureRandom();
        try {
            skeleton = Skeleton.start(clock, Pulses.ofPulse(new PulsePacer(clock)),
                    new SplittableRandom(seeds.nextLong()), new SplittableRandom(seeds.nextLong()));
        } catch (JoltStartupException | GlUnavailableException e) {
            System.err.println(e.getMessage());
            Platform.exit();
            // The JavaFX launcher would otherwise exit 0 once the toolkit stops.
            System.exit(1);
            return;
        }
        stage.setTitle("Andy's Dice");
        stage.setScene(new Scene(skeleton.pane(), 1280, 800));
        stage.setMinWidth(960);
        stage.setMinHeight(600);
        stage.show();
        skeleton.pane().expressionBox().requestFocus();
    }

    @Override
    public void stop() {
        if (skeleton != null) {
            skeleton.close();
        }
    }
}
