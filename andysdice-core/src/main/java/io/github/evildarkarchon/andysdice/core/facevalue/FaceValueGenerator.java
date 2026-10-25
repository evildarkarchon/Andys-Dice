package io.github.evildarkarchon.andysdice.core.facevalue;

import io.github.evildarkarchon.andysdice.core.geometry.DieKind;
import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * The Face Value generator: it chooses every Face Value up front, before any Throw is simulated (ADR-0001), so
 * fairness is the injected generator's uniformity. Physics never decides a value; a Numbering Rotation later
 * puts the chosen Numeral on whatever face landed.
 *
 * <p>The app injects its own Face Value generator, seeded independently of the Release and sound generators
 * (spec §5), so neither a physics retry nor a sound can shift a Face Value. Tests inject fixed seeds.
 *
 * <p>Not thread-safe: like the {@link RandomGenerator} it draws from, use it from one thread at a time.
 */
public final class FaceValueGenerator {

    private final RandomGenerator random;

    /**
     * Creates a generator over a source of randomness.
     *
     * @param random the Face Value source; every draw comes from it and from nothing else
     */
    public FaceValueGenerator(RandomGenerator random) {
        this.random = Objects.requireNonNull(random, "random");
    }

    /**
     * Chooses one Die's Face Value: one uniform draw from 1 to the Die's face count. {@link FaceValues#face} gives
     * the face whose Numeral shows it.
     *
     * @param kind any Die Kind but the percentile d10, which is only rolled in a {@link #percentileRoll()}
     * @return the Face Value, from 1 to {@link FaceValues#highest(DieKind)}
     * @throws IllegalArgumentException if {@code kind} is the percentile d10
     */
    public int faceValue(DieKind kind) {
        return random.nextInt(1, FaceValues.highest(kind) + 1);
    }

    /**
     * Chooses one Percentile Roll: a single uniform draw from 1 to 100, which
     * {@link FaceValues#percentileTensFace} and {@link FaceValues#percentileUnitsFace} split across the percentile
     * d10 and the d10. Drawing the value once, rather than each Die apart, keeps the value and its two Dice one
     * draw of the generator.
     *
     * @return the value, from 1 to 100
     */
    public int percentileRoll() {
        return random.nextInt(1, FaceValues.PERCENTILE_HIGHEST + 1);
    }
}
