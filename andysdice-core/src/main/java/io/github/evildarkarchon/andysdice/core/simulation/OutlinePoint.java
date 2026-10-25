package io.github.evildarkarchon.andysdice.core.simulation;

/**
 * A corner of a Tray's outline on the floor plane, in centimetres.
 *
 * @param x the world x coordinate
 * @param z the world z coordinate; the near (camera-side) end of the Tray is towards -z
 */
public record OutlinePoint(float x, float z) {

    /**
     * Checks the point is a real position.
     *
     * @throws IllegalArgumentException if a coordinate is NaN or infinite
     */
    public OutlinePoint {
        if (!Float.isFinite(x) || !Float.isFinite(z)) {
            throw new IllegalArgumentException("An outline point must be finite: (" + x + ", " + z + ")");
        }
    }
}
