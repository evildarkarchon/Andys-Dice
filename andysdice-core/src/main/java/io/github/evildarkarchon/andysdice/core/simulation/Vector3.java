package io.github.evildarkarchon.andysdice.core.simulation;

/**
 * A vector in the simulator's world, y up and the near (camera-side) end of the Tray towards -z. Positions
 * and lengths are in centimetres (ADR-0003), velocities in cm/s and angular velocities in rad/s.
 *
 * @param x the x component
 * @param y the y component (up)
 * @param z the z component
 */
public record Vector3(float x, float y, float z) {

    /** The zero vector. */
    public static final Vector3 ZERO = new Vector3(0, 0, 0);

    /** The unit vector straight up. */
    public static final Vector3 UP = new Vector3(0, 1, 0);

    /**
     * Returns this vector's Euclidean length.
     *
     * @return the length, in this vector's unit
     */
    public float length() {
        return (float) Math.sqrt(x * x + y * y + z * z);
    }

    /**
     * Tests whether every component is a finite number.
     *
     * @return {@code true} if no component is NaN or infinite
     */
    public boolean isFinite() {
        return Float.isFinite(x) && Float.isFinite(y) && Float.isFinite(z);
    }
}
