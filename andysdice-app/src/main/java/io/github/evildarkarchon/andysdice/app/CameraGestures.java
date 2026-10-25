package io.github.evildarkarchon.andysdice.app;

import io.github.evildarkarchon.andysdice.render.camera.TrayCamera;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import javafx.scene.Node;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;

/**
 * The Player's hold on the camera (spec §3.2 "Camera"): drag on the Tray to orbit and turn the wheel to zoom, at
 * any time, mid-Throw included; double-click the resting Tray to reset the camera at once. Orbit and zoom have
 * no keyboard equivalent: the automatic camera is complete without them (spec §3.2 "Keyboard-only floor").
 *
 * <p>The rates are the tray prototype's, and tunables.
 *
 * <p>Thread contract: the JavaFX Application Thread, like the node and the camera.
 */
final class CameraGestures {

    /** How far a drag across the Tray swings the camera round, in degrees per pixel. */
    static final double YAW_DEGREES_PER_PIXEL = Math.toDegrees(0.01);

    /** How far a drag down the Tray raises the camera, in degrees per pixel. */
    static final double PITCH_DEGREES_PER_PIXEL = Math.toDegrees(0.006);

    /** How much one wheel notch moves the camera in (or out), as a factor on its distance. */
    private static final double ZOOM_PER_NOTCH = 1.1;

    /** The scroll delta of one wheel notch, in pixels, as JavaFX reports a mouse wheel on Windows. */
    private static final double NOTCH_PIXELS = 40;

    private double lastX;
    private double lastY;

    private CameraGestures() {}

    /**
     * Lets the Player orbit, zoom and reset a camera from a node.
     *
     * @param tray the node the Tray is drawn in
     * @param camera the camera to move
     * @param resting tells whether the Tray is resting (no Roll under way), when a double-click resets the camera
     * @param redraw draws the Tray again from the camera; called after each change
     */
    static void attach(Node tray, TrayCamera camera, BooleanSupplier resting, Runnable redraw) {
        Objects.requireNonNull(camera, "camera");
        Objects.requireNonNull(resting, "resting");
        Objects.requireNonNull(redraw, "redraw");
        CameraGestures gestures = new CameraGestures();
        tray.addEventHandler(MouseEvent.MOUSE_PRESSED, event -> {
            if (event.getButton() == MouseButton.PRIMARY) {
                gestures.lastX = event.getSceneX();
                gestures.lastY = event.getSceneY();
            }
        });
        tray.addEventHandler(MouseEvent.MOUSE_DRAGGED, event -> {
            if (!event.isPrimaryButtonDown()) {
                return;
            }
            double dx = event.getSceneX() - gestures.lastX;
            double dy = event.getSceneY() - gestures.lastY;
            gestures.lastX = event.getSceneX();
            gestures.lastY = event.getSceneY();
            camera.orbit(dx * YAW_DEGREES_PER_PIXEL, dy * PITCH_DEGREES_PER_PIXEL);
            redraw.run();
        });
        tray.addEventHandler(ScrollEvent.SCROLL, event -> {
            if (event.getDeltaY() == 0) {
                return;
            }
            // Turning the wheel away (a positive delta) moves the camera in.
            camera.zoom(Math.pow(ZOOM_PER_NOTCH, -event.getDeltaY() / NOTCH_PIXELS));
            redraw.run();
        });
        tray.addEventHandler(MouseEvent.MOUSE_CLICKED, event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2
                    && resting.getAsBoolean()) {
                camera.reset();
                redraw.run();
            }
        });
    }
}
