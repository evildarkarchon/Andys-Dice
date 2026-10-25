package io.github.evildarkarchon.andysdice.core.expression;

import java.util.Objects;

/**
 * What parsing a Roll Expression gives: all or nothing, either a {@link Valid} expression or one
 * {@link Invalid} error.
 */
public sealed interface ParseResult permits ParseResult.Valid, ParseResult.Invalid {

    /**
     * The input is a valid Roll Expression.
     *
     * @param expression the parsed expression
     */
    record Valid(RollExpression expression) implements ParseResult {

        /** Checks that the expression is present. */
        public Valid {
            Objects.requireNonNull(expression, "expression");
        }
    }

    /**
     * The input is invalid and rolls nothing.
     *
     * @param error the first error found
     */
    record Invalid(ParseError error) implements ParseResult {

        /** Checks that the error is present. */
        public Invalid {
            Objects.requireNonNull(error, "error");
        }
    }
}
