package andysdice.prototype.tray;

import com.github.stephengold.joltjni.BodyCreationSettings;
import com.github.stephengold.joltjni.BodyInterface;
import com.github.stephengold.joltjni.BoxShape;
import com.github.stephengold.joltjni.BroadPhaseLayerInterfaceTable;
import com.github.stephengold.joltjni.ConvexHullShapeSettings;
import com.github.stephengold.joltjni.JobSystemSingleThreaded;
import com.github.stephengold.joltjni.Jolt;
import com.github.stephengold.joltjni.JoltPhysicsObject;
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
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * PROTOTYPE. Pre-simulates a Throw headless on Jolt (ADR-0003, roughly): a fresh single-threaded
 * physics system per attempt, a centimetre world, rounded convex hulls, and a recording of transforms
 * that the renderer plays back. An attempt ends at rest, cocked, escaped or timed out; anything but
 * rest is re-simulated with a new release.
 *
 * <p>Not ADR-0001: the Face Value is read off whatever lands; nothing is chosen first or renumbered.
 * The renderer only needs motion that looks like a real Throw into this tray.
 *
 * <p>Runs only on the caller's thread (the app's single physics thread); not thread-safe.
 */
final class Throws {
    /** Physics steps per second, and recorded samples per second. */
    static final int STEP_HZ = 240, SAMPLE_HZ = 60;
    /** Simulated-time cap per attempt, and attempts per Throw. */
    static final float MAX_SECONDS = 12f;
    static final int MAX_ATTEMPTS = 12;
    /** A die whose up face is tilted more than this from level is cocked. */
    static final float COCKED_DEG = 4f;
    /** Jolt's convex radius in cm: ADR-0008's 0.75 mm edge radius. */
    static final float EDGE_CM = 0.075f;

    private static final int NON_MOVING = 0, MOVING = 1;

    /** One die's transform: position in mm and rotation (model frame to world). */
    record Pose(Vector3f pos, Quaternionf rot) {
        Pose copy() {
            return new Pose(new Vector3f(pos), new Quaternionf(rot));
        }
    }

    /**
     * Transforms of the moving dice at {@link #SAMPLE_HZ}, ending when the last one sleeps.
     *
     * @param data per sample, per die: x, y, z (mm), qx, qy, qz, qw
     */
    record Recording(int dice, int samples, float[] data, float restTime) {
        /** The die's pose at time {@code t} seconds, interpolated; held at the last sample after rest. */
        Pose sample(int die, float t) {
            float f = Math.clamp(t * SAMPLE_HZ, 0, samples - 1);
            int i0 = (int) f, i1 = Math.min(i0 + 1, samples - 1);
            float w = f - i0;
            int a = (i0 * dice + die) * 7, b = (i1 * dice + die) * 7;
            Vector3f p = new Vector3f(data[a], data[a + 1], data[a + 2]).lerp(new Vector3f(data[b], data[b + 1], data[b + 2]), w);
            Quaternionf q = new Quaternionf(data[a + 3], data[a + 4], data[a + 5], data[a + 6])
                    .slerp(new Quaternionf(data[b + 3], data[b + 4], data[b + 5], data[b + 6]), w);
            return new Pose(p, q);
        }
    }

    /** Why an attempt ended. */
    enum Ending {
        REST, COCKED, ESCAPED, TIMED_OUT
    }

    /** The accepted attempt, and what it took to get one. */
    record Result(Recording rec, Ending ending, int attempts, Map<Ending, Integer> endings, long wallMillis, List<String> faces) {
    }

    private final BroadPhaseLayerInterfaceTable bpLayers = new BroadPhaseLayerInterfaceTable(2, 2)
            .mapObjectToBroadPhaseLayer(NON_MOVING, 0).mapObjectToBroadPhaseLayer(MOVING, 1);
    private final ObjVsBpFilter objVsBp = new ObjVsBpFilter(2, 2).disablePair(NON_MOVING, 0);
    private final ObjectLayerPairFilterTable pairs = new ObjectLayerPairFilterTable(2)
            .enableCollision(MOVING, MOVING).enableCollision(MOVING, NON_MOVING);
    private final TempAllocatorImpl temp = new TempAllocatorImpl(16 * 1024 * 1024);
    private final JobSystemSingleThreaded jobs = new JobSystemSingleThreaded(Jolt.cMaxPhysicsJobs);
    private final Map<DieKind, Vec3[]> hulls = new EnumMap<>(DieKind.class);

    /** Loads the native library from {@code dll} and sets Jolt up once per process. Call first. */
    static void loadNatives(Path dll) {
        System.load(dll.toAbsolutePath().toString());
        Jolt.registerDefaultAllocator();
        Jolt.installDefaultAssertCallback();
        Jolt.installDefaultTraceCallback();
        Jolt.newFactory();
        Jolt.registerTypes();
        // Frees the native side of objects nobody closed; the per-attempt systems are closed explicitly.
        JoltPhysicsObject.startCleaner();
    }

    Throws() {
        for (DieKind k : DieKind.values()) {
            List<Vector3f> normals = new ArrayList<>();
            for (DieKind.FaceSpec f : k.faces()) normals.add(f.normal());
            // The sharp solid at the full inradius: Jolt shrinks it by the convex radius and rounds it
            // back out, so the physical die matches the rendered one (same face planes, 0.75 mm edges).
            Solid s = new Solid(normals, k.inradius);
            Vec3[] pts = new Vec3[s.vertices.size()];
            for (int i = 0; i < pts.length; i++) {
                Vector3f v = s.vertices.get(i);
                pts[i] = new Vec3(v.x / 10, v.y / 10, v.z / 10);
            }
            hulls.put(k, pts);
        }
    }

    /**
     * Throws {@code moving} into the tray, around the {@code frozen} dice (a Reroll Throw's resting
     * dice), and retries until an attempt comes to rest flat or {@link #MAX_ATTEMPTS} run out (then the
     * last attempt is returned as it is).
     */
    Result simulate(TrayGeom tray, Params.Surface surface, List<DieKind> moving, List<DieKind> frozenKinds, List<Pose> frozen, long seed) {
        long t0 = System.nanoTime();
        Map<Ending, Integer> endings = new EnumMap<>(Ending.class);
        Attempt last = null;
        int n = 0;
        while (n < MAX_ATTEMPTS) {
            n++;
            last = attempt(tray, surface, moving, frozenKinds, frozen, new SplittableRandom(seed * 1_000_003L + n));
            endings.merge(last.ending, 1, Integer::sum);
            if (last.ending == Ending.REST) break;
        }
        return new Result(last.rec, last.ending, n, endings, (System.nanoTime() - t0) / 1_000_000, last.faces);
    }

    private record Attempt(Recording rec, Ending ending, List<String> faces) {
    }

    private Attempt attempt(TrayGeom tray, Params.Surface surface, List<DieKind> moving, List<DieKind> frozenKinds, List<Pose> frozen,
                            SplittableRandom rnd) {
        PhysicsSystem sys = new PhysicsSystem();
        try {
            sys.init(256, 0, 4096, 4096, bpLayers, objVsBp, pairs);
            sys.setGravity(0, -981f, 0);
            PhysicsSettings ps = sys.getPhysicsSettings();
            // Jolt's defaults are metre-scale; in centimetres most of them are already about right
            // (0.02 cm slop and speculative distance), but sleep needs a looser threshold or dice on felt
            // creep for seconds.
            ps.setPointVelocitySleepThreshold(0.4f);
            ps.setTimeBeforeSleep(0.35f);
            sys.setPhysicsSettings(ps);
            BodyInterface bi = sys.getBodyInterface();

            for (TrayGeom.Box b : tray.physicsBoxes()) {
                boolean floor = b.yaw() == 0 && b.centre().y < 0;
                BodyCreationSettings bcs = new BodyCreationSettings(
                        new BoxShape(b.half().x / 10, b.half().y / 10, b.half().z / 10),
                        new RVec3(b.centre().x / 10, b.centre().y / 10, b.centre().z / 10),
                        new Quat(0, (float) Math.sin(b.yaw() / 2), 0, (float) Math.cos(b.yaw() / 2)),
                        EMotionType.Static, NON_MOVING);
                bcs.setRestitution(floor ? surface.restitution : tray.wallMaterial.restitution);
                bcs.setFriction(floor ? surface.friction : 0.5f);
                bi.createAndAddBody(bcs, EActivation.DontActivate);
            }
            for (int i = 0; i < frozen.size(); i++) {
                Pose p = frozen.get(i);
                BodyCreationSettings bcs = new BodyCreationSettings(hull(frozenKinds.get(i)),
                        new RVec3(p.pos().x / 10, p.pos().y / 10, p.pos().z / 10),
                        new Quat(p.rot().x, p.rot().y, p.rot().z, p.rot().w), EMotionType.Static, NON_MOVING);
                bcs.setRestitution(surface.restitution);
                bcs.setFriction(0.4f);
                bi.createAndAddBody(bcs, EActivation.DontActivate);
            }

            // The release: a loose handful let go low over the near (camera-side, -Z) end and thrown down
            // and away from the camera, so the first bounce lands well short of the far wall. Spacing is
            // wider than a d20's circumscribed diameter (about 25 mm), so no two dice start overlapping.
            float hx = tray.size.halfX, hz = tray.size.halfZ;
            int cols = Math.min(5, moving.size());
            float spacing = 27;
            float cx = (float) rnd.nextDouble(-0.25, 0.25) * hx, cz = -hz + 30;
            float cy = Math.min(tray.wallHeight, 30) + 26;
            float speed = (float) (rnd.nextDouble(65, 115) * Math.sqrt(hz / 100f));
            float vx = (float) rnd.nextDouble(-0.3, 0.3) * speed;
            int[] ids = new int[moving.size()];
            for (int i = 0; i < moving.size(); i++) {
                int col = i % cols, row = (i / cols) % 2, layer = i / (cols * 2);
                float x = cx + (col - (cols - 1) / 2f) * spacing + (float) rnd.nextDouble(-2, 2);
                float z = cz + row * spacing + (float) rnd.nextDouble(-2, 2);
                float y = cy + layer * spacing;
                Quaternionf q = new Quaternionf().rotateXYZ((float) rnd.nextDouble(7), (float) rnd.nextDouble(7), (float) rnd.nextDouble(7));
                BodyCreationSettings bcs = new BodyCreationSettings(hull(moving.get(i)),
                        new RVec3(x / 10, y / 10, z / 10), new Quat(q.x, q.y, q.z, q.w), EMotionType.Dynamic, MOVING);
                bcs.setMotionQuality(EMotionQuality.LinearCast);
                bcs.setRestitution(surface.restitution);
                bcs.setFriction(0.4f);
                bcs.setAngularDamping(surface.angularDamping);
                bcs.setLinearDamping(0.05f);
                bcs.setLinearVelocity(vx + (float) rnd.nextDouble(-10, 10), (float) rnd.nextDouble(-90, -40), speed + (float) rnd.nextDouble(-10, 10));
                bcs.setAngularVelocity((float) rnd.nextDouble(-30, 30), (float) rnd.nextDouble(-30, 30), (float) rnd.nextDouble(-30, 30));
                ids[i] = bi.createAndAddBody(bcs, EActivation.Activate);
            }
            sys.optimizeBroadPhase();

            List<float[]> samples = new ArrayList<>();
            samples.add(snapshot(bi, ids));
            int steps = (int) (MAX_SECONDS * STEP_HZ), perSample = STEP_HZ / SAMPLE_HZ;
            Ending ending = Ending.TIMED_OUT;
            float rest = MAX_SECONDS;
            for (int s = 1; s <= steps; s++) {
                sys.update(1f / STEP_HZ, 1, temp, jobs);
                if (s % perSample != 0) continue;
                float[] snap = snapshot(bi, ids);
                samples.add(snap);
                if (escaped(tray, snap)) {
                    ending = Ending.ESCAPED;
                    rest = s / (float) STEP_HZ;
                    break;
                }
                boolean any = false;
                for (int id : ids) any |= bi.isActive(id);
                if (!any) {
                    rest = s / (float) STEP_HZ;
                    ending = Ending.REST;
                    break;
                }
            }
            float[] last = samples.getLast();
            List<String> faces = new ArrayList<>();
            for (int i = 0; i < moving.size(); i++) {
                Quaternionf q = new Quaternionf(last[i * 7 + 3], last[i * 7 + 4], last[i * 7 + 5], last[i * 7 + 6]);
                String[] face = new String[1];
                float tilt = upFace(moving.get(i), q, face);
                faces.add(face[0]);
                if (ending == Ending.REST && tilt > COCKED_DEG) {
                    ending = Ending.COCKED;
                    if (Boolean.getBoolean("tray.debugCocked")) {
                        // Diagnostic: is it leaning on something (centre high), or asleep on an edge?
                        float lift = last[i * 7 + 1] - moving.get(i).inradius;
                        System.out.printf("cocked %s tilt %.1f deg, centre %.2f mm above flat%n", moving.get(i), tilt, lift);
                    }
                }
            }
            float[] data = new float[samples.size() * moving.size() * 7];
            for (int i = 0; i < samples.size(); i++) System.arraycopy(samples.get(i), 0, data, i * moving.size() * 7, moving.size() * 7);
            return new Attempt(new Recording(moving.size(), samples.size(), data, rest), ending, faces);
        } finally {
            sys.close();
        }
    }

    private ConvexHullShapeSettings hull(DieKind k) {
        return new ConvexHullShapeSettings(hulls.get(k), EDGE_CM);
    }

    private static float[] snapshot(BodyInterface bi, int[] ids) {
        float[] out = new float[ids.length * 7];
        RVec3 p = new RVec3();
        Quat q = new Quat();
        for (int i = 0; i < ids.length; i++) {
            bi.getPositionAndRotation(ids[i], p, q);
            out[i * 7] = (float) p.xx() * 10;
            out[i * 7 + 1] = (float) p.yy() * 10;
            out[i * 7 + 2] = (float) p.zz() * 10;
            out[i * 7 + 3] = q.getX();
            out[i * 7 + 4] = q.getY();
            out[i * 7 + 5] = q.getZ();
            out[i * 7 + 6] = q.getW();
        }
        return out;
    }

    /** A die is out once it is past the outline (with a millimetre of grace) or above the glass. */
    private static boolean escaped(TrayGeom tray, float[] snap) {
        for (int i = 0; i < snap.length; i += 7) {
            float x = snap[i], y = snap[i + 1], z = snap[i + 2];
            if (y < -5 || y > TrayGeom.GLASS_H + 50 || !tray.inside(x, z, -1)) return true;
        }
        return false;
    }

    /**
     * The face that reads as the result, and how far (degrees) it is from level: the face pointing most
     * nearly up, or for the top-read d4 the face pointing most nearly down (it is named for the corner
     * that is then up).
     */
    static float upFace(DieKind kind, Quaternionf rot, String[] label) {
        float best = -2;
        for (DieKind.FaceSpec f : kind.faces()) {
            Vector3f n = rot.transform(new Vector3f(f.normal()).normalize());
            float up = kind == DieKind.D4 ? -n.y : n.y;
            if (up > best) {
                best = up;
                label[0] = f.label();
            }
        }
        return (float) Math.toDegrees(Math.acos(Math.clamp(best, -1, 1)));
    }
}
