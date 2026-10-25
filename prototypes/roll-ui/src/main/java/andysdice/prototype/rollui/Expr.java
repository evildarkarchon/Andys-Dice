package andysdice.prototype.rollui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * PROTOTYPE parser for the v1 Roll Expression grammar (#7) plus the reroll forms (#13). Prototype-grade: it
 * enforces the rules a player is likely to hit so the UI has real messages and spans to show, not every corner.
 * Spans are {@code [start, end)} character offsets into the source; {@code start == end} points just past a
 * character (for example "a term must follow `+`" at the end of the input).
 */
final class Expr {

    static final int MAX_DICE = 20;
    /** Provisional, like the Dice cap: the real number comes from the "Performance targets" fog. */
    static final int MAX_LENGTH = 120;
    static final int MAX_MODIFIER = 999;

    private Expr() {
    }

    enum Kind {
        D4(4, "d4"), D6(6, "d6"), D8(8, "d8"), D10(10, "d10"), D12(12, "d12"), D20(20, "d20"), D100(100, "d%");

        final int faces;
        final String label;

        Kind(int faces, String label) {
            this.faces = faces;
            this.label = label;
        }

        /** Physical Dice this kind puts in the tray: a Percentile Roll is a percentile d10 plus a d10. */
        int physical() {
            return this == D100 ? 2 : 1;
        }
    }

    /** {@code ro} (once) or {@code rr} (until the condition no longer matches), with one value or comparison. */
    record Reroll(boolean untilClear, String op, int value) {
        boolean matches(int v) {
            return switch (op) {
                case "<" -> v < value;
                case "<=" -> v <= value;
                case ">" -> v > value;
                case ">=" -> v >= value;
                default -> v == value;
            };
        }
    }

    sealed interface Term permits Group, Modifier {
        int sign();

        /** The term exactly as the player wrote it, without its sign. */
        String text();
    }

    /**
     * A Dice Group. {@code count} and the keep rule are the effective ones: {@code 1d20 adv} has count 2 and
     * keeps the highest 1, while {@code text} stays {@code 1d20} and {@code advantage} says why.
     */
    record Group(int sign, int count, Kind kind, Reroll reroll, Boolean keepHighest, int keepN, String text,
                 String advantage) implements Term {
    }

    record Modifier(int sign, int value, String text) implements Term {
    }

    record Parsed(String source, List<Term> terms, String advantage, int physicalDice) {
    }

    record ParseError(String message, int start, int end) {
    }

    record Result(Parsed ok, ParseError error) {
    }

    static Result parse(String source) {
        try {
            return new Result(new P(source).parse(), null);
        } catch (Fail f) {
            return new Result(null, f.error);
        }
    }

    private static final class Fail extends Exception {
        final ParseError error;

        Fail(String message, int start, int end) {
            super(message, null, false, false);
            error = new ParseError(message, start, Math.max(start, end));
        }
    }

    /** Recursive descent over characters: tokens may touch ({@code 4d6rr1kh3}), so there is no tokenizer. */
    private static final class P {
        final String s;
        final String l;
        final int n;
        int pos;

        P(String s) {
            this.s = s;
            this.l = s.toLowerCase(Locale.ROOT);
            this.n = s.length();
        }

        Parsed parse() throws Fail {
            if (s.isBlank()) {
                throw new Fail("Type a Roll Expression, such as 1d20+5", 0, 0);
            }
            if (n > MAX_LENGTH) {
                throw new Fail("A Roll Expression is at most " + MAX_LENGTH + " characters", MAX_LENGTH, n);
            }
            List<Term> terms = new ArrayList<>();
            ws();
            int sign = 1;
            if (pos < n && (l.charAt(pos) == '+' || l.charAt(pos) == '-')) {
                sign = l.charAt(pos) == '+' ? 1 : -1;
                pos++;
                ws();
            }
            String adv = null;
            int advStart = -1;
            int advEnd = -1;
            while (true) {
                terms.add(term(sign));
                ws();
                if (pos >= n) {
                    break;
                }
                char c = l.charAt(pos);
                if (c == '+' || c == '-') {
                    int at = pos++;
                    ws();
                    if (pos >= n) {
                        throw new Fail("A term must follow `" + c + "`", at, at + 1);
                    }
                    if (l.charAt(pos) == '+' || l.charAt(pos) == '-') {
                        throw new Fail("Two signs in a row", at, pos + 1);
                    }
                    sign = c == '+' ? 1 : -1;
                    continue;
                }
                if (Character.isLetter(c)) {
                    int ws = pos;
                    String word = letters();
                    switch (word) {
                        case "adv", "dis" -> {
                            adv = word;
                            advStart = ws;
                            advEnd = pos;
                        }
                        case "advantage" -> throw new Fail("Write `adv`", ws, pos);
                        case "disadvantage" -> throw new Fail("Write `dis`", ws, pos);
                        default -> throw new Fail("`" + s.substring(ws, pos) + "` isn't part of a Roll Expression", ws, pos);
                    }
                    ws();
                    if (pos < n) {
                        if (Character.isLetter(l.charAt(pos))) {
                            int w2 = pos;
                            String second = letters();
                            if (second.equals("adv") || second.equals("dis")) {
                                throw new Fail("Use only one of adv or dis", w2, pos);
                            }
                            pos = w2;
                        }
                        throw new Fail("`" + adv + "` must come last", advStart, advEnd);
                    }
                    break;
                }
                if (c == '#') {
                    throw new Fail("Labels and comments aren't allowed; keep a name with a Saved Roll instead", pos, n);
                }
                if (c == '*' || c == '/') {
                    throw new Fail("Only + and - are allowed; there is no * or /", pos, pos + 1);
                }
                if (c == '(' || c == ')') {
                    throw new Fail("Parentheses aren't allowed; a Roll Expression is a flat sum", pos, pos + 1);
                }
                if (Character.isDigit(c)) {
                    throw new Fail("Put + or - between terms", pos, pos + 1);
                }
                throw new Fail("`" + s.charAt(pos) + "` isn't part of a Roll Expression", pos, pos + 1);
            }

            List<Group> groups = terms.stream().filter(Group.class::isInstance).map(Group.class::cast).toList();
            if (groups.isEmpty()) {
                throw new Fail("Add at least one Dice Group: there is nothing to Throw", 0, n);
            }
            if (adv != null) {
                List<Group> d20s = groups.stream().filter(g -> g.kind() == Kind.D20).toList();
                if (d20s.isEmpty()) {
                    throw new Fail("`" + adv + "` needs a 1d20 term to apply to", advStart, advEnd);
                }
                if (d20s.size() > 1) {
                    throw new Fail("`" + adv + "` needs exactly one d20 term; there are " + d20s.size(), advStart, advEnd);
                }
                Group g = d20s.getFirst();
                if (g.sign() < 0) {
                    throw new Fail("`" + adv + "` can't apply to a subtracted d20", advStart, advEnd);
                }
                if (g.count() > 1) {
                    throw new Fail("`" + adv + "` applies to 1d20, not " + g.text(), advStart, advEnd);
                }
                if (g.keepHighest() != null) {
                    throw new Fail("`" + adv + "` can't be combined with a keep rule", advStart, advEnd);
                }
                Group effective = new Group(g.sign(), 2, Kind.D20, g.reroll(), adv.equals("adv"), 1, g.text(), adv);
                terms.set(terms.indexOf(g), effective);
            }
            int dice = 0;
            for (Term t : terms) {
                if (t instanceof Group g) {
                    dice += g.count() * g.kind().physical();
                }
            }
            if (dice > MAX_DICE) {
                throw new Fail("That's " + dice + " Dice; one Throw holds at most " + MAX_DICE, 0, n);
            }
            return new Parsed(s, List.copyOf(terms), adv, dice);
        }

        Term term(int sign) throws Fail {
            int start = pos;
            if (pos >= n) {
                throw new Fail("Expected a Dice Group (like 2d6) or a Modifier (like 3)", pos, pos);
            }
            char c = l.charAt(pos);
            if (Character.isDigit(c)) {
                long value = number();
                if (pos < n && l.charAt(pos) == 'd' && !at("dis")) {
                    return group(start, value, sign);
                }
                int save = pos;
                ws();
                if (pos > save && pos < n && l.charAt(pos) == 'd' && !at("dis")) {
                    throw new Fail("No spaces inside a Dice Group: write " + value + "d…", start, pos + 1);
                }
                pos = save;
                if (value > MAX_MODIFIER) {
                    throw new Fail("A Modifier is at most " + MAX_MODIFIER, start, pos);
                }
                return new Modifier(sign, (int) value, s.substring(start, pos));
            }
            if (c == 'd') {
                return group(start, 1, sign);
            }
            if (c == '+' || c == '-') {
                throw new Fail("Two signs in a row", pos, pos + 1);
            }
            if (c == '(' || c == ')') {
                throw new Fail("Parentheses aren't allowed; a Roll Expression is a flat sum", pos, pos + 1);
            }
            if (at("adv") || at("dis")) {
                throw new Fail("`" + s.substring(pos, pos + 3) + "` must come last, as in 1d20+5 adv", pos, pos + 3);
            }
            throw new Fail("Expected a Dice Group (like 2d6) or a Modifier (like 3)", pos, pos + 1);
        }

        Group group(int start, long count, int sign) throws Fail {
            int dAt = pos++;
            Kind kind;
            if (pos < n && l.charAt(pos) == '%') {
                pos++;
                kind = Kind.D100;
            } else if (pos < n && Character.isDigit(l.charAt(pos))) {
                long k = number();
                kind = switch ((int) Math.min(k, 1000)) {
                    case 4 -> Kind.D4;
                    case 6 -> Kind.D6;
                    case 8 -> Kind.D8;
                    case 10 -> Kind.D10;
                    case 12 -> Kind.D12;
                    case 20 -> Kind.D20;
                    case 100 -> Kind.D100;
                    default -> throw new Fail("`" + s.substring(dAt, pos)
                            + "` is not a Die Kind; use d4, d6, d8, d10, d12, d20 or d%", dAt, pos);
                };
            } else if (pos < n && Character.isLetter(l.charAt(pos))) {
                letters();
                throw new Fail("`" + s.substring(dAt, pos) + "` is not a Die Kind; use d4, d6, d8, d10, d12, d20 or d%", dAt, pos);
            } else {
                throw new Fail("A Die Kind must follow `d`: d4, d6, d8, d10, d12, d20 or d%", dAt, pos);
            }
            if (count < 1) {
                throw new Fail("A Dice Group needs at least 1 Die", start, pos);
            }
            if (count > 999) {
                throw new Fail("That's " + count + " Dice; one Throw holds at most " + MAX_DICE, start, pos);
            }

            Reroll reroll = null;
            Boolean keepHighest = null;
            int keepN = 0;
            int keepStart = -1;
            while (pos < n) {
                int rs = pos;
                if (at("ro") || at("rr")) {
                    if (keepHighest != null) {
                        throw new Fail("Write the reroll rule before the keep rule, as in 4d6rr1kh3", rs, rs + 2);
                    }
                    if (reroll != null) {
                        throw new Fail("Only one reroll rule per Dice Group", rs, rs + 2);
                    }
                    boolean until = at("rr");
                    pos += 2;
                    String op = "=";
                    for (String o : new String[] {"<=", ">=", "<", ">"}) {
                        if (at(o)) {
                            op = o;
                            pos += o.length();
                            break;
                        }
                    }
                    if (pos >= n || !Character.isDigit(l.charAt(pos))) {
                        throw new Fail("Give a value or comparison after `" + s.substring(rs, rs + 2)
                                + "`, as in ro1 or rr<3", rs, pos);
                    }
                    long v = number();
                    reroll = new Reroll(until, op, (int) Math.min(v, Integer.MAX_VALUE));
                    int matches = 0;
                    for (int f = 1; f <= kind.faces; f++) {
                        if (reroll.matches(f)) {
                            matches++;
                        }
                    }
                    String rule = s.substring(rs, pos);
                    if (matches == 0) {
                        throw new Fail("`" + rule + "` never matches a " + kind.label + " face", rs, pos);
                    }
                    if (matches == kind.faces) {
                        throw new Fail("`" + rule + "` matches every " + kind.label + " face", rs, pos);
                    }
                    if (until && matches > kind.faces / 2) {
                        throw new Fail("`rr` may match at most half of a " + kind.label + "'s faces ("
                                + kind.faces / 2 + "); this matches " + matches, rs, pos);
                    }
                } else if (l.charAt(pos) == 'r') {
                    throw new Fail("A bare `r` is ambiguous: write `ro` to reroll once, or `rr` to reroll until it no "
                            + "longer matches", rs, rs + 1);
                } else if (at("kh") || at("kl")) {
                    if (keepHighest != null) {
                        throw new Fail("Only one keep rule per Dice Group", rs, rs + 2);
                    }
                    keepHighest = at("kh");
                    keepStart = rs;
                    pos += 2;
                    keepN = pos < n && Character.isDigit(l.charAt(pos)) ? (int) Math.min(number(), 10_000) : 1;
                    if (keepN < 1 || keepN > count) {
                        throw new Fail("Keep between 1 and " + count + " here", keepStart, pos);
                    }
                } else if (l.charAt(pos) == 'k') {
                    throw new Fail("Write `kh` or `kl`, as in 4d6kh3", rs, rs + 1);
                } else if (at("dis") || at("adv")) {
                    break;
                } else if (at("dl") || at("dh")) {
                    throw new Fail("Drop rules aren't supported; use the keep form, as in 4d6kh3", rs, rs + 2);
                } else if (l.charAt(pos) == '!') {
                    throw new Fail("Exploding dice aren't supported", rs, rs + 1);
                } else if (Character.isLetter(l.charAt(pos))) {
                    letters();
                    throw new Fail("`" + s.substring(rs, pos) + "` isn't a rule; a Dice Group takes ro/rr and kh/kl",
                            rs, pos);
                } else {
                    break;
                }
            }
            return new Group(sign, (int) count, kind, reroll, keepHighest, keepN, s.substring(start, pos), null);
        }

        boolean at(String token) {
            return l.startsWith(token, pos);
        }

        void ws() {
            while (pos < n && Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
        }

        String letters() {
            int st = pos;
            while (pos < n && Character.isLetter(l.charAt(pos))) {
                pos++;
            }
            return l.substring(st, pos);
        }

        long number() {
            long v = 0;
            while (pos < n && Character.isDigit(l.charAt(pos))) {
                v = Math.min(v * 10 + (l.charAt(pos) - '0'), 1_000_000_000L);
                pos++;
            }
            return v;
        }
    }
}
