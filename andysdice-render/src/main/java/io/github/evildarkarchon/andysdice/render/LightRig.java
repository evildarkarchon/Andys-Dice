package io.github.evildarkarchon.andysdice.render;

import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * The light the Tray and its Dice are seen under (ADR-0010, spec Appendix C), chosen separately from the Tray. Rigs
 * are code, not data files. Each has one key light, the only shadow caster (PCSS soft shadows, sized by the light),
 * one fill that casts nothing, and an analytic reflection room ({@code env.glsl}) that glossy, metal and translucent
 * Dice reflect.
 *
 * <p>The numbers are the tray prototype's ({@code SceneSpec.light(Rig)} at {@code 5d07bfa}). World space is y-up and
 * in mm; the camera's default side is -z, so from there world -x is on the right of the screen.
 */
public enum LightRig {

    /**
     * The default: a warm directional key from high on the camera's right (two softboxes over the Tray), a cool fill
     * from the far left, in a neutral grey room whose softboxes the Dice reflect.
     */
    STUDIO(Key.directional(new Vector3d(-0.45, 0.85, -0.35)), new Vector3d(1.0, 0.96, 0.90).mul(3.0), 0.04,
            new Vector3d(0.7, 0.45, 0.5), new Vector3d(0.55, 0.62, 0.75).mul(0.5), 0),

    /**
     * One warm spot hanging overhead, a little to the camera's left, in a dark room with warm walls: the Dice reflect
     * the lamp's bright opening and little else.
     */
    LAMP(Key.spot(new Vector3d(70, 480, -60), new Vector3d(0, 0, 10), 24, 9), new Vector3d(1.0, 0.74, 0.48).mul(4.8),
            15, new Vector3d(-0.3, 1, 0.4), new Vector3d(0.9, 0.6, 0.4).mul(0.06), 1),

    /**
     * Low, cool daylight from the side (the camera's right) through one big window, with a blue sky fill from
     * overhead; the Dice reflect the window and the sky.
     */
    WINDOW(Key.directional(new Vector3d(-0.86, 0.42, 0.28)), new Vector3d(0.86, 0.93, 1.0).mul(3.4), 0.06,
            new Vector3d(0.1, 1, -0.2), new Vector3d(0.35, 0.45, 0.62).mul(0.55), 2);

    /** The rig a new scene, and a Player who never chose, is lit by (ADR-0010). */
    public static final LightRig DEFAULT = STUDIO;

    /**
     * The key light: directional ({@code position} is the unit direction toward it) or a spot hanging at
     * {@code position} in mm, pointing along {@code aim} with its cone's outer and inner half-angles.
     */
    private record Key(boolean spot, Vector3dc position, Vector3dc aim, double outerDegrees, double innerDegrees) {

        static Key directional(Vector3d toward) {
            return new Key(false, toward.normalize(), new Vector3d(), 0, 0);
        }

        static Key spot(Vector3d position, Vector3d aimedAt, double outerDegrees, double innerDegrees) {
            return new Key(true, position, new Vector3d(aimedAt).sub(position).normalize(), outerDegrees,
                    innerDegrees);
        }
    }

    private final Key key;
    private final Vector3dc keyColour;
    private final double lightSize;
    private final Vector3dc fillDirection;
    private final Vector3dc fillColour;
    private final int room;

    LightRig(Key key, Vector3dc keyColour, double lightSize, Vector3d fillDirection, Vector3dc fillColour, int room) {
        this.key = key;
        this.keyColour = keyColour;
        this.lightSize = lightSize;
        this.fillDirection = fillDirection.normalize();
        this.fillColour = fillColour;
        this.room = room;
    }

    /** @return whether the key is a spot (the Lamp) rather than directional */
    boolean spot() {
        return key.spot();
    }

    /**
     * Where the key light is.
     *
     * @return for a directional key, the unit direction toward it; for a spot, its position in mm
     */
    Vector3dc keyPosition() {
        return key.position();
    }

    /** @return a spot's unit direction (zero for a directional key) */
    Vector3dc spotAim() {
        return key.aim();
    }

    /** @return a spot's outer cone half-angle, where its light ends, in degrees (0 for a directional key) */
    double spotOuterDegrees() {
        return key.outerDegrees();
    }

    /** @return a spot's inner cone half-angle, inside which it is at full strength, in degrees */
    double spotInnerDegrees() {
        return key.innerDegrees();
    }

    /**
     * The key's radiance, linear RGB. A spot delivers it at the distance from the spot to the world's origin (about
     * the Tray's middle) and falls off with the square of distance around that.
     *
     * @return the key's colour times its intensity
     */
    Vector3dc keyColour() {
        return keyColour;
    }

    /**
     * How big the key light is, which is what PCSS widens penumbrae by.
     *
     * @return for a directional key, the tangent of its angular radius; for a spot, its radius in mm
     */
    double lightSize() {
        return lightSize;
    }

    /** @return the unit direction toward the fill light */
    Vector3dc fillDirection() {
        return fillDirection;
    }

    /** @return the fill's radiance, linear RGB */
    Vector3dc fillColour() {
        return fillColour;
    }

    /** @return which reflection room {@code env.glsl} draws: 0 studio, 1 the lamp's dark room, 2 the window's */
    int room() {
        return room;
    }
}
