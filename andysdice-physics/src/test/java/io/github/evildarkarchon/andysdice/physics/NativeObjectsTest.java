package io.github.evildarkarchon.andysdice.physics;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.stephengold.joltjni.JoltPhysicsObject;
import com.github.stephengold.joltjni.Jolt;
import io.github.evildarkarchon.andysdice.core.simulation.AttemptRequest;
import io.github.evildarkarchon.andysdice.core.simulation.ContactMaterial;
import io.github.evildarkarchon.andysdice.core.simulation.FrozenDie;
import io.github.evildarkarchon.andysdice.core.simulation.Orientation;
import io.github.evildarkarchon.andysdice.core.simulation.Pose;
import io.github.evildarkarchon.andysdice.core.simulation.Release;
import io.github.evildarkarchon.andysdice.core.simulation.TrayGeometry;
import io.github.evildarkarchon.andysdice.core.simulation.TrayMaterials;
import io.github.evildarkarchon.andysdice.core.simulation.Vector3;
import java.util.Collections;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/**
 * Each Attempt frees every native object it makes (ADR-0003), with jolt-jni's cleaner never started.
 *
 * <p>Jolt counts its native allocations only in its Debug builds, so the balance check has teeth when the
 * tests run on the DebugSp native ({@code mvn -Pjolt-debug ...}); on ReleaseSp both counts stay 0.
 */
class NativeObjectsTest {

    private static final TrayMaterials FELT = new TrayMaterials(new ContactMaterial(0.45f, 0.15f),
            new ContactMaterial(0.45f, 0.5f), new ContactMaterial(0.2f, 0.15f), 0.6f);

    @Test
    void anAttemptLeavesNoNativeObjectBehind() {
        // Twenty thrown Dice among a Frozen Die, so every kind of body and plenty of contacts are made.
        FrozenDie frozen = new FrozenDie(TestCube.INSTANCE,
                new Pose(new Vector3(0, TestCube.INRADIUS, 4), Orientation.IDENTITY));
        AttemptRequest request = new AttemptRequest(Collections.nCopies(20, TestCube.INSTANCE),
                new Release.Drawn(new SplittableRandom(32)), List.of(frozen), TrayGeometry.rectangle(15, 10, 3),
                FELT);

        long[] live = TestWorker.get().submit(simulator -> {
            long before = Jolt.countNews() - Jolt.countDeletes();
            simulator.simulate(request);
            return new long[] {before, Jolt.countNews() - Jolt.countDeletes()};
        }).join();

        assertThat(live[1]).isEqualTo(live[0]);
    }

    @Test
    void joltsCleanerThreadIsNeverStarted() {
        TestWorker.get();
        assertThat(JoltPhysicsObject.isCleanerStarted()).isFalse();
    }
}
