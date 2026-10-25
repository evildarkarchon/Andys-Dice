package io.github.evildarkarchon.andysdice.render;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javafx.application.Platform;

/**
 * Starts JavaFX once per test JVM on its built-in headless platform (ADR-0011), so the bridge's JavaFX end runs
 * without a desktop session, and runs test code on the JavaFX Application Thread.
 *
 * <p>This is the one place render's tests set JavaFX's platform properties; swapping them here is how to watch
 * the same tests in real windows.
 */
final class HeadlessFx {

    private static boolean started;

    private HeadlessFx() {}

    /** Starts the toolkit on the headless platform if no test has yet. */
    static synchronized void start() {
        if (started) {
            return;
        }
        // Read by the toolkit when it starts, so they must be set before Platform.startup.
        System.setProperty("glass.platform", "Headless");
        System.setProperty("prism.order", "sw");
        Platform.startup(() -> {});
        // The tests open and close windows; closing the last must not stop the toolkit for the next test.
        Platform.setImplicitExit(false);
        started = true;
    }

    /**
     * Runs {@code task} on the JavaFX Application Thread and waits for its result.
     *
     * @param task what to run
     * @param <T> the result type
     * @return the task's result
     * @throws Exception whatever the task threw, or a timeout after 10 s
     */
    static <T> T call(Callable<T> task) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                result.complete(task.call());
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        return result.get(10, TimeUnit.SECONDS);
    }

    /**
     * Polls {@code condition} on the JavaFX Application Thread until it holds.
     *
     * @param condition what to wait for; evaluated on the JavaFX thread
     * @param timeout how long to wait
     * @return whether the condition held within the timeout
     * @throws Exception if evaluating the condition failed
     */
    static boolean await(BooleanSupplier condition, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (call(condition::getAsBoolean)) {
                return true;
            }
            Thread.sleep(5);
        }
        return false;
    }
}
