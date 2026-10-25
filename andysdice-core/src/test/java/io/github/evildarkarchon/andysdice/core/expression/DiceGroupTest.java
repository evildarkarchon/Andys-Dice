package io.github.evildarkarchon.andysdice.core.expression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The {@link DiceGroup}, {@link KeepRule} and {@link RerollRule} contracts, for callers that build terms without
 * the parser: a keep rule keeps from 1 to the group's count, a reroll condition suits its Die Kind, and Advantage
 * only ever marks a {@code 2d20} with its own keep rule.
 */
class DiceGroupTest {

    private static final Span SPAN = new Span(0, 4);

    private static Optional<KeepRule> keep(KeepRule.Direction direction, int count) {
        return Optional.of(new KeepRule(direction, count));
    }

    @Test
    void aKeepRuleKeepsAtLeastOneAndAtMostTheGroupsCount() {
        assertThatIllegalArgumentException().isThrownBy(() -> new KeepRule(KeepRule.Direction.HIGHEST, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new DiceGroup(Sign.PLUS, 4, GroupKind.D6,
                keep(KeepRule.Direction.HIGHEST, 5), Optional.empty(), SPAN, "4d6"));

        DiceGroup all = new DiceGroup(Sign.PLUS, 4, GroupKind.D6, keep(KeepRule.Direction.LOWEST, 4),
                Optional.empty(), SPAN, "4d6");
        assertThat(all.canonical()).isEqualTo("4d6kl4");
    }

    private static Optional<RerollRule> reroll(RerollRule.Mode mode, RerollRule.Comparison comparison, int value) {
        return Optional.of(new RerollRule(mode, comparison, value));
    }

    /**
     * A reroll condition matches at least one value of the Die Kind and not every one, and an {@code rr}
     * condition at most half; {@code canonical()} writes the reroll before the keep rule.
     */
    @Test
    void aRerollMatchesSomeButNotAllFacesAndRrAtMostHalf() {
        assertThatIllegalArgumentException().isThrownBy(() -> new DiceGroup(Sign.PLUS, 1, GroupKind.D6,
                reroll(RerollRule.Mode.ONCE, RerollRule.Comparison.EQUAL, 7), Optional.empty(), Optional.empty(),
                SPAN, "1d6ro7"));
        assertThatIllegalArgumentException().isThrownBy(() -> new DiceGroup(Sign.PLUS, 1, GroupKind.D6,
                reroll(RerollRule.Mode.ONCE, RerollRule.Comparison.GREATER_OR_EQUAL, 1), Optional.empty(),
                Optional.empty(), SPAN, "1d6ro>=1"));
        assertThatIllegalArgumentException().isThrownBy(() -> new DiceGroup(Sign.PLUS, 1, GroupKind.D20,
                reroll(RerollRule.Mode.UNTIL, RerollRule.Comparison.LESS_OR_EQUAL, 11), Optional.empty(),
                Optional.empty(), SPAN, "1d20rr<=11"));
        assertThatIllegalArgumentException().isThrownBy(() -> new RerollRule(RerollRule.Mode.ONCE,
                RerollRule.Comparison.EQUAL, -1));

        DiceGroup half = new DiceGroup(Sign.PLUS, 4, GroupKind.D20,
                reroll(RerollRule.Mode.UNTIL, RerollRule.Comparison.LESS_OR_EQUAL, 10),
                keep(KeepRule.Direction.HIGHEST, 3), Optional.empty(), SPAN, "4d20rr<=10kh3");
        assertThat(half.canonical()).isEqualTo("4d20rr<=10kh3");
        DiceGroup percentile = new DiceGroup(Sign.PLUS, 1, GroupKind.PERCENTILE,
                reroll(RerollRule.Mode.ONCE, RerollRule.Comparison.GREATER, 99), Optional.empty(), Optional.empty(),
                SPAN, "d%ro>99");
        assertThat(percentile.canonical()).isEqualTo("1d%ro>99");
    }

    /** What the Throw planner asks of a reroll rule: does a Die that has shown these values get Rerolled again? */
    @Test
    void aRerollRuleSaysWhetherADieIsRerolledAgain() {
        RerollRule once = new RerollRule(RerollRule.Mode.ONCE, RerollRule.Comparison.LESS_OR_EQUAL, 2);
        assertThat(once.rerollsAgain(FaceValueChain.of(2))).isTrue();
        assertThat(once.rerollsAgain(FaceValueChain.of(3))).isFalse();
        assertThat(once.rerollsAgain(FaceValueChain.of(1, 1))).isFalse();

        RerollRule until = new RerollRule(RerollRule.Mode.UNTIL, RerollRule.Comparison.GREATER, 4);
        assertThat(until.rerollsAgain(FaceValueChain.of(5))).isTrue();
        assertThat(until.rerollsAgain(FaceValueChain.of(5, 6))).isTrue();
        assertThat(until.rerollsAgain(FaceValueChain.of(5, 6, 4))).isFalse();

        assertThat(until.matchingFaces(GroupKind.D8)).isEqualTo(4);
        assertThat(new RerollRule(RerollRule.Mode.ONCE, RerollRule.Comparison.LESS, 3).matches(3)).isFalse();
        assertThat(new RerollRule(RerollRule.Mode.ONCE, RerollRule.Comparison.EQUAL, 3).matches(3)).isTrue();
        assertThat(new RerollRule(RerollRule.Mode.ONCE, RerollRule.Comparison.GREATER_OR_EQUAL, 3).matches(3))
                .isTrue();
    }

    @Test
    void advantageOnlyMarksA2d20WithItsKeepRule() {
        assertThatIllegalArgumentException().isThrownBy(() -> new DiceGroup(Sign.PLUS, 1, GroupKind.D20,
                Optional.empty(), Optional.of(Advantage.ADVANTAGE), SPAN, "1d20"));
        assertThatIllegalArgumentException().isThrownBy(() -> new DiceGroup(Sign.PLUS, 2, GroupKind.D20,
                keep(KeepRule.Direction.LOWEST, 1), Optional.of(Advantage.ADVANTAGE), SPAN, "1d20"));
        assertThatIllegalArgumentException().isThrownBy(() -> new DiceGroup(Sign.PLUS, 2, GroupKind.D12,
                keep(KeepRule.Direction.HIGHEST, 1), Optional.of(Advantage.ADVANTAGE), SPAN, "1d12"));

        DiceGroup disadvantage = new DiceGroup(Sign.PLUS, 2, GroupKind.D20, keep(KeepRule.Direction.LOWEST, 1),
                Optional.of(Advantage.DISADVANTAGE), SPAN, "1d20");
        assertThat(disadvantage.canonical()).isEqualTo("2d20kl1");
        assertThat(disadvantage.physicalDice()).isEqualTo(2);
    }
}
