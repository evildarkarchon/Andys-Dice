package io.github.evildarkarchon.andysdice.core.expression;

import java.util.Objects;

/**
 * A Modifier: a constant added to or subtracted from the total, such as the {@code 3} in {@code 2d6+3}.
 *
 * @param sign  whether the value is added or subtracted
 * @param value the constant, from 0 to {@link RollExpressionCaps#MAX_MODIFIER}
 * @param span  where the Modifier stands in the input, without its sign
 * @param text  the Modifier exactly as typed, leading zeros included
 */
public record Modifier(Sign sign, int value, Span span, String text) implements Term {

    /**
     * Checks the components.
     *
     * @throws IllegalArgumentException if {@code value} is outside 0 to {@link RollExpressionCaps#MAX_MODIFIER}
     */
    public Modifier {
        Objects.requireNonNull(sign, "sign");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(text, "text");
        if (value < 0 || value > RollExpressionCaps.MAX_MODIFIER) {
            throw new IllegalArgumentException("a Modifier runs from 0 to " + RollExpressionCaps.MAX_MODIFIER
                    + ": " + value);
        }
    }

    @Override
    public String canonical() {
        return Integer.toString(value);
    }
}
