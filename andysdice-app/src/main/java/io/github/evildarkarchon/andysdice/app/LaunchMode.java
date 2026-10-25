package io.github.evildarkarchon.andysdice.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.IntConsumer;
import java.util.stream.Stream;

/**
 * What this run of the app is for, as the command line chose it: the Player's app, the hidden smoke test
 * ({@value SmokeTest#FLAG}, ADR-0005) or the hidden, dev-only bench ({@value Bench#FLAG}, ADR-0014). Each hidden mode
 * plays faster than real time, never waits on a person (a failure exits at once), and runs its own driver once the
 * window is up.
 *
 * <p>The bench keeps its persisted state (settings, Roll History, Saved Rolls, window geometry) in a throwaway folder
 * of its own, so its Rolls never reach the Player's {@code history.txt}; the log and the instance lock stay in the
 * data folder, where the dev reads the bench's result.
 *
 * <p>Thread contract: {@link #prepare} before JavaFX starts, {@link #run} on the JavaFX Application Thread; the rest
 * on any thread.
 */
enum LaunchMode {

    /** The Player's app: real-time playback, waits for Quit after a failure. */
    PLAYER(1, false, "") {
        @Override
        void run(Skeleton skeleton, Duration launchToWindow, IntConsumer exit) {
            // Nothing drives the Player's app: the Player does.
        }
    },

    /** The smoke test: one Roll at {@value SmokeTest#SPEED}×, then exit. */
    SMOKE_TEST(SmokeTest.SPEED, true, ", in smoke-test mode") {
        @Override
        void prepare() {
            SmokeTest.prepare();
        }

        @Override
        void run(Skeleton skeleton, Duration launchToWindow, IntConsumer exit) {
            SmokeTest.run(skeleton, exit);
        }
    },

    /** The bench: its Rolls at {@value Bench#SPEED}× and the GPU bench, then exit. */
    BENCH(Bench.SPEED, true, ", in bench mode") {
        @Override
        void run(Skeleton skeleton, Duration launchToWindow, IntConsumer exit) {
            Bench.run(skeleton, launchToWindow, exit);
        }

        @Override
        Path openState(Path dataFolder) throws IOException {
            // A fresh folder every run: the bench starts from a first run's settings, and never sees, nor adds to,
            // the Player's Roll History.
            return Files.createTempDirectory("andysdice-bench-");
        }

        @Override
        void closeState(Path state) throws IOException {
            if (!Files.exists(state)) {
                return;
            }
            // Deepest first, so each folder is empty when it is deleted.
            try (Stream<Path> paths = Files.walk(state)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
    };

    private final double speed;
    private final boolean unattended;
    private final String logSuffix;

    LaunchMode(double speed, boolean unattended, String logSuffix) {
        this.speed = speed;
        this.unattended = unattended;
        this.logSuffix = logSuffix;
    }

    /**
     * Chooses the mode from the command line: the smoke test wins if both hidden flags are given; anything else
     * on the line is ignored.
     *
     * @param args the command line
     * @return the mode
     */
    static LaunchMode of(List<String> args) {
        Objects.requireNonNull(args, "args");
        if (args.contains(SmokeTest.FLAG)) {
            return SMOKE_TEST;
        }
        return args.contains(Bench.FLAG) ? BENCH : PLAYER;
    }

    /**
     * Returns how fast the playback clock runs in this mode.
     *
     * @return the playback speed, 1 for real time
     */
    double speed() {
        return speed;
    }

    /**
     * Tells whether this mode runs without a person: a failure exits at once instead of waiting for Quit.
     *
     * @return {@code true} for the smoke test and the bench
     */
    boolean unattended() {
        return unattended;
    }

    /**
     * Returns what the start-up log line adds for this mode.
     *
     * @return {@code ""} for the Player's app, else a clause naming the mode, with its leading comma
     */
    String logSuffix() {
        return logSuffix;
    }

    /** Sets up what must be in place before JavaFX starts. Only the smoke test needs anything. */
    void prepare() {
        // Nothing to set up by default.
    }

    /**
     * Drives the started app in this mode, once its window is up and failures go to the panel.
     *
     * @param skeleton the started app
     * @param launchToWindow the launch-to-window time the log recorded
     * @param exit quits the app with an exit status; called once, on the JavaFX Application Thread, by the hidden
     *     modes and never by the Player's app
     */
    abstract void run(Skeleton skeleton, Duration launchToWindow, IntConsumer exit);

    /**
     * Returns the folder this run keeps its persisted state in, creating it if this mode needs one of its own.
     *
     * @param dataFolder the data folder chosen for this run (spec §3.6)
     * @return the data folder itself, or, for the bench, a new empty folder outside it
     * @throws IOException if the bench's folder couldn't be made
     */
    Path openState(Path dataFolder) throws IOException {
        return Objects.requireNonNull(dataFolder, "dataFolder");
    }

    /**
     * Lets go of the folder {@link #openState} returned, once everything has been written to it: the bench's is
     * deleted, the data folder is left alone.
     *
     * @param state the folder {@link #openState} returned
     * @throws IOException if the bench's folder couldn't be deleted
     */
    void closeState(Path state) throws IOException {
        // The data folder outlives the run.
    }
}
