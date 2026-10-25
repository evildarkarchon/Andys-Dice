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
 * <p>It checks the {@link CommandLine} before any window opens: a rejected one is logged at WARNING and written to
 * standard error, naming each rejected argument and the accepted flags, and the process exits with
 * {@link CommandLine#REJECTED_STATUS} without starting JavaFX.
 *
 * <p>Otherwise the process exits with the status {@link AndysDice#launch} returns: 0 after a normal quit or a passed
 * smoke test, non-zero after a start-up failure, a runtime failure or a failed smoke test. Every exit status:
 * <ul>
 *   <li>0: a normal quit, a passed smoke test or a bench within its budgets</li>
 *   <li>1 ({@link AndysDice#STARTUP_FAILED}): Jolt or the GL context couldn't start</li>
 *   <li>2 ({@link SmokeTest#FAILED}): the smoke test failed</li>
 *   <li>3 ({@link AndysDice#RUNTIME_FAILED}): a runtime failure ended the session</li>
 *   <li>4 ({@link AlreadyRunning#EXIT_STATUS}): another copy is running</li>
 *   <li>5 ({@link Bench#MISSED}): the bench missed a budget</li>
 *   <li>6 ({@link Bench#FAILED}): the bench couldn't finish</li>
 *   <li>7 ({@link CommandLine#REJECTED_STATUS}): the command line was rejected</li>
 * </ul>
 */
public final class Main {

    private static final System.Logger LOG = System.getLogger(Main.class.getName());

    private Main() {}

    /**
     * Starts Andy's Dice.
     *
     * @param args nothing for the Player's app, or exactly one of {@value SmokeTest#FLAG} (the hidden smoke test,
     *     ADR-0005) and {@value Bench#FLAG} (the hidden, dev-only bench, ADR-0014); anything else is rejected
     */
    public static void main(String[] args) {
        // The process's own start, launcher included, so "launch to window" covers what the user waits for.
        Instant launched = ProcessHandle.current().info().startInstant().orElseGet(Instant::now);
        // Checked now, but acted on only once the lock is settled: see below.
        CommandLine line = CommandLine.parse(List.of(args));

        DataFolder data = DataFolder.choose();
        // One instance (spec §3.6). Taken before the log is installed: the running copy owns the log file, and a
        // second copy rotating it could rename it out from under that copy.
        InstanceLock lock = null;
        IOException lockFailure = null;
        try {
            Optional<InstanceLock> acquired = InstanceLock.tryAcquire(data.folder());
            if (acquired.isEmpty()) {
                if (line instanceof CommandLine.Rejected rejected) {
                    // A rejected line opens no window, not even AlreadyRunning's, and its status names the real
                    // mistake. The log is the running copy's, so only standard error gets the rejection.
                    System.err.println(rejected.message());
                    System.exit(CommandLine.REJECTED_STATUS);
                    return;
                }
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
        // A rejected command line is acted on only here, after the lock and before anything else. It must be in
        // the log, since the packaged exe has no console, so the log has to be installed first; and the log may
        // only be installed by the copy holding the lock (above), or a rejected launch would rotate a running
        // copy's log. Standard error, for a dev run, is written before the log's tee is in, which would otherwise
        // put the rejection in the log a second time.
        if (line instanceof CommandLine.Rejected rejected) {
            System.err.println(rejected.message());
        }
        AppLog log = null;
        try {
            log = AppLog.install(data.folder());
        } catch (IOException e) {
            // Running without a log file beats not running; the console, where there is one, still gets it all.
            System.err.println("Andy's Dice can't write its log in " + data.folder() + ": " + e);
        }
        int status = switch (line) {
            case CommandLine.Rejected rejected -> {
                LOG.log(Level.WARNING, rejected.message());
                yield CommandLine.REJECTED_STATUS;
            }
            case CommandLine.Accepted(LaunchMode mode) -> run(launched, mode, data, log, lock, lockFailure);
        };
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

    /**
     * Runs the app in an accepted mode, once the lock is settled and the log installed, and returns when it quits.
     *
     * @param launched when the process started
     * @param mode what this run is for
     * @param data the data folder chosen for this run
     * @param log the installed log, or {@code null} if it couldn't be
     * @param lock the instance lock, or {@code null} if it couldn't be taken
     * @param lockFailure why the lock couldn't be taken, when {@code lock} is {@code null}
     * @return the process's exit status
     */
    private static int run(Instant launched, LaunchMode mode, DataFolder data, AppLog log, InstanceLock lock,
            IOException lockFailure) {
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

        int status = AndysDice.launch(launched, data.folder(), mode, failures);

        LOG.log(status == 0 ? Level.INFO : Level.ERROR, "Andy's Dice exiting with status " + status);
        return status;
    }

    /** The app's version, as the module was built with it, or "(unversioned)" in an exploded dev build. */
    private static String version() {
        return Main.class.getModule().getDescriptor() == null ? "(unversioned)"
                : Main.class.getModule().getDescriptor().rawVersion().orElse("(unversioned)");
    }
}
