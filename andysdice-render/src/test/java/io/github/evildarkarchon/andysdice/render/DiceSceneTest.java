package io.github.evildarkarchon.andysdice.render;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.evildarkarchon.andysdice.core.geometry.DieKind;
import io.github.evildarkarchon.andysdice.core.simulation.Orientation;
import io.github.evildarkarchon.andysdice.core.simulation.Pose;
import io.github.evildarkarchon.andysdice.core.simulation.Vector3;
import io.github.evildarkarchon.andysdice.render.mesh.DieMesh;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;

/**
 * The dice renderer on a real GL 3.3 core context (ADR-0005): every Die Kind is drawn, with its rounded mesh,
 * where its pose puts it. Asserts invariants (covered pixels, what was drawn), never golden images.
 */
@Tag("gl")
class DiceSceneTest {

    private static final int SIZE = 96;

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

    @ParameterizedTest
    @EnumSource(DieKind.class)
    void everyDieKindIsDrawnWithItsRoundedMesh(DieKind kind) throws Exception {
        Still still = capture.take(renderer, () -> {
            scene.setCamera(new DiceScene.Camera(new Vector3d(0, 25, 40), new Vector3d(), 32));
            scene.setDice(List.of(new DiceScene.Die(kind, new Vector3d(), new Quaterniond())));
        }).get(10, TimeUnit.SECONDS);

        assertThat(still.width()).isEqualTo(SIZE);
        assertThat(still.argb(0, 0)).isEqualTo(DiceScene.BACKGROUND_ARGB);
        assertThat(still.argb(SIZE / 2, SIZE / 2)).as("the die covers the centre")
                .isNotEqualTo(DiceScene.BACKGROUND_ARGB);
        int drawn = renderer.submit(() -> scene.drawnTriangles(kind)).get(10, TimeUnit.SECONDS);
        assertThat(drawn).isEqualTo(DieMesh.of(kind).triangleCount());
    }

    @Test
    void aDieIsDrawnWhereItsPosePutsIt() throws Exception {
        // Physics poses are in cm: 3 cm to the right of the target, seen from straight ahead.
        DiceScene.Die die = DiceScene.Die.of(DieKind.D6,
                new Pose(new Vector3(3, 0, 0), Orientation.IDENTITY));
        assertThat(die.position()).isEqualTo(new Vector3d(30, 0, 0));

        Still still = capture.take(renderer, () -> {
            scene.setCamera(new DiceScene.Camera(new Vector3d(0, 0, 150), new Vector3d(), 32));
            scene.setDice(List.of(die));
        }).get(10, TimeUnit.SECONDS);

        assertThat(still.argb(SIZE / 2, SIZE / 2)).as("nothing at the centre")
                .isEqualTo(DiceScene.BACKGROUND_ARGB);
        assertThat(still.argb(SIZE * 3 / 4, SIZE / 2)).as("the die right of centre")
                .isNotEqualTo(DiceScene.BACKGROUND_ARGB);
    }

    @Test
    void withNoDiceOnlyTheBackgroundIsDrawn() throws Exception {
        Still still = capture.take(renderer, () -> scene.setDice(List.of())).get(10, TimeUnit.SECONDS);

        for (int y = 0; y < SIZE; y += 8) {
            for (int x = 0; x < SIZE; x += 8) {
                assertThat(still.argb(x, y)).isEqualTo(DiceScene.BACKGROUND_ARGB);
            }
        }
    }
}
