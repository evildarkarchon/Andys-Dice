package io.github.evildarkarchon.andysdice.app;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import javafx.scene.Node;
import javafx.scene.control.Tab;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;

/**
 * The window's global shortcuts (spec §3.2 "Keyboard"), beside {@link SavedTab#NEW_SHORTCUT Ctrl+S}, which the Saved
 * tab owns:
 * <ul>
 *   <li>Ctrl+1 to Ctrl+4 pick the Saved, History, Look and Settings tabs;</li>
 *   <li>Ctrl+L focuses the Roll Expression box and selects all of it;</li>
 *   <li>Ctrl+R moves focus to the result strip, which is focusable but not a Tab stop.</li>
 * </ul>
 * There are no single-letter shortcuts: every one needs Ctrl, so typing in the box never triggers one. The rest of
 * the keyboard map lives with what it drives: Esc in {@link RollEntry} (and nearer the focus, in popups, dialogs and
 * the Saved tab's inline edit), Shift's Fast-forward in {@link FastForward}, and each list's and grid's own keys in
 * its tab. The focus order comes from {@link RollUi}'s layout.
 *
 * <p>Thread contract: the JavaFX Application Thread.
 */
final class KeyboardMap {

    /** Ctrl+1 to Ctrl+4, in tab order; the number pad's digits work too. */
    private static final List<List<KeyCombination>> TAB_SHORTCUTS = List.of(
            digit(KeyCode.DIGIT1, KeyCode.NUMPAD1), digit(KeyCode.DIGIT2, KeyCode.NUMPAD2),
            digit(KeyCode.DIGIT3, KeyCode.NUMPAD3), digit(KeyCode.DIGIT4, KeyCode.NUMPAD4));

    /** Ctrl+L: focus the box and select its contents. */
    static final KeyCombination FOCUS_BOX = new KeyCodeCombination(KeyCode.L, KeyCombination.SHORTCUT_DOWN);

    /** Ctrl+R: focus the result strip. */
    static final KeyCombination FOCUS_RESULT = new KeyCodeCombination(KeyCode.R, KeyCombination.SHORTCUT_DOWN);

    private final RollUi ui;

    private KeyboardMap(RollUi ui) {
        this.ui = ui;
    }

    /**
     * Installs the shortcuts on a Roll UI: a key-pressed filter on its root, so they work from the box (whose own key
     * handling would otherwise come first) and from every tab. Keys typed in a popup or a dialog go to that window,
     * not this one, so the shortcuts never act behind one. Once the session has {@link RollUi#failed failed} they do
     * nothing.
     *
     * @param ui the Roll UI
     */
    static void install(RollUi ui) {
        KeyboardMap map = new KeyboardMap(Objects.requireNonNull(ui, "ui"));
        ui.addEventFilter(KeyEvent.KEY_PRESSED, map::keyPressed);
    }

    private void keyPressed(KeyEvent event) {
        if (ui.failed()) {
            return;
        }
        for (int i = 0; i < TAB_SHORTCUTS.size(); i++) {
            int index = i;
            if (TAB_SHORTCUTS.get(i).stream().anyMatch(shortcut -> shortcut.match(event))) {
                pickTab(ui.tabs().getTabs().get(index));
                event.consume();
                return;
            }
        }
        if (FOCUS_BOX.match(event)) {
            ui.focusBox();
            ui.expressionBox().selectAll();
            event.consume();
        } else if (FOCUS_RESULT.match(event)) {
            ui.resultStrip().requestFocus();
            event.consume();
        }
    }

    /**
     * Selects a tab. Focus stays where it was, unless it was inside the tab being hidden: then it moves to the tabs
     * themselves, rather than staying on a node no one can see.
     */
    private void pickTab(Tab tab) {
        Tab before = ui.tabs().getSelectionModel().getSelectedItem();
        Node focus = ui.getScene() == null ? null : ui.getScene().getFocusOwner();
        ui.tabs().getSelectionModel().select(tab);
        if (before != tab && focus != null && before != null && within(focus, before.getContent())) {
            ui.tabs().requestFocus();
        }
    }

    private static boolean within(Node node, Node ancestor) {
        for (Node at = node; at != null; at = at.getParent()) {
            if (at == ancestor) {
                return true;
            }
        }
        return false;
    }

    private static List<KeyCombination> digit(KeyCode... codes) {
        return Arrays.stream(codes)
                .<KeyCombination>map(code -> new KeyCodeCombination(code, KeyCombination.SHORTCUT_DOWN))
                .toList();
    }
}
