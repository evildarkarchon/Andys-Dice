package io.github.evildarkarchon.andysdice.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.github.evildarkarchon.andysdice.core.simulation.Vector3;
import io.github.evildarkarchon.andysdice.render.DiceScene;
import io.github.evildarkarchon.andysdice.sound.ScreenProjection;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

/** Panning follows where a contact appears across the screen, as the camera sees it now (ADR-0012 "Output"). */
class CameraPanningTest {

    /** A 90° lens 1 m behind the origin on -z, looking along +z: world -x is screen right. */
    private static final DiceScene.Camera BEHIND = new DiceScene.Camera(new Vector3d(0, 0, -1000),
            new Vector3d(0, 0, 0), 90);

    @Test
    void aPointStraightAheadIsInTheMiddle() {
        assertThat(CameraPanning.of(BEHIND, 1).screenX(new Vector3(0, 0, 30))).isCloseTo(0, within(1e-9));
    }

    @Test
    void screenXIsTheFractionOfTheHalfWidthAcrossFromTheMiddle() {
        ScreenProjection square = CameraPanning.of(BEHIND, 1);
        // 50 mm left in the world (screen right) at 1500 mm deep, through a half-width of tan 45° per mm of depth.
        assertThat(square.screenX(new Vector3(-5, 0, 50))).isCloseTo(50.0 / 1500, within(1e-9));
        assertThat(square.screenX(new Vector3(5, 0, 50))).isCloseTo(-50.0 / 1500, within(1e-9));
        // At the frustum's edge: as far across as it is deep.
        assertThat(square.screenX(new Vector3(-100, 0, 0))).isCloseTo(1, within(1e-9));
    }

    @Test
    void aWiderViewPutsThePointNearerTheMiddle() {
        assertThat(CameraPanning.of(BEHIND, 2).screenX(new Vector3(-100, 0, 0))).isCloseTo(0.5, within(1e-9));
    }

    @Test
    void anOrbitedCameraPansByItsOwnView() {
        // Looking along -x from +x (y up, right-handed): world -z is now screen right.
        DiceScene.Camera side = new DiceScene.Camera(new Vector3d(1000, 0, 0), new Vector3d(0, 0, 0), 90);
        assertThat(CameraPanning.of(side, 1).screenX(new Vector3(0, 0, -50))).isCloseTo(0.5, within(1e-9));
    }

    @Test
    void aPointBehindTheCameraIsAtTheEdgeOnItsSide() {
        assertThat(CameraPanning.of(BEHIND, 1).screenX(new Vector3(-10, 0, -200))).isEqualTo(1);
        assertThat(CameraPanning.of(BEHIND, 1).screenX(new Vector3(10, 0, -200))).isEqualTo(-1);
    }
}
