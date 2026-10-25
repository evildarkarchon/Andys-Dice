package io.github.evildarkarchon.andysdice.render;

import java.util.Objects;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;

/**
 * The reroll pickup (#47, ADR-0010): before a Reroll Throw or Cocked Rethrow, each Die going back glows where it
 * lies for {@value #GLOW_SECONDS} s, so the Player sees which Dice go back, then lifts out along an arc to its
 * Release pose over {@value #LIFT_SECONDS} s, and the Throw's Recording starts from there. A Cocked Rethrow's
 * pickup is the same as a Reroll Throw's (spec §3.2). It makes no sound: only contacts do (spec §3.4).
 *
 * <p>From the tray prototype's chosen {@code GLOW_LIFT}: the glow pulses one and a half times; the lift eases its
 * position and its turn with a smoothstep and rises {@value #ARC_HEIGHT_MM} mm over the straight path, up out of
 * the Tray first; and the glow fades out through the lift.
 *
 * <p>With reduced motion on (spec §3.2), the glow is a steady highlight rather than a pulse, and the lift keeps its
 * arc but plays at ×{@value #REDUCED_MOTION_LIFT_SPEED}. The lift still ends as the Throw starts, so the steady
 * glow holds for what the lift no longer takes, and the chain's timeline is the same either way: a change of the
 * system setting during a Roll applies at once, as the camera's does, without moving any Throw.
 *
 * <p>Pure: every read is worked out from the time given, which is playback-clock time, so Fast-forward speeds the
 * pickup up with everything else. Safe on any thread.
 */
public final class Pickup {

    /** How long a Die going back glows where it lies before it lifts. Tunable (spec Appendix: ≈ 0.6 s). */
    public static final double GLOW_SECONDS = 0.6;
    /** How long the lift from where it lies to its Release pose takes. Tunable (spec Appendix: ≈ 0.7 s). */
    public static final double LIFT_SECONDS = 0.7;
    /** The whole pickup: the glow, then the lift. */
    public static final double SECONDS = GLOW_SECONDS + LIFT_SECONDS;
    /** How much faster the lift plays with reduced motion on. Tunable (spec Appendix: ×2). */
    public static final double REDUCED_MOTION_LIFT_SPEED = 2;
    /** How far the arc rises over the straight path from where the Die lies to its Release, in mm. */
    public static final double ARC_HEIGHT_MM = 55;

    /** The glow's pulses over {@link #GLOW_SECONDS}: one and a half, so it is at its brightest as the lift starts. */
    private static final double PULSES = 1.5;
    /** The glow as the lift starts, fading to none by the Release. */
    private static final double LIFT_GLOW = 0.4;
    /**
     * Reduced motion's steady highlight: the level the lift fades from, so there is no step where the lift
     * starts, just one highlight that holds and then fades.
     */
    private static final double STEADY_GLOW = LIFT_GLOW;
    /**
     * The arc's rise is a half sine over the first 1 / 1.3 of the lift, so the Die is up out of the Tray before
     * it travels far, and level with the straight path for the last part, coming down onto the Release.
     */
    private static final double RISE_RATE = 1.3;

    private Pickup() {}

    /**
     * Returns a Die going back as it is drawn at a moment of its pickup.
     *
     * @param lying the Die where it lies, as the last Throw that moved it left it
     * @param release the same Die at its Release pose for the coming Throw (its first Recording sample, turned by
     *                its new Numbering Rotation)
     * @param time playback seconds since the pickup started; before 0 the Die lies unlit, from {@link #SECONDS}
     *             on it is at its Release pose
     * @param reducedMotion whether reduced motion is on
     * @return the Die to draw, with its glow
     */
    public static DiceScene.Die at(DiceScene.Die lying, DiceScene.Die release, double time, boolean reducedMotion) {
        Objects.requireNonNull(lying, "lying");
        Objects.requireNonNull(release, "release");
        if (time < 0) {
            return glowing(lying, 0);
        }
        if (time >= SECONDS) {
            return glowing(release, 0);
        }
        double lift = reducedMotion ? LIFT_SECONDS / REDUCED_MOTION_LIFT_SPEED : LIFT_SECONDS;
        // Not SECONDS - lift: that rounds a hair short of GLOW_SECONDS, which would start the lift a hair early.
        double liftStart = GLOW_SECONDS + (LIFT_SECONDS - lift);
        if (time < liftStart) {
            double pulse = 0.5 - 0.5 * Math.cos(time / GLOW_SECONDS * 2 * Math.PI * PULSES);
            return glowing(lying, reducedMotion ? STEADY_GLOW : pulse);
        }
        double u = (time - liftStart) / lift;
        return arc(lying, release, u, LIFT_GLOW * (1 - u));
    }

    /** The same Die at the same pose with another glow. */
    private static DiceScene.Die glowing(DiceScene.Die die, double glow) {
        return new DiceScene.Die(die.kind(), die.position(), die.orientation(), glow);
    }

    /**
     * A hand-like lift (the tray prototype's {@code arc}): up out of the Tray first, over to the Release, eased at
     * both ends.
     *
     * @param u how far through the lift, 0 to 1
     */
    private static DiceScene.Die arc(DiceScene.Die from, DiceScene.Die to, double u, double glow) {
        double e = u * u * (3 - 2 * u);
        Vector3d position = new Vector3d(from.position()).lerp(to.position(), e);
        position.y += Math.sin(Math.PI * Math.min(1, u * RISE_RATE)) * ARC_HEIGHT_MM;
        // q and -q are the same turn; slerp toward the one on the near side, so the Die turns the short way.
        Quaterniondc target = to.orientation();
        if (from.orientation().dot(target) < 0) {
            target = new Quaterniond(target).mul(-1);
        }
        Quaterniond orientation = new Quaterniond(from.orientation()).slerp(target, e);
        return new DiceScene.Die(from.kind(), position, orientation, glow);
    }
}
