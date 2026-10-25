package io.github.evildarkarchon.andysdice.core.expression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The evaluator at its seam, {@link RollEvaluator#evaluate(RollExpression, List)}: from a parsed expression and
 * each Die's Face Value Chain to the Roll Result's total and Breakdown.
 */
class RollEvaluatorTest {

    private static RollExpression parse(String input) {
        return ((ParseResult.Valid) RollExpressionParser.parse(input)).expression();
    }

    private static FaceValueChain chain(int... values) {
        return FaceValueChain.of(values);
    }

    private static RollResult evaluate(String input, FaceValueChain... chains) {
        return RollEvaluator.evaluate(parse(input), List.of(chains));
    }

    @Test
    void totalsEveryTermAndBreaksItDownTermByTermAsWritten() {
        RollResult result = evaluate("2d6+1d8+3", chain(3), chain(5), chain(7));

        assertThat(result.total()).isEqualTo(18);
        List<TermResult> terms = result.breakdown().terms();
        assertThat(terms).extracting(TermResult::text).containsExactly("2d6", "1d8", "3");
        assertThat(terms).extracting(TermResult::subtotal).containsExactly(8, 7, 3);
        assertThat(terms.get(0).dice()).containsExactly(
                new DieResult(chain(3), true), new DieResult(chain(5), true));
        assertThat(terms.get(1).dice()).containsExactly(new DieResult(chain(7), true));
        assertThat(terms.get(2).dice()).isEmpty();
    }

    @Test
    void termsAreNeverMergedOrReordered() {
        RollResult result = evaluate("1d6+1d6", chain(2), chain(6));

        assertThat(result.breakdown().terms()).extracting(TermResult::text).containsExactly("1d6", "1d6");
        assertThat(result.breakdown().terms()).extracting(TermResult::subtotal).containsExactly(2, 6);
        assertThat(result.total()).isEqualTo(8);
    }

    @Test
    void theBreakdownKeepsTheTermsAsTypedWithTheirSigns() {
        RollResult result = evaluate(" 2D6 - D4 ", chain(1), chain(2), chain(4));

        assertThat(result.breakdown().terms()).extracting(TermResult::text).containsExactly("2D6", "D4");
        assertThat(result.breakdown().terms()).extracting(t -> t.term().sign())
                .containsExactly(Sign.PLUS, Sign.MINUS);
        assertThat(result.breakdown().terms()).extracting(TermResult::contribution).containsExactly(3, -4);
        assertThat(result.total()).isEqualTo(-1);
    }

    @Test
    void totalsHaveNoFloor() {
        assertThat(evaluate("1d4-5", chain(1)).total()).isEqualTo(-4);
        assertThat(evaluate("-1d4+2", chain(4)).total()).isEqualTo(-2);
        assertThat(evaluate("1d20-1d4", chain(1), chain(4)).total()).isEqualTo(-3);
    }

    @Test
    void aPercentileRollCountsItsValueFrom1To100() {
        RollResult result = evaluate("d%+d100", chain(100), chain(37));

        assertThat(result.total()).isEqualTo(137);
        assertThat(result.breakdown().terms()).extracting(TermResult::subtotal).containsExactly(100, 37);
    }

    @Test
    void onlyTheLastValueOfAFaceValueChainCounts() {
        RollResult result = evaluate("2d6", chain(1, 1, 5), chain(4));

        assertThat(result.total()).isEqualTo(9);
        DieResult rerolled = result.breakdown().terms().getFirst().dice().getFirst();
        assertThat(rerolled.chain().values()).containsExactly(1, 1, 5);
        assertThat(rerolled.value()).isEqualTo(5);
        assertThat(rerolled.rerolled()).isTrue();
        assertThat(result.breakdown().terms().getFirst().dice().get(1).rerolled()).isFalse();
    }

    @Test
    void isAPureFunctionOfItsInputs() {
        RollExpression expression = parse("3d8+2");
        List<FaceValueChain> chains = new ArrayList<>(List.of(chain(8), chain(1), chain(4)));

        RollResult first = RollEvaluator.evaluate(expression, chains);
        RollResult second = RollEvaluator.evaluate(expression, List.copyOf(chains));
        chains.set(0, chain(2));

        assertThat(first).isEqualTo(second);
        assertThat(first.total()).isEqualTo(15);
    }

    @Test
    void needsExactlyOneChainPerRollInTheExpression() {
        RollExpression expression = parse("2d6+d%");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> RollEvaluator.evaluate(expression, List.of(chain(1), chain(2))));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> RollEvaluator.evaluate(expression,
                        List.of(chain(1), chain(2), chain(3), chain(4))));
    }

    @Test
    void rejectsAFaceValueTheDieKindCannotShow() {
        assertThatIllegalArgumentException().isThrownBy(() -> evaluate("1d6", chain(7)));
        assertThatIllegalArgumentException().isThrownBy(() -> evaluate("1d6", chain(0)));
        assertThatIllegalArgumentException().isThrownBy(() -> evaluate("1d6", chain(9, 3)));
        assertThatIllegalArgumentException().isThrownBy(() -> evaluate("d%", chain(101)));
    }

    @Test
    void aFaceValueChainHoldsAtLeastOneValue() {
        assertThatIllegalArgumentException().isThrownBy(FaceValueChain::of);
    }
}
