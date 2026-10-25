package io.github.evildarkarchon.andysdice.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.lwjgl.opengl.GL11C.GL_NO_ERROR;
import static org.lwjgl.opengl.GL11C.glGetError;

import io.github.evildarkarchon.andysdice.core.diceset.DiceSet;
import io.github.evildarkarchon.andysdice.core.diceset.DiceSetCatalogue;
import io.github.evildarkarchon.andysdice.core.geometry.DieKind;
import io.github.evildarkarchon.andysdice.core.tray.BuiltInTrays;
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
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Every Quality Preset (ADR-0014) on a real GL 3.3 core context, handed to the scene as one message: each draws the
 * Tray and its Dice, opaque and translucent, without a GL error and at the render target's full size whatever its
 * render scale, each draws a different frame, and switching back to one draws its frame again. Invariants only,
 * never golden images (ADR-0005).
 */
@Tag("gl")
class QualityPresetSceneTest {

    private static final int SIZE = 160;

    private static CaptureTransport capture;
    private static DiceScene scene;
    private static RenderThread renderer;

    @BeforeAll
    static void startRenderThread() {
        capture = new CaptureTransport();
        scene = new DiceScene();
        // The Tray too: it receives the shadow and samples it with the preset's taps.
        scene.setTray(BuiltInTrays.defaultTray());
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
    void backToHigh() {
        scene.setQualityPreset(QualityPreset.HIGH);
    }

    @ParameterizedTest
    @CsvSource({
        "LOW, 01-onyx", "MEDIUM, 01-onyx", "HIGH, 01-onyx",
        "LOW, 14-nebula", "MEDIUM, 14-nebula", "HIGH, 14-nebula",
    })
    void everyPresetDrawsTheTrayAndItsDiceWithoutAGlError(QualityPreset preset, String diceSet) throws Exception {
        DiceSet set = DiceSetCatalogue.builtIn().find(diceSet).orElseThrow();
        renderer.submit(() -> {
            // Start clean, so an error found below is this frame's.
            while (glGetError() != GL_NO_ERROR) {
                // Drained: earlier work's errors aren't this test's.
            }
            return null;
        }).get(10, TimeUnit.SECONDS);

        scene.setQualityPreset(preset);
        Still still = draw(set);

        assertThat(still.width()).as("the frame fills the render target").isEqualTo(SIZE);
        assertThat(still.height()).isEqualTo(SIZE);
        Still trayOnly = drawNoDice();
        assertThat(changed(still, trayOnly)).as("pixels the Dice cover at %s", preset)
                .isGreaterThan(SIZE * SIZE / 20);
        int error = renderer.submit(() -> glGetError()).get(10, TimeUnit.SECONDS);
        assertThat(error).as("GL error drawing %s at %s", diceSet, preset).isEqualTo(GL_NO_ERROR);
    }

    @Test
    void eachPresetDrawsADifferentFrameOfTheSameScene() throws Exception {
        DiceSet onyx = DiceSetCatalogue.builtIn().fallback();
        scene.setQualityPreset(QualityPreset.LOW);
        Still low = draw(onyx);
        scene.setQualityPreset(QualityPreset.MEDIUM);
        Still medium = draw(onyx);
        scene.setQualityPreset(QualityPreset.HIGH);
        Still high = draw(onyx);

        assertThat(changed(low, high)).as("Low against High").isGreaterThan(SIZE * SIZE / 100);
        assertThat(changed(medium, high)).as("Medium against High").isGreaterThan(SIZE * SIZE / 100);
        assertThat(changed(low, medium)).as("Low against Medium").isGreaterThan(SIZE * SIZE / 100);
        // Yet it is the same picture: a scaled-down frame is drawn over the whole target, not into a corner of it.
        assertThat(changed(low, high, 96)).as("pixels far off between Low and High").isLessThan(SIZE * SIZE / 5);
    }

    @Test
    void switchingBackToAPresetDrawsItsFrameAgain() throws Exception {
        DiceSet nebula = DiceSetCatalogue.builtIn().find("14-nebula").orElseThrow();
        scene.setQualityPreset(QualityPreset.HIGH);
        Still high = draw(nebula);
        scene.setQualityPreset(QualityPreset.LOW);
        Still low = draw(nebula);
        Still lowAgain = draw(nebula);
        scene.setQualityPreset(QualityPreset.HIGH);
        Still highAgain = draw(nebula);

        assertThat(changed(low, lowAgain)).as("Low twice").isZero();
        assertThat(changed(high, highAgain)).as("High, then Low, then High").isZero();
    }

    /** Draws a D20 and a D6 of a Dice Set in the Tray, filling much of the frame. */
    private static Still draw(DiceSet set) throws Exception {
        return capture.take(renderer, () -> {
            scene.setDiceSet(set);
            scene.setCamera(new DiceScene.Camera(new Vector3d(0, 60, -70), new Vector3d(0, 8, 0), 32));
            scene.setDice(List.of(
                    new DiceScene.Die(DieKind.D20, new Vector3d(-12, 11, 0),
                            new Quaterniond().rotationXYZ(0.4, 0.7, 0.2)),
                    new DiceScene.Die(DieKind.D6, new Vector3d(14, 8, 4),
                            new Quaterniond().rotationXYZ(-0.3, 0.2, 0.9))));
        }).get(20, TimeUnit.SECONDS);
    }

    /** Draws the Tray alone from the same camera. */
    private static Still drawNoDice() throws Exception {
        return capture.take(renderer, () -> scene.setDice(List.of())).get(20, TimeUnit.SECONDS);
    }

    /** How many pixels differ clearly between two stills. */
    private static int changed(Still a, Still b) {
        return changed(a, b, 24);
    }

    /** How many pixels differ by more than a channel threshold between two stills. */
    private static int changed(Still a, Still b, int threshold) {
        int changed = 0;
        int[] p = a.pixels();
        int[] q = b.pixels();
        for (int i = 0; i < p.length; i++) {
            if (TranslucentDiceSetTest.distance(p[i], q[i]) > threshold) {
                changed++;
            }
        }
        return changed;
    }
}
