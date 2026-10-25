package io.github.evildarkarchon.andysdice.render.mesh;

import io.github.evildarkarchon.andysdice.core.geometry.DieKind;
import io.github.evildarkarchon.andysdice.core.geometry.Solid;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import org.joml.Vector3f;

/**
 * The triangle mesh a Die is drawn with: its {@code core} solid with every edge and corner rounded (spec §4,
 * ADR-0008). Pure Java, no GL; the renderer uploads it as it is.
 *
 * <p>The rounded solid is the Minkowski sum of a <em>kernel</em>, the same face table's solid at
 * {@code inradius - r}, and a sphere of radius {@code r}: flat faces (the kernel's faces pushed out by
 * {@code r}, so they lie on the sharp solid's face planes), a cylindrical fillet along every kernel edge and a
 * spherical cap at every kernel corner. Jolt's convex radius rounds the sharp solid to the same shape
 * (geometry.md), so what is drawn is what is simulated.
 *
 * <p>Lengths are in mm, in the face tables' z-up model frame; a Die's pose turns it into the y-up world. Every
 * vertex has a position, a unit outward normal and a face tag: the face-table index of the flat face it belongs
 * to, or {@link #NO_FACE} on a fillet or a cap. Faces, fillets and caps keep vertices of their own, so a flat
 * face can later carry its own atlas coordinates; where they meet, the copies agree bit for bit in position and
 * normal, so the surface is closed and smooth with no cracks. Triangles wind counter-clockwise seen from
 * outside.
 *
 * <p>Instances are immutable and safe to share between threads.
 */
public final class DieMesh {

    /** The face tag of a vertex on a fillet or a corner cap rather than a flat face. */
    public static final int NO_FACE = -1;

    /** Subdivisions across each edge fillet; each corner cap uses as many along each side, so they meet. */
    public static final int FILLET_STEPS = 6;

    private final float[] positions;
    private final float[] normals;
    private final int[] faces;
    private final int[] indices;

    DieMesh(float[] positions, float[] normals, int[] faces, int[] indices) {
        this.positions = positions;
        this.normals = normals;
        this.faces = faces;
        this.indices = indices;
    }

    /**
     * The rounded mesh of a Die Kind, at its inradius and {@link DieKind#edgeRadius()}. Built once per solid and
     * shared: the percentile d10 returns the very mesh the d10 does.
     *
     * @param kind the Die Kind
     * @return its shared mesh
     */
    public static DieMesh of(DieKind kind) {
        return Cache.BY_KIND.get(Objects.requireNonNull(kind, "kind"));
    }

    /**
     * Builds the rounded mesh of any sharp solid: the kernel at {@code solid.inradius() - edgeRadius} grown by a
     * sphere of {@code edgeRadius}, with {@link #FILLET_STEPS} subdivisions across each fillet.
     *
     * @param solid the sharp solid; face {@code i} of the mesh is its face {@code i}
     * @param edgeRadius the rounding radius, greater than zero and less than the solid's inradius
     * @return a new mesh
     * @throws IllegalArgumentException if {@code edgeRadius} is out of range
     * @throws IllegalStateException if the kernel solid loses a face (the radius is too large for the solid)
     */
    public static DieMesh rounded(Solid solid, double edgeRadius) {
        if (!(edgeRadius > 0 && edgeRadius < solid.inradius())) {
            throw new IllegalArgumentException("edge radius " + edgeRadius + " is not within (0, "
                    + solid.inradius() + ")");
        }
        return RoundedMeshBuilder.build(solid, edgeRadius, FILLET_STEPS);
    }

    /** @return the number of vertices */
    public int vertexCount() {
        return faces.length;
    }

    /** @return the number of triangles */
    public int triangleCount() {
        return indices.length / 3;
    }

    /**
     * A vertex's position.
     *
     * @param vertex the vertex, {@code 0 <= vertex < vertexCount()}
     * @param dest where to write it
     * @return {@code dest}, holding the position in mm
     */
    public Vector3f position(int vertex, Vector3f dest) {
        Objects.checkIndex(vertex, vertexCount());
        return dest.set(positions[3 * vertex], positions[3 * vertex + 1], positions[3 * vertex + 2]);
    }

    /**
     * A vertex's unit outward normal.
     *
     * @param vertex the vertex, {@code 0 <= vertex < vertexCount()}
     * @param dest where to write it
     * @return {@code dest}, holding the normal
     */
    public Vector3f normal(int vertex, Vector3f dest) {
        Objects.checkIndex(vertex, vertexCount());
        return dest.set(normals[3 * vertex], normals[3 * vertex + 1], normals[3 * vertex + 2]);
    }

    /**
     * Which flat face a vertex belongs to.
     *
     * @param vertex the vertex, {@code 0 <= vertex < vertexCount()}
     * @return the face-table index of its flat face, or {@link #NO_FACE} on a fillet or a cap
     */
    public int face(int vertex) {
        return faces[Objects.checkIndex(vertex, vertexCount())];
    }

    /**
     * One corner of a triangle.
     *
     * @param triangle the triangle, {@code 0 <= triangle < triangleCount()}
     * @param corner 0, 1 or 2, in counter-clockwise order seen from outside
     * @return the corner's vertex index
     */
    public int index(int triangle, int corner) {
        Objects.checkIndex(triangle, triangleCount());
        return indices[3 * triangle + Objects.checkIndex(corner, 3)];
    }

    /**
     * The vertices as one interleaved array for a vertex buffer: per vertex, position x, y, z then normal x, y,
     * z.
     *
     * @return a new array of {@code 6 * vertexCount()} floats
     */
    public float[] interleavedPositionsAndNormals() {
        float[] out = new float[6 * vertexCount()];
        for (int v = 0; v < vertexCount(); v++) {
            System.arraycopy(positions, 3 * v, out, 6 * v, 3);
            System.arraycopy(normals, 3 * v, out, 6 * v + 3, 3);
        }
        return out;
    }

    /**
     * The triangles' vertex indices for an element buffer, three per triangle.
     *
     * @return a new array of {@code 3 * triangleCount()} indices
     */
    public int[] indices() {
        return indices.clone();
    }

    /**
     * Builds every Die Kind's mesh once, on first use. Keyed by solid so that Die Kinds sharing a solid share a
     * mesh.
     */
    private static final class Cache {
        private static final Map<DieKind, DieMesh> BY_KIND = build();

        private static Map<DieKind, DieMesh> build() {
            Map<Solid, DieMesh> bySolid = new IdentityHashMap<>();
            Map<DieKind, DieMesh> out = new EnumMap<>(DieKind.class);
            for (DieKind kind : DieKind.values()) {
                out.put(kind, bySolid.computeIfAbsent(kind.solid(), solid -> rounded(solid, kind.edgeRadius())));
            }
            return out;
        }
    }
}
