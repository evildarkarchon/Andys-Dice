package io.github.evildarkarchon.andysdice.physics;

/**
 * The one physics worker the test JVM shares, as the app has one (spec §5). Jolt's process-wide setup runs
 * only once per process, so every test class uses this worker rather than starting its own.
 */
final class TestWorker {

    private static PhysicsWorker worker;

    private TestWorker() {
    }

    /**
     * Returns the shared worker, starting it (and Jolt) on first use. It is never closed: its thread is a
     * daemon and ends with the test JVM.
     *
     * @return the running worker
     */
    static synchronized PhysicsWorker get() {
        if (worker == null) {
            worker = PhysicsWorker.start();
        }
        return worker;
    }
}
