package io.github.evildarkarchon.andysdice.app;

import io.github.evildarkarchon.andysdice.core.expression.RollExpression;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * The Saved tab (spec §3.2 "Saved tab", §3.6 "Saved Rolls"): the Player's Saved Rolls in their own order, kept in
 * {@code saved-rolls.txt}.
 * <ul>
 *   <li>+ New Saved Roll, or Ctrl+S anywhere in the window, opens the {@link SavedRollDialog} pre-filled with the
 *       box's expression; History's Save as a Saved Roll… opens it pre-filled with that Roll's. New ones go at the
 *       end, at most {@value SavedRolls#CAP}.</li>
 *   <li>A click copies a Saved Roll into the box, and a double-click rolls it (queuing while Dice move), recording
 *       the Roll with its name. One whose expression no longer parses is marked, won't roll, and stays editable.</li>
 *   <li>The up/down buttons or Alt+↑/↓ reorder; the edit button or F2 edits the name and expression inline, under
 *       the same rules as a new one; the delete button or Delete asks a {@link ConfirmDialog modal confirmation}
 *       first, Cancel the default.</li>
 *   <li>Keys on the list: ↑/↓ move the selection, Space copies, Enter rolls.</li>
 * </ul>
 * Empty, the list reads {@value #EMPTY_TEXT}
 *
 * <p>Thread contract: made and used on the JavaFX Application Thread. Each change is saved through
 * {@link Persistence}, which writes it on its own thread.
 */
final class SavedTab {

    /** What the list reads with no Saved Rolls in it. */
    static final String EMPTY_TEXT = "No Saved Rolls yet. Use + New Saved Roll, or right-click a History entry.";

    /** The shortcut that opens + New Saved Roll (spec §3.2 "Keyboard"). */
    static final KeyCombination NEW_SHORTCUT = new KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN);

    private static final String URI = "data:text/css;base64,"
            + Base64.getEncoder().encodeToString(css().getBytes(StandardCharsets.UTF_8));

    private final RollUi ui;
    private final RollSession session;
    private final Persistence persistence;
    private final ListView<SavedRoll> list = new ListView<>();
    private final Label emptyText = new Label(EMPTY_TEXT);
    private final Button newButton = new Button("+ New Saved Roll");
    private SavedRolls rolls = SavedRolls.EMPTY;
    /** The index of the Saved Roll being edited inline, or -1. */
    private int editing = -1;
    private SavedRollDialog dialog;
    private Dialog<ButtonType> confirmation;

    private SavedTab(RollSession session, Persistence persistence) {
        this.session = Objects.requireNonNull(session, "session");
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        ui = session.ui();

        newButton.getStyleClass().add("saved-new");
        newButton.setAccessibleText("New Saved Roll");
        newButton.setOnAction(event -> askNew(ui.expressionBox().getText()));

        list.getStyleClass().add("saved-list");
        list.setAccessibleText("Saved Rolls");
        list.setCellFactory(view -> new SavedCell(this));
        // As in the History tab: the empty state is a label of our own over the list, not its placeholder.
        list.setPlaceholder(new Region());
        emptyText.getStyleClass().add("saved-empty");
        emptyText.setWrapText(true);
        emptyText.setMouseTransparent(true);
        emptyText.visibleProperty().bind(Bindings.isEmpty(list.getItems()));
        StackPane listArea = new StackPane(list, emptyText);
        StackPane.setAlignment(emptyText, Pos.TOP_LEFT);
        VBox.setVgrow(listArea, Priority.ALWAYS);

        VBox.setMargin(newButton, new Insets(0, 12, 0, 12));
        VBox root = new VBox(newButton, listArea);
        root.getStyleClass().add("saved");
        root.getStylesheets().add(URI);
        ((StackPane) ui.savedTab().getContent()).getChildren().add(root);

        // A filter, ahead of the list's own key handling (which would take Space for selection and Alt+↑/↓ for
        // moving it).
        list.addEventFilter(KeyEvent.KEY_PRESSED, this::keyPressed);
        // Esc's second step (spec §3.2 "Keyboard"): an edit left open while focus went elsewhere is cancelled before
        // the Queued Roll is. Esc typed in the edit's own fields never gets that far: the editor takes it.
        session.entry().setInlineEdit(() -> {
            if (editing < 0) {
                return false;
            }
            cancelEdit();
            return true;
        });
        // A filter on the whole window's content, so Ctrl+S works from the box (whose own handling comes first
        // otherwise) and from any tab.
        // Not once the session has failed: this root still hears keys behind the failure panel (#62).
        ui.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (NEW_SHORTCUT.match(event) && !ui.failed()) {
                askNew(ui.expressionBox().getText());
                event.consume();
            }
        });
    }

    /**
     * Fills the Saved tab: loads {@code saved-rolls.txt} (set aside, with a Notice, if it can't be read) and gives the
     * History's menu Save as a Saved Roll….
     *
     * @param session the Roll UI and its controller; the tab goes in its Saved tab
     * @param persistence where {@code saved-rolls.txt} is kept
     * @param history the History tab, whose menu gains Save as a Saved Roll…
     * @return the tab's content and actions
     */
    static SavedTab install(RollSession session, Persistence persistence, HistoryTab history) {
        SavedTab tab = new SavedTab(session, persistence);
        tab.show(persistence.load(SavedRolls.FILE));
        history.setOnSaveAsSavedRoll(roll -> tab.askNew(roll.expression()));
        return tab;
    }

    /**
     * Opens the + New Saved Roll dialog with its expression pre-filled. While one is open, another isn't opened.
     *
     * @param expression what the expression field starts with
     */
    void askNew(String expression) {
        if (dialog().isPresent()) {
            return;
        }
        dialog = SavedRollDialog.open(ui.getScene().getWindow(), expression,
                (name, typed) -> rolls.refusal(name, typed, SavedRolls.NEW),
                roll -> {
                    change(rolls.with(roll));
                    list.getSelectionModel().select(rolls.rolls().size() - 1);
                });
    }

    /**
     * Copies a Saved Roll's expression into the box, replacing what it held (spec §3.2 "Entry"). Focus stays where it
     * is, so the list's keys keep working.
     *
     * @param index the Saved Roll's index
     */
    void copy(int index) {
        TextField box = ui.expressionBox();
        box.setText(rolls.rolls().get(index).expression());
        box.positionCaret(box.getLength());
    }

    /**
     * Rolls a Saved Roll: at once, or queued while Dice move, as Enter in the box does, recorded with its name. The box
     * then holds the expression, all selected, with focus. One that no longer parses is only copied: it won't roll.
     *
     * @param index the Saved Roll's index
     */
    void roll(int index) {
        SavedRoll roll = rolls.rolls().get(index);
        copy(index);
        Optional<RollExpression> expression = roll.parsed();
        if (expression.isEmpty()) {
            return;
        }
        session.roll(expression.get(), roll.name());
        ui.focusBox();
        ui.expressionBox().selectAll();
    }

    /**
     * Moves a Saved Roll up or down one place, keeping it selected; past either end nothing moves. An inline edit
     * under way is cancelled first.
     *
     * @param index the Saved Roll's index
     * @param by -1 for up, 1 for down
     */
    void move(int index, int by) {
        SavedRolls next = rolls.moved(index, by);
        if (next == rolls) {
            return;
        }
        endEdit();
        change(next);
        list.getSelectionModel().select(index + by);
    }

    /**
     * Starts editing a Saved Roll inline: its row becomes the editor, focused in the name field.
     *
     * @param index the Saved Roll's index
     */
    void edit(int index) {
        editing = index;
        list.getSelectionModel().select(index);
        list.scrollTo(index);
        list.refresh();
        // Lay the list out now, so the row's cell shows the editor and can take the focus in this same turn.
        list.applyCss();
        list.layout();
        cellAt(index).ifPresent(SavedCell::focusEditor);
    }

    /**
     * Saves an inline edit, if the rules allow it.
     *
     * @param index the edited Saved Roll's index
     * @param name the name as typed
     * @param expression the expression as typed
     * @return why it was refused, or empty if it was saved (the edit is then over)
     */
    Optional<SavedRolls.Refusal> saveEdit(int index, String name, String expression) {
        Optional<SavedRolls.Refusal> refusal = rolls.refusal(name, expression, index);
        if (refusal.isPresent()) {
            return refusal;
        }
        editing = -1;
        change(rolls.replaced(index, SavedRoll.of(name, expression)));
        list.getSelectionModel().select(index);
        list.requestFocus();
        return Optional.empty();
    }

    /** Cancels an inline edit, leaving the Saved Roll as it was, and puts the focus back on the list. */
    void cancelEdit() {
        if (editing < 0) {
            return;
        }
        int index = editing;
        endEdit();
        list.getSelectionModel().select(index);
        list.requestFocus();
    }

    /**
     * Asks before deleting a Saved Roll; Cancel is the default.
     *
     * @param index the Saved Roll's index
     */
    void askToDelete(int index) {
        SavedRoll roll = rolls.rolls().get(index);
        confirmation = ConfirmDialog.ask(ui.getScene().getWindow(), "Delete a Saved Roll",
                "Delete the Saved Roll \"" + roll.name() + "\" (" + roll.expression() + ")? This can't be undone.",
                "Delete", () -> {
                    // The dialog is modal, so the list hasn't changed under it; found again all the same.
                    int at = rolls.rolls().indexOf(roll);
                    if (at >= 0) {
                        endEdit();
                        change(rolls.without(at));
                        if (!rolls.rolls().isEmpty()) {
                            list.getSelectionModel().select(Math.min(at, rolls.rolls().size() - 1));
                        }
                    }
                });
    }

    /** The edit is over without saving: every row shows its Saved Roll again. */
    private void endEdit() {
        if (editing >= 0) {
            editing = -1;
            list.refresh();
        }
    }

    /**
     * Returns the index of the Saved Roll being edited inline.
     *
     * @return the index, or -1 when none is
     */
    int editingIndex() {
        return editing;
    }

    private void keyPressed(KeyEvent event) {
        // Keys typed in the inline editor are the editor's, not the list's.
        if (event.getTarget() != list) {
            return;
        }
        int index = list.getSelectionModel().getSelectedIndex();
        if (index < 0) {
            return;
        }
        KeyCode code = event.getCode();
        if (event.isAltDown() && (code == KeyCode.UP || code == KeyCode.DOWN)) {
            move(index, code == KeyCode.UP ? -1 : 1);
            event.consume();
        } else if (code == KeyCode.SPACE && !event.isShortcutDown()) {
            copy(index);
            event.consume();
        } else if (code == KeyCode.ENTER) {
            roll(index);
            event.consume();
        } else if (code == KeyCode.F2) {
            edit(index);
            event.consume();
        } else if (code == KeyCode.DELETE) {
            askToDelete(index);
            event.consume();
        }
    }

    private void change(SavedRolls next) {
        show(next);
        persistence.save(SavedRolls.FILE, next);
    }

    private void show(SavedRolls next) {
        rolls = next;
        list.getItems().setAll(next.rolls());
        // The up/down buttons depend on a row's place, so every visible cell is worked out again.
        list.refresh();
    }

    /** The visible cell showing a row, if it is laid out. */
    private Optional<SavedCell> cellAt(int index) {
        return list.lookupAll("." + SavedCell.STYLE_CLASS).stream()
                .filter(node -> node instanceof SavedCell cell && cell.getIndex() == index && cell.isVisible())
                .map(SavedCell.class::cast)
                .findFirst();
    }

    /**
     * Returns the Saved Rolls as the tab holds them.
     *
     * @return the Saved Rolls, in the Player's order
     */
    SavedRolls saved() {
        return rolls;
    }

    /**
     * Returns the list of Saved Rolls.
     *
     * @return the list, in the Player's order, each row a {@link SavedCell}
     */
    ListView<SavedRoll> list() {
        return list;
    }

    /**
     * Returns the empty state's text.
     *
     * @return the label shown over the list while it holds no Saved Rolls
     */
    Label emptyText() {
        return emptyText;
    }

    /**
     * Returns + New Saved Roll.
     *
     * @return the button
     */
    Button newButton() {
        return newButton;
    }

    /**
     * Returns the + New Saved Roll dialog while it shows.
     *
     * @return the showing dialog, or empty
     */
    Optional<SavedRollDialog> dialog() {
        return Optional.ofNullable(dialog).filter(shown -> shown.dialog().isShowing());
    }

    /**
     * Returns the delete confirmation while it shows.
     *
     * @return the showing dialog, or empty
     */
    Optional<Dialog<ButtonType>> confirmation() {
        return Optional.ofNullable(confirmation).filter(Dialog::isShowing);
    }

    /**
     * Returns the tab's own stylesheet, which the {@link SavedRollDialog} (a scene of its own) also takes.
     *
     * @return a {@code data:} URI
     */
    static String uri() {
        return URI;
    }

    /** The tab's own rules, over the shared theme; its selectors out-rank the shared ones in either load order. */
    private static String css() {
        String surface = Stylesheet.hex(Palette.SURFACE);
        String raised = Stylesheet.hex(Palette.RAISED);
        String muted = Stylesheet.hex(Palette.TEXT_MUTED);
        String text = Stylesheet.hex(Palette.TEXT);
        String accent = Stylesheet.hex(Palette.ACCENT);
        String focus = Stylesheet.hex(Palette.FOCUS);
        String warning = Stylesheet.hex(Palette.NOTICE);
        String root = "." + Stylesheet.ROOT_CLASS;
        String scope = root + " .saved";
        return String.join("\n",
                scope + " { -fx-spacing: 8; -fx-padding: 10 0 0 0; }",
                scope + " .saved-list { -fx-background-color: " + surface + "; -fx-background-insets: 0; "
                        + "-fx-padding: 0; }",
                scope + " .saved-list:focused { -fx-background-color: " + focus + ", " + surface
                        + "; -fx-background-insets: 0, 1; }",
                scope + " .saved-list .list-cell.saved-cell { -fx-background-color: transparent; -fx-padding: 0; }",
                scope + " .saved-cell .saved-row { -fx-spacing: 6; -fx-padding: 6 6 6 12; -fx-alignment: center-left; }",
                scope + " .saved-cell .saved-editor { -fx-spacing: 6; -fx-padding: 6 12 6 12; }",
                scope + " .saved-cell .saved-edit-buttons { -fx-spacing: 6; }",
                scope + " .saved-cell .saved-buttons { -fx-spacing: 0; -fx-alignment: center-right; }",
                scope + " .saved-cell .icon-button { -fx-padding: 4; }",
                // The selected row: raised, with an accent bar at its left edge (never colour alone), as in History.
                scope + " .saved-cell:filled:hover .saved-row { -fx-background-color: " + raised + "; }",
                scope + " .saved-cell:filled:selected .saved-row { -fx-background-color: " + accent + ", "
                        + raised + "; -fx-background-insets: 0, 0 0 0 3; }",
                scope + " .saved-list:focused .saved-cell:filled:focused .saved-row { -fx-border-color: "
                        + focus + "; -fx-border-width: 1; }",
                scope + " .saved-cell .saved-name { -fx-text-fill: " + text + "; -fx-font-weight: bold; }",
                scope + " .saved-cell .saved-expression { -fx-text-fill: " + muted + "; }",
                scope + " .saved-cell .saved-warning { -fx-text-fill: " + text + "; -fx-font-size: 11px; "
                        + "-fx-graphic-text-gap: 4; }",
                scope + " .saved-empty { -fx-text-fill: " + muted + "; -fx-padding: 16 12 0 12; }",
                // The warning mark, wherever a refusal or a Saved Roll that won't roll is shown (the dialog too).
                root + " .saved-warning .icon, " + root + " .saved-error .icon { -fx-fill: " + warning
                        + "; -fx-scale-x: 0.6; -fx-scale-y: 0.6; }",
                root + " .saved-error { -fx-text-fill: " + text + "; -fx-graphic-text-gap: 4; }",
                "");
    }
}
