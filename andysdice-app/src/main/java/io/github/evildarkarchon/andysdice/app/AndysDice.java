package io.github.evildarkarchon.andysdice.app;

import io.github.evildarkarchon.andysdice.core.playback.PlaybackClock;
import io.github.evildarkarchon.andysdice.physics.JoltStartupException;
import io.github.evildarkarchon.andysdice.render.GlUnavailableException;
import io.github.evildarkarchon.andysdice.sound.LineSink;
import io.github.evildarkarchon.andysdice.sound.SoundOutput;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;

/**
 * Andy's Dice: the JavaFX application. Its window is Layout C ({@link RollUi}): the sidebar, the toolbar with the
 * Roll Expression box, the Tray and the result strip; typing a Roll Expression and pressing Enter (or Roll)
 * Throws its Dice with real Jolt and plays the whole chain of Throws back on the playback clock, which JavaFX's
 * pulse advances at 60 Hz.
 *
 * <p>{@link Main} launches it. Without Jolt's native or an OpenGL 3.3 core context it logs why, naming the
 * requirement and the detected renderer and driver (ADR-0002's fail-fast), shows the same in the failure panel,
 * and the process exits with {@link #STARTUP_FAILED} once the Player quits or closes the window. A failure later
 * in the session (spec §3.2 "Runtime failure") replaces the Tray with that panel, and the process then exits with
 * {@link #RUNTIME_FAILED}. With {@value SmokeTest#FLAG} it runs the {@link SmokeTest} once the window is up, and
 * either failure exits at once instead of waiting for Quit.
 */
public final class AndysDice extends Application {

    /** Exit status when Jolt or the GL context couldn't start. */
    static final int STARTUP_FAILED = 1;

    /** Exit status after a runtime failure ended the session (spec §3.2 "Runtime failure"). */
    static final int RUNTIME_FAILED = 3;

    private static final System.Logger LOG = System.getLogger(AndysDice.class.getName());

    // Handed from Main to the instance JavaFX creates, and back: launch() returns only after the toolkit stops.
    private static volatile Instant launched = Instant.now();
    private static volatile FailureGuard failures = new FailureGuard(null);
    private static volatile int exitStatus;
    private static volatile Path dataFolder;

    private Skeleton skeleton;
    private Persistence persistence;
    private WindowGeometry.Tracker geometry;
    private SoundOutput sound;

    /** Created by {@link Application#launch}. */
    public AndysDice() {}

    /**
     * Runs the application until it quits, on the calling thread.
     *
     * @param launchedAt when the process started, for the launch-to-window time in the log
     * @param data the data folder chosen for this run, where the persisted files live
     * @param args the command line
     * @param guard where failures that end the session go; it shows them in the failure panel once the window
     *     is up
     * @return the process's exit status
     */
    static int launch(Instant launchedAt, Path data, String[] args, FailureGuard guard) {
        launched = Objects.requireNonNull(launchedAt, "launchedAt");
        dataFolder = Objects.requireNonNull(data, "data");
        failures = Objects.requireNonNull(guard, "guard");
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
        // The persisted state first (spec §3.6): the window opens at the saved geometry. The one Notices goes to the
        // session too, so a file set aside while loading shows behind the bell.
        Notices notices = new Notices();
        persistence = new Persistence(dataFolder, notices::post);
        SettingsStore settings = new SettingsStore(persistence);
        // Spec §6: the Face Value, Release and sound variation generators are seeded independently from
        // SecureRandom.
        FailureGuard guard = failures;
        try {
            skeleton = Skeleton.start(clock, Pulses.ofPulse(new PulsePacer(clock)), Generators.fromSecureRandom(),
                    guard, notices);
        } catch (JoltStartupException | GlUnavailableException e) {
            // The message names what is missing; for GL, the 3.3 core requirement and the renderer, vendor and
            // version the driver reported (ADR-0002). The log has it, and so does the failure panel.
            LOG.log(Level.ERROR, "Start-up failed: " + e.getMessage(), e);
            // Set before the window opens: closing it is as much a way out as Quit, and exits non-zero too.
            exitStatus = STARTUP_FAILED;
            FailureScreen.startup(stage, Failure.startup(e, guard.logFile()), () -> quit(STARTUP_FAILED));
            if (smoke) {
                // The smoke test never waits on a person.
                quit(STARTUP_FAILED);
            }
            return;
        }
        LOG.log(Level.INFO, "Jolt Physics started; OpenGL context: " + skeleton.driver() + "; shaders compiled");
        // The Settings tab's Quality Preset (#60): the saved one (Medium at first run) goes to the renderer now, and
        // a new choice is saved and applied at once.
        QualityPresetChooser.attach(skeleton.pane(), settings, skeleton::setQualityPreset);
        // The Roll History (#55), loaded before the window shows; Rolls are dated by the wall clock in the system
        // time zone as it was at start-up.
        HistoryTab history = HistoryTab.install(skeleton.session(), persistence, Clock.systemDefaultZone());
        // The Saved Rolls (#56); History's menu gains Save as a Saved Roll….
        SavedTab.install(skeleton.session(), persistence, history);
        // The Player Files are read once, here (spec §3.3, §3.4): each one skipped is a Notice behind the bell, and
        // the Tray is drawn in the stored look before the window opens.
        skeleton.attachLook(LookCatalogue.load(dataFolder, notices::post), settings);

        // Sound (#59): optional, so it never fails the start. The audio thread loads the samples and tries the device
        // once; no device means silence and one Notice (the smoke test, on CI, runs that way and logs it).
        sound = Clatter.output(clock, skeleton.generators().soundVariation(), LineSink::open, notices);
        skeleton.attachSound(sound);
        SoundSettings.attach(skeleton.pane(), settings, sound);

        // Layout C, titled as spec §3.2's first run, at the saved geometry (or the first run's): shown, with focus in
        // the box.
        geometry = MainWindow.open(stage, skeleton.pane(), settings);
        LOG.log(Level.INFO, "Window shown " + Duration.between(launched, Instant.now()).toMillis()
                + " ms after launch");

        // From now on a failure that ends the session (spec §3.2 "Runtime failure") replaces the Tray with the
        // failure panel; one that came before the window was up is shown at once.
        guard.attach(failure -> {
            exitStatus = RUNTIME_FAILED;
            FailureScreen.show(skeleton.pane(), failure, () -> quit(RUNTIME_FAILED));
            if (smoke) {
                quit(RUNTIME_FAILED);
            }
        }, Platform::runLater);

        if (smoke) {
            SmokeTest.run(skeleton, this::quit);
        }
    }

    @Override
    public void stop() {
        // Closing the window has saved the geometry already; a quit that skipped that (the smoke test, a Quit
        // button) saves it here. Then every queued write finishes before the process exits.
        if (geometry != null) {
            geometry.save();
        }
        if (persistence != null) {
            persistence.close();
        }
        if (sound != null) {
            sound.close();
        }
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
