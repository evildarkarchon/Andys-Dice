package io.github.evildarkarchon.andysdice.core.simulation;

import java.util.Objects;

/**
 * Where a Die is and how it is turned: the world position of its centre of mass, in centimetres, and the
 * rotation from its model frame to the world.
 *
 * @param position the centre's position, in cm
 * @param orientation the rotation from the model frame to the world
 */
public record Pose(Vector3 position, Orientation orientation) {

    /**
     * Checks that both parts are present.
     *
     * @throws NullPointerException if either part is {@code null}
     */
    public Pose {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(orientation, "orientation");
    }
}
