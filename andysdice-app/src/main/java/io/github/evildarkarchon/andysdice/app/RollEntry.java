package io.github.evildarkarchon.andysdice.app;

import io.github.evildarkarchon.andysdice.core.expression.ParseResult;
import io.github.evildarkarchon.andysdice.core.expression.RollExpressionParser;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.collections.WeakListChangeListener;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.PopupWindow;
import javafx.stage.Window;

/**
 * Entry in the Roll Expression box (spec §3.2 "Entry"): what Enter and the Roll button do with the box's text, the
 * error popup, the Queued Roll's "next: …" chip and Esc cancelling the Queued Roll.
 * <ul>
 *   <li>Errors are checked on Enter only. An invalid Roll Expression selects its span and shows its message in the
 *       {@link ErrorPopup}; nothing is rolled or queued, and a Roll already queued stays queued.</li>
 *   <li>A valid one is accepted: it rolls, or becomes the Queued Roll while Dice move ({@link RollController}), and
 *       the box keeps it with all of it selected, so typing replaces it and Enter rolls it again.</li>
 *   <li>Esc, when nothing nearer the focus took it (an open popup, an inline edit), cancels an inline edit still
 *       open elsewhere in the window, else the Queued Roll.</li>
 * </ul>
 *
 * <p>Thread contract: the JavaFX Application Thread.
 */
final class RollEntry {

    /** The chip's text before the Queued Roll's expression. */
    static final String NEXT_PREFIX = "next: ";

    private final TextField box;
    private final RollController controller;
    private final ErrorPopup errorPopup;
    private final Label nextChip = new Label();
    /** Esc's inline-edit step; none until the Saved tab sets it. */
    private BooleanSupplier inlineEdit = () -> false;
    /** Set while the key event under way hid one of this window's popups; see the constructor. */
    private boolean popupJustHidden;
    /** Kept here, strongly: the toolkit's window list holds it only weakly. */
    private final ListChangeListener<Window> windowsListener;

    /**
     * Wires entry into a Roll UI: Enter and the Roll button, the error popup, the chip at the end of the toolbar
     * and Esc.
     *
     * @param ui the Roll UI whose box this is
     * @param controller where accepted Rolls go, to roll or queue, and which holds the Queued Roll
     */
    RollEntry(RollUi ui, RollController controller) {
        Objects.requireNonNull(ui, "ui");
        this.controller = Objects.requireNonNull(controller, "controller");
        box = ui.expressionBox();
        errorPopup = new ErrorPopup(box);

        // The chip follows the Roll button (spec §3.2 "Layout") and takes no room while nothing is queued.
        nextChip.getStyleClass().add("next-chip");
        nextChip.setVisible(false);
        nextChip.managedProperty().bind(nextChip.visibleProperty());
        ui.toolbar().getChildren().add(nextChip);
        // The chip follows the Queued Roll in each of the Roll's state snapshots.
        controller.addStateListener(state -> showQueued(state.queued()));

        ui.setOnRoll(this::enter);
        // A popup that doesn't consume the events that auto-hide it (a ContextMenu, the History menu, while nothing
        // in it has focus yet) hides on Esc and lets that same key event go on into this window, where it would
        // cancel the Queued Roll too (#62). Esc's first step is the popup's alone, so a popup of this window hiding
        // marks the key event under way as used up. The mark is cleared on the next turn: one key event is
        // dispatched within a turn, and the clearing is queued ahead of any later key. The list is the toolkit's,
        // so it holds the listener weakly and a closed window's entry is let go.
        windowsListener = change -> {
            while (change.next()) {
                for (Window gone : change.getRemoved()) {
                    Window own = ui.getScene() == null ? null : ui.getScene().getWindow();
                    if (gone instanceof PopupWindow && !(gone instanceof Tooltip) && own != null
                            && RollUi.ownedBy(gone, own)) {
                        popupJustHidden = true;
                        Platform.runLater(() -> popupJustHidden = false);
                    }
                }
            }
        };
        Window.getWindows().addListener(new WeakListChangeListener<>(windowsListener));
        // Bubbling, on the root: a popup (which sees Esc first) or an inline edit nearer the focus consumes Esc
        // before it gets here, which is the order spec §3.2 "Keyboard" gives Esc.
        ui.addEventHandler(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE && (popupJustHidden || escape())) {
                event.consume();
            }
        });
        // A ListView maps Esc to cancelling its own cell edit and consumes it even with no edit under way, so the
        // handler above would never hear Esc while the Saved or History list has focus (#62). Esc on a list that
        // isn't editing is taken here, before the list's own handling. (Neither list uses ListView editing; the
        // Saved tab's inline edit has its own fields, so Esc typed there never targets the list.)
        ui.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE && event.getTarget() instanceof ListView<?> list
                    && list.getEditingIndex() < 0 && (popupJustHidden || escape())) {
                event.consume();
            }
        });
    }

    /**
     * Sets Esc's second step (spec §3.2 "Keyboard"): cancelling an inline ✎ edit, which comes before cancelling the
     * Queued Roll. Esc typed in the edit's own fields is the edit's; this step is for Esc typed while an edit is open
     * but focus has moved elsewhere in the window.
     *
     * @param cancelIfOpen cancels the inline edit if one is open, and tells whether it did; it replaces any earlier
     *     one
     */
    void setInlineEdit(BooleanSupplier cancelIfOpen) {
        inlineEdit = Objects.requireNonNull(cancelIfOpen, "cancelIfOpen");
    }

    /**
     * Does what Enter in the box does with its text: shows its error, or accepts it, rolling or queueing it and
     * selecting all of it.
     *
     * @param text the box's text
     */
    void enter(String text) {
        switch (RollExpressionParser.parse(text)) {
            case ParseResult.Invalid invalid -> errorPopup.show(invalid.error());
            case ParseResult.Valid valid -> {
                errorPopup.hide();
                controller.roll(text);
                box.requestFocus();
                box.selectAll();
            }
        }
    }

    /**
     * Esc's last resort: the error popup if it is somehow still up (it normally takes Esc itself), else an open inline
     * edit ({@link #setInlineEdit}), else the Queued Roll.
     *
     * @return whether Esc did something, and so is used up
     */
    boolean escape() {
        if (errorPopup.isShowing()) {
            errorPopup.hide();
            return true;
        }
        if (inlineEdit.getAsBoolean()) {
            return true;
        }
        return controller.cancelQueued();
    }

    /**
     * Returns the error popup.
     *
     * @return the popup Enter shows an invalid expression's message in
     */
    ErrorPopup errorPopup() {
        return errorPopup;
    }

    /**
     * Returns the "next: …" chip.
     *
     * @return the label at the end of the toolbar, visible only while a Roll is queued
     */
    Label nextChip() {
        return nextChip;
    }

    private void showQueued(Optional<RollState.QueuedRoll> queued) {
        nextChip.setText(queued.map(roll -> NEXT_PREFIX + roll.input().strip()).orElse(""));
        nextChip.setVisible(queued.isPresent());
    }
}
