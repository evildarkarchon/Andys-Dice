package io.github.evildarkarchon.andysdice.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

/** The app's three generators (spec §5, §6): each seeded on its own from one seed source at start-up. */
class GeneratorsTest {

    /** A seed source that hands out exactly the listed longs, in order. */
    private static final class Seeds implements RandomGenerator {
        final Deque<Long> seeds = new ArrayDeque<>();

        Seeds(long... seeds) {
            for (long seed : seeds) {
                this.seeds.add(seed);
            }
        }

        @Override
        public long nextLong() {
            return seeds.removeFirst();
        }
    }

    private static List<Long> first(RandomGenerator generator, int n) {
        return LongStream.generate(generator::nextLong).limit(n).boxed().toList();
    }

    @Test
    void faceValuesReleasesAndSoundVariationEachTakeTheirOwnSeedInThatOrder() {
        Seeds seeds = new Seeds(11, 22, 33);

        Generators generators = Generators.seededFrom(seeds);

        assertThat(first(generators.faceValues(), 4)).isEqualTo(first(new SplittableRandom(11), 4));
        assertThat(first(generators.releases(), 4)).isEqualTo(first(new SplittableRandom(22), 4));
        assertThat(first(generators.soundVariation(), 4)).isEqualTo(first(new SplittableRandom(33), 4));
        assertThat(seeds.seeds).isEmpty();
    }

    @Test
    void drawingFromOneGeneratorLeavesTheOthersUnmoved() {
        Generators generators = Generators.seededFrom(new Seeds(11, 22, 33));

        first(generators.faceValues(), 100);
        first(generators.soundVariation(), 100);

        assertThat(first(generators.releases(), 4)).isEqualTo(first(new SplittableRandom(22), 4));
    }

    @Test
    void secureRandomSeedsDifferEveryStartUp() {
        Generators one = Generators.fromSecureRandom();
        Generators two = Generators.fromSecureRandom();

        assertThat(first(one.faceValues(), 4)).isNotEqualTo(first(two.faceValues(), 4))
                .isNotEqualTo(first(one.releases(), 4));
        assertThat(first(one.releases(), 4)).isNotEqualTo(first(one.soundVariation(), 4));
    }
}
