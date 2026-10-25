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
 * <p>Not here yet (ticket #37): Settle Nudges and the full tuning of every Die Kind.
 */
final class JoltSimulator implements Simulator {

    /** Physics steps per second of simulated time (ADR-0014). */
    static final int STEP_HZ = 240;

    /** Steps per Recording sample: the Recording keeps every 2nd step. */
    private static final int STEPS_PER_SAMPLE = STEP_HZ / Recording.SAMPLE_RATE_HZ;

    private static final int MAX_STEPS = Math.round(TIME_CAP_SECONDS * STEP_HZ);

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

    /** The walls' and floor's thickness, in cm; only their inner faces matter. */
    private static final float WALL_THICKNESS = 1.4f;
    private static final float FLOOR_THICKNESS = 4;

    /** A Die has escaped once its centre is this far beyond the outline, in cm: a millimetre of grace. */
    private static final float ESCAPE_MARGIN = 0.1f;
    /** ... or this far below the floor's top, which only a Die that went over a wall can reach. */
    private static final float ESCAPE_BELOW = -1;

    private final Thread worker;

    /**
     * Creates the simulator for the worker it will run on. Jolt must already be started.
     *
     * @param worker the physics worker's thread
     */
    JoltSimulator(Thread worker) {
        this.worker = worker;
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalStateException if called from any thread but the physics worker, or if Jolt reports an
     *     error during a step
     */
    @Override
    public Attempt simulate(AttemptRequest request) {
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
    private Attempt run(NativeScope scope, AttemptRequest request, List<DieStart> starts) {
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
        for (int i = 0; i < request.frozen().size(); i++) {
            FrozenDie frozen = request.frozen().get(i);
            BodyCreationSettings body = scope.own(new BodyCreationSettings(hull(scope, hulls, frozen.body()),
                    position(frozen.pose().position()), rotation(frozen.pose().orientation()), EMotionType.Static,
                    NON_MOVING));
            contacts.register(bodies.createAndAddBody(body, EActivation.DontActivate), ContactBody.frozenDie(i));
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

        return step(system, bodies, ids, contacts, temp, jobs, request);
    }

    /** Steps the world until the Dice rest, one escapes, or the time cap passes. */
    private static Attempt step(PhysicsSystem system, BodyInterface bodies, int[] ids, ContactRecorder contacts,
            TempAllocatorImpl temp, JobSystemSingleThreaded jobs, AttemptRequest request) {
        List<float[]> samples = new ArrayList<>();
        RVec3 position = new RVec3();
        Quat rotation = new Quat();
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
            if (step % STEPS_PER_SAMPLE != 0 && !resting) {
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
            if (resting) {
                return attempt(Ending.AT_REST, request, samples, step / (float) STEP_HZ, contacts);
            }
        }
        return attempt(Ending.TIMED_OUT, request, samples, TIME_CAP_SECONDS, contacts);
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

    /**
     * Returns the rounded hull of a Die's solid: the sharp vertices with the edge radius as Jolt's convex
     * radius. Jolt shrinks the hull by that radius and rounds it back out, so the body keeps the solid's face
     * planes and the rendered Die's rounded edges (ADR-0003).
     */
    private static ConvexHullShapeSettings hull(NativeScope scope, Map<DieBody, ConvexHullShapeSettings> hulls,
            DieBody die) {
        return hulls.computeIfAbsent(die, d -> {
            Vec3[] points = d.vertices().stream().map(JoltSimulator::vector).toArray(Vec3[]::new);
            ConvexHullShapeSettings settings = scope.own(new ConvexHullShapeSettings(points, d.edgeRadius()));
            settings.setDensity(DIE_DENSITY);
            return settings;
        });
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
