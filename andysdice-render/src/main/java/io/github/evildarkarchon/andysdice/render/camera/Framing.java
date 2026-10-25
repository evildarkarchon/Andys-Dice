package io.github.evildarkarchon.andysdice.render.camera;

import io.github.evildarkarchon.andysdice.core.simulation.OutlinePoint;
import io.github.evildarkarchon.andysdice.core.simulation.TrayGeometry;
import io.github.evildarkarchon.andysdice.render.DiceScene;
import java.util.List;
import org.joml.Vector3d;

/**
 * The automatic camera's two framings of one Tray (spec §3.2 "Camera", ADR-0010's settle-in camera), taken from
 * the tray prototype's chosen SETTLE camera: the whole Tray, walls included, from a fixed 3/4 view over its near
 * end while Dice tumble; and a closer, higher view of the Dice at rest. Both fit the view's aspect ratio through
 * the {@value CameraPose#FOV_Y_DEGREES}° lens. The numbers are tunables (spec §6: the camera limits).
 *
 * <p>Pure and immutable: no GL, safe on any thread.
 *
 * @param halfWidth half the Tray's outer width along x, walls included, in mm
 * @param halfDepth half the Tray's outer depth along z, walls included, in mm
 * @param wallHeight the walls' height above the floor, in mm
 */
public record Framing(double halfWidth, double halfDepth, double wallHeight) {

    /** The pitch of the fixed 3/4 view while Dice tumble, in degrees. */
    public static final double TRAY_PITCH_DEGREES = 50;

    /** The pitch of the close-up on the Dice at rest, in degrees. */
    public static final double REST_PITCH_DEGREES = 62;

    /** The walls' thickness outside the outline the simulator walls stand on: the tray prototype's 14 mm. */
    public static final double WALL_THICKNESS_MM = 14;

    /** Where the whole-Tray view looks: a little above the middle of the floor, in mm. */
    private static final double TRAY_TARGET_HEIGHT_MM = 5;

    /** A margin around the whole Tray. */
    private static final double TRAY_MARGIN = 1.08;

    /** A margin around the Dice at rest. */
    private static final double REST_MARGIN = 1.1;

    /** Each Die is framed as a sphere this many inradii round, so its corners and shadow stay in view. */
    private static final double DIE_RADII = 1.6;

    /** The close-up never frames less than a sphere this big, so a lone Die isn't filled edge to edge, in mm. */
    private static final double MIN_REST_RADIUS_MM = 45;

    private static final double MM_PER_CM = 10;

    /**
     * Checks the sizes.
     *
     * @throws IllegalArgumentException if a size isn't positive and finite
     */
    public Framing {
        for (double size : new double[] {halfWidth, halfDepth, wallHeight}) {
            if (!(size > 0) || !Double.isFinite(size)) {
                throw new IllegalArgumentException("A Tray's framed sizes must be positive: " + halfWidth + ", "
                        + halfDepth + ", " + wallHeight);
            }
        }
    }

    /**
     * Returns the framing of a Tray the simulator knows: the box around its outline, plus the walls' thickness.
     *
     * @param tray the Tray's geometry, in the simulator's cm
     * @return its framing, in mm
     */
    public static Framing of(TrayGeometry tray) {
        double halfX = 0;
        double halfZ = 0;
        for (OutlinePoint corner : tray.outline()) {
            halfX = Math.max(halfX, Math.abs(corner.x()));
            halfZ = Math.max(halfZ, Math.abs(corner.z()));
        }
        return new Framing(halfX * MM_PER_CM + WALL_THICKNESS_MM, halfZ * MM_PER_CM + WALL_THICKNESS_MM,
                tray.wallHeight() * MM_PER_CM);
    }

    /**
     * Returns the fixed 3/4 view of the whole Tray, as it is while Dice tumble.
     *
     * @param aspect the view's width over its height
     * @return a pose at yaw 0 and {@value #TRAY_PITCH_DEGREES}°, just far enough back to hold the Tray
     */
    public CameraPose wholeTray(double aspect) {
        checkAspect(aspect);
        double pitch = Math.toRadians(TRAY_PITCH_DEGREES);
        double tanHalf = Math.tan(Math.toRadians(CameraPose.FOV_Y_DEGREES / 2));
        double byWidth = halfWidth / (tanHalf * aspect);
        double byDepth = (halfDepth * Math.sin(pitch) + wallHeight * Math.cos(pitch)) / tanHalf;
        // The prototype's fit: the near wall sits nearer the eye than the target, so step back by its run.
        double distance = Math.max(byWidth, byDepth) * TRAY_MARGIN + halfDepth * Math.cos(pitch);
        return new CameraPose(new Vector3d(0, TRAY_TARGET_HEIGHT_MM, 0), 0, TRAY_PITCH_DEGREES, distance);
    }

    /**
     * Returns the close-up on Dice at rest: higher than the whole-Tray view, looking at their middle on the
     * floor, just far enough back to hold every one of them.
     *
     * @param dice the Dice at rest, in the renderer's mm
     * @param aspect the view's width over its height
     * @return the close-up, or {@link #wholeTray} if there are no Dice
     */
    public CameraPose resting(List<DiceScene.Die> dice, double aspect) {
        checkAspect(aspect);
        if (dice.isEmpty()) {
            return wholeTray(aspect);
        }
        Vector3d centre = new Vector3d();
        for (DiceScene.Die die : dice) {
            centre.add(die.position());
        }
        centre.div(dice.size());
        double radius = MIN_REST_RADIUS_MM;
        for (DiceScene.Die die : dice) {
            radius = Math.max(radius, die.position().distance(centre) + die.kind().inradius() * DIE_RADII);
        }
        double halfVertical = Math.toRadians(CameraPose.FOV_Y_DEGREES / 2);
        double halfHorizontal = Math.atan(Math.tan(halfVertical) * aspect);
        double distance = radius / Math.sin(Math.min(halfVertical, halfHorizontal)) * REST_MARGIN;
        return new CameraPose(new Vector3d(centre.x, 0, centre.z), 0, REST_PITCH_DEGREES, distance);
    }

    private static void checkAspect(double aspect) {
        if (!(aspect > 0) || !Double.isFinite(aspect)) {
            throw new IllegalArgumentException("A view's aspect ratio must be positive: " + aspect);
        }
    }
}
