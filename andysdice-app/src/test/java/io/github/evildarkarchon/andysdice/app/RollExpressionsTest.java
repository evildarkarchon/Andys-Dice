package io.github.evildarkarchon.andysdice.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Parsing a Roll Expression the app itself wrote and knows to be valid (a fixed one, a Saved Roll's or a Roll
 * History entry's, checked already): the parsed expression keeps the text as written, and text that doesn't parse
 * is a programming error, not a Roll to refuse.
 */
class RollExpressionsTest {

    @Test
    void aValidRollExpressionIsParsedKeepingItsTextAsWritten() {
        assertThat(RollExpressions.valid("1D20 + 5").source()).isEqualTo("1D20 + 5");
        assertThat(RollExpressions.valid("1D20 + 5").canonical()).isEqualTo("1d20+5");
    }

    @Test
    void textThatDoesntParseIsRejectedNamingItAndWhy() {
        assertThatThrownBy(() -> RollExpressions.valid("1d6+"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("`1d6+`");
    }
}
