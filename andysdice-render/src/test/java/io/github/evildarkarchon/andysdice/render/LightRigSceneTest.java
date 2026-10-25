package io.github.evildarkarchon.andysdice.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.lwjgl.opengl.GL11C.GL_NO_ERROR;
import static org.lwjgl.opengl.GL11C.glGetError;

import io.github.evildarkarchon.andysdice.core.diceset.DiceSet;
import io.github.evildarkarchon.andysdice.core.diceset.DiceSetCatalogue;
import io.github.evildarkarchon.andysdice.core.diceset.DiceSetFile;
import io.github.evildarkarchon.andysdice.core.geometry.DieKind;
import io.github.evildarkarchon.andysdice.core.tray.BuiltInTrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.joml.Matrix4d;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.Vector4d;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The Light Rigs on a real GL 3.3 core context (ADR-0005, ADR-0010): the scene takes a Light Rig message like its
 * other messages, every rig draws, the key light alone casts PCSS soft shadows, and Dice reflect each rig's room.
 * Asserts invariants (which side of a Die is darker, which channel leads), never golden images.
 */
@Tag("gl")
class LightRigSceneTest {

    private static final int SIZE = 160;

    private static CaptureTransport capture;
    private static DiceScene scene;
    private static RenderThread renderer;
    // The first frame, before the scene was ever told a Light Rig.
    private static Still unset;

    /** The whole Tray at the default 50° pitch, a d20 at rest in it. */
    private static final DiceScene.Camera WHOLE_TRAY = new DiceScene.Camera(
            new Vector3d(0, 5 + 520 * Math.sin(Math.toRadians(50)), -520 * Math.cos(Math.toRadians(50))),
            new Vector3d(0, 5, 0), 32);
    private static final List<DiceScene.Die> RESTING_D20 =
            List.of(new DiceScene.Die(DieKind.D20, new Vector3d(0, 10, 0), new Quaterniond()));

    @BeforeAll
    static void startRenderThread() throws Exception {
        capture = new CaptureTransport();
        scene = new DiceScene();
        renderer = RenderThread.start(capture, scene);
        renderer.resize(SIZE, SIZE);
        unset = capture.take(renderer, () -> {
            scene.setTray(BuiltInTrays.defaultTray());
            scene.setDice(RESTING_D20);
            scene.setCamera(WHOLE_TRAY);
        }).get(30, TimeUnit.SECONDS);
    }

    @AfterAll
    static void stopRenderThread() {
        if (renderer != null) {
            renderer.close();
        }
    }

    @Test
    void aSceneNeverToldALightRigIsLitByStudio() throws Exception {
        Still studio = take(LightRig.STUDIO, RESTING_D20, WHOLE_TRAY);

        assertThat(differingPixels(studio, unset)).isZero();
    }

    @ParameterizedTest
    @EnumSource(LightRig.class)
    void everyRigCompilesAndDeliversFrames(LightRig rig) throws Exception {
        Still still = take(rig, RESTING_D20, WHOLE_TRAY);

        assertThat(still.argb(SIZE / 2, SIZE / 2)).isNotEqualTo(DiceScene.BACKGROUND_ARGB);
        assertThat(renderer.submit(() -> glGetError()).get(10, TimeUnit.SECONDS)).isEqualTo(GL_NO_ERROR);
    }

    @Test
    void eachRigLightsTheSceneDifferently() throws Exception {
        Still studio = take(LightRig.STUDIO, RESTING_D20, WHOLE_TRAY);
        Still lamp = take(LightRig.LAMP, RESTING_D20, WHOLE_TRAY);
        Still window = take(LightRig.WINDOW, RESTING_D20, WHOLE_TRAY);

        int all = SIZE * SIZE;
        assertThat(differingPixels(studio, lamp)).isGreaterThan(all / 2);
        assertThat(differingPixels(studio, window)).isGreaterThan(all / 2);
        assertThat(differingPixels(lamp, window)).isGreaterThan(all / 2);
    }

    /**
     * A Die held above the floor shades the floor where the key light's ray through it lands, and not the mirror
     * point on its other side, where a fill casting shadows would put one.
     */
    @ParameterizedTest
    @EnumSource(LightRig.class)
    void theKeyLightCastsTheDiesShadowAwayFromItself(LightRig rig) throws Exception {
        // Placed so that its shadow and the mirror point both fall on open floor, clear of the walls' shadows.
        Vector3d centre = switch (rig) {
            case STUDIO -> new Vector3d(0, 40, 0);
            case LAMP -> new Vector3d(-100, 50, 60);
            case WINDOW -> new Vector3d(20, 30, 0);
        };
        Vector3d shadow = alongKeyToFloor(rig, centre);
        Vector3d mirror = new Vector3d(centre.x(), 0, centre.z()).mul(2).sub(shadow);
        assertThat(shadow.distance(mirror)).as("the shadow lands clear of the Die").isGreaterThan(40);
        DiceScene.Camera above = topDown(new Vector3d(centre.x(), 0, centre.z()), 420);

        Still still = take(rig, List.of(cube(centre)), above);

        double inShadow = luminance(still, above, shadow);
        double opposite = luminance(still, above, mirror);
        assertThat(inShadow).as("%s: floor under the key's shadow vs the mirror point", rig)
                .isLessThan(0.75 * opposite);
    }

    /**
     * PCSS: a shadow's edge is sharp where the caster is near the floor and widens with the gap, as a light of some
     * size makes it. Under a directional key a cube's shadow has the same shape at any height, so only the width of
     * its edge changes.
     */
    @Test
    void aShadowsEdgeSoftensAsItsCasterRises() throws Exception {
        LightRig rig = LightRig.WINDOW;
        Vector3d low = new Vector3d(0, 12, 0);
        Vector3d high = new Vector3d(0, 30, 0);
        DiceScene.Camera above = new DiceScene.Camera(new Vector3d(45, 300, -0.01), new Vector3d(45, 0, 0), 32);

        Still bare = take(rig, List.of(), above);
        int lowEdge = edgeSamples(take(rig, List.of(cube(low)), above), bare, above, alongKeyToFloor(rig, low));
        int highEdge = edgeSamples(take(rig, List.of(cube(high)), above), bare, above, alongKeyToFloor(rig, high));

        assertThat(lowEdge).as("the low cube's shadow has an edge at all").isPositive();
        assertThat(highEdge).as("penumbra samples: raised %d vs low %d", highEdge, lowEdge)
                .isGreaterThan(lowEdge * 3 / 2);
    }

    @Test
    void metalDiceTakeOnTheLampsWarmRoomAndTheWindowsCoolOne() throws Exception {
        DiceSet steel = DiceSetCatalogue.builtIn().find("08-steel").orElseThrow();
        assertReflectsTheRooms(steel);
    }

    @Test
    void glossyTranslucentDiceReflectTheRoomsToo() throws Exception {
        // A clear, colourless body: what it shows of the rig is what its surface reflects.
        DiceSet clear = DiceSetFile.parse("clear", "format = 1\nbase = #101010\nroughness = 0.05\n"
                + "transmission = 0.9\ntransmission.color = #ffffff\n");
        assertThat(clear.translucent()).isTrue();
        assertReflectsTheRooms(clear);
    }

    /** Under the Lamp a Die's pixels lean red; under the Window, blue (Appendix C's warm spot and cool daylight). */
    private static void assertReflectsTheRooms(DiceSet set) throws Exception {
        DiceScene.Camera close = new DiceScene.Camera(new Vector3d(0, 70, -60), new Vector3d(0, 10, 0), 32);
        double[] lamp = dieColour(set, LightRig.LAMP, close);
        double[] window = dieColour(set, LightRig.WINDOW, close);

        assertThat(lamp[0]).as("%s under the Lamp, red over blue", set.name()).isGreaterThan(lamp[2] * 1.2);
        assertThat(window[2]).as("%s under the Window, blue over red", set.name()).isGreaterThan(window[0]);
    }

    /**
     * The mean RGB of the pixels a d20 in a Dice Set covers. They are found as the pixels that change when the same
     * d20 is drawn in Ivory instead: its shadow on the felt is the same in both, so it doesn't count.
     */
    private static double[] dieColour(DiceSet set, LightRig rig, DiceScene.Camera camera) throws Exception {
        DiceSet ivory = DiceSetCatalogue.builtIn().find("02-ivory").orElseThrow();
        Still other = capture.take(renderer, () -> {
            scene.setDiceSet(ivory);
            scene.setLightRig(rig);
            scene.setTray(BuiltInTrays.defaultTray());
            scene.setDice(RESTING_D20);
            scene.setCamera(camera);
        }).get(30, TimeUnit.SECONDS);
        Still die = capture.take(renderer, () -> scene.setDiceSet(set)).get(30, TimeUnit.SECONDS);
        double[] sum = new double[3];
        int count = 0;
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int argb = die.argb(x, y);
                if (argb != other.argb(x, y)) {
                    sum[0] += (argb >> 16) & 0xff;
                    sum[1] += (argb >> 8) & 0xff;
                    sum[2] += argb & 0xff;
                    count++;
                }
            }
        }
        assertThat(count).as("the d20 covers part of the view").isGreaterThan(SIZE * SIZE / 20);
        return new double[] {sum[0] / count, sum[1] / count, sum[2] / count};
    }

    /**
     * How many samples across a cube's shadow are in its penumbra: a line along z through the shadow's middle, every
     * 0.25 mm, each taken as the frame's luminance over the bare floor's, counted when it is clearly between the
     * shadow's darkest and fully lit.
     */
    private static int edgeSamples(Still withCube, Still bare, DiceScene.Camera camera, Vector3dc shadowCentre) {
        int samples = 241;
        double[] ratio = new double[samples];
        double darkest = 1;
        for (int i = 0; i < samples; i++) {
            Vector3d p = new Vector3d(shadowCentre.x(), 0, shadowCentre.z() - 30 + i * 0.25);
            ratio[i] = luminance(withCube, camera, p) / luminance(bare, camera, p);
            darkest = Math.min(darkest, ratio[i]);
        }
        double margin = 0.15 * (1 - darkest);
        int edge = 0;
        for (double r : ratio) {
            if (r > darkest + margin && r < 1 - margin) {
                edge++;
            }
        }
        return edge;
    }

    /** Where the key light's ray through a point meets the floor. */
    private static Vector3d alongKeyToFloor(LightRig rig, Vector3dc point) {
        Vector3d toward = rig.spot() ? new Vector3d(rig.keyPosition()).sub(point) : new Vector3d(rig.keyPosition());
        return new Vector3d(toward).mul(-point.y() / toward.y()).add(point);
    }

    /** A d6 (a cube 16 mm across) with its faces square to the world axes, centred on a point. */
    private static DiceScene.Die cube(Vector3dc centre) {
        return new DiceScene.Die(DieKind.D6, centre, new Quaterniond());
    }

    /** Straight down on a floor point from a height. */
    private static DiceScene.Camera topDown(Vector3dc target, double height) {
        return new DiceScene.Camera(new Vector3d(target.x(), height, target.z() - 0.01), target, 32);
    }

    /** Draws the default Tray under a rig, with some Dice, from a camera. */
    private static Still take(LightRig rig, List<DiceScene.Die> dice, DiceScene.Camera camera) throws Exception {
        return capture.take(renderer, () -> {
            scene.setDiceSet(DiceSetCatalogue.builtIn().fallback());
            scene.setLightRig(rig);
            scene.setTray(BuiltInTrays.defaultTray());
            scene.setDice(dice);
            scene.setCamera(camera);
        }).get(30, TimeUnit.SECONDS);
    }

    /** The luminance (0..255) of the pixel a world point lands on, projected as {@link DiceScene} projects. */
    private static double luminance(Still still, DiceScene.Camera camera, Vector3dc world) {
        Matrix4d viewProjection = new Matrix4d().perspective(Math.toRadians(camera.fovYDegrees()), 1, 1, 2000)
                .lookAt(camera.eye(), camera.target(), new Vector3d(0, 1, 0));
        Vector4d clip = new Vector4d(world, 1).mul(viewProjection);
        int x = (int) Math.floor((clip.x() / clip.w() * 0.5 + 0.5) * SIZE);
        int y = (int) Math.floor((0.5 - clip.y() / clip.w() * 0.5) * SIZE);
        assertThat(x).as("column of %s", world).isBetween(0, SIZE - 1);
        assertThat(y).as("row of %s", world).isBetween(0, SIZE - 1);
        int argb = still.argb(x, y);
        return 0.2126 * ((argb >> 16) & 0xff) + 0.7152 * ((argb >> 8) & 0xff) + 0.0722 * (argb & 0xff);
    }

    private static int differingPixels(Still a, Still b) {
        int differing = 0;
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                if (a.argb(x, y) != b.argb(x, y)) {
                    differing++;
                }
            }
        }
        return differing;
    }
}
