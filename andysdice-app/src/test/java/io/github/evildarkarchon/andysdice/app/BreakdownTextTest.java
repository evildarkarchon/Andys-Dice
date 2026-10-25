package io.github.evildarkarchon.andysdice.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.evildarkarchon.andysdice.core.expression.FaceValueChain;
import io.github.evildarkarchon.andysdice.core.expression.ParseResult;
import io.github.evildarkarchon.andysdice.core.expression.RollEvaluator;
import io.github.evildarkarchon.andysdice.core.expression.RollExpression;
import io.github.evildarkarchon.andysdice.core.expression.RollExpressionParser;
import io.github.evildarkarchon.andysdice.core.expression.RollResult;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The result strip's words in pure Java (spec §3.2 "Screen readers", "Breakdown marks"): the spoken summary for the
 * forms the headless tests don't roll (each reroll comparison, Reroll Once, Disadvantage, d%, subtracted terms and a
 * leading Modifier), and the headings of terms as written. Results come from the real parser and evaluator.
 */
class BreakdownTextTest {

    @Test
    void theSpokenSummaryReadsSubtractedTermsPercentileAndALeadingModifier() {
        // 2d8rr>=7kl1: [7, 3] rerolled once to a 3 and kept (the lower), [5] not kept.
        RollResult result = evaluate("5 - 2d8rr>=7kl1 + d% - 3", FaceValueChain.of(7, 3), FaceValueChain.of(5),
                FaceValueChain.of(42));

        assertThat(result.total()).isEqualTo(41);
        assertThat(BreakdownText.spokenSummary(result)).isEqualTo("Total 41. 5. Minus 2d8 reroll 7 or over keep "
                + "lowest 1: 3 after a reroll, 5 not kept. Plus 1d%: 42. Minus 3.");
        assertThat(result.breakdown().terms()).extracting(term -> BreakdownText.asWritten(term.term(),
                term == result.breakdown().terms().getFirst())).containsExactly("+5", "-2d8rr>=7kl1", "+d%", "-3");
    }

    @Test
    void theSpokenSummaryReadsDisadvantageAsTheTypedD20() {
        // ro<3: the 2 is rerolled once to a 15, which stands; dis keeps the 9.
        RollResult result = evaluate("1d20ro<3 dis", FaceValueChain.of(2, 15), FaceValueChain.of(9));

        assertThat(BreakdownText.spokenSummary(result)).isEqualTo(
                "Total 9. 1d20 reroll under 3 once with disadvantage: 15 after a reroll and not kept, 9.");
        assertThat(BreakdownText.asWritten(result.breakdown().terms().getFirst().term(), true))
                .isEqualTo("1d20ro<3 dis");
    }

    @Test
    void theOtherComparisonsReadInWords() {
        assertThat(BreakdownText.spokenSummary(evaluate("4d6ro<=2", FaceValueChain.of(3), FaceValueChain.of(4),
                FaceValueChain.of(5), FaceValueChain.of(6))))
                .isEqualTo("Total 18. 4d6 reroll 2 or under once: 3, 4, 5, 6.");
        assertThat(BreakdownText.spokenSummary(evaluate("1d20rr>18", FaceValueChain.of(7))))
                .isEqualTo("Total 7. 1d20 reroll over 18: 7.");
    }

    @Test
    void theThrowsCountIsSingularForOne() {
        assertThat(BreakdownText.throwsText(1)).isEqualTo("1 Throw");
        assertThat(BreakdownText.throwsText(3)).isEqualTo("3 Throws");
    }

    private static RollResult evaluate(String input, FaceValueChain... chains) {
        RollExpression expression = ((ParseResult.Valid) RollExpressionParser.parse(input)).expression();
        return RollEvaluator.evaluate(expression, List.of(chains));
    }
}
