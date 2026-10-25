package io.github.evildarkarchon.andysdice.render.camera;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.github.evildarkarchon.andysdice.core.geometry.DieKind;
import io.github.evildarkarchon.andysdice.core.simulation.TrayGeometry;
import io.github.evildarkarchon.andysdice.render.DiceScene;
import java.util.List;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

/**
 * The automatic camera's two framings, without GL: the whole Tray from the fixed 3/4 view, and the close-up on
 * the Dice at rest. The expected distances are the tray prototype's framing worked by hand for the Footprint
 * (300 × 200 mm inside 30 mm walls, 14 mm thick) under its 32° lens.
 */
class FramingTest {

    private static final Framing FOOTPRINT = Framing.of(TrayGeometry.rectangle(15, 10, 3));

    private static DiceScene.Die die(DieKind kind, double x, double y, double z) {
        return new DiceScene.Die(kind, new Vector3d(x, y, z), new Quaterniond());
    }

    @Test
    void theWholeTrayIsFramedFromTheNearEndAtAFixedThreeQuarterView() {
        CameraPose pose = FOOTPRINT.wholeTray(1.6);

        assertThat(pose.yawDegrees()).isZero();
        assertThat(pose.pitchDegrees()).isEqualTo(50);
        assertThat(pose.target()).isEqualTo(new Vector3d(0, 5, 0));
        // The depth (near wall to far wall, tilted by the pitch) is what limits a 16:10 view.
        assertThat(pose.distance()).isCloseTo(474.82, within(0.01));
        DiceScene.Camera camera = pose.camera();
        assertThat(camera.eye().z()).isNegative();
        assertThat(camera.eye().y()).isPositive();
        assertThat(camera.fovYDegrees()).isEqualTo(32);
    }

    @Test
    void aNarrowerViewStandsFurtherBackToKeepTheTrayWidthInFrame() {
        assertThat(FOOTPRINT.wholeTray(1.0).distance()).isCloseTo(690.97, within(0.01));
    }

    @Test
    void theDiceAtRestAreFramedFromHigherOnTheirMiddleOnTheFloor() {
        CameraPose pose = FOOTPRINT.resting(List.of(die(DieKind.D6, 30, 8, -20)), 1.6);

        assertThat(pose.pitchDegrees()).isEqualTo(62);
        assertThat(pose.yawDegrees()).isZero();
        assertThat(pose.target()).isEqualTo(new Vector3d(30, 0, -20));
        // One small Die is framed as if it were a 45 mm sphere, so a lone Die isn't filled edge to edge.
        assertThat(pose.distance()).isCloseTo(179.58, within(0.01));
    }

    @Test
    void diceSpreadAcrossTheTrayAreAllKeptInFrame() {
        CameraPose pose = FOOTPRINT.resting(List.of(die(DieKind.D20, -100, 10, 0), die(DieKind.D20, 100, 10, 0)),
                1.6);

        assertThat(pose.target()).isEqualTo(new Vector3d(0, 0, 0));
        // 100 mm to each centre, plus 1.6 inradii of a d20.
        assertThat(pose.distance()).isCloseTo(462.93, within(0.01));
    }

    @Test
    void withNoDiceTheCloseUpIsTheWholeTray() {
        assertThat(FOOTPRINT.resting(List.of(), 1.6)).isEqualTo(FOOTPRINT.wholeTray(1.6));
    }

    @Test
    void thePoseIsAnOrbitAroundTheTarget() {
        CameraPose pose = new CameraPose(new Vector3d(10, 0, 0), 90, 30, 100);

        DiceScene.Camera camera = pose.camera();

        assertThat(camera.target()).isEqualTo(new Vector3d(10, 0, 0));
        assertThat(camera.eye().x()).isCloseTo(10 + 86.6025, within(1e-3));
        assertThat(camera.eye().y()).isCloseTo(50, within(1e-9));
        assertThat(camera.eye().z()).isCloseTo(0, within(1e-9));
    }
}
