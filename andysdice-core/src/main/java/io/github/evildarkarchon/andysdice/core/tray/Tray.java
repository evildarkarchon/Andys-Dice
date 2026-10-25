package io.github.evildarkarchon.andysdice.core.tray;

import io.github.evildarkarchon.andysdice.core.diceset.SrgbColour;
import io.github.evildarkarchon.andysdice.core.simulation.ContactMaterial;
import io.github.evildarkarchon.andysdice.core.simulation.OutlinePoint;
import io.github.evildarkarchon.andysdice.core.simulation.TrayGeometry;
import io.github.evildarkarchon.andysdice.core.simulation.TrayMaterials;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A Tray: what the Dice are Thrown into (ADR-0010). Its outline, walls and floor come from a {@code .tray} file (read
 * by {@link TrayFile}, which also holds each key's default); its size does not. Every Tray shares one Footprint,
 * {@value #FOOTPRINT_WIDTH_MM} × {@value #FOOTPRINT_DEPTH_MM} mm, with walls {@value #WALL_THICKNESS_MM} mm thick,
 * so physics is tuned once whichever Tray is chosen.
 *
 * @param identity the file's name without {@code .tray}, such as {@code 01-green-felt}: what the settings store and
 *     the catalogue finds it by
 * @param name the name the Player sees, such as {@code Green felt}
 * @param outline the inner outline's shape
 * @param walls the walls
 * @param floor the floor
 */
public record Tray(String identity, String name, TrayOutline outline, Walls walls, Floor floor) {

    /** The Footprint's inner width, along x, in mm (ADR-0010; spec §4: a contract). */
    public static final int FOOTPRINT_WIDTH_MM = 300;

    /** The Footprint's inner depth, along z (away from the camera), in mm. */
    public static final int FOOTPRINT_DEPTH_MM = 200;

    /** How thick every wall is, in mm: fixed, not a key (ADR-0010; the tray prototype's {@code WALL_T}). */
    public static final int WALL_THICKNESS_MM = 14;

    /** The lowest a wall may be, in mm (ADR-0010; spec §4: a contract). */
    public static final double MIN_WALL_HEIGHT_MM = 30;

    /**
     * The highest a wall may be, in mm. ADR-0010 sets only a minimum; this build caps it so the Release, which lets
     * go just above a 30 mm wall, and the camera's framing still suit every Tray. A taller value falls back.
     */
    public static final double MAX_WALL_HEIGHT_MM = 100;

    /**
     * The friction the prototypes gave a Die, which every surface and wall friction was tuned against. Jolt
     * combined two frictions by their geometric mean, so a Die on a surface of friction f had sqrt(0.4 f).
     */
    public static final float REFERENCE_DIE_FRICTION = 0.4f;

    /**
     * A Tray's walls.
     *
     * @param profile the cross-section over the top
     * @param material what they are made of
     * @param colour their colour
     * @param heightMm how far they rise above the floor, in mm: {@value #MIN_WALL_HEIGHT_MM} to
     *     {@value #MAX_WALL_HEIGHT_MM}
     */
    public record Walls(WallProfile profile, WallMaterial material, SrgbColour colour, double heightMm) {

        /**
         * Checks every part is there and the height is in range.
         *
         * @throws NullPointerException if a part is {@code null}
         * @throws IllegalArgumentException if the height is out of range
         */
        public Walls {
            Objects.requireNonNull(profile, "profile");
            Objects.requireNonNull(material, "material");
            Objects.requireNonNull(colour, "colour");
            if (!(heightMm >= MIN_WALL_HEIGHT_MM && heightMm <= MAX_WALL_HEIGHT_MM)) {
                throw new IllegalArgumentException("A Tray's walls are " + MIN_WALL_HEIGHT_MM + " to "
                        + MAX_WALL_HEIGHT_MM + " mm high, not " + heightMm);
            }
        }
    }

    /**
     * A Tray's floor.
     *
     * @param surface what it is covered with
     * @param colour its colour
     */
    public record Floor(FloorSurface surface, SrgbColour colour) {

        /**
         * Checks every part is there.
         *
         * @throws NullPointerException if a part is {@code null}
         */
        public Floor {
            Objects.requireNonNull(surface, "surface");
            Objects.requireNonNull(colour, "colour");
        }
    }

    /**
     * Checks that every part is there.
     *
     * @throws NullPointerException if a part is {@code null}
     * @throws IllegalArgumentException if the identity is blank
     */
    public Tray {
        Objects.requireNonNull(identity, "identity");
        if (identity.isBlank()) {
            throw new IllegalArgumentException("a Tray's identity is not blank");
        }
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(outline, "outline");
        Objects.requireNonNull(walls, "walls");
        Objects.requireNonNull(floor, "floor");
    }

    /** Physics works in centimetres (ADR-0003); a Tray file and the renderer in millimetres. */
    private static final float MM_PER_CM = 10;

    /** How many corners the oval's polygon has: the tray prototype's 32-gon. */
    static final int OVAL_CORNERS = 32;

    /**
     * Returns the Tray's shape as the simulator takes it, in centimetres: the outline centred on the origin inside
     * the Footprint, with the floor's top at y = 0 and walls of this Tray's height. The walls' inner faces stand on
     * the outline, and the escape volume is exactly what they enclose up to their top (ADR-0010).
     *
     * @return the geometry
     */
    public TrayGeometry geometry() {
        float halfX = FOOTPRINT_WIDTH_MM / 2f / MM_PER_CM;
        float halfZ = FOOTPRINT_DEPTH_MM / 2f / MM_PER_CM;
        float height = (float) (walls.heightMm() / MM_PER_CM);
        return switch (outline) {
            case RECTANGLE -> TrayGeometry.rectangle(halfX, halfZ, height);
            case HEXAGON -> {
                // The tray prototype's hexagon: pointed at the left and right, its near and far flats running
                // 0.55 of the half-width either side of the middle.
                float flat = halfX * 0.55f;
                yield new TrayGeometry(List.of(new OutlinePoint(halfX, 0), new OutlinePoint(flat, halfZ),
                        new OutlinePoint(-flat, halfZ), new OutlinePoint(-halfX, 0), new OutlinePoint(-flat, -halfZ),
                        new OutlinePoint(flat, -halfZ)), height);
            }
            case OVAL -> {
                // A superellipse of exponent 3 (|x|^3 + |z|^3 = 1, sampled as x = cos^(2/3)), as the prototype's
                // ROUND: it reads as a rounded tray, where an ellipse would read as a stretched circle.
                List<OutlinePoint> corners = new ArrayList<>(OVAL_CORNERS);
                for (int i = 0; i < OVAL_CORNERS; i++) {
                    double t = 2 * Math.PI * i / OVAL_CORNERS;
                    corners.add(new OutlinePoint((float) (halfX * superellipse(Math.cos(t))),
                            (float) (halfZ * superellipse(Math.sin(t)))));
                }
                yield new TrayGeometry(corners, height);
            }
        };
    }

    /**
     * One coordinate of the oval, {@code sign(c) |c|^(2/3)}, with the rounding error of cos(π/2) and sin(π)
     * snapped to 0 so the oval touches the Footprint's sides exactly where it should.
     */
    private static double superellipse(double c) {
        return Math.abs(c) < 1e-9 ? 0 : Math.signum(c) * Math.pow(Math.abs(c), 2.0 / 3.0);
    }

    /**
     * Returns the physics materials of a Throw into this Tray, as the combined pair values the simulator takes
     * (ADR-0010, ADR-0013): each set by the floor's surface and the walls' material.
     *
     * <p>Friction reproduces the flat-rest prototype, whose numbers the soak's gates were measured with. Its Dice had
     * friction {@value #REFERENCE_DIE_FRICTION}, and Jolt combines frictions by their geometric mean. So a Die
     * against the floor has friction sqrt(0.4 f) and the floor's restitution; against a wall, sqrt(0.4
     * f<sub>wall</sub>) and the wall's own restitution, spec Appendix C's wall bounce. That is not Jolt's maximum of
     * the two (which would lift padded leather's 0.2 to the leather floor's 0.25): the simulator sets each contact's
     * combined restitution from these values; and against another Die, ADR-0013's
     * {@link TrayMaterials#DIE_ON_DIE_FRICTION} with the floor's restitution. The floor also sets the Dice's spin
     * damping.
     *
     * @return the materials
     */
    public TrayMaterials materials() {
        FloorSurface surface = floor.surface();
        WallMaterial wall = walls.material();
        return TrayMaterials.of(
                new ContactMaterial((float) Math.sqrt(REFERENCE_DIE_FRICTION * surface.friction()),
                        surface.restitution()),
                new ContactMaterial((float) Math.sqrt(REFERENCE_DIE_FRICTION * wall.friction()),
                        wall.restitution()),
                surface.spinDamping());
    }
}
