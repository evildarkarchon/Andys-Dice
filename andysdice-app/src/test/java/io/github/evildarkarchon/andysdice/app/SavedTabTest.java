package io.github.evildarkarchon.andysdice.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import javafx.geometry.Bounds;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.MenuItem;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Modality;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The Saved tab (spec §3.2 "Saved tab", §3.6 "Saved Rolls", #56): + New Saved Roll (and Ctrl+S) opens a dialog
 * pre-filled from the box, new Saved Rolls go at the end and survive a restart; History's menu saves one too; a click
 * copies a Saved Roll into the box and a double-click rolls it, recording its name; up/down and Alt+up/down reorder;
 * the edit button and F2 edit inline; delete asks first, Cancel the default; one that no longer parses is marked,
 * won't roll and stays editable; the empty state and the accessible names. Headless, over the real app (ADR-0011).
 */
class SavedTabTest extends HeadlessUiTest {

    private static final String EMPTY_TEXT = "No Saved Rolls yet. Use + New Saved Roll, or right-click a History entry.";

    @AfterEach
    void releaseKeys() throws Exception {
        // The headless keyboard's state may outlive this test's window (see FastForwardTest).
        onFx(() -> {
            robot().keyRelease(KeyCode.ALT);
            robot().keyRelease(KeyCode.SHIFT);
            robot().keyRelease(KeyCode.CONTROL);
        });
    }

    // --- empty state and creating ---

    @Test
    void theFirstRunHasNoSavedRolls() throws Exception {
        showSavedTab();

        assertThat(fromFx(() -> saved.list().getItems())).isEmpty();
        assertThat(fromFx(() -> saved.emptyText().getText())).isEqualTo(EMPTY_TEXT);
        assertThat(fromFx(() -> saved.emptyText().isVisible())).isTrue();
        assertThat(fromFx(() -> saved.newButton().getText())).isEqualTo("+ New Saved Roll");
        assertThat(fromFx(() -> saved.list().getAccessibleText())).isEqualTo("Saved Rolls");
    }

    @Test
    void newSavedRollOpensADialogPreFilledFromTheBox() throws Exception {
        onFx(() -> ui().expressionBox().setText(" 8d6 "));

        onFx(() -> saved.newButton().fire());

        SavedRollDialog dialog = fromFx(() -> saved.dialog().orElseThrow());
        assertThat(fromFx(() -> dialog.dialog().isShowing())).isTrue();
        assertThat(fromFx(() -> dialog.dialog().getModality())).isEqualTo(Modality.APPLICATION_MODAL);
        assertThat(fromFx(() -> dialog.dialog().getOwner())).isSameAs(stage);
        assertThat(fromFx(() -> dialog.dialog().getTitle())).isEqualTo("New Saved Roll");
        assertThat(fromFx(() -> dialog.nameField().getText())).isEmpty();
        assertThat(fromFx(() -> dialog.expressionField().getText())).isEqualTo(" 8d6 ");
        assertThat(fromFx(() -> dialog.dialog().getDialogPane().getStyleClass())).contains(Stylesheet.ROOT_CLASS);
        assertThat(fromFx(() -> dialog.saveButton().isDefaultButton())).isTrue();
        assertThat(fromFx(() -> dialog.nameField().getAccessibleText())).isEqualTo("Saved Roll name");
        assertThat(fromFx(() -> dialog.expressionField().getAccessibleText())).isEqualTo("Saved Roll expression");

        onFx(() -> {
            dialog.nameField().setText(" Fireball ");
            dialog.saveButton().fire();
        });

        assertThat(fromFx(() -> dialog.dialog().isShowing())).isFalse();
        assertThat(fromFx(() -> List.copyOf(saved.saved().rolls()))).containsExactly(new SavedRoll("Fireball", "8d6"));
        persistence.flush();
        assertThat(savedFile()).isEqualTo("#andysdice saved-rolls.txt 1\nFireball\t8d6\n");
    }

    @Test
    void ctrlSOpensNewSavedRoll() throws Exception {
        onFx(() -> {
            ui().expressionBox().setText("1d20+7");
            ui().focusBox();
        });

        onFx(() -> {
            robot().keyPress(KeyCode.CONTROL);
            robot().keyType(KeyCode.S);
            robot().keyRelease(KeyCode.CONTROL);
        });

        SavedRollDialog dialog = fromFx(() -> saved.dialog().orElseThrow());
        assertThat(fromFx(() -> dialog.expressionField().getText())).isEqualTo("1d20+7");
        // Ctrl+S isn't typed into the box.
        assertThat(fromFx(() -> ui().expressionBox().getText())).isEqualTo("1d20+7");
        onFx(() -> dialog.cancelButton().fire());
        assertThat(fromFx(() -> dialog.dialog().isShowing())).isFalse();
        assertThat(fromFx(() -> saved.saved().rolls())).isEmpty();
    }

    @Test
    void newSavedRollsGoAtTheEndAndSurviveARestart() throws Exception {
        create("Fireball", "8d6");
        create("Longsword attack", "1d20+7");
        create("Stats", "4d6rr1kh3");

        restartApp();

        assertThat(names(cells())).containsExactly("Fireball", "Longsword attack", "Stats");
        assertThat(fromFx(() -> saved.emptyText().isVisible())).isFalse();
    }

    @Test
    void aBadNameIsRefusedWithItsMessageAndTheDialogStaysOpen() throws Exception {
        create("Fireball", "8d6");
        onFx(() -> ui().expressionBox().setText("10d6"));
        onFx(() -> saved.newButton().fire());
        SavedRollDialog dialog = fromFx(() -> saved.dialog().orElseThrow());

        onFx(() -> {
            dialog.nameField().setText("FIREBALL");
            dialog.saveButton().fire();
        });

        assertThat(fromFx(() -> dialog.dialog().isShowing())).isTrue();
        assertThat(fromFx(() -> dialog.error().getText())).isEqualTo("There's already a Saved Roll called \"Fireball\".");
        assertThat(fromFx(() -> dialog.error().isVisible())).isTrue();
        assertThat(fromFx(() -> dialog.nameField().isFocused())).isTrue();
        assertThat(fromFx(() -> saved.saved().rolls())).hasSize(1);
        onFx(() -> dialog.cancelButton().fire());
    }

    @Test
    void anExpressionThatDoesntParseIsRefusedSelectingItsSpan() throws Exception {
        onFx(() -> ui().expressionBox().setText("2d6+1d7"));
        onFx(() -> saved.newButton().fire());
        SavedRollDialog dialog = fromFx(() -> saved.dialog().orElseThrow());

        onFx(() -> {
            dialog.nameField().setText("Odd");
            dialog.saveButton().fire();
        });

        assertThat(fromFx(() -> dialog.dialog().isShowing())).isTrue();
        assertThat(fromFx(() -> dialog.error().getText())).startsWith("`d7` is not a Die Kind");
        assertThat(fromFx(() -> dialog.expressionField().isFocused())).isTrue();
        assertThat(fromFx(() -> dialog.expressionField().getSelectedText())).isEqualTo("d7");
        assertThat(fromFx(() -> saved.saved().rolls())).isEmpty();
        onFx(() -> dialog.cancelButton().fire());
    }

    @Test
    void historysMenuSavesAPastRollAsASavedRoll() throws Exception {
        faces.then(4);
        roll("1d6+2");
        playToRest();
        HistoryCell cell = historyCells().getFirst();
        onFx(() -> {
            Bounds screen = cell.localToScreen(cell.getBoundsInLocal());
            cell.fireEvent(new ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED, 10, 10,
                    screen.getMinX() + 10, screen.getMinY() + 10, false, null));
        });
        assertThat(fromFx(() -> history.menu().getItems())).extracting(MenuItem::getText)
                .containsExactly("Roll again", "Copy into the box", "Save as a Saved Roll…");
        onFx(() -> ui().expressionBox().setText("something else"));

        onFx(() -> history.menu().getItems().get(2).fire());

        SavedRollDialog dialog = fromFx(() -> saved.dialog().orElseThrow());
        assertThat(fromFx(() -> dialog.expressionField().getText())).isEqualTo("1d6+2");
        onFx(() -> {
            dialog.nameField().setText("Dagger");
            dialog.saveButton().fire();
        });
        assertThat(fromFx(() -> List.copyOf(saved.saved().rolls()))).containsExactly(new SavedRoll("Dagger", "1d6+2"));
    }

    @Test
    void theCapIsOneHundred() throws Exception {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            body.append("Roll ").append(i).append("\t1d6\n");
        }
        load(body.toString());
        onFx(() -> ui().expressionBox().setText("1d8"));
        onFx(() -> saved.newButton().fire());
        SavedRollDialog dialog = fromFx(() -> saved.dialog().orElseThrow());

        onFx(() -> {
            dialog.nameField().setText("One more");
            dialog.saveButton().fire();
        });

        assertThat(fromFx(() -> dialog.dialog().isShowing())).isTrue();
        assertThat(fromFx(() -> dialog.error().getText()))
                .isEqualTo("There are already 100 Saved Rolls, the most kept. Delete one to save another.");
        assertThat(fromFx(() -> saved.saved().rolls())).hasSize(100);
        onFx(() -> dialog.cancelButton().fire());
    }

    // --- copying and rolling ---

    @Test
    void aClickCopiesASavedRollIntoTheBox() throws Exception {
        load("Fireball\t8d6\nStats\t4d6rr1kh3\n");
        onFx(() -> ui().expressionBox().setText("1d20"));

        click(cells().get(1), 1);

        assertThat(fromFx(() -> ui().expressionBox().getText())).isEqualTo("4d6rr1kh3");
        assertThat(fromFx(() -> controller().underWay())).isFalse();
    }

    @Test
    void aDoubleClickRollsItAndTheRollIsRecordedWithItsName() throws Exception {
        load("Fireball\t2d6+3\n");
        faces.then(2, 5);

        SavedCell cell = cells().getFirst();
        click(cell, 1);
        click(cell, 2);
        onFx(() -> {});
        playToRest();

        assertThat(fromFx(() -> ((RollStatus.Rested) controller().status()).savedRollName())).contains("Fireball");
        assertThat(fromFx(() -> ui().resultStrip().details().getText())).startsWith("Fireball");
        List<PastRoll> rolls = fromFx(() -> List.copyOf(history.list().getItems()));
        assertThat(rolls).singleElement().satisfies(roll -> {
            assertThat(roll.expression()).isEqualTo("2d6+3");
            assertThat(roll.savedRollName()).contains("Fireball");
            assertThat(roll.total()).isEqualTo(10);
        });
        assertThat(fromFx(() -> ui().expressionBox().isFocused())).isTrue();
        assertThat(fromFx(() -> ui().expressionBox().getSelectedText())).isEqualTo("2d6+3");
    }

    @Test
    void aDoubleClickWhileDiceMoveQueuesItAndTheQueuedRollKeepsTheName() throws Exception {
        load("Fireball\t8d6\n");
        roll("3d6");
        advance(0.2);

        SavedCell cell = cells().getFirst();
        click(cell, 1);
        click(cell, 2);

        assertThat(queuedRoll()).contains("8d6");
        playToRest();
        advance(RollController.BEAT_SECONDS + 0.1);
        onFx(() -> {});
        playToRest();
        List<PastRoll> rolls = fromFx(() -> List.copyOf(history.list().getItems()));
        assertThat(rolls).extracting(PastRoll::expression).containsExactly("8d6", "3d6");
        assertThat(rolls).extracting(PastRoll::savedRollName).containsExactly(Optional.of("Fireball"),
                Optional.empty());
    }

    @Test
    void aRollTypedInTheBoxCarriesNoName() throws Exception {
        load("Fireball\t8d6\n");
        faces.then(1);

        roll("1d6");
        playToRest();

        assertThat(fromFx(() -> history.list().getItems().getFirst().savedRollName())).isEmpty();
    }

    @Test
    void listKeysMoveCopyAndRoll() throws Exception {
        load("Fireball\t8d6\nDagger\t1d4+2\n");
        cells();
        onFx(() -> {
            saved.list().requestFocus();
            saved.list().getSelectionModel().select(0);
        });

        onFx(() -> robot().keyType(KeyCode.DOWN));
        assertThat(fromFx(() -> saved.list().getSelectionModel().getSelectedItem().name())).isEqualTo("Dagger");
        onFx(() -> robot().keyType(KeyCode.SPACE));
        assertThat(fromFx(() -> ui().expressionBox().getText())).isEqualTo("1d4+2");
        assertThat(fromFx(() -> saved.list().isFocused())).isTrue();

        faces.then(3);
        onFx(() -> robot().keyType(KeyCode.ENTER));
        onFx(() -> {});
        assertThat(fromFx(() -> controller().underWay())).isTrue();
        playToRest();
        assertThat(fromFx(() -> history.list().getItems().getFirst().savedRollName())).contains("Dagger");
    }

    // --- reordering ---

    @Test
    void theUpAndDownButtonsReorderAndTheOrderIsSaved() throws Exception {
        load("Fireball\t8d6\nDagger\t1d4+2\nStats\t4d6rr1kh3\n");
        List<SavedCell> cells = cells();
        assertThat(fromFx(() -> cells.getFirst().upButton().isDisabled())).isTrue();
        assertThat(fromFx(() -> cells.getLast().downButton().isDisabled())).isTrue();
        assertThat(fromFx(() -> cells.get(1).upButton().isDisabled())).isFalse();

        onFx(() -> cells.get(2).upButton().fire());
        assertThat(names(cells())).containsExactly("Fireball", "Stats", "Dagger");
        SavedCell first = cells().getFirst();
        onFx(() -> first.downButton().fire());

        assertThat(names(cells())).containsExactly("Stats", "Fireball", "Dagger");
        assertThat(fromFx(() -> saved.list().getSelectionModel().getSelectedItem().name())).isEqualTo("Fireball");
        // A mouse click on a button is the button's alone: it doesn't also copy the row into the box.
        SavedCell top = cells().getFirst();
        onFx(() -> top.downButton().fireEvent(new MouseEvent(MouseEvent.MOUSE_CLICKED, 5, 5, 0, 0,
                MouseButton.PRIMARY, 1, false, false, false, false, false, false, false, false, false, true, null)));
        assertThat(fromFx(() -> ui().expressionBox().getText())).isEmpty();
        persistence.flush();
        assertThat(savedFile()).isEqualTo("#andysdice saved-rolls.txt 1\nStats\t4d6rr1kh3\nFireball\t8d6\n"
                + "Dagger\t1d4+2\n");
        restartApp();
        assertThat(names(cells())).containsExactly("Stats", "Fireball", "Dagger");
    }

    @Test
    void altUpAndDownReorderTheSelectedSavedRoll() throws Exception {
        load("Fireball\t8d6\nDagger\t1d4+2\nStats\t4d6rr1kh3\n");
        cells();
        onFx(() -> {
            saved.list().requestFocus();
            saved.list().getSelectionModel().select(0);
        });

        onFx(() -> {
            robot().keyPress(KeyCode.ALT);
            robot().keyType(KeyCode.DOWN);
            robot().keyType(KeyCode.DOWN);
            robot().keyRelease(KeyCode.ALT);
        });

        assertThat(names(cells())).containsExactly("Dagger", "Stats", "Fireball");
        assertThat(fromFx(() -> saved.list().getSelectionModel().getSelectedIndex())).isEqualTo(2);

        onFx(() -> {
            saved.list().requestFocus();
            robot().keyPress(KeyCode.ALT);
            robot().keyType(KeyCode.UP);
            robot().keyRelease(KeyCode.ALT);
        });
        assertThat(names(cells())).containsExactly("Dagger", "Fireball", "Stats");
    }

    // --- editing inline ---

    @Test
    void theEditButtonEditsTheNameAndExpressionInline() throws Exception {
        load("Fireball\t8d6\nDagger\t1d4+2\n");
        SavedCell cell = cells().getFirst();

        onFx(() -> cell.editButton().fire());

        SavedCell editing = editingCell();
        assertThat(fromFx(() -> editing.nameField().getText())).isEqualTo("Fireball");
        assertThat(fromFx(() -> editing.expressionField().getText())).isEqualTo("8d6");
        assertThat(fromFx(() -> editing.nameField().isFocused())).isTrue();

        onFx(() -> {
            editing.nameField().setText(" Big fireball ");
            editing.expressionField().setText("10d6");
            editing.saveButton().fire();
        });

        assertThat(fromFx(() -> List.copyOf(saved.saved().rolls()))).containsExactly(
                new SavedRoll("Big fireball", "10d6"), new SavedRoll("Dagger", "1d4+2"));
        assertThat(fromFx(() -> saved.editingIndex())).isEqualTo(-1);
        assertThat(names(cells())).containsExactly("Big fireball", "Dagger");
        assertThat(fromFx(() -> saved.list().isFocused())).isTrue();
        persistence.flush();
        assertThat(savedFile()).isEqualTo("#andysdice saved-rolls.txt 1\nBig fireball\t10d6\nDagger\t1d4+2\n");
    }

    @Test
    void f2EditsAndEnterSavesAndEscCancels() throws Exception {
        load("Fireball\t8d6\nDagger\t1d4+2\n");
        cells();
        onFx(() -> {
            saved.list().requestFocus();
            saved.list().getSelectionModel().select(1);
        });

        onFx(() -> robot().keyType(KeyCode.F2));
        SavedCell editing = editingCell();
        assertThat(fromFx(() -> editing.getIndex())).isEqualTo(1);
        onFx(() -> editing.expressionField().setText("1d4+3"));
        onFx(() -> {
            editing.expressionField().requestFocus();
            robot().keyType(KeyCode.ENTER);
        });
        assertThat(fromFx(() -> saved.saved().rolls().get(1))).isEqualTo(new SavedRoll("Dagger", "1d4+3"));
        // Enter saved the edit; it didn't also roll.
        assertThat(fromFx(() -> controller().underWay())).isFalse();

        // Esc cancels an edit, before (and instead of) cancelling a Queued Roll.
        roll("3d6");
        advance(0.2);
        onFx(() -> session.roll("1d8"));
        onFx(() -> {
            saved.list().requestFocus();
            robot().keyType(KeyCode.F2);
        });
        SavedCell again = editingCell();
        onFx(() -> again.nameField().setText("Knife"));
        onFx(() -> robot().keyType(KeyCode.ESCAPE));

        assertThat(fromFx(() -> saved.editingIndex())).isEqualTo(-1);
        assertThat(fromFx(() -> saved.saved().rolls().get(1).name())).isEqualTo("Dagger");
        assertThat(queuedRoll()).contains("1d8");
        assertThat(fromFx(() -> saved.list().isFocused())).isTrue();
    }

    @Test
    void anEditBreakingTheRulesIsRefusedWithItsMessage() throws Exception {
        load("Fireball\t8d6\nDagger\t1d4+2\n");
        SavedCell last = cells().getLast();
        onFx(() -> last.editButton().fire());
        SavedCell editing = editingCell();

        onFx(() -> {
            editing.nameField().setText("fireball");
            editing.saveButton().fire();
        });
        assertThat(fromFx(() -> editing.editError().getText()))
                .isEqualTo("There's already a Saved Roll called \"Fireball\".");
        assertThat(fromFx(() -> editing.editError().isVisible())).isTrue();
        assertThat(fromFx(() -> editing.nameField().isFocused())).isTrue();

        onFx(() -> {
            editing.nameField().setText("Dagger");
            editing.expressionField().setText("1d4+1d7");
            editing.saveButton().fire();
        });
        assertThat(fromFx(() -> editing.editError().getText())).startsWith("`d7` is not a Die Kind");
        assertThat(fromFx(() -> editing.expressionField().isFocused())).isTrue();
        assertThat(fromFx(() -> editing.expressionField().getSelectedText())).isEqualTo("d7");
        assertThat(fromFx(() -> saved.editingIndex())).isEqualTo(1);
        assertThat(fromFx(() -> saved.saved().rolls().get(1))).isEqualTo(new SavedRoll("Dagger", "1d4+2"));
        onFx(() -> editing.cancelButton().fire());
    }

    // --- deleting ---

    @Test
    void deletingAsksFirstWithCancelAsTheDefault() throws Exception {
        load("Fireball\t8d6\nDagger\t1d4+2\n");

        SavedCell first = cells().getFirst();
        onFx(() -> first.deleteButton().fire());

        Dialog<ButtonType> dialog = fromFx(() -> saved.confirmation().orElseThrow());
        assertThat(fromFx(dialog::getModality)).isEqualTo(Modality.APPLICATION_MODAL);
        assertThat(fromFx(() -> dialog.getOwner())).isSameAs(stage);
        assertThat(fromFx(dialog::getContentText)).contains("Fireball");
        Button cancel = fromFx(() -> button(dialog, ButtonBar.ButtonData.CANCEL_CLOSE));
        Button delete = fromFx(() -> button(dialog, ButtonBar.ButtonData.OK_DONE));
        assertThat(fromFx(cancel::isDefaultButton)).isTrue();
        assertThat(fromFx(delete::isDefaultButton)).isFalse();
        assertThat(fromFx(delete::getText)).isEqualTo("Delete");

        onFx(cancel::fire);
        assertThat(fromFx(dialog::isShowing)).isFalse();
        assertThat(fromFx(() -> saved.saved().rolls())).hasSize(2);

        SavedCell remaining = cells().getFirst();
        onFx(() -> remaining.deleteButton().fire());
        onFx(() -> button(saved.confirmation().orElseThrow(), ButtonBar.ButtonData.OK_DONE).fire());

        assertThat(names(cells())).containsExactly("Dagger");
        persistence.flush();
        assertThat(savedFile()).isEqualTo("#andysdice saved-rolls.txt 1\nDagger\t1d4+2\n");
    }

    @Test
    void theDeleteKeyAsksToo() throws Exception {
        load("Fireball\t8d6\nDagger\t1d4+2\n");
        cells();
        onFx(() -> {
            saved.list().requestFocus();
            saved.list().getSelectionModel().select(1);
        });

        onFx(() -> robot().keyType(KeyCode.DELETE));

        Dialog<ButtonType> dialog = fromFx(() -> saved.confirmation().orElseThrow());
        assertThat(fromFx(dialog::getContentText)).contains("Dagger");
        onFx(() -> button(dialog, ButtonBar.ButtonData.OK_DONE).fire());
        assertThat(names(cells())).containsExactly("Fireball");
        assertThat(fromFx(() -> saved.emptyText().isVisible())).isFalse();

        SavedCell first = cells().getFirst();
        onFx(() -> first.deleteButton().fire());
        onFx(() -> button(saved.confirmation().orElseThrow(), ButtonBar.ButtonData.OK_DONE).fire());
        assertThat(fromFx(() -> saved.emptyText().isVisible())).isTrue();
    }

    // --- one that no longer parses ---

    @Test
    void aSavedRollThatNoLongerParsesIsMarkedWontRollAndStaysEditable() throws Exception {
        load("Old homebrew\t3d7+2\n");
        SavedCell cell = cells().getFirst();

        assertThat(fromFx(() -> cell.warning().isVisible())).isTrue();
        assertThat(fromFx(() -> cell.warning().getText())).startsWith("`d7` is not a Die Kind");
        assertThat(fromFx(() -> cell.warning().getGraphic())).isNotNull();
        assertThat(fromFx(cell::getAccessibleText)).startsWith("Old homebrew, 3d7+2, won't roll: `d7` is not a Die Kind");

        click(cell, 1);
        click(cell, 2);
        onFx(() -> {});
        assertThat(fromFx(() -> controller().underWay())).isFalse();
        assertThat(queuedRoll()).isEmpty();

        onFx(() -> cell.editButton().fire());
        SavedCell editing = editingCell();
        onFx(() -> {
            editing.expressionField().setText("3d6+2");
            editing.saveButton().fire();
        });
        SavedCell fixed = cells().getFirst();
        assertThat(fromFx(() -> fixed.warning().isVisible())).isFalse();
        faces.then(1, 1, 1);
        click(fixed, 1);
        click(fixed, 2);
        onFx(() -> {});
        playToRest();
        assertThat(fromFx(() -> history.list().getItems().getFirst().total())).isEqualTo(5);
    }

    // --- accessibility ---

    @Test
    void iconButtonsHaveAccessibleNamesAndEachRowReadsAsOneSentence() throws Exception {
        load("Fireball\t8d6\n");
        SavedCell cell = cells().getFirst();

        assertThat(fromFx(() -> cell.deleteButton().getAccessibleText())).isEqualTo("Delete Saved Roll Fireball");
        assertThat(fromFx(() -> cell.editButton().getAccessibleText())).isEqualTo("Edit Saved Roll Fireball");
        assertThat(fromFx(() -> cell.upButton().getAccessibleText())).isEqualTo("Move Saved Roll Fireball up");
        assertThat(fromFx(() -> cell.downButton().getAccessibleText())).isEqualTo("Move Saved Roll Fireball down");
        assertThat(fromFx(cell::getAccessibleText)).isEqualTo("Fireball, 8d6.");
        assertThat(fromFx(() -> saved.newButton().getAccessibleText())).isEqualTo("New Saved Roll");
        // Every icon is code-drawn: none of the buttons carries text.
        assertThat(fromFx(() -> cell.deleteButton().getText())).isNullOrEmpty();
        assertThat(fromFx(() -> cell.deleteButton().getGraphic())).isInstanceOf(javafx.scene.shape.SVGPath.class);
    }

    // --- helpers ---

    /** The one visible cell showing the inline editor. */
    private SavedCell editingCell() throws Exception {
        return cells().stream().filter(cell -> {
            try {
                return fromFx(cell::editing);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).findFirst().orElseThrow();
    }

    /** Makes a Saved Roll through + New Saved Roll, as the Player does. */
    private void create(String name, String expression) throws Exception {
        onFx(() -> ui().expressionBox().setText(expression));
        onFx(() -> saved.newButton().fire());
        onFx(() -> {
            SavedRollDialog dialog = saved.dialog().orElseThrow();
            dialog.nameField().setText(name);
            dialog.saveButton().fire();
        });
    }

    /** Writes {@code saved-rolls.txt} as an earlier run left it, then restarts the app so it loads it. */
    private void load(String body) throws Exception {
        Files.writeString(dataFolder.resolve(SavedRolls.FILE_NAME), "#andysdice saved-rolls.txt 1\n" + body,
                StandardCharsets.UTF_8);
        restartApp();
    }

    private String savedFile() throws IOException {
        return Files.readString(dataFolder.resolve(SavedRolls.FILE_NAME), StandardCharsets.UTF_8);
    }

    private void showSavedTab() throws Exception {
        onFx(() -> ui().tabs().getSelectionModel().select(ui().savedTab()));
        pulse(stage.getScene());
    }

    /** The Saved tab's filled cells after a pulse, in list order; selects the tab first so its list is laid out. */
    private List<SavedCell> cells() throws Exception {
        showSavedTab();
        return fromFx(() -> saved.list().lookupAll("." + SavedCell.STYLE_CLASS).stream()
                .map(SavedCell.class::cast)
                .filter(cell -> !cell.isEmpty() && cell.isVisible())
                .sorted(Comparator.comparingInt(SavedCell::getIndex))
                .toList());
    }

    private List<HistoryCell> historyCells() throws Exception {
        pulse(stage.getScene());
        return fromFx(() -> history.list().lookupAll(".history-cell").stream()
                .map(HistoryCell.class::cast)
                .filter(cell -> !cell.isEmpty() && cell.isVisible())
                .sorted(Comparator.comparingInt(HistoryCell::getIndex))
                .toList());
    }

    private static List<String> names(List<SavedCell> cells) throws Exception {
        return fromFx(() -> cells.stream().map(cell -> cell.name().getText()).toList());
    }

    /** Clicks a cell with the primary button, as the {@code count}th click of a run. */
    private static void click(SavedCell cell, int count) throws Exception {
        onFx(() -> {
            Bounds screen = cell.localToScreen(cell.getBoundsInLocal());
            double x = 20;
            double y = cell.getHeight() / 2;
            cell.fireEvent(new MouseEvent(MouseEvent.MOUSE_CLICKED, x, y, screen.getMinX() + x,
                    screen.getMinY() + y, MouseButton.PRIMARY, count, false, false, false, false, false, false,
                    false, false, false, true, null));
        });
    }

    /** The dialog's button of this kind. Call it on the JavaFX thread. */
    private static Button button(Dialog<ButtonType> dialog, ButtonBar.ButtonData data) {
        Optional<ButtonType> type = dialog.getDialogPane().getButtonTypes().stream()
                .filter(t -> t.getButtonData() == data).findFirst();
        return (Button) dialog.getDialogPane().lookupButton(type.orElseThrow());
    }
}
