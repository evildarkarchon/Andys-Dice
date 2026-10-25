package io.github.evildarkarchon.andysdice.core.expression;

/**
 * A run of characters in a Roll Expression as typed: 0-based, end-exclusive {@code [start, end)} offsets into
 * the input string (UTF-16 code units, which is what a JavaFX text field's selection uses). A zero-width span
 * ({@code start == end}) marks a position, such as where a missing term should be.
 *
 * @param start the offset of the first character in the span
 * @param end   the offset just past the last character; equal to {@code start} for a zero-width span
 */
public record Span(int start, int end) {

    /**
     * Checks the offsets.
     *
     * @throws IllegalArgumentException if {@code start} is negative or {@code end} is before {@code start}
     */
    public Span {
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("not a span: [" + start + ", " + end + ")");
        }
    }

    /** Returns the zero-width span at {@code position}. */
    public static Span at(int position) {
        return new Span(position, position);
    }

    /** Returns how many characters the span covers; 0 for a zero-width span. */
    public int length() {
        return end - start;
    }

    /** Returns the span as {@code [start, end)}, the form the grammar reference writes spans in. */
    @Override
    public String toString() {
        return "[" + start + ", " + end + ")";
    }
}
