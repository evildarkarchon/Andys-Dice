package io.github.evildarkarchon.andysdice.core.expression;

import java.util.List;

/**
 * The part of a Roll Result that shows each term as written, its subtotal and each Die's Face Value Chain.
 *
 * @param terms one result per term of the Roll Expression, in the order written; never merged or reordered
 */
public record Breakdown(List<TermResult> terms) {

    /** Copies {@code terms}, so the Breakdown stays immutable. */
    public Breakdown {
        terms = List.copyOf(terms);
    }
}
