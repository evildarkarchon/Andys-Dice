package io.github.evildarkarchon.andysdice.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import io.github.evildarkarchon.andysdice.core.geometry.DieKind;
import io.github.evildarkarchon.andysdice.core.simulation.Orientation;
import io.github.evildarkarchon.andysdice.core.simulation.Recording;
import io.github.evildarkarchon.andysdice.core.simulation.Vector3;
import java.util.List;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

/**
 * What a Throw's playback draws at a moment, without GL: each Die where its Recording has it at that playback
 * time, interpolated between samples, in the renderer's millimetres, turned by its Numbering Rotation.
 */
class ThrowPlaybackTest {

    private static final double SAMPLE = 1.0 / Recording.SAMPLE_RATE_HZ;
    private static final Orientation QUARTER_ABOUT_Y = Orientation.about(new Vector3(0, 1, 0), (float) (Math.PI / 2));
    private static final Quaterniondc NO_TURN = new Quaterniond();

    /** One Die sliding 2 cm along +z between two samples while turning 90° about y, at rest at the second. */
    private static final Recording SLIDE = new Recording(1, new float[] {
        0, 0.8f, 0, 0, 0, 0, 1,
        0, 0.8f, 2, QUARTER_ABOUT_Y.x(), QUARTER_ABOUT_Y.y(), QUARTER_ABOUT_Y.z(), QUARTER_ABOUT_Y.w(),
    }, (float) SAMPLE, List.of());

    @Test
    void atTheReleaseEachDieIsAtItsFirstSampleInMillimetres() {
        ThrowPlayback playback = new ThrowPlayback(SLIDE, List.of(DieKind.D6), List.of(NO_TURN));

        DiceScene.Die die = single(playback.diceAt(0));

        assertThat(die.kind()).isEqualTo(DieKind.D6);
        assertPoint(die.position(), 0, 8, 0);
    }

    @Test
    void betweenSamplesTheDieIsInterpolatedAtThePlaybackTime() {
        ThrowPlayback playback = new ThrowPlayback(SLIDE, List.of(DieKind.D6), List.of(NO_TURN));

        DiceScene.Die die = single(playback.diceAt(SAMPLE / 2));

        // Halfway: 1 cm along z, turned 45° about y, so model +x points along (cos 45°, 0, -sin 45°).
        assertPoint(die.position(), 0, 8, 10);
        double s = Math.sqrt(0.5);
        assertPoint(die.orientation().transform(new Vector3d(1, 0, 0)), s, 0, -s);
    }

    @Test
    void theNumberingRotationTurnsTheDieInItsModelFrameBeforeItsPose() {
        // A quarter turn about the model's z axis, then the pose's quarter turn about the world's y axis.
        Quaterniondc turn = new Quaterniond().rotationZ(Math.PI / 2);
        ThrowPlayback playback = new ThrowPlayback(SLIDE, List.of(DieKind.D6), List.of(turn));

        DiceScene.Die die = single(playback.diceAt(SAMPLE));

        // Model +x goes to +y under the turn and stays +y under the pose.
        assertPoint(die.orientation().transform(new Vector3d(1, 0, 0)), 0, 1, 0);
    }

    @Test
    void afterTheRecordingEndsTheDieStaysWhereItStopped() {
        ThrowPlayback playback = new ThrowPlayback(SLIDE, List.of(DieKind.D6), List.of(NO_TURN));

        assertPoint(single(playback.diceAt(60)).position(), 0, 8, 20);
    }

    @Test
    void itNeedsOneDieKindAndOneTurnPerRecordedDie() {
        assertThatThrownBy(() -> new ThrowPlayback(SLIDE, List.of(DieKind.D6, DieKind.D6), List.of(NO_TURN)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ThrowPlayback(SLIDE, List.of(DieKind.D6), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static DiceScene.Die single(List<DiceScene.Die> dice) {
        assertThat(dice).hasSize(1);
        return dice.getFirst();
    }

    private static void assertPoint(org.joml.Vector3dc p, double x, double y, double z) {
        assertThat(p.x()).as("x").isCloseTo(x, within(1e-4));
        assertThat(p.y()).as("y").isCloseTo(y, within(1e-4));
        assertThat(p.z()).as("z").isCloseTo(z, within(1e-4));
    }
}
