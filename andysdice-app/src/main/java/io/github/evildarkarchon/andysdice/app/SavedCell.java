package io.github.evildarkarchon.andysdice.app;

import io.github.evildarkarchon.andysdice.core.expression.ParseError;
import java.util.Objects;
import java.util.Optional;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * One Saved Roll in the Saved tab's list (spec §3.2 "Saved tab"): its name and expression, a warning mark and the
 * parse error's message if it no longer parses, then the icon buttons up, down, edit and delete. While the
 * {@link SavedTab} edits this row, the cell shows the inline editor instead: a name field, an expression field, the
 * refusal's message and Save / Cancel. Enter in either field saves, Esc cancels.
 *
 * <p>A primary click copies the Saved Roll into the box and a double-click rolls it (its first click has copied it
 * already); clicks on the icon buttons do only what the button does. The icon buttons aren't focus-traversable: the
 * list's own keys (Alt+↑/↓, F2, Delete) do the same for the selected row, so Tab moves past the list in one step.
 * Each row reads as one sentence to a screen reader, and each icon button is named after its Saved Roll ("Delete
 * Saved Roll Fireball").
 *
 * <p>Each cell owns its own editor's nodes, so no node is ever shared between cells (the list's skin also lays out an
 * invisible measuring cell). Thread contract: a JavaFX node, used on the JavaFX Application Thread.
 */
final class SavedCell extends ListCell<SavedRoll> {

    /** The style class every Saved cell carries. */
    static final String STYLE_CLASS = "saved-cell";

    private final SavedTab tab;

    private final Label name = new Label();
    private final Label expression = new Label();
    private final Label warning = new Label();
    private final Button up = iconButton(Icons.up());
    private final Button down = iconButton(Icons.down());
    private final Button edit = iconButton(Icons.edit());
    private final Button delete = iconButton(Icons.delete());
    private final HBox view;

    private final TextField nameField = new TextField();
    private final TextField expressionField = new TextField();
    private final Label editError = new Label();
    private final Button save = new Button("Save");
    private final Button cancel = new Button("Cancel");
    private final VBox editor;
    /** The index whose Saved Roll the editor's fields were filled from, or -1 while the view shows. */
    private int editorFor = -1;

    /**
     * Makes an empty cell.
     *
     * @param tab the tab whose list this is: what the row's actions and the editor's Save and Cancel do
     */
    SavedCell(SavedTab tab) {
        this.tab = Objects.requireNonNull(tab, "tab");
        getStyleClass().add(STYLE_CLASS);

        name.getStyleClass().add("saved-name");
        expression.getStyleClass().add("saved-expression");
        warning.getStyleClass().add("saved-warning");
        warning.setGraphic(Icons.warning());
        warning.setWrapText(true);
        warning.managedProperty().bind(warning.visibleProperty());
        for (Label label : new Label[] {name, expression, warning}) {
            label.setMinWidth(0);
        }
        VBox text = new VBox(name, expression, warning);
        text.getStyleClass().add("saved-text");
        text.setMinWidth(0);
        HBox.setHgrow(text, Priority.ALWAYS);
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        up.setOnAction(event -> tab.move(getIndex(), -1));
        down.setOnAction(event -> tab.move(getIndex(), 1));
        edit.setOnAction(event -> tab.edit(getIndex()));
        delete.setOnAction(event -> tab.askToDelete(getIndex()));
        HBox buttons = new HBox(up, down, edit, delete);
        buttons.getStyleClass().add("saved-buttons");
        buttons.setMinWidth(USE_PREF_SIZE);
        view = new HBox(text, gap, buttons);
        view.getStyleClass().add("saved-row");

        nameField.setAccessibleText("Saved Roll name");
        expressionField.setAccessibleText("Saved Roll expression");
        nameField.setOnAction(event -> saveEdit());
        expressionField.setOnAction(event -> saveEdit());
        editError.getStyleClass().add("saved-error");
        editError.setGraphic(Icons.warning());
        editError.setWrapText(true);
        editError.managedProperty().bind(editError.visibleProperty());
        editError.setVisible(false);
        nameField.textProperty().addListener((t, before, after) -> editError.setVisible(false));
        expressionField.textProperty().addListener((t, before, after) -> editError.setVisible(false));
        save.setOnAction(event -> saveEdit());
        cancel.setOnAction(event -> tab.cancelEdit());
        HBox editButtons = new HBox(save, cancel);
        editButtons.getStyleClass().add("saved-edit-buttons");
        editor = new VBox(nameField, expressionField, editError, editButtons);
        editor.getStyleClass().add("saved-editor");
        // A filter, so Esc cancels the edit before a text field or the list sees it, and is used up: it never
        // also cancels the Queued Roll (spec §3.2 "Keyboard": an inline edit takes Esc before the queue does).
        editor.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                tab.cancelEdit();
                event.consume();
            }
        });

        // A pref width of 0 lets the list fit cells to its width (no sideways scrolling); the content follows it.
        setPrefWidth(0);
        view.prefWidthProperty().bind(widthProperty());
        editor.prefWidthProperty().bind(widthProperty());
        setContentDisplay(ContentDisplay.GRAPHIC_ONLY);

        setOnMouseClicked(event -> {
            if (isEmpty() || event.getButton() != MouseButton.PRIMARY || editorFor >= 0) {
                return;
            }
            if (event.getClickCount() == 1) {
                tab.copy(getIndex());
            } else if (event.getClickCount() == 2) {
                tab.roll(getIndex());
            }
        });
    }

    /** An icon-only button: focusable by mouse and the list's keys only, its click its own. */
    private static Button iconButton(Node icon) {
        Button button = new Button();
        button.setGraphic(icon);
        button.getStyleClass().add("icon-button");
        button.setFocusTraversable(false);
        // A click on a button is the button's alone: it doesn't also copy the row into the box.
        button.addEventHandler(MouseEvent.MOUSE_CLICKED, Event::consume);
        return button;
    }

    @Override
    protected void updateItem(SavedRoll roll, boolean empty) {
        super.updateItem(roll, empty);
        setText(null);
        if (empty || roll == null) {
            editorFor = -1;
            setGraphic(null);
            setAccessibleText(null);
            return;
        }
        int index = getIndex();
        if (index == tab.editingIndex()) {
            if (editorFor != index) {
                // Entering the edit: start from the Saved Roll as it is.
                editorFor = index;
                nameField.setText(roll.name());
                expressionField.setText(roll.expression());
                editError.setVisible(false);
            }
            setGraphic(editor);
            setAccessibleText("Editing the Saved Roll " + roll.name());
            return;
        }
        editorFor = -1;
        name.setText(roll.name());
        expression.setText(roll.expression());
        Optional<ParseError> problem = roll.problem();
        warning.setText(problem.map(ParseError::message).orElse(""));
        warning.setVisible(problem.isPresent());
        up.setDisable(index == 0);
        down.setDisable(index >= getListView().getItems().size() - 1);
        up.setAccessibleText("Move Saved Roll " + roll.name() + " up");
        down.setAccessibleText("Move Saved Roll " + roll.name() + " down");
        edit.setAccessibleText("Edit Saved Roll " + roll.name());
        delete.setAccessibleText("Delete Saved Roll " + roll.name());
        setGraphic(view);
        setAccessibleText(sentence(roll));
    }

    /**
     * Returns how a screen reader reads a Saved Roll's row: one sentence.
     *
     * @param roll the Saved Roll
     * @return for example "Fireball, 8d6." or "Old homebrew, 3d7+2, won't roll: `d7` is not a Die Kind; …."
     */
    static String sentence(SavedRoll roll) {
        return roll.name() + ", " + roll.expression()
                + roll.problem().map(error -> ", won't roll: " + error.message()).orElse("") + ".";
    }

    /** Hands the editor's fields to the tab; a refusal is shown here. */
    private void saveEdit() {
        int index = editorFor;
        if (index < 0) {
            return;
        }
        tab.saveEdit(index, nameField.getText(), expressionField.getText()).ifPresent(this::showRefusal);
    }

    /** Shows why an edit was refused, focusing the field at fault, with an expression error's span selected. */
    private void showRefusal(SavedRolls.Refusal refusal) {
        editError.setText(refusal.message());
        editError.setVisible(true);
        if (refusal instanceof SavedRolls.Refusal.BadExpression bad) {
            expressionField.requestFocus();
            SpanGeometry.Selection selection = SpanGeometry.selection(bad.error().span(), expressionField.getLength());
            expressionField.selectRange(selection.anchor(), selection.caret());
        } else {
            nameField.requestFocus();
            nameField.selectAll();
        }
    }

    /** Puts the focus in the editor's name field with its text selected, as an edit starts. */
    void focusEditor() {
        nameField.requestFocus();
        nameField.selectAll();
    }

    /**
     * Tells whether the cell shows the inline editor.
     *
     * @return {@code true} while its row is being edited
     */
    boolean editing() {
        return editorFor >= 0;
    }

    /**
     * Returns the Saved Roll's name.
     *
     * @return the label
     */
    Label name() {
        return name;
    }

    /**
     * Returns the Saved Roll's expression.
     *
     * @return the label
     */
    Label expression() {
        return expression;
    }

    /**
     * Returns the warning shown when the expression no longer parses: the mark and the parse error's message.
     *
     * @return the label, visible only then
     */
    Label warning() {
        return warning;
    }

    /**
     * Returns the button that moves the Saved Roll up.
     *
     * @return the button, disabled on the first row
     */
    Button upButton() {
        return up;
    }

    /**
     * Returns the button that moves the Saved Roll down.
     *
     * @return the button, disabled on the last row
     */
    Button downButton() {
        return down;
    }

    /**
     * Returns the button that starts the inline edit.
     *
     * @return the button
     */
    Button editButton() {
        return edit;
    }

    /**
     * Returns the button that asks to delete the Saved Roll.
     *
     * @return the button
     */
    Button deleteButton() {
        return delete;
    }

    /**
     * Returns the inline editor's name field.
     *
     * @return the field
     */
    TextField nameField() {
        return nameField;
    }

    /**
     * Returns the inline editor's expression field.
     *
     * @return the field
     */
    TextField expressionField() {
        return expressionField;
    }

    /**
     * Returns the inline editor's refusal message.
     *
     * @return the label, visible after a refused Save until the next change
     */
    Label editError() {
        return editError;
    }

    /**
     * Returns the inline editor's Save.
     *
     * @return the button
     */
    Button saveButton() {
        return save;
    }

    /**
     * Returns the inline editor's Cancel.
     *
     * @return the button
     */
    Button cancelButton() {
        return cancel;
    }
}
