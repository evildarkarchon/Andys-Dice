package io.github.evildarkarchon.andysdice.app;

import java.util.Objects;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.stage.Modality;
import javafx.stage.Window;

/**
 * The modal confirmation every destructive action asks for first (spec §3.2 "Notices and confirmations"): clearing
 * the Roll History (#55), deleting a Saved Roll (#56). Cancel is the default button, so Enter right away cancels,
 * and Esc cancels too; only the named action's own button goes ahead.
 *
 * <p>It is shown with {@link Dialog#show()}, not {@code showAndWait()}: the dialog is still application-modal, but
 * the caller's event handler returns at once and the action runs when the Player picks it. That also keeps the
 * headless UI tests, which drive the dialog from the JavaFX thread, from blocking in a nested event loop.
 *
 * <p>Thread contract: the JavaFX Application Thread.
 */
final class ConfirmDialog {

    private ConfirmDialog() {}

    /**
     * Shows a modal confirmation over a window.
     *
     * @param owner the window it is modal over and centred on
     * @param title the dialog's title
     * @param message what will happen, in a sentence or two
     * @param action the label of the button that goes ahead ("Clear all")
     * @param onConfirm what runs if the Player picks {@code action}; nothing runs on Cancel, Esc or closing it
     * @return the dialog, already showing
     */
    static Dialog<ButtonType> ask(Window owner, String title, String message, String action, Runnable onConfirm) {
        Objects.requireNonNull(onConfirm, "onConfirm");
        ButtonType go = new ButtonType(action, ButtonBar.ButtonData.OK_DONE);
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle(title);
        dialog.setHeaderText(null);
        dialog.setGraphic(null);
        dialog.setContentText(message);
        dialog.getDialogPane().getButtonTypes().setAll(go, ButtonType.CANCEL);
        // A dialog is its own scene, so it takes the theme explicitly (see Stylesheet).
        dialog.getDialogPane().getStylesheets().add(Stylesheet.uri());
        dialog.getDialogPane().getStyleClass().add(Stylesheet.ROOT_CLASS);

        // OK_DONE would make the action the default button; destructive actions default to Cancel instead.
        Button goButton = (Button) dialog.getDialogPane().lookupButton(go);
        Button cancelButton = (Button) dialog.getDialogPane().lookupButton(ButtonType.CANCEL);
        goButton.setDefaultButton(false);
        cancelButton.setDefaultButton(true);
        dialog.setOnShown(event -> cancelButton.requestFocus());
        dialog.setOnHidden(event -> {
            if (dialog.getResult() == go) {
                onConfirm.run();
            }
        });
        dialog.show();
        return dialog;
    }
}
