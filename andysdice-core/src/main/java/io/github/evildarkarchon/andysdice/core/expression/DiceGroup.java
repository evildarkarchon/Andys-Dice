package io.github.evildarkarchon.andysdice.core.expression;

import java.util.Objects;

/**
 * A Dice Group: a count of Dice of one Die Kind, such as {@code 2d6} or {@code d%}.
 *
 * <p>The keep rule and reroll condition the grammar allows on a Dice Group are added by later tickets (#28,
 * #29) as further components here; the evaluator reads them in one place each.
 *
 * @param sign  whether the group's subtotal is added or subtracted
 * @param count how many rolls of {@code kind} the group makes, at least 1 (a missing count is 1)
 * @param kind  the Die Kind rolled
 * @param span  where the group stands in the input, from its count (or {@code d}) to its end, without its sign
 * @param text  the group exactly as typed
 */
public record DiceGroup(Sign sign, int count, GroupKind kind, Span span, String text) implements Term {

    /**
     * Checks the components.
     *
     * @throws IllegalArgumentException if {@code count} is less than 1
     */
    public DiceGroup {
        Objects.requireNonNull(sign, "sign");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(text, "text");
        if (count < 1) {
            throw new IllegalArgumentException("a Dice Group throws at least 1 Die: " + count);
        }
    }

    /** Returns how many physical Dice the group puts in the Tray: a Percentile Roll counts 2. */
    public int physicalDice() {
        return count * kind.physicalDice();
    }

    @Override
    public String canonical() {
        return count + kind.label();
    }
}
