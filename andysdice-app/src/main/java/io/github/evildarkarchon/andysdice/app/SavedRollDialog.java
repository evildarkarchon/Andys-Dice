package io.github.evildarkarchon.andysdice.app;

import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import javafx.event.ActionEvent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.stage.Modality;
import javafx.stage.Window;

/**
 * The + New Saved Roll dialog (spec §3.2 "Saved tab"): a name and a Roll Expression, the expression pre-filled (with
 * the box's, or a History entry's for Save as a Saved Roll…). Save checks the rules first ({@link SavedRolls#refusal}):
 * a refusal shows its message under the fields and keeps the dialog open, focusing the field at fault, with an
 * expression error's span selected as the box selects it ({@link SpanGeometry}). Save is the default button (Enter in
 * either field saves); Cancel, Esc and closing the dialog save nothing.
 *
 * <p>Like {@link ConfirmDialog} it is application-modal but shown with {@link Dialog#show()}, so the caller returns at
 * once and the headless tests can drive it from the JavaFX thread.
 *
 * <p>Thread contract: the JavaFX Application Thread.
 */
final class SavedRollDialog {

    /** The dialog's title. */
    static final String TITLE = "New Saved Roll";

    private final Dialog<ButtonType> dialog = new Dialog<>();
    private final TextField nameField = new TextField();
    private final TextField expressionField = new TextField();
    private final Label error = new Label();
    private final ButtonType save = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
    private final Button saveButton;
    private final Button cancelButton;

    /**
     * Shows the dialog.
     *
     * @param owner the window it is modal over
     * @param expression what the expression field starts with
     * @param check the rules: given the name and expression as typed, why they can't be saved, if they can't
     * @param onSave given the new Saved Roll (trimmed) once Save has passed the rules
     */
    private SavedRollDialog(Window owner, String expression,
            BiFunction<String, String, Optional<SavedRolls.Refusal>> check, Consumer<SavedRoll> onSave) {
        Objects.requireNonNull(check, "check");
        Objects.requireNonNull(onSave, "onSave");
        dialog.initOwner(owner);
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle(TITLE);
        dialog.setHeaderText(null);
        dialog.setGraphic(null);

        nameField.setPromptText("e.g. Fireball");
        nameField.setAccessibleText("Saved Roll name");
        nameField.setPrefColumnCount(24);
        expressionField.setText(expression);
        expressionField.setAccessibleText("Saved Roll expression");
        Label nameLabel = new Label("Name");
        nameLabel.setLabelFor(nameField);
        Label expressionLabel = new Label("Roll Expression");
        expressionLabel.setLabelFor(expressionField);
        error.getStyleClass().add("saved-error");
        error.setWrapText(true);
        error.setMaxWidth(360);
        error.setGraphic(Icons.warning());
        error.managedProperty().bind(error.visibleProperty());
        error.setVisible(false);
        // A refusal's message belongs to what was typed when Save was pressed; a change makes it stale.
        nameField.textProperty().addListener((text, before, after) -> error.setVisible(false));
        expressionField.textProperty().addListener((text, before, after) -> error.setVisible(false));

        GridPane grid = new GridPane(10, 8);
        grid.addRow(0, nameLabel, nameField);
        grid.addRow(1, expressionLabel, expressionField);
        grid.add(error, 0, 2, 2, 1);
        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().setAll(save, ButtonType.CANCEL);
        // A dialog is its own scene, so it takes the theme explicitly (see Stylesheet).
        dialog.getDialogPane().getStylesheets().addAll(Stylesheet.uri(), SavedTab.uri());
        dialog.getDialogPane().getStyleClass().add(Stylesheet.ROOT_CLASS);
        saveButton = (Button) dialog.getDialogPane().lookupButton(save);
        cancelButton = (Button) dialog.getDialogPane().lookupButton(ButtonType.CANCEL);

        // A filter on Save's action: consuming it keeps the dialog open, which is how a refusal is shown.
        saveButton.addEventFilter(ActionEvent.ACTION, event -> {
            Optional<SavedRolls.Refusal> refusal = check.apply(nameField.getText(), expressionField.getText());
            if (refusal.isPresent()) {
                event.consume();
                show(refusal.get());
            }
        });
        dialog.setOnShown(event -> nameField.requestFocus());
        dialog.setOnHidden(event -> {
            if (dialog.getResult() == save) {
                onSave.accept(SavedRoll.of(nameField.getText(), expressionField.getText()));
            }
        });
    }

    /**
     * Shows a new Saved Roll dialog over a window.
     *
     * @param owner the window it is modal over and centred on
     * @param expression what the expression field starts with
     * @param check given the name and expression as typed, why they can't be saved, if they can't
     * @param onSave given the new Saved Roll once Save has passed {@code check}; nothing is given on Cancel
     * @return the dialog, already showing
     */
    static SavedRollDialog open(Window owner, String expression,
            BiFunction<String, String, Optional<SavedRolls.Refusal>> check, Consumer<SavedRoll> onSave) {
        SavedRollDialog shown = new SavedRollDialog(owner, expression, check, onSave);
        shown.dialog.show();
        return shown;
    }

    /** Shows a refusal's message and puts the focus on the field at fault. */
    private void show(SavedRolls.Refusal refusal) {
        // Set after the text listeners have run, so the message stays until the next change.
        error.setText(refusal.message());
        error.setVisible(true);
        if (refusal instanceof SavedRolls.Refusal.BadExpression bad) {
            expressionField.requestFocus();
            SpanGeometry.Selection selection = SpanGeometry.selection(bad.error().span(), expressionField.getLength());
            expressionField.selectRange(selection.anchor(), selection.caret());
        } else {
            nameField.requestFocus();
            nameField.selectAll();
        }
        dialog.getDialogPane().getScene().getWindow().sizeToScene();
    }

    /**
     * Returns the dialog itself.
     *
     * @return the dialog
     */
    Dialog<ButtonType> dialog() {
        return dialog;
    }

    /**
     * Returns the name field.
     *
     * @return the field, empty at first
     */
    TextField nameField() {
        return nameField;
    }

    /**
     * Returns the expression field.
     *
     * @return the field, pre-filled
     */
    TextField expressionField() {
        return expressionField;
    }

    /**
     * Returns the refusal's message.
     *
     * @return the label, visible only after a refused Save and until the next change
     */
    Label error() {
        return error;
    }

    /**
     * Returns Save, the default button.
     *
     * @return the button
     */
    Button saveButton() {
        return saveButton;
    }

    /**
     * Returns Cancel.
     *
     * @return the button
     */
    Button cancelButton() {
        return cancelButton;
    }
}
