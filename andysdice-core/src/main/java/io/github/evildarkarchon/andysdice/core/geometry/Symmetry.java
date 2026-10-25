package io.github.evildarkarchon.andysdice.core.geometry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.joml.Matrix3d;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * One of a solid's own symmetries: a proper rotation of the model frame that maps the solid onto itself, and
 * the permutation of faces it makes. A Numbering Rotation is one of these (ADR-0001).
 *
 * <p>Only proper rotations count. A reflection would also map the solid onto itself, but it would mirror the
 * numbering, which no real die can be turned into.
 *
 * @param rotation the rotation, in the solid's z-up model frame; a unit quaternion with {@code w >= 0}
 * @param faceMap  {@code faceMap.get(f)} is the face onto whose normal the rotation carries face {@code f}'s
 *                 normal; a permutation of {@code 0..faces-1}
 */
public record Symmetry(Quaterniondc rotation, List<Integer> faceMap) {

    /**
     * How far apart, as unit vectors, a rotated face normal and its image may be. The face tables carry four
     * decimals, so a true symmetry misses by about 1e-4; distinct faces of every Die Kind are tens of degrees
     * apart, so nothing near this tolerance is ambiguous.
     */
    private static final double NORMAL_TOLERANCE = 2e-3;

    /** Copies the rotation and face map, so that the symmetry cannot change under its group. */
    public Symmetry {
        rotation = new Quaterniond(rotation);
        faceMap = List.copyOf(faceMap);
    }

    /**
     * Where the symmetry takes a face.
     *
     * @param face a face index of the solid
     * @return the index of the face onto which the rotation carries {@code face}
     * @throws IndexOutOfBoundsException if {@code face} is out of range
     */
    public int face(int face) {
        return faceMap.get(face);
    }

    /**
     * Which face the symmetry brings onto a face: the inverse of {@link #face(int)}.
     *
     * @param face a face index of the solid
     * @return the index of the face the rotation carries onto {@code face}
     * @throws IndexOutOfBoundsException if {@code face} is out of range
     */
    public int preimage(int face) {
        Objects.checkIndex(face, faceMap.size());
        return faceMap.indexOf(face);
    }

    /**
     * Derives a solid's rotation group from its face normals: every proper rotation that carries the set of
     * face normals onto itself. Since every face plane of a {@link Solid} lies at the same inradius, such a
     * rotation maps the whole solid onto itself.
     *
     * <p>A rotation is fixed by where it sends two non-parallel vectors, so the search fixes two reference
     * normals and tries every ordered pair of faces at the same angle as their images, keeping the rotations that
     * carry every other normal onto a normal too.
     *
     * @param solid the solid
     * @return the group, identity first, then by increasing rotation angle, then by face map; an asymmetric
     *         solid gets only the identity
     */
    public static List<Symmetry> groupOf(Solid solid) {
        List<Vector3dc> n = solid.faces().stream().map(Solid.Face::normal).toList();
        Vector3dc a = n.getFirst();
        Vector3dc b = n.stream()
                .filter(v -> Math.abs(v.dot(a)) < 0.9)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("every face normal is parallel to the first"));
        double cosAB = a.dot(b);
        Matrix3d fromFrame = frame(a, b).transpose();

        // Each rotation sends (a, b) to exactly one pair, so each is found once; keying by face map is a guard
        // against the tolerance ever admitting two near-copies of one symmetry.
        Map<List<Integer>, Symmetry> found = new LinkedHashMap<>();
        for (Vector3dc ia : n) {
            for (Vector3dc ib : n) {
                if (ia == ib || Math.abs(ia.dot(ib) - cosAB) > NORMAL_TOLERANCE) {
                    continue;
                }
                Matrix3d r = frame(ia, ib).mul(fromFrame);
                List<Integer> map = faceMap(r, n);
                if (map != null) {
                    Quaterniond q = r.getNormalizedRotation(new Quaterniond()).normalize();
                    // q and -q are the same rotation; pick w >= 0 so that angle() is in [0, pi].
                    if (q.w < 0) {
                        q.mul(-1);
                    }
                    found.putIfAbsent(map, new Symmetry(q, map));
                }
            }
        }
        List<Symmetry> group = new ArrayList<>(found.values());
        // The angles of these groups are multiples of 2pi/n for n <= 5, at least 0.4 rad apart, so rounding to
        // 0.01 rad removes the tables' 1e-4 noise without merging distinct angles.
        group.sort(Comparator
                .comparingLong((Symmetry s) -> Math.round(s.rotation().angle() * 100))
                .thenComparing(Symmetry::faceMap, Symmetry::compareLexicographically));
        return List.copyOf(group);
    }

    /**
     * The orthonormal frame whose first axis is {@code a} and whose second lies in the plane of {@code a} and
     * {@code b}, as the columns of a matrix.
     */
    private static Matrix3d frame(Vector3dc a, Vector3dc b) {
        Vector3d e1 = a.normalize(new Vector3d());
        Vector3d e2 = b.sub(new Vector3d(e1).mul(e1.dot(b)), new Vector3d()).normalize();
        Vector3d e3 = e1.cross(e2, new Vector3d());
        return new Matrix3d(e1, e2, e3);
    }

    /**
     * Where {@code r} sends each normal, as indices into {@code normals}, or null if some normal lands on no
     * normal or two land on the same one.
     */
    private static List<Integer> faceMap(Matrix3d r, List<Vector3dc> normals) {
        List<Integer> map = new ArrayList<>(normals.size());
        boolean[] hit = new boolean[normals.size()];
        for (Vector3dc v : normals) {
            Vector3d moved = r.transform(v, new Vector3d());
            int image = -1;
            for (int j = 0; j < normals.size(); j++) {
                if (moved.distance(normals.get(j)) < NORMAL_TOLERANCE) {
                    image = j;
                    break;
                }
            }
            if (image < 0 || hit[image]) {
                return null;
            }
            hit[image] = true;
            map.add(image);
        }
        return map;
    }

    /** Orders two face maps of the same length by their first differing entry. */
    private static int compareLexicographically(List<Integer> x, List<Integer> y) {
        for (int i = 0; i < x.size(); i++) {
            int c = Integer.compare(x.get(i), y.get(i));
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }
}
