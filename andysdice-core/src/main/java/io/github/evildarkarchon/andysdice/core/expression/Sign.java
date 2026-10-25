package io.github.evildarkarchon.andysdice.core.expression;

/** Whether a term of a Roll Expression is added to the total or subtracted from it. */
public enum Sign {
    /** The term is added; also the sign of a first term written without one. */
    PLUS('+'),
    /** The term is subtracted. */
    MINUS('-');

    private final char symbol;

    Sign(char symbol) {
        this.symbol = symbol;
    }

    /** Returns {@code '+'} or {@code '-'}. */
    public char symbol() {
        return symbol;
    }

    /** Returns {@code value} for {@link #PLUS} and its negation for {@link #MINUS}. */
    public int apply(int value) {
        return this == PLUS ? value : -value;
    }
}
