package io.github.evildarkarchon.andysdice.app;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * What ended the session, as the failure panel shows it (spec §3.2 "Runtime failure"): the one-line cause, the
 * log file's path, and the details Copy details puts on the clipboard.
 *
 * @param cause the one-line cause
 * @param details everything Copy details copies: the heading, the cause, the log file and the stack trace
 * @param logFile the app's log file, or {@code null} when none could be written
 */
record Failure(String cause, String details, Path logFile) {

    /** The panel's heading, the same for every failure. */
    static final String HEADING = "Andy's Dice hit a problem and has to close";

    /** Checks the cause and the details are present; the log file may not be. */
    Failure {
        Objects.requireNonNull(cause, "cause");
        Objects.requireNonNull(details, "details");
    }

    /**
     * The failure an exception nothing caught makes: an uncaught exception on one of the app's threads, a
     * pre-simulation that broke, a dead render thread or a lost GL context.
     *
     * @param thread the name of the thread it happened on
     * @param error what was thrown; a {@link CompletionException} or {@link ExecutionException} is unwrapped
     * @param logFile the log file, or {@code null}
     * @return the failure, its cause naming the exception, its message's first line and the thread
     */
    static Failure uncaught(String thread, Throwable error, Path logFile) {
        Throwable root = unwrap(error);
        String message = root.getMessage() == null || root.getMessage().isBlank() ? ""
                : ": " + root.getMessage().strip().lines().findFirst().orElse("");
        String cause = root.getClass().getSimpleName() + message + " (on thread " + thread + ")";
        return new Failure(cause, details(cause, logFile, root), logFile);
    }

    /**
     * The failure a start-up that couldn't get going makes: no OpenGL 3.3 core context, or no Jolt. The
     * exception's message already names the requirement and what was detected (ADR-0002, ADR-0003).
     *
     * @param error what start-up threw
     * @param logFile the log file, or {@code null}
     * @return the failure, its cause the exception's message
     */
    static Failure startup(Throwable error, Path logFile) {
        String cause = error.getMessage() == null ? error.toString() : error.getMessage();
        return new Failure(cause, details(cause, logFile, error), logFile);
    }

    /**
     * Returns the panel's line about the log.
     *
     * @return "Log file: " and its path, or a line saying there is none
     */
    String logLine() {
        return logLine(logFile);
    }

    private static String logLine(Path logFile) {
        return logFile == null ? "No log file could be written." : "Log file: " + logFile;
    }

    /**
     * Unwraps the wrappers a future puts round what a job threw, so the cause names the real exception.
     *
     * @param error the exception as it arrived
     * @return the first cause that isn't such a wrapper
     */
    static Throwable unwrap(Throwable error) {
        Throwable root = Objects.requireNonNull(error, "error");
        while ((root instanceof CompletionException || root instanceof ExecutionException)
                && root.getCause() != null) {
            root = root.getCause();
        }
        return root;
    }

    private static String details(String cause, Path logFile, Throwable error) {
        StringWriter trace = new StringWriter();
        try (PrintWriter out = new PrintWriter(trace)) {
            error.printStackTrace(out);
        }
        return HEADING + System.lineSeparator()
                + "Cause: " + cause + System.lineSeparator()
                + logLine(logFile) + System.lineSeparator()
                + "Java " + Runtime.version() + ", " + System.getProperty("os.name") + " "
                + System.getProperty("os.arch") + System.lineSeparator()
                + System.lineSeparator()
                + trace;
    }
}
