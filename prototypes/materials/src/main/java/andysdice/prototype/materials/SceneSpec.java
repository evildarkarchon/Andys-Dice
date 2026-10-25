package andysdice.prototype.materials;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * PROTOTYPE. The tray, lights, camera, and where each die rests. Written on the FX thread and read on
 * openglfx's render thread, hence the volatiles.
 *
 * <p>Two layouts: the seven Die Kinds in one Dice Set (as in the numerals prototype), or the
 * <em>catalogue</em>, one d20 per Dice Set in two rows, for comparing the sets side by side.
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
    /** The tray's contact-occlusion uniform array holds this many dice. */
    static final int MAX_DICE = 16;
    static final int CATALOGUE = -2, WHOLE_SET = -1;

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

    /** {@link #CATALOGUE}, {@link #WHOLE_SET}, or the index of the focused {@link DieKind}. */
    volatile int focus = CATALOGUE;
    /** 1 = close-up, 2 = macro; only used while focused. */
    volatile int preset = 1;
    /** Scroll-wheel zoom factor applied to the preset distance. */
    volatile float zoom = 1;
    volatile float yaw = 0;
    volatile float pitch = (float) Math.toRadians(38);
    volatile boolean orbiting = false;
    volatile boolean tumbling = false;
    /** The catalogue, in file-name order; replaced wholesale on reload. */
    volatile List<DiceSet> sets = List.of();
    /** The Dice Set shown in the whole-set and focused views. */
    volatile int setIndex = 0;
    /** Index into each kind's {@link DieModel#labels()} of the value showing. */
    final int[] upIndex = new int[DieKind.values().length];

    private final long startNanos = System.nanoTime();

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

    DiceSet set() {
        List<DiceSet> s = sets;
        return s.isEmpty() ? null : s.get(Math.floorMod(setIndex, s.size()));
    }

    double time() {
        return (System.nanoTime() - startNanos) / 1e9;
    }

    String viewName() {
        int f = focus;
        if (f == CATALOGUE) return "catalogue (one d20 per Dice Set)";
        return f == WHOLE_SET ? "whole set" : DieKind.values()[f].label + " " + PRESET_NAMES[preset];
    }

    /**
     * One die this frame.
     *
     * @param set  the Dice Set it is drawn in
     * @param slot a stable per-die number, which seeds its offset into the swirl and flake fields
     */
    record DieInstance(DieKind kind, Matrix4f model, Vector3f centre, DiceSet set, int slot) {
    }

    /** Camera and per-die transforms for one frame. Y-up, millimetres. */
    record Frame(Vector3f eye, Vector3f target, float dist, List<DieInstance> dice) {
    }

    Frame frame(List<DieModel> models) {
        double t = time();
        List<DiceSet> all = sets;
        DiceSet current = set();
        List<DieInstance> dice = new ArrayList<>();
        int f = focus;
        if (all.isEmpty()) return camera(f, t, dice);
        if (f == CATALOGUE) {
            DieModel d20 = models.get(DieKind.D20.ordinal());
            int n = Math.min(all.size(), MAX_DICE);
            int cols = (n + 1) / 2;
            float spacing = Math.min(38f, 260f / Math.max(1, cols - 1));
            for (int i = 0; i < n; i++) {
                int row = i / cols, col = i % cols;
                Vector3f p = new Vector3f((cols - 1) * spacing / 2 - col * spacing, d20.kind.inradius, row == 0 ? -26 : 30);
                Quaternionf q = d20.restRotation("20", 0.4f * i);
                dice.add(new DieInstance(d20.kind, new Matrix4f().translation(p).rotate(q), p, all.get(i), i));
            }
        } else {
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
                dice.add(new DieInstance(m.kind, new Matrix4f().translation(p).rotate(q), p, current, i));
            }
        }
        return camera(f, t, dice);
    }

    private Frame camera(int f, double t, List<DieInstance> dice) {
        Vector3f target;
        float dist;
        if (f < 0) {
            target = new Vector3f(0, 6, 0);
            // The catalogue spans the whole tray width, so it needs to stand further back.
            dist = PRESET_DIST[0] * (f == CATALOGUE ? 1.2f : 1f) * zoom;
        } else {
            target = new Vector3f(X[f], DieKind.values()[f].inradius * (tumbling ? 1.7f : 1.15f), Z[f]);
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
