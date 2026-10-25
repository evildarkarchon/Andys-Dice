package io.github.evildarkarchon.andysdice.core.expression;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Works out the Roll Result of a parsed Roll Expression from the Face Values its Dice showed
 * (docs/roll-expression-grammar.md "Meaning"). It is a pure function: no generator, no state, and the same
 * inputs always give the same result. Choosing the Face Values (ADR-0001) and settling rerolls (ADR-0006)
 * happen before it, so every chain arrives complete and only its last value counts.
 */
public final class RollEvaluator {

    private RollEvaluator() {
    }

    /**
     * Evaluates {@code expression} against one Face Value Chain per roll.
     *
     * @param expression the parsed Roll Expression
     * @param chains     one chain per roll of every Dice Group, in the order written and then by position in the
     *                   group: {@code 2d6+1d8} takes three, the two d6 then the d8. A Percentile Roll is one roll,
     *                   with values 1 to 100 (see {@link PercentileRoll})
     * @return the total and the Breakdown, one term per term as written
     * @throws IllegalArgumentException if there isn't exactly one chain per roll, or a chain holds a value its Die
     *                                  Kind can't show
     */
    public static RollResult evaluate(RollExpression expression, List<FaceValueChain> chains) {
        Objects.requireNonNull(expression, "expression");
        List<FaceValueChain> given = List.copyOf(chains);
        int needed = expression.diceGroups().stream().mapToInt(DiceGroup::count).sum();
        if (given.size() != needed) {
            throw new IllegalArgumentException(expression.canonical() + " needs " + needed
                    + " Face Value Chains, one per roll; got " + given.size());
        }
        List<TermResult> terms = new ArrayList<>();
        int next = 0;
        int total = 0;
        for (Term term : expression.terms()) {
            TermResult result = switch (term) {
                case Modifier modifier -> new TermResult(modifier, modifier.value(), List.of());
                case DiceGroup group -> {
                    TermResult groupResult = group(group, given.subList(next, next + group.count()));
                    next += group.count();
                    yield groupResult;
                }
            };
            terms.add(result);
            total += result.contribution();
        }
        return new RollResult(total, new Breakdown(terms));
    }

    /** Evaluates one Dice Group from its own chains, in their original order in the group. */
    private static TermResult group(DiceGroup group, List<FaceValueChain> chains) {
        for (FaceValueChain chain : chains) {
            for (int value : chain.values()) {
                if (value < 1 || value > group.kind().faces()) {
                    throw new IllegalArgumentException("a " + group.kind().label() + " can't show " + value);
                }
            }
        }
        boolean[] kept = kept(group, chains);
        List<DieResult> dice = new ArrayList<>();
        int subtotal = 0;
        for (int i = 0; i < chains.size(); i++) {
            dice.add(new DieResult(chains.get(i), kept[i]));
            if (kept[i]) {
                subtotal += chains.get(i).last();
            }
        }
        return new TermResult(group, subtotal, dice);
    }

    /**
     * Returns which of the group's rolls count, by original position. Every roll counts until keep rules (#28)
     * exist; they replace this with the group's keep rule over the chains' last values.
     */
    private static boolean[] kept(DiceGroup group, List<FaceValueChain> chains) {
        boolean[] kept = new boolean[chains.size()];
        Arrays.fill(kept, true);
        return kept;
    }
}
