package io.github.evildarkarchon.andysdice.physics;

import io.github.evildarkarchon.andysdice.core.simulation.DieStart;
import io.github.evildarkarchon.andysdice.core.simulation.Orientation;
import io.github.evildarkarchon.andysdice.core.simulation.OutlinePoint;
import io.github.evildarkarchon.andysdice.core.simulation.Pose;
import io.github.evildarkarchon.andysdice.core.simulation.TrayGeometry;
import io.github.evildarkarchon.andysdice.core.simulation.Vector3;
import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Draws a Release from the Release generator: ADR-0013's one-layer, gentle handful, with the flat-rest
 * prototype's "spread + gentle" numbers converted to centimetres. The numbers are tunables (spec §6).
 *
 * <p>Everything random comes from the given generator, in a fixed order, and nothing else is drawn from it.
 */
final class DrawnRelease {

    /** Up to this many Dice across, and this many rows deep, in one layer. */
    private static final int COLUMNS = 7;
    private static final int ROWS = 3;
    /** Wider than a d20's circumscribed diameter (about 2.5 cm), so no two Dice start overlapping. */
    private static final float SPACING = 3;
    private static final float JITTER = 0.2f;
    /** How far in from the near wall the first row starts. */
    private static final float NEAR_INSET = 2.2f;
    /** How far above the walls (capped at 3 cm) the handful starts. */
    private static final float LIFT = 2.6f;
    /** A Die is pulled in until its centre is this far inside the outline: a d20's circumradius and a little. */
    private static final float INSIDE = 1.4f;
    private static final float GENTLE = 0.65f;
    private static final float SPIN = 18;

    private DrawnRelease() {
    }

    /**
     * Draws the starts of {@code count} Dice into a Tray.
     *
     * @param generator the Release generator
     * @param count how many Dice
     * @param tray the Tray
     * @return one start per Die
     */
    static List<DieStart> draw(RandomGenerator generator, int count, TrayGeometry tray) {
        float halfX = 0;
        float nearZ = Float.POSITIVE_INFINITY;
        float farZ = Float.NEGATIVE_INFINITY;
        for (OutlinePoint p : tray.outline()) {
            halfX = Math.max(halfX, Math.abs(p.x()));
            nearZ = Math.min(nearZ, p.z());
            farZ = Math.max(farZ, p.z());
        }
        float halfZ = (farZ - nearZ) / 2;

        int columns = Math.min(COLUMNS, count);
        float cx = (float) generator.nextDouble(-0.25, 0.25) * halfX * 0.4f;
        float cz = nearZ + NEAR_INSET;
        float cy = Math.min(tray.wallHeight(), 3) + LIFT;
        // The prototype scaled speed with the square root of the half-depth in units of 10 cm.
        float speed = (float) (generator.nextDouble(65, 115) * Math.sqrt(halfZ / 10)) * GENTLE;
        float vx = (float) generator.nextDouble(-0.3, 0.3) * speed;

        List<DieStart> starts = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int column = i % columns;
            int row = (i / columns) % ROWS;
            int layer = i / (columns * ROWS);
            float x = cx + (column - (columns - 1) / 2f) * SPACING + (float) generator.nextDouble(-JITTER, JITTER);
            float z = cz + row * SPACING + (float) generator.nextDouble(-JITTER, JITTER);
            // Rows can overhang a slanted near wall (the hexagon); pull such a Die in towards the middle.
            while (tray.depthInside(x, z) < INSIDE && Math.abs(x) > 0.1f) {
                x *= 0.95f;
            }
            float y = cy + layer * SPACING;
            Orientation orientation = Orientation.about(new Vector3(1, 0, 0), (float) generator.nextDouble(7))
                    .times(Orientation.about(new Vector3(0, 1, 0), (float) generator.nextDouble(7)))
                    .times(Orientation.about(new Vector3(0, 0, 1), (float) generator.nextDouble(7)));
            float vy = (float) generator.nextDouble(-50, -25);
            Vector3 velocity = new Vector3(vx + (float) generator.nextDouble(-10, 10), vy,
                    speed + (float) generator.nextDouble(-10, 10));
            Vector3 spin = new Vector3((float) generator.nextDouble(-SPIN, SPIN),
                    (float) generator.nextDouble(-SPIN, SPIN), (float) generator.nextDouble(-SPIN, SPIN));
            starts.add(new DieStart(new Pose(new Vector3(x, y, z), orientation), velocity, spin));
        }
        return starts;
    }
}
