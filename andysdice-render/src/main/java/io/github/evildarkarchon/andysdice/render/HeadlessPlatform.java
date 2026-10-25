package io.github.evildarkarchon.andysdice.render;

import javafx.application.Platform;

/**
 * For tests only: starts JavaFX once per JVM on its built-in headless platform (ADR-0011), with no window, display
 * or GPU behind the toolkit. The app never calls it.
 *
 * <p><b>This class owns the headless flags</b>, for every module's tests: {@code glass.platform=Headless} and
 * Prism's {@code sw} pipeline. {@code render}'s tests and {@code app}'s ({@code HeadlessUiTest} and the rest) all start
 * the toolkit through here. It lives in {@code render}'s main code because that is the one place both modules' tests
 * can reach: {@code app} reads {@code render}, and neither module's tests can see the other's. If a JavaFX update
 * breaks or removes the headless platform, delete the two properties in {@link #start} and the same tests run in
 * real windows on the Windows runner.
 *
 * <p>Thread contract: {@link #start} from any thread but the JavaFX Application Thread.
 */
public final class HeadlessPlatform {

    private static boolean started;

    private HeadlessPlatform() {}

    /**
     * Starts the toolkit on the headless platform if no test in this JVM has yet, and keeps it running when the
     * last window closes, so one test closing its window doesn't stop the toolkit for the next.
     */
    public static synchronized void start() {
        if (started) {
            return;
        }
        // Read by the toolkit when it starts, so they must be set before Platform.startup. These two lines are the
        // whole headless choice (ADR-0011): remove them to watch the same tests in real windows.
        System.setProperty("glass.platform", "Headless");
        System.setProperty("prism.order", "sw");
        Platform.startup(() -> {});
        Platform.setImplicitExit(false);
        started = true;
    }
}
