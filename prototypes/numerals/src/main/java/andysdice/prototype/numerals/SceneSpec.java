package andysdice.prototype.numerals;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * A Dice Set look: body and ink colours (linear RGB) and surface response. The ink colour belongs to
 * the Dice Set ("How are Dice Set materials defined...", issue #16); these four are stand-ins.
 */
record Look(String name, float[] base, float[] ink, float roughness, float metallic, float inkMetallic) {
    static final List<Look> ALL = List.of(
            new Look("Opaque black, white ink", new float[]{0.018f, 0.018f, 0.02f}, new float[]{0.80f, 0.80f, 0.77f}, 0.22f, 0f, 0f),
            new Look("Crimson resin, white ink", new float[]{0.32f, 0.012f, 0.018f}, new float[]{0.80f, 0.80f, 0.75f}, 0.16f, 0f, 0f),
            new Look("Ivory, black ink", new float[]{0.70f, 0.62f, 0.45f}, new float[]{0.015f, 0.015f, 0.015f}, 0.32f, 0f, 0f),
            new Look("Gunmetal, gold ink", new float[]{0.30f, 0.30f, 0.32f}, new float[]{0.85f, 0.62f, 0.25f}, 0.30f, 1f, 1f));
}

/**
 * PROTOTYPE. The tray, lights, camera and where each die rests. Written on the FX thread and read on
 * openglfx's render thread, hence the volatiles.
 */
final class SceneSpec {
    static final float TRAY_HALF_X = 150, TRAY_HALF_Z = 80, WALL_H = 28, WALL_T = 12;
    /** Direction towards the key light (y up). The key light is the one that casts shadows. */
    static final Vector3f KEY_DIR = new Vector3f(-0.45f, 0.85f, -0.35f).normalize();
    static final Vector3f KEY_COLOR = new Vector3f(1.0f, 0.96f, 0.90f).mul(3.0f);
    static final Vector3f FILL_DIR = new Vector3f(0.7f, 0.45f, 0.5f).normalize();
    static final Vector3f FILL_COLOR = new Vector3f(0.55f, 0.62f, 0.75f).mul(0.5f);
    static final float FOV_Y_DEG = 32f;
    /** Camera distances in mm: whole set, close-up, macro. */
    static final float[] PRESET_DIST = {290, 78, 40};
    static final String[] PRESET_NAMES = {"set", "close-up", "macro"};

    final MeshData floor = MeshData.floorQuad(TRAY_HALF_X, TRAY_HALF_Z, 0);
    final List<MeshData> walls = List.of(
            MeshData.box(new Vector3f(-TRAY_HALF_X - WALL_T, 0, -TRAY_HALF_Z - WALL_T), new Vector3f(TRAY_HALF_X + WALL_T, WALL_H, -TRAY_HALF_Z), 1 / 240f),
            MeshData.box(new Vector3f(-TRAY_HALF_X - WALL_T, 0, TRAY_HALF_Z), new Vector3f(TRAY_HALF_X + WALL_T, WALL_H, TRAY_HALF_Z + WALL_T), 1 / 240f),
            MeshData.box(new Vector3f(-TRAY_HALF_X - WALL_T, 0, -TRAY_HALF_Z), new Vector3f(-TRAY_HALF_X, WALL_H, TRAY_HALF_Z), 1 / 240f),
            MeshData.box(new Vector3f(TRAY_HALF_X, 0, -TRAY_HALF_Z), new Vector3f(TRAY_HALF_X + WALL_T, WALL_H, TRAY_HALF_Z), 1 / 240f));

    // +X is screen-left from a camera on the -Z side, so the d4 gets the largest X to sit on the left.
    private static final float[] X = {102, 68, 34, 0, -34, -68, -102};
    private static final float[] Z = {4, -6, 3, -3, 6, -4, 2};
    private static final float[] YAW = {0.25f, -0.15f, 0.1f, -0.3f, 0.2f, -0.1f, 0.15f};

    /** -1 = the whole set, otherwise the index of the focused {@link DieKind}. */
    volatile int focus = -1;
    /** 1 = close-up, 2 = macro; only used while focused. */
    volatile int preset = 1;
    /** Scroll-wheel zoom factor applied to the preset distance. */
    volatile float zoom = 1;
    volatile float yaw = 0;
    volatile float pitch = (float) Math.toRadians(38);
    volatile boolean orbiting = false;
    volatile boolean tumbling = false;
    volatile int lookIndex = 0;
    /** Index into each kind's {@link DieModel#labels()} of the value showing. */
    final int[] upIndex = new int[DieKind.values().length];

    private final long startNanos = System.nanoTime();
    private volatile double frozenAt = Double.NaN;

    SceneSpec() {
        resetFaces();
    }

    /** Defaults show a 6/9 mark where the kind has one, and each kind's highest face otherwise. */
    void resetFaces() {
        upIndex[DieKind.D4.ordinal()] = 3;   // 4
        upIndex[DieKind.D6.ordinal()] = 5;   // 6
        upIndex[DieKind.D8.ordinal()] = 7;   // 8
        upIndex[DieKind.D10.ordinal()] = 9;  // 9
        upIndex[DieKind.D_PERCENT.ordinal()] = 9; // 90
        upIndex[DieKind.D12.ordinal()] = 11; // 12
        upIndex[DieKind.D20.ordinal()] = 19; // 20
    }

    Look look() {
        return Look.ALL.get(lookIndex);
    }

    void freezeAt(double seconds) {
        frozenAt = seconds;
    }

    double time() {
        double f = frozenAt;
        return Double.isNaN(f) ? (System.nanoTime() - startNanos) / 1e9 : f;
    }

    String viewName() {
        return focus < 0 ? "whole set" : DieKind.values()[focus].label + " " + PRESET_NAMES[preset];
    }

    /** One die this frame. */
    record DieInstance(DieKind kind, Matrix4f model, Vector3f centre) {
    }

    /** Camera and per-die transforms for one frame. Y-up, millimetres. */
    record Frame(Vector3f eye, Vector3f target, float dist, List<DieInstance> dice) {
    }

    Frame frame(List<DieModel> models) {
        double t = time();
        List<DieInstance> dice = new ArrayList<>();
        for (DieModel m : models) {
            int i = m.kind.ordinal();
            List<String> labels = m.labels();
            Quaternionf q = m.restRotation(labels.get(upIndex[i] % labels.size()), YAW[i]);
            Vector3f p = new Vector3f(X[i], m.kind.inradius, Z[i]);
            if (tumbling) {
                // Not physics: a slow canned spin in place, so every face passes the camera.
                Vector3f axis = new Vector3f(0.8f, 0.5f, 0.3f + 0.1f * i).normalize();
                q = new Quaternionf().rotationAxis((float) (t * 0.5 + i), axis).mul(q);
                p.y += m.kind.inradius * 0.7f;
            }
            dice.add(new DieInstance(m.kind, new Matrix4f().translation(p).rotate(q), p));
        }
        int f = focus;
        Vector3f target;
        float dist;
        if (f < 0) {
            target = new Vector3f(0, 6, 0);
            dist = PRESET_DIST[0] * zoom;
        } else {
            Vector3f c = new Vector3f(X[f], DieKind.values()[f].inradius * (tumbling ? 1.7f : 1.15f), Z[f]);
            target = c;
            dist = PRESET_DIST[preset] * zoom;
        }
        float y = yaw + (orbiting ? (float) (t * 0.15) : 0f);
        float pt = pitch;
        Vector3f eye = new Vector3f(
                (float) (Math.cos(pt) * Math.sin(y)),
                (float) Math.sin(pt),
                (float) (-Math.cos(pt) * Math.cos(y))).mul(dist).add(target);
        return new Frame(eye, target, dist, dice);
    }
}
