package io.github.evildarkarchon.andysdice.core.simulation;

import java.util.List;
import java.util.Objects;

/**
 * A Tray's shape as the simulator needs it: a flat floor whose top is the plane y = 0, bounded by a convex
 * outline of vertical walls of one height. A rounded outline (the oval) is given as a polygon.
 *
 * <p>The walls' inner faces stand on the outline, so the outline is also the escape boundary: a Die whose
 * centre leaves it has gone over a wall (ADR-0010).
 *
 * @param outline the outline's corners in order, either winding; at least 3, strictly convex
 * @param wallHeight how far the walls rise above the floor, in cm
 */
public record TrayGeometry(List<OutlinePoint> outline, float wallHeight) {

    /**
     * Checks the outline is a convex polygon and the walls have a height.
     *
     * @throws IllegalArgumentException if there are fewer than 3 corners, the outline isn't strictly convex,
     *     or the wall height isn't positive
     */
    public TrayGeometry {
        outline = List.copyOf(outline);
        if (outline.size() < 3) {
            throw new IllegalArgumentException("A Tray outline needs at least 3 corners, not " + outline.size());
        }
        if (!(wallHeight > 0) || !Float.isFinite(wallHeight)) {
            throw new IllegalArgumentException("A Tray's walls must have a positive height: " + wallHeight);
        }
        float sign = Math.signum(signedArea(outline));
        for (int i = 0; i < outline.size(); i++) {
            OutlinePoint a = outline.get(i);
            OutlinePoint b = outline.get((i + 1) % outline.size());
            OutlinePoint c = outline.get((i + 2) % outline.size());
            float turn = (b.x() - a.x()) * (c.z() - b.z()) - (b.z() - a.z()) * (c.x() - b.x());
            if (sign == 0 || Math.signum(turn) != sign) {
                throw new IllegalArgumentException("A Tray outline must be strictly convex; it isn't at " + b);
            }
        }
    }

    /**
     * Returns a rectangular Tray centred on the origin.
     *
     * @param halfX half the inner width along x, in cm
     * @param halfZ half the inner depth along z, in cm
     * @param wallHeight the wall height, in cm
     * @return the geometry
     * @throws IllegalArgumentException if a size isn't positive
     */
    public static TrayGeometry rectangle(float halfX, float halfZ, float wallHeight) {
        return new TrayGeometry(List.of(
                new OutlinePoint(-halfX, -halfZ), new OutlinePoint(halfX, -halfZ),
                new OutlinePoint(halfX, halfZ), new OutlinePoint(-halfX, halfZ)), wallHeight);
    }

    /**
     * Returns how many walls the outline has: one per edge, edge {@code i} running from corner {@code i} to
     * the next.
     *
     * @return the wall count
     */
    public int wallCount() {
        return outline.size();
    }

    /**
     * Returns the unit normal of a wall's inner face, pointing into the Tray.
     *
     * @param wall the wall's index
     * @return the normal, horizontal (y = 0)
     */
    public Vector3 inwardNormal(int wall) {
        Objects.checkIndex(wall, outline.size());
        OutlinePoint a = outline.get(wall);
        OutlinePoint b = outline.get((wall + 1) % outline.size());
        float dx = b.x() - a.x();
        float dz = b.z() - a.z();
        float length = (float) Math.sqrt(dx * dx + dz * dz);
        // With a positive signed area (in x-z treated as a 2D x-y plane) the interior is to each edge's left.
        float sign = Math.signum(signedArea(outline));
        return new Vector3(-dz * sign / length, 0, dx * sign / length);
    }

    /**
     * Returns how far a point on the floor plane lies inside the outline: its distance to the nearest wall's
     * inner face, negative once it is beyond a wall.
     *
     * @param x the world x, in cm
     * @param z the world z, in cm
     * @return the depth in cm; positive inside, negative outside
     */
    public float depthInside(float x, float z) {
        float depth = Float.POSITIVE_INFINITY;
        for (int i = 0; i < outline.size(); i++) {
            OutlinePoint a = outline.get(i);
            Vector3 n = inwardNormal(i);
            depth = Math.min(depth, (x - a.x()) * n.x() + (z - a.z()) * n.z());
        }
        return depth;
    }

    /** The shoelace formula's signed area of the outline in the x-z plane. */
    private static float signedArea(List<OutlinePoint> outline) {
        float twice = 0;
        for (int i = 0; i < outline.size(); i++) {
            OutlinePoint a = outline.get(i);
            OutlinePoint b = outline.get((i + 1) % outline.size());
            twice += a.x() * b.z() - b.x() * a.z();
        }
        return twice / 2;
    }
}
