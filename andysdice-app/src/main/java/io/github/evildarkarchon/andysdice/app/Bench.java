package io.github.evildarkarchon.andysdice.app;

import io.github.evildarkarchon.andysdice.render.GpuBench;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;
import javafx.application.Platform;

/**
 * The hidden, dev-only {@code --bench} mode (ADR-0014): the real app start-up, then the two measures that block a
 * release, each printed against its budget, then exit, non-zero if either is missed. It is a release-checklist item,
 * never a CI gate: only the driving dev's machine (an RTX 4070) gives meaningful numbers.
 *
 * <ol>
 *   <li><b>Enter to first motion</b> for the realistic worst case, {@value #EXPRESSION} (20 Dice, an {@code rr}
 *       matching half the faces): {@value #ROLLS} Rolls through the real pipeline ({@link FirstMotion}), the first
 *       one cold (JIT, first pre-simulation) and the rest warm. The budget, {@link #FIRST_MOTION_BUDGET}, is judged
 *       on the warm Rolls' median; the cold one and the slowest are printed beside it.</li>
 *   <li><b>GPU time</b> of the most expensive scene at 1080p on Medium ({@link GpuBench}), on the app's own render
 *       thread and scene, against {@value GpuBench#BUDGET_MILLIS} ms.</li>
 * </ol>
 * It also prints the launch-to-window time the log records, against ADR-0014's 3 s; that one is the release
 * checklist's to judge, not a gate of the bench.
 *
 * <p>The Rolls play at {@value #SPEED}× so the bench doesn't wait out every chain; playback speed doesn't touch
 * pre-simulation or the first frame. They never reach the Player's Roll History: the bench keeps its persisted state
 * in a throwaway folder of its own ({@link LaunchMode#BENCH}), deleted when it exits, and starts from a first run's
 * settings; only the log stays in the data folder. Leave the window alone while it runs: a resize would resize the render target
 * under the GPU bench.
 *
 * <p>Thread contract: {@link #run} on the JavaFX Application Thread; the GPU bench runs on a thread of its own.
 */
final class Bench {

    /** The command-line flag. Hidden: no UI or Player-facing documentation offers it. */
    static final String FLAG = "--bench";

    /** ADR-0014's realistic worst case: 20 Dice, with an {@code rr} condition matching half the faces. */
    static final String EXPRESSION = "20d20rr<=10";

    /** ADR-0014's budget from Enter to first motion, for {@link #EXPRESSION}, on the driving dev's machine. */
    static final Duration FIRST_MOTION_BUDGET = Duration.ofMillis(150);

    /** ADR-0014's launch-to-interactive-window target; printed, not gated on. */
    static final Duration LAUNCH_BUDGET = Duration.ofSeconds(3);

    /** Rolls timed: one cold, then warm ones. */
    static final int ROLLS = 11;

    /** How much faster than real time the Rolls play back. */
    static final double SPEED = 16;

    /** Exit status when a budget is missed. */
    static final int MISSED = 5;

    /** Exit status when the bench couldn't finish (a Roll failed, a frame never came back). */
    static final int FAILED = 6;

    /** How long the Tray must stay idle before the next Roll is timed: no frame of the last one still in flight. */
    private static final long QUIET_MILLIS = 300;
    private static final long IDLE_POLL_MILLIS = 50;

    private static final System.Logger LOG = System.getLogger(Bench.class.getName());

    /**
     * The Enter-to-first-motion times of the bench's Rolls, in the order rolled.
     *
     * @param rolls the times; the first is the cold Roll's
     */
    record FirstMotions(List<Duration> rolls) {

        /**
         * Copies the times.
         *
         * @throws IllegalArgumentException with fewer than two: there must be a cold Roll and a warm one
         */
        FirstMotions {
            rolls = List.copyOf(rolls);
            if (rolls.size() < 2) {
                throw new IllegalArgumentException("need a cold Roll and at least one warm one, not " + rolls.size());
            }
        }

        /** @return the first Roll's time, with nothing warmed up yet */
        Duration cold() {
            return rolls.getFirst();
        }

        /** @return the Rolls after the first */
        List<Duration> warm() {
            return rolls.subList(1, rolls.size());
        }

        /**
         * Returns the warm Rolls' median, which the budget is judged on: one Roll that needed extra Attempts (each
         * costs some milliseconds of simulation, ADR-0014) doesn't move it.
         *
         * @return the median of {@link #warm()}
         */
        Duration median() {
            List<Duration> sorted = warm().stream().sorted().toList();
            int middle = sorted.size() / 2;
            return sorted.size() % 2 == 1 ? sorted.get(middle)
                    : sorted.get(middle - 1).plus(sorted.get(middle)).dividedBy(2);
        }

        /** @return the slowest warm Roll */
        Duration slowest() {
            return warm().stream().max(Duration::compareTo).orElseThrow();
        }

        /** @return whether the median is within {@link #FIRST_MOTION_BUDGET} */
        boolean meetsBudget() {
            return median().compareTo(FIRST_MOTION_BUDGET) <= 0;
        }
    }

    /**
     * Everything the bench measured.
     *
     * @param launchToWindow from the process's start to the window shown, as the log records it
     * @param firstMotions the Enter-to-first-motion times
     * @param gpu the GPU bench's report
     */
    record Result(Duration launchToWindow, FirstMotions firstMotions, GpuBench.Report gpu) {

        /** Checks that every part is there. */
        Result {
            Objects.requireNonNull(launchToWindow, "launchToWindow");
            Objects.requireNonNull(firstMotions, "firstMotions");
            Objects.requireNonNull(gpu, "gpu");
        }

        /** @return whether both release-blocking budgets are met */
        boolean meetsBudgets() {
            return firstMotions.meetsBudget() && gpu.meetsBudget();
        }

        /** @return 0 when both budgets are met, else {@link #MISSED} */
        int exitStatus() {
            return meetsBudgets() ? 0 : MISSED;
        }

        /**
         * Prints each measure against its budget, then the verdict.
         *
         * @param out where to print
         */
        void print(PrintStream out) {
            out.println("Launch to window: " + launchToWindow.toMillis() + " ms (budget " + LAUNCH_BUDGET.toMillis()
                    + " ms: " + verdict(launchToWindow.compareTo(LAUNCH_BUDGET) <= 0) + "; not a bench gate)");
            out.println("Enter to first motion, " + EXPRESSION + ": median " + firstMotions.median().toMillis()
                    + " ms of " + firstMotions.warm().size() + " warm Rolls (budget "
                    + FIRST_MOTION_BUDGET.toMillis() + " ms: " + verdict(firstMotions.meetsBudget()) + ")");
            out.println("  cold " + firstMotions.cold().toMillis() + " ms, slowest " + firstMotions.slowest().toMillis()
                    + " ms; every Roll: " + firstMotions.rolls().stream().map(d -> Long.toString(d.toMillis()))
                    .toList());
            gpu.print(out);
            out.println("Bench: " + verdict(meetsBudgets()));
        }

        /** @return the result as {@link #print} prints it */
        String text() {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            print(new PrintStream(bytes, true, StandardCharsets.UTF_8));
            return bytes.toString(StandardCharsets.UTF_8);
        }

        private static String verdict(boolean met) {
            return met ? "met" : "MISSED";
        }
    }

    private Bench() {}

    /**
     * Runs the bench on the started app and calls {@code exit} once it is over, with 0 when both budgets were met,
     * {@link #MISSED} when one wasn't, and {@link #FAILED} when the bench couldn't finish. The result goes to standard
     * output and the log.
     *
     * @param skeleton the started app, its window shown
     * @param launchToWindow the launch-to-window time the app logged
     * @param exit called once, on the JavaFX Application Thread, with the exit status
     */
    static void run(Skeleton skeleton, Duration launchToWindow, IntConsumer exit) {
        Objects.requireNonNull(exit, "exit");
        Executor fx = Platform::runLater;
        LOG.log(Level.INFO, "Bench: timing Enter to first motion of " + EXPRESSION + " over " + ROLLS + " Rolls");
        roll(skeleton, new ArrayList<>(), fx)
                .thenCompose(motions -> whenIdle(skeleton).thenApply(idle -> motions))
                .thenCompose(motions -> {
                    LOG.log(Level.INFO, "Bench: timing the GPU on " + skeleton.driver());
                    // Off the JavaFX thread, which must keep presenting the frames the GPU bench draws.
                    return CompletableFuture.supplyAsync(() -> GpuBench.run(skeleton.renderer(), skeleton.scene()),
                            command -> Thread.ofPlatform().name("andysdice-bench").daemon(true).start(command))
                            .thenApply(gpu -> new Result(launchToWindow, motions, gpu));
                })
                .whenCompleteAsync((result, error) -> {
                    if (error != null) {
                        Throwable cause = Failure.unwrap(error);
                        LOG.log(Level.ERROR, "Bench couldn't finish: " + cause, cause);
                        System.out.println("Bench couldn't finish: " + cause);
                        exit.accept(FAILED);
                        return;
                    }
                    String text = result.text();
                    System.out.print(text);
                    LOG.log(result.meetsBudgets() ? Level.INFO : Level.WARNING, "Bench result:\n" + text);
                    exit.accept(result.exitStatus());
                }, fx);
    }

    /** Times the next Roll once the Tray is idle, until {@value #ROLLS} have been timed. */
    private static CompletableFuture<FirstMotions> roll(Skeleton skeleton, List<Duration> timed, Executor fx) {
        if (timed.size() == ROLLS) {
            return CompletableFuture.completedFuture(new FirstMotions(timed));
        }
        return whenIdle(skeleton)
                .thenComposeAsync(idle -> FirstMotion.time(skeleton, RollExpressions.valid(EXPRESSION)), fx)
                .thenComposeAsync(firstMotion -> {
                    LOG.log(Level.INFO, "Bench: Roll " + (timed.size() + 1) + " reached the screen "
                            + String.format(Locale.ROOT, "%.1f", firstMotion.toNanos() / 1e6) + " ms after Enter");
                    timed.add(firstMotion);
                    return roll(skeleton, timed, fx);
                }, fx);
    }

    /**
     * Completes, on the JavaFX thread, once no Roll is under way and the camera has stopped, and has stayed so for
     * {@value #QUIET_MILLIS} ms, long enough for the last frame drawn to have reached the screen.
     */
    private static CompletableFuture<Void> whenIdle(Skeleton skeleton) {
        CompletableFuture<Void> idle = new CompletableFuture<>();
        long[] quietSince = {-1};
        Runnable[] check = new Runnable[1];
        check[0] = () -> {
            boolean quiet = !skeleton.controller().state().underWay() && !skeleton.camera().moving();
            long now = System.nanoTime();
            if (!quiet) {
                quietSince[0] = -1;
            } else if (quietSince[0] < 0) {
                quietSince[0] = now;
            }
            if (quietSince[0] >= 0 && now - quietSince[0] >= TimeUnit.MILLISECONDS.toNanos(QUIET_MILLIS)) {
                idle.complete(null);
            } else {
                CompletableFuture.delayedExecutor(IDLE_POLL_MILLIS, TimeUnit.MILLISECONDS)
                        .execute(() -> Platform.runLater(check[0]));
            }
        };
        Platform.runLater(check[0]);
        return idle;
    }
}
