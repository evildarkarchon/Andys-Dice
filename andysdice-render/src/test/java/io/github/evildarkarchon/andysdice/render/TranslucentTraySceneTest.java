package io.github.evildarkarchon.andysdice.render;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.evildarkarchon.andysdice.core.diceset.DiceSet;
import io.github.evildarkarchon.andysdice.core.diceset.DiceSetCatalogue;
import io.github.evildarkarchon.andysdice.core.geometry.DieKind;
import io.github.evildarkarchon.andysdice.core.tray.BuiltInTrays;
import io.github.evildarkarchon.andysdice.core.tray.Tray;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A translucent Die (ticket #43, ADR-0009) lying in a Tray (ticket #44) refracts the Tray's floor: the copy of the
 * opaque pass it reads is taken after the Tray is drawn. A class of its own because a scene, once given a Tray,
 * keeps one, and {@link TranslucentDiceSetTest} needs a Tray-less scene.
 */
@Tag("gl")
class TranslucentTraySceneTest {

    private static final int SIZE = 128;

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

    @Test
    void aTranslucentDieRefractsTheFloorOfTheTrayItLiesIn() throws Exception {
        DiceSet smoke = TranslucentDiceSetTest.clear(DiceSetCatalogue.builtIn().find("11-smoke").orElseThrow());
        DiceSet opaque = TranslucentDiceSetTest.withTransmission(smoke, 0);
        Tray felt = BuiltInTrays.find("01-green-felt").orElseThrow();
        Tray walnut = BuiltInTrays.find("03-walnut-board").orElseThrow();

        int[] opaqueOnFelt = inTray(opaque, felt).pixels();
        int[] opaqueOnWalnut = inTray(opaque, walnut).pixels();
        int[] translucentOnFelt = inTray(smoke, felt).pixels();
        int[] translucentOnWalnut = inTray(smoke, walnut).pixels();

        // An opaque Die looks the same in any Tray, so the pixels the two opaque stills share are the Die's.
        // Through a translucent Die the floor under it shows: green felt in one Tray, walnut in the other.
        int die = 0;
        int changed = 0;
        for (int i = 0; i < SIZE * SIZE; i++) {
            if (opaqueOnFelt[i] != opaqueOnWalnut[i]) {
                continue;
            }
            die++;
            if (TranslucentDiceSetTest.distance(translucentOnFelt[i], translucentOnWalnut[i]) > 24) {
                changed++;
            }
        }
        assertThat(die).as("pixels of the Die").isGreaterThan(SIZE * SIZE / 20);
        assertThat(changed).as("of the Die's %d pixels, those the Tray changes", die).isGreaterThan(die / 3);
    }

    /** A d20 resting in the middle of a Tray, seen from close above the near wall, in a Dice Set. */
    private static Still inTray(DiceSet set, Tray tray) throws Exception {
        return capture.take(renderer, () -> {
            scene.setTray(tray);
            scene.setDiceSet(set);
            scene.setCamera(new DiceScene.Camera(new Vector3d(0, 45, -45), new Vector3d(0, 10, 0), 32));
            scene.setDice(List.of(new DiceScene.Die(DieKind.D20, new Vector3d(0, 11, 0),
                    new Quaterniond().rotationXYZ(0.4, 0.7, 0.2))));
        }).get(30, TimeUnit.SECONDS);
    }
}
