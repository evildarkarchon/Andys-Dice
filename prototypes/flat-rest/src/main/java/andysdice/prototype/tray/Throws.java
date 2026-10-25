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
 * <p>Forked from the tray prototype for "How does a 20-Dice Throw reach a flat rest?" (issue #21): every
 * option that ticket measures is a {@link Knobs} field, and each attempt reports every Die's tilt and why
 * it is cocked, so one run can be scored at several Cocked Die tolerances afterwards ({@link FlatRest}).
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

    /** How the handful is let go. */
    enum Release {
        /** The tray prototype's: 5 columns, 2 rows, stacked in layers past 10 dice, thrown hard. */
        HANDFUL,
        /** One layer: 7 columns by 3 rows at 30 mm, so no die starts above another. Same speed. */
        SPREAD,
        /** The handful, thrown about 35% slower, more gently down, with less spin. */
        GENTLE,
        /** Spread and gentle together. */
        SPREAD_GENTLE
    }

    /**
     * The options issue #21 measures. {@code dieFriction} is the die's own friction; the floor's and
     * walls' are rescaled so a die-on-floor and die-on-wall contact keeps the tray prototype's combined
     * friction (Jolt combines frictions as the geometric mean), and only die-on-die friction changes.
     *
     * @param nudges   most nudges per die per attempt; 0 = never nudge (see {@link #attempt})
     * @param nudgeDeg a slow die tilted more than this is nudged
     */
    record Knobs(String name, Release release, float dieFriction, float sleepVelocity, float sleepTime, int nudges, float nudgeDeg) {
        static final Knobs BASE = new Knobs("base", Release.HANDFUL, 0.4f, 0.4f, 0.35f, 0, 4f);

        Knobs with(String name, Release r) {
            return new Knobs(name, r, dieFriction, sleepVelocity, sleepTime, nudges, nudgeDeg);
        }

        Knobs friction(String name, float f) {
            return new Knobs(name, release, f, sleepVelocity, sleepTime, nudges, nudgeDeg);
        }

        Knobs sleep(String name, float v, float t) {
            return new Knobs(name, release, dieFriction, v, t, nudges, nudgeDeg);
        }

        Knobs nudging(String name, int n, float deg) {
            return new Knobs(name, release, dieFriction, sleepVelocity, sleepTime, n, deg);
        }
    }

    /** The tray prototype's combined die-on-floor and die-on-wall friction come from a 0.4 die. */
    private static final float REFERENCE_DIE_FRICTION = 0.4f, REFERENCE_WALL_FRICTION = 0.5f;

    /** Knobs for the interactive app (TrayApp); FlatRest passes its own. */
    static volatile Knobs appKnobs = Knobs.BASE;

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

        /** Every die's pose in the last sample. */
        List<Pose> last() {
            List<Pose> out = new ArrayList<>();
            for (int i = 0; i < dice; i++) out.add(sample(i, Float.MAX_VALUE));
            return out;
        }
    }

    /** Why an attempt ended. */
    enum Ending {
        REST, COCKED, ESCAPED, TIMED_OUT
    }

    /** What a tilted die is resting on: another die (moving or frozen), a wall, or only the floor. */
    enum Lean {
        DIE, WALL, FLOOR
    }

    /**
     * One attempt, before any tolerance is applied.
     *
     * @param physics REST, ESCAPED or TIMED_OUT: never COCKED, which depends on the tolerance
     * @param tilt    per moving die, degrees between its reading face and level
     * @param lean    per moving die, what it rests on
     * @param stacked per moving die, whether it lies flat on top of another die (centre 3 mm or more above flat)
     * @param nudges  nudges given in this attempt, over all dice
     */
    record Attempt(Recording rec, Ending physics, float[] tilt, Lean[] lean, boolean[] stacked, int nudges, List<String> faces) {
        /** The largest tilt over the moving dice. */
        float maxTilt() {
            float m = 0;
            for (float t : tilt) m = Math.max(m, t);
            return m;
        }

        /** How the attempt ends at tolerance {@code deg}. */
        Ending ending(float deg) {
            return physics == Ending.REST && maxTilt() > deg ? Ending.COCKED : physics;
        }
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
    /** Circumscribed radius per kind in mm: the farthest vertex of the sharp solid. */
    private final Map<DieKind, Float> circumradius = new EnumMap<>(DieKind.class);

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
            float r = 0;
            for (int i = 0; i < pts.length; i++) {
                Vector3f v = s.vertices.get(i);
                pts[i] = new Vec3(v.x / 10, v.y / 10, v.z / 10);
                r = Math.max(r, v.length());
            }
            hulls.put(k, pts);
            circumradius.put(k, r);
        }
    }

    /** The tray prototype's entry point, for TrayApp: whole-Throw retries at the 4 degree tolerance. */
    Result simulate(TrayGeom tray, Params.Surface surface, List<DieKind> moving, List<DieKind> frozenKinds, List<Pose> frozen, long seed) {
        long t0 = System.nanoTime();
        Map<Ending, Integer> endings = new EnumMap<>(Ending.class);
        Attempt last = null;
        int n = 0;
        while (n < MAX_ATTEMPTS) {
            n++;
            last = attempt(tray, surface, appKnobs, moving, frozenKinds, frozen, attemptSeed(seed, n));
            endings.merge(last.ending(COCKED_DEG), 1, Integer::sum);
            if (last.ending(COCKED_DEG) == Ending.REST) break;
        }
        return new Result(last.rec, last.ending(COCKED_DEG), n, endings, (System.nanoTime() - t0) / 1_000_000, last.faces);
    }

    /** The release generator's seed for attempt {@code n} (from 1) of the Throw seeded {@code seed}. */
    static long attemptSeed(long seed, int n) {
        return seed * 1_000_003L + n;
    }

    /**
     * Simulates one attempt: {@code moving} thrown into the tray around the static {@code frozen} dice.
     *
     * <p>With {@code knobs.nudges() > 0}, a die that has been nearly still for 0.15 s while tilted more
     * than {@code knobs.nudgeDeg()} gets a small spin about the axis that would level its reading face,
     * plus a 4 cm/s hop, so it tips onto that face before Jolt puts it to sleep. The attempt continues in
     * the same system, so the recording stays one continuous motion.
     */
    Attempt attempt(TrayGeom tray, Params.Surface surface, Knobs knobs, List<DieKind> moving, List<DieKind> frozenKinds, List<Pose> frozen,
                    long seed) {
        SplittableRandom rnd = new SplittableRandom(seed);
        PhysicsSystem sys = new PhysicsSystem();
        try {
            sys.init(256, 0, 4096, 4096, bpLayers, objVsBp, pairs);
            sys.setGravity(0, -981f, 0);
            PhysicsSettings ps = sys.getPhysicsSettings();
            // Jolt's defaults are metre-scale; in centimetres most of them are already about right
            // (0.02 cm slop and speculative distance), but sleep needs a looser threshold or dice on felt
            // creep for seconds.
            ps.setPointVelocitySleepThreshold(knobs.sleepVelocity());
            ps.setTimeBeforeSleep(knobs.sleepTime());
            sys.setPhysicsSettings(ps);
            BodyInterface bi = sys.getBodyInterface();

            // Keep die-on-floor and die-on-wall friction where the tray prototype had them, whatever the
            // die's own friction: combined = sqrt(die * other), so other = reference^2 / die.
            float df = knobs.dieFriction();
            float floorF = REFERENCE_DIE_FRICTION * surface.friction / df;
            float wallF = REFERENCE_DIE_FRICTION * REFERENCE_WALL_FRICTION / df;
            for (TrayGeom.Box b : tray.physicsBoxes()) {
                boolean floor = b.yaw() == 0 && b.centre().y < 0;
                BodyCreationSettings bcs = new BodyCreationSettings(
                        new BoxShape(b.half().x / 10, b.half().y / 10, b.half().z / 10),
                        new RVec3(b.centre().x / 10, b.centre().y / 10, b.centre().z / 10),
                        new Quat(0, (float) Math.sin(b.yaw() / 2), 0, (float) Math.cos(b.yaw() / 2)),
                        EMotionType.Static, NON_MOVING);
                bcs.setRestitution(floor ? surface.restitution : tray.wallMaterial.restitution);
                bcs.setFriction(floor ? floorF : wallF);
                bi.createAndAddBody(bcs, EActivation.DontActivate);
            }
            for (int i = 0; i < frozen.size(); i++) {
                Pose p = frozen.get(i);
                BodyCreationSettings bcs = new BodyCreationSettings(hull(frozenKinds.get(i)),
                        new RVec3(p.pos().x / 10, p.pos().y / 10, p.pos().z / 10),
                        new Quat(p.rot().x, p.rot().y, p.rot().z, p.rot().w), EMotionType.Static, NON_MOVING);
                bcs.setRestitution(surface.restitution);
                bcs.setFriction(df);
                bi.createAndAddBody(bcs, EActivation.DontActivate);
            }

            // The release: a loose handful let go low over the near (camera-side, -Z) end and thrown down
            // and away from the camera, so the first bounce lands well short of the far wall. Spacing is
            // wider than a d20's circumscribed diameter (about 25 mm), so no two dice start overlapping.
            boolean spread = knobs.release() == Release.SPREAD || knobs.release() == Release.SPREAD_GENTLE;
            boolean gentle = knobs.release() == Release.GENTLE || knobs.release() == Release.SPREAD_GENTLE;
            float hx = tray.size.halfX, hz = tray.size.halfZ;
            int cols = Math.min(spread ? 7 : 5, moving.size()), rows = spread ? 3 : 2;
            float spacing = spread ? 30 : 27;
            float cx = (float) rnd.nextDouble(-0.25, 0.25) * hx * (spread ? 0.4f : 1f), cz = -hz + (spread ? 22 : 30);
            float cy = Math.min(tray.wallHeight, 30) + 26;
            float speed = (float) (rnd.nextDouble(65, 115) * Math.sqrt(hz / 100f)) * (gentle ? 0.65f : 1f);
            float vx = (float) rnd.nextDouble(-0.3, 0.3) * speed;
            float spin = gentle ? 18 : 30;
            int[] ids = new int[moving.size()];
            for (int i = 0; i < moving.size(); i++) {
                int col = i % cols, row = (i / cols) % rows, layer = i / (cols * rows);
                float x = cx + (col - (cols - 1) / 2f) * spacing + (float) rnd.nextDouble(-2, 2);
                float z = cz + row * spacing + (float) rnd.nextDouble(-2, 2);
                // The spread's 7-wide rows overhang the hexagon's slanted near walls; pull such a die in
                // until it starts 14 mm (a d20's circumradius plus a little) inside the outline, or it would
                // count as escaped at the first sample.
                while (!tray.inside(x, z, 14) && Math.abs(x) > 1) x *= 0.95f;
                float y = cy + layer * spacing;
                Quaternionf q = new Quaternionf().rotateXYZ((float) rnd.nextDouble(7), (float) rnd.nextDouble(7), (float) rnd.nextDouble(7));
                BodyCreationSettings bcs = new BodyCreationSettings(hull(moving.get(i)),
                        new RVec3(x / 10, y / 10, z / 10), new Quat(q.x, q.y, q.z, q.w), EMotionType.Dynamic, MOVING);
                bcs.setMotionQuality(EMotionQuality.LinearCast);
                bcs.setRestitution(surface.restitution);
                bcs.setFriction(df);
                bcs.setAngularDamping(surface.angularDamping);
                bcs.setLinearDamping(0.05f);
                float vy = gentle ? (float) rnd.nextDouble(-50, -25) : (float) rnd.nextDouble(-90, -40);
                bcs.setLinearVelocity(vx + (float) rnd.nextDouble(-10, 10), vy, speed + (float) rnd.nextDouble(-10, 10));
                bcs.setAngularVelocity((float) rnd.nextDouble(-spin, spin), (float) rnd.nextDouble(-spin, spin), (float) rnd.nextDouble(-spin, spin));
                ids[i] = bi.createAndAddBody(bcs, EActivation.Activate);
            }
            sys.optimizeBroadPhase();

            List<float[]> samples = new ArrayList<>();
            samples.add(snapshot(bi, ids));
            int steps = (int) (MAX_SECONDS * STEP_HZ), perSample = STEP_HZ / SAMPLE_HZ;
            Ending ending = Ending.TIMED_OUT;
            float rest = MAX_SECONDS;
            int[] still = new int[ids.length], nudged = new int[ids.length];
            int nudges = 0;
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
                if (knobs.nudges() > 0) nudges += nudge(bi, ids, moving, snap, knobs, still, nudged);
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
            float[] tilt = new float[moving.size()];
            Lean[] lean = new Lean[moving.size()];
            boolean[] stacked = new boolean[moving.size()];
            for (int i = 0; i < moving.size(); i++) {
                Quaternionf q = new Quaternionf(last[i * 7 + 3], last[i * 7 + 4], last[i * 7 + 5], last[i * 7 + 6]);
                String[] face = new String[1];
                tilt[i] = upFace(moving.get(i), q, face);
                faces.add(face[0]);
                lean[i] = lean(tray, i, moving, last, frozenKinds, frozen);
                stacked[i] = tilt[i] <= COCKED_DEG && last[i * 7 + 1] - moving.get(i).inradius >= 3;
            }
            float[] data = new float[samples.size() * moving.size() * 7];
            for (int i = 0; i < samples.size(); i++) System.arraycopy(samples.get(i), 0, data, i * moving.size() * 7, moving.size() * 7);
            return new Attempt(new Recording(moving.size(), samples.size(), data, rest), ending, tilt, lean, stacked, nudges, faces);
        } finally {
            sys.close();
        }
    }

    /**
     * Nudges dice that are settling cocked. A die counts as settling once it has been nearly still
     * (under about 3 mm/s and 0.4 rad/s) for 9 samples (0.15 s); Jolt's own sleep needs
     * {@code sleepTime} (0.35 s by default), so the nudge lands first and the die never visibly stops
     * before tipping. Returns how many nudges it gave.
     */
    private int nudge(BodyInterface bi, int[] ids, List<DieKind> moving, float[] snap, Knobs knobs, int[] still, int[] nudged) {
        int given = 0;
        for (int i = 0; i < ids.length; i++) {
            Vec3 v = bi.getLinearVelocity(ids[i]);
            Vec3 w = bi.getAngularVelocity(ids[i]);
            boolean slow = Math.abs(v.getX()) + Math.abs(v.getY()) + Math.abs(v.getZ()) < 0.3f
                    && Math.abs(w.getX()) + Math.abs(w.getY()) + Math.abs(w.getZ()) < 0.4f;
            Quaternionf q = new Quaternionf(snap[i * 7 + 3], snap[i * 7 + 4], snap[i * 7 + 5], snap[i * 7 + 6]);
            Vector3f n = readingNormal(moving.get(i), q);
            float tilt = (float) Math.toDegrees(Math.acos(Math.clamp(n.y, -1, 1)));
            still[i] = slow && tilt > knobs.nudgeDeg() ? still[i] + 1 : 0;
            if (still[i] < 9 || nudged[i] >= knobs.nudges()) continue;
            // Spin about n x up: dn/dt = w x n is then (up - n cos(tilt)) scaled, which turns the reading
            // normal straight towards +Y, so the die tips onto the face it was already nearest.
            Vector3f axis = new Vector3f(n).cross(0, 1, 0);
            if (axis.lengthSquared() < 1e-8f) continue;
            axis.normalize(5f);
            bi.setLinearAndAngularVelocity(ids[i], 0, 4f, 0, axis.x, axis.y, axis.z);
            bi.activateBody(ids[i]);
            still[i] = 0;
            nudged[i]++;
            given++;
        }
        return given;
    }

    /**
     * What a die's centre is close to: another die if their circumscribed spheres come within 1 mm,
     * else a wall if its sphere does, else the floor alone (balanced on an edge or a vertex).
     */
    private Lean lean(TrayGeom tray, int i, List<DieKind> moving, float[] last, List<DieKind> frozenKinds, List<Pose> frozen) {
        float x = last[i * 7], y = last[i * 7 + 1], z = last[i * 7 + 2];
        float r = circumradius.get(moving.get(i));
        for (int j = 0; j < moving.size(); j++) {
            if (j == i) continue;
            float dx = last[j * 7] - x, dy = last[j * 7 + 1] - y, dz = last[j * 7 + 2] - z;
            if (Math.sqrt(dx * dx + dy * dy + dz * dz) < r + circumradius.get(moving.get(j)) + 1) return Lean.DIE;
        }
        for (int j = 0; j < frozen.size(); j++) {
            Vector3f p = frozen.get(j).pos();
            if (p.distance(x, y, z) < r + circumradius.get(frozenKinds.get(j)) + 1) return Lean.DIE;
        }
        return tray.inside(x, z, r + 1) ? Lean.FLOOR : Lean.WALL;
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
     * The world-space normal that should point straight up when the die is flat: the face normal most
     * nearly up, or for the top-read d4 the negated normal of the face most nearly down.
     */
    static Vector3f readingNormal(DieKind kind, Quaternionf rot) {
        Vector3f best = null;
        float bestUp = -2;
        for (DieKind.FaceSpec f : kind.faces()) {
            Vector3f n = rot.transform(new Vector3f(f.normal()).normalize());
            if (kind == DieKind.D4) n.negate();
            if (n.y > bestUp) {
                bestUp = n.y;
                best = n;
            }
        }
        return best;
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
