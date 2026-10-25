package io.github.evildarkarchon.andysdice.core.expression;

import java.util.Objects;

/**
 * Why a Roll Expression is invalid: the first error only (docs/roll-expression-grammar.md "Errors").
 *
 * @param message names the problem and suggests the fix
 * @param span    the characters the error refers to; zero-width when it marks a position
 */
public record ParseError(String message, Span span) {

    /** Checks that both components are present. */
    public ParseError {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(span, "span");
    }
}
