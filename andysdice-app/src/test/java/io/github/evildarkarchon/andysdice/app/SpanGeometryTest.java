package io.github.evildarkarchon.andysdice.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.evildarkarchon.andysdice.core.expression.Span;
import org.junit.jupiter.api.Test;

/**
 * Where an entry error lands in the Roll Expression box (spec §3.2 "Errors"), as pure geometry with no toolkit
 * (ADR-0011): the selection a span becomes, the character the popup is anchored at, and where the popup goes under
 * that character.
 */
class SpanGeometryTest {

    @Test
    void aSpanSelectsItsCharactersWithTheCaretAtItsStart() {
        // "2d6+1d7kh1": the d7 group is [4, 7).
        SpanGeometry.Selection selection = SpanGeometry.selection(new Span(4, 7), 10);

        assertThat(selection.start()).isEqualTo(4);
        assertThat(selection.end()).isEqualTo(7);
        // The caret sits at the start, so a long span's start is the part the box scrolls into view.
        assertThat(selection.caret()).isEqualTo(4);
        assertThat(selection.anchor()).isEqualTo(7);
    }

    @Test
    void aZeroWidthSpanSelectsNothingAndPutsTheCaretAtItsPosition() {
        // "1d6+": the missing term is the zero-width span at 4, the end of the text.
        SpanGeometry.Selection atEnd = SpanGeometry.selection(Span.at(4), 4);
        assertThat(atEnd.start()).isEqualTo(4);
        assertThat(atEnd.end()).isEqualTo(4);
        assertThat(atEnd.caret()).isEqualTo(4);

        SpanGeometry.Selection inside = SpanGeometry.selection(Span.at(2), 6);
        assertThat(inside.start()).isEqualTo(2);
        assertThat(inside.end()).isEqualTo(2);
        assertThat(inside.isEmpty()).isTrue();
        assertThat(atEnd.isEmpty()).isTrue();
        assertThat(SpanGeometry.selection(new Span(0, 1), 3).isEmpty()).isFalse();
    }

    @Test
    void aSpanPastTheTextIsClampedToIt() {
        SpanGeometry.Selection selection = SpanGeometry.selection(new Span(3, 9), 5);

        assertThat(selection.start()).isEqualTo(3);
        assertThat(selection.end()).isEqualTo(5);
        assertThat(SpanGeometry.selection(Span.at(8), 5).caret()).isEqualTo(5);
        assertThat(SpanGeometry.selection(Span.at(0), 0).caret()).isZero();
    }

    @Test
    void thePopupIsAnchoredAtTheSpansStartCharacterZeroWidthIncluded() {
        assertThat(SpanGeometry.anchorIndex(new Span(4, 7), 10)).isEqualTo(4);
        assertThat(SpanGeometry.anchorIndex(Span.at(4), 4)).isEqualTo(4);
        assertThat(SpanGeometry.anchorIndex(Span.at(12), 4)).isEqualTo(4);
    }

    @Test
    void thePopupSitsJustUnderTheBoxALittleLeftOfTheSpansStart() {
        SpanGeometry.Anchor anchor = SpanGeometry.popupAnchor(120, 500, 32);

        // Its padding then lines its text up with the span's first character.
        assertThat(anchor.x()).isEqualTo(120 - SpanGeometry.POPUP_INSET);
        assertThat(anchor.y()).isEqualTo(32 + SpanGeometry.POPUP_GAP);
        assertThat(SpanGeometry.POPUP_INSET).isEqualTo(6);
        assertThat(SpanGeometry.POPUP_GAP).isEqualTo(2);
    }

    @Test
    void thePopupNeverStartsOutsideTheBox() {
        // A span at the very start: the inset would put the popup left of the box.
        assertThat(SpanGeometry.popupAnchor(3, 500, 32).x()).isZero();
        // A start the field reports past its right edge (scrolled text): kept over the box.
        assertThat(SpanGeometry.popupAnchor(900, 500, 32).x()).isEqualTo(500);
    }
}
