package io.github.evildarkarchon.andysdice.render;

import static org.lwjgl.opengl.GL11C.GL_BACK;
import static org.lwjgl.opengl.GL11C.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11C.GL_CULL_FACE;
import static org.lwjgl.opengl.GL11C.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL11C.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11C.glClear;
import static org.lwjgl.opengl.GL11C.glClearColor;
import static org.lwjgl.opengl.GL11C.glCullFace;
import static org.lwjgl.opengl.GL11C.glDisable;
import static org.lwjgl.opengl.GL11C.glEnable;
import static org.lwjgl.opengl.GL20C.glDeleteProgram;
import static org.lwjgl.opengl.GL20C.glGetUniformLocation;
import static org.lwjgl.opengl.GL20C.glUniform1f;
import static org.lwjgl.opengl.GL20C.glUniform1i;
import static org.lwjgl.opengl.GL20C.glUniform3f;
import static org.lwjgl.opengl.GL20C.glUniformMatrix4fv;
import static org.lwjgl.opengl.GL20C.glUseProgram;

import io.github.evildarkarchon.andysdice.core.geometry.DieKind;
import io.github.evildarkarchon.andysdice.core.simulation.Orientation;
import io.github.evildarkarchon.andysdice.core.simulation.Pose;
import io.github.evildarkarchon.andysdice.render.mesh.DieMesh;
import io.github.evildarkarchon.andysdice.render.numerals.NumeralAtlas;
import io.github.evildarkarchon.andysdice.render.numerals.NumeralLayout;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.joml.Matrix4d;
import org.joml.Matrix4dc;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Draws Dice with their rounded meshes ({@link DieMesh}, ticket #40) in place of the sharp solids: each Die of
 * whatever Die Kind at its pose, seen from a camera, under a fixed key and fill light, in one plain ivory look
 * until the Dice Set shader (#42) takes over. The app's playback and the gallery both draw through it.
 *
 * <p>Every face carries its engraved, inked Numerals (ticket #41, ADR-0008): each Die Kind's atlas is
 * rasterised and uploaded at {@link #init()}, and {@code engraving.glsl} traces it by parallax occlusion.
 *
 * <p>World space is y-up and in mm. A Die's orientation turns its z-up model frame (the face tables') into the
 * world, as {@code core}'s poses do.
 *
 * <p>{@link #setDice} and {@link #setCamera} may be called from any thread and take effect at the next frame;
 * everything else runs on the render thread, as a {@link FrameRenderer}.
 */
public final class DiceScene implements FrameRenderer {

    /** The background, as the opaque ARGB a capture reads back: a dark felt green. */
    public static final int BACKGROUND_ARGB = 0xff12301c;

    /** Physics poses are in centimetres, the renderer's world in millimetres. */
    private static final double MM_PER_CM = 10;

    /**
     * One Die to draw.
     *
     * @param kind its Die Kind, which picks the mesh
     * @param position its centre in the world, in mm
     * @param orientation the rotation from its z-up model frame into the y-up world
     */
    public record Die(DieKind kind, Vector3dc position, Quaterniondc orientation) {

        /**
         * Checks the parts and copies them, so that the Die cannot change after it is handed over.
         *
         * @throws NullPointerException if any part is {@code null}
         */
        public Die {
            Objects.requireNonNull(kind, "kind");
            position = new Vector3d(Objects.requireNonNull(position, "position"));
            orientation = new Quaterniond(Objects.requireNonNull(orientation, "orientation"));
        }

        /**
         * A Die at a simulator pose, converting its centimetres to the renderer's millimetres.
         *
         * @param kind the Die Kind
         * @param pose the pose, as a Recording holds it
         * @return the Die to draw
         */
        public static Die of(DieKind kind, Pose pose) {
            Orientation q = pose.orientation();
            return new Die(kind,
                    new Vector3d(pose.position().x(), pose.position().y(), pose.position().z()).mul(MM_PER_CM),
                    new Quaterniond(q.x(), q.y(), q.z(), q.w()));
        }
    }

    /**
     * Where the scene is seen from. Up is the world's +y.
     *
     * @param eye the camera's position, in mm
     * @param target the point it looks at, in mm; not the eye, and not straight above or below it
     * @param fovYDegrees the vertical field of view, in degrees
     */
    public record Camera(Vector3dc eye, Vector3dc target, double fovYDegrees) {

        /**
         * Checks the parts and copies the points.
         *
         * @throws NullPointerException if a point is {@code null}
         * @throws IllegalArgumentException if the field of view is not within (0, 180) degrees
         */
        public Camera {
            eye = new Vector3d(Objects.requireNonNull(eye, "eye"));
            target = new Vector3d(Objects.requireNonNull(target, "target"));
            if (!(fovYDegrees > 0 && fovYDegrees < 180)) {
                throw new IllegalArgumentException("field of view out of range: " + fovYDegrees);
            }
        }
    }

    // The prototype tray's 32 degree lens at its default 50 degree pitch, looking at the Tray's middle.
    private static final Camera DEFAULT_CAMERA = new Camera(
            new Vector3d(0, 5 + 400 * Math.sin(Math.toRadians(50)), 400 * Math.cos(Math.toRadians(50))),
            new Vector3d(0, 5, 0), 32);
    // The bridge test's key light and a cool fill from the other side, as directions toward the lights.
    private static final Vector3dc KEY = new Vector3d(-0.45, 0.85, 0.35).normalize();
    private static final Vector3dc FILL = new Vector3d(0.7, 0.45, -0.5).normalize();

    private volatile List<Die> dice = List.of();
    private volatile Camera camera = DEFAULT_CAMERA;

    // Render thread only.
    private final Map<DieKind, GlMesh> meshes = new EnumMap<>(DieKind.class);
    private final Map<DieKind, GlAtlas> atlases = new EnumMap<>(DieKind.class);
    private int program;
    private int modelUniform;
    private int viewProjectionUniform;
    private int eyeUniform;
    private int keyUniform;
    private int fillUniform;
    private int colourUniform;
    private int inkUniform;
    private int mmPerUvUniform;

    // The texture units the engraving's atlas is bound to.
    private static final int MASK_UNIT = 0;
    private static final int SLOPE_UNIT = 1;

    /** Creates an empty scene; its GL objects are made in {@link #init()}. */
    public DiceScene() {}

    /**
     * Replaces the Dice drawn. Takes effect at the next frame; any thread.
     *
     * @param dice the Dice, drawn in this order
     */
    public void setDice(List<Die> dice) {
        this.dice = List.copyOf(dice);
    }

    /**
     * Moves the camera. Takes effect at the next frame; any thread.
     *
     * @param camera the new camera
     */
    public void setCamera(Camera camera) {
        this.camera = Objects.requireNonNull(camera, "camera");
    }

    /**
     * How many triangles a Die of a kind is drawn with. Render thread only, after {@link #init()}; for tests.
     *
     * @param kind the Die Kind
     * @return the triangle count of the mesh uploaded for it
     */
    int drawnTriangles(DieKind kind) {
        RenderThread.checkRenderThread();
        return meshes.get(kind).triangleCount();
    }

    @Override
    public void init() {
        program = Shaders.program("die.vert", "die.frag");
        modelUniform = glGetUniformLocation(program, "uModel");
        viewProjectionUniform = glGetUniformLocation(program, "uViewProjection");
        eyeUniform = glGetUniformLocation(program, "uEye");
        keyUniform = glGetUniformLocation(program, "uKeyDirection");
        fillUniform = glGetUniformLocation(program, "uFillDirection");
        colourUniform = glGetUniformLocation(program, "uColour");
        inkUniform = glGetUniformLocation(program, "uInk");
        mmPerUvUniform = glGetUniformLocation(program, "uMmPerUv");
        glUseProgram(program);
        glUniform1i(glGetUniformLocation(program, "uMask"), MASK_UNIT);
        glUniform1i(glGetUniformLocation(program, "uSlope"), SLOPE_UNIT);
        glUniform1f(glGetUniformLocation(program, "uDepth"), (float) NumeralAtlas.VISIBLE_DEPTH_MM);
        glUseProgram(0);
        // One upload per Die Kind: the d10 and d% share a solid but not their Numerals, so not their UVs either.
        // Each atlas is rasterised, uploaded and dropped in turn, so only one is ever held in memory.
        for (DieKind kind : DieKind.values()) {
            meshes.put(kind, new GlMesh(kind));
            atlases.put(kind, new GlAtlas(NumeralAtlas.rasterise(NumeralLayout.of(kind))));
        }
    }

    @Override
    public void render(int width, int height) {
        glClearColor(((BACKGROUND_ARGB >> 16) & 0xff) / 255f, ((BACKGROUND_ARGB >> 8) & 0xff) / 255f,
                (BACKGROUND_ARGB & 0xff) / 255f, 1);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        List<Die> drawn = dice;
        if (drawn.isEmpty()) {
            return;
        }
        Camera view = camera;
        glEnable(GL_DEPTH_TEST);
        // Back faces culled: the meshes wind counter-clockwise from outside, so a wrongly wound one shows.
        glEnable(GL_CULL_FACE);
        glCullFace(GL_BACK);
        glUseProgram(program);

        double distance = view.eye().distance(view.target());
        // Near and far bracket everything within a metre of the target, which holds any Tray and its Dice.
        double near = Math.max(1, distance - 1000);
        double far = distance + 1000;
        Matrix4d viewProjection = new Matrix4d()
                .perspective(Math.toRadians(view.fovYDegrees()), (double) width / height, near, far)
                .lookAt(view.eye(), view.target(), new Vector3d(0, 1, 0));
        glUniformMatrix4fv(viewProjectionUniform, false, columns(viewProjection));
        glUniform3f(eyeUniform, (float) view.eye().x(), (float) view.eye().y(), (float) view.eye().z());
        glUniform3f(keyUniform, (float) KEY.x(), (float) KEY.y(), (float) KEY.z());
        glUniform3f(fillUniform, (float) FILL.x(), (float) FILL.y(), (float) FILL.z());
        // An ivory die colour, linear RGB (the bridge test cube's).
        glUniform3f(colourUniform, 0.80f, 0.76f, 0.66f);
        // A near-black ink, linear RGB (Ivory / black is spec Appendix B's second Dice Set).
        glUniform3f(inkUniform, 0.012f, 0.012f, 0.013f);
        for (Die die : drawn) {
            Matrix4d model = new Matrix4d().translation(die.position()).rotate(die.orientation());
            glUniformMatrix4fv(modelUniform, false, columns(model));
            GlAtlas atlas = atlases.get(die.kind());
            atlas.bind(MASK_UNIT, SLOPE_UNIT);
            glUniform1f(mmPerUvUniform, atlas.mmPerUv());
            meshes.get(die.kind()).draw();
        }
        glUseProgram(0);
        glDisable(GL_CULL_FACE);
        glDisable(GL_DEPTH_TEST);
    }

    @Override
    public void dispose() {
        // Every Die Kind has buffers and textures of its own (the d10 and d% differ in their UVs), so each is
        // deleted once.
        meshes.values().forEach(GlMesh::delete);
        meshes.clear();
        atlases.values().forEach(GlAtlas::delete);
        atlases.clear();
        glDeleteProgram(program);
    }

    /**
     * A matrix as the column-major floats {@code glUniformMatrix4fv} takes untransposed. Read element by element:
     * JOML's own array copies go through {@code sun.misc.Unsafe} unless told not to, which JDK 27 warns about.
     */
    private static float[] columns(Matrix4dc m) {
        return new float[] {
            (float) m.m00(), (float) m.m01(), (float) m.m02(), (float) m.m03(),
            (float) m.m10(), (float) m.m11(), (float) m.m12(), (float) m.m13(),
            (float) m.m20(), (float) m.m21(), (float) m.m22(), (float) m.m23(),
            (float) m.m30(), (float) m.m31(), (float) m.m32(), (float) m.m33(),
        };
    }
}
