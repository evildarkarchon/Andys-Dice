package io.github.evildarkarchon.andysdice.core.expression;

import java.util.Objects;

/**
 * A Dice Group's keep rule: Keep Highest ({@code khN}) or Keep Lowest ({@code klN}) of its rolls. The group's
 * value is the sum of the kept rolls; the rest are still Thrown and shown, marked as not kept.
 *
 * @param direction whether the highest or the lowest values are kept
 * @param count     how many rolls are kept, at least 1 (a missing N means 1); the parser also holds it to at most
 *                  the group's count
 */
public record KeepRule(Direction direction, int count) {

    /** Which end of the group's values a keep rule keeps. */
    public enum Direction {
        /** Keep Highest, {@code kh}. */
        HIGHEST("kh"),
        /** Keep Lowest, {@code kl}. */
        LOWEST("kl");

        private final String symbol;

        Direction(String symbol) {
            this.symbol = symbol;
        }

        /** Returns how the direction is written: {@code kh} or {@code kl}. */
        public String symbol() {
            return symbol;
        }
    }

    /**
     * Checks the components.
     *
     * @throws IllegalArgumentException if {@code count} is less than 1
     */
    public KeepRule {
        Objects.requireNonNull(direction, "direction");
        if (count < 1) {
            throw new IllegalArgumentException("a keep rule keeps at least 1 roll: " + count);
        }
    }

    /** Returns the rule in canonical notation, with its count always written, such as {@code kh1}. */
    public String canonical() {
        return direction.symbol() + count;
    }
}
