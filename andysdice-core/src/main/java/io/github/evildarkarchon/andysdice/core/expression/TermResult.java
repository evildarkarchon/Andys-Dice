package io.github.evildarkarchon.andysdice.core.expression;

import java.util.List;
import java.util.Objects;

/**
 * One term of the Breakdown: the term as written, its subtotal, and for a Dice Group each roll's result.
 *
 * @param term     the term, which also gives its sign and its text as typed
 * @param subtotal the term's value before its sign: the sum of a Dice Group's kept values, or a Modifier's value
 * @param dice     a Dice Group's rolls in their original order in the group; empty for a Modifier
 */
public record TermResult(Term term, int subtotal, List<DieResult> dice) {

    /** Copies {@code dice}, so the result stays immutable. */
    public TermResult {
        Objects.requireNonNull(term, "term");
        dice = List.copyOf(dice);
    }

    /** Returns the term exactly as typed, without its sign. */
    public String text() {
        return term.text();
    }

    /** Returns what the term adds to the total: its subtotal with its sign applied. */
    public int contribution() {
        return term.sign().apply(subtotal);
    }
}
