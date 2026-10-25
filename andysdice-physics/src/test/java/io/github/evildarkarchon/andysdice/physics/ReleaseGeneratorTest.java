package io.github.evildarkarchon.andysdice.physics;

import static io.github.evildarkarchon.andysdice.physics.Scenarios.BOX;
import static io.github.evildarkarchon.andysdice.physics.Scenarios.CAP;
import static io.github.evildarkarchon.andysdice.physics.Scenarios.FELT;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.evildarkarchon.andysdice.core.simulation.AttemptRequest;
import io.github.evildarkarchon.andysdice.core.simulation.Recording;
import io.github.evildarkarchon.andysdice.core.simulation.Release;
import java.util.List;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

/**
 * The Release draws only from the Release generator, and Settle Nudges draw from no generator (ADR-0003,
 * ADR-0013): an Attempt advances its generator by exactly the Release's draws, however many nudges it gives.
 */
class ReleaseGeneratorTest {

    /** A generator that counts how many numbers have been drawn from it. */
    private static final class Counting implements RandomGenerator {
        private final SplittableRandom source;
        private int draws;

        Counting(long seed) {
            source = new SplittableRandom(seed);
        }

        @Override
        public long nextLong() {
            draws++;
            return source.nextLong();
        }
    }

    private static AttemptRequest cap(RandomGenerator generator) {
        return new AttemptRequest(CAP, new Release.Drawn(generator), List.of(), BOX, FELT);
    }

    @Test
    void anAttemptDrawsExactlyItsReleaseFromTheGeneratorWhateverItsNudges() {
        int nudges = 0;
        for (long seed = 1; seed <= 4; seed++) {
            Counting thrown = new Counting(seed);
            Counting released = new Counting(seed);

            JoltSimulator.Run run = Scenarios.run(cap(thrown));
            DrawnRelease.draw(released, CAP.size(), BOX);

            assertThat(thrown.draws).as("seed %s", seed).isEqualTo(released.draws).isPositive();
            assertThat(thrown.nextLong()).isEqualTo(released.nextLong());
            nudges += run.nudges().stream().mapToInt(Integer::intValue).sum();
        }
        // The draws matched in Throws that were nudged, so the nudges drew nothing.
        assertThat(nudges).isPositive();
    }

    @Test
    void theSameReleaseSeedThrowsTheSameRecording() {
        // Nothing but the Release generator varies an Attempt: no hidden generator, in the Release or a nudge.
        Recording first = Scenarios.simulate(cap(new SplittableRandom(77))).recording();
        Recording second = Scenarios.simulate(cap(new SplittableRandom(77))).recording();

        assertThat(second.samples()).isEqualTo(first.samples());
        assertThat(second.restTime()).isEqualTo(first.restTime());
        for (int die = 0; die < CAP.size(); die++) {
            for (int s = 0; s < first.samples(); s++) {
                assertThat(second.pose(die, s)).isEqualTo(first.pose(die, s));
            }
        }
        assertThat(second.contacts()).isEqualTo(first.contacts());
    }
}
