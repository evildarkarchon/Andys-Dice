package andysdice.prototype.tray;

import org.joml.Vector2f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * PROTOTYPE. One tray's shape: its inner outline (a convex polygon in XZ, millimetres, y up, the floor
 * at y = 0), the meshes the renderer draws, and the boxes physics collides with. Immutable; a new one is
 * built whenever a tray knob changes, and physics and the renderer each compare by identity.
 */
final class TrayGeom {
    /** Wall thickness in mm. */
    static final float WALL_T = 14;
    /** Height of the invisible glass walls when {@link Params.Escape#GLASS} is on. */
    static final float GLASS_H = 200;
    /** The table around the tray, half size in mm. */
    static final float TABLE_HALF = 900;

    final Params.Shape shape;
    final Params.Size size;
    final Params.WallMaterial wallMaterial;
    final float wallHeight;
    final Params.Escape escape;
    /** Inner outline, with a positive shoelace area in (x, z), so (ez, -ex) is each edge's outward normal. */
    final List<Vector2f> outline;
    final MeshData floor, walls, table;
    /** Line segments (pairs of points) outlining the escape volume. */
    final float[] escapeLines;

    TrayGeom(Params.Shape shape, Params.Size size, Params.WallMaterial wallMaterial, float wallHeight, Params.Escape escape) {
        this.shape = shape;
        this.size = size;
        this.wallMaterial = wallMaterial;
        this.wallHeight = wallHeight;
        this.escape = escape;
        outline = outline(shape, size.halfX, size.halfZ);
        floor = floorMesh(outline, 0f, 1 / 100f);
        walls = wallMesh();
        table = floorMesh(List.of(new Vector2f(-TABLE_HALF, -TABLE_HALF), new Vector2f(-TABLE_HALF, TABLE_HALF),
                new Vector2f(TABLE_HALF, TABLE_HALF), new Vector2f(TABLE_HALF, -TABLE_HALF)), -0.5f, 1 / 140f);
        escapeLines = escapeLines();
    }

    /** Whether this geometry was built from exactly these knobs. */
    boolean matches(Params p) {
        return shape == p.shape && size == p.size && wallMaterial == p.wall && wallHeight == p.wallHeight() && escape == p.escape;
    }

    /** Top of the escape volume: the wall top, or the glass. */
    float escapeTop() {
        return escape == Params.Escape.GLASS ? GLASS_H : wallHeight;
    }

    /** Radius of a circle around the tray centre that holds the whole inner outline. */
    float radius() {
        float r = 0;
        for (Vector2f v : outline) r = Math.max(r, v.length());
        return r;
    }

    /** Whether XZ point (x, z) lies inside the inner outline, shrunk by {@code inset} mm. */
    boolean inside(float x, float z, float inset) {
        int n = outline.size();
        for (int i = 0; i < n; i++) {
            Vector2f a = outline.get(i), b = outline.get((i + 1) % n);
            // Outward normal of a CCW (seen from +Y) edge in the XZ plane.
            float ex = b.x - a.x, ez = b.y - a.y;
            float len = (float) Math.hypot(ex, ez);
            float nx = ez / len, nz = -ex / len;
            if ((x - a.x) * nx + (z - a.y) * nz > -inset) return false;
        }
        return true;
    }

    /** One static box for physics: centre and half extents in mm, rotated by {@code yaw} about +Y. */
    record Box(Vector3f centre, Vector3f half, float yaw) {
    }

    /** The floor and one box per wall edge, as tall as the escape volume (glass) or the wall. */
    List<Box> physicsBoxes() {
        List<Box> out = new ArrayList<>();
        out.add(new Box(new Vector3f(0, -20, 0), new Vector3f(TABLE_HALF, 20, TABLE_HALF), 0));
        float h = escapeTop();
        int n = outline.size();
        for (int i = 0; i < n; i++) {
            Vector2f a = outline.get(i), b = outline.get((i + 1) % n);
            float ex = b.x - a.x, ez = b.y - a.y;
            float len = (float) Math.hypot(ex, ez);
            float nx = ez / len, nz = -ex / len;
            Vector3f c = new Vector3f((a.x + b.x) / 2 + nx * WALL_T / 2, h / 2, (a.y + b.y) / 2 + nz * WALL_T / 2);
            // Long enough to overlap its neighbours at the corners, so nothing slips through a seam.
            float yaw = (float) Math.atan2(-ez, ex);
            out.add(new Box(c, new Vector3f(len / 2 + WALL_T, h / 2, WALL_T / 2), yaw));
        }
        return out;
    }

    private static List<Vector2f> outline(Params.Shape shape, float hx, float hz) {
        List<Vector2f> out = new ArrayList<>();
        switch (shape) {
            case RECT -> {
                out.add(new Vector2f(hx, -hz));
                out.add(new Vector2f(hx, hz));
                out.add(new Vector2f(-hx, hz));
                out.add(new Vector2f(-hx, -hz));
            }
            case HEX -> {
                // Pointy at the left and right, flat along the near and far walls.
                out.add(new Vector2f(hx, 0));
                out.add(new Vector2f(hx * 0.55f, hz));
                out.add(new Vector2f(-hx * 0.55f, hz));
                out.add(new Vector2f(-hx, 0));
                out.add(new Vector2f(-hx * 0.55f, -hz));
                out.add(new Vector2f(hx * 0.55f, -hz));
            }
            case ROUND -> {
                // A superellipse reads as a rounded tray rather than a stretched circle.
                int n = shape.sides;
                for (int i = 0; i < n; i++) {
                    double t = 2 * Math.PI * i / n;
                    double c = Math.cos(t), s = Math.sin(t);
                    double e = 2.0 / 3.0;
                    out.add(new Vector2f((float) (hx * Math.signum(c) * Math.pow(Math.abs(c), e)), (float) (hz * Math.signum(s) * Math.pow(Math.abs(s), e))));
                }
            }
        }
        // Keep a positive shoelace area in (x, z): then every edge's outward normal is (ez, -ex), the
        // formula edgeNormal(), inside() and physicsBoxes() all rely on.
        float area = 0;
        for (int i = 0; i < out.size(); i++) {
            Vector2f a = out.get(i), b = out.get((i + 1) % out.size());
            area += a.x * b.y - b.x * a.y;
        }
        if (area < 0) java.util.Collections.reverse(out);
        return out;
    }

    /** A flat polygon fan facing up, with UVs from world XZ so textures keep a constant density. */
    private static MeshData floorMesh(List<Vector2f> poly, float y, float uvPerMm) {
        MeshData.Builder b = new MeshData.Builder();
        Vector3f up = new Vector3f(0, 1, 0), t = new Vector3f(1, 0, 0), bt = new Vector3f(0, 0, 1);
        int c = b.vertex(new Vector3f(0, y, 0), up, new Vector2f(0, 0), t, bt);
        int[] ring = new int[poly.size()];
        for (int i = 0; i < poly.size(); i++) {
            Vector2f p = poly.get(i);
            ring[i] = b.vertex(new Vector3f(p.x, y, p.y), up, new Vector2f(p.x * uvPerMm, p.y * uvPerMm), t, bt);
        }
        for (int i = 0; i < ring.length; i++) b.triangle(c, ring[i], ring[(i + 1) % ring.length], up);
        return b.build();
    }

    /**
     * Sweeps the wall's cross-section around the outline. The profile is a polyline in (out, up) mm from
     * the inner floor edge, over the top, and down the outside. Corners are mitred; a many-sided outline
     * (the oval) gets smooth normals around the corners, a rectangle or hexagon keeps them sharp.
     */
    private MeshData wallMesh() {
        List<float[]> prof = new ArrayList<>(); // {out, up, normalOut, normalUp}
        float h = wallHeight, t = WALL_T;
        if (wallMaterial == Params.WallMaterial.WOOD) {
            prof.add(new float[]{0, 0, -1, 0});
            prof.add(new float[]{0, h, -1, 0});
            prof.add(new float[]{0, h, 0, 1});
            prof.add(new float[]{t, h, 0, 1});
            prof.add(new float[]{t, h, 1, 0});
            prof.add(new float[]{t, 0, 1, 0});
        } else {
            // A padded roll: straight inner face, then a half-round over the top, then the outer face.
            float r = t / 2, base = Math.max(h - r, 0);
            prof.add(new float[]{0, 0, -1, 0});
            for (int i = 0; i <= 10; i++) {
                double a = Math.PI * i / 10;
                float nx = (float) -Math.cos(a), ny = (float) Math.sin(a);
                prof.add(new float[]{r + nx * r, base + ny * r, nx, ny});
            }
            prof.add(new float[]{t, 0, 1, 0});
        }
        int n = outline.size();
        boolean smooth = n > 8;
        MeshData.Builder b = new MeshData.Builder();
        // Leather keeps the floor's grain size; wood grain runs longer on the walls.
        float uvPerMm = wallMaterial == Params.WallMaterial.PADDED ? 1 / 100f : 1 / 240f;
        float perim = 0;
        for (int i = 0; i < n; i++) {
            Vector2f a = outline.get(i), c = outline.get((i + 1) % n);
            Vector2f na = edgeNormal(i), nc = smooth ? vertexNormal((i + 1) % n) : na;
            Vector2f n0 = smooth ? vertexNormal(i) : na;
            Vector2f ma = miter(i), mc = miter((i + 1) % n);
            float len = a.distance(c);
            Vector3f tan = new Vector3f(c.x - a.x, 0, c.y - a.y).normalize();
            int[] prevRow = null;
            float v = 0;
            for (int k = 0; k < prof.size(); k++) {
                float[] p = prof.get(k);
                if (k > 0) {
                    float[] q = prof.get(k - 1);
                    v += (float) Math.hypot(p[0] - q[0], p[1] - q[1]);
                }
                int[] row = new int[2];
                for (int e = 0; e < 2; e++) {
                    Vector2f base = e == 0 ? a : c, m = e == 0 ? ma : mc, en = e == 0 ? n0 : nc;
                    Vector3f pos = new Vector3f(base.x + m.x * p[0], p[1], base.y + m.y * p[0]);
                    Vector3f nrm = new Vector3f(en.x * p[2], p[3], en.y * p[2]).normalize();
                    Vector3f bt = new Vector3f(nrm).cross(tan).normalize();
                    row[e] = b.vertex(pos, nrm, new Vector2f((perim + e * len) * uvPerMm, v * uvPerMm), tan, bt);
                }
                // Consecutive profile points at the same spot only split the normal (a hard crease).
                if (prevRow != null && (p[0] != prof.get(k - 1)[0] || p[1] != prof.get(k - 1)[1])) {
                    Vector3f out = new Vector3f(n0.x * (p[2] + prof.get(k - 1)[2]), p[3] + prof.get(k - 1)[3], n0.y * (p[2] + prof.get(k - 1)[2]));
                    b.triangle(prevRow[0], prevRow[1], row[1], out);
                    b.triangle(prevRow[0], row[1], row[0], out);
                }
                prevRow = row;
            }
            perim += len;
        }
        return b.build();
    }

    /** Outward unit normal of edge i (from vertex i to i + 1), in XZ. */
    private Vector2f edgeNormal(int i) {
        Vector2f a = outline.get(i), b = outline.get((i + 1) % outline.size());
        return new Vector2f(b.y - a.y, -(b.x - a.x)).normalize();
    }

    private Vector2f vertexNormal(int i) {
        int n = outline.size();
        return new Vector2f(edgeNormal((i - 1 + n) % n)).add(edgeNormal(i)).normalize();
    }

    /** The offset direction at vertex i that keeps both adjacent wall faces at their full thickness. */
    private Vector2f miter(int i) {
        int n = outline.size();
        Vector2f e0 = edgeNormal((i - 1 + n) % n), e1 = edgeNormal(i);
        Vector2f m = new Vector2f(e0).add(e1).normalize();
        return m.div(Math.max(0.2f, m.dot(e1)));
    }

    private float[] escapeLines() {
        List<Float> out = new ArrayList<>();
        float top = escapeTop();
        int n = outline.size();
        for (int i = 0; i < n; i++) {
            Vector2f a = outline.get(i), b = outline.get((i + 1) % n);
            for (float y : new float[]{0.3f, top}) {
                out.addAll(List.of(a.x, y, a.y, b.x, y, b.y));
            }
            out.addAll(List.of(a.x, 0.3f, a.y, a.x, top, a.y));
        }
        float[] arr = new float[out.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = out.get(i);
        return arr;
    }
}
