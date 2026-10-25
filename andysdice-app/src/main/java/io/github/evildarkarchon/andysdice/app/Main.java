package io.github.evildarkarchon.andysdice.app;

import java.io.IOException;
import java.lang.System.Logger.Level;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The launcher's main class: it sets up the process around the JavaFX application, then launches it.
 *
 * <p>It chooses the data folder once (spec §3.6), sends the log there (spec §6), and records both, before
 * JavaFX starts. That order is why it is a separate class: the Java launcher starts a main class that extends
 * {@code Application} through JavaFX's own launcher, which brings the toolkit and Prism up before any of our
 * code runs, too late for the smoke test's {@code prism.verbose} and for logging the toolkit's start-up.
 *
 * <p>Before the log it takes the data folder's {@link InstanceLock}; if another copy holds it, this launch only
 * shows {@link AlreadyRunning}'s message and exits with {@link AlreadyRunning#EXIT_STATUS}.
 *
 * <p>The process exits with the status {@link AndysDice#launch} returns: 0 after a normal quit or a passed smoke
 * test, non-zero after a start-up failure, a runtime failure or a failed smoke test.
 */
public final class Main {

    private static final System.Logger LOG = System.getLogger(Main.class.getName());

    private Main() {}

    /**
     * Starts Andy's Dice.
     *
     * @param args {@value SmokeTest#FLAG} runs the hidden smoke test (ADR-0005), {@value Bench#FLAG} the hidden,
     *     dev-only bench (ADR-0014); anything else is ignored
     */
    public static void main(String[] args) {
        // The process's own start, launcher included, so "launch to window" covers what the user waits for.
        Instant launched = ProcessHandle.current().info().startInstant().orElseGet(Instant::now);
        LaunchMode mode = LaunchMode.of(List.of(args));

        DataFolder data = DataFolder.choose();
        // One instance (spec §3.6). Taken before the log is installed: the running copy owns the log file, and a
        // second copy rotating it could rename it out from under that copy.
        InstanceLock lock = null;
        IOException lockFailure = null;
        try {
            Optional<InstanceLock> acquired = InstanceLock.tryAcquire(data.folder());
            if (acquired.isEmpty()) {
                System.err.println(AlreadyRunning.MESSAGE + ": " + data.folder() + " is locked by another copy");
                System.exit(AlreadyRunning.run(args));
                return;
            }
            lock = acquired.get();
        } catch (IOException e) {
            // An unwritable data folder can't hold the lock file; run without the rule rather than not at all
            // (persistence then reports the folder in a Notice).
            lockFailure = e;
        }
        AppLog log = null;
        try {
            log = AppLog.install(data.folder());
        } catch (IOException e) {
            // Running without a log file beats not running; the console, where there is one, still gets it all.
            System.err.println("Andy's Dice can't write its log in " + data.folder() + ": " + e);
        }
        LOG.log(Level.INFO, "Andy's Dice " + version() + " starting on Java " + Runtime.version() + ", "
                + System.getProperty("os.name") + " " + System.getProperty("os.arch")
                + mode.logSuffix());
        LOG.log(Level.INFO, "Data folder: " + data.folder() + " (" + data.reason() + ")");
        if (log != null) {
            LOG.log(Level.INFO, "Log file: " + log.file());
        }
        if (lock != null) {
            LOG.log(Level.INFO, "Instance lock: " + lock.file());
        } else {
            LOG.log(Level.WARNING, "Running without the instance lock: " + lockFailure);
        }
        // v1 is crash-only (spec §3): whatever escapes a thread is on record, and ends the session in the failure
        // panel once the window is up.
        FailureGuard failures = FailureGuard.install(log == null ? null : log.file());
        // The smoke test's verbose reports must be on before JavaFX starts.
        mode.prepare();

        int status = AndysDice.launch(launched, data.folder(), args, failures);

        LOG.log(status == 0 ? Level.INFO : Level.ERROR, "Andy's Dice exiting with status " + status);
        if (lock != null) {
            // Also what keeps the lock reachable for the whole run: a collected channel would release it early.
            try {
                lock.close();
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Couldn't release the instance lock: " + e);
            }
        }
        if (log != null) {
            log.close();
        }
        // Explicitly, so the status reaches the launcher and no stray non-daemon thread keeps the JVM alive.
        System.exit(status);
    }

    /** The app's version, as the module was built with it, or "(unversioned)" in an exploded dev build. */
    private static String version() {
        return Main.class.getModule().getDescriptor() == null ? "(unversioned)"
                : Main.class.getModule().getDescriptor().rawVersion().orElse("(unversioned)");
    }
}
