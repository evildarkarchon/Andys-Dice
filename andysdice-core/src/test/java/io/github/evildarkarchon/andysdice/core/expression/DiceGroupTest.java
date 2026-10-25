package io.github.evildarkarchon.andysdice.core.expression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The {@link DiceGroup} and {@link KeepRule} constructors' contracts, for callers that build terms without the
 * parser: a keep rule keeps from 1 to the group's count, and Advantage only ever marks a {@code 2d20} with its
 * own keep rule.
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
