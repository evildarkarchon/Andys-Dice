package io.github.evildarkarchon.andysdice.physics;

import io.github.evildarkarchon.andysdice.core.simulation.DieBody;
import io.github.evildarkarchon.andysdice.core.simulation.Orientation;
import io.github.evildarkarchon.andysdice.core.simulation.Vector3;
import java.util.ArrayList;
import java.util.List;

/**
 * The walking skeleton's hand-made cube (spec slice S3): a d6-sized solid built here rather than from
 * {@code core}'s Die Kind solids, so this ticket doesn't wait on them. Inradius 8 mm (Appendix A), edges
 * rounded to 0.75 mm (ADR-0008), cocked past 4° (ADR-0013).
 */
final class TestCube implements DieBody {

    /** The cube's inradius in cm: half its face-to-face distance. */
    static final float INRADIUS = 0.8f;

    static final TestCube INSTANCE = new TestCube();

    private static final double COCKED_DEGREES = 4;

    private static final List<Vector3> FACE_NORMALS = List.of(
            new Vector3(1, 0, 0), new Vector3(-1, 0, 0), new Vector3(0, 1, 0),
            new Vector3(0, -1, 0), new Vector3(0, 0, 1), new Vector3(0, 0, -1));

    private final List<Vector3> vertices;

    private TestCube() {
        List<Vector3> corners = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            corners.add(new Vector3((i & 1) == 0 ? -INRADIUS : INRADIUS, (i & 2) == 0 ? -INRADIUS : INRADIUS,
                    (i & 4) == 0 ? -INRADIUS : INRADIUS));
        }
        this.vertices = List.copyOf(corners);
    }

    @Override
    public List<Vector3> vertices() {
        return vertices;
    }

    @Override
    public float edgeRadius() {
        return 0.075f;
    }

    @Override
    public boolean isCocked(Orientation orientation) {
        return tiltDegrees(orientation) > COCKED_DEGREES;
    }

    /**
     * Returns how far the face most nearly up is from level.
     *
     * @param orientation the cube's orientation
     * @return the angle between that face's normal and straight up, in degrees
     */
    static double tiltDegrees(Orientation orientation) {
        double up = -1;
        for (Vector3 n : FACE_NORMALS) {
            up = Math.max(up, orientation.rotate(n).y());
        }
        return Math.toDegrees(Math.acos(Math.clamp(up, -1, 1)));
    }
}
