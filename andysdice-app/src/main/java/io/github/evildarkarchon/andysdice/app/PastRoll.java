package io.github.evildarkarchon.andysdice.app;

import io.github.evildarkarchon.andysdice.core.expression.DiceGroup;
import io.github.evildarkarchon.andysdice.core.expression.DieResult;
import io.github.evildarkarchon.andysdice.core.expression.FaceValueChain;
import io.github.evildarkarchon.andysdice.core.expression.ParseResult;
import io.github.evildarkarchon.andysdice.core.expression.RollEvaluator;
import io.github.evildarkarchon.andysdice.core.expression.RollExpressionParser;
import io.github.evildarkarchon.andysdice.core.expression.RollResult;
import io.github.evildarkarchon.andysdice.core.expression.TermResult;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One Roll as the Roll History keeps it: only facts (ADR-0007). When it was revealed, its Roll Expression as
 * written, the Saved Roll's name at the time (if it came from one) and each Die's Face Value Chain in Dice Group
 * order. Its Roll Result is never stored: {@link #result()} recomputes it with the pure evaluator, so a kept Roll can
 * never disagree with it.
 *
 * <p>Every one is valid: its expression parses and its chains evaluate under it, checked when it is made, so a
 * Roll the History shows always has a total and can always be rolled again.
 *
 * @param at the instant its Roll Result was revealed
 * @param expression the Roll Expression as written, without leading or trailing spaces
 * @param savedRollName the Saved Roll's name at the time, if the Roll came from one
 * @param chains one Face Value Chain per roll of every Dice Group, in the order the evaluator takes them
 */
record PastRoll(Instant at, String expression, Optional<String> savedRollName, List<FaceValueChain> chains) {

    /**
     * Checks the facts make a Roll: the expression parses and the chains evaluate under it.
     *
     * @throws IllegalArgumentException if the expression doesn't parse, or the chains don't fit it
     */
    PastRoll {
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(expression, "expression");
        Objects.requireNonNull(savedRollName, "savedRollName");
        chains = List.copyOf(chains);
        // Evaluated once here only to refuse facts that make no Roll; result() works it out again when asked.
        evaluate(expression, chains);
    }

    /**
     * Returns the facts of a Roll whose Roll Result has just been revealed.
     *
     * @param at when it was revealed
     * @param rested the Roll at rest
     * @return the Roll as the History keeps it
     */
    static PastRoll of(Instant at, RollStatus.Rested rested) {
        // The Breakdown holds each Dice Group's rolls in their original order, which is the evaluator's order too.
        List<FaceValueChain> chains = rested.result().breakdown().terms().stream()
                .filter(term -> term.term() instanceof DiceGroup)
                .map(TermResult::dice)
                .flatMap(List::stream)
                .map(DieResult::chain)
                .toList();
        return new PastRoll(at, rested.expression().source().strip(), rested.savedRollName(), chains);
    }

    /**
     * Recomputes the Roll Result from the stored facts.
     *
     * @return the total and the Breakdown, as the evaluator makes them
     */
    RollResult result() {
        return evaluate(expression, chains);
    }

    /**
     * Returns the Roll's total, recomputed.
     *
     * @return the signed total
     */
    int total() {
        return result().total();
    }

    private static RollResult evaluate(String expression, List<FaceValueChain> chains) {
        return switch (RollExpressionParser.parse(expression)) {
            case ParseResult.Invalid invalid -> throw new IllegalArgumentException(
                    "`" + expression + "` doesn't parse: " + invalid.error().message());
            case ParseResult.Valid valid -> RollEvaluator.evaluate(valid.expression(), chains);
        };
    }
}
