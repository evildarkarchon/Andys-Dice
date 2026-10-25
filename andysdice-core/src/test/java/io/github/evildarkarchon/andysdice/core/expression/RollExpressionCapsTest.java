package io.github.evildarkarchon.andysdice.core.expression;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The caps are the spec's contract values (spec §4, ADR-0014), held as code constants. */
class RollExpressionCapsTest {

    @Test
    void capsAreTheSpecsValues() {
        assertThat(RollExpressionCaps.MAX_LENGTH).isEqualTo(100);
        assertThat(RollExpressionCaps.MAX_DICE_PER_THROW).isEqualTo(20);
        assertThat(RollExpressionCaps.MAX_MODIFIER).isEqualTo(999);
    }

    @Test
    void aPercentileRollCountsTwoPhysicalDice() {
        assertThat(GroupKind.PERCENTILE.physicalDice()).isEqualTo(2);
        for (GroupKind kind : GroupKind.values()) {
            if (kind != GroupKind.PERCENTILE) {
                assertThat(kind.physicalDice()).as(kind.label()).isEqualTo(1);
            }
        }
    }
}
