package io.github.evildarkarchon.andysdice.app;

import io.github.evildarkarchon.andysdice.core.playback.PlaybackClock;
import io.github.evildarkarchon.andysdice.core.tray.BuiltInTrays;
import io.github.evildarkarchon.andysdice.render.HeadlessPlatform;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.robot.Robot;
import javafx.stage.PopupWindow;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

/**
 * The one base class of the Roll UI's headless tests (ADR-0011). Each test gets the real app window, opened by
 * {@link MainWindow#open} around the real Layout C {@link RollUi}, wired by the real {@link RollSession} to the real
 * {@code core} pipeline (parser, {@code ThrowPlanner}, evaluator), with three stand-ins:
 * <ul>
 *   <li>the fake simulator ({@link ScriptedSimulator}) in place of Jolt, run by {@link HeldPhysics} on the JavaFX
 *       thread as soon as a Roll is submitted (call {@code physics.setHolding(true)} to keep it pre-simulating);</li>
 *   <li>the stand-in tray ({@link StandInTray}) in place of the GL renderer behind the Tray seam, reporting Throw
 *       phases and rest on the injected clock;</li>
 *   <li>the injected playback clock ({@link #clock}), which only {@link #advance} moves: tests never sleep and
 *       JavaFX's own pulse never plays a Roll ({@link HandPulses}).</li>
 * </ul>
 * Face Values come from {@link #faces}: script the ones a test checks ({@code faces.then(1, 4, 5)}) before it
 * rolls; any draw past the script comes from a fixed seed.
 *
 * <p>The persisted state is real too: a {@link Persistence} writer over the test's data folder, with the History
 * tab ({@link #history}) over it, dated by a {@link #wallClock} the test sets. {@link #restartApp} quits and opens
 * the app again on the same folder, as the next run would.
 *
 * <p><b>The headless flags are {@link HeadlessPlatform}'s.</b> This class starts the toolkit through it, once per
 * test JVM, on {@code glass.platform=Headless} and Prism's {@code sw} pipeline, as render's tests do; that render class
 * is the one owner of those flags (ADR-0011). If a JavaFX update breaks or removes the headless platform, delete the
 * two properties there and the same tests run in real windows on the Windows runner. This class also points the data-folder override property
 * ({@value #DATA_FOLDER_PROPERTY}) at a fresh temp folder for every test, so nothing a test persists survives it.
 *
 * <p><b>Threads.</b> JUnit runs on its own thread; everything touching JavaFX goes through {@link #onFx} (or {@link #fromFx} for a result), which runs
 * on the JavaFX Application Thread and waits. Work the app posts with {@code Platform.runLater} (a pre-simulated
 * Roll arriving, for one) runs before the next {@code onFx} block, so successive blocks see it. No GL is used, so
 * these tests run in both CI jobs; a test that needs GL (WalkingSkeletonTest) is {@code gl}-tagged and uses only the
 * static helpers.
 */
abstract class HeadlessUiTest {

    /**
     * The app's data-folder override system property (spec §3.6, ADR-0007), which {@link DataFolder} reads.
     * Set by this class, never by a test.
     */
    static final String DATA_FOLDER_PROPERTY = DataFolder.OVERRIDE_PROPERTY;

    /** One 60 Hz frame, the step {@link #advance} plays in. */
    static final double FRAME = 1.0 / 60;

    /** How long {@link #onFx} waits for the JavaFX thread before failing the test. */
    private static final Duration FX_TIMEOUT = Duration.ofSeconds(30);

    /** The longest {@link #playToRest} plays before giving up: far past any scripted chain. */
    private static final double PLAY_LIMIT_SECONDS = 120;

    /** A fresh data folder for this test; JUnit deletes it afterwards. */
    @TempDir
    Path dataFolder;

    /** The injected playback clock; only {@link #advance} moves it. */
    protected PlaybackClock clock;
    /** The fake simulator the planner throws with. */
    protected ScriptedSimulator simulator;
    /** Runs pre-simulation on the JavaFX thread; not holding unless a test says so. */
    protected HeldPhysics physics;
    /** The Face Values drawn, scripted first and seeded after. */
    protected ScriptedRandom faces;
    /** What advances the clock in the app; here it only records whether playback wants pulses. */
    protected HandPulses pulses;
    /** The stand-in tray. */
    protected StandInTray tray;
    /** The app under test: controller and Roll UI. */
    protected RollSession session;
    /** The app window. */
    protected Stage stage;
    /**
     * The wall clock the Roll History stamps Rolls with and dates its day headers by (#55): 2026-10-25 12:00 UTC
     * at the start of every test, in UTC, and moved only by the test.
     */
    protected SettableClock wallClock;
    /** The persisted files' writer, over {@link #dataFolder}; closed after each test and on {@link #restartApp}. */
    protected Persistence persistence;
    /** The History tab (#55), over {@link #persistence} and {@link #wallClock}. */
    protected HistoryTab history;
    /** The Saved tab (#56), over {@link #persistence}, its Save as a Saved Roll… on {@link #history}'s menu. */
    protected SavedTab saved;

    /** Where every test's wall clock starts: a Sunday. */
    static final Instant WALL_CLOCK_START = Instant.parse("2026-10-25T12:00:00Z");

    private String previousDataFolder;
    private Robot robot;

    /**
     * Starts JavaFX once per test JVM on its built-in headless platform, if no test has yet. Safe to call from any
     * test class, including ones that don't extend this one.
     */
    @BeforeAll
    static void startToolkit() {
        // The headless choice itself (ADR-0011) is HeadlessPlatform's, shared with render's tests.
        HeadlessPlatform.start();
    }

    /** Opens the app window with its stand-ins, on a fresh data folder, as a first run sees it. */
    @BeforeEach
    final void openApp() throws Exception {
        previousDataFolder = System.setProperty(DATA_FOLDER_PROPERTY, dataFolder.toString());
        wallClock = new SettableClock(WALL_CLOCK_START, ZoneOffset.UTC);
        open();
    }

    /**
     * Closes the window and the persisted files' writer, as quitting the app does, then opens the app again on the
     * same data folder, wall clock and Face Value script, as the next run would: whatever was persisted is loaded
     * again, and a Roll still under way is lost.
     *
     * @throws Exception if the JavaFX thread failed or timed out
     */
    protected final void restartApp() throws Exception {
        close();
        open();
    }

    /** Builds the app's pieces and opens its window over {@link #dataFolder}. */
    private void open() throws Exception {
        clock = new PlaybackClock();
        simulator = new ScriptedSimulator();
        physics = new HeldPhysics(simulator);
        physics.setHolding(false);
        if (faces == null) {
            // Kept across a restart, so a script a test wrote before restarting still applies.
            faces = new ScriptedRandom().orElse(new SplittableRandom(48));
        }
        pulses = new HandPulses();
        Generators generators = new Generators(faces, new SplittableRandom(7), new SplittableRandom(9));
        onFx(() -> {
            tray = new StandInTray(clock);
            session = new RollSession(clock, pulses, physics, Platform::runLater, generators,
                    BuiltInTrays.defaultTray(), tray);
            // As AndysDice.start does: one writer over the data folder, its Notices behind the bell.
            persistence = new Persistence(dataFolder, session.notices()::post);
            history = HistoryTab.install(session, persistence, wallClock);
            saved = SavedTab.install(session, persistence, history);
            stage = new Stage();
            MainWindow.open(stage, session.ui());
        });
        // The window's size reaches the scene's layout on a pulse; start every test from that laid-out window.
        pulse(stage.getScene());
    }

    /**
     * Waits for JavaFX to run one pulse over a scene: CSS, layout (a new window or scene size included) and
     * rendering, as the toolkit does after any change. This waits on the toolkit, never on playback, which only
     * {@link #advance} moves.
     *
     * @param scene a showing scene
     * @throws Exception if the JavaFX thread failed, or no pulse ran within 30 s
     */
    static void pulse(Scene scene) throws Exception {
        CompletableFuture<Void> pulsed = new CompletableFuture<>();
        Runnable listener = () -> pulsed.complete(null);
        onFx(() -> {
            scene.addPostLayoutPulseListener(listener);
            Platform.requestNextPulse();
        });
        try {
            pulsed.get(FX_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } finally {
            // Removed afterwards, not from inside the pulse that is calling it.
            onFx(() -> scene.removePostLayoutPulseListener(listener));
        }
    }

    /** Closes the window and the writer, and restores the data-folder property. */
    @AfterEach
    final void closeApp() throws Exception {
        try {
            close();
        } finally {
            if (previousDataFolder == null) {
                System.clearProperty(DATA_FOLDER_PROPERTY);
            } else {
                System.setProperty(DATA_FOLDER_PROPERTY, previousDataFolder);
            }
        }
    }

    /** Closes the window, then the writer, which finishes every queued write first (as AndysDice.stop does). */
    private void close() throws Exception {
        try {
            if (stage != null) {
                onFx(stage::close);
            }
        } finally {
            if (persistence != null) {
                persistence.close();
            }
        }
    }

    /**
     * Returns the Roll UI under test.
     *
     * @return the window's Layout C root
     */
    protected final RollUi ui() {
        return session.ui();
    }

    /**
     * Returns the roll controller under test.
     *
     * @return the controller the box and the Roll button roll through
     */
    protected final RollController controller() {
        return session.controller();
    }

    /**
     * Returns the Queued Roll as the Player sees it: the expression on the "next: …" chip at the end of the toolbar.
     * Call it from the test thread.
     *
     * @return the Queued Roll's expression, stripped as the chip shows it, or empty while the chip is hidden
     * @throws Exception if the JavaFX thread failed or timed out
     */
    protected final Optional<String> queuedRoll() throws Exception {
        return fromFx(() -> {
            Label chip = session.entry().nextChip();
            return chip.isVisible()
                    ? Optional.of(chip.getText().substring(RollEntry.NEXT_PREFIX.length()))
                    : Optional.<String>empty();
        });
    }

    /**
     * Returns the public JavaFX robot for this test, made on first use. Call it on the JavaFX thread (inside
     * {@link #onFx}). Its key and mouse input goes to the focused window, as a Player's would.
     *
     * @return the robot
     */
    protected final Robot robot() {
        if (robot == null) {
            robot = new Robot();
        }
        return robot;
    }

    /**
     * Presses Enter in the Roll Expression box, as the Player does to roll. Call it on the JavaFX thread.
     */
    protected final void pressEnterInBox() {
        ui().expressionBox().fireEvent(
                new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
    }

    /**
     * Types an expression into an empty box and presses Enter, then lets the pre-simulated Roll arrive, so the
     * next {@link #advance} plays it. Call it from the test thread.
     *
     * @param expression the Roll Expression to roll
     * @throws Exception if the JavaFX thread failed or timed out
     */
    protected final void roll(String expression) throws Exception {
        onFx(() -> {
            ui().expressionBox().setText(expression);
            pressEnterInBox();
        });
        // The planned Roll comes back through Platform.runLater; this block runs after it.
        onFx(() -> {});
    }

    /**
     * Plays the clock forward in 60 Hz frames, as JavaFX's pulse would, on the JavaFX thread.
     *
     * @param seconds how much real time to play, rounded up to whole frames. The playback time is the same at the
     *     default ×1, and that many seconds times the clock's speed otherwise
     * @throws Exception if the JavaFX thread failed or timed out
     */
    protected final void advance(double seconds) throws Exception {
        onFx(() -> {
            for (double played = 0; played < seconds - 1e-9; played += FRAME) {
                clock.advance(FRAME);
            }
        });
    }

    /**
     * Plays frame by frame until no Roll is under way: the last Throw is at rest and its result shown, or the Roll
     * failed.
     *
     * @throws Exception if the JavaFX thread failed or timed out
     * @throws AssertionError if the Roll is still under way after two minutes of playback
     */
    protected final void playToRest() throws Exception {
        onFx(() -> {
            double start = clock.time();
            while (controller().underWay() && clock.time() - start < PLAY_LIMIT_SECONDS) {
                clock.advance(FRAME);
            }
        });
        if (fromFx(() -> controller().underWay())) {
            throw new AssertionError("the Roll was still under way after " + PLAY_LIMIT_SECONDS + " s of playback");
        }
    }

    /**
     * Returns every popup window showing now (the parse-error popup, a list popup, and so on), tooltips left out
     * since a robot's mouse can raise one by chance. Popups are windows of their own, so a scene snapshot or a
     * lookup in the main window never finds them. Call it on the JavaFX thread.
     *
     * @return the showing popups, in JavaFX's window order
     */
    static List<PopupWindow> showingPopups() {
        return Window.getWindows().stream()
                .filter(window -> window instanceof PopupWindow && !(window instanceof Tooltip) && window.isShowing())
                .map(PopupWindow.class::cast)
                .toList();
    }

    /** Code run on the JavaFX thread that may throw. */
    @FunctionalInterface
    interface FxAction {
        /**
         * Runs the action.
         *
         * @throws Exception whatever it throws
         */
        void run() throws Exception;
    }

    /**
     * Runs {@code task} on the JavaFX Application Thread and waits for its result.
     *
     * @param task what to run
     * @param <T> the result type
     * @return the task's result
     * @throws Exception whatever the task threw, or a timeout after 30 s
     */
    static <T> T fromFx(Callable<T> task) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                result.complete(task.call());
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        try {
            return result.get(FX_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            // Rethrow what the task threw, so an assertion inside a block fails the test as itself.
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            if (e.getCause() instanceof Error error) {
                throw error;
            }
            throw e;
        }
    }

    /**
     * Runs {@code task} on the JavaFX Application Thread and waits for it.
     *
     * @param task what to run
     * @throws Exception whatever the task threw, or a timeout
     */
    static void onFx(FxAction task) throws Exception {
        fromFx(() -> {
            task.run();
            return null;
        });
    }

    /**
     * Polls {@code condition} on the JavaFX Application Thread until it holds. This waits on other threads (the
     * physics worker and the render thread of a {@code gl} test), never on playback, which tests advance by hand.
     *
     * @param condition what to wait for; evaluated on the JavaFX thread
     * @param timeout how long to wait
     * @return whether the condition held within the timeout
     * @throws Exception if evaluating the condition failed
     */
    static boolean await(BooleanSupplier condition, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (fromFx(condition::getAsBoolean)) {
                return true;
            }
            // A poll, not a wait on time: the condition is any state another thread changes (the failure guard's
            // panel, the physics worker, the audio thread, a gl test's render thread), with no completion signal of its own to block
            // on. Playback never waits here; tests advance it by hand.
            Thread.sleep(5);
        }
        return false;
    }
}
