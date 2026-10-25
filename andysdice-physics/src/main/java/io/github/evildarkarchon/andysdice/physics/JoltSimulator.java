package io.github.evildarkarchon.andysdice.physics;

import com.github.stephengold.joltjni.BodyCreationSettings;
import com.github.stephengold.joltjni.BodyInterface;
import com.github.stephengold.joltjni.BoxShape;
import com.github.stephengold.joltjni.BroadPhaseLayerInterfaceTable;
import com.github.stephengold.joltjni.CollisionEstimationResult;
import com.github.stephengold.joltjni.ConvexHullShapeSettings;
import com.github.stephengold.joltjni.JobSystemSingleThreaded;
import com.github.stephengold.joltjni.Jolt;
import com.github.stephengold.joltjni.ObjVsBpFilter;
import com.github.stephengold.joltjni.ObjectLayerPairFilterTable;
import com.github.stephengold.joltjni.PhysicsSettings;
import com.github.stephengold.joltjni.PhysicsSystem;
import com.github.stephengold.joltjni.Quat;
import com.github.stephengold.joltjni.RVec3;
import com.github.stephengold.joltjni.TempAllocatorImpl;
import com.github.stephengold.joltjni.Vec3;
import com.github.stephengold.joltjni.enumerate.EActivation;
import com.github.stephengold.joltjni.enumerate.EMotionQuality;
import com.github.stephengold.joltjni.enumerate.EMotionType;
import io.github.evildarkarchon.andysdice.core.simulation.Attempt;
import io.github.evildarkarchon.andysdice.core.simulation.AttemptRequest;
import io.github.evildarkarchon.andysdice.core.simulation.ContactBody;
import io.github.evildarkarchon.andysdice.core.simulation.DieBody;
import io.github.evildarkarchon.andysdice.core.simulation.DieStart;
import io.github.evildarkarchon.andysdice.core.simulation.Ending;
import io.github.evildarkarchon.andysdice.core.simulation.FrozenDie;
import io.github.evildarkarchon.andysdice.core.simulation.Orientation;
import io.github.evildarkarchon.andysdice.core.simulation.OutlinePoint;
import io.github.evildarkarchon.andysdice.core.simulation.Pose;
import io.github.evildarkarchon.andysdice.core.simulation.Recording;
import io.github.evildarkarchon.andysdice.core.simulation.Release;
import io.github.evildarkarchon.andysdice.core.simulation.Simulator;
import io.github.evildarkarchon.andysdice.core.simulation.TrayGeometry;
import io.github.evildarkarchon.andysdice.core.simulation.TrayMaterials;
import io.github.evildarkarchon.andysdice.core.simulation.Vector3;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The Jolt simulator behind {@code core}'s seam (ADR-0003), confined to the physics worker.
 *
 * <p>Each Attempt builds a fresh physics system with a single-threaded job system, steps it at
 * {@value #STEP_HZ} Hz, records every 2nd step, and frees every native object it made before returning. The
 * world is in centimetres, so Jolt's metre-scale defaults (0.02 slop and speculative distance, 0.05 convex
 * radius) read as dice-scale tolerances.
 *
 * <p>Within an Attempt, a Die settling cocked gets up to {@value #SETTLE_NUDGES} Settle Nudges (ADR-0013): once
 * it has been nearly still for {@value #NUDGE_STILL_SECONDS} s while cocked, it is spun about the axis that
 * levels its reading face and given a small hop, so it tips onto the face it is nearest before Jolt puts it to
 * sleep. A nudge draws from no generator, so it never shifts a Face Value or a later Release.
 */
final class JoltSimulator implements Simulator {

    /** Physics steps per second of simulated time (ADR-0014). */
    static final int STEP_HZ = 240;

    /** Settle Nudges per Die per Attempt (ADR-0013; spec §4: a tunable). */
    static final int SETTLE_NUDGES = 3;

    /**
     * How long a Die must be nearly still while cocked before it is nudged, in seconds: the flat-rest
     * prototype's 9 samples at 60 Hz. Well inside Jolt's {@link #TIME_BEFORE_SLEEP}, so the nudge lands before
     * the Die sleeps and it never visibly stops first.
     */
    static final float NUDGE_STILL_SECONDS = 0.15f;

    /** Steps per Recording sample: the Recording keeps every 2nd step. */
    private static final int STEPS_PER_SAMPLE = STEP_HZ / Recording.SAMPLE_RATE_HZ;

    private static final int MAX_STEPS = Math.round(TIME_CAP_SECONDS * STEP_HZ);

    /** {@link #NUDGE_STILL_SECONDS} in Recording samples, the rate at which stillness is checked: 18. */
    private static final int NUDGE_STILL_SAMPLES = Math.round(NUDGE_STILL_SECONDS * Recording.SAMPLE_RATE_HZ);

    /**
     * "Nearly still", as the prototype measured it: the L1 norms of the linear velocity under 0.3 cm/s and of
     * the angular velocity under 0.4 rad/s.
     */
    private static final float STILL_SPEED = 0.3f;
    private static final float STILL_SPIN = 0.4f;

    /** A nudge's spin about the levelling axis, in rad/s, and its upward hop, in cm/s (ADR-0013). */
    private static final float NUDGE_SPIN = 5;
    private static final float NUDGE_HOP = 4;

    /** Object layers: the Tray and Frozen Dice never move and never collide with each other. */
    private static final int NON_MOVING = 0;
    private static final int MOVING = 1;

    /** The Dice's density in kg/cm³ (resin, about 1.2 g/cm³), so contact impulses come out in kg·cm/s. */
    private static final float DIE_DENSITY = 1.2e-3f;

    private static final float LINEAR_DAMPING = 0.05f;

    /**
     * Sleep: Jolt's default 3 cm/s would be right in metres, but in centimetres Dice on felt creep for seconds
     * before sleeping, so the flat-rest prototype's 0.4 cm/s over 0.35 s is used (ADR-0013).
     */
    private static final float SLEEP_VELOCITY = 0.4f;
    private static final float TIME_BEFORE_SLEEP = 0.35f;

    /**
     * How far, in cm, Jolt may let a rounded hull stand inside its sharp one before it shrinks the convex
     * radius. Jolt's default (0.05, meant for metres) would cut the d4's rounding to 0.25 mm and the d6's and
     * d8's to 0.68 mm, because rounding a sharp corner by r pulls it in by up to 2r (the d4's tips). Rounding
     * the corners is the point here (the rendered Die is rounded the same way, ADR-0008), so the limit is set
     * far above any Die Kind's.
     */
    static final float MAX_ROUNDING_ERROR = 1;

    /** The walls' and floor's thickness, in cm; only their inner faces matter. */
    private static final float WALL_THICKNESS = 1.4f;
    private static final float FLOOR_THICKNESS = 4;

    /** A Die has escaped once its centre is this far beyond the outline, in cm: a millimetre of grace. */
    private static final float ESCAPE_MARGIN = 0.1f;
    /** ... or this far below the floor's top, which only a Die that went over a wall can reach. */
    private static final float ESCAPE_BELOW = -1;

    /**
     * An Attempt with what the tests need to see beyond the seam.
     *
     * @param attempt the Attempt
     * @param nudges the Settle Nudges each thrown Die got, in the request's order
     * @param frozenAtEnd each Frozen Die's pose when the Attempt ended, in the request's order
     */
    record Run(Attempt attempt, List<Integer> nudges, List<Pose> frozenAtEnd) {
    }

    private final Thread worker;
    private final int settleNudges;

    /**
     * Creates the simulator for the worker it will run on. Jolt must already be started.
     *
     * @param worker the physics worker's thread
     */
    JoltSimulator(Thread worker) {
        this(worker, SETTLE_NUDGES);
    }

    /**
     * Creates a simulator that gives each Die a stated number of Settle Nudges, for scenario tests that need
     * to see what a Throw does without them.
     *
     * @param worker the physics worker's thread
     * @param settleNudges the most Settle Nudges per Die per Attempt; 0 for none
     */
    JoltSimulator(Thread worker, int settleNudges) {
        this.worker = worker;
        this.settleNudges = settleNudges;
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalStateException if called from any thread but the physics worker, or if Jolt reports an
     *     error during a step
     */
    @Override
    public Attempt simulate(AttemptRequest request) {
        return run(request).attempt();
    }

    /**
     * Simulates one Attempt, as {@link #simulate}, and also reports its nudges and Frozen Dice.
     *
     * @param request the Dice, Release, Frozen Dice and Tray
     * @return the Attempt with its details
     * @throws IllegalStateException if called from any thread but the physics worker, or if Jolt reports an
     *     error during a step
     */
    Run run(AttemptRequest request) {
        if (Thread.currentThread() != worker) {
            throw new IllegalStateException("The Jolt simulator runs only on the physics worker, not on "
                    + Thread.currentThread().getName());
        }
        List<DieStart> starts = switch (request.release()) {
            case Release.Given given -> given.starts();
            case Release.Drawn drawn -> DrawnRelease.draw(drawn.generator(), request.dice().size(), request.tray());
        };
        try (NativeScope scope = new NativeScope()) {
            return run(scope, request, starts);
        }
    }

    /** Builds the Attempt's world in {@code scope}, steps it to an ending and records it. */
    private Run run(NativeScope scope, AttemptRequest request, List<DieStart> starts) {
        BroadPhaseLayerInterfaceTable broadPhaseLayers = scope.own(new BroadPhaseLayerInterfaceTable(2, 2))
                .mapObjectToBroadPhaseLayer(NON_MOVING, 0)
                .mapObjectToBroadPhaseLayer(MOVING, 1);
        ObjVsBpFilter objectVsBroadPhase = scope.own(new ObjVsBpFilter(2, 2)).disablePair(NON_MOVING, 0);
        ObjectLayerPairFilterTable layerPairs = scope.own(new ObjectLayerPairFilterTable(2))
                .enableCollision(MOVING, MOVING)
                .enableCollision(MOVING, NON_MOVING);
        TempAllocatorImpl temp = scope.own(new TempAllocatorImpl(16 * 1024 * 1024));
        JobSystemSingleThreaded jobs = scope.own(new JobSystemSingleThreaded(Jolt.cMaxPhysicsJobs));
        CollisionEstimationResult estimate = scope.own(new CollisionEstimationResult());
        PhysicsSettings settings = scope.own(new PhysicsSettings());
        settings.setPointVelocitySleepThreshold(SLEEP_VELOCITY);
        settings.setTimeBeforeSleep(TIME_BEFORE_SLEEP);
        ContactRecorder contacts = scope.own(new ContactRecorder(request.materials(),
                settings.getMinVelocityForRestitution(), estimate));

        // Everything the system points to is owned above, so the scope frees the system first.
        PhysicsSystem system = new PhysicsSystem();
        scope.onClose(() -> {
            system.destroyAllBodies();
            system.forgetMe(); // else jolt-jni's static address-to-system map keeps it reachable
            system.close();
        });
        system.init(256, 0, 4096, 4096, broadPhaseLayers, objectVsBroadPhase, layerPairs);
        system.setGravity(GRAVITY.x(), GRAVITY.y(), GRAVITY.z());
        system.setPhysicsSettings(settings);
        system.setContactListener(contacts);

        BodyInterface bodies = system.getBodyInterface();
        addTray(scope, bodies, contacts, request.tray());
        Map<DieBody, ConvexHullShapeSettings> hulls = new IdentityHashMap<>();
        int[] frozenIds = new int[request.frozen().size()];
        for (int i = 0; i < frozenIds.length; i++) {
            FrozenDie frozen = request.frozen().get(i);
            // A static body on the non-moving layer: thrown Dice strike it, but nothing can move it.
            BodyCreationSettings body = scope.own(new BodyCreationSettings(hull(scope, hulls, frozen.body()),
                    position(frozen.pose().position()), rotation(frozen.pose().orientation()), EMotionType.Static,
                    NON_MOVING));
            frozenIds[i] = bodies.createAndAddBody(body, EActivation.DontActivate);
            contacts.register(frozenIds[i], ContactBody.frozenDie(i));
        }
        int[] ids = new int[request.dice().size()];
        for (int i = 0; i < ids.length; i++) {
            DieStart start = starts.get(i);
            BodyCreationSettings body = scope.own(new BodyCreationSettings(hull(scope, hulls, request.dice().get(i)),
                    position(start.pose().position()), rotation(start.pose().orientation()), EMotionType.Dynamic,
                    MOVING));
            body.setMotionQuality(EMotionQuality.LinearCast);
            body.setAngularDamping(request.materials().dieAngularDamping());
            body.setLinearDamping(LINEAR_DAMPING);
            body.setLinearVelocity(vector(start.linearVelocity()));
            body.setAngularVelocity(vector(start.angularVelocity()));
            ids[i] = bodies.createAndAddBody(body, EActivation.Activate);
            contacts.register(ids[i], ContactBody.die(i));
        }
        system.optimizeBroadPhase();

        int[] nudges = new int[ids.length];
        Attempt attempt = step(system, bodies, ids, nudges, contacts, temp, jobs, request);
        List<Integer> nudgeList = new ArrayList<>(nudges.length);
        for (int n : nudges) {
            nudgeList.add(n);
        }
        return new Run(attempt, List.copyOf(nudgeList), poses(bodies, frozenIds));
    }

    /** Steps the world until the Dice rest, one escapes, or the time cap passes. */
    private Attempt step(PhysicsSystem system, BodyInterface bodies, int[] ids, int[] nudges,
            ContactRecorder contacts, TempAllocatorImpl temp, JobSystemSingleThreaded jobs, AttemptRequest request) {
        List<float[]> samples = new ArrayList<>();
        RVec3 position = new RVec3();
        Quat rotation = new Quat();
        int[] still = new int[ids.length];
        samples.add(snapshot(bodies, ids, position, rotation));
        for (int step = 1; step <= MAX_STEPS; step++) {
            contacts.beginStep(step / (float) STEP_HZ);
            int errors = system.update(1f / STEP_HZ, 1, temp, jobs);
            contacts.rethrowFailure();
            if (errors != 0) {
                throw new IllegalStateException("Jolt reported update errors " + errors + " at step " + step);
            }
            // At rest is polled here, after every step, never through Jolt's activation callbacks (ADR-0003).
            boolean resting = true;
            for (int id : ids) {
                resting &= !bodies.isActive(id);
            }
            boolean sampleStep = step % STEPS_PER_SAMPLE == 0;
            if (!sampleStep && !resting) {
                continue;
            }
            // A rest on an odd step is recorded at the next sample time: sleeping Dice don't move, so the
            // poses now are the poses then.
            float[] sample = snapshot(bodies, ids, position, rotation);
            samples.add(sample);
            if (escaped(request.tray(), sample)) {
                return attempt(Ending.ESCAPED, request, samples, (samples.size() - 1) / (float) Recording.SAMPLE_RATE_HZ,
                        contacts);
            }
            // Stillness is counted in samples, so only on sample steps. A nudge wakes its Die, so the Dice
            // aren't at rest after all.
            if (sampleStep && nudge(bodies, ids, request.dice(), sample, still, nudges)) {
                resting = false;
            }
            if (resting) {
                return attempt(Ending.AT_REST, request, samples, step / (float) STEP_HZ, contacts);
            }
        }
        return attempt(Ending.TIMED_OUT, request, samples, TIME_CAP_SECONDS, contacts);
    }

    /**
     * Gives a Settle Nudge to each Die that has been nearly still and cocked for {@link #NUDGE_STILL_SAMPLES}
     * samples and has nudges left (the flat-rest prototype's {@code Throws.nudge}). The nudge sets the Die
     * spinning at {@link #NUDGE_SPIN} about {@code n × up}, where {@code n} is its reading normal: then
     * {@code dn/dt = ω × n} is the part of straight up perpendicular to {@code n}, so the reading normal turns
     * straight towards vertical and the Die tips onto the face it was already nearest. The small hop lifts it
     * clear of what it rests on as it turns.
     *
     * @param sample the poses just recorded
     * @param still per Die, how many samples in a row it has been nearly still and cocked; updated
     * @param nudges per Die, the nudges given so far; updated
     * @return {@code true} if any Die was nudged
     */
    private boolean nudge(BodyInterface bodies, int[] ids, List<DieBody> dice, float[] sample, int[] still,
            int[] nudges) {
        boolean any = false;
        for (int i = 0; i < ids.length; i++) {
            Vec3 v = bodies.getLinearVelocity(ids[i]);
            Vec3 w = bodies.getAngularVelocity(ids[i]);
            boolean slow = Math.abs(v.getX()) + Math.abs(v.getY()) + Math.abs(v.getZ()) < STILL_SPEED
                    && Math.abs(w.getX()) + Math.abs(w.getY()) + Math.abs(w.getZ()) < STILL_SPIN;
            int at = i * 7;
            Orientation orientation = new Orientation(sample[at + 3], sample[at + 4], sample[at + 5], sample[at + 6]);
            still[i] = slow && dice.get(i).isCocked(orientation) ? still[i] + 1 : 0;
            if (still[i] < NUDGE_STILL_SAMPLES || nudges[i] >= settleNudges) {
                continue;
            }
            Vector3 n = dice.get(i).readingNormal(orientation);
            // n × (0, 1, 0) = (-n.z, 0, n.x); it vanishes only for a level face, which is never cocked.
            float ax = -n.z();
            float az = n.x();
            float length = (float) Math.sqrt(ax * ax + az * az);
            if (!(length > 1e-6f)) {
                continue;
            }
            bodies.setLinearAndAngularVelocity(ids[i], 0, NUDGE_HOP, 0, ax / length * NUDGE_SPIN, 0,
                    az / length * NUDGE_SPIN);
            bodies.activateBody(ids[i]);
            still[i] = 0;
            nudges[i]++;
            any = true;
        }
        return any;
    }

    /** Builds the Attempt, turning a rest into cocked if any Die rests cocked. */
    private static Attempt attempt(Ending ending, AttemptRequest request, List<float[]> samples, float endTime,
            ContactRecorder contacts) {
        int perSample = samples.getFirst().length;
        float[] data = new float[samples.size() * perSample];
        for (int s = 0; s < samples.size(); s++) {
            System.arraycopy(samples.get(s), 0, data, s * perSample, perSample);
        }
        Recording recording = new Recording(request.dice().size(), data, endTime, contacts.events());
        Set<Integer> cocked = new TreeSet<>();
        if (ending == Ending.AT_REST) {
            for (int i = 0; i < request.dice().size(); i++) {
                if (request.dice().get(i).isCocked(recording.finalPose(i).orientation())) {
                    cocked.add(i);
                }
            }
        }
        return new Attempt(cocked.isEmpty() ? ending : Ending.COCKED, recording, cocked);
    }

    /**
     * Adds the floor and one wall box per outline edge. Each wall's inner face stands on its edge, and each is
     * one thickness longer at both ends so neighbouring walls overlap at the corners.
     */
    private static void addTray(NativeScope scope, BodyInterface bodies, ContactRecorder contacts,
            TrayGeometry tray) {
        float reach = 0;
        for (OutlinePoint p : tray.outline()) {
            reach = Math.max(reach, Math.max(Math.abs(p.x()), Math.abs(p.z())));
        }
        reach += 2 * WALL_THICKNESS;
        BoxShape floorShape = scope.own(new BoxShape(reach, FLOOR_THICKNESS / 2, reach));
        BodyCreationSettings floor = scope.own(new BodyCreationSettings(floorShape,
                new RVec3(0, -FLOOR_THICKNESS / 2, 0), new Quat(), EMotionType.Static, NON_MOVING));
        contacts.register(bodies.createAndAddBody(floor, EActivation.DontActivate), ContactBody.floor());

        for (int i = 0; i < tray.wallCount(); i++) {
            OutlinePoint a = tray.outline().get(i);
            OutlinePoint b = tray.outline().get((i + 1) % tray.wallCount());
            float dx = b.x() - a.x();
            float dz = b.z() - a.z();
            float length = (float) Math.sqrt(dx * dx + dz * dz);
            Vector3 inward = tray.inwardNormal(i);
            float half = WALL_THICKNESS / 2;
            // Turning by yaw about +y takes the box's x axis to (cos yaw, 0, -sin yaw): the edge's direction.
            float yaw = (float) Math.atan2(-dz, dx);
            BoxShape shape = scope.own(new BoxShape(length / 2 + WALL_THICKNESS, tray.wallHeight() / 2, half));
            BodyCreationSettings wall = scope.own(new BodyCreationSettings(shape,
                    new RVec3((a.x() + b.x()) / 2 - inward.x() * half, tray.wallHeight() / 2,
                            (a.z() + b.z()) / 2 - inward.z() * half),
                    new Quat(0, (float) Math.sin(yaw / 2), 0, (float) Math.cos(yaw / 2)), EMotionType.Static,
                    NON_MOVING));
            contacts.register(bodies.createAndAddBody(wall, EActivation.DontActivate), ContactBody.wall());
        }
    }

    /** Returns the rounded hull of a Die's solid, made once per Die body in an Attempt. */
    private static ConvexHullShapeSettings hull(NativeScope scope, Map<DieBody, ConvexHullShapeSettings> hulls,
            DieBody die) {
        return hulls.computeIfAbsent(die, d -> scope.own(hullSettings(d)));
    }

    /**
     * Returns the settings of a Die's rounded hull: the sharp vertices with the edge radius as Jolt's convex
     * radius. Jolt shrinks the hull by that radius and rounds it back out, so the body keeps the solid's face
     * planes and the rendered Die's rounded edges (ADR-0003). {@link #MAX_ROUNDING_ERROR} keeps Jolt from
     * shrinking the radius at sharp corners.
     *
     * @param die the Die
     * @return new settings, which the caller must close
     */
    static ConvexHullShapeSettings hullSettings(DieBody die) {
        Vec3[] points = die.vertices().stream().map(JoltSimulator::vector).toArray(Vec3[]::new);
        ConvexHullShapeSettings settings = new ConvexHullShapeSettings(points, die.edgeRadius());
        settings.setMaxErrorConvexRadius(MAX_ROUNDING_ERROR);
        settings.setDensity(DIE_DENSITY);
        return settings;
    }

    /** Copies every Die's pose into one sample: x, y, z, then the quaternion's x, y, z, w. */
    private static float[] snapshot(BodyInterface bodies, int[] ids, RVec3 position, Quat rotation) {
        float[] sample = new float[ids.length * 7];
        for (int i = 0; i < ids.length; i++) {
            bodies.getPositionAndRotation(ids[i], position, rotation);
            int at = i * 7;
            sample[at] = (float) position.xx();
            sample[at + 1] = (float) position.yy();
            sample[at + 2] = (float) position.zz();
            sample[at + 3] = rotation.getX();
            sample[at + 4] = rotation.getY();
            sample[at + 5] = rotation.getZ();
            sample[at + 6] = rotation.getW();
        }
        return sample;
    }

    /** Reads bodies' current poses. */
    private static List<Pose> poses(BodyInterface bodies, int[] ids) {
        float[] sample = snapshot(bodies, ids, new RVec3(), new Quat());
        List<Pose> poses = new ArrayList<>(ids.length);
        for (int at = 0; at < sample.length; at += 7) {
            poses.add(new Pose(new Vector3(sample[at], sample[at + 1], sample[at + 2]),
                    new Orientation(sample[at + 3], sample[at + 4], sample[at + 5], sample[at + 6])));
        }
        return List.copyOf(poses);
    }

    /** A Die has escaped once it has gone over a wall: its centre is beyond the outline or below the floor. */
    private static boolean escaped(TrayGeometry tray, float[] sample) {
        for (int at = 0; at < sample.length; at += 7) {
            if (sample[at + 1] < ESCAPE_BELOW || tray.depthInside(sample[at], sample[at + 2]) < -ESCAPE_MARGIN) {
                return true;
            }
        }
        return false;
    }

    private static Vec3 vector(Vector3 v) {
        return new Vec3(v.x(), v.y(), v.z());
    }

    private static RVec3 position(Vector3 v) {
        return new RVec3(v.x(), v.y(), v.z());
    }

    private static Quat rotation(Orientation o) {
        return new Quat(o.x(), o.y(), o.z(), o.w());
    }
}
