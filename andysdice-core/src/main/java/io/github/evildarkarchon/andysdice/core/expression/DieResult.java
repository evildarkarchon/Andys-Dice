package io.github.evildarkarchon.andysdice.core.expression;

import java.util.Objects;

/**
 * One roll of a Dice Group in the Breakdown: its Face Value Chain and whether its Dice Group kept it.
 *
 * @param chain every Face Value it showed, oldest first
 * @param kept  whether its value counts towards its Dice Group's subtotal: false only for a roll its group's keep
 *              rule left out, which the Breakdown marks as not kept; "rerolled" is a separate mark from "unkept"
 *              (ADR-0006)
 */
public record DieResult(FaceValueChain chain, boolean kept) {

    /** Checks that the chain is present. */
    public DieResult {
        Objects.requireNonNull(chain, "chain");
    }

    /** Returns the Face Value that counts: the last in the chain. */
    public int value() {
        return chain.last();
    }

    /** Returns whether the roll was Rerolled. */
    public boolean rerolled() {
        return chain.rerolled();
    }
}
