package io.github.evildarkarchon.andysdice.core.expression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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
        RollResult result = evaluate("2d6rr1", chain(1, 1, 5), chain(4));

        assertThat(result.total()).isEqualTo(9);
        DieResult rerolled = result.breakdown().terms().getFirst().dice().getFirst();
        assertThat(rerolled.chain().values()).containsExactly(1, 1, 5);
        assertThat(rerolled.value()).isEqualTo(5);
        assertThat(rerolled.rerolled()).isTrue();
        assertThat(result.breakdown().terms().getFirst().dice().get(1).rerolled()).isFalse();
    }

    @Test
    void keepHighestSumsTheHighestNAndMarksEveryUnkeptDie() {
        RollResult result = evaluate("4d6kh3+2", chain(6), chain(5), chain(6), chain(1));

        assertThat(result.total()).isEqualTo(19);
        TermResult group = result.breakdown().terms().getFirst();
        assertThat(group.subtotal()).isEqualTo(17);
        assertThat(group.dice()).extracting(DieResult::kept).containsExactly(true, true, true, false);
        assertThat(group.dice()).extracting(DieResult::value).containsExactly(6, 5, 6, 1);
    }

    @Test
    void keepLowestSumsTheLowestN() {
        RollResult result = evaluate("3d8kl2", chain(8), chain(2), chain(5));

        assertThat(result.total()).isEqualTo(7);
        assertThat(result.breakdown().terms().getFirst().dice()).extracting(DieResult::kept)
                .containsExactly(false, true, true);
    }

    /** Ties between kept and unkept values go to the Die's original position in its Dice Group: earlier wins. */
    @Test
    void tiesAreBrokenByOriginalPositionInTheDiceGroup() {
        assertThat(evaluate("3d6kh1", chain(4), chain(4), chain(4)).breakdown().terms().getFirst().dice())
                .extracting(DieResult::kept).containsExactly(true, false, false);
        assertThat(evaluate("4d6kh2", chain(2), chain(5), chain(3), chain(5)).breakdown().terms().getFirst().dice())
                .extracting(DieResult::kept).containsExactly(false, true, false, true);
        assertThat(evaluate("4d6kl2", chain(3), chain(1), chain(3), chain(3)).breakdown().terms().getFirst().dice())
                .extracting(DieResult::kept).containsExactly(true, true, false, false);
    }

    /**
     * Only a chain's last value is compared, and a Rerolled Die keeps its original position, whichever Throw it
     * last landed in.
     */
    @Test
    void keepsCompareLastValuesAndARerolledDieKeepsItsPosition() {
        TermResult rerolledLow = evaluate("2d6ro>5kh1", chain(6, 2), chain(3)).breakdown().terms().getFirst();
        assertThat(rerolledLow.subtotal()).isEqualTo(3);
        assertThat(rerolledLow.dice()).extracting(DieResult::kept).containsExactly(false, true);

        TermResult tie = evaluate("2d6ro1kh1", chain(1, 4), chain(4)).breakdown().terms().getFirst();
        assertThat(tie.dice()).extracting(DieResult::kept).containsExactly(true, false);
    }

    /** Keep rules apply to every Die Kind, Percentile Rolls included, where the 1-100 value is compared. */
    @ParameterizedTest(name = "{0}")
    @CsvSource({"2d4kh1, 4", "2d6kh1, 6", "2d8kh1, 8", "2d10kh1, 10", "2d12kh1, 12", "2d20kh1, 20", "2d%kh1, 100"})
    void keepRulesApplyToEveryDieKind(String input, int faces) {
        TermResult highest = evaluate(input, chain(faces - 1), chain(faces)).breakdown().terms().getFirst();
        assertThat(highest.subtotal()).isEqualTo(faces);
        assertThat(highest.dice()).extracting(DieResult::kept).containsExactly(false, true);

        TermResult lowest = evaluate(input.replace("kh", "kl"), chain(faces - 1), chain(faces))
                .breakdown().terms().getFirst();
        assertThat(lowest.subtotal()).isEqualTo(faces - 1);
        assertThat(lowest.dice()).extracting(DieResult::kept).containsExactly(true, false);
    }

    /** Advantage's 1d20 is rolled as 2d20 keeping the higher; the Breakdown still shows it as typed. */
    @Test
    void advantageKeepsTheHigherOfTwoD20AndDisadvantageTheLower() {
        RollResult advantage = evaluate("1d20+5 adv", chain(8), chain(15));
        assertThat(advantage.total()).isEqualTo(20);
        TermResult d20 = advantage.breakdown().terms().getFirst();
        assertThat(d20.text()).isEqualTo("1d20");
        assertThat(((DiceGroup) d20.term()).advantage()).contains(Advantage.ADVANTAGE);
        assertThat(d20.dice()).extracting(DieResult::kept).containsExactly(false, true);

        RollResult disadvantage = evaluate("1d20+5 dis", chain(8), chain(15));
        assertThat(disadvantage.total()).isEqualTo(13);
        assertThat(disadvantage.breakdown().terms().getFirst().dice()).extracting(DieResult::kept)
                .containsExactly(true, false);

        assertThat(evaluate("1d20 adv", chain(11), chain(11)).breakdown().terms().getFirst().dice())
                .extracting(DieResult::kept).containsExactly(true, false);
    }

    @Test
    void anAdvantageRollNeedsTwoChainsForItsD20() {
        assertThatIllegalArgumentException().isThrownBy(() -> evaluate("1d20 adv", chain(11)));
    }

    /**
     * Rerolls are settled before keeps: the keep rule compares each chain's last value, and a Rerolled Die is
     * marked rerolled whether or not it is kept.
     */
    @Test
    void rerollsAreSettledBeforeKeeps() {
        RollResult result = evaluate("4d6rr1kh3+2", chain(1, 1, 5), chain(6), chain(2), chain(1, 3));

        assertThat(result.total()).isEqualTo(16);
        TermResult group = result.breakdown().terms().getFirst();
        assertThat(group.subtotal()).isEqualTo(14);
        assertThat(group.dice()).extracting(DieResult::value).containsExactly(5, 6, 2, 3);
        assertThat(group.dice()).extracting(DieResult::kept).containsExactly(true, true, false, true);
        assertThat(group.dice()).extracting(DieResult::rerolled).containsExactly(true, false, false, true);
        assertThat(group.dice().getFirst().chain().values()).containsExactly(1, 1, 5);
    }

    /** {@code ro} rerolls once and the new value stands, even if it matches the condition again. */
    @Test
    void rerollOnceKeepsTheSecondValueWhateverItIs() {
        RollResult result = evaluate("2d6ro<=2", chain(1, 2), chain(4));

        assertThat(result.total()).isEqualTo(6);
        assertThat(result.breakdown().terms().getFirst().dice()).extracting(DieResult::rerolled)
                .containsExactly(true, false);
    }

    /** A Percentile Roll's reroll tests its 1-100 value. */
    @Test
    void aPercentileRerollTestsTheValueFrom1To100() {
        assertThat(evaluate("d%rr<=50", chain(37, 50, 81)).total()).isEqualTo(81);
        assertThatIllegalArgumentException().isThrownBy(() -> evaluate("d%rr<=50", chain(37, 51, 81)));
    }

    /** {@code 1d20ro1 adv} evaluates exactly like {@code 2d20ro1kh1}: the reroll applies to both d20s. */
    @Test
    void aRerollOnAnAdvantageD20CarriesOver() {
        FaceValueChain[] chains = {chain(1, 7), chain(12)};
        RollResult advantage = evaluate("1d20ro1 adv", chains);
        RollResult written = evaluate("2d20ro1kh1", chains);

        assertThat(advantage.total()).isEqualTo(12).isEqualTo(written.total());
        assertThat(advantage.breakdown().terms().getFirst().dice())
                .isEqualTo(written.breakdown().terms().getFirst().dice());
        assertThat(evaluate("1d20ro1 dis", chain(12), chain(1, 7)).total()).isEqualTo(7);
        assertThatIllegalArgumentException().isThrownBy(() -> evaluate("1d20ro1 adv", chain(12), chain(1)));
    }

    /**
     * The chains must follow the group's reroll rule: a Die is Rerolled exactly while the rule says it rerolls
     * again, so a chain that stops early, goes on too long or rerolls without a reroll rule is refused.
     */
    @Test
    void rejectsAChainThatDoesNotFollowTheRerollRule() {
        assertThatIllegalArgumentException().as("ro: a matching value not rerolled")
                .isThrownBy(() -> evaluate("1d6ro1", chain(1)));
        assertThatIllegalArgumentException().as("ro: a value rerolled that didn't match")
                .isThrownBy(() -> evaluate("1d6ro1", chain(2, 1)));
        assertThatIllegalArgumentException().as("ro: rerolled twice")
                .isThrownBy(() -> evaluate("1d6ro1", chain(1, 1, 4)));
        assertThatIllegalArgumentException().as("rr: stopped while it still matches")
                .isThrownBy(() -> evaluate("1d6rr<3", chain(1, 2)));
        assertThatIllegalArgumentException().as("rr: rerolled past a value that didn't match")
                .isThrownBy(() -> evaluate("1d6rr<3", chain(1, 4, 5)));
        assertThatIllegalArgumentException().as("no reroll rule")
                .isThrownBy(() -> evaluate("4d6kh3", chain(1, 5), chain(2), chain(3), chain(4)));

        assertThat(evaluate("1d6ro1", chain(1, 1)).total()).isEqualTo(1);
        assertThat(evaluate("1d6ro1", chain(2)).total()).isEqualTo(2);
        assertThat(evaluate("1d6rr<3", chain(2, 1, 1, 6)).total()).isEqualTo(6);
    }

    @Test
    void aKeepOfTheWholeGroupKeepsEveryDie() {
        assertThat(evaluate("3d6kl3", chain(1), chain(6), chain(3)).total()).isEqualTo(10);
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
