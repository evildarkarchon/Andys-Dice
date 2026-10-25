package io.github.evildarkarchon.andysdice.core.expression;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The Roll Expression parser at its public seam, {@link RollExpressionParser#parse(String)}: what parses, into
 * which terms, and how an invalid Roll Expression fails (docs/roll-expression-grammar.md).
 */
class RollExpressionParserTest {

    private static RollExpression valid(String input) {
        ParseResult result = RollExpressionParser.parse(input);
        assertThat(result).as("parse of '%s'", input).isInstanceOf(ParseResult.Valid.class);
        return ((ParseResult.Valid) result).expression();
    }

    @Test
    void signedSumOfDiceGroupsAndModifiersParsesIntoTermsAsWritten() {
        RollExpression expression = valid("2d6+1d8+3");

        assertThat(expression.terms()).containsExactly(
                new DiceGroup(Sign.PLUS, 2, GroupKind.D6, new Span(0, 3), "2d6"),
                new DiceGroup(Sign.PLUS, 1, GroupKind.D8, new Span(4, 7), "1d8"),
                new Modifier(Sign.PLUS, 3, new Span(8, 9), "3"));
        assertThat(expression.source()).isEqualTo("2d6+1d8+3");
        assertThat(expression.diceGroups()).containsExactly(
                (DiceGroup) expression.terms().get(0), (DiceGroup) expression.terms().get(1));
    }

    /**
     * The grammar reference's valid examples that use no keep, reroll or {@code adv}/{@code dis}, with what each
     * means written in canonical notation, and how many physical Dice its first Throw holds.
     */
    @ParameterizedTest(name = "\"{0}\" means {1} ({2} Dice)")
    @CsvSource(delimiter = '|', quoteCharacter = '"', textBlock = """
            2d6+1d8+3     | 2d6+1d8+3  | 3
            d20           | 1d20       | 1
            2D6           | 2d6        | 2
            D%            | 1d%        | 2
            02d6          | 2d6        | 2
            " 1d20 + 5 "  | 1d20+5     | 1
            -1d4+2        | -1d4+2     | 1
            1d20-1d4      | 1d20-1d4   | 2
            1d6+1d6       | 1d6+1d6    | 2
            d%            | 1d%        | 2
            d100          | 1d%        | 2
            1d4-5         | 1d4-5      | 1
            10d6+10d6     | 10d6+10d6  | 20
            10d%          | 10d%       | 20
            """)
    void grammarReferenceValidExamples(String input, String canonical, int physicalDice) {
        RollExpression expression = valid(input);

        assertThat(expression.canonical()).isEqualTo(canonical);
        assertThat(expression.physicalDice()).isEqualTo(physicalDice);
    }

    @ParameterizedTest(name = "\"{0}\" means {1}")
    @CsvSource(delimiter = '|', quoteCharacter = '"', textBlock = """
            1d20-1d4+2   | 1d20-1d4+2
            +1d6         | 1d6
            "  -  d4  "  | -1d4
            1D10+1d12-0  | 1d10+1d12-0
            0999+d8      | 999+1d8
            001d4        | 1d4
            """)
    void signedSumsWithSpacesCaseAndLeadingZeros(String input, String canonical) {
        assertThat(valid(input).canonical()).isEqualTo(canonical);
    }

    @Test
    void termsKeepTheirTextAsTypedAndTheirSpansWithoutTheSign() {
        RollExpression expression = valid(" -2D6 + D% ");

        assertThat(expression.terms()).containsExactly(
                new DiceGroup(Sign.MINUS, 2, GroupKind.D6, new Span(2, 5), "2D6"),
                new DiceGroup(Sign.PLUS, 1, GroupKind.PERCENTILE, new Span(8, 10), "D%"));
    }

    @Test
    void d100AndPercentAreTheSamePercentileRoll() {
        assertThat(valid("d100").diceGroups().getFirst().kind()).isEqualTo(GroupKind.PERCENTILE);
        assertThat(valid("d%").diceGroups().getFirst().kind()).isEqualTo(GroupKind.PERCENTILE);
        assertThat(valid("d10").diceGroups().getFirst().kind()).isEqualTo(GroupKind.D10);
    }

    @Test
    void modifiersRunFromZeroTo999() {
        assertThat(valid("1d6+0").terms().get(1)).isEqualTo(new Modifier(Sign.PLUS, 0, new Span(4, 5), "0"));
        assertThat(valid("1d6-999").terms().get(1)).isEqualTo(new Modifier(Sign.MINUS, 999, new Span(4, 7), "999"));
    }

    /**
     * The grammar reference's valid examples that use a keep rule or {@code adv}/{@code dis} (and no reroll),
     * with what each means in canonical notation, and how many physical Dice its first Throw holds.
     */
    @ParameterizedTest(name = "\"{0}\" means {1} ({2} Dice)")
    @CsvSource(delimiter = '|', quoteCharacter = '"', textBlock = """
            2d%kh1          | 2d%kh1          | 4
            4d6kh3          | 4d6kh3          | 4
            4d6KH3          | 4d6kh3          | 4
            "1d20 adv"      | 2d20kh1         | 2
            1d20adv         | 2d20kh1         | 2
            "1d20+5 dis"    | 2d20kl1+5       | 2
            "18d6+1d20 adv" | 18d6+2d20kh1    | 20
            """)
    void grammarReferenceKeepAndAdvantageExamples(String input, String canonical, int physicalDice) {
        RollExpression expression = valid(input);

        assertThat(expression.canonical()).isEqualTo(canonical);
        assertThat(expression.physicalDice()).isEqualTo(physicalDice);
    }

    @ParameterizedTest(name = "\"{0}\" means {1}")
    @CsvSource(delimiter = '|', quoteCharacter = '"', textBlock = """
            2d20kl       | 2d20kl1
            d6kh         | 1d6kh1
            4d6kh4       | 4d6kh4
            4d6kh03      | 4d6kh3
            4d6Kl1+2d8kH | 4d6kl1+2d8kh1
            -3d4kh2-1    | -3d4kh2-1
            "4d6kh3 + 1" | 4d6kh3+1
            """)
    void keepRulesWithAMissingCountCaseLeadingZerosAndSigns(String input, String canonical) {
        assertThat(valid(input).canonical()).isEqualTo(canonical);
    }

    @Test
    void aKeepRuleIsPartOfItsDiceGroup() {
        RollExpression expression = valid("4d6kh3+2");

        assertThat(expression.terms()).containsExactly(
                new DiceGroup(Sign.PLUS, 4, GroupKind.D6, Optional.of(new KeepRule(KeepRule.Direction.HIGHEST, 3)),
                        Optional.empty(), new Span(0, 6), "4d6kh3"),
                new Modifier(Sign.PLUS, 2, new Span(7, 8), "2"));
    }

    @Test
    void advantageRewritesTheOne1d20Into2d20KeepingTheHigher() {
        RollExpression expression = valid("1d20+5 adv");

        assertThat(expression.terms()).containsExactly(
                new DiceGroup(Sign.PLUS, 2, GroupKind.D20, Optional.of(new KeepRule(KeepRule.Direction.HIGHEST, 1)),
                        Optional.of(Advantage.ADVANTAGE), new Span(0, 4), "1d20"),
                new Modifier(Sign.PLUS, 5, new Span(5, 6), "5"));
    }

    @Test
    void disadvantageRewritesTheOne1d20Into2d20KeepingTheLower() {
        RollExpression expression = valid("1d6+D20 DIS");

        assertThat(expression.terms()).containsExactly(
                new DiceGroup(Sign.PLUS, 1, GroupKind.D6, new Span(0, 3), "1d6"),
                new DiceGroup(Sign.PLUS, 2, GroupKind.D20, Optional.of(new KeepRule(KeepRule.Direction.LOWEST, 1)),
                        Optional.of(Advantage.DISADVANTAGE), new Span(4, 7), "D20"));
        assertThat(expression.physicalDice()).isEqualTo(3);
    }

    @ParameterizedTest(name = "\"{0}\" means {1}")
    @CsvSource(delimiter = '|', quoteCharacter = '"', textBlock = """
            "  d20 adv  "     | 2d20kh1
            1d20ADV           | 2d20kh1
            "1d4+1d20+1 dis"  | 1d4+2d20kl1+1
            "-1d6+1d20 adv"   | -1d6+2d20kh1
            "01d20 adv"       | 2d20kh1
            """)
    void advantageWithSpacesCaseAndOtherTerms(String input, String canonical) {
        assertThat(valid(input).canonical()).isEqualTo(canonical);
    }

    /** Unkept Dice are still Thrown, so they count towards the 20-Dice cap; so does Advantage's second d20. */
    @Test
    void unkeptDiceAndAdvantageCountTowardsTheDiceCap() {
        assertThat(valid("4d6kh1+16d6").physicalDice()).isEqualTo(20);
        assertThat(valid("10d%kl1").physicalDice()).isEqualTo(20);
        assertThat(RollExpressionParser.parse("5d6kh1+16d6")).isInstanceOf(ParseResult.Invalid.class);
        assertThat(RollExpressionParser.parse("19d6+1d20 adv")).isInstanceOf(ParseResult.Invalid.class);
    }

    @Test
    void anExpressionOfExactly100CharactersIsValid() {
        String input = "1d20" + "+1".repeat(48);
        assertThat(input).hasSize(RollExpressionCaps.MAX_LENGTH);

        assertThat(valid(input).terms()).hasSize(49);
    }
}
