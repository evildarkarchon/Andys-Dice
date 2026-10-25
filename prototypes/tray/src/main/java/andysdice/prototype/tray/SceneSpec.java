package andysdice.prototype.tray;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * PROTOTYPE. The tray, the Roll being played, the camera and the light rigs. Written on the FX and
 * physics threads and read on openglfx's render thread, hence the volatiles. The camera's smoothed state
 * is touched only by the render thread ({@link #frame}).
 */
final class SceneSpec {
    static final float FOV_Y_DEG = 32f;
    /** The tray's contact-occlusion uniform array holds this many dice (the 20-Dice cap plus slack). */
    static final int MAX_DICE = 24;

    volatile TrayGeom tray;
    volatile Roll roll;
    /** Seconds into the last segment to hold the playback at (for captures), or NaN to play live. */
    volatile float freezeAt = Float.NaN;
    volatile List<DiceSet> sets = List.of();
    volatile int setIndex = 0;
    volatile float zoom = 1;
    volatile float yaw = 0;
    volatile float pitch = (float) Math.toRadians(50);
    /** What the last rendered frame showed (pickup, tumbling, at rest), for the HUD. */
    volatile String phase = "";

    // Render-thread-only camera state, eased toward each frame's goal.
    private final Vector3f camTarget = new Vector3f();
    private float camDist = -1, camPitch;
    private long lastFrameNanos;

    DiceSet set() {
        List<DiceSet> s = sets;
        return s.isEmpty() ? null : s.get(Math.floorMod(setIndex, s.size()));
    }

    /** One die this frame. {@code slot} seeds its offset into the swirl and flake fields. */
    record DieInstance(DieKind kind, Matrix4f model, Vector3f centre, float radius, DiceSet set, int slot, float glow) {
    }

    /** Camera and dice for one frame. Y-up, millimetres. */
    record Frame(Vector3f eye, Vector3f target, float dist, List<DieInstance> dice, String phase) {
    }

    /** Seconds into the last segment right now, honouring {@link #freezeAt}. */
    float segmentTime(Roll r) {
        float f = freezeAt;
        if (!Float.isNaN(f)) return f;
        return (System.nanoTime() - r.last().startNanos()) / 1e9f;
    }

    Frame frame(Params p, float aspect) {
        TrayGeom g = tray;
        Roll r = roll;
        DiceSet set = set();
        List<DieInstance> dice = new ArrayList<>();
        String phase = "empty tray (Enter throws)";
        Vector3f movingC = new Vector3f(), allC = new Vector3f();
        float movingR = 0, allR = 0;
        boolean atRest = false;
        int nMoving = 0;
        if (r != null && set != null) {
            Roll.Segment seg = r.last();
            float t = segmentTime(r);
            List<Roll.DieState> st = r.stateAt(t);
            atRest = t >= seg.endSeconds();
            phase = atRest ? "at rest" : t < seg.pickupSeconds() ? "pickup (" + seg.pickup() + ")" : "tumbling";
            for (int i = 0; i < st.size(); i++) {
                Roll.DieState s = st.get(i);
                DieKind k = r.kinds().get(i);
                Matrix4f m = new Matrix4f().translation(s.pose().pos()).rotate(s.pose().rot()).scale(Math.max(s.scale(), 1e-3f));
                if (s.scale() > 0.01f) dice.add(new DieInstance(k, m, new Vector3f(s.pose().pos()), k.inradius * s.scale(), set, i, s.glow()));
                allC.add(s.pose().pos());
                if (s.moving()) {
                    movingC.add(s.pose().pos());
                    nMoving++;
                }
            }
            allC.div(st.size());
            if (nMoving > 0) movingC.div(nMoving);
            for (int i = 0; i < st.size(); i++) {
                Vector3f pos = st.get(i).pose().pos();
                allR = Math.max(allR, pos.distance(allC) + r.kinds().get(i).inradius * 1.6f);
                if (st.get(i).moving()) movingR = Math.max(movingR, pos.distance(movingC) + r.kinds().get(i).inradius * 1.6f);
            }
        }

        // The goal framing for this frame, by camera mode.
        float defPitch = pitch;
        Vector3f goalT = new Vector3f(0, 5, 0);
        float goalPitch = defPitch;
        float goalDist = fitTray(g, aspect, defPitch);
        switch (p.camera) {
            case FIXED -> {
            }
            case TOP -> {
                goalPitch = (float) Math.toRadians(80);
                goalDist = fitTray(g, aspect, goalPitch);
            }
            case FOLLOW -> {
                goalPitch = (float) Math.toRadians(42);
                if (r != null && !dice.isEmpty()) {
                    boolean follow = !atRest && nMoving > 0;
                    Vector3f c = follow ? movingC : allC;
                    float rad = Math.max(follow ? movingR : allR, 40);
                    goalT.set(c.x, Math.min(c.y, 20), c.z);
                    goalDist = Math.min(fit(rad, aspect) * 1.25f, fitTray(g, aspect, goalPitch));
                }
            }
            case SETTLE -> {
                if (r != null && atRest && !dice.isEmpty()) {
                    goalPitch = (float) Math.toRadians(62);
                    goalT.set(allC.x, 0, allC.z);
                    goalDist = fit(Math.max(allR, 45), aspect) * 1.1f;
                }
            }
        }
        goalDist *= zoom;

        long now = System.nanoTime();
        float dt = lastFrameNanos == 0 ? 1f : Math.min(0.25f, (now - lastFrameNanos) / 1e9f);
        lastFrameNanos = now;
        if (camDist < 0 || !Float.isNaN(freezeAt)) dt = 10; // snap on the first frame and in captures
        // FIXED and TOP follow a drag at once; the moving modes ease, which is what is being judged.
        float tau = p.camera == Params.Camera.FIXED || p.camera == Params.Camera.TOP ? 0.05f : p.camera == Params.Camera.FOLLOW ? 0.45f : 0.6f;
        float a = 1 - (float) Math.exp(-dt / tau);
        camTarget.lerp(goalT, a);
        camDist += (goalDist - camDist) * (camDist < 0 ? 1 : a);
        if (camDist < 0) camDist = goalDist;
        camPitch += (goalPitch - camPitch) * a;

        float y = yaw;
        Vector3f eye = new Vector3f(
                (float) (Math.cos(camPitch) * Math.sin(y)),
                (float) Math.sin(camPitch),
                (float) (-Math.cos(camPitch) * Math.cos(y))).mul(camDist).add(camTarget);
        this.phase = phase;
        return new Frame(eye, new Vector3f(camTarget), camDist, dice, phase);
    }

    /** Distance at which a sphere of radius {@code r} just fits the view. */
    private static float fit(float r, float aspect) {
        double half = Math.toRadians(FOV_Y_DEG / 2);
        double hHalf = Math.atan(Math.tan(half) * aspect);
        return (float) (r / Math.sin(Math.min(half, hHalf)));
    }

    /** Distance at which the whole tray, walls included, fits the view from this pitch. */
    private static float fitTray(TrayGeom g, float aspect, float pitch) {
        double tv = Math.tan(Math.toRadians(FOV_Y_DEG / 2));
        float hx = g.size.halfX + TrayGeom.WALL_T, hz = g.size.halfZ + TrayGeom.WALL_T;
        float byWidth = (float) (hx / (tv * aspect));
        float byDepth = (float) ((hz * Math.sin(pitch) + g.wallHeight * Math.cos(pitch)) / tv);
        return Math.max(byWidth, byDepth) * 1.08f + hz * (float) Math.cos(pitch);
    }

    // ---------------------------------------------------------------------------------------------
    // Light rigs

    /**
     * One rig. The key light casts the shadows: directional ({@code keyPos.w == 0}, xyz towards the
     * light) or a spot at {@code keyPos.xyz}. {@code size} is what PCSS widens penumbrae by: the
     * tangent of the light's angular radius (directional) or its radius in mm (spot).
     *
     * @param env selects the reflection environment in env.glsl (0 studio, 1 lamp room, 2 window)
     */
    record Light(float[] keyPos, Vector3f keyColor, Vector3f spotDir, float spotOuterDeg, float spotInnerDeg, float size,
                 Vector3f fillDir, Vector3f fillColor, int env) {
        boolean spot() {
            return keyPos[3] != 0;
        }

        Vector3f keyVec() {
            return new Vector3f(keyPos[0], keyPos[1], keyPos[2]);
        }
    }

    static Light light(Params.Rig rig) {
        return switch (rig) {
            case STUDIO -> {
                Vector3f d = new Vector3f(-0.45f, 0.85f, -0.35f).normalize();
                yield new Light(new float[]{d.x, d.y, d.z, 0}, new Vector3f(1.0f, 0.96f, 0.90f).mul(3.0f), new Vector3f(), 0, 0, 0.04f,
                        new Vector3f(0.7f, 0.45f, 0.5f).normalize(), new Vector3f(0.55f, 0.62f, 0.75f).mul(0.5f), 0);
            }
            case LAMP -> {
                Vector3f pos = new Vector3f(70, 480, -60);
                Vector3f dir = new Vector3f(pos).negate().add(0, 0, 10).normalize();
                yield new Light(new float[]{pos.x, pos.y, pos.z, 1}, new Vector3f(1.0f, 0.74f, 0.48f).mul(4.8f), dir, 24, 9, 15f,
                        new Vector3f(-0.3f, 1f, 0.4f).normalize(), new Vector3f(0.9f, 0.6f, 0.4f).mul(0.06f), 1);
            }
            case WINDOW -> {
                Vector3f d = new Vector3f(-0.86f, 0.42f, 0.28f).normalize();
                yield new Light(new float[]{d.x, d.y, d.z, 0}, new Vector3f(0.86f, 0.93f, 1.0f).mul(3.4f), new Vector3f(), 0, 0, 0.06f,
                        new Vector3f(0.1f, 1f, -0.2f).normalize(), new Vector3f(0.35f, 0.45f, 0.62f).mul(0.55f), 2);
            }
        };
    }
}
