package io.github.evildarkarchon.andysdice.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.lwjgl.opengl.GL11C.GL_NO_ERROR;
import static org.lwjgl.opengl.GL11C.glGetError;

import io.github.evildarkarchon.andysdice.core.diceset.DiceSet;
import io.github.evildarkarchon.andysdice.core.diceset.DiceSetCatalogue;
import io.github.evildarkarchon.andysdice.core.diceset.SrgbColour;
import io.github.evildarkarchon.andysdice.core.geometry.DieKind;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Translucent Dice Sets (ticket #43, ADR-0009) on a real GL 3.3 core context: the refracted view through the body
 * reads a copy of the opaque pass, the far face's Numerals show through, and the volume march's sample count is a
 * lever. Asserts invariants (what changing the scene behind or the far ink changes), never golden images.
 */
@Tag("gl")
class TranslucentDiceSetTest {

    private static final int SIZE = 128;
    // Two backdrops far apart in hue, standing in for whatever is drawn before the translucent Dice.
    private static final int RED_BACKDROP = 0xffa02818;
    private static final int BLUE_BACKDROP = 0xff1828a0;

    private static CaptureTransport capture;
    private static DiceScene scene;
    private static RenderThread renderer;

    @BeforeAll
    static void startRenderThread() {
        capture = new CaptureTransport();
        scene = new DiceScene();
        renderer = RenderThread.start(capture, scene);
        renderer.resize(SIZE, SIZE);
    }

    @AfterAll
    static void stopRenderThread() {
        if (renderer != null) {
            renderer.close();
        }
    }

    @AfterEach
    void restoreTheScene() {
        scene.setClearColour(DiceScene.BACKGROUND_ARGB);
        scene.setVolumeSteps(DiceScene.DEFAULT_VOLUME_STEPS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"11-smoke", "12-ruby", "13-emerald", "14-nebula"})
    void everyTranslucentBuiltInDrawsADieWithoutAGlError(String identity) throws Exception {
        DiceSet set = DiceSetCatalogue.builtIn().find(identity).orElseThrow();
        assertThat(set.translucent()).isTrue();
        renderer.submit(() -> {
            // Start clean, so an error found below is this frame's.
            while (glGetError() != GL_NO_ERROR) {
                // Drained: earlier work's errors aren't this test's.
            }
            return null;
        }).get(10, TimeUnit.SECONDS);

        Still still = draw(set, DieKind.D20);

        assertThat(still.argb(SIZE / 2, SIZE / 2)).as("the die covers the centre")
                .isNotEqualTo(DiceScene.BACKGROUND_ARGB);
        int error = renderer.submit(() -> glGetError()).get(10, TimeUnit.SECONDS);
        assertThat(error).as("GL error after drawing %s", identity).isEqualTo(GL_NO_ERROR);
    }

    @Test
    void aTranslucentDieShowsWhatIsBehindIt() throws Exception {
        DiceSet smoke = clear(DiceSetCatalogue.builtIn().find("11-smoke").orElseThrow());
        DiceSet opaque = withTransmission(smoke, 0);

        // Through a clear body the backdrop's colour reaches the Die's own pixels; an opaque body shows only its
        // lights and its analytic room, which no backdrop changes.
        double translucentShift = redMinusBlue(smoke, RED_BACKDROP) - redMinusBlue(smoke, BLUE_BACKDROP);
        double opaqueShift = redMinusBlue(opaque, RED_BACKDROP) - redMinusBlue(opaque, BLUE_BACKDROP);
        // On the dev GPU: 76 inside the translucent Die, exactly 0 inside the opaque one (78 and 0 with #60's MSAA,
        // the silhouette left out).
        assertThat(translucentShift).as("mean red-blue shift inside the translucent Die").isGreaterThan(30);
        assertThat(Math.abs(opaqueShift)).as("the same shift inside the opaque Die").isLessThan(1);
    }

    @Test
    void theFarFacesNumeralsShowThroughTheBody() throws Exception {
        DiceSet smoke = clear(DiceSetCatalogue.builtIn().find("11-smoke").orElseThrow());
        DiceSet opaque = withTransmission(smoke, 0);
        SrgbColour black = new SrgbColour(0, 0, 0);

        // Re-inking an opaque Die changes only its near faces' Numerals; re-inking a translucent one also changes
        // the Numerals seen through the body, on the faces at the back.
        int opaqueChanged = changed(draw(opaque, DieKind.D6), draw(withInk(opaque, black), DieKind.D6));
        int translucentChanged = changed(draw(smoke, DieKind.D6), draw(withInk(smoke, black), DieKind.D6));
        // On the dev GPU: 2400 pixels against the opaque Die's 1359 (and 1241 with the far ink switched off).
        assertThat(opaqueChanged).as("pixels the near Numerals cover").isGreaterThan(20);
        assertThat(translucentChanged).as("pixels changed by re-inking the translucent Die; opaque %s",
                opaqueChanged).isGreaterThan(opaqueChanged * 3 / 2);
    }

    @Test
    void theVolumeMarchsSampleCountIsALever() throws Exception {
        DiceSet nebula = DiceSetCatalogue.builtIn().find("14-nebula").orElseThrow();

        Still eight = draw(nebula, DieKind.D20);
        scene.setVolumeSteps(1);
        Still one = draw(nebula, DieKind.D20);
        scene.setVolumeSteps(DiceScene.DEFAULT_VOLUME_STEPS);
        Still eightAgain = draw(nebula, DieKind.D20);

        // Fewer samples see fewer of Nebula's clouds and glitter flakes inside the body (on the dev GPU, a third of
        // the frame changes).
        assertThat(changed(eight, one)).as("pixels changed by marching 1 step instead of 8")
                .isGreaterThan(SIZE * SIZE / 50);
        assertThat(changed(eight, eightAgain)).as("the same count draws the same").isZero();
    }

    @Test
    void theSampleCountIsBounded() {
        assertThat(DiceScene.DEFAULT_VOLUME_STEPS).as("ADR-0009's 8 steps").isEqualTo(8);
        assertThatThrownBy(() -> scene.setVolumeSteps(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> scene.setVolumeSteps(DiceScene.MAX_VOLUME_STEPS + 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Draws one Die of a kind in a Dice Set over the default backdrop, filling most of the frame. */
    private static Still draw(DiceSet set, DieKind kind) throws Exception {
        return capture.take(renderer, () -> {
            scene.setDiceSet(set);
            scene.setCamera(new DiceScene.Camera(new Vector3d(0, 25, -40), new Vector3d(), 32));
            scene.setDice(List.of(new DiceScene.Die(kind, new Vector3d(),
                    new Quaterniond().rotationXYZ(0.4, 0.7, 0.2))));
        }).get(10, TimeUnit.SECONDS);
    }

    /** The mean of red minus blue over the Die's pixels, drawn in front of a backdrop. */
    private static double redMinusBlue(DiceSet set, int backdrop) throws Exception {
        scene.setClearColour(backdrop);
        Still still = draw(set, DieKind.D20);
        long sum = 0;
        int covered = 0;
        // Only the Die's inside: a pixel on its silhouette is partly backdrop once the scene draws with MSAA (the
        // default High preset, #60), whatever the body is made of.
        for (int y = 1; y < SIZE - 1; y++) {
            for (int x = 1; x < SIZE - 1; x++) {
                if (!insideTheDie(still, x, y, backdrop)) {
                    continue;
                }
                int argb = still.argb(x, y);
                covered++;
                sum += ((argb >> 16) & 0xff) - (argb & 0xff);
            }
        }
        assertThat(covered).isGreaterThan(SIZE * SIZE / 10);
        return sum / (double) covered;
    }

    /** Whether a pixel and its eight neighbours all show the Die rather than the backdrop. */
    private static boolean insideTheDie(Still still, int x, int y, int backdrop) {
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (still.argb(x + dx, y + dy) == backdrop) {
                    return false;
                }
            }
        }
        return true;
    }

    /** How many pixels differ clearly between two stills. */
    private static int changed(Still a, Still b) {
        int changed = 0;
        int[] p = a.pixels();
        int[] q = b.pixels();
        for (int i = 0; i < p.length; i++) {
            if (distance(p[i], q[i]) > 24) {
                changed++;
            }
        }
        return changed;
    }

    /** The largest channel difference between two colours, 0..255. */
    static int distance(int p, int q) {
        int d = 0;
        for (int shift = 0; shift <= 16; shift += 8) {
            d = Math.max(d, Math.abs(((p >> shift) & 0xff) - ((q >> shift) & 0xff)));
        }
        return d;
    }

    /** The Dice Set with a body that absorbs nothing, so whatever shows through it is at full strength. */
    static DiceSet clear(DiceSet set) {
        DiceSet.Transmission t = set.transmission();
        return new DiceSet(set.identity(), set.name(), set.base(), set.roughness(), set.metallic(), set.swirl(),
                set.flakes(), set.pearl(),
                new DiceSet.Transmission(t.amount(), new SrgbColour(0xff, 0xff, 0xff), t.ior(), t.cloudiness()),
                set.ink());
    }

    /** The Dice Set with another transmission amount; 0 makes it opaque. */
    static DiceSet withTransmission(DiceSet set, double amount) {
        DiceSet.Transmission t = set.transmission();
        return new DiceSet(set.identity(), set.name(), set.base(), set.roughness(), set.metallic(), set.swirl(),
                set.flakes(), set.pearl(), new DiceSet.Transmission(amount, t.colour(), t.ior(), t.cloudiness()),
                set.ink());
    }

    private static DiceSet withInk(DiceSet set, SrgbColour colour) {
        return new DiceSet(set.identity(), set.name(), set.base(), set.roughness(), set.metallic(), set.swirl(),
                set.flakes(), set.pearl(), set.transmission(),
                new DiceSet.Ink(colour, set.ink().metallic(), set.ink().roughness()));
    }
}
