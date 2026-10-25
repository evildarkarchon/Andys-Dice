package io.github.evildarkarchon.andysdice.core.expression;

/**
 * The Roll Expression caps (spec §4, ADR-0014, docs/roll-expression-grammar.md "Caps"). They are contracts and
 * code constants, never player settings.
 */
public final class RollExpressionCaps {

    /** The longest Roll Expression, in characters as typed, spaces included. */
    public static final int MAX_LENGTH = 100;

    /**
     * The most physical Dice one Throw may hold. A Percentile Roll counts 2, and Dice that aren't kept still
     * count because they are still Thrown.
     */
    public static final int MAX_DICE_PER_THROW = 20;

    /** The largest Modifier; the smallest is 0. */
    public static final int MAX_MODIFIER = 999;

    private RollExpressionCaps() {
    }
}
