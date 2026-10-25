package io.github.evildarkarchon.andysdice.app;

import io.github.evildarkarchon.andysdice.core.expression.ParseError;
import java.util.Objects;
import javafx.geometry.Point2D;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.skin.TextFieldSkin;
import javafx.stage.Popup;

/**
 * The red popup an entry error shows (spec §3.2 "Errors"): the box selects the error's span, and the popup, just
 * under the span's start, shows the one message. Any change to the box's text (typing, a die-strip button, a Saved
 * Roll copied in) dismisses it; so do Esc and a click elsewhere, as for any popup. Errors are never checked here as
 * the Player types: only {@link #show} puts the popup up, and only Enter calls it.
 *
 * <p>The rules for the selection and the popup's place are pure ({@link SpanGeometry}); this class measures where
 * the span's start is drawn, through the box's own skin, which knows how far the text has scrolled.
 *
 * <p>Thread contract: the JavaFX Application Thread.
 */
final class ErrorPopup {

    /** The widest the popup grows before its message wraps, logical px. */
    static final double MAX_WIDTH = 520;

    private final TextField box;
    private final Popup popup = new Popup();
    private final Label label = new Label();

    /**
     * Makes the (hidden) popup for a box.
     *
     * @param box the Roll Expression box the errors are in, and the popup's owner
     */
    ErrorPopup(TextField box) {
        this.box = Objects.requireNonNull(box, "box");
        // A popup is its own scene and doesn't inherit Layout C's stylesheet (ui.md, #48).
        label.getStylesheets().add(Stylesheet.uri());
        label.getStyleClass().addAll(Stylesheet.ROOT_CLASS, "error-popup");
        label.setWrapText(true);
        label.setMaxWidth(MAX_WIDTH);
        popup.getContent().add(label);
        popup.setAutoHide(true);
        // Esc closes the popup before it does anything else (spec §3.2 "Keyboard"): the popup sees Esc first and
        // consumes it, so it never also cancels the Queued Roll.
        popup.setHideOnEscape(true);
        box.textProperty().addListener((text, before, after) -> hide());
    }

    /**
     * Shows an error: focuses the box, selects the error's span (a zero-width span only places the caret) and puts
     * the popup with its message under the span's start.
     *
     * @param error the parse error of the box's text
     */
    void show(ParseError error) {
        Objects.requireNonNull(error, "error");
        hide();
        box.requestFocus();
        int length = box.getLength();
        SpanGeometry.Selection selection = SpanGeometry.selection(error.span(), length);
        box.selectRange(selection.anchor(), selection.caret());
        label.setText(error.message());
        // Lay the box out first: it scrolls its text to keep the caret (the span's start) in view, and the start's
        // x must be read after that scroll.
        box.applyCss();
        box.layout();
        SpanGeometry.Anchor anchor = SpanGeometry.popupAnchor(
                startX(SpanGeometry.anchorIndex(error.span(), length)), box.getWidth(), box.getHeight());
        Point2D onScreen = box.localToScreen(anchor.x(), anchor.y());
        if (onScreen == null) {
            // Not in a showing window: there is nowhere to put a popup, but the selection still marks the error.
            return;
        }
        popup.show(box, onScreen.getX(), onScreen.getY());
    }

    /** Hides the popup, if it is showing. */
    void hide() {
        popup.hide();
    }

    /**
     * Tells whether the popup is up.
     *
     * @return {@code true} while it shows an error
     */
    boolean isShowing() {
        return popup.isShowing();
    }

    /**
     * Returns the message shown, or last shown.
     *
     * @return the parse error's message; empty before the first error
     */
    String message() {
        return label.getText();
    }

    /**
     * Returns the popup's content: the label holding the message, which carries the Roll UI's stylesheet itself.
     *
     * @return the label
     */
    Label label() {
        return label;
    }

    /**
     * The x of a character's left edge in the box, as its skin draws it (scroll included). At the text's end this
     * is just past the last character; with no skin yet, the box's left inset.
     */
    private double startX(int index) {
        if (box.getSkin() instanceof TextFieldSkin skin) {
            return skin.getCharacterBounds(index).getMinX();
        }
        return box.snappedLeftInset();
    }
}
