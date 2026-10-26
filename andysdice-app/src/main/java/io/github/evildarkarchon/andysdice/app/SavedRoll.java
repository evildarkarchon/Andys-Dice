package io.github.evildarkarchon.andysdice.app;

import io.github.evildarkarchon.andysdice.core.expression.ParseError;
import io.github.evildarkarchon.andysdice.core.expression.ParseResult;
import io.github.evildarkarchon.andysdice.core.expression.RollExpression;
import io.github.evildarkarchon.andysdice.core.expression.RollExpressionParser;
import java.util.Objects;
import java.util.Optional;

/**
 * One Saved Roll (spec §3.2 "Saved tab", §3.6 "Saved Rolls"): a name the Player rolls it by and a Roll Expression as
 * written. The expression parsed when it was saved, but one read back from {@code saved-rolls.txt} may no longer
 * parse (a hand-edited file, or a later grammar): it is kept, says {@linkplain #problem why}, and won't
 * {@linkplain #rolls roll} until it is edited.
 *
 * <p>The name rules (trimmed, 1-40 characters, unique ignoring case, no control characters) belong to the list, since
 * uniqueness does: see {@link SavedRolls#refusal}.
 *
 * @param name the name, already trimmed
 * @param expression the Roll Expression as written, without the spaces round it
 */
record SavedRoll(String name, String expression) {

    /** Checks both are present. */
    SavedRoll {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(expression, "expression");
    }

    /**
     * Makes a Saved Roll from what was typed into a name field and an expression field: both trimmed.
     *
     * @param name the name as typed
     * @param expression the expression as typed
     * @return the Saved Roll
     */
    static SavedRoll of(String name, String expression) {
        return new SavedRoll(name.strip(), expression.strip());
    }

    /**
     * Returns why the expression doesn't parse, if it doesn't.
     *
     * @return the parse error, or empty for a Saved Roll that rolls
     */
    Optional<ParseError> problem() {
        return switch (RollExpressionParser.parse(expression)) {
            case ParseResult.Invalid invalid -> Optional.of(invalid.error());
            case ParseResult.Valid valid -> Optional.empty();
        };
    }

    /**
     * Returns the expression parsed, to roll it, if it parses.
     *
     * @return the parsed expression, or empty for a Saved Roll that {@linkplain #problem no longer parses}
     */
    Optional<RollExpression> parsed() {
        // Known to parse once problem() found nothing; parsing it again is cheap (one short expression, on a click).
        return rolls() ? Optional.of(RollExpressions.valid(expression)) : Optional.empty();
    }

    /**
     * Tells whether the Saved Roll can be rolled: its expression parses.
     *
     * @return {@code true} unless it {@linkplain #problem no longer parses}
     */
    boolean rolls() {
        return problem().isEmpty();
    }
}
