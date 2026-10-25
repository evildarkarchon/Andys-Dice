package io.github.evildarkarchon.andysdice.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The app's command line, checked before any window opens: either {@link Accepted}, naming the {@link LaunchMode}
 * it asks for, or {@link Rejected}, naming every argument that can't be honoured.
 *
 * <p>The rule: no arguments (the Player's app), or exactly one of {@link #FLAGS}, spelled exactly. Anything else is
 * rejected, so a mistyped hidden flag ({@code --smoke} for {@value SmokeTest#FLAG}) can't open a normal session on
 * the dev's desktop. Two flags are rejected too, the same flag twice included: each flag is a whole mode of its own,
 * and quietly picking one of two would hide a mistake just as ignoring a typo did.
 *
 * <p>Nothing else legitimately reaches {@code main}: the jpackage launcher's {@code .cfg} has no {@code arguments}
 * entries, and the app has no file associations or shortcuts that add any (Explorer passes none on a double-click).
 * Render's {@code --gallery} isn't accepted: the app doesn't route it (it runs only from render's own dev build).
 */
sealed interface CommandLine {

    /** The process's exit status when the command line is rejected. */
    int REJECTED_STATUS = 7;

    /** Every flag the app accepts, in the order the rejection lists them. */
    List<String> FLAGS = List.of(SmokeTest.FLAG, Bench.FLAG);

    /**
     * Checks a command line.
     *
     * @param args the command line, as {@code main} got it
     * @return {@link Accepted} with the mode for an empty line or one accepted flag, else {@link Rejected}
     */
    static CommandLine parse(List<String> args) {
        Objects.requireNonNull(args, "args");
        String chosen = null;
        List<String> rejected = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        for (String arg : args) {
            if (!FLAGS.contains(arg)) {
                rejected.add(arg);
                reasons.add(quote(arg) + " isn't an argument Andy's Dice knows");
            } else if (chosen != null) {
                rejected.add(arg);
                reasons.add(quote(arg) + " can't follow " + quote(chosen) + ": one mode per launch");
            } else {
                chosen = arg;
            }
        }
        if (!rejected.isEmpty()) {
            return new Rejected(rejected, "Rejected the command line: " + String.join("; ", reasons)
                    + ". Andy's Dice accepts no arguments, or exactly one of " + String.join(", ", FLAGS)
                    + "; exiting with status " + REJECTED_STATUS);
        }
        if (SmokeTest.FLAG.equals(chosen)) {
            return new Accepted(LaunchMode.SMOKE_TEST);
        }
        return new Accepted(Bench.FLAG.equals(chosen) ? LaunchMode.BENCH : LaunchMode.PLAYER);
    }

    /** Quotes an argument, so an empty one or one with spaces reads unambiguously. */
    private static String quote(String arg) {
        return '"' + arg + '"';
    }

    /**
     * A command line the app can run.
     *
     * @param mode what this run is for
     */
    record Accepted(LaunchMode mode) implements CommandLine {

        /**
         * Checks the mode is given.
         *
         * @param mode what this run is for
         */
        public Accepted {
            Objects.requireNonNull(mode, "mode");
        }
    }

    /**
     * A command line the app refuses to run.
     *
     * @param arguments every rejected argument, in command-line order
     * @param message the one line for the log and standard error: each rejected argument and why, the accepted
     *     flags, and the exit status
     */
    record Rejected(List<String> arguments, String message) implements CommandLine {

        /**
         * Copies the arguments.
         *
         * @param arguments every rejected argument, in command-line order; not empty
         * @param message the one line for the log and standard error
         */
        public Rejected {
            arguments = List.copyOf(arguments);
            if (arguments.isEmpty()) {
                throw new IllegalArgumentException("a rejected command line names at least one argument");
            }
            Objects.requireNonNull(message, "message");
        }
    }
}
