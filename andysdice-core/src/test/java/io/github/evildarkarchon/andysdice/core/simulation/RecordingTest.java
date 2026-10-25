package io.github.evildarkarchon.andysdice.core.simulation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The Recording's contract (ADR-0003, ADR-0014): every Die's pose per sample at 120 Hz, in centimetres, the
 * moment every Die rested, and the contact events, with nothing that could not have happened in the Attempt.
 */
class RecordingTest {

    /** Two Dice, three samples: die 0 falls 1 cm per sample, die 1 sits still and turned a quarter about Y. */
    private static float[] twoDiceThreeSamples() {
        float s = (float) Math.sqrt(0.5);
        return new float[] {
            0, 3, 0, 0, 0, 0, 1, /* */ 5, 1, 0, 0, s, 0, s,
            0, 2, 0, 0, 0, 0, 1, /* */ 5, 1, 0, 0, s, 0, s,
            0, 1, 0, 0, 0, 0, 1, /* */ 5, 1, 0, 0, s, 0, s,
        };
    }

    private static ContactEvent floorHit(float time) {
        return new ContactEvent(time, ContactBody.die(0), ContactBody.floor(), 0.5f, new Vector3(0, 0, 0));
    }

    @Test
    void samplesAreTakenAt120Hz() {
        assertThat(Recording.SAMPLE_RATE_HZ).isEqualTo(120);
        Recording r = new Recording(2, twoDiceThreeSamples(), 1 / 60f, List.of());
        assertThat(r.samples()).isEqualTo(3);
        assertThat(r.sampleTime(0)).isZero();
        assertThat(r.sampleTime(2)).isEqualTo(2 / 120f);
        assertThat(r.duration()).isEqualTo(2 / 120f);
    }

    @Test
    void returnsEachDiesPoseAtEachSample() {
        Recording r = new Recording(2, twoDiceThreeSamples(), 1 / 60f, List.of());
        assertThat(r.pose(0, 1).position()).isEqualTo(new Vector3(0, 2, 0));
        assertThat(r.pose(1, 2).position()).isEqualTo(new Vector3(5, 1, 0));
        float s = (float) Math.sqrt(0.5);
        assertThat(r.pose(1, 0).orientation()).isEqualTo(new Orientation(0, s, 0, s));
        assertThat(r.finalPose(0).position()).isEqualTo(new Vector3(0, 1, 0));
    }

    @Test
    void doesNotShareItsPoseArrayWithTheCaller() {
        float[] data = twoDiceThreeSamples();
        Recording r = new Recording(2, data, 1 / 60f, List.of());
        data[1] = 99;
        assertThat(r.pose(0, 0).position().y()).isEqualTo(3);
    }

    @Test
    void restsNoLaterThanItsLastSampleAndWithinOneSampleOfIt() {
        float[] data = twoDiceThreeSamples();
        // The rest may fall on an odd 240 Hz step, half a sample before the last sample.
        assertThat(new Recording(2, data, 1.5f / 120, List.of()).restTime()).isEqualTo(1.5f / 120);
        assertThatThrownBy(() -> new Recording(2, data, 3 / 120f, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Recording(2, data, 0.5f / 120, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsPoseDataThatIsNotWholeSamplesOfEveryDie() {
        assertThatThrownBy(() -> new Recording(2, new float[13], 0, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Recording(2, new float[0], 0, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void keepsContactsInTimeOrderWithinTheAttempt() {
        float[] data = twoDiceThreeSamples();
        Recording r = new Recording(2, data, 1 / 60f, List.of(floorHit(0), floorHit(1 / 120f)));
        assertThat(r.contacts()).hasSize(2);
        assertThatThrownBy(() -> new Recording(2, data, 1 / 60f, List.of(floorHit(1 / 120f), floorHit(0))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Recording(2, data, 1 / 60f, List.of(floorHit(3 / 120f))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAContactNamingADieItDoesNotRecord() {
        ContactEvent unknown = new ContactEvent(0, ContactBody.die(2), ContactBody.floor(), 1, new Vector3(0, 0, 0));
        assertThatThrownBy(() -> new Recording(2, twoDiceThreeSamples(), 1 / 60f, List.of(unknown)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
