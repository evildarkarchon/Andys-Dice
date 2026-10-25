package io.github.evildarkarchon.andysdice.core.expression;

import java.util.Arrays;
import java.util.List;

/**
 * Every Face Value one roll of a Dice Group showed during a Roll, oldest first; only the last counts. It holds
 * more than one value only when the roll was Rerolled (ADR-0006).
 *
 * <p>A Percentile Roll's chain holds its values from 1 to 100, not its two Dice's faces: see
 * {@link PercentileRoll} for how the tens and units faces read.
 *
 * @param values the Face Values, oldest first; never empty
 */
public record FaceValueChain(List<Integer> values) {

    /**
     * Copies {@code values}, so the chain stays immutable.
     *
     * @throws IllegalArgumentException if {@code values} is empty
     */
    public FaceValueChain {
        values = List.copyOf(values);
        if (values.isEmpty()) {
            throw new IllegalArgumentException("a Face Value Chain holds at least one Face Value");
        }
    }

    /**
     * Returns the chain of {@code values}, oldest first.
     *
     * @throws IllegalArgumentException if no value is given
     */
    public static FaceValueChain of(int... values) {
        return new FaceValueChain(Arrays.stream(values).boxed().toList());
    }

    /** Returns the Face Value that counts: the last one shown. */
    public int last() {
        return values.getLast();
    }

    /** Returns whether the roll was Rerolled, that is whether it showed more than one Face Value. */
    public boolean rerolled() {
        return values.size() > 1;
    }
}
