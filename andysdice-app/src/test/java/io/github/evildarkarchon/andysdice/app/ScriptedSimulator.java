package io.github.evildarkarchon.andysdice.app;

import io.github.evildarkarchon.andysdice.core.geometry.DieKind;
import io.github.evildarkarchon.andysdice.core.simulation.Attempt;
import io.github.evildarkarchon.andysdice.core.simulation.AttemptRequest;
import io.github.evildarkarchon.andysdice.core.simulation.ContactBody;
import io.github.evildarkarchon.andysdice.core.simulation.ContactEvent;
import io.github.evildarkarchon.andysdice.core.simulation.DieKindBody;
import io.github.evildarkarchon.andysdice.core.simulation.Ending;
import io.github.evildarkarchon.andysdice.core.simulation.Recording;
import io.github.evildarkarchon.andysdice.core.simulation.Simulator;
import io.github.evildarkarchon.andysdice.core.simulation.Vector3;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * The fake simulator app's tests stand in for Jolt (ADR-0011): it ends each Attempt as scripted (at rest once the
 * script runs out) with a 50/120 s Recording of every thrown Die falling from its Release to lie flat on a face, at
 * a position unique to the Attempt and the Die. Its Recordings never move Frozen Dice, as a real one's never do.
 * The planner then turns each Die so its chosen Face Value is up, as it would a real landing.
 *
 * <p>Not thread-safe: used on one thread (the test's, or the JavaFX thread in UI tests).
 */
final class ScriptedSimulator implements Simulator {
    private static final int SAMPLES = 51;
    /** When each Die strikes the floor, if {@link #clatter} is set: well before it comes to rest. */
    static final float CONTACT_TIME = 0.25f;
    final Deque<Ending> script = new ArrayDeque<>();
    /**
     * Whether each thrown Die strikes the floor once, at {@link #CONTACT_TIME}, for the sound tests (#59). Off, the
     * Recordings have no contacts, as before.
     */
    boolean clatter;
    /** Every request simulated, in order. */
    final List<AttemptRequest> requests = new ArrayList<>();
    private boolean failNext;

    /** Scripts the next {@code times} Attempts to end in {@code ending}; a cocked one cocks request Die 0. */
    void then(int times, Ending ending) {
        for (int i = 0; i < times; i++) {
            script.add(ending);
        }
    }

    /** Makes the next Attempt throw, as a crashed engine would. */
    void failNext() {
        failNext = true;
    }

    @Override
    public Attempt simulate(AttemptRequest request) {
        if (failNext) {
            failNext = false;
            throw new IllegalStateException("the scripted simulator was told to fail");
        }
        int call = requests.size();
        requests.add(request);
        Ending ending = script.isEmpty() ? Ending.AT_REST : script.poll();
        int dice = request.dice().size();
        float[] poses = new float[SAMPLES * dice * 7];
        for (int die = 0; die < dice; die++) {
            DieKind kind = ((DieKindBody) request.dice().get(die)).kind();
            Vector3 from = new Vector3(3 * die - 6, 8, -5);
            // An escaped Attempt ends with its Dice over the far wall, outside the Tray and below its floor.
            Vector3 to = ending == Ending.ESCAPED
                    ? new Vector3(3 * die, -2, 25)
                    : new Vector3(call, (float) kind.inradius() / 10, die);
            Quaterniond end = flat(kind, (call * 7 + die * 3) % kind.faceCount(), call + die);
            for (int s = 0; s < SAMPLES; s++) {
                double u = s / (double) (SAMPLES - 1);
                Quaterniond q = new Quaterniond().nlerp(end, u);
                int at = (s * dice + die) * 7;
                poses[at] = (float) (from.x() + (to.x() - from.x()) * u);
                poses[at + 1] = (float) (from.y() + (to.y() - from.y()) * u);
                poses[at + 2] = (float) (from.z() + (to.z() - from.z()) * u);
                poses[at + 3] = (float) q.x;
                poses[at + 4] = (float) q.y;
                poses[at + 5] = (float) q.z;
                poses[at + 6] = (float) q.w;
            }
        }
        // At rest on the last sample. 50/120 s doesn't survive the float round trip exactly, as a real
        // Recording's length often doesn't, so reading the pose "at the duration" lands just short of it.
        List<ContactEvent> contacts = new ArrayList<>();
        for (int die = 0; clatter && die < dice; die++) {
            contacts.add(new ContactEvent(CONTACT_TIME, ContactBody.die(die), ContactBody.floor(), 0.5f,
                    new Vector3(3 * die - 6, 0, 0)));
        }
        Recording recording = new Recording(dice, poses, (SAMPLES - 1) / (float) Recording.SAMPLE_RATE_HZ,
                contacts);
        return new Attempt(ending, recording, ending == Ending.COCKED ? Set.of(0) : Set.of());
    }

    /** Lying flat on {@code face}, turned about the vertical; a d4 lies on the face it reads. */
    private static Quaterniond flat(DieKind kind, int face, double yaw) {
        Vector3dc normal = kind.solid().faces().get(face).normal();
        Vector3d target = new Vector3d(0, kind == DieKind.D4 ? -1 : 1, 0);
        Quaterniond q = new Quaterniond().rotationY(yaw).mul(new Quaterniond().rotationTo(normal, target));
        // The short way from the Release's identity, so the fall turns the least.
        return q.w < 0 ? new Quaterniond(-q.x, -q.y, -q.z, -q.w) : q;
    }
}
