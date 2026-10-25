package io.github.evildarkarchon.andysdice.app;

import io.github.evildarkarchon.andysdice.core.expression.Span;

/**
 * Where an entry error lands in the Roll Expression box (spec §3.2 "Errors"): the selection a {@link Span} becomes,
 * the character its popup is anchored at, and where the popup goes under that character. A zero-width span (a
 * missing term, say) selects nothing: the caret sits at its position and the popup anchors there.
 *
 * <p>Pure: no toolkit is needed, so these rules are tested without one (ADR-0011). Measuring where a character is
 * drawn is the toolkit's half and lives in {@link ErrorPopup}.
 */
final class SpanGeometry {

    /**
     * How far left of the span's first character the popup's left edge sits, logical px: about the popup's own
     * padding, so its message starts under the span rather than one padding to the right of it.
     */
    static final double POPUP_INSET = 6;
    /** The gap between the bottom of the box and the top of the popup, logical px. */
    static final double POPUP_GAP = 2;

    private SpanGeometry() {}

    /**
     * A text selection as JavaFX's {@code selectRange(anchor, caretPosition)} takes it.
     *
     * @param anchor the fixed end of the selection
     * @param caret where the caret goes, the other end
     */
    record Selection(int anchor, int caret) {

        /**
         * Returns the selection's first offset.
         *
         * @return the smaller of the two ends
         */
        int start() {
            return Math.min(anchor, caret);
        }

        /**
         * Returns the offset just past the selection.
         *
         * @return the larger of the two ends
         */
        int end() {
            return Math.max(anchor, caret);
        }

        /**
         * Tells whether nothing is selected, only a caret placed.
         *
         * @return {@code true} for a zero-width selection
         */
        boolean isEmpty() {
            return anchor == caret;
        }
    }

    /**
     * Where the popup's top-left corner goes, in the box's own coordinates.
     *
     * @param x from the box's left edge, logical px
     * @param y from the box's top edge, logical px
     */
    record Anchor(double x, double y) {}

    /**
     * Returns the selection that shows a span: its characters, with the caret at the span's start so that a span
     * longer than the box shows its start (the box scrolls to keep the caret in view). Offsets past the text are
     * clamped to its end.
     *
     * @param span the error's span, UTF-16 offsets into the text
     * @param textLength the length of the box's text
     * @return the selection; empty, with the caret at the span's position, for a zero-width span
     */
    static Selection selection(Span span, int textLength) {
        int start = clamp(span.start(), textLength);
        int end = clamp(span.end(), textLength);
        return new Selection(end, start);
    }

    /**
     * Returns the character the popup is anchored under: the span's start, clamped to the text. It may equal the
     * text's length, for a zero-width span at the end ("{@code 1d6+}"), which anchors just after the last character.
     *
     * @param span the error's span
     * @param textLength the length of the box's text
     * @return an offset from 0 to {@code textLength}
     */
    static int anchorIndex(Span span, int textLength) {
        return clamp(span.start(), textLength);
    }

    /**
     * Returns where the popup goes: just under the box, {@link #POPUP_INSET} left of the span's start, but never
     * starting left of the box or past its right edge.
     *
     * @param spanStartX the x of the span's start character's left edge in the box, logical px
     * @param boxWidth the box's width
     * @param boxHeight the box's height
     * @return the popup's top-left corner in the box's coordinates
     */
    static Anchor popupAnchor(double spanStartX, double boxWidth, double boxHeight) {
        double x = Math.clamp(spanStartX - POPUP_INSET, 0, Math.max(0, boxWidth));
        return new Anchor(x, boxHeight + POPUP_GAP);
    }

    private static int clamp(int offset, int textLength) {
        return Math.clamp(offset, 0, Math.max(0, textLength));
    }
}
