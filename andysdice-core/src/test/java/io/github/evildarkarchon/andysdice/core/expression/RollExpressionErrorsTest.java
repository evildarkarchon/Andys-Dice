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

    /**
     * Every row of the grammar reference's invalid example table that uses a keep rule or {@code adv}/{@code dis}
     * and no reroll. The table's wording is kept inside each message.
     */
    static Stream<Arguments> grammarReferenceKeepAndAdvantageExamples() {
        return Stream.of(
                row("4d6dl1", 3, 6, "drop forms aren't used; use the keep form, `4d6kh3`"),
                row("4d6k3", 3, 4, "write `kh` (keep highest) or `kl` (keep lowest)"),
                row("4d6kh5", 3, 6, "`4d6` can keep at most 4"),
                row("1d6 adv", 4, 7, "`adv` needs one added `1d20`"),
                row("2d20 adv", 5, 8, "`adv` needs one added `1d20`; write `2d20kh1` or `1d20 adv`"),
                row("-1d20 adv", 6, 9, "`adv` needs one added `1d20`, not a subtracted one"),
                row("1d20+1d20 adv", 10, 13, "`adv` needs exactly one d20 term"),
                row("1d20 adv+5", 5, 8, "`adv` goes at the end"),
                row("1d20 adv dis", 9, 12, "use only one of adv or dis"),
                row("1d20 advantage", 5, 14, "write `adv`; only the short form is accepted"));
    }

    /** {@code adv}/{@code dis} errors the table doesn't list. */
    static Stream<Arguments> otherAdvantageErrors() {
        return Stream.of(
                row("1d6 dis", 4, 7, "`dis` needs one added `1d20`"),
                row("d% ADV", 3, 6, "`adv` needs one added `1d20`"),
                row("3d20 dis", 5, 8, "`dis` needs one added `1d20`; write `2d20kl1` or `1d20 dis`"),
                row("1d20kh1 adv", 8, 11,
                        "`adv` needs one added `1d20` without a keep rule; write `2d20kh1` or `1d20 adv`"),
                row("1d20kl dis", 7, 10,
                        "`dis` needs one added `1d20` without a keep rule; write `2d20kl1` or `1d20 dis`"),
                row("5-1d20 dis", 7, 10, "`dis` needs one added `1d20`, not a subtracted one"),
                row("2d20-1d20 adv", 10, 13, "`adv` needs exactly one d20 term"),
                row("1d20 dis 5", 5, 8, "`dis` goes at the end"),
                row("1d20 adv 1d6", 5, 8, "`adv` goes at the end"),
                row("1d20 dis #", 5, 8, "`dis` goes at the end"),
                row("1d20advdis", 4, 10, "`advdis` isn't part of a Roll Expression; " + SHAPE_HINT),
                row("1d20 ADV adv", 9, 12, "use only one of adv or dis"),
                row("1d20 disadvantage", 5, 17, "write `dis`; only the short form is accepted"),
                row("1d20ADVANTAGE", 4, 13, "write `adv`; only the short form is accepted"),
                row("adv 1d20", 0, 3, "`adv` goes at the end, after the last term with no sign before it"),
                row("1d20+adv", 5, 8, "`adv` goes at the end, after the last term with no sign before it"),
                row("1d20-dis", 5, 8, "`dis` goes at the end, after the last term with no sign before it"));
    }

    @ParameterizedTest(name = "\"{0}\" fails at {1}: {2}")
    @MethodSource("otherAdvantageErrors")
    void otherAdvantageErrors(String input, Span span, String message) {
        assertThat(error(input)).isEqualTo(new ParseError(message, span));
    }

    @ParameterizedTest(name = "\"{0}\" fails at {1}: {2}")
    @MethodSource("grammarReferenceKeepAndAdvantageExamples")
    void grammarReferenceKeepAndAdvantageExamples(String input, Span span, String message) {
        assertThat(error(input)).isEqualTo(new ParseError(message, span));
    }

    /** Every row of the grammar reference's invalid example table that uses a reroll. */
    static Stream<Arguments> grammarReferenceRerollExamples() {
        return Stream.of(
                row("1d6ro <3", 5, 6, "no space inside `ro<3`"),
                row("1d6r1", 3, 4, "`r` is ambiguous; use `ro` (once) or `rr` (until)"),
                row("1d6ro7", 3, 6, "`ro7` never matches a d6"),
                row("1d6ro>=1", 3, 8, "`ro>=1` matches every face of a d6"),
                row("1d4rr<5", 3, 7, "`rr<5` matches every face of a d4"),
                row("1d20rr<=19", 4, 10, "`rr` may reroll at most half the faces; use `ro` to reroll once"),
                row("1d6ro1ro2", 6, 9, "one reroll clause per Dice Group"),
                row("4d6kh3rr1", 6, 9, "write the reroll before the keep rule: `4d6rr1kh3`"));
    }

    @ParameterizedTest(name = "\"{0}\" fails at {1}: {2}")
    @MethodSource("grammarReferenceRerollExamples")
    void grammarReferenceRerollExamples(String input, Span span, String message) {
        assertThat(error(input)).isEqualTo(new ParseError(message, span));
    }

    /**
     * Reroll errors the table doesn't list: the half-the-faces rule for every Die Kind (one face past half), a
     * condition with no value or a comparison that isn't one, spaces, case, values of any length, and where a
     * misplaced or second clause is marked.
     */
    static Stream<Arguments> otherRerollErrors() {
        String half = "`rr` may reroll at most half the faces; use `ro` to reroll once";
        return Stream.of(
                row("1d4rr<=3", 3, 8, half),
                row("1d6rr>=3", 3, 8, half),
                row("1d8rr<6", 3, 7, half),
                row("1d10rr>4", 4, 8, half),
                row("1d12rr<=7", 4, 9, half),
                row("d%rr<=51", 2, 8, half),
                row("1D20RR>9", 4, 8, half),
                row("1d6ro0", 3, 6, "`ro0` never matches a d6"),
                row("1d6ro<1", 3, 7, "`ro<1` never matches a d6"),
                row("1d6RO>6", 3, 7, "`RO>6` never matches a d6"),
                row("d%ro101", 2, 7, "`ro101` never matches a d%"),
                row("1d6ro99999999999999999999", 3, 25, "`ro99999999999999999999` never matches a d6"),
                row("1d6rr<99999999999999999999", 3, 26, "`rr<99999999999999999999` matches every face of a d6"),
                row("1d6ro<=6", 3, 8, "`ro<=6` matches every face of a d6"),
                row("1d6ro>0", 3, 7, "`ro>0` matches every face of a d6"),
                row("1d6R1", 3, 4, "`R` is ambiguous; use `ro` (once) or `rr` (until)"),
                row("1d6r", 3, 4, "`r` is ambiguous; use `ro` (once) or `rr` (until)"),
                row("1d6r<3", 3, 4, "`r` is ambiguous; use `ro` (once) or `rr` (until)"),
                row("4d6kh3r1", 6, 7, "`r` is ambiguous; use `ro` (once) or `rr` (until)"),
                row("1d6reroll1", 3, 9, "`reroll` isn't part of a Roll Expression; " + SHAPE_HINT),
                row("1d6ro", 5, 5, "`ro` needs a condition; write a value or a comparison, such as `ro1` or `ro<3`"),
                row("1d6RR+1", 5, 5, "`RR` needs a condition; write a value or a comparison, such as `rr1` or `rr<3`"),
                row("1d6rokh1", 5, 5,
                        "`ro` needs a condition; write a value or a comparison, such as `ro1` or `ro<3`"),
                row("1d6ro<", 6, 6, "`ro<` needs a value, such as `ro<3`"),
                row("1d6rr>=+1", 7, 7, "`rr>=` needs a value, such as `rr>=3`"),
                row("1d6ro=1", 5, 6,
                        "`=` isn't a comparison; use `<`, `<=`, `>` or `>=`, or the value alone, as in `ro1`"),
                row("1d6ro=<2", 5, 7,
                        "`=<` isn't a comparison; use `<`, `<=`, `>` or `>=`, or the value alone, as in `ro1`"),
                row("1d6rr<>2", 5, 7,
                        "`<>` isn't a comparison; use `<`, `<=`, `>` or `>=`, or the value alone, as in `rr1`"),
                row("1d6ro< 3", 6, 7, "no space inside `ro<3`"),
                row("1d6ro  1", 5, 7, "no space inside `ro1`"),
                row("1d6ro < 3", 5, 6, "no space inside `ro<3`"),
                row("4d6 rr1", 3, 4, "no space inside `4d6rr1`"),
                row("1d20 RO<2", 4, 5, "no space inside `1d20RO<2`"),
                row("4d6rr1 kh3", 6, 7, "no space inside `4d6rr1kh3`"),
                row("1d6ro1rr2", 6, 9, "one reroll clause per Dice Group"),
                row("1d6ro1r", 6, 7, "one reroll clause per Dice Group"),
                row("4d6rr1kh3ro2", 9, 12, "one reroll clause per Dice Group"),
                row("4d6KH3RO<2", 6, 10, "write the reroll before the keep rule: `4d6RO<2KH3`"),
                row("4d6kl3ro7", 6, 9, "write the reroll before the keep rule: `4d6ro7kl3`"),
                row("4d6rr1kh3x", 9, 10, "`x` isn't part of a Roll Expression; " + SHAPE_HINT));
    }

    @ParameterizedTest(name = "\"{0}\" fails at {1}: {2}")
    @MethodSource("otherRerollErrors")
    void otherRerollErrors(String input, Span span, String message) {
        assertThat(error(input)).isEqualTo(new ParseError(message, span));
    }

    /** Keep-rule errors the table doesn't list. */
    static Stream<Arguments> otherKeepErrors() {
        return Stream.of(
                row("4d6kh0", 3, 6, "`kh0` keeps no Dice; keep at least 1"),
                row("4d6kl00", 3, 7, "`kl00` keeps no Dice; keep at least 1"),
                row("2d%kh3", 3, 6, "`2d%` can keep at most 2"),
                row("d6KL2", 2, 5, "`1d6` can keep at most 1"),
                row("d100kh2", 4, 7, "`1d%` can keep at most 1"),
                row("4d6kh99999999999999999999", 3, 25, "`4d6` can keep at most 4"),
                row("4d6dh1", 3, 6, "drop forms aren't used; use the keep form, `4d6kl3`"),
                row("4D6DL", 3, 5, "drop forms aren't used; use the keep form, `4d6kh3`"),
                row("2d%dh1", 3, 6, "drop forms aren't used; use the keep form, `2d%kl1`"),
                row("1d6dl1", 3, 6, "drop forms aren't used; use the keep form, `kh` or `kl`"),
                row("4d6dl4", 3, 6, "drop forms aren't used; use the keep form, `kh` or `kl`"),
                row("4d6K", 3, 4, "write `kh` (keep highest) or `kl` (keep lowest)"),
                row("4d6keep3", 3, 4, "write `kh` (keep highest) or `kl` (keep lowest)"),
                row("4d6kh3kl1", 6, 9, "one keep rule per Dice Group"),
                row("4d6kh3k", 6, 7, "one keep rule per Dice Group"),
                row("4d6 kh3", 3, 4, "no space inside `4d6kh3`"),
                row("4d6  KL", 3, 5, "no space inside `4d6KL`"),
                row("4d6kh 3", 5, 6, "no space inside `kh3`"),
                row("4d6kh 0", 5, 6, "no space inside `kh0`"),
                row("4d6kh3x", 6, 7, "`x` isn't part of a Roll Expression; " + SHAPE_HINT),
                row("4d6kh3 1", 7, 7, "put `+` or `-` between terms"));
    }

    @ParameterizedTest(name = "\"{0}\" fails at {1}: {2}")
    @MethodSource("otherKeepErrors")
    void otherKeepErrors(String input, Span span, String message) {
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
     * whole-expression checks (at least one Dice Group, then {@code adv}/{@code dis}, then Dice per Throw, where
     * Advantage's second d20 counts). Only the first error is reported.
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
                row("21d6 #", 5, 6, "`#` can't appear in a Roll Expression; " + SHAPE_HINT),
                row("21d6kh30", 4, 8, "`21d6` can keep at most 21"),
                row("1d6ro7+1d7", 3, 6, "`ro7` never matches a d6"),
                row("1d6rr<=4kh5", 3, 8, "`rr` may reroll at most half the faces; use `ro` to reroll once"),
                row("4d6rr1kh5", 6, 9, "`4d6` can keep at most 4"),
                row("1d20ro0 adv", 4, 7, "`ro0` never matches a d20"),
                row("21d6ro1", 0, 7, "at most 20 Dice per Throw; this throws 21"),
                row("19d6+1d20rr1 adv", 5, 12, "at most 20 Dice per Throw; this throws 21"),
                row("1d7kh1 adv", 1, 3, "`d7` is not a Die Kind; " + KINDS),
                row("1d20 adv+1d7", 5, 8, "`adv` goes at the end"),
                row("5 adv", 0, 1, "nothing to Throw; add a Dice Group such as `1d20`"),
                row("21d6 adv", 5, 8, "`adv` needs one added `1d20`"),
                row("19d6+1d20 adv", 5, 9, "at most 20 Dice per Throw; this throws 21"),
                row("1d20+19d6 dis", 5, 9, "at most 20 Dice per Throw; this throws 21"));
    }

    @ParameterizedTest(name = "\"{0}\" fails first at {1}: {2}")
    @MethodSource("errorOrder")
    void reportsOnlyTheFirstErrorInTheGrammarsOrder(String input, Span span, String message) {
        assertThat(error(input)).isEqualTo(new ParseError(message, span));
    }
}
