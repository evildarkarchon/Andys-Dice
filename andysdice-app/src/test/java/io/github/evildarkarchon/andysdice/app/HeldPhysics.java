package io.github.evildarkarchon.andysdice.app;

import io.github.evildarkarchon.andysdice.core.planner.RollPlan;
import io.github.evildarkarchon.andysdice.core.simulation.Simulator;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * A physics worker stand-in for tests: it runs each pre-simulation job on the test's own thread, over a given
 * simulator. While holding (the default) it keeps each job until the test calls {@link #finish()}, as if
 * pre-simulating took a while; told not to hold, it runs each job as soon as it is submitted. Either way the
 * controller still gets the result through its own executor, as it does from the real worker.
 *
 * <p>Not thread-safe: one test thread (the JavaFX thread, in UI tests) submits and finishes.
 */
final class HeldPhysics implements RollController.PhysicsJobs {
    private final Simulator simulator;
    /** The job waiting to run, or {@code null}. */
    Function<Simulator, RollPlan> held;
    private CompletableFuture<RollPlan> result;
    private boolean holding = true;

    /**
     * Creates a holding worker.
     *
     * @param simulator what the jobs simulate with
     */
    HeldPhysics(Simulator simulator) {
        this.simulator = simulator;
    }

    /**
     * Sets whether jobs are held until {@link #finish()}, or run as soon as they are submitted.
     *
     * @param holding {@code true} to hold the next jobs
     */
    void setHolding(boolean holding) {
        this.holding = holding;
    }

    @Override
    public CompletableFuture<RollPlan> submit(Function<Simulator, RollPlan> job) {
        held = job;
        result = new CompletableFuture<>();
        CompletableFuture<RollPlan> submitted = result;
        if (!holding) {
            finish();
        }
        return submitted;
    }

    /** Runs the held job, as the worker would, and completes its future. */
    void finish() {
        Function<Simulator, RollPlan> job = held;
        CompletableFuture<RollPlan> future = result;
        held = null;
        try {
            future.complete(job.apply(simulator));
        } catch (RuntimeException e) {
            future.completeExceptionally(e);
        }
    }
}
