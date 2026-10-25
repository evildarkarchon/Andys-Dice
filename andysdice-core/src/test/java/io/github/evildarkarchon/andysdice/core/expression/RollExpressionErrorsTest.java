package io.github.evildarkarchon.andysdice.core.expression;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * How an invalid Roll Expression fails at {@link RollExpressionParser#parse(String)}: one error, with the span
 * the grammar reference gives and a message that names the problem and suggests the fix.
 */
class RollExpressionErrorsTest {

    private static final String SHAPE_HINT =
            "write Dice Groups and Modifiers joined by `+` or `-`, such as `2d6+3`";
    private static final String KINDS = "use d4, d6, d8, d10, d12, d20 or d%";

    private static ParseError error(String input) {
        ParseResult result = RollExpressionParser.parse(input);
        assertThat(result).as("parse of '%s'", input).isInstanceOf(ParseResult.Invalid.class);
        return ((ParseResult.Invalid) result).error();
    }

    private static Arguments row(String input, int start, int end, String message) {
        return Arguments.of(input, new Span(start, end), message);
    }

    /**
     * Every row of the grammar reference's invalid example table that uses no keep, reroll or
     * {@code adv}/{@code dis}. The table's wording is kept inside each message.
     */
    static Stream<Arguments> grammarReferenceInvalidExamples() {
        return Stream.of(
                row("", 0, 0, "nothing typed; type a Roll Expression such as `1d20`"),
                row("    ", 0, 0, "nothing typed; type a Roll Expression such as `1d20`"),
                row("1d20" + "+1".repeat(48) + "5", 100, 101,
                        "a Roll Expression is at most 100 characters; this one has 101"),
                row("1d7", 1, 3, "`d7` is not a Die Kind; " + KINDS),
                row("2 d6", 1, 2, "no space inside `2d6`"),
                row("1d20\t+5", 4, 5, "a tab can't appear; use a space"),
                row("(1d6+2)", 0, 1, "`(` can't appear in a Roll Expression; " + SHAPE_HINT),
                row("1d20+5 # attack", 7, 8, "`#` can't appear in a Roll Expression; " + SHAPE_HINT),
                row("2d6+", 4, 4, "a term must follow `+`; add a Dice Group or a Modifier, such as `1d6` or `3`"),
                row("0d6", 0, 1, "a Dice Group throws at least 1 Die; write a count of 1 or more, such as `1d6`"),
                row("5", 0, 1, "nothing to Throw; add a Dice Group such as `1d20`"),
                row("1d20+1000", 5, 9, "a Modifier is at most 999"),
                row("11d6+10d6", 5, 9, "at most 20 Dice per Throw; this throws 21"),
                row("11d%", 0, 4, "at most 20 Dice per Throw; this throws 22"));
    }

    @ParameterizedTest(name = "\"{0}\" fails at {1}: {2}")
    @MethodSource("grammarReferenceInvalidExamples")
    void grammarReferenceInvalidExamples(String input, Span span, String message) {
        assertThat(error(input)).isEqualTo(new ParseError(message, span));
    }

    /** Errors the table doesn't list, settled here so each still has one span and a suggestion. */
    static Stream<Arguments> otherErrors() {
        return Stream.of(
                row("-", 1, 1, "a term must follow `-`; add a Dice Group or a Modifier, such as `1d6` or `3`"),
                row("1d6 + ", 5, 5, "a term must follow `+`; add a Dice Group or a Modifier, such as `1d6` or `3`"),
                row("2d6++3", 4, 5, "two signs in a row; keep only one"),
                row("+-1d6", 1, 2, "two signs in a row; keep only one"),
                row("1d6 1d6", 4, 4, "put `+` or `-` between terms"),
                row("1d20 5", 5, 5, "put `+` or `-` between terms"),
                row("1d6d6", 3, 3, "put `+` or `-` between terms"),
                row("2d", 2, 2, "a Die Kind must follow `d`; " + KINDS),
                row("2d+1", 2, 2, "a Die Kind must follow `d`; " + KINDS),
                row("1d 20", 2, 3, "no space inside `1d20`"),
                row("2  d%", 1, 3, "no space inside `2d%`"),
                row("dF", 0, 2, "`dF` is not a Die Kind; " + KINDS),
                row("1D1000", 1, 6, "`D1000` is not a Die Kind; " + KINDS),
                row("d06", 0, 3, "`d06` is not a Die Kind; " + KINDS),
                row("1d3+1d7", 1, 3, "`d3` is not a Die Kind; " + KINDS),
                row("00d6", 0, 2, "a Dice Group throws at least 1 Die; write a count of 1 or more, such as `1d6`"),
                row("1d20+5 attack", 7, 13, "`attack` isn't part of a Roll Expression; " + SHAPE_HINT),
                row("1d6%", 3, 4, "`%` can't go here; " + SHAPE_HINT),
                row("1d6+=2", 4, 5, "`=` can't go here; " + SHAPE_HINT),
                row("1d6*2", 3, 4, "`*` can't appear in a Roll Expression; " + SHAPE_HINT),
                row("1d#", 2, 3, "`#` can't appear in a Roll Expression; " + SHAPE_HINT),
                row("1d6\n", 3, 4, "a control character (U+000A) can't appear; use a space"),
                row("1d6 +1", 3, 4, "U+00A0 is not a plain space; use a space"),
                row("1d6+🎲", 4, 6, "`🎲` can't appear in a Roll Expression; " + SHAPE_HINT),
                row("5+3", 0, 3, "nothing to Throw; add a Dice Group such as `1d20`"),
                row("  5 - 2 ", 2, 7, "nothing to Throw; add a Dice Group such as `1d20`"),
                row("1d6+99999999999999", 4, 18, "a Modifier is at most 999"),
                row("20d6+d4", 5, 7, "at most 20 Dice per Throw; this throws 21"),
                row("15d6+5d6+3d4", 9, 12,"at most 20 Dice per Throw; this throws 23"),
                row("99999999999999999999d6", 0, 22,
                        "at most 20 Dice per Throw; this throws 99999999999999999999"));
    }

    @ParameterizedTest(name = "\"{0}\" fails at {1}: {2}")
    @MethodSource("otherErrors")
    void otherErrors(String input, Span span, String message) {
        assertThat(error(input)).isEqualTo(new ParseError(message, span));
    }

    /**
     * The grammar's error order: the length cap first, then the first problem reading left to right, then the
     * whole-expression checks (at least one Dice Group, then Dice per Throw). Only the first error is reported.
     */
    static Stream<Arguments> errorOrder() {
        return Stream.of(
                row("#" + "1".repeat(100), 100, 101, "a Roll Expression is at most 100 characters; this one has 101"),
                row("#5", 0, 1, "`#` can't appear in a Roll Expression; " + SHAPE_HINT),
                row("5+1000", 2, 6, "a Modifier is at most 999"),
                row("21d6+1d7", 6, 8, "`d7` is not a Die Kind; " + KINDS),
                row("0d7", 0, 1, "a Dice Group throws at least 1 Die; write a count of 1 or more, such as `1d6`"),
                row("1d7+0d6", 1, 3, "`d7` is not a Die Kind; " + KINDS),
                row("21d6+1000", 5, 9, "a Modifier is at most 999"),
                row("21d6 #", 5, 6, "`#` can't appear in a Roll Expression; " + SHAPE_HINT));
    }

    @ParameterizedTest(name = "\"{0}\" fails first at {1}: {2}")
    @MethodSource("errorOrder")
    void reportsOnlyTheFirstErrorInTheGrammarsOrder(String input, Span span, String message) {
        assertThat(error(input)).isEqualTo(new ParseError(message, span));
    }
}
