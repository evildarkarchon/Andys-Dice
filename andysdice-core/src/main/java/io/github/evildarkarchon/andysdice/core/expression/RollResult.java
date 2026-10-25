package io.github.evildarkarchon.andysdice.core.expression;

import java.util.Objects;

/**
 * The Roll Result: the total and the Breakdown that explains it.
 *
 * @param total     the signed sum of every term's subtotal, with no floor ({@code 1d4-5} can total -4)
 * @param breakdown each term as written with its subtotal and its Dice
 */
public record RollResult(int total, Breakdown breakdown) {

    /** Checks that the Breakdown is present. */
    public RollResult {
        Objects.requireNonNull(breakdown, "breakdown");
    }
}
