package io.github.evildarkarchon.andysdice.core.simulation;

/**
 * A rotation from a Die's model frame to the world, as a unit quaternion {@code (x, y, z, w)}.
 *
 * <p>The components are stored as given; producers keep them unit-length (a simulator's output always is, to
 * float precision), and {@link #rotate(Vector3)} assumes it.
 *
 * @param x the quaternion's x component
 * @param y the quaternion's y component
 * @param z the quaternion's z component
 * @param w the quaternion's scalar component
 */
public record Orientation(float x, float y, float z, float w) {

    /** No rotation: the model frame is the world frame. */
    public static final Orientation IDENTITY = new Orientation(0, 0, 0, 1);

    /**
     * Returns the rotation by {@code angle} radians about {@code axis}, counter-clockwise looking down the axis.
     *
     * @param axis the axis to turn about; need not be unit-length, but must not be zero
     * @param angle the angle in radians
     * @return the rotation
     * @throws IllegalArgumentException if {@code axis} is zero
     */
    public static Orientation about(Vector3 axis, float angle) {
        float length = axis.length();
        if (!(length > 0)) {
            throw new IllegalArgumentException("A rotation axis must not be zero: " + axis);
        }
        float s = (float) Math.sin(angle / 2) / length;
        return new Orientation(axis.x() * s, axis.y() * s, axis.z() * s, (float) Math.cos(angle / 2));
    }

    /**
     * Returns the rotation that applies {@code other} first and then this one.
     *
     * @param other the rotation applied first
     * @return the composed rotation {@code this * other}
     */
    public Orientation times(Orientation other) {
        return new Orientation(
                w * other.x + x * other.w + y * other.z - z * other.y,
                w * other.y - x * other.z + y * other.w + z * other.x,
                w * other.z + x * other.y - y * other.x + z * other.w,
                w * other.w - x * other.x - y * other.y - z * other.z);
    }

    /**
     * Rotates a vector from the model frame into the world.
     *
     * @param v the vector in the model frame
     * @return the same vector in the world frame
     */
    public Vector3 rotate(Vector3 v) {
        // v' = v + 2w (q x v) + 2 q x (q x v), with q the vector part.
        float tx = 2 * (y * v.z() - z * v.y());
        float ty = 2 * (z * v.x() - x * v.z());
        float tz = 2 * (x * v.y() - y * v.x());
        return new Vector3(
                v.x() + w * tx + (y * tz - z * ty),
                v.y() + w * ty + (z * tx - x * tz),
                v.z() + w * tz + (x * ty - y * tx));
    }

    /**
     * Returns the quaternion's norm, which is 1 for a valid rotation.
     *
     * @return {@code sqrt(x² + y² + z² + w²)}
     */
    public float norm() {
        return (float) Math.sqrt(x * x + y * y + z * z + w * w);
    }
}
