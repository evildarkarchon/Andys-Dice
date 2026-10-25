package io.github.evildarkarchon.andysdice.core.expression;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Parses Roll Expressions as docs/roll-expression-grammar.md defines them: all or nothing, with only the first
 * error reported, as one message and one {@code [start, end)} span into the input as typed.
 *
 * <p>Errors come in the grammar's order: the {@linkplain RollExpressionCaps#MAX_LENGTH length cap} first, then
 * the first character, syntax or per-term problem reading left to right, then the whole-expression checks (at
 * least one Dice Group, then {@code adv}/{@code dis}, then
 * {@linkplain RollExpressionCaps#MAX_DICE_PER_THROW Dice per Throw}).
 *
 * <p>It is a recursive descent over characters rather than tokens, because tokens may touch ({@code 2d6+3},
 * {@code 4d6kh3}, {@code 1d20adv}, and with #29 {@code 4d6rr1kh3}) and because {@code d} starts a Die Kind, a
 * drop form and {@code dis}. Keep rules are read at {@code Scan.groupSuffix}, where rerolls (#29) go before
 * them. The trailing {@code adv}/{@code dis} is read in the term loop by {@code Scan.trailingKeyword} and
 * applied in {@code Scan.checkWholeExpression}, which rewrites its {@code 1d20} into {@code 2d20kh1} or
 * {@code 2d20kl1} before the Dice are counted.
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

    /**
     * A Dice Group read during the scan.
     *
     * @param keep      the keep rule, or null if none was written
     * @param advantage the {@code adv}/{@code dis} that rewrote it, or null; set only by the whole-expression check
     */
    private record GroupDraft(Sign sign, BigInteger count, GroupKind kind, KeepDraft keep, Advantage advantage,
                              Span span, String text) implements Draft {

        BigInteger physicalDice() {
            return count.multiply(BigInteger.valueOf(kind.physicalDice()));
        }

        /** Returns the group as {@code adv}/{@code dis} rewrites it: {@code 2d20} keeping the higher or lower. */
        GroupDraft rewrittenBy(Advantage rewrite) {
            KeepRule rule = rewrite.keepRule();
            return new GroupDraft(sign, BigInteger.TWO, kind,
                    new KeepDraft(rule.direction(), BigInteger.valueOf(rule.count())), rewrite, span, text);
        }

        /**
         * Only called once the Dice-per-Throw check has passed, so {@code count}, and the keep count that the keep
         * range check held to at most it, fit an int.
         */
        @Override
        public Term toTerm() {
            return new DiceGroup(sign, count.intValueExact(), kind,
                    Optional.ofNullable(keep).map(k -> new KeepRule(k.direction(), k.count().intValueExact())),
                    Optional.ofNullable(advantage), span, text);
        }
    }

    /** A keep rule read during the scan; its count is exact for the same reason as a group's. */
    private record KeepDraft(KeepRule.Direction direction, BigInteger count) {
    }

    /** The trailing {@code adv}/{@code dis} and where it was typed, which every Advantage error is marked on. */
    private record KeywordDraft(Advantage advantage, Span span) {
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
            KeywordDraft keyword = null;
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
                if (advantageKeywordAt(pos) != null) {
                    // It needs no space before it (1d20adv), and it must be the last thing written.
                    keyword = trailingKeyword();
                    break;
                }
                throw afterTerm(drafts.getLast(), termEnd);
            }
            checkWholeExpression(drafts, keyword);
            return new RollExpression(in, drafts.stream().map(Draft::toTerm).toList());
        }

        /**
         * Reads the {@code adv}/{@code dis} at {@code pos} and checks that nothing but spaces follows it.
         *
         * @throws Failure if a second keyword follows it, or anything else does
         */
        private KeywordDraft trailingKeyword() throws Failure {
            int start = pos;
            Advantage advantage = advantageKeywordAt(pos);
            letters();
            KeywordDraft keyword = new KeywordDraft(advantage, new Span(start, pos));
            spaces();
            if (pos < n) {
                if (advantageKeywordAt(pos) != null) {
                    int second = pos;
                    letters();
                    throw new Failure("use only one of adv or dis", new Span(second, pos));
                }
                throw new Failure("`" + advantage.keyword() + "` goes at the end", keyword.span());
            }
            return keyword;
        }

        /**
         * Returns the keyword whose whole run of letters starts at {@code index}, or null if that run isn't exactly
         * {@code adv} or {@code dis} in some case ({@code advantage} is not).
         */
        private Advantage advantageKeywordAt(int index) {
            int end = index;
            while (isLetter(at(end))) {
                end++;
            }
            String run = lower(in.substring(index, end));
            for (Advantage advantage : Advantage.values()) {
                if (advantage.keyword().equals(run)) {
                    return advantage;
                }
            }
            return null;
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
            KeepDraft keep = groupSuffix(start, count, kind);
            Span span = new Span(start, pos);
            return new GroupDraft(sign, count, kind, keep, null, span, text(span));
        }

        /**
         * Reads what may follow a Dice Group's Die Kind: the reroll clause (#29) and then the keep rule, before the
         * scan moves on, so that {@code 4d6rr1kh3} stays one Dice Group. Anything else after the Die Kind is left
         * for {@code afterTerm}.
         *
         * @param start where the group began, for the suggestion when a space splits the keep rule off
         * @param count the group's count as written, which the keep rule's N may not exceed
         * @param kind  the group's Die Kind, to name the group in messages
         * @return the keep rule, or null if none was written
         * @throws Failure for a drop form, a bare {@code k}, a keep count out of range, a space inside the keep
         *                 rule or a second keep rule
         */
        private KeepDraft groupSuffix(int start, BigInteger count, GroupKind kind) throws Failure {
            // #29: the reroll clause is read here, before the keep rule.
            rejectDropForm(count, kind);
            KeepDraft keep = keepRule(count, kind);
            if (keep == null && at(pos) == ' ' && keepRuleAfterSpaces(pos)) {
                // "4d6 kh3": the keep rule is part of the Dice Group, so no space may split it off.
                int spaceStart = pos;
                spaces();
                int keepStart = pos;
                pos += 2;
                digitsIfAny();
                throw new Failure("no space inside `" + in.substring(start, spaceStart) + in.substring(keepStart, pos)
                        + "`", spaceRun(spaceStart));
            }
            if (keep != null && lower(at(pos)) == 'k') {
                int second = pos++;
                if (lower(at(pos)) == 'h' || lower(at(pos)) == 'l') {
                    pos++;
                }
                digitsIfAny();
                throw new Failure("one keep rule per Dice Group", new Span(second, pos));
            }
            return keep;
        }

        /**
         * Reads a keep rule at {@code pos}, if one starts there, and checks that it keeps from 1 to the group's
         * count.
         *
         * @return the keep rule, or null if {@code pos} doesn't start one
         * @throws Failure for a bare {@code k}, a keep of 0 or more than the group's count, or a space before N
         */
        private KeepDraft keepRule(BigInteger count, GroupKind kind) throws Failure {
            if (lower(at(pos)) != 'k') {
                return null;
            }
            int start = pos;
            char which = lower(at(pos + 1));
            if (which != 'h' && which != 'l') {
                throw new Failure("write `kh` (keep highest) or `kl` (keep lowest)", new Span(start, start + 1));
            }
            pos += 2;
            if (at(pos) == ' ' && digitAfterSpaces(pos)) {
                int spaceStart = pos;
                spaces();
                int digitsStart = pos;
                digits();
                throw new Failure("no space inside `" + in.substring(start, spaceStart)
                        + in.substring(digitsStart, pos) + "`", spaceRun(spaceStart));
            }
            BigInteger keepCount = isDigit(at(pos)) ? digits() : BigInteger.ONE;
            Span span = new Span(start, pos);
            if (keepCount.signum() == 0) {
                throw new Failure("`" + text(span) + "` keeps no Dice; keep at least 1", span);
            }
            if (keepCount.compareTo(count) > 0) {
                throw new Failure("`" + count + kind.label() + "` can keep at most " + count, span);
            }
            return new KeepDraft(which == 'h' ? KeepRule.Direction.HIGHEST : KeepRule.Direction.LOWEST, keepCount);
        }

        /**
         * Rejects a drop form ({@code dlN}, {@code dhN}) at {@code pos}, suggesting the keep form that means the
         * same: dropping the lowest N keeps the highest count − N, and dropping the highest keeps the lowest.
         */
        private void rejectDropForm(BigInteger count, GroupKind kind) throws Failure {
            char which = lower(at(pos + 1));
            if (lower(at(pos)) != 'd' || (which != 'l' && which != 'h')) {
                return;
            }
            int start = pos;
            pos += 2;
            BigInteger dropped = isDigit(at(pos)) ? digits() : BigInteger.ONE;
            BigInteger keptCount = count.subtract(dropped);
            String suggestion = keptCount.signum() > 0
                    ? "`" + count + kind.label() + (which == 'l' ? "kh" : "kl") + keptCount + "`"
                    // Dropping every Die (or more) has no keep form that means the same, so name both forms.
                    : "`kh` or `kl`";
            throw new Failure("drop forms aren't used; use the keep form, " + suggestion, new Span(start, pos));
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
         * Reports the run of letters at {@code pos} that isn't part of any term. A trailing {@code adv}/{@code dis}
         * never reaches here, so one that does stands where a term belongs; the long forms {@code advantage} and
         * {@code disadvantage} get their short form suggested.
         */
        private Failure word() {
            int start = pos;
            Advantage misplaced = advantageKeywordAt(pos);
            letters();
            Span span = new Span(start, pos);
            if (misplaced != null) {
                return new Failure("`" + misplaced.keyword()
                        + "` goes at the end, after the last term with no sign before it", span);
            }
            String run = lower(text(span));
            for (Advantage advantage : Advantage.values()) {
                if (run.equals(advantage.longForm())) {
                    return new Failure("write `" + advantage.keyword() + "`; only the short form is accepted", span);
                }
            }
            return new Failure("`" + text(span) + "` isn't part of a Roll Expression; " + SHAPE_HINT, span);
        }

        /**
         * The whole-expression checks, in the grammar's order: at least one Dice Group, then {@code adv}/
         * {@code dis}, then Dice per Throw. An {@code adv}/{@code dis} that passes rewrites its {@code 1d20} draft
         * in {@code drafts} into {@code 2d20kh1}/{@code 2d20kl1} before the Dice are counted.
         *
         * @param keyword the trailing {@code adv}/{@code dis}, or null if none was written
         */
        private void checkWholeExpression(List<Draft> drafts, KeywordDraft keyword) throws Failure {
            if (drafts.stream().noneMatch(GroupDraft.class::isInstance)) {
                int first = drafts.getFirst().span().start();
                int last = drafts.getLast().span().end();
                throw new Failure("nothing to Throw; add a Dice Group such as `1d20`", new Span(first, last));
            }
            if (keyword != null) {
                applyAdvantage(drafts, keyword);
            }
            List<GroupDraft> groups = drafts.stream()
                    .filter(GroupDraft.class::isInstance).map(GroupDraft.class::cast).toList();
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

        /**
         * Checks that {@code keyword} has the one term it can rewrite, an added {@code 1d20} with no keep rule and
         * the only d20 term, and rewrites it in place. The checks run in the grammar's order of its rules, and every
         * error is marked on the keyword.
         *
         * @throws Failure if there is no d20 term, more than one, or the one has a count above 1, its own keep rule
         *                 or a minus sign
         */
        private void applyAdvantage(List<Draft> drafts, KeywordDraft keyword) throws Failure {
            String word = "`" + keyword.advantage().keyword() + "`";
            String rewritten = "`2d20" + keyword.advantage().keepRule().canonical() + "`";
            List<GroupDraft> d20s = drafts.stream().filter(GroupDraft.class::isInstance).map(GroupDraft.class::cast)
                    .filter(g -> g.kind() == GroupKind.D20).toList();
            if (d20s.isEmpty()) {
                throw new Failure(word + " needs one added `1d20`", keyword.span());
            }
            if (d20s.size() > 1) {
                throw new Failure(word + " needs exactly one d20 term", keyword.span());
            }
            GroupDraft d20 = d20s.getFirst();
            String fix = "; write " + rewritten + " or `1d20 " + keyword.advantage().keyword() + "`";
            if (!d20.count().equals(BigInteger.ONE)) {
                throw new Failure(word + " needs one added `1d20`" + fix, keyword.span());
            }
            if (d20.keep() != null) {
                throw new Failure(word + " needs one added `1d20` without a keep rule" + fix, keyword.span());
            }
            if (d20.sign() == Sign.MINUS) {
                throw new Failure(word + " needs one added `1d20`, not a subtracted one", keyword.span());
            }
            // Rewritten before the Dice-per-Throw check, so Advantage's second d20 counts towards the cap.
            drafts.set(drafts.indexOf(d20), d20.rewrittenBy(keyword.advantage()));
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

        /** Whether spaces starting at {@code index} are followed by {@code kh} or {@code kl}, in either case. */
        private boolean keepRuleAfterSpaces(int index) {
            while (at(index) == ' ') {
                index++;
            }
            return lower(at(index)) == 'k' && (lower(at(index + 1)) == 'h' || lower(at(index + 1)) == 'l');
        }

        /** Whether spaces starting at {@code index} are followed by a digit. */
        private boolean digitAfterSpaces(int index) {
            while (at(index) == ' ') {
                index++;
            }
            return isDigit(at(index));
        }

        /** Returns the span of the run of spaces starting at {@code start}. */
        private Span spaceRun(int start) {
            int end = start;
            while (at(end) == ' ') {
                end++;
            }
            return new Span(start, end);
        }

        /** Consumes a run of digits, if one starts at {@code pos}, so that an error's span covers it. */
        private void digitsIfAny() {
            while (isDigit(at(pos))) {
                pos++;
            }
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

    /** Lower-cases the ASCII letters of {@code s}, keeping its length: see {@link #lower(char)}. */
    private static String lower(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            out.append(lower(s.charAt(i)));
        }
        return out.toString();
    }

    private static String unicodeName(int codePoint) {
        return String.format(Locale.ROOT, "U+%04X", codePoint);
    }
}
