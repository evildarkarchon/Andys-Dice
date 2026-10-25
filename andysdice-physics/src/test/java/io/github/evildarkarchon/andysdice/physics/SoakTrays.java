package io.github.evildarkarchon.andysdice.physics;

import io.github.evildarkarchon.andysdice.core.simulation.ContactMaterial;
import io.github.evildarkarchon.andysdice.core.simulation.OutlinePoint;
import io.github.evildarkarchon.andysdice.core.simulation.TrayGeometry;
import io.github.evildarkarchon.andysdice.core.simulation.TrayMaterials;
import java.util.List;

/**
 * The Trays the soak throws into: stand-ins for spec Appendix C's three built-in Trays, built straight from the
 * tray and flat-rest prototypes' numbers ({@code Params.Surface}, {@code Params.WallMaterial}, {@code TrayGeom})
 * at the 300 × 200 mm Footprint with 30 mm walls (ADR-0010).
 *
 * <p>The built-in Trays ticket (#44) replaces {@link #BUILT_IN} with the shipped {@code .tray} files' geometry and
 * materials; the soak only needs each Tray's name, {@link TrayGeometry} and {@link TrayMaterials}.
 */
final class SoakTrays {

    /**
     * One Tray to soak.
     *
     * @param name what the report calls it
     * @param geometry its shape
     * @param materials its physics materials
     */
    record SoakTray(String name, TrayGeometry geometry, TrayMaterials materials) {

        @Override
        public String toString() {
            return name;
        }
    }

    /** The Footprint's half extents and the wall height, in cm (ADR-0010). */
    private static final float HALF_X = 15;
    private static final float HALF_Z = 10;
    private static final float WALL_HEIGHT = 3;

    /**
     * The prototypes' own friction of a Die. They tuned every surface against it, and Jolt combines two frictions
     * by their geometric mean, so a Die against a surface of friction f had a combined friction of sqrt(0.4 f)
     * (die-on-die is ADR-0013's 0.2 instead: {@link TrayMaterials#of}).
     */
    private static final float PROTOTYPE_DIE_FRICTION = 0.4f;

    /** The prototypes' wall friction, the same for walnut and padded leather. */
    private static final float WALL_FRICTION = 0.5f;

    /** Green felt in walnut walls, square tops (wall bounce 0.5): the default Tray. */
    static final SoakTray GREEN_FELT = new SoakTray("Green felt",
            TrayGeometry.rectangle(HALF_X, HALF_Z, WALL_HEIGHT), materials(0.5f, 0.15f, 0.6f, 0.5f));

    /** Brown leather in a hexagon of padded leather walls, rounded tops (wall bounce 0.2). */
    static final SoakTray LEATHER_HEXAGON = new SoakTray("Leather hexagon", hexagon(),
            materials(0.55f, 0.25f, 0.45f, 0.2f));

    /** Bare walnut in walnut walls, square tops (wall bounce 0.5). */
    static final SoakTray WALNUT_BOARD = new SoakTray("Walnut board",
            TrayGeometry.rectangle(HALF_X, HALF_Z, WALL_HEIGHT), materials(0.35f, 0.45f, 0.2f, 0.5f));

    /** Every built-in Tray, in Appendix C's order. */
    static final List<SoakTray> BUILT_IN = List.of(GREEN_FELT, LEATHER_HEXAGON, WALNUT_BOARD);

    private SoakTrays() {
    }

    /**
     * Returns a Tray's combined contact materials from the prototypes' per-surface numbers.
     *
     * <p>The prototypes gave each Die the floor's restitution and Jolt combines two restitutions by their maximum,
     * so a Die bounced off the floor at the floor's restitution and off a wall at the larger of the floor's and the
     * wall's. These are the values the prototype's measurements were made with.
     *
     * @param floorFriction the floor surface's own friction ({@code Params.Surface.friction})
     * @param floorBounce the floor surface's restitution
     * @param spinDamping the floor surface's angular damping of a Die
     * @param wallBounce the walls' restitution ({@code Params.WallMaterial.restitution})
     */
    private static TrayMaterials materials(float floorFriction, float floorBounce, float spinDamping,
            float wallBounce) {
        return TrayMaterials.of(
                new ContactMaterial((float) Math.sqrt(PROTOTYPE_DIE_FRICTION * floorFriction), floorBounce),
                new ContactMaterial((float) Math.sqrt(PROTOTYPE_DIE_FRICTION * WALL_FRICTION),
                        Math.max(floorBounce, wallBounce)),
                spinDamping);
    }

    /**
     * Returns the tray prototype's hexagon at the Footprint: pointed at the left and right, flat along the near and
     * far walls, the flats 0.55 of the half-width either side of the middle ({@code TrayGeom.outline(HEX)}).
     */
    private static TrayGeometry hexagon() {
        float flat = HALF_X * 0.55f;
        return new TrayGeometry(List.of(new OutlinePoint(HALF_X, 0), new OutlinePoint(flat, HALF_Z),
                new OutlinePoint(-flat, HALF_Z), new OutlinePoint(-HALF_X, 0), new OutlinePoint(-flat, -HALF_Z),
                new OutlinePoint(flat, -HALF_Z)), WALL_HEIGHT);
    }
}
