package io.github.evildarkarchon.andysdice.core.expression;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Parses Roll Expressions as docs/roll-expression-grammar.md defines them: all or nothing, with only the first
 * error reported, as one message and one {@code [start, end)} span into the input as typed.
 *
 * <p>Errors come in the grammar's order: the {@linkplain RollExpressionCaps#MAX_LENGTH length cap} first, then
 * the first character, syntax or per-term problem reading left to right, then the whole-expression checks (at
 * least one Dice Group, then {@code adv}/{@code dis} once #28 adds them, then
 * {@linkplain RollExpressionCaps#MAX_DICE_PER_THROW Dice per Throw}).
 *
 * <p>It is a recursive descent over characters rather than tokens, because tokens may touch ({@code 2d6+3},
 * and later {@code 4d6rr1kh3} and {@code 1d20adv}) and because {@code d} starts both a Die Kind and
 * {@code dis}. Keep rules (#28) slot in at {@code Scan.groupSuffix}, rerolls (#29) there too, and the trailing
 * {@code adv}/{@code dis} at {@code Scan.word} and {@code Scan.checkWholeExpression}.
 */
public final class RollExpressionParser {

    /** The suggestion given when the input doesn't have the shape of a Roll Expression at all. */
    private static final String SHAPE_HINT = "write Dice Groups and Modifiers joined by `+` or `-`, such as `2d6+3`";

    /** The suggestion given for a missing or unknown Die Kind. */
    private static final String KINDS_HINT = "use d4, d6, d8, d10, d12, d20 or d%";

    /** Every character the grammar's alphabet allows besides letters and the space, which are handled apart. */
    private static final String ALPHABET_SYMBOLS = "0123456789%+-<>=";

    private RollExpressionParser() {
    }

    /**
     * Parses {@code input} as typed. Letters may be in either case and ASCII spaces may stand between tokens.
     *
     * @param input the Roll Expression box's text
     * @return the parsed expression, or the first error with its span
     * @throws NullPointerException if {@code input} is null
     */
    public static ParseResult parse(String input) {
        Objects.requireNonNull(input, "input");
        try {
            return new ParseResult.Valid(new Scan(input).expression());
        } catch (Failure failure) {
            return new ParseResult.Invalid(failure.error);
        }
    }

    /** Unwinds the descent at the first error. Stackless: it is control flow, never logged. */
    private static final class Failure extends Exception {
        private static final long serialVersionUID = 1L;

        // Transient because ParseError isn't Serializable; a Failure never leaves parse(), let alone the JVM.
        private final transient ParseError error;

        Failure(String message, Span span) {
            super(message, null, false, false);
            this.error = new ParseError(message, span);
        }
    }

    /**
     * A term read during the scan, before the whole-expression checks. A Dice Group's count is kept exact (it can
     * be any number of digits) so the Dice-per-Throw message can say how many Dice were asked for.
     */
    private sealed interface Draft {
        /** Where the term stands in the input, without its sign. */
        Span span();

        /** The finished term; only valid once the whole-expression checks have passed. */
        Term toTerm();
    }

    private record GroupDraft(Sign sign, BigInteger count, GroupKind kind, Span span, String text) implements Draft {

        BigInteger physicalDice() {
            return count.multiply(BigInteger.valueOf(kind.physicalDice()));
        }

        /** Only called once the Dice-per-Throw check has passed, so {@code count} fits an int. */
        @Override
        public Term toTerm() {
            return new DiceGroup(sign, count.intValueExact(), kind, span, text);
        }
    }

    private record ModifierDraft(Modifier modifier) implements Draft {
        @Override
        public Span span() {
            return modifier.span();
        }

        @Override
        public Term toTerm() {
            return modifier;
        }
    }

    /** One parse of one input; {@code pos} only moves forward. */
    private static final class Scan {
        private final String in;
        private final int n;
        private int pos;

        Scan(String in) {
            this.in = in;
            this.n = in.length();
        }

        RollExpression expression() throws Failure {
            if (n > RollExpressionCaps.MAX_LENGTH) {
                throw new Failure("a Roll Expression is at most " + RollExpressionCaps.MAX_LENGTH
                        + " characters; this one has " + n, new Span(RollExpressionCaps.MAX_LENGTH, n));
            }
            if (in.chars().allMatch(c -> c == ' ')) {
                throw new Failure("nothing typed; type a Roll Expression such as `1d20`", Span.at(0));
            }
            List<Draft> drafts = new ArrayList<>();
            spaces();
            Sign sign = Sign.PLUS;
            if (isSign(at(pos))) {
                sign = signAfterWhichATermMustFollow();
            }
            while (true) {
                drafts.add(term(sign));
                int termEnd = pos;
                spaces();
                if (pos >= n) {
                    break;
                }
                if (isSign(at(pos))) {
                    sign = signAfterWhichATermMustFollow();
                    continue;
                }
                throw afterTerm(drafts.getLast(), termEnd);
            }
            checkWholeExpression(drafts);
            return new RollExpression(in, drafts.stream().map(Draft::toTerm).toList());
        }

        /**
         * Consumes the sign at {@code pos} and the spaces after it, and checks that a term follows: not the end of
         * the input and not a second sign.
         */
        private Sign signAfterWhichATermMustFollow() throws Failure {
            char symbol = in.charAt(pos++);
            int signEnd = pos;
            spaces();
            if (pos >= n) {
                // Marked straight after the sign, where the term belongs, even if spaces follow it.
                throw new Failure("a term must follow `" + symbol
                        + "`; add a Dice Group or a Modifier, such as `1d6` or `3`", Span.at(signEnd));
            }
            if (isSign(at(pos))) {
                throw new Failure("two signs in a row; keep only one", new Span(pos, pos + 1));
            }
            return symbol == '+' ? Sign.PLUS : Sign.MINUS;
        }

        /** Reads one Dice Group or Modifier starting at {@code pos}, which is not a space, a sign or the end. */
        private Draft term(Sign sign) throws Failure {
            int start = pos;
            char c = at(pos);
            if (isDigit(c)) {
                BigInteger value = digits();
                if (startsDieKind(pos)) {
                    if (value.signum() == 0) {
                        throw new Failure("a Dice Group throws at least 1 Die; write a count of 1 or more, such as "
                                + "`1d6`", new Span(start, pos));
                    }
                    return group(sign, start, value);
                }
                if (value.compareTo(BigInteger.valueOf(RollExpressionCaps.MAX_MODIFIER)) > 0) {
                    throw new Failure("a Modifier is at most " + RollExpressionCaps.MAX_MODIFIER, new Span(start, pos));
                }
                Span span = new Span(start, pos);
                return new ModifierDraft(new Modifier(sign, value.intValueExact(), span, text(span)));
            }
            if (startsDieKind(pos)) {
                return group(sign, start, BigInteger.ONE);
            }
            if (isLetter(c)) {
                throw word();
            }
            throw unexpected(pos);
        }

        /**
         * Reads a Dice Group from its {@code d} at {@code pos}: the Die Kind, then whatever the grammar lets follow
         * it.
         *
         * @param sign  the group's sign
         * @param start where the group began: its count, or its {@code d} if it had none
         * @param count the count as written, at least 1 (1 if none was written)
         * @throws Failure if the Die Kind is missing or not one of the 7-piece set
         */
        private GroupDraft group(Sign sign, int start, BigInteger count) throws Failure {
            int d = pos++;
            GroupKind kind;
            if (at(pos) == '%') {
                pos++;
                kind = GroupKind.PERCENTILE;
            } else if (isDigit(at(pos))) {
                int digitsStart = pos;
                digits();
                // The Die Kind is a fixed token, not an integer: d06 and d1000 are not Die Kinds.
                kind = switch (in.substring(digitsStart, pos)) {
                    case "4" -> GroupKind.D4;
                    case "6" -> GroupKind.D6;
                    case "8" -> GroupKind.D8;
                    case "10" -> GroupKind.D10;
                    case "12" -> GroupKind.D12;
                    case "20" -> GroupKind.D20;
                    case "100" -> GroupKind.PERCENTILE;
                    default -> throw notADieKind(d);
                };
            } else if (isLetter(at(pos))) {
                letters();
                throw notADieKind(d);
            } else if (at(pos) == ' ' && dieKindAfterSpaces(pos)) {
                throw spaceInside(start, pos);
            } else if (pos >= n || isSign(at(pos)) || at(pos) == ' ' || isAllowedSymbol(at(pos))) {
                throw new Failure("a Die Kind must follow `d`; " + KINDS_HINT, Span.at(pos));
            } else {
                throw unexpected(pos);
            }
            groupSuffix();
            Span span = new Span(start, pos);
            return new GroupDraft(sign, count, kind, span, text(span));
        }

        /**
         * Reads what may follow a Dice Group's Die Kind. Nothing yet: the reroll clause (#29) and then the keep
         * rule (#28) go here, before the scan moves on, so that {@code 4d6rr1kh3} stays one Dice Group.
         */
        private void groupSuffix() {
            // Intentionally empty until #28 and #29: anything after the Die Kind is read by afterTerm.
        }

        /**
         * Reports what stands after a complete term and its spaces when it isn't a sign: two terms with no sign
         * between them, a space inside a Dice Group, a stray word or a character that can't go there.
         *
         * @param previous the term just read
         * @param termEnd  where that term ended, before any spaces
         */
        private Failure afterTerm(Draft previous, int termEnd) {
            char c = at(pos);
            if (previous instanceof ModifierDraft && pos > termEnd && startsDieKind(pos) && dieKindFollows(pos + 1)) {
                // "2 d6": the Modifier was really the count of a Dice Group with a space inside it.
                return spaceInside(previous.span().start(), termEnd);
            }
            if (isDigit(c) || (startsDieKind(pos) && dieKindFollows(pos + 1))) {
                return new Failure("put `+` or `-` between terms", Span.at(pos));
            }
            if (isLetter(c)) {
                return word();
            }
            return unexpected(pos);
        }

        /**
         * Reports the run of letters at {@code pos} that isn't part of any term. {@code adv}/{@code dis} (#28)
         * will be recognised here.
         */
        private Failure word() {
            int start = pos;
            letters();
            return new Failure("`" + in.substring(start, pos) + "` isn't part of a Roll Expression; " + SHAPE_HINT,
                    new Span(start, pos));
        }

        /**
         * The whole-expression checks, in the grammar's order: at least one Dice Group, then ({@code adv}/
         * {@code dis}, #28), then Dice per Throw.
         */
        private void checkWholeExpression(List<Draft> drafts) throws Failure {
            List<GroupDraft> groups = drafts.stream()
                    .filter(GroupDraft.class::isInstance).map(GroupDraft.class::cast).toList();
            if (groups.isEmpty()) {
                int first = drafts.getFirst().span().start();
                int last = drafts.getLast().span().end();
                throw new Failure("nothing to Throw; add a Dice Group such as `1d20`", new Span(first, last));
            }
            BigInteger cap = BigInteger.valueOf(RollExpressionCaps.MAX_DICE_PER_THROW);
            BigInteger total = groups.stream().map(GroupDraft::physicalDice).reduce(BigInteger.ZERO, BigInteger::add);
            BigInteger running = BigInteger.ZERO;
            for (GroupDraft group : groups) {
                running = running.add(group.physicalDice());
                if (running.compareTo(cap) > 0) {
                    // The span is the group whose Dice take the count past the cap; the message gives the total.
                    throw new Failure("at most " + cap + " Dice per Throw; this throws " + total, group.span());
                }
            }
        }

        private Failure notADieKind(int d) {
            return new Failure("`" + in.substring(d, pos) + "` is not a Die Kind; " + KINDS_HINT, new Span(d, pos));
        }

        /**
         * Reports spaces inside a Dice Group, as in {@code 2 d6} or {@code 1d 20}, suggesting the group without
         * them.
         *
         * @param start      where the group's text begins
         * @param spaceStart where the offending spaces begin
         */
        private Failure spaceInside(int start, int spaceStart) {
            int spaceEnd = spaceStart;
            while (at(spaceEnd) == ' ') {
                spaceEnd++;
            }
            StringBuilder written = new StringBuilder();
            int i = start;
            boolean pastD = false;
            // Rebuild "<count>d<kind>" from what was typed, skipping the spaces, so the suggestion is the fix.
            while (i < n) {
                char c = at(i);
                if (c == ' ') {
                    i++;
                    continue;
                }
                if (isDigit(c) || (pastD && c == '%') || (!pastD && lower(c) == 'd')) {
                    pastD |= lower(c) == 'd';
                    written.append(lower(c));
                    i++;
                    if (c == '%') {
                        break;
                    }
                } else {
                    break;
                }
            }
            return new Failure("no space inside `" + written + "`", new Span(spaceStart, spaceEnd));
        }

        /**
         * Reports the character at {@code index}, which no rule of the grammar can read there: either it can
         * never appear in a Roll Expression, or it can but not at that point.
         */
        private Failure unexpected(int index) {
            int codePoint = in.codePointAt(index);
            Span span = new Span(index, index + Character.charCount(codePoint));
            if (codePoint == '\t') {
                return new Failure("a tab can't appear; use a space", span);
            }
            if (Character.isISOControl(codePoint)) {
                return new Failure("a control character (" + unicodeName(codePoint) + ") can't appear; use a space",
                        span);
            }
            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
                return new Failure(unicodeName(codePoint) + " is not a plain space; use a space", span);
            }
            String shown = in.substring(span.start(), span.end());
            if (codePoint < 0x80 && isAllowedSymbol((char) codePoint)) {
                return new Failure("`" + shown + "` can't go here; " + SHAPE_HINT, span);
            }
            return new Failure("`" + shown + "` can't appear in a Roll Expression; " + SHAPE_HINT, span);
        }

        /** Whether a {@code d} at {@code index} starts a Die Kind: it does unless it begins the word {@code dis}. */
        private boolean startsDieKind(int index) {
            return lower(at(index)) == 'd'
                    && !(lower(at(index + 1)) == 'i' && lower(at(index + 2)) == 's');
        }

        /** Whether a Die Kind's digits or {@code %} start at {@code index}. */
        private boolean dieKindFollows(int index) {
            return isDigit(at(index)) || at(index) == '%';
        }

        /** Whether spaces starting at {@code index} are followed by the digits or {@code %} of a Die Kind. */
        private boolean dieKindAfterSpaces(int index) {
            while (at(index) == ' ') {
                index++;
            }
            return dieKindFollows(index);
        }

        /** Consumes a run of digits and returns its value exactly: a count may be any length. */
        private BigInteger digits() {
            int start = pos;
            while (isDigit(at(pos))) {
                pos++;
            }
            return new BigInteger(in.substring(start, pos));
        }

        private void letters() {
            while (isLetter(at(pos))) {
                pos++;
            }
        }

        private void spaces() {
            while (at(pos) == ' ') {
                pos++;
            }
        }

        private String text(Span span) {
            return in.substring(span.start(), span.end());
        }

        /** Returns the character at {@code index}, or {@code 0} past the end, so look-ahead needs no bounds checks. */
        private char at(int index) {
            return index < n ? in.charAt(index) : 0;
        }
    }

    private static boolean isSign(char c) {
        return c == '+' || c == '-';
    }

    /** ASCII digits only: the grammar's digits are 0-9, never other scripts' digits. */
    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    /** ASCII letters only; a word of them is read whole so it can be named in an error. */
    private static boolean isLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean isAllowedSymbol(char c) {
        return c != 0 && ALPHABET_SYMBOLS.indexOf(c) >= 0;
    }

    /**
     * Lower-cases ASCII letters only. {@link String#toLowerCase} is avoided on purpose: for some non-ASCII
     * characters it changes the string's length, which would shift every span after them.
     */
    private static char lower(char c) {
        return c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c;
    }

    private static String unicodeName(int codePoint) {
        return String.format(Locale.ROOT, "U+%04X", codePoint);
    }
}
