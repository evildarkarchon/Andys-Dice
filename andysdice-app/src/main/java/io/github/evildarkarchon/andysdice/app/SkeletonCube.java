package io.github.evildarkarchon.andysdice.app;

import io.github.evildarkarchon.andysdice.core.geometry.DieKind;
import io.github.evildarkarchon.andysdice.core.geometry.UpFace;
import io.github.evildarkarchon.andysdice.core.simulation.DieBody;
import io.github.evildarkarchon.andysdice.core.simulation.Orientation;
import io.github.evildarkarchon.andysdice.core.simulation.Vector3;
import java.util.ArrayList;
import java.util.List;
import org.joml.Quaterniond;

/**
 * The walking skeleton's hand-made cube (spec slice S3): eight corners at the d6's inradius, with its edges
 * rounded to the 0.75 mm edge radius (ADR-0008). Its faces line up with the d6's face table, so reading its up
 * face, its cocked test and its Numbering Rotation are the d6's own ({@link UpFace}, 4°).
 *
 * <p>Ticket #38 replaces it with each Die Kind's own solid.
 */
final class SkeletonCube implements DieBody {

    /** The cube's inradius in cm: the d6's, half its face-to-face distance (spec Appendix A). */
    static final float INRADIUS_CM = (float) (DieKind.D6.inradius() / 10);

    /** The one cube; it is immutable. */
    static final SkeletonCube INSTANCE = new SkeletonCube();

    private final List<Vector3> vertices;

    private SkeletonCube() {
        List<Vector3> corners = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            corners.add(new Vector3((i & 1) == 0 ? -INRADIUS_CM : INRADIUS_CM,
                    (i & 2) == 0 ? -INRADIUS_CM : INRADIUS_CM, (i & 4) == 0 ? -INRADIUS_CM : INRADIUS_CM));
        }
        vertices = List.copyOf(corners);
    }

    @Override
    public List<Vector3> vertices() {
        return vertices;
    }

    @Override
    public float edgeRadius() {
        return (float) (DieKind.D6.edgeRadius() / 10);
    }

    @Override
    public boolean isCocked(Orientation orientation) {
        return upFace(orientation).cocked();
    }

    /**
     * Reads the cube's up face as the d6's.
     *
     * @param orientation the cube's orientation, from its model frame to the world
     * @return the d6 face up, with its tilt
     */
    static UpFace upFace(Orientation orientation) {
        return UpFace.read(DieKind.D6, quaternion(orientation));
    }

    /**
     * Converts a simulator orientation to JOML's double quaternion, as {@code core}'s geometry takes it.
     *
     * @param orientation the orientation
     * @return the same rotation
     */
    static Quaterniond quaternion(Orientation orientation) {
        return new Quaterniond(orientation.x(), orientation.y(), orientation.z(), orientation.w());
    }
}
