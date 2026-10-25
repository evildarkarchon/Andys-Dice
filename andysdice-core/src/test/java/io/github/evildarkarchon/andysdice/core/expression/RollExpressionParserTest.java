package io.github.evildarkarchon.andysdice.core.expression;

import static org.assertj.core.api.Assertions.assertThat;

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

    @Test
    void anExpressionOfExactly100CharactersIsValid() {
        String input = "1d20" + "+1".repeat(48);
        assertThat(input).hasSize(RollExpressionCaps.MAX_LENGTH);

        assertThat(valid(input).terms()).hasSize(49);
    }
}
