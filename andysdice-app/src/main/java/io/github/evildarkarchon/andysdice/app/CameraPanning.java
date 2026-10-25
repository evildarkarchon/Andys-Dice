package io.github.evildarkarchon.andysdice.app;

import io.github.evildarkarchon.andysdice.core.simulation.Vector3;
import io.github.evildarkarchon.andysdice.render.DiceScene;
import io.github.evildarkarchon.andysdice.sound.ScreenProjection;

/**
 * Where a contact appears across the screen, for panning its sound (ADR-0012 "Output"): an immutable snapshot of one
 * camera's view, made each time the Tray is drawn from a new camera, so an orbited camera still pans correctly.
 * It matches the renderer's view: a perspective of the camera's vertical field of view and the view's aspect,
 * looking from the eye at the target with the world's +y up.
 */
final class CameraPanning implements ScreenProjection {

    /** The renderer's world is in mm; contact points are in the simulation's cm. */
    private static final double MM_PER_CM = 10;

    private final double eyeX;
    private final double eyeY;
    private final double eyeZ;
    // The view's forward and right unit vectors, in world space.
    private final double fx;
    private final double fy;
    private final double fz;
    private final double rx;
    private final double rz;
    private final double halfWidth;

    private CameraPanning(DiceScene.Camera camera, double aspect) {
        eyeX = camera.eye().x();
        eyeY = camera.eye().y();
        eyeZ = camera.eye().z();
        double dx = camera.target().x() - eyeX;
        double dy = camera.target().y() - eyeY;
        double dz = camera.target().z() - eyeZ;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        fx = dx / length;
        fy = dy / length;
        fz = dz / length;
        // right = forward × up, with up = +y, as the renderer's lookAt has it; it never tilts out of the floor's plane.
        double r = Math.hypot(fz, fx);
        rx = -fz / r;
        rz = fx / r;
        halfWidth = Math.tan(Math.toRadians(camera.fovYDegrees()) / 2) * aspect;
    }

    /**
     * Snapshots a camera's view for panning.
     *
     * @param camera the camera the Tray is drawn from now; its target is never straight above or below its eye
     * @param aspect the view's width over its height
     * @return the projection
     * @throws IllegalArgumentException if the aspect isn't positive
     */
    static CameraPanning of(DiceScene.Camera camera, double aspect) {
        if (!(aspect > 0) || !Double.isFinite(aspect)) {
            throw new IllegalArgumentException("A view's aspect is positive: " + aspect);
        }
        return new CameraPanning(camera, aspect);
    }

    @Override
    public double screenX(Vector3 point) {
        double dx = point.x() * MM_PER_CM - eyeX;
        double dy = point.y() * MM_PER_CM - eyeY;
        double dz = point.z() * MM_PER_CM - eyeZ;
        double across = dx * rx + dz * rz;
        double depth = dx * fx + dy * fy + dz * fz;
        if (depth <= 0) {
            // Behind the eye (never a contact on the Tray in practice): hard to its side.
            return across >= 0 ? 1 : -1;
        }
        return Math.clamp(across / (depth * halfWidth), -1.0, 1.0);
    }
}
