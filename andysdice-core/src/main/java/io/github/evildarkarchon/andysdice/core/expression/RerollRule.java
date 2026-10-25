package io.github.evildarkarchon.andysdice.core.expression;

import java.util.Objects;
import java.util.stream.IntStream;

/**
 * A Dice Group's reroll clause: Reroll Once ({@code ro}) or Reroll Until ({@code rr}) its condition no longer
 * matches. The condition is one value ({@code ro1}) or one comparison ({@code ro<3}), tested against each roll's
 * Face Value (a Percentile Roll's 1-100 value), with its ordinary meaning.
 *
 * <p>The rule decides when a roll is Rerolled ({@link #rerollsAgain(FaceValueChain)}); the evaluator checks the
 * chains it is given against it, and the Throw planner (#36) uses it to know which Dice go into each Reroll Throw.
 *
 * @param mode       once or until
 * @param comparison how a Face Value is compared with {@code value}; {@link Comparison#EQUAL} for a bare value
 * @param value      the condition's value, at least 0; whether it suits a Die Kind is checked by
 *                   {@link DiceGroup}, which knows the faces
 */
public record RerollRule(Mode mode, Comparison comparison, int value) {

    /** How often a matching roll is Rerolled. */
    public enum Mode {
        /** {@code ro}: a roll whose first Face Value matches is Rerolled once, and the new value stands. */
        ONCE("ro"),
        /** {@code rr}: a roll is Rerolled again for as long as its latest Face Value matches. */
        UNTIL("rr");

        private final String symbol;

        Mode(String symbol) {
            this.symbol = symbol;
        }

        /** Returns how the mode is written: {@code ro} or {@code rr}. */
        public String symbol() {
            return symbol;
        }
    }

    /** How a reroll condition compares a Face Value with its value. */
    public enum Comparison {
        /** A bare value, {@code ro1}: matches that value only. */
        EQUAL(""),
        /** {@code <}. */
        LESS("<"),
        /** {@code <=}. */
        LESS_OR_EQUAL("<="),
        /** {@code >}. */
        GREATER(">"),
        /** {@code >=}. */
        GREATER_OR_EQUAL(">=");

        private final String symbol;

        Comparison(String symbol) {
            this.symbol = symbol;
        }

        /** Returns how the comparison is written: empty for a bare value, else {@code <}, {@code <=} and so on. */
        public String symbol() {
            return symbol;
        }

        /** Returns whether {@code faceValue} compared with {@code value} this way holds. */
        boolean holds(int faceValue, int value) {
            return switch (this) {
                case EQUAL -> faceValue == value;
                case LESS -> faceValue < value;
                case LESS_OR_EQUAL -> faceValue <= value;
                case GREATER -> faceValue > value;
                case GREATER_OR_EQUAL -> faceValue >= value;
            };
        }
    }

    /**
     * Checks the components.
     *
     * @throws IllegalArgumentException if {@code value} is negative, which no condition the grammar can write has
     */
    public RerollRule {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(comparison, "comparison");
        if (value < 0) {
            throw new IllegalArgumentException("a reroll condition's value is at least 0: " + value);
        }
    }

    /** Returns whether the condition matches {@code faceValue}, a Percentile Roll's being its 1-100 value. */
    public boolean matches(int faceValue) {
        return comparison.holds(faceValue, value);
    }

    /** Returns how many of {@code kind}'s values, 1 to its faces (1-100 for {@code d%}), the condition matches. */
    public int matchingFaces(GroupKind kind) {
        Objects.requireNonNull(kind, "kind");
        return (int) IntStream.rangeClosed(1, kind.faces()).filter(this::matches).count();
    }

    /**
     * Returns whether a roll that has shown {@code shown} so far is Rerolled again: for {@code ro}, only if it has
     * shown one value and that value matches; for {@code rr}, whenever its latest value matches. A roll is
     * settled once this is false, and its chain's last value is the one that counts.
     *
     * @param shown the Face Values the roll has shown so far, oldest first
     */
    public boolean rerollsAgain(FaceValueChain shown) {
        Objects.requireNonNull(shown, "shown");
        return switch (mode) {
            case ONCE -> !shown.rerolled() && matches(shown.last());
            case UNTIL -> matches(shown.last());
        };
    }

    /** Returns the clause in canonical notation, such as {@code rr1} or {@code ro<=2}. */
    public String canonical() {
        return mode.symbol() + comparison.symbol() + value;
    }
}
