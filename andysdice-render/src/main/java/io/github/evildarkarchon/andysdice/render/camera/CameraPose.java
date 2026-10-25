package io.github.evildarkarchon.andysdice.render.camera;

import io.github.evildarkarchon.andysdice.render.DiceScene;
import java.util.Objects;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Where the camera is, as an orbit around the point it looks at: world y-up, millimetres. Yaw 0 looks from the
 * Tray's near end (the eye on the -z side, where Dice are released from) towards +z; positive yaw swings the
 * eye towards +x. Pitch is the eye's elevation above the target's horizontal plane: 90° would be straight down.
 *
 * @param target the point looked at, in mm
 * @param yawDegrees the swing around the vertical through the target, in degrees; any value
 * @param pitchDegrees the elevation, in degrees
 * @param distance how far the eye is from the target, in mm
 */
public record CameraPose(Vector3dc target, double yawDegrees, double pitchDegrees, double distance) {

    /** The lens every framing assumes and every pose is drawn with: the tray prototype's 32° vertical view. */
    public static final double FOV_Y_DEGREES = 32;

    /**
     * Checks the parts and copies the target, so the pose can't change after it is made.
     *
     * @throws NullPointerException if the target is {@code null}
     * @throws IllegalArgumentException if an angle isn't finite or the distance isn't positive and finite
     */
    public CameraPose {
        target = new Vector3d(Objects.requireNonNull(target, "target"));
        if (!Double.isFinite(yawDegrees) || !Double.isFinite(pitchDegrees)) {
            throw new IllegalArgumentException("A camera's angles must be finite: " + yawDegrees + ", "
                    + pitchDegrees);
        }
        if (!(distance > 0) || !Double.isFinite(distance)) {
            throw new IllegalArgumentException("A camera's distance must be positive and finite: " + distance);
        }
    }

    /**
     * Returns the eye's position.
     *
     * @return the point {@link #distance()} from the target along the yaw and pitch, in mm
     */
    public Vector3d eye() {
        double yaw = Math.toRadians(yawDegrees);
        double pitch = Math.toRadians(pitchDegrees);
        return new Vector3d(Math.cos(pitch) * Math.sin(yaw), Math.sin(pitch), -Math.cos(pitch) * Math.cos(yaw))
                .mul(distance).add(target);
    }

    /**
     * Returns the pose as the renderer takes it.
     *
     * @return a camera at {@link #eye()} looking at the target through the {@value #FOV_Y_DEGREES}° lens
     */
    public DiceScene.Camera camera() {
        return new DiceScene.Camera(eye(), target, FOV_Y_DEGREES);
    }
}
