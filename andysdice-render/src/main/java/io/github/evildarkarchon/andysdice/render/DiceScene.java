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

import io.github.evildarkarchon.andysdice.core.diceset.DiceSet;
import io.github.evildarkarchon.andysdice.core.diceset.DiceSetCatalogue;
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
 * whatever Die Kind at its pose, seen from a camera, under the Studio Light Rig's key and fill lights and its
 * analytic reflection environment, in the chosen Dice Set. The app's playback and the gallery both draw through it.
 *
 * <p>Every face carries its engraved, inked Numerals (ticket #41, ADR-0008): each Die Kind's atlas is
 * rasterised and uploaded at {@link #init()}, and {@code engraving.glsl} traces it by parallax occlusion.
 *
 * <p>One uber-shader ({@code die.frag}, ticket #42, ADR-0009) draws every Dice Set from its parameters: solid,
 * swirl, speckle, pearl and metal. A swirled Dice Set's field is baked into a 3D texture the first time it is
 * drawn. Until ticket #43, a translucent Dice Set is drawn opaque.
 *
 * <p>World space is y-up and in mm. A Die's orientation turns its z-up model frame (the face tables') into the
 * world, as {@code core}'s poses do.
 *
 * <p>{@link #setDice}, {@link #setCamera} and {@link #setDiceSet} may be called from any thread and take effect at
 * the next frame; everything else runs on the render thread, as a {@link FrameRenderer}.
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

    // The prototype tray's 32 degree lens at its default 50 degree pitch, looking at the Tray's middle from the
    // near side (-z), where the prototype's camera sits at zero yaw and the Studio key light shines from.
    private static final Camera DEFAULT_CAMERA = new Camera(
            new Vector3d(0, 5 + 400 * Math.sin(Math.toRadians(50)), -400 * Math.cos(Math.toRadians(50))),
            new Vector3d(0, 5, 0), 32);
    // The Studio Light Rig (the tray prototype's SceneSpec.light(STUDIO)): a warm key from above the camera's left
    // and a cool fill from the far right, as directions toward the lights and linear radiance. env.glsl's two
    // softboxes sit in the same directions. Ticket #45 makes the rigs choosable and adds their shadows.
    private static final Vector3dc KEY = new Vector3d(-0.45, 0.85, -0.35).normalize();
    private static final Vector3dc KEY_COLOUR = new Vector3d(1.0, 0.96, 0.90).mul(3.0);
    private static final Vector3dc FILL = new Vector3d(0.7, 0.45, 0.5).normalize();
    private static final Vector3dc FILL_COLOUR = new Vector3d(0.55, 0.62, 0.75).mul(0.5);

    private volatile List<Die> dice = List.of();
    private volatile Camera camera = DEFAULT_CAMERA;
    private volatile DiceSet diceSet = DiceSetCatalogue.builtIn().fallback();

    // Render thread only.
    private final Map<DieKind, GlMesh> meshes = new EnumMap<>(DieKind.class);
    private final Map<DieKind, GlAtlas> atlases = new EnumMap<>(DieKind.class);
    private SwirlVolumes swirls;
    private DiceSetUniforms diceSetUniforms;
    private int program;
    private int modelUniform;
    private int viewProjectionUniform;
    private int eyeUniform;
    private int keyUniform;
    private int keyColourUniform;
    private int fillUniform;
    private int fillColourUniform;
    private int mmPerUvUniform;

    // The texture units the engraving's atlas and the swirl volume are bound to.
    private static final int MASK_UNIT = 0;
    private static final int SLOPE_UNIT = 1;
    private static final int SWIRL_UNIT = 2;

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
     * Chooses the Dice Set every Die is drawn in (one per Roll, as in the prototypes). Takes effect at the next
     * frame; any thread. Until this is called, it is the built-in fallback, Onyx.
     *
     * @param diceSet the Dice Set
     */
    public void setDiceSet(DiceSet diceSet) {
        this.diceSet = Objects.requireNonNull(diceSet, "diceSet");
    }

    /**
     * How many swirl volumes have been baked so far. Render thread only, after {@link #init()}; for tests.
     *
     * @return the count of distinct swirl fields drawn since {@link #init()}
     */
    int bakedSwirls() {
        RenderThread.checkRenderThread();
        return swirls.baked();
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
        keyColourUniform = glGetUniformLocation(program, "uKeyColour");
        fillUniform = glGetUniformLocation(program, "uFillDirection");
        fillColourUniform = glGetUniformLocation(program, "uFillColour");
        mmPerUvUniform = glGetUniformLocation(program, "uMmPerUv");
        glUseProgram(program);
        glUniform1i(glGetUniformLocation(program, "uMask"), MASK_UNIT);
        glUniform1i(glGetUniformLocation(program, "uSlope"), SLOPE_UNIT);
        glUniform1f(glGetUniformLocation(program, "uDepth"), (float) NumeralAtlas.VISIBLE_DEPTH_MM);
        diceSetUniforms = new DiceSetUniforms(program, SWIRL_UNIT);
        glUseProgram(0);
        swirls = new SwirlVolumes();
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
        glUseProgram(program);
        // Before the depth test and culling go on: a first use of a swirled Dice Set bakes its volume here, with
        // a full-screen triangle into a framebuffer of its own.
        diceSetUniforms.apply(diceSet, swirls);
        glEnable(GL_DEPTH_TEST);
        // Back faces culled: the meshes wind counter-clockwise from outside, so a wrongly wound one shows.
        glEnable(GL_CULL_FACE);
        glCullFace(GL_BACK);

        double distance = view.eye().distance(view.target());
        // Near and far bracket everything within a metre of the target, which holds any Tray and its Dice.
        double near = Math.max(1, distance - 1000);
        double far = distance + 1000;
        Matrix4d viewProjection = new Matrix4d()
                .perspective(Math.toRadians(view.fovYDegrees()), (double) width / height, near, far)
                .lookAt(view.eye(), view.target(), new Vector3d(0, 1, 0));
        glUniformMatrix4fv(viewProjectionUniform, false, columns(viewProjection));
        glUniform3f(eyeUniform, (float) view.eye().x(), (float) view.eye().y(), (float) view.eye().z());
        vector(keyUniform, KEY);
        vector(keyColourUniform, KEY_COLOUR);
        vector(fillUniform, FILL);
        vector(fillColourUniform, FILL_COLOUR);
        for (int slot = 0; slot < drawn.size(); slot++) {
            Die die = drawn.get(slot);
            diceSetUniforms.seed(slot);
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
        swirls.delete();
        glDeleteProgram(program);
    }

    /** Sets a {@code vec3} uniform from a vector. */
    private static void vector(int location, Vector3dc v) {
        glUniform3f(location, (float) v.x(), (float) v.y(), (float) v.z());
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
