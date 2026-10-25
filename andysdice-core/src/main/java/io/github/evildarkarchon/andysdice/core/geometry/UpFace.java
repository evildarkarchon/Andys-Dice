package io.github.evildarkarchon.andysdice.core.geometry;

import java.util.Objects;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * What a Die's pose reads as: the face that is up, and how far from flat the Die lies (real-dice-geometry §5).
 *
 * <p>The world is y-up (physics and rendering both use it), while the face tables are in the research's z-up
 * model frame; the pose's orientation rotates the model frame into the world. For every Die Kind but the d4 the
 * face read is the one whose world outward normal is nearest straight up. The d4 is read from the top: its face
 * read is the one whose normal is nearest straight down, which is named for the corner then pointing up, and its
 * reading normal is that face's normal negated.
 *
 * @param kind           the Die Kind read
 * @param face           index of the face read, into {@code kind.solid().faces()}
 * @param readingNormal  the world-space unit direction that points straight up when the Die lies flat: the up
 *                       face's outward normal, or on the d4 the down face's outward normal negated
 * @param tiltDegrees    the angle between {@code readingNormal} and straight up, from 0 to 180
 */
public record UpFace(DieKind kind, int face, Vector3dc readingNormal, double tiltDegrees) {

    /**
     * A Die whose reading normal is more than this many degrees from vertical is cocked (spec §4: a contract,
     * ADR-0013). Kept tight because a d20's up face becomes ambiguous near 21°.
     */
    public static final double COCKED_TOLERANCE_DEGREES = 4.0;

    /** Validates the reading and copies the normal so that it cannot change under the record. */
    public UpFace {
        Objects.requireNonNull(kind, "kind");
        Objects.checkIndex(face, kind.faceCount());
        readingNormal = new Vector3d(readingNormal);
    }

    /**
     * Reads a Die's pose.
     *
     * @param kind        the Die Kind
     * @param orientation the Die's orientation, rotating its z-up model frame into the y-up world; it should be a
     *                    unit quaternion
     * @return the face read, its reading normal and the Die's tilt
     */
    public static UpFace read(DieKind kind, Quaterniondc orientation) {
        // The d4 rests on the face it reads, so the comparison flips sign and so does the reading normal.
        double sign = kind == DieKind.D4 ? -1 : 1;
        int best = -1;
        Vector3d bestNormal = null;
        for (Solid.Face f : kind.solid().faces()) {
            Vector3d n = orientation.transform(f.normal(), new Vector3d()).mul(sign);
            if (bestNormal == null || n.y > bestNormal.y) {
                best = f.index();
                bestNormal = n;
            }
        }
        // atan2 rather than acos(y): acos loses most of its precision next to 0°, just where the tolerance sits.
        double horizontal = Math.hypot(bestNormal.x, bestNormal.z);
        double tilt = Math.toDegrees(Math.atan2(horizontal, bestNormal.y));
        return new UpFace(kind, best, bestNormal.normalize(), tilt);
    }

    /**
     * The Numeral the face table puts on the face read; on the d4, the corner pointing up. This is the solid's
     * fixed numbering, before any Numbering Rotation.
     *
     * @return {@code kind().numeral(face())}
     */
    public String numeral() {
        return kind.numeral(face);
    }

    /**
     * Whether the Die is cocked: its reading normal more than {@link #COCKED_TOLERANCE_DEGREES} from vertical, so
     * it has no Face Value.
     *
     * @return {@code tiltDegrees() > COCKED_TOLERANCE_DEGREES}
     */
    public boolean cocked() {
        return tiltDegrees > COCKED_TOLERANCE_DEGREES;
    }
}
