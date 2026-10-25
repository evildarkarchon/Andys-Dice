package io.github.evildarkarchon.andysdice.physics;

import io.github.evildarkarchon.andysdice.core.geometry.DieKind;
import io.github.evildarkarchon.andysdice.core.simulation.Attempt;
import io.github.evildarkarchon.andysdice.core.simulation.AttemptRequest;
import io.github.evildarkarchon.andysdice.core.simulation.DieBody;
import io.github.evildarkarchon.andysdice.core.simulation.DieKindBody;
import io.github.evildarkarchon.andysdice.core.simulation.DieStart;
import io.github.evildarkarchon.andysdice.core.simulation.FrozenDie;
import io.github.evildarkarchon.andysdice.core.simulation.Orientation;
import io.github.evildarkarchon.andysdice.core.simulation.Pose;
import io.github.evildarkarchon.andysdice.core.simulation.Recording;
import io.github.evildarkarchon.andysdice.core.simulation.Release;
import io.github.evildarkarchon.andysdice.core.simulation.TrayGeometry;
import io.github.evildarkarchon.andysdice.core.simulation.TrayMaterials;
import io.github.evildarkarchon.andysdice.core.simulation.Vector3;
import io.github.evildarkarchon.andysdice.core.tray.BuiltInTrays;
import java.util.List;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/** Shared pieces of the Jolt scenario tests: the Tray, its materials, and ways to place and run Dice. */
final class Scenarios {

    /**
     * The default Tray's shape (ADR-0010: the invariant tests on real Jolt run in the default Tray): the whole
     * 300 × 200 mm Footprint inside 30 mm walls.
     */
    static final TrayGeometry BOX = BuiltInTrays.defaultTray().geometry();

    /**
     * The default Tray's materials, green felt in walnut (Appendix C): Jolt's geometric mean of a 0.4 die against
     * felt's 0.5 and the walls' 0.5, felt's 0.15 bounce and the walls' 0.5, and felt's spin damping; die-on-die at
     * ADR-0013's 0.2.
     */
    static final TrayMaterials FELT = BuiltInTrays.defaultTray().materials();

    /** The flat-rest prototype's 20-Dice cap mix (physics notes: {@code Params.DICE[5]}). */
    static final List<DieBody> CAP = List.of(DieKind.D20, DieKind.D20, DieKind.D12, DieKind.D12, DieKind.D10,
            DieKind.D10, DieKind.PERCENTILE_D10, DieKind.D8, DieKind.D8, DieKind.D6, DieKind.D6, DieKind.D6,
            DieKind.D6, DieKind.D6, DieKind.D6, DieKind.D4, DieKind.D4, DieKind.D4, DieKind.D20, DieKind.D8)
            .stream().<DieBody>map(DieKindBody::of).toList();

    private Scenarios() {
    }

    /**
     * Returns the orientation that lays a Die flat on the floor with a face read: that face's reading normal
     * straight up (on a d4, the face itself straight down).
     *
     * @param kind the Die Kind
     * @param face the face to read
     * @return the orientation
     */
    static Orientation flat(DieKind kind, int face) {
        Vector3dc normal = kind.solid().faces().get(face).normal();
        Vector3d up = kind == DieKind.D4 ? new Vector3d(0, -1, 0) : new Vector3d(0, 1, 0);
        Quaterniond q = new Quaterniond().rotationTo(normal, up);
        return new Orientation((float) q.x, (float) q.y, (float) q.z, (float) q.w);
    }

    /**
     * Returns a still start: a Die let go from a pose with no motion.
     *
     * @param position where its centre starts, in cm
     * @param orientation its orientation
     * @return the start
     */
    static DieStart still(Vector3 position, Orientation orientation) {
        return new DieStart(new Pose(position, orientation), Vector3.ZERO, Vector3.ZERO);
    }

    /**
     * Returns a request in {@link #BOX} on {@link #FELT} with given starts.
     *
     * @param dice the Dice
     * @param starts one start per Die
     * @param frozen the Frozen Dice
     * @return the request
     */
    static AttemptRequest given(List<DieBody> dice, List<DieStart> starts, List<FrozenDie> frozen) {
        return new AttemptRequest(dice, new Release.Given(starts), frozen, BOX, FELT);
    }

    /**
     * Simulates an Attempt through the seam on the shared worker.
     *
     * @param request the request
     * @return the Attempt
     */
    static Attempt simulate(AttemptRequest request) {
        return TestWorker.get().submit(simulator -> simulator.simulate(request)).join();
    }

    /**
     * Simulates an Attempt on the shared worker, with its nudges and Frozen Dice.
     *
     * @param request the request
     * @return the Attempt and its details
     */
    static JoltSimulator.Run run(AttemptRequest request) {
        return TestWorker.get().submit(simulator -> ((JoltSimulator) simulator).run(request)).join();
    }

    /**
     * Simulates an Attempt on the shared worker with no Settle Nudges at all.
     *
     * @param request the request
     * @return the Attempt and its details
     */
    static JoltSimulator.Run runWithoutNudges(AttemptRequest request) {
        return TestWorker.get().submit(simulator -> new JoltSimulator(Thread.currentThread(), 0).run(request))
                .join();
    }

    /**
     * Returns the largest angle a Die turned through between two consecutive samples.
     *
     * @param recording the Recording
     * @param die the Die
     * @return the angle in radians
     */
    static double largestTurnPerSample(Recording recording, int die) {
        double largest = 0;
        for (int s = 1; s < recording.samples(); s++) {
            Orientation a = recording.pose(die, s - 1).orientation();
            Orientation b = recording.pose(die, s).orientation();
            double dot = Math.abs(a.x() * b.x() + a.y() * b.y() + a.z() * b.z() + a.w() * b.w());
            largest = Math.max(largest, 2 * Math.acos(Math.min(1, dot)));
        }
        return largest;
    }

    /**
     * Returns the largest distance a Die's centre moved between two consecutive samples.
     *
     * @param recording the Recording
     * @param die the Die
     * @return the distance in cm
     */
    static double largestStepPerSample(Recording recording, int die) {
        double largest = 0;
        for (int s = 1; s < recording.samples(); s++) {
            Vector3 a = recording.pose(die, s - 1).position();
            Vector3 b = recording.pose(die, s).position();
            largest = Math.max(largest, new Vector3(b.x() - a.x(), b.y() - a.y(), b.z() - a.z()).length());
        }
        return largest;
    }
}
