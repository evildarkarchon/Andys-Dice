package andysdice.prototype.tray;

import org.joml.Vector2f;
import org.joml.Vector3f;

import java.util.Arrays;

/**
 * PROTOTYPE. Triangle mesh in a Y-up, millimetre world (copied from the renderer bake-off).
 *
 * <p>Every vertex carries a tangent ({@code dP/du}) and bitangent ({@code dP/dv}, with v growing
 * downwards in the image), matching how {@link Atlas} derives its slope map.
 */
final class MeshData {
    final float[] positions;
    final float[] normals;
    final float[] uvs;
    final float[] tangents;
    final float[] bitangents;
    /** Triangles, counter-clockwise when seen from outside. */
    final int[] indices;

    private MeshData(Builder b) {
        positions = Arrays.copyOf(b.pos, b.vertexCount * 3);
        normals = Arrays.copyOf(b.nrm, b.vertexCount * 3);
        uvs = Arrays.copyOf(b.uv, b.vertexCount * 2);
        tangents = Arrays.copyOf(b.tan, b.vertexCount * 3);
        bitangents = Arrays.copyOf(b.bit, b.vertexCount * 3);
        indices = Arrays.copyOf(b.idx, b.indexCount);
    }

    int vertexCount() {
        return positions.length / 3;
    }

    int triangleCount() {
        return indices.length / 3;
    }

    /** Growable builder; vertices are never shared implicitly, callers index them explicitly. */
    static final class Builder {
        private float[] pos = new float[3 * 1024], nrm = new float[3 * 1024], uv = new float[2 * 1024];
        private float[] tan = new float[3 * 1024], bit = new float[3 * 1024];
        private int[] idx = new int[3 * 1024];
        private int vertexCount, indexCount;

        /**
         * Appends a vertex and returns its index.
         *
         * @param t tangent, the direction of increasing u on the surface
         * @param b bitangent, the direction of increasing v (image-down) on the surface
         */
        int vertex(Vector3f p, Vector3f n, Vector2f texCoord, Vector3f t, Vector3f b) {
            return vertex(p.x, p.y, p.z, n, texCoord.x, texCoord.y, t, b);
        }

        /** Allocation-free variant for the dense meshes: position and UV as primitives. */
        int vertex(float px, float py, float pz, Vector3f n, float u, float v, Vector3f t, Vector3f b) {
            if (vertexCount * 3 + 3 > pos.length) {
                int cap = pos.length * 2;
                pos = Arrays.copyOf(pos, cap);
                nrm = Arrays.copyOf(nrm, cap);
                tan = Arrays.copyOf(tan, cap);
                bit = Arrays.copyOf(bit, cap);
                uv = Arrays.copyOf(uv, cap / 3 * 2);
            }
            int i = vertexCount++;
            pos[i * 3] = px; pos[i * 3 + 1] = py; pos[i * 3 + 2] = pz;
            nrm[i * 3] = n.x; nrm[i * 3 + 1] = n.y; nrm[i * 3 + 2] = n.z;
            tan[i * 3] = t.x; tan[i * 3 + 1] = t.y; tan[i * 3 + 2] = t.z;
            bit[i * 3] = b.x; bit[i * 3 + 1] = b.y; bit[i * 3 + 2] = b.z;
            uv[i * 2] = u; uv[i * 2 + 1] = v;
            return i;
        }

        /**
         * Appends a triangle, flipping its winding if needed so it is counter-clockwise when seen
         * from the side {@code outward} points to. This lets generators ignore winding entirely.
         */
        void triangle(int a, int b, int c, Vector3f outward) {
            float abx = pos[b * 3] - pos[a * 3], aby = pos[b * 3 + 1] - pos[a * 3 + 1], abz = pos[b * 3 + 2] - pos[a * 3 + 2];
            float acx = pos[c * 3] - pos[a * 3], acy = pos[c * 3 + 1] - pos[a * 3 + 1], acz = pos[c * 3 + 2] - pos[a * 3 + 2];
            float cx = aby * acz - abz * acy, cy = abz * acx - abx * acz, cz = abx * acy - aby * acx;
            if (cx * outward.x + cy * outward.y + cz * outward.z < 0) {
                int t = b; b = c; c = t;
            }
            if (indexCount + 3 > idx.length) idx = Arrays.copyOf(idx, idx.length * 2);
            idx[indexCount++] = a;
            idx[indexCount++] = b;
            idx[indexCount++] = c;
        }

        Vector3f p(int i) {
            return new Vector3f(pos[i * 3], pos[i * 3 + 1], pos[i * 3 + 2]);
        }

        MeshData build() {
            return new MeshData(this);
        }
    }

    /**
     * An axis-aligned box from {@code min} to {@code max}, each face mapped to the full 0..1 UV range
     * scaled by {@code uvPerMm} so wood grain keeps a constant density across differently sized walls.
     */
    static MeshData box(Vector3f min, Vector3f max, float uvPerMm) {
        Builder b = new Builder();
        Vector3f size = new Vector3f(max).sub(min);
        Vector3f centre = new Vector3f(min).add(max).mul(0.5f);
        Vector3f[] axes = {new Vector3f(1, 0, 0), new Vector3f(0, 1, 0), new Vector3f(0, 0, 1)};
        for (int axis = 0; axis < 3; axis++) {
            for (int sign = -1; sign <= 1; sign += 2) {
                Vector3f n = new Vector3f(axes[axis]).mul(sign);
                // u runs along the next axis, v (image-down) against the one after, so grain runs lengthwise.
                Vector3f t = new Vector3f(axes[(axis + 1) % 3]);
                Vector3f bt = new Vector3f(axes[(axis + 2) % 3]).negate();
                float hu = size.get((axis + 1) % 3) / 2, hv = size.get((axis + 2) % 3) / 2;
                Vector3f faceCentre = new Vector3f(centre).add(new Vector3f(n).mul(size.get(axis) / 2));
                int[] v = new int[4];
                float[][] corners = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
                for (int k = 0; k < 4; k++) {
                    float cu = corners[k][0], cv = corners[k][1];
                    Vector3f p = new Vector3f(faceCentre).add(new Vector3f(t).mul(cu * hu)).add(new Vector3f(bt).mul(cv * hv));
                    Vector2f tc = new Vector2f((cu * hu + hu) * uvPerMm, (cv * hv + hv) * uvPerMm);
                    v[k] = b.vertex(p, n, tc, t, bt);
                }
                b.triangle(v[0], v[1], v[2], n);
                b.triangle(v[0], v[2], v[3], n);
            }
        }
        return b.build();
    }

    /** A horizontal quad at height {@code y}, facing up, with UVs 0..1 across it. */
    static MeshData floorQuad(float halfX, float halfZ, float y) {
        Builder b = new Builder();
        Vector3f up = new Vector3f(0, 1, 0);
        Vector3f t = new Vector3f(1, 0, 0), bt = new Vector3f(0, 0, 1);
        int a = b.vertex(new Vector3f(-halfX, y, -halfZ), up, new Vector2f(0, 0), t, bt);
        int c = b.vertex(new Vector3f(halfX, y, -halfZ), up, new Vector2f(1, 0), t, bt);
        int d = b.vertex(new Vector3f(halfX, y, halfZ), up, new Vector2f(1, 1), t, bt);
        int e = b.vertex(new Vector3f(-halfX, y, halfZ), up, new Vector2f(0, 1), t, bt);
        b.triangle(a, c, d, up);
        b.triangle(a, d, e, up);
        return b.build();
    }
}
