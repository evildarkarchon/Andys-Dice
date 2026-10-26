package io.github.evildarkarchon.andysdice.app;

import java.lang.System.Logger.Level;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;
import javafx.application.Platform;

/**
 * The hidden {@code --smoke-test} mode (ADR-0005): the real app start-up, then one fixed Roll Expression played
 * at speed, then exit. Failsafe runs it on the built {@code AndysDice.exe}.
 *
 * <p>By the time {@link #run} is called the app has started JavaFX, run Jolt's eager start-up, created the GL
 * context and compiled the shaders, as every launch does. Before JavaFX starts, {@link #prepare} turns on Prism's
 * and LWJGL's own reports, which reach the log through its tee of standard output and error, so the log shows
 * which Prism pipeline ran and where LWJGL's natives were loaded from.
 *
 * <p>Thread contract: {@link #prepare} on the main thread before JavaFX starts; {@link #run} on the JavaFX
 * Application Thread.
 */
final class SmokeTest {

    /** The command-line flag. Hidden: no UI or documentation offers it. */
    static final String FLAG = "--smoke-test";

    /** The fixed Roll Expression it rolls: two Die Kinds in one Throw and a Modifier, kept quick to play. */
    static final String EXPRESSION = "1d20+2d6+1";

    /** How much faster than real time the Throw plays. */
    static final double SPEED = 8;

    /** Exit status when the Roll failed, or didn't finish in time. */
    static final int FAILED = 2;

    /** How long the Roll may take, pre-simulation and playback together, before the smoke test fails. */
    private static final long TIMEOUT_SECONDS = 120;

    private static final System.Logger LOG = System.getLogger(SmokeTest.class.getName());

    private SmokeTest() {}

    /** Turns on Prism's pipeline report and LWJGL's library-loading report. Must run before JavaFX starts. */
    static void prepare() {
        System.setProperty("prism.verbose", "true");
        System.setProperty("javafx.verbose", "true");
        System.setProperty("org.lwjgl.util.Debug", "true");
        System.setProperty("org.lwjgl.util.DebugLoader", "true");
    }

    /**
     * Rolls {@link #EXPRESSION} on the started app and calls {@code exit} once it is over: with 0 when the Dice
     * came to rest and the Roll Result was shown, with {@link #FAILED} otherwise.
     *
     * @param skeleton the started app
     * @param exit called once, on the JavaFX Application Thread, with the smoke test's exit status
     */
    static void run(Skeleton skeleton, IntConsumer exit) {
        Objects.requireNonNull(exit, "exit");
        boolean[] done = {false};
        IntConsumer finish = status -> {
            if (!done[0]) {
                done[0] = true;
                exit.accept(status);
            }
        };
        skeleton.controller().addStateListener(RollState.statusChanges(status -> {
            switch (status) {
                case RollStatus.Rested rested -> {
                    LOG.log(Level.INFO, "Smoke test rolled " + rested.text());
                    finish.accept(0);
                }
                case RollStatus.Failed failed -> {
                    LOG.log(Level.ERROR, "Smoke test's Roll failed: " + failed.text());
                    finish.accept(FAILED);
                }
                case RollStatus.Ready ready -> { }
                case RollStatus.PreSimulating preSimulating -> { }
                case RollStatus.Playing playing -> { }
                // A runtime failure ended the session: the failure guard's callback quits with the runtime
                // failure's own exit status, which is the one to report, so the smoke test adds nothing.
                case RollStatus.Lost lost -> { }
            }
        }));
        CompletableFuture.delayedExecutor(TIMEOUT_SECONDS, TimeUnit.SECONDS).execute(() -> Platform.runLater(() -> {
            if (!done[0]) {
                LOG.log(Level.ERROR, "Smoke test's Roll didn't finish within " + TIMEOUT_SECONDS + " s");
                finish.accept(FAILED);
            }
        }));
        LOG.log(Level.INFO, "Smoke test rolling " + EXPRESSION + " at x" + SPEED);
        // The controller's normal Roll, the one Enter goes through once entry has parsed the box.
        skeleton.controller().roll(RollExpressions.valid(EXPRESSION));
    }
}
