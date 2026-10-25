package io.github.evildarkarchon.andysdice.physics;

import io.github.evildarkarchon.andysdice.core.simulation.Simulator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * The one physics worker (spec §5): a single thread that starts Jolt and then runs every pre-simulation.
 *
 * <p>{@link #start()} starts Jolt eagerly, on the worker, and returns only once it is up, so a missing or
 * broken native fails the app at start-up rather than at the first Roll (ADR-0003). Jolt isn't thread-safe,
 * so the Jolt {@link Simulator} is handed only to jobs running on the worker, and refuses calls from any other
 * thread.
 *
 * <p>The app starts exactly one worker. Jolt's process-wide setup runs once per process however many are
 * started.
 */
public final class PhysicsWorker implements AutoCloseable {

    private static final String THREAD_NAME = "andysdice-physics";

    private final ExecutorService executor;
    private final Simulator simulator;

    private PhysicsWorker(ExecutorService executor, Simulator simulator) {
        this.executor = executor;
        this.simulator = simulator;
    }

    /**
     * Starts the worker thread and Jolt on it, waiting until Jolt is up.
     *
     * @return the running worker
     * @throws JoltStartupException if Jolt can't be started; the message names the platform and the library
     */
    public static PhysicsWorker start() {
        // A daemon, so a Throw still being simulated never keeps the app from exiting.
        ExecutorService executor = Executors.newSingleThreadExecutor(job -> {
            Thread thread = new Thread(job, THREAD_NAME);
            thread.setDaemon(true);
            return thread;
        });
        try {
            Simulator simulator = executor.submit(() -> {
                JoltLoader.start();
                return (Simulator) new JoltSimulator(Thread.currentThread());
            }).get();
            return new PhysicsWorker(executor, simulator);
        } catch (ExecutionException e) {
            executor.shutdownNow();
            if (e.getCause() instanceof JoltStartupException startup) {
                throw startup;
            }
            throw new JoltStartupException("Can't start Jolt Physics on " + System.getProperty("os.name") + " "
                    + System.getProperty("os.arch") + ": " + e.getCause(), e.getCause());
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
            throw new JoltStartupException("Interrupted while starting Jolt Physics", e);
        }
    }

    /**
     * Runs a job on the worker, handing it the Jolt simulator. Jobs run one at a time, in submission order.
     *
     * @param job the job; it may call the simulator only from within itself, on the worker
     * @param <T> the job's result type
     * @return the job's result, completed exceptionally if it threw
     */
    public <T> CompletableFuture<T> submit(Function<? super Simulator, ? extends T> job) {
        // CompletableFuture captures anything the job throws, so the worker's one thread never dies and the
        // simulator, which is bound to that thread, stays usable.
        return CompletableFuture.supplyAsync(() -> job.apply(simulator), executor);
    }

    /** Stops accepting jobs; jobs already submitted still run. Jolt stays loaded for the life of the process. */
    @Override
    public void close() {
        executor.shutdown();
    }
}
