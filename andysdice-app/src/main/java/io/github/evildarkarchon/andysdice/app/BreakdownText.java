package io.github.evildarkarchon.andysdice.app;

import io.github.evildarkarchon.andysdice.core.expression.DiceGroup;
import io.github.evildarkarchon.andysdice.core.expression.DieResult;
import io.github.evildarkarchon.andysdice.core.expression.GroupKind;
import io.github.evildarkarchon.andysdice.core.expression.KeepRule;
import io.github.evildarkarchon.andysdice.core.expression.Modifier;
import io.github.evildarkarchon.andysdice.core.expression.RerollRule;
import io.github.evildarkarchon.andysdice.core.expression.RollResult;
import io.github.evildarkarchon.andysdice.core.expression.Sign;
import io.github.evildarkarchon.andysdice.core.expression.Term;
import io.github.evildarkarchon.andysdice.core.expression.TermResult;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The words the result strip uses for a Roll Result (spec §3.2 "Layout", "Breakdown marks", "Screen readers"): each
 * term as written, a chip's hover text spelling out its Face Value Chain, the number of Throws, and the spoken
 * summary a screen reader reads from the focused strip.
 *
 * <p>UI text is fixed English and holds only plain characters (no symbol glyphs: icons are drawn in code), and a
 * minus is ASCII {@code -}: U+2212 sits in the range the shell test keeps out of UI text.
 *
 * <p>Pure; safe on any thread.
 */
final class BreakdownText {

    /** Between the parts of a hover text or the facts line. */
    static final String SEPARATOR = " · ";

    private BreakdownText() {}

    /**
     * Returns a term as the Player wrote it, for its block's heading: a Dice Group's text as typed with its
     * {@code adv}/{@code dis} after it ({@code 1d20 adv}), signed unless it leads with a plus; a Modifier always as a
     * signed number ({@code +2}, {@code -2}).
     *
     * @param term the term
     * @param first whether it is the expression's first term, whose plus isn't written
     * @return the heading
     */
    static String asWritten(Term term, boolean first) {
        return switch (term) {
            case DiceGroup group -> (group.sign() == Sign.MINUS ? "-" : first ? "" : "+") + group.text()
                    + group.advantage().map(advantage -> " " + advantage.keyword()).orElse("");
            case Modifier modifier -> modifier.sign().symbol() + Integer.toString(modifier.value());
        };
    }

    /**
     * Returns a chip's hover text: the Die Kind and every Face Value it showed, and whether it was kept, as in
     * "d6: showed [1, 1, 6], the last counts · not kept" or "d20: showed 15".
     *
     * @param kind the Die Kind of the chip's Dice Group
     * @param die the Die's result
     * @return the hover text
     */
    static String hoverText(GroupKind kind, DieResult die) {
        String shown = die.rerolled() ? die.chain().values() + ", the last counts" : Integer.toString(die.value());
        return kind.label() + ": showed " + shown + (die.kept() ? "" : SEPARATOR + "not kept");
    }

    /**
     * Returns the number of Throws as the facts line shows it, every Throw counted, Cocked Rethrows included.
     *
     * @param throwCount the Throws in the Roll's chain, at least 1
     * @return "1 Throw" or "n Throws"
     */
    static String throwsText(int throwCount) {
        return throwCount + (throwCount == 1 ? " Throw" : " Throws");
    }

    /**
     * Returns the spoken summary of a Roll Result: the total, then one sentence per term, a Dice Group described in
     * words with each Die's value, as in "Total 17. 4d6 reroll 1s keep highest 3: 6, 5, 6, 1 not kept. Plus 2." A
     * rerolled Die says how many rerolls it took ("3 after a reroll"); its earlier values are in its hover text.
     *
     * @param result the Roll Result
     * @return the summary
     */
    static String spokenSummary(RollResult result) {
        List<String> sentences = new ArrayList<>();
        sentences.add("Total " + result.total() + ".");
        boolean first = true;
        for (TermResult term : result.breakdown().terms()) {
            // A leading plus isn't read, as it isn't written.
            String sign = first && term.term().sign() == Sign.PLUS ? ""
                    : term.term().sign() == Sign.PLUS ? "Plus " : "Minus ";
            first = false;
            switch (term.term()) {
                case DiceGroup group -> sentences.add(sign + spoken(group) + ": " + term.dice().stream()
                        .map(BreakdownText::spoken).collect(Collectors.joining(", ")) + ".");
                case Modifier modifier -> sentences.add(sign + modifier.value() + ".");
            }
        }
        return String.join(" ", sentences);
    }

    /** A Dice Group in words: "4d6 reroll 1s keep highest 3", or "1d20 with advantage" for the adv rewrite. */
    private static String spoken(DiceGroup group) {
        String reroll = group.reroll().map(rule -> " " + spoken(rule)).orElse("");
        if (group.advantage().isPresent()) {
            // The rewrite came from a typed 1d20, so it reads as that, not as the 2d20kh1 it means.
            return "1" + group.kind().label() + reroll + " with " + group.advantage().get().longForm();
        }
        return group.count() + group.kind().label() + reroll
                + group.keep().map(keep -> " " + spoken(keep)).orElse("");
    }

    /** A reroll clause in words: "reroll 1s", "reroll under 3 once", "reroll 18 or over". */
    private static String spoken(RerollRule rule) {
        int value = rule.value();
        String condition = switch (rule.comparison()) {
            case EQUAL -> value + "s";
            case LESS -> "under " + value;
            case LESS_OR_EQUAL -> value + " or under";
            case GREATER -> "over " + value;
            case GREATER_OR_EQUAL -> value + " or over";
        };
        return "reroll " + condition + (rule.mode() == RerollRule.Mode.ONCE ? " once" : "");
    }

    /** A keep rule in words: "keep highest 3", "keep lowest 1". */
    private static String spoken(KeepRule keep) {
        return "keep " + (keep.direction() == KeepRule.Direction.HIGHEST ? "highest " : "lowest ") + keep.count();
    }

    /** One Die in words: "6", "1 not kept", "3 after 2 rerolls and not kept". */
    private static String spoken(DieResult die) {
        int rerolls = die.chain().values().size() - 1;
        String rerolled = rerolls == 0 ? "" : rerolls == 1 ? " after a reroll" : " after " + rerolls + " rerolls";
        String unkept = die.kept() ? "" : rerolls == 0 ? " not kept" : " and not kept";
        return die.value() + rerolled + unkept;
    }
}
