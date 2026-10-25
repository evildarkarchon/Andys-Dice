package io.github.evildarkarchon.andysdice.app;

import io.github.evildarkarchon.andysdice.core.planner.RollPlan;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Where every failure that ends the session goes (spec §3.2 "Runtime failure"). v1 is crash-only: an uncaught
 * exception on any of the app's threads (JavaFX, render, physics, audio, persistence), a pre-simulation job that
 * threw, a dead render thread or a lost GL context ends the session. The guard logs the cause and hands the first
 * failure to the failure panel; nothing is recovered in-process (ADR-0002 allows one GL context per app run).
 *
 * <p>{@link Main} installs it as the JVM's default uncaught-exception handler, so every thread without a handler of
 * its own reports here, the JavaFX Application Thread included: JavaFX hands what an event handler or a
 * {@code runLater} task throws to the thread's uncaught-exception handler. Failures that a future captures instead
 * of a thread are reported by {@link #watch} and {@link #reporting}.
 *
 * <p>Only the first failure is shown; any later one is still logged, since one failure often drags others after
 * it, and the first is the one to read. The same exception reported twice (a render thread's failure reaches both
 * its future and the uncaught-exception handler) is logged once.
 *
 * <p>Thread contract: {@link #report} and everything that calls it are safe on any thread. The panel is shown
 * through the executor given to {@link #attach}, the JavaFX Application Thread in the app.
 */
final class FailureGuard implements Thread.UncaughtExceptionHandler {

    /** The physics worker's thread name, which a failed pre-simulation job is reported under. */
    static final String PHYSICS_THREAD = "andysdice-physics";

    /** The render thread's name, which its unexpected end is reported under. */
    static final String RENDER_THREAD = "andysdice-render";

    private static final System.Logger LOG = System.getLogger(FailureGuard.class.getName());

    private final Path logFile;
    private final Object lock = new Object();
    // Guarded by lock.
    private Failure first;
    private Throwable firstError;
    private Consumer<Failure> view;
    private Executor viewThread;

    /**
     * Creates a guard that is not yet anyone's handler.
     *
     * @param logFile the app's log file, which the panel names, or {@code null} when none could be written
     */
    FailureGuard(Path logFile) {
        this.logFile = logFile;
    }

    /**
     * Creates a guard and makes it the JVM's default uncaught-exception handler.
     *
     * @param logFile the app's log file, or {@code null}
     * @return the installed guard
     */
    static FailureGuard install(Path logFile) {
        FailureGuard guard = new FailureGuard(logFile);
        Thread.setDefaultUncaughtExceptionHandler(guard);
        return guard;
    }

    /** Reports an exception that ended a thread. */
    @Override
    public void uncaughtException(Thread thread, Throwable error) {
        report(thread.getName(), error);
    }

    /**
     * Reports a failure that ends the session: logs it, and shows the panel if it is the first. Any thread.
     *
     * @param thread the name of the thread it happened on
     * @param error what was thrown
     */
    void report(String thread, Throwable error) {
        Objects.requireNonNull(thread, "thread");
        Objects.requireNonNull(error, "error");
        Failure failure;
        Consumer<Failure> show;
        Executor on;
        synchronized (lock) {
            if (first != null) {
                if (Failure.unwrap(error) != firstError) {
                    LOG.log(Level.ERROR, "Another uncaught exception on thread " + thread
                            + " after the session had already failed", error);
                }
                return;
            }
            first = Failure.uncaught(thread, error, logFile);
            firstError = Failure.unwrap(error);
            failure = first;
            show = view;
            on = viewThread;
        }
        LOG.log(Level.ERROR, "Uncaught exception on thread " + thread + "; Andy's Dice has to close: "
                + failure.cause(), error);
        if (show != null) {
            on.execute(() -> show.accept(failure));
        }
    }

    /**
     * Sets what shows the failure panel. If the session has already failed, it is shown the failure at once.
     *
     * @param show shows the panel for a failure; called at most once, through {@code on}
     * @param on the thread {@code show} runs on ({@code Platform::runLater} in the app)
     */
    void attach(Consumer<Failure> show, Executor on) {
        Objects.requireNonNull(show, "show");
        Objects.requireNonNull(on, "on");
        Failure already;
        synchronized (lock) {
            view = show;
            viewThread = on;
            already = first;
        }
        if (already != null) {
            on.execute(() -> show.accept(already));
        }
    }

    /**
     * Watches for a thread's unexpected end, reported through a future rather than its uncaught-exception handler:
     * the render thread's, which also ends when the driver reports its GL context lost.
     *
     * @param thread the thread's name
     * @param stopped completes normally when the thread was closed on purpose, and exceptionally, with why, when
     *     it died
     */
    void watch(String thread, CompletionStage<?> stopped) {
        Objects.requireNonNull(thread, "thread");
        stopped.whenComplete((ignored, error) -> {
            if (error != null) {
                report(thread, error);
            }
        });
    }

    /**
     * Wraps the physics worker so a pre-simulation job that throws is reported. The worker's future captures the
     * exception, so its thread never dies of it, but a broken pre-simulation still ends the session.
     *
     * @param jobs the physics worker's jobs
     * @return the same jobs, each failure also reported under {@value #PHYSICS_THREAD}
     */
    RollController.PhysicsJobs reporting(RollController.PhysicsJobs jobs) {
        Objects.requireNonNull(jobs, "jobs");
        return job -> {
            CompletableFuture<RollPlan> planned = jobs.submit(job);
            watch(PHYSICS_THREAD, planned);
            return planned;
        };
    }

    /**
     * Returns the log file the panel names.
     *
     * @return the app's log file, or {@code null} when none could be written
     */
    Path logFile() {
        return logFile;
    }

    /**
     * Returns the failure that ended the session.
     *
     * @return the first failure reported, or {@code null} while there has been none
     */
    Failure failure() {
        synchronized (lock) {
            return first;
        }
    }
}
