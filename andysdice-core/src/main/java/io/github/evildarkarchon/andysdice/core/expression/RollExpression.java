package io.github.evildarkarchon.andysdice.core.expression;

import java.util.List;
import java.util.Objects;

/**
 * A parsed, valid Roll Expression: its terms in the order written. Only {@link RollExpressionParser} makes
 * these from input, so every one obeys the grammar and its caps.
 *
 * @param source the input exactly as typed, which every term's {@link Term#span() span} indexes into
 * @param terms  the terms in the order written, never merged or reordered; at least one is a Dice Group
 */
public record RollExpression(String source, List<Term> terms) {

    /** Copies {@code terms}, so the expression stays immutable. */
    public RollExpression {
        Objects.requireNonNull(source, "source");
        terms = List.copyOf(terms);
    }

    /** Returns the Dice Groups among the terms, in the order written. */
    public List<DiceGroup> diceGroups() {
        return terms.stream().filter(DiceGroup.class::isInstance).map(DiceGroup.class::cast).toList();
    }

    /** Returns how many physical Dice the first Throw holds: a Percentile Roll counts 2. */
    public int physicalDice() {
        return diceGroups().stream().mapToInt(DiceGroup::physicalDice).sum();
    }

    /**
     * Returns the expression in canonical notation: lower case, no spaces, every count written, {@code d%} for a
     * Percentile Roll and a sign only where one is needed, such as {@code 1d20+5} for {@code " D20 + 05 "}.
     */
    public String canonical() {
        StringBuilder out = new StringBuilder();
        for (Term term : terms) {
            if (term.sign() == Sign.MINUS || !out.isEmpty()) {
                out.append(term.sign().symbol());
            }
            out.append(term.canonical());
        }
        return out.toString();
    }
}
