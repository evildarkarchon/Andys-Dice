package io.github.evildarkarchon.andysdice.core.simulation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

/** What goes into the simulator seam and what comes out of it. */
class AttemptTest {

    /** A 1 cm cube that is never cocked: enough to build requests with. */
    private static final DieBody CUBE = new DieBody() {
        @Override
        public List<Vector3> vertices() {
            return List.of(new Vector3(1, 1, 1), new Vector3(-1, -1, -1), new Vector3(1, -1, 1),
                    new Vector3(-1, 1, -1));
        }

        @Override
        public float edgeRadius() {
            return 0.075f;
        }

        @Override
        public boolean isCocked(Orientation orientation) {
            return false;
        }
    };

    private static final TrayMaterials FELT = new TrayMaterials(
            new ContactMaterial(0.45f, 0.15f), new ContactMaterial(0.45f, 0.5f), new ContactMaterial(0.2f, 0.15f),
            0.6f);

    private static final DieStart AT_REST_ABOVE_CENTRE =
            new DieStart(new Pose(new Vector3(0, 5, 0), Orientation.IDENTITY), Vector3.ZERO, Vector3.ZERO);

    private static Recording oneDieOneSample() {
        return new Recording(1, new float[] {0, 1, 0, 0, 0, 0, 1}, 0, List.of());
    }

    @Test
    void theSeamIsInCentimetresUnderEarthGravityWithAFourSecondCap() {
        assertThat(Simulator.GRAVITY).isEqualTo(new Vector3(0, -981, 0));
        assertThat(Simulator.TIME_CAP_SECONDS).isEqualTo(4f);
    }

    @Test
    void endsInExactlyOneOfFourWays() {
        assertThat(Ending.values())
                .containsExactlyInAnyOrder(Ending.AT_REST, Ending.COCKED, Ending.ESCAPED, Ending.TIMED_OUT);
    }

    @Test
    void namesItsCockedDiceExactlyWhenItEndsCocked() {
        assertThat(new Attempt(Ending.COCKED, oneDieOneSample(), Set.of(0)).cockedDice()).containsExactly(0);
        assertThat(new Attempt(Ending.AT_REST, oneDieOneSample(), Set.of()).cockedDice()).isEmpty();
        assertThatThrownBy(() -> new Attempt(Ending.COCKED, oneDieOneSample(), Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Attempt(Ending.TIMED_OUT, oneDieOneSample(), Set.of(0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Attempt(Ending.COCKED, oneDieOneSample(), Set.of(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aRequestCarriesTheDiceTheReleaseTheFrozenDiceAndTheTray() {
        FrozenDie lying = new FrozenDie(CUBE, new Pose(new Vector3(5, 1, 0), Orientation.IDENTITY));
        AttemptRequest request = new AttemptRequest(List.of(CUBE), new Release.Given(List.of(AT_REST_ABOVE_CENTRE)),
                List.of(lying), TrayGeometry.rectangle(15, 10, 3), FELT);
        assertThat(request.dice()).containsExactly(CUBE);
        assertThat(request.frozen()).containsExactly(lying);
        assertThat(request.materials()).isEqualTo(FELT);
    }

    @Test
    void aGivenReleaseStartsEveryThrownDie() {
        assertThatThrownBy(() -> new AttemptRequest(List.of(CUBE, CUBE),
                new Release.Given(List.of(AT_REST_ABOVE_CENTRE)), List.of(), TrayGeometry.rectangle(15, 10, 3),
                FELT)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aDrawnReleaseNeedsOnlyItsGenerator() {
        RandomGenerator generator = RandomGenerator.of("L64X128MixRandom");
        AttemptRequest request = new AttemptRequest(List.of(CUBE, CUBE), new Release.Drawn(generator), List.of(),
                TrayGeometry.rectangle(15, 10, 3), FELT);
        assertThat(request.release()).isEqualTo(new Release.Drawn(generator));
    }

    @Test
    void aRequestThrowsAtLeastOneDie() {
        assertThatThrownBy(() -> new AttemptRequest(List.of(), new Release.Given(List.of()), List.of(),
                TrayGeometry.rectangle(15, 10, 3), FELT)).isInstanceOf(IllegalArgumentException.class);
    }
}
