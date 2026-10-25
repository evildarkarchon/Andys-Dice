package io.github.evildarkarchon.andysdice.core.expression;

import java.util.Objects;
import java.util.Optional;

/**
 * A Dice Group: a count of Dice of one Die Kind, such as {@code 2d6} or {@code d%}, with an optional reroll clause
 * and an optional keep rule ({@code 4d6rr1kh3}).
 *
 * <p>Advantage and Disadvantage are a rewrite, not a kind of their own: the {@code 1d20} of {@code 1d20+5 adv}
 * becomes a group of count 2 keeping the highest 1, with {@code advantage} saying why. Its {@code span} and
 * {@code text} stay the {@code 1d20} as typed. A reroll on that {@code 1d20} carries over to both d20s
 * ({@code 1d20ro1 adv} is {@code 2d20ro1kh1}). The evaluator reads each component in one place: rerolls are
 * settled before keeps.
 *
 * @param sign      whether the group's subtotal is added or subtracted
 * @param count     how many rolls of {@code kind} the group makes, at least 1 (a missing count is 1); 2 for a group
 *                  rewritten by Advantage or Disadvantage
 * @param kind      the Die Kind rolled
 * @param reroll    the reroll clause, if any; empty means no roll is Rerolled
 * @param keep      the keep rule, if any; empty means every roll is kept
 * @param advantage the {@code adv}/{@code dis} that rewrote this group from {@code 1d20}, if any
 * @param span      where the group stands in the input, from its count (or {@code d}) to its end, without its sign
 * @param text      the group exactly as typed
 */
public record DiceGroup(Sign sign, int count, GroupKind kind, Optional<RerollRule> reroll, Optional<KeepRule> keep,
                        Optional<Advantage> advantage, Span span, String text) implements Term {

    /**
     * Checks the components.
     *
     * @throws IllegalArgumentException if {@code count} is less than 1, the reroll condition matches no value of
     *                                  {@code kind} or every value, an {@code rr} condition matches more than half
     *                                  of them, the keep rule keeps more rolls than the group makes, or
     *                                  {@code advantage} is given for anything but {@code 2d20} with its keep rule
     */
    public DiceGroup {
        Objects.requireNonNull(sign, "sign");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(reroll, "reroll");
        Objects.requireNonNull(keep, "keep");
        Objects.requireNonNull(advantage, "advantage");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(text, "text");
        if (count < 1) {
            throw new IllegalArgumentException("a Dice Group throws at least 1 Die: " + count);
        }
        if (reroll.isPresent()) {
            int matching = reroll.get().matchingFaces(kind);
            if (matching == 0 || matching == kind.faces()) {
                throw new IllegalArgumentException(reroll.get().canonical() + " matches " + matching + " of a "
                        + kind.label() + "'s " + kind.faces() + " values; a reroll matches at least 1 and not all");
            }
            // ADR-0006's parse-time bound on rr: at most half the faces keeps the expected rounds per Die at 2.
            if (reroll.get().mode() == RerollRule.Mode.UNTIL && matching > kind.faces() / 2) {
                throw new IllegalArgumentException(reroll.get().canonical() + " matches " + matching + " of a "
                        + kind.label() + "'s " + kind.faces() + " values; rr matches at most half");
            }
        }
        if (keep.isPresent() && keep.get().count() > count) {
            throw new IllegalArgumentException(count + kind.label() + " can't keep " + keep.get().count());
        }
        if (advantage.isPresent() && (kind != GroupKind.D20 || count != 2
                || !keep.equals(Optional.of(advantage.get().keepRule())))) {
            throw new IllegalArgumentException("`" + advantage.get().keyword() + "` rewrites 1d20 into 2d20"
                    + advantage.get().keepRule().canonical() + ", not " + count + kind.label() + keep.map(
                            KeepRule::canonical).orElse(""));
        }
    }

    /**
     * Makes a Dice Group with no reroll clause, as {@code 4d6kh3} or {@code 1d20 adv} parse.
     *
     * @throws IllegalArgumentException as the canonical constructor does
     */
    public DiceGroup(Sign sign, int count, GroupKind kind, Optional<KeepRule> keep, Optional<Advantage> advantage,
                     Span span, String text) {
        this(sign, count, kind, Optional.empty(), keep, advantage, span, text);
    }

    /**
     * Makes a Dice Group with no reroll clause, no keep rule and no Advantage, in which every roll counts.
     *
     * @throws IllegalArgumentException if {@code count} is less than 1
     */
    public DiceGroup(Sign sign, int count, GroupKind kind, Span span, String text) {
        this(sign, count, kind, Optional.empty(), Optional.empty(), Optional.empty(), span, text);
    }

    /** Returns how many physical Dice the group puts in the Tray: a Percentile Roll counts 2, and unkept Dice count. */
    public int physicalDice() {
        return count * kind.physicalDice();
    }

    /**
     * Returns what the group means, reroll before keep, such as {@code 4d6rr1kh3}, or {@code 2d20ro1kh1} for a
     * {@code 1d20ro1} with Advantage.
     */
    @Override
    public String canonical() {
        return count + kind.label() + reroll.map(RerollRule::canonical).orElse("")
                + keep.map(KeepRule::canonical).orElse("");
    }
}
