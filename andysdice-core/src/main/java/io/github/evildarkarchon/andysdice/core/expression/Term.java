package io.github.evildarkarchon.andysdice.core.expression;

/**
 * One term of a Roll Expression, in the order written: a {@link DiceGroup} or a {@link Modifier}. Terms are
 * never merged or reordered, so {@code 1d6+1d6} is two terms.
 */
public sealed interface Term permits DiceGroup, Modifier {

    /** Returns whether the term is added or subtracted. */
    Sign sign();

    /** Returns where the term stands in the input, without its sign. */
    Span span();

    /** Returns the term exactly as typed (case kept), without its sign; the Breakdown shows it. */
    String text();

    /** Returns the term in canonical notation without its sign, such as {@code 2d6} for {@code 02D6}. */
    String canonical();
}
