package io.github.evildarkarchon.andysdice.core.playback;

import io.github.evildarkarchon.andysdice.core.simulation.Orientation;
import io.github.evildarkarchon.andysdice.core.simulation.Pose;
import io.github.evildarkarchon.andysdice.core.simulation.Recording;
import io.github.evildarkarchon.andysdice.core.simulation.Vector3;

/**
 * Reads a {@link Recording} between its samples, as playback draws it at whatever time the playback clock
 * shows (ADR-0014): positions are interpolated linearly and orientations by spherical linear interpolation
 * (slerp), so a Die turning up to 90° between two 120 Hz samples still turns at an even rate at any frame rate
 * and at ×4.
 *
 * <p>Pure and stateless; safe on any thread.
 */
public final class Interpolation {

    /** Above this cosine the two orientations are so close that a normalised lerp is exact enough and stable. */
    private static final double NEARLY_PARALLEL = 0.9995;

    private Interpolation() {}

    /**
     * Returns a Die's pose at a moment of its Recording.
     *
     * @param recording the Recording
     * @param die the Die's index in the Recording
     * @param time seconds after the Release; before 0 gives the Release pose, past the last sample the pose the
     *     Die stopped in
     * @return the interpolated pose, in the Recording's units (cm); the orientation is unit-length
     * @throws IndexOutOfBoundsException if the Die isn't one the Recording holds
     */
    public static Pose pose(Recording recording, int die, double time) {
        int last = recording.samples() - 1;
        double at = Math.clamp(time * Recording.SAMPLE_RATE_HZ, 0, last);
        int sample = (int) Math.floor(at);
        if (sample >= last) {
            return recording.pose(die, last);
        }
        double t = at - sample;
        Pose a = recording.pose(die, sample);
        Pose b = recording.pose(die, sample + 1);
        return new Pose(lerp(a.position(), b.position(), t), slerp(a.orientation(), b.orientation(), t));
    }

    /**
     * Interpolates linearly between two positions.
     *
     * @param a the position at {@code t = 0}
     * @param b the position at {@code t = 1}
     * @param t how far from {@code a} to {@code b}
     * @return the point between them
     */
    static Vector3 lerp(Vector3 a, Vector3 b, double t) {
        return new Vector3((float) (a.x() + (b.x() - a.x()) * t), (float) (a.y() + (b.y() - a.y()) * t),
                (float) (a.z() + (b.z() - a.z()) * t));
    }

    /**
     * Interpolates between two rotations along the shorter arc, at an even angular rate.
     *
     * @param a the rotation at {@code t = 0}
     * @param b the rotation at {@code t = 1}
     * @param t how far from {@code a} to {@code b}
     * @return the unit rotation between them
     */
    static Orientation slerp(Orientation a, Orientation b, double t) {
        double bx = b.x();
        double by = b.y();
        double bz = b.z();
        double bw = b.w();
        double dot = a.x() * bx + a.y() * by + a.z() * bz + a.w() * bw;
        // q and -q are the same rotation; flipping b to the same hemisphere as a takes the short way round,
        // which is the way the Die actually turned between two samples 1/120 s apart.
        if (dot < 0) {
            dot = -dot;
            bx = -bx;
            by = -by;
            bz = -bz;
            bw = -bw;
        }
        double wa;
        double wb;
        if (dot > NEARLY_PARALLEL) {
            wa = 1 - t;
            wb = t;
        } else {
            double theta = Math.acos(dot);
            double sin = Math.sin(theta);
            wa = Math.sin((1 - t) * theta) / sin;
            wb = Math.sin(t * theta) / sin;
        }
        double x = wa * a.x() + wb * bx;
        double y = wa * a.y() + wb * by;
        double z = wa * a.z() + wb * bz;
        double w = wa * a.w() + wb * bw;
        double norm = Math.sqrt(x * x + y * y + z * z + w * w);
        return new Orientation((float) (x / norm), (float) (y / norm), (float) (z / norm), (float) (w / norm));
    }
}
