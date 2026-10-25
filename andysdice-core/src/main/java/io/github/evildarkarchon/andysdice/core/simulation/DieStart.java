package io.github.evildarkarchon.andysdice.core.simulation;

import java.util.Objects;

/**
 * How one Die leaves the hand: its starting pose and motion.
 *
 * @param pose where it starts, in cm
 * @param linearVelocity its velocity, in cm/s
 * @param angularVelocity its spin in the world frame, in rad/s
 */
public record DieStart(Pose pose, Vector3 linearVelocity, Vector3 angularVelocity) {

    /**
     * Checks every part is present.
     *
     * @throws NullPointerException if a part is {@code null}
     */
    public DieStart {
        Objects.requireNonNull(pose, "pose");
        Objects.requireNonNull(linearVelocity, "linearVelocity");
        Objects.requireNonNull(angularVelocity, "angularVelocity");
    }
}
