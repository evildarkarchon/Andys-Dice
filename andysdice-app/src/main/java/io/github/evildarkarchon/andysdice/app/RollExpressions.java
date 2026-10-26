package io.github.evildarkarchon.andysdice.app;

import io.github.evildarkarchon.andysdice.core.expression.ParseResult;
import io.github.evildarkarchon.andysdice.core.expression.RollExpression;
import io.github.evildarkarchon.andysdice.core.expression.RollExpressionParser;

/**
 * Parses Roll Expressions the app already knows to be valid: a fixed one (the smoke test's, the bench's), one
 * checked when it was kept (a Roll History entry's), or a Saved Roll's that has just been found to parse
 * ({@link SavedRoll#parsed}). A Player's own input is parsed, and refused, only by entry in
 * the Roll Expression box ({@link RollEntry}); the roll controller takes only parsed expressions.
 */
final class RollExpressions {

    private RollExpressions() {}

    /**
     * Parses a Roll Expression that is known to parse.
     *
     * @param text the Roll Expression as written
     * @return the parsed expression, whose {@link RollExpression#source() source} is {@code text}
     * @throws IllegalArgumentException if it doesn't parse after all, naming it and why
     */
    static RollExpression valid(String text) {
        return switch (RollExpressionParser.parse(text)) {
            case ParseResult.Invalid invalid -> throw new IllegalArgumentException(
                    "`" + text + "` doesn't parse: " + invalid.error().message());
            case ParseResult.Valid valid -> valid.expression();
        };
    }
}
