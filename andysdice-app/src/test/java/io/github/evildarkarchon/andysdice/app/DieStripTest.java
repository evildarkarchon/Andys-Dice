package io.github.evildarkarchon.andysdice.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * What a die-strip button does to the box's text (spec §3.2 "Layout"): it appends {@code +1dX}, or {@code 1dX}
 * when the box is empty. Pure: the UI test covers the button, the caret and a selected expression.
 */
class DieStripTest {

    @Test
    void theStripHoldsTheSevenDiceInOrder() {
        assertThat(DieStrip.DICE).containsExactly("d4", "d6", "d8", "d10", "d12", "d20", "d%");
    }

    @Test
    void anEmptyBoxGetsOneDie() {
        assertThat(DieStrip.append("", "d20")).isEqualTo("1d20");
        assertThat(DieStrip.append("", "d%")).isEqualTo("1d%");
    }

    @Test
    void aBoxOfOnlySpacesCountsAsEmpty() {
        assertThat(DieStrip.append("   ", "d6")).isEqualTo("1d6");
    }

    @Test
    void anExpressionGetsPlusOneDie() {
        assertThat(DieStrip.append("1d20+5", "d6")).isEqualTo("1d20+5+1d6");
        assertThat(DieStrip.append("4d6rr1kh3", "d%")).isEqualTo("4d6rr1kh3+1d%");
    }

    @Test
    void trailingSpacesAreDroppedBeforeTheNewDie() {
        assertThat(DieStrip.append("1d20 adv ", "d4")).isEqualTo("1d20 adv+1d4");
    }

    @Test
    void whatIsAlreadyThereIsKeptAsTypedEvenIfItDoesNotParse() {
        // Errors are checked on Enter only (spec §3.2 "Entry"), so the strip never judges the box.
        assertThat(DieStrip.append("2d6+", "d8")).isEqualTo("2d6++1d8");
    }
}
