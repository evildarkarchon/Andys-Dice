package io.github.evildarkarchon.andysdice.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.github.evildarkarchon.andysdice.core.playback.PlaybackClock;
import io.github.evildarkarchon.andysdice.core.simulation.TrayGeometry;
import io.github.evildarkarchon.andysdice.render.camera.CameraPose;
import io.github.evildarkarchon.andysdice.render.camera.Framing;
import io.github.evildarkarchon.andysdice.render.camera.TrayCamera;
import javafx.event.Event;
import javafx.event.EventType;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.Pane;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The Player's hold on the camera, on JavaFX's headless platform with events fired at a stand-in Tray node (no
 * GL): a drag orbits, the wheel zooms, at any time, and a double-click on the resting Tray resets the camera.
 */
class CameraGesturesTest {

    private static final Framing FOOTPRINT = Framing.of(TrayGeometry.rectangle(15, 10, 3));

    private Pane tray;
    private TrayCamera camera;
    private boolean resting;
    private int redraws;

    @BeforeAll
    static void startFx() {
        HeadlessUiTest.startToolkit();
    }

    @BeforeEach
    void setUp() throws Exception {
        HeadlessUiTest.onFx(() -> {
            tray = new Pane();
            camera = new TrayCamera(new PlaybackClock(), FOOTPRINT, () -> false);
            resting = true;
            CameraGestures.attach(tray, camera, () -> resting, () -> redraws++);
        });
    }

    private static MouseEvent mouse(EventType<MouseEvent> type, double x, double y, int clicks) {
        return new MouseEvent(type, x, y, x, y, MouseButton.PRIMARY, clicks, false, false, false, false, true,
                false, false, false, false, true, null);
    }

    private static ScrollEvent wheel(double deltaY) {
        return new ScrollEvent(ScrollEvent.SCROLL, 10, 10, 10, 10, false, false, false, false, false, false, 0,
                deltaY, 0, deltaY, ScrollEvent.HorizontalTextScrollUnits.NONE, 0,
                ScrollEvent.VerticalTextScrollUnits.NONE, 0, 0, null);
    }

    private void fire(Event event) throws Exception {
        HeadlessUiTest.onFx(() -> Event.fireEvent(tray, event));
    }

    private CameraPose pose() throws Exception {
        return HeadlessUiTest.fromFx(camera::pose);
    }

    @Test
    void draggingAcrossTheTraySwingsTheCameraRoundAndDraggingDownRaisesIt() throws Exception {
        fire(mouse(MouseEvent.MOUSE_PRESSED, 100, 100, 1));
        fire(mouse(MouseEvent.MOUSE_DRAGGED, 150, 100, 1));
        fire(mouse(MouseEvent.MOUSE_DRAGGED, 200, 120, 1));

        CameraPose pose = pose();
        assertThat(pose.yawDegrees()).isCloseTo(100 * CameraGestures.YAW_DEGREES_PER_PIXEL, within(1e-9));
        assertThat(pose.pitchDegrees()).isCloseTo(
                Framing.TRAY_PITCH_DEGREES + 20 * CameraGestures.PITCH_DEGREES_PER_PIXEL, within(1e-9));
        assertThat(redraws).isEqualTo(2);
    }

    @Test
    void theWheelZoomsInWhenTurnedAwayAndOutWhenTurnedBack() throws Exception {
        double automatic = pose().distance();

        fire(wheel(40));
        assertThat(pose().distance()).isCloseTo(automatic / 1.1, within(1e-6));

        fire(wheel(-80));
        assertThat(pose().distance()).isCloseTo(automatic * 1.1, within(1e-6));
        assertThat(redraws).isEqualTo(2);
    }

    @Test
    void theCameraCanBeOrbitedWhileTheDiceAreMoving() throws Exception {
        resting = false;

        fire(mouse(MouseEvent.MOUSE_PRESSED, 0, 0, 1));
        fire(mouse(MouseEvent.MOUSE_DRAGGED, 10, 0, 1));
        fire(wheel(40));

        assertThat(HeadlessUiTest.fromFx(camera::adjusted)).isTrue();
    }

    @Test
    void aDoubleClickOnTheRestingTrayResetsTheCameraAtOnce() throws Exception {
        fire(mouse(MouseEvent.MOUSE_PRESSED, 0, 0, 1));
        fire(mouse(MouseEvent.MOUSE_DRAGGED, 30, 0, 1));
        fire(wheel(40));

        fire(mouse(MouseEvent.MOUSE_CLICKED, 30, 0, 2));

        assertThat(HeadlessUiTest.fromFx(camera::adjusted)).isFalse();
        assertThat(redraws).isEqualTo(3);
    }

    @Test
    void aDoubleClickWhileTheDiceAreMovingDoesNothing() throws Exception {
        fire(mouse(MouseEvent.MOUSE_PRESSED, 0, 0, 1));
        fire(mouse(MouseEvent.MOUSE_DRAGGED, 30, 0, 1));
        resting = false;

        fire(mouse(MouseEvent.MOUSE_CLICKED, 30, 0, 2));

        assertThat(HeadlessUiTest.fromFx(camera::adjusted)).isTrue();
    }
}
