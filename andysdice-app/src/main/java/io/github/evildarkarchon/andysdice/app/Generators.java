package io.github.evildarkarchon.andysdice.app;

import java.security.SecureRandom;
import java.util.Objects;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;

/**
 * The app's three independent generators (spec §5): Face Values (ADR-0001), Releases (ADR-0003) and sound
 * variation (ADR-0012). Each has its own seed, so no physics retry or sound can shift a Face Value. The sound
 * variation generator is held here for the sound wiring (#59).
 *
 * <p>Each generator has one user thread at a time (Face Values and Releases: the physics worker, one Roll at a
 * time; sound variation: whatever #59 mixes on); none is shared.
 *
 * @param faceValues the Face Value generator's source
 * @param releases the Release generator
 * @param soundVariation the sound variation generator
 */
record Generators(RandomGenerator faceValues, RandomGenerator releases, RandomGenerator soundVariation) {

    /** Checks all three are present. */
    Generators {
        Objects.requireNonNull(faceValues, "faceValues");
        Objects.requireNonNull(releases, "releases");
        Objects.requireNonNull(soundVariation, "soundVariation");
    }

    /**
     * Seeds the three generators at start-up, each independently from {@link SecureRandom} (spec §6).
     *
     * @return fresh generators
     */
    static Generators fromSecureRandom() {
        return seededFrom(new SecureRandom());
    }

    /**
     * Seeds the three generators from one seed source: one long each, Face Values first, then Releases, then
     * sound variation. Tests pass a fixed source.
     *
     * @param seeds where the seeds come from
     * @return the generators
     */
    static Generators seededFrom(RandomGenerator seeds) {
        Objects.requireNonNull(seeds, "seeds");
        long faceSeed = seeds.nextLong();
        long releaseSeed = seeds.nextLong();
        long soundSeed = seeds.nextLong();
        return new Generators(new SplittableRandom(faceSeed), new SplittableRandom(releaseSeed),
                new SplittableRandom(soundSeed));
    }
}
