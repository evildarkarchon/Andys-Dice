package io.github.evildarkarchon.andysdice.physics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.evildarkarchon.andysdice.core.simulation.Simulator;
import org.junit.jupiter.api.Test;

/** Jolt starts eagerly on the one physics worker, and the simulator runs only there (spec §5). */
class PhysicsWorkerTest {

    @Test
    void startsJoltOnItsOwnThreadBeforeReturning() {
        PhysicsWorker worker = TestWorker.get();
        // start() has returned, so Jolt is already up, and it was set up on the thread that runs every job.
        Thread jolt = JoltLoader.startedOn();
        assertThat(jolt).isNotNull().isNotSameAs(Thread.currentThread());
        assertThat(worker.submit(simulator -> Thread.currentThread()).join()).isSameAs(jolt);
    }

    @Test
    void theSimulatorRefusesCallsFromAnyOtherThread() {
        Simulator simulator = TestWorker.get().submit(s -> s).join();
        assertThatThrownBy(() -> simulator.simulate(null)).isInstanceOf(IllegalStateException.class);
    }
}
