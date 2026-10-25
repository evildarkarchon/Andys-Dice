package io.github.evildarkarchon.andysdice.core.simulation;

import io.github.evildarkarchon.andysdice.core.geometry.DieKind;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.random.RandomGenerator;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * The fake simulator of {@code core}'s test fixtures (spec §5): it ends each Attempt however its script says,
 * so a test can make any ending happen any number of times, and it remembers every request it was given.
 *
 * <p>Once the script runs out, every Attempt comes to rest. Each Attempt's Recording has two samples: the
 * Release, with every Die above the Tray's centre, and the final pose. A Die that comes to rest lies flat on a
 * face (the face and a turn about the vertical drawn from the Release generator when the Release is drawn, as a
 * real simulator draws its Release; face 0 for a given Release), at a position no other Attempt or Die shares,
 * so a test can tell exactly which Attempt a Frozen Die's pose came from. It needs {@link DieKindBody} bodies.
 *
 * <p>It does no physics and no Settle Nudges (ADR-0013 lets a fake skip them).
 */
public final class FakeSimulator implements Simulator {

    /**
     * One Attempt the fake simulated.
     *
     * @param request what it was asked
     * @param attempt what it answered
     */
    public record Call(AttemptRequest request, Attempt attempt) {
    }

    /** One scripted ending, with the request indices of the Dice it leaves cocked. */
    private record Step(Ending ending, Set<Integer> cocked) {
    }

    private final Deque<Step> script = new ArrayDeque<>();
    private final List<Call> calls = new ArrayList<>();

    /** Creates a fake with an empty script: every Attempt comes to rest. */
    public FakeSimulator() {
    }

    /**
     * Scripts the next {@code times} Attempts to end in {@code ending}. A cocked ending leaves the first Die of
     * the request cocked; use {@link #thenCocked} to name others.
     *
     * @param times how many Attempts in a row
     * @param ending how each ends
     * @return this fake, to script more
     */
    public FakeSimulator then(int times, Ending ending) {
        return add(times, ending, ending == Ending.COCKED ? Set.of(0) : Set.of());
    }

    /**
     * Scripts the next {@code times} Attempts to end cocked, with these Dice cocked.
     *
     * @param times how many Attempts in a row
     * @param cocked the Cocked Dice, as indices into each request's Dice; not empty
     * @return this fake, to script more
     */
    public FakeSimulator thenCocked(int times, Integer... cocked) {
        return add(times, Ending.COCKED, Set.of(cocked));
    }

    private FakeSimulator add(int times, Ending ending, Set<Integer> cocked) {
        for (int i = 0; i < times; i++) {
            script.add(new Step(ending, cocked));
        }
        return this;
    }

    /**
     * Returns every Attempt simulated so far, oldest first.
     *
     * @return the calls; a copy
     */
    public List<Call> calls() {
        return List.copyOf(calls);
    }

    /**
     * Returns every request received so far, oldest first.
     *
     * @return the requests; a copy
     */
    public List<AttemptRequest> requests() {
        return calls.stream().map(Call::request).toList();
    }

    /**
     * Returns how many scripted endings are still to come before every Attempt comes to rest.
     *
     * @return the steps left
     */
    public int scriptLeft() {
        return script.size();
    }

    @Override
    public Attempt simulate(AttemptRequest request) {
        Step step = script.isEmpty() ? new Step(Ending.AT_REST, Set.of()) : script.poll();
        RandomGenerator release = request.release() instanceof Release.Drawn(RandomGenerator generator)
                ? generator : null;
        int dice = request.dice().size();
        int call = calls.size();
        float[] poses = new float[2 * dice * 7];
        for (int die = 0; die < dice; die++) {
            put(poses, die, new Vector3(0, 10, 0), Orientation.IDENTITY);
            DieKind kind = ((DieKindBody) request.dice().get(die)).kind();
            // Positions unique per Attempt and Die: x counts Attempts, z counts Dice.
            Vector3 at = new Vector3(call, (float) kind.inradius() / 10, die);
            put(poses, dice + die, at, flat(kind, release));
        }
        // Every ending stops one sample after the Release: the planner only reads the ending and final poses.
        Recording recording = new Recording(dice, poses, 1f / Recording.SAMPLE_RATE_HZ, List.of());
        Attempt attempt = new Attempt(step.ending(), recording, step.cocked());
        calls.add(new Call(request, attempt));
        return attempt;
    }

    /** An orientation lying flat on one face, turned about the vertical; a d4 lies on the face it reads. */
    private static Orientation flat(DieKind kind, RandomGenerator release) {
        int face = release == null ? 0 : release.nextInt(kind.faceCount());
        double yaw = release == null ? 0 : release.nextDouble(2 * Math.PI);
        Vector3dc normal = kind.solid().faces().get(face).normal();
        Vector3d target = new Vector3d(0, kind == DieKind.D4 ? -1 : 1, 0);
        Quaterniond q = new Quaterniond().rotationY(yaw).mul(new Quaterniond().rotationTo(normal, target));
        return new Orientation((float) q.x, (float) q.y, (float) q.z, (float) q.w);
    }

    private static void put(float[] poses, int slot, Vector3 position, Orientation orientation) {
        int at = slot * 7;
        poses[at] = position.x();
        poses[at + 1] = position.y();
        poses[at + 2] = position.z();
        poses[at + 3] = orientation.x();
        poses[at + 4] = orientation.y();
        poses[at + 5] = orientation.z();
        poses[at + 6] = orientation.w();
    }

}
