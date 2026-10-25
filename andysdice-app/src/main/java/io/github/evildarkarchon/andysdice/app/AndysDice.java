package io.github.evildarkarchon.andysdice.app;

import io.github.evildarkarchon.andysdice.core.playback.PlaybackClock;
import io.github.evildarkarchon.andysdice.physics.JoltStartupException;
import io.github.evildarkarchon.andysdice.render.GlUnavailableException;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;

/**
 * Andy's Dice: the JavaFX application. In the walking skeleton (ticket #34) it shows the Roll Expression box,
 * the Tray and the result strip; typing a Roll Expression and pressing Enter Throws its Dice with real Jolt and
 * plays the whole chain of Throws back on the playback clock, which JavaFX's pulse advances at 60 Hz.
 *
 * <p>{@link Main} launches it. Without Jolt's native or an OpenGL 3.3 core context it logs why, naming the
 * requirement and the detected renderer and driver (ADR-0002's fail-fast), and the process exits with
 * {@link #STARTUP_FAILED}. With {@value SmokeTest#FLAG} it runs the {@link SmokeTest} once the window is up.
 */
public final class AndysDice extends Application {

    /** Exit status when Jolt or the GL context couldn't start. */
    static final int STARTUP_FAILED = 1;

    private static final System.Logger LOG = System.getLogger(AndysDice.class.getName());

    // Handed from Main to the instance JavaFX creates, and back: launch() returns only after the toolkit stops.
    private static volatile Instant launched = Instant.now();
    private static volatile int exitStatus;

    private Skeleton skeleton;

    /** Created by {@link Application#launch}. */
    public AndysDice() {}

    /**
     * Runs the application until it quits, on the calling thread.
     *
     * @param launchedAt when the process started, for the launch-to-window time in the log
     * @param args the command line
     * @return the process's exit status
     */
    static int launch(Instant launchedAt, String[] args) {
        launched = Objects.requireNonNull(launchedAt, "launchedAt");
        exitStatus = 0;
        Application.launch(AndysDice.class, args);
        return exitStatus;
    }

    @Override
    public void start(Stage stage) {
        boolean smoke = getParameters().getRaw().contains(SmokeTest.FLAG);
        PlaybackClock clock = new PlaybackClock();
        if (smoke) {
            clock.setSpeed(SmokeTest.SPEED);
        }
        // Spec §6: the Face Value, Release and sound variation generators are seeded independently from
        // SecureRandom.
        try {
            skeleton = Skeleton.start(clock, Pulses.ofPulse(new PulsePacer(clock)), Generators.fromSecureRandom());
        } catch (JoltStartupException | GlUnavailableException e) {
            // The message names what is missing; for GL, the 3.3 core requirement and the renderer, vendor and
            // version the driver reported (ADR-0002). #53 shows it in the failure panel; until then the log has it.
            LOG.log(Level.ERROR, "Start-up failed: " + e.getMessage(), e);
            quit(STARTUP_FAILED);
            return;
        }
        LOG.log(Level.INFO, "Jolt Physics started; OpenGL context: " + skeleton.driver() + "; shaders compiled");

        stage.setTitle("Andy's Dice");
        stage.setScene(new Scene(skeleton.pane(), 1280, 800));
        stage.setMinWidth(960);
        stage.setMinHeight(600);
        stage.show();
        LOG.log(Level.INFO, "Window shown " + Duration.between(launched, Instant.now()).toMillis()
                + " ms after launch");
        skeleton.pane().expressionBox().requestFocus();

        if (smoke) {
            SmokeTest.run(skeleton, this::quit);
        }
    }

    @Override
    public void stop() {
        if (skeleton != null) {
            skeleton.close();
        }
    }

    /** Stops the toolkit, so {@link #launch} returns and {@link Main} exits with {@code status}. */
    private void quit(int status) {
        exitStatus = status;
        Platform.exit();
    }
}
