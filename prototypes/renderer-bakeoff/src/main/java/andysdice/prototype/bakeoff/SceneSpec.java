package andysdice.prototype.bakeoff;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * A Dice Set look. Colours are linear RGB. The {@code fx*} fields are the closest JavaFX Phong
 * equivalents; JavaFX has no roughness or metalness, which is part of what the bake-off shows.
 */
record Look(String name, float[] base, float[] ink, float roughness, float metallic, float inkMetallic,
            float fxSpecular, float fxSpecularPower) {
    static final List<Look> ALL = List.of(
            new Look("Crimson resin, white ink", new float[]{0.32f, 0.012f, 0.018f}, new float[]{0.80f, 0.80f, 0.75f},
                    0.16f, 0f, 0f, 0.55f, 80f),
            new Look("Ivory, black ink", new float[]{0.70f, 0.62f, 0.45f}, new float[]{0.015f, 0.015f, 0.015f},
                    0.32f, 0f, 0f, 0.35f, 40f),
            new Look("Gunmetal, gold ink", new float[]{0.30f, 0.30f, 0.32f}, new float[]{0.85f, 0.62f, 0.25f},
                    0.30f, 1f, 1f, 0.9f, 30f));
}

/**
 * PROTOTYPE. Everything the two renderers must agree on: tray size, lights, camera, Dice Set look and
 * where each die is at a given moment. State is read from the FX thread and from openglfx's render
 * thread, hence the volatiles; a torn read just means one frame mixes two toggles, which is harmless.
 */
final class SceneSpec {
    static final float TRAY_HALF_X = 110, TRAY_HALF_Z = 75, WALL_H = 28, WALL_T = 12;
    /** Direction towards the key light (y up). The key light is the one that casts shadows. */
    static final Vector3f KEY_DIR = new Vector3f(-0.45f, 0.85f, -0.35f).normalize();
    static final Vector3f KEY_COLOR = new Vector3f(1.0f, 0.96f, 0.90f).mul(3.0f);
    static final Vector3f FILL_DIR = new Vector3f(0.7f, 0.45f, 0.5f).normalize();
    static final Vector3f FILL_COLOR = new Vector3f(0.55f, 0.62f, 0.75f).mul(0.5f);
    static final float AMBIENT = 0.22f;
    static final float FOV_Y_DEG = 32f;
    static final float ELEVATION = (float) Math.toRadians(38);

    final D20 d20 = new D20();
    final MeshData floor = MeshData.floorQuad(TRAY_HALF_X, TRAY_HALF_Z, 0);
    final List<MeshData> walls = List.of(
            MeshData.box(new Vector3f(-TRAY_HALF_X - WALL_T, 0, -TRAY_HALF_Z - WALL_T), new Vector3f(TRAY_HALF_X + WALL_T, WALL_H, -TRAY_HALF_Z), 1 / 240f),
            MeshData.box(new Vector3f(-TRAY_HALF_X - WALL_T, 0, TRAY_HALF_Z), new Vector3f(TRAY_HALF_X + WALL_T, WALL_H, TRAY_HALF_Z + WALL_T), 1 / 240f),
            MeshData.box(new Vector3f(-TRAY_HALF_X - WALL_T, 0, -TRAY_HALF_Z), new Vector3f(-TRAY_HALF_X, WALL_H, TRAY_HALF_Z), 1 / 240f),
            MeshData.box(new Vector3f(TRAY_HALF_X, 0, -TRAY_HALF_Z), new Vector3f(TRAY_HALF_X + WALL_T, WALL_H, TRAY_HALF_Z), 1 / 240f));

    volatile boolean orbiting = true;
    volatile boolean tumbling = false;
    volatile boolean twenty = false;
    volatile int lookIndex = 0;
    /** Bumped whenever the look changes so renderers can rebuild look-dependent resources. */
    volatile int lookVersion = 0;
    volatile float manualYaw = 0;

    private final long startNanos = System.nanoTime();
    private volatile double frozenAt = Double.NaN;

    private final List<Quaternionf> restSingle = List.of(d20.restRotation(20, 0));
    private final List<Vector3f> posSingle = List.of(new Vector3f(0, D20.INRADIUS, 0));
    private final List<Quaternionf> restTwenty = new ArrayList<>();
    private final List<Vector3f> posTwenty = new ArrayList<>();
    private final List<Vector3f> tumbleAxes = new ArrayList<>();

    SceneSpec() {
        SplittableRandom rnd = new SplittableRandom(20);
        for (int i = 0; i < 20; i++) {
            int col = i % 5, row = i / 5;
            restTwenty.add(d20.restRotation(i + 1, (float) (rnd.nextDouble() * Math.PI * 2)));
            float jitterX = (float) (rnd.nextDouble() * 8 - 4), jitterZ = (float) (rnd.nextDouble() * 8 - 4);
            posTwenty.add(new Vector3f((col - 2) * 38 + jitterX, D20.INRADIUS, (row - 1.5f) * 33 + jitterZ));
            tumbleAxes.add(new Vector3f((float) rnd.nextDouble() - 0.5f, (float) rnd.nextDouble() - 0.5f, (float) rnd.nextDouble() - 0.5f).normalize());
        }
    }

    Look look() {
        return Look.ALL.get(lookIndex);
    }

    void nextLook() {
        lookIndex = (lookIndex + 1) % Look.ALL.size();
        lookVersion++;
    }

    /** Freezes the animation clock at {@code seconds} (used for like-for-like screenshots). */
    void freezeAt(double seconds) {
        frozenAt = seconds;
    }

    double time() {
        double f = frozenAt;
        return Double.isNaN(f) ? (System.nanoTime() - startNanos) / 1e9 : f;
    }

    /** Camera and per-die transforms for one frame. Y-up, millimetres. */
    record Frame(Vector3f eye, Vector3f target, List<Matrix4f> dice, List<Vector3f> diceCentres) {
    }

    Frame frame() {
        double t = time();
        boolean many = twenty;
        float yaw = manualYaw + (orbiting ? (float) (t * 0.12) : 0f);
        float dist = many ? 330 : 105;
        Vector3f target = new Vector3f(0, many ? 0 : 7, 0);
        Vector3f eye = new Vector3f(
                (float) (Math.cos(ELEVATION) * Math.sin(yaw)),
                (float) Math.sin(ELEVATION),
                (float) (-Math.cos(ELEVATION) * Math.cos(yaw))).mul(dist).add(target);
        List<Quaternionf> rest = many ? restTwenty : restSingle;
        List<Vector3f> pos = many ? posTwenty : posSingle;
        List<Matrix4f> models = new ArrayList<>(rest.size());
        List<Vector3f> centres = new ArrayList<>(rest.size());
        for (int i = 0; i < rest.size(); i++) {
            Quaternionf q = new Quaternionf(rest.get(i));
            Vector3f p = new Vector3f(pos.get(i));
            if (tumbling) {
                // Not physics: a canned spin above the felt, enough to judge moving highlights and shadows.
                Vector3f axis = many ? tumbleAxes.get(i) : new Vector3f(0.8f, 0.5f, 0.3f).normalize();
                q = new Quaternionf().rotationAxis((float) (t * 1.4 + i), axis).mul(q);
                p.y += 14 + 5 * (float) Math.sin(t * 2.3 + i);
            }
            models.add(new Matrix4f().translation(p).rotate(q));
            centres.add(p);
        }
        return new Frame(eye, target, models, centres);
    }
}
