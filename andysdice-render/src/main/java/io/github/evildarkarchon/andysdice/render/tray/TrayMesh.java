package io.github.evildarkarchon.andysdice.render.tray;

import io.github.evildarkarchon.andysdice.core.simulation.OutlinePoint;
import io.github.evildarkarchon.andysdice.core.simulation.TrayGeometry;
import io.github.evildarkarchon.andysdice.core.tray.Tray;
import io.github.evildarkarchon.andysdice.core.tray.WallMaterial;
import io.github.evildarkarchon.andysdice.core.tray.WallProfile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.joml.Vector2f;
import org.joml.Vector3f;

/**
 * The meshes a Tray is drawn with (ADR-0010), built from the very {@link TrayGeometry} physics collides with, so the
 * walls the Player sees are the escape volume: the floor fills the outline at y = 0, each wall's inner face stands on
 * an outline edge and rises exactly to the wall top, and the table lies just under the floor all around. After the
 * tray prototype's {@code TrayGeom}: the walls sweep a square or padded cross-section {@value Tray#WALL_THICKNESS_MM}
 * mm thick around the outline with mitred corners, smooth round the oval's many corners and sharp at a rectangle's
 * or hexagon's.
 *
 * <p>World space is y-up and in mm. Each vertex is {@value #FLOATS_PER_VERTEX} floats: position, unit normal, and a
 * texture coordinate that runs on at a constant density in mm, so the tiling surfaces keep one scale everywhere.
 * Pure Java: no GL.
 *
 * @param floor the floor
 * @param walls the walls
 * @param table the table around the Tray
 */
public record TrayMesh(Part floor, Part walls, Part table) {

    /** Floats per vertex: position (3), normal (3), texture coordinate (2). */
    public static final int FLOATS_PER_VERTEX = 8;

    /** Floor texture tiles per mm: one 100 mm tile (the tray prototype's density). */
    public static final float FLOOR_UV_PER_MM = 1 / 100f;
    /** Wooden walls' tiles per mm: the grain runs longer on the walls than on a floor. */
    public static final float WOOD_WALL_UV_PER_MM = 1 / 240f;
    /** Leather walls' tiles per mm: the floor's pebble size. */
    public static final float LEATHER_WALL_UV_PER_MM = 1 / 100f;
    /** The table's tiles per mm. */
    public static final float TABLE_UV_PER_MM = 1 / 140f;

    /** The table's top, just under the floor's so the two never fight for depth, in mm. */
    public static final float TABLE_Y = -0.5f;
    /** Half the table's side, in mm: far beyond anything the camera frames. */
    public static final float TABLE_HALF = 900;

    /** Outlines with more corners than this (the oval) get smooth wall normals round the corners. */
    private static final int SHARP_CORNERS_UP_TO = 8;
    /** How many segments the padded half-round is drawn with. */
    private static final int PADDED_SEGMENTS = 10;
    private static final float MM_PER_CM = 10;

    /**
     * One mesh: interleaved vertices and the triangles over them.
     *
     * @param vertices {@value #FLOATS_PER_VERTEX} floats per vertex
     * @param indices three vertex indices per triangle
     */
    public record Part(float[] vertices, int[] indices) {

        /**
         * Checks the arrays fit together and copies them.
         *
         * @throws IllegalArgumentException if the vertices aren't whole or the indices aren't whole triangles
         */
        public Part {
            vertices = vertices.clone();
            indices = indices.clone();
            if (vertices.length % FLOATS_PER_VERTEX != 0 || indices.length % 3 != 0) {
                throw new IllegalArgumentException("a mesh holds whole vertices and whole triangles");
            }
        }

        @Override
        public float[] vertices() {
            return vertices.clone();
        }

        @Override
        public int[] indices() {
            return indices.clone();
        }

        /**
         * Returns the number of vertices.
         *
         * @return the count
         */
        public int vertexCount() {
            return vertices.length / FLOATS_PER_VERTEX;
        }

        /**
         * Returns a vertex's position.
         *
         * @param v the vertex
         * @return a new vector, in mm
         */
        public Vector3f position(int v) {
            int at = v * FLOATS_PER_VERTEX;
            return new Vector3f(vertices[at], vertices[at + 1], vertices[at + 2]);
        }

        /**
         * Returns a vertex's normal.
         *
         * @param v the vertex
         * @return a new unit vector
         */
        public Vector3f normal(int v) {
            int at = v * FLOATS_PER_VERTEX + 3;
            return new Vector3f(vertices[at], vertices[at + 1], vertices[at + 2]);
        }

        /**
         * Returns a vertex's texture coordinate.
         *
         * @param v the vertex
         * @return {u, v}, in tiles
         */
        public float[] uv(int v) {
            int at = v * FLOATS_PER_VERTEX + 6;
            return new float[] {vertices[at], vertices[at + 1]};
        }
    }

    /**
     * Checks every part is there.
     *
     * @throws NullPointerException if a part is {@code null}
     */
    public TrayMesh {
        Objects.requireNonNull(floor, "floor");
        Objects.requireNonNull(walls, "walls");
        Objects.requireNonNull(table, "table");
    }

    /**
     * Builds a Tray's meshes.
     *
     * @param tray the Tray
     * @return its floor, walls and table
     */
    public static TrayMesh of(Tray tray) {
        List<Vector2f> outline = outlineMm(tray.geometry());
        float height = tray.geometry().wallHeight() * MM_PER_CM;
        float wallUvPerMm = tray.walls().material() == WallMaterial.LEATHER
                ? LEATHER_WALL_UV_PER_MM
                : WOOD_WALL_UV_PER_MM;
        return new TrayMesh(floor(outline), walls(outline, height, tray.walls().profile(), wallUvPerMm), TABLE);
    }

    /** The table around every Tray, the same under each: a square well beyond the walls, just under the floor. */
    private static final Part TABLE = buildTable();

    /** Builds the table: one upward square. */
    private static Part buildTable() {
        Builder b = new Builder();
        Vector3f up = new Vector3f(0, 1, 0);
        float h = TABLE_HALF;
        int p0 = b.vertex(new Vector3f(-h, TABLE_Y, -h), up, -h * TABLE_UV_PER_MM, -h * TABLE_UV_PER_MM);
        int p1 = b.vertex(new Vector3f(h, TABLE_Y, -h), up, h * TABLE_UV_PER_MM, -h * TABLE_UV_PER_MM);
        int p2 = b.vertex(new Vector3f(h, TABLE_Y, h), up, h * TABLE_UV_PER_MM, h * TABLE_UV_PER_MM);
        int p3 = b.vertex(new Vector3f(-h, TABLE_Y, h), up, -h * TABLE_UV_PER_MM, h * TABLE_UV_PER_MM);
        b.triangle(p0, p2, p1);
        b.triangle(p0, p3, p2);
        return b.build();
    }

    /**
     * The geometry's outline in mm, wound with a positive shoelace area in (x, z), so that (e.z, -e.x) is each edge
     * e's outward normal.
     */
    private static List<Vector2f> outlineMm(TrayGeometry geometry) {
        List<Vector2f> out = new ArrayList<>();
        for (OutlinePoint p : geometry.outline()) {
            out.add(new Vector2f(p.x() * MM_PER_CM, p.z() * MM_PER_CM));
        }
        float twiceArea = 0;
        for (int i = 0; i < out.size(); i++) {
            Vector2f a = out.get(i);
            Vector2f b = out.get((i + 1) % out.size());
            twiceArea += a.x * b.y - b.x * a.y;
        }
        if (twiceArea < 0) {
            Collections.reverse(out);
        }
        return out;
    }

    /** The floor: a fan over the (convex) outline from its middle, facing up. */
    private static Part floor(List<Vector2f> outline) {
        Builder b = new Builder();
        Vector3f up = new Vector3f(0, 1, 0);
        int centre = b.vertex(new Vector3f(0, 0, 0), up, 0, 0);
        int[] ring = new int[outline.size()];
        for (int i = 0; i < ring.length; i++) {
            Vector2f p = outline.get(i);
            ring[i] = b.vertex(new Vector3f(p.x, 0, p.y), up, p.x * FLOOR_UV_PER_MM, p.y * FLOOR_UV_PER_MM);
        }
        for (int i = 0; i < ring.length; i++) {
            b.triangle(centre, ring[(i + 1) % ring.length], ring[i]);
        }
        return b.build();
    }

    /**
     * The walls: a cross-section, in (out, up) mm from the floor's edge, swept round the outline. The profile runs up
     * the inner face, over the top and down the outer face; its points carry their (out, up) normal.
     */
    private static Part walls(List<Vector2f> outline, float height, WallProfile profile, float uvPerMm) {
        float t = Tray.WALL_THICKNESS_MM;
        List<float[]> prof = new ArrayList<>(); // {out, up, normalOut, normalUp}
        if (profile == WallProfile.SQUARE) {
            prof.add(new float[] {0, 0, -1, 0});
            prof.add(new float[] {0, height, -1, 0});
            prof.add(new float[] {0, height, 0, 1});
            prof.add(new float[] {t, height, 0, 1});
            prof.add(new float[] {t, height, 1, 0});
            prof.add(new float[] {t, 0, 1, 0});
        } else {
            // A padded roll: a straight inner face, a half-round over the top whose crown is the wall top, and the
            // outer face. Physics' wall is a box to the same top, so the roll's crown is where a Die can clear it.
            float r = t / 2;
            float base = height - r;
            prof.add(new float[] {0, 0, -1, 0});
            for (int i = 0; i <= PADDED_SEGMENTS; i++) {
                double a = Math.PI * i / PADDED_SEGMENTS;
                float nOut = (float) -Math.cos(a);
                float nUp = (float) Math.sin(a);
                // The crown lands exactly on the wall top, not a rounding error off it.
                float up = i * 2 == PADDED_SEGMENTS ? height : base + nUp * r;
                prof.add(new float[] {r + nOut * r, up, nOut, nUp});
            }
            prof.add(new float[] {t, 0, 1, 0});
        }

        int n = outline.size();
        boolean smooth = n > SHARP_CORNERS_UP_TO;
        Builder b = new Builder();
        float perimeter = 0;
        for (int i = 0; i < n; i++) {
            Vector2f a = outline.get(i);
            Vector2f c = outline.get((i + 1) % n);
            Vector2f edgeNormal = edgeNormal(outline, i);
            Vector2f normalAtA = smooth ? vertexNormal(outline, i) : edgeNormal;
            Vector2f normalAtC = smooth ? vertexNormal(outline, (i + 1) % n) : edgeNormal;
            Vector2f mitreA = mitre(outline, i);
            Vector2f mitreC = mitre(outline, (i + 1) % n);
            float length = a.distance(c);
            int[] previous = null;
            float v = 0;
            for (int k = 0; k < prof.size(); k++) {
                float[] p = prof.get(k);
                if (k > 0) {
                    float[] q = prof.get(k - 1);
                    v += (float) Math.hypot(p[0] - q[0], p[1] - q[1]);
                }
                int[] row = new int[2];
                for (int end = 0; end < 2; end++) {
                    Vector2f corner = end == 0 ? a : c;
                    Vector2f m = end == 0 ? mitreA : mitreC;
                    Vector2f en = end == 0 ? normalAtA : normalAtC;
                    Vector3f position = new Vector3f(corner.x + m.x * p[0], p[1], corner.y + m.y * p[0]);
                    Vector3f normal = new Vector3f(en.x * p[2], p[3], en.y * p[2]).normalize();
                    row[end] = b.vertex(position, normal, (perimeter + end * length) * uvPerMm, v * uvPerMm);
                }
                // Two profile points at the same spot only split the normal, a hard crease: no strip between them.
                if (previous != null && (p[0] != prof.get(k - 1)[0] || p[1] != prof.get(k - 1)[1])) {
                    b.triangle(previous[0], previous[1], row[1]);
                    b.triangle(previous[0], row[1], row[0]);
                }
                previous = row;
            }
            perimeter += length;
        }
        return b.build();
    }

    /** Outward unit normal of edge i (from corner i to i + 1), in (x, z). */
    private static Vector2f edgeNormal(List<Vector2f> outline, int i) {
        Vector2f a = outline.get(i);
        Vector2f b = outline.get((i + 1) % outline.size());
        return new Vector2f(b.y - a.y, -(b.x - a.x)).normalize();
    }

    /** The mean of the outward normals of the two edges meeting at corner i. */
    private static Vector2f vertexNormal(List<Vector2f> outline, int i) {
        int n = outline.size();
        return new Vector2f(edgeNormal(outline, (i - 1 + n) % n)).add(edgeNormal(outline, i)).normalize();
    }

    /** The offset direction at corner i that keeps both walls meeting there at their full thickness. */
    private static Vector2f mitre(List<Vector2f> outline, int i) {
        int n = outline.size();
        Vector2f before = edgeNormal(outline, (i - 1 + n) % n);
        Vector2f after = edgeNormal(outline, i);
        Vector2f m = new Vector2f(before).add(after).normalize();
        return m.div(Math.max(0.2f, m.dot(after)));
    }

    /** Collects interleaved vertices and triangles. */
    private static final class Builder {
        private float[] vertices = new float[64 * FLOATS_PER_VERTEX];
        private int floats;
        private int[] indices = new int[96];
        private int count;

        int vertex(Vector3f position, Vector3f normal, float u, float v) {
            if (floats + FLOATS_PER_VERTEX > vertices.length) {
                vertices = Arrays.copyOf(vertices, vertices.length * 2);
            }
            float[] f = {position.x, position.y, position.z, normal.x, normal.y, normal.z, u, v};
            System.arraycopy(f, 0, vertices, floats, FLOATS_PER_VERTEX);
            floats += FLOATS_PER_VERTEX;
            return floats / FLOATS_PER_VERTEX - 1;
        }

        void triangle(int a, int b, int c) {
            if (count + 3 > indices.length) {
                indices = Arrays.copyOf(indices, indices.length * 2);
            }
            indices[count++] = a;
            indices[count++] = b;
            indices[count++] = c;
        }

        Part build() {
            return new Part(Arrays.copyOf(vertices, floats), Arrays.copyOf(indices, count));
        }
    }
}
