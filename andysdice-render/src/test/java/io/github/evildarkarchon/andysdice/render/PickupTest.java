package io.github.evildarkarchon.andysdice.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.github.evildarkarchon.andysdice.core.geometry.DieKind;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.junit.jupiter.api.Test;

/**
 * The reroll pickup without GL (ADR-0010, spec §3.1 step 4): a Die going back glows in pulses where it lies, then
 * lifts out along an arc to its Release pose, and with reduced motion on the glow is steady and the lift, still
 * on its arc, plays at ×2 (spec §3.2).
 */
class PickupTest {

    private static final DiceScene.Die LYING = new DiceScene.Die(DieKind.D6, new Vector3d(40, 8, -20),
            new Quaterniond());
    private static final DiceScene.Die RELEASE = new DiceScene.Die(DieKind.D6, new Vector3d(-60, 80, -90),
            new Quaterniond().rotationX(Math.PI / 2));
    /** Where in its lift (0 to 1) the arc is highest: sin(π × 1.3u) peaks at u = 1 / 2.6. */
    private static final double PEAK = 1 / 2.6;

    @Test
    void thePickupIsAGlowThenALiftAboutOnePointThreeSecondsLong() {
        assertThat(Pickup.GLOW_SECONDS).isEqualTo(0.6);
        assertThat(Pickup.LIFT_SECONDS).isEqualTo(0.7);
        assertThat(Pickup.SECONDS).isCloseTo(1.3, within(1e-12));
        assertThat(Pickup.REDUCED_MOTION_LIFT_SPEED).isEqualTo(2);
    }

    @Test
    void theDieGlowsInPulsesWhereItLiesBeforeItLifts() {
        // One and a half pulses over the glow: dark, bright, dark, bright.
        assertThat(at(0).glow()).isCloseTo(0, within(1e-9));
        assertThat(at(0.2).glow()).isCloseTo(1, within(1e-9));
        assertThat(at(0.4).glow()).isCloseTo(0, within(1e-9));
        assertThat(at(0.6 - 1e-9).glow()).isCloseTo(1, within(1e-6));
        for (double t = 0; t < Pickup.GLOW_SECONDS; t += 0.01) {
            DiceScene.Die die = at(t);
            assertThat(die.position()).as("at %.2f s", t).isEqualTo(LYING.position());
            assertThat(die.orientation()).isEqualTo(LYING.orientation());
            assertThat(die.kind()).isEqualTo(DieKind.D6);
        }
    }

    @Test
    void theLiftArcsFromWhereTheDieLiesToItsReleasePoseFadingTheGlow() {
        DiceScene.Die start = at(Pickup.GLOW_SECONDS);
        assertThat(start.position().distance(LYING.position())).isLessThan(1e-9);
        assertThat(start.glow()).isGreaterThan(0);

        DiceScene.Die end = at(Pickup.SECONDS);
        assertThat(end.position()).isEqualTo(RELEASE.position());
        assertThat(end.orientation().equals(RELEASE.orientation(), 1e-12)).isTrue();
        assertThat(end.glow()).isZero();

        double peakHeight = 0;
        double glow = start.glow();
        for (double u = 0; u <= 1; u += 0.01) {
            DiceScene.Die die = at(Pickup.GLOW_SECONDS + u * Pickup.LIFT_SECONDS);
            assertThat(die.glow()).as("the glow fades through the lift").isLessThanOrEqualTo(glow);
            glow = die.glow();
            double above = aboveStraightPath(die.position());
            assertThat(above).as("never below the straight path, at u %.2f", u).isGreaterThan(-1e-9);
            peakHeight = Math.max(peakHeight, above);
        }
        // Up out of the Tray first: the hand-like arc of the tray prototype, 55 mm over the straight path.
        assertThat(aboveStraightPath(at(Pickup.GLOW_SECONDS + PEAK * Pickup.LIFT_SECONDS).position()))
                .isCloseTo(55, within(1e-6));
        // ...and never higher anywhere else (sampled every 1% of the lift, so a hair short of the exact peak).
        assertThat(peakHeight).isLessThanOrEqualTo(55 + 1e-9).isCloseTo(55, within(0.01));
    }

    @Test
    void theLiftTurnsTheDieTheShortWayFromItsRestToItsRelease() {
        DiceScene.Die mid = at(Pickup.GLOW_SECONDS + Pickup.LIFT_SECONDS / 2);
        double whole = angle(LYING.orientation(), RELEASE.orientation());
        // Halfway through the lift is halfway along the smoothstep, so halfway round.
        assertThat(angle(LYING.orientation(), mid.orientation())).isCloseTo(whole / 2, within(1e-9));
        assertThat(angle(mid.orientation(), RELEASE.orientation())).isCloseTo(whole / 2, within(1e-9));

        // The same turn written as the opposite quaternion takes the same short way.
        DiceScene.Die flipped = new DiceScene.Die(DieKind.D6, RELEASE.position(),
                new Quaterniond(RELEASE.orientation()).mul(-1));
        DiceScene.Die midFlipped = Pickup.at(LYING, flipped, Pickup.GLOW_SECONDS + Pickup.LIFT_SECONDS / 2, false);
        assertThat(angle(LYING.orientation(), midFlipped.orientation())).isCloseTo(whole / 2, within(1e-9));
    }

    @Test
    void outsideThePickupTheDieLiesUnlitBeforeItAndIsAtItsReleaseAfter() {
        assertThat(at(-1).position()).isEqualTo(LYING.position());
        assertThat(at(-1).glow()).isZero();
        assertThat(at(5).position()).isEqualTo(RELEASE.position());
        assertThat(at(5).glow()).isZero();
    }

    @Test
    void withReducedMotionTheGlowIsASteadyHighlight() {
        double steady = Pickup.at(LYING, RELEASE, 0, true).glow();
        assertThat(steady).isGreaterThan(0);
        double liftStart = Pickup.SECONDS - Pickup.LIFT_SECONDS / 2;
        for (double t = 0; t < liftStart; t += 0.01) {
            DiceScene.Die die = Pickup.at(LYING, RELEASE, t, true);
            assertThat(die.glow()).as("at %.2f s", t).isEqualTo(steady);
            assertThat(die.position()).isEqualTo(LYING.position());
        }
        // No step into the lift: it fades from the same highlight.
        assertThat(Pickup.at(LYING, RELEASE, liftStart, true).glow()).isCloseTo(steady, within(1e-9));
    }

    @Test
    void withReducedMotionTheLiftKeepsItsArcButPlaysAtTwiceTheSpeed() {
        double lift = Pickup.LIFT_SECONDS / 2;
        double liftStart = Pickup.SECONDS - lift;
        assertThat(Pickup.at(LYING, RELEASE, liftStart, true).position().distance(LYING.position()))
                .isLessThan(1e-9);
        assertThat(aboveStraightPath(Pickup.at(LYING, RELEASE, liftStart + PEAK * lift, true).position()))
                .isCloseTo(55, within(1e-6));
        // It still reaches the Release pose as the Reroll Throw starts, so the timeline is the same either way.
        DiceScene.Die end = Pickup.at(LYING, RELEASE, Pickup.SECONDS, true);
        assertThat(end.position()).isEqualTo(RELEASE.position());
        assertThat(end.glow()).isZero();
        // Each point of the arc is reached in half the time.
        for (double u = 0; u <= 1; u += 0.05) {
            Vector3dc normal = at(Pickup.GLOW_SECONDS + u * Pickup.LIFT_SECONDS).position();
            Vector3dc reduced = Pickup.at(LYING, RELEASE, liftStart + u * lift, true).position();
            assertThat(reduced.distance(normal)).as("at u %.2f", u).isLessThan(1e-9);
        }
    }

    private static DiceScene.Die at(double t) {
        return Pickup.at(LYING, RELEASE, t, false);
    }

    /** How far a point is above the straight line from the lying to the release position, at its x. */
    private static double aboveStraightPath(Vector3dc p) {
        double s = (p.x() - LYING.position().x()) / (RELEASE.position().x() - LYING.position().x());
        return p.y() - (LYING.position().y() + s * (RELEASE.position().y() - LYING.position().y()));
    }

    /** The angle between two orientations, in radians, the short way. */
    private static double angle(org.joml.Quaterniondc a, org.joml.Quaterniondc b) {
        return 2 * Math.acos(Math.min(1, Math.abs(a.dot(b))));
    }
}
