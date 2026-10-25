package io.github.evildarkarchon.andysdice.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.input.KeyCode;
import javafx.stage.Popup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The window-wide keyboard map (spec §3.2 "Keyboard", #62), driven through the public headless Robot as a Player's
 * keyboard would: the focus order, the box's focus at start and after every Roll, the global shortcuts, the absence
 * of single-letter shortcuts, and Esc's order of precedence.
 */
class KeyboardMapTest extends HeadlessUiTest {

    /** Lets go of any modifier a test held, so the headless keyboard's state can't leak into the next test. */
    @AfterEach
    void releaseModifiers() throws Exception {
        onFx(() -> {
            robot().keyRelease(KeyCode.CONTROL);
            robot().keyRelease(KeyCode.SHIFT);
            robot().keyRelease(KeyCode.ALT);
        });
    }

    @Test
    void tabRunsFromTheBoxThroughTheDieStripRollTheTabsTheOpenTabAndTheBellBackToTheBox() throws Exception {
        List<Node> expected = new ArrayList<>();
        expected.add(ui().expressionBox());
        expected.addAll(ui().dieButtons());
        expected.add(ui().rollButton());
        expected.add(ui().tabs());
        // The History tab is open at first run: its list (Clear all… is disabled while it is empty).
        expected.add(history.list());
        expected.add(ui().bell());
        expected.add(ui().expressionBox());

        assertThat(focusOwner()).isSameAs(ui().expressionBox());
        assertThat(walk(KeyCode.TAB, expected.size() - 1)).containsExactlyElementsOf(expected.subList(1,
                expected.size()));
    }

    @Test
    void shiftTabRunsTheSameOrderBackwards() throws Exception {
        List<Node> expected = new ArrayList<>();
        expected.add(ui().bell());
        expected.add(history.list());
        expected.add(ui().tabs());
        expected.add(ui().rollButton());
        expected.addAll(ui().dieButtons().reversed());
        expected.add(ui().expressionBox());

        onFx(() -> robot().keyPress(KeyCode.SHIFT));
        List<Node> walked = walk(KeyCode.TAB, expected.size());
        onFx(() -> robot().keyRelease(KeyCode.SHIFT));

        assertThat(walked).containsExactlyElementsOf(expected);
    }

    @Test
    void theOpenTabsContentComesBetweenTheTabsAndTheBell() throws Exception {
        onFx(() -> ui().tabs().getSelectionModel().select(ui().savedTab()));
        pulse(stage.getScene());
        onFx(() -> ui().tabs().requestFocus());

        assertThat(walk(KeyCode.TAB, 3)).containsExactly(saved.newButton(), saved.list(), ui().bell());
    }

    @Test
    void theBoxHasFocusAfterEveryRollHoweverItWasRolled() throws Exception {
        Files.writeString(dataFolder.resolve("saved-rolls.txt"), "#andysdice saved-rolls.txt 1\nFireball\t8d6\n");
        restartApp();

        // Enter in the box.
        onFx(() -> ui().expressionBox().setText("1d6"));
        onFx(() -> robot().keyType(KeyCode.ENTER));
        playToRest();
        assertThat(focusOwner()).isSameAs(ui().expressionBox());

        // The Roll button, reached by Tab and pressed with Space.
        walk(KeyCode.TAB, ui().dieButtons().size() + 1);
        assertThat(focusOwner()).isSameAs(ui().rollButton());
        onFx(() -> robot().keyType(KeyCode.SPACE));
        playToRest();
        assertThat(focusOwner()).isSameAs(ui().expressionBox());

        // Enter on a Roll History row.
        onFx(() -> {
            history.list().requestFocus();
            history.list().getSelectionModel().select(0);
        });
        onFx(() -> robot().keyType(KeyCode.ENTER));
        playToRest();
        assertThat(focusOwner()).isSameAs(ui().expressionBox());

        // Enter on a Saved Roll.
        onFx(() -> ui().tabs().getSelectionModel().select(ui().savedTab()));
        pulse(stage.getScene());
        onFx(() -> {
            saved.list().requestFocus();
            saved.list().getSelectionModel().select(0);
        });
        onFx(() -> robot().keyType(KeyCode.ENTER));
        playToRest();
        assertThat(focusOwner()).isSameAs(ui().expressionBox());
        assertThat(fromFx(() -> ui().resultStrip().details().getText())).startsWith("Fireball");
        assertThat(tray.shows()).isEqualTo(4);
    }

    @Test
    void ctrlOneToFourPickTheTabsInOrderAndFocusStaysInTheBox() throws Exception {
        List<Tab> picked = new ArrayList<>();
        for (KeyCode digit : List.of(KeyCode.DIGIT1, KeyCode.DIGIT2, KeyCode.DIGIT3, KeyCode.DIGIT4)) {
            ctrl(digit);
            picked.add(fromFx(() -> ui().tabs().getSelectionModel().getSelectedItem()));
            assertThat(focusOwner()).isSameAs(ui().expressionBox());
        }

        assertThat(picked).containsExactly(ui().savedTab(), ui().historyTab(), ui().lookTab(), ui().settingsTab());
        // Nothing was typed into the box.
        assertThat(fromFx(() -> ui().expressionBox().getText())).isEmpty();
    }

    @Test
    void pickingAnotherTabFromInsideTheOpenOneMovesFocusToTheTabsNotIntoAHiddenTab() throws Exception {
        onFx(() -> history.list().requestFocus());

        ctrl(KeyCode.DIGIT3);

        assertThat(fromFx(() -> ui().tabs().getSelectionModel().getSelectedItem())).isSameAs(ui().lookTab());
        assertThat(focusOwner()).isSameAs(ui().tabs());
    }

    @Test
    void ctrlLFocusesTheBoxAndSelectsAllOfIt() throws Exception {
        onFx(() -> {
            ui().expressionBox().setText("4d6rr1kh3+2");
            ui().expressionBox().positionCaret(3);
            ui().bell().requestFocus();
        });

        ctrl(KeyCode.L);

        assertThat(focusOwner()).isSameAs(ui().expressionBox());
        assertThat(fromFx(() -> ui().expressionBox().getSelectedText())).isEqualTo("4d6rr1kh3+2");
        assertThat(fromFx(() -> ui().expressionBox().getText())).isEqualTo("4d6rr1kh3+2");
    }

    @Test
    void ctrlRMovesFocusToTheResultStrip() throws Exception {
        faces.then(5);
        roll("1d6+2");
        playToRest();

        ctrl(KeyCode.R);

        assertThat(focusOwner()).isSameAs(ui().resultStrip());
        assertThat(fromFx(() -> ui().resultStrip().getAccessibleText())).isEqualTo("Total 7. 1d6: 5. Plus 2.");
        // The strip isn't a Tab stop, and the shortcut didn't roll anything.
        assertThat(fromFx(() -> ui().resultStrip().isFocusTraversable())).isFalse();
        assertThat(tray.shows()).isEqualTo(1);
    }

    @Test
    void ctrlSOpensNewSavedRollFromAnywhereInTheWindow() throws Exception {
        onFx(() -> {
            ui().expressionBox().setText("2d6+3");
            ui().bell().requestFocus();
        });

        ctrl(KeyCode.S);

        assertThat(fromFx(() -> saved.dialog().isPresent())).isTrue();
        assertThat(fromFx(() -> saved.dialog().orElseThrow().expressionField().getText())).isEqualTo("2d6+3");
        onFx(() -> saved.dialog().orElseThrow().dialog().close());
    }

    @Test
    void noSingleLetterOrDigitDoesAnythingButType() throws Exception {
        List<KeyCode> keys = new ArrayList<>();
        for (KeyCode code : KeyCode.values()) {
            if (code.isLetterKey() || (code.isDigitKey() && !code.isKeypadKey())) {
                keys.add(code);
            }
        }
        // Wherever focus is: on a button, on the tabs, on a list; each key alone leaves the window as it was.
        for (Node focus : List.of(ui().rollButton(), ui().tabs(), history.list(), ui().bell())) {
            onFx(focus::requestFocus);
            for (KeyCode key : keys) {
                onFx(() -> robot().keyType(key));
                assertThat(focusOwner()).as("focus after %s on %s", key, focus).isSameAs(focus);
            }
            assertThat(fromFx(() -> ui().tabs().getSelectionModel().getSelectedItem())).isSameAs(ui().historyTab());
            assertThat(fromFx(HeadlessUiTest::showingPopups)).isEmpty();
            assertThat(fromFx(() -> saved.dialog().isPresent())).isFalse();
            assertThat(fromFx(() -> ui().expressionBox().getText())).isEmpty();
        }
        assertThat(tray.shows()).isZero();
        assertThat(fromFx(() -> controller().status())).isInstanceOf(RollStatus.Ready.class);

        // In the box, the same keys only type.
        onFx(() -> ui().expressionBox().requestFocus());
        for (KeyCode key : keys) {
            onFx(() -> robot().keyType(key));
        }
        assertThat(fromFx(() -> ui().expressionBox().getText())).isEqualTo("0123456789abcdefghijklmnopqrstuvwxyz");
        assertThat(focusOwner()).isSameAs(ui().expressionBox());
        assertThat(tray.shows()).isZero();
    }

    @Test
    void escClosesTheErrorPopupBeforeTheQueuedRollIsCancelled() throws Exception {
        rollWithARollQueued();
        onFx(() -> {
            ui().expressionBox().setText("2d6+1d7kh1");
            robot().keyType(KeyCode.ENTER);
        });
        assertThat(fromFx(() -> session.entry().errorPopup().isShowing())).isTrue();

        esc();
        assertThat(fromFx(() -> session.entry().errorPopup().isShowing())).isFalse();
        assertThat(queued()).contains("1d8");

        esc();
        assertThat(queued()).isEmpty();
    }

    @Test
    void escClosesTheNoticeListBeforeTheQueuedRollIsCancelled() throws Exception {
        rollWithARollQueued();
        onFx(() -> ui().bell().fire());
        assertThat(fromFx(HeadlessUiTest::showingPopups)).hasSize(1);

        esc();
        assertThat(fromFx(HeadlessUiTest::showingPopups)).isEmpty();
        assertThat(queued()).contains("1d8");
    }

    @Test
    void escClosesTheHistoryMenuBeforeTheQueuedRollIsCancelled() throws Exception {
        faces.then(3);
        roll("1d6");
        playToRest();
        rollWithARollQueued();
        onFx(() -> {
            history.list().requestFocus();
            history.list().getSelectionModel().select(0);
        });
        onFx(() -> {
            robot().keyPress(KeyCode.SHIFT);
            robot().keyType(KeyCode.F10);
            robot().keyRelease(KeyCode.SHIFT);
        });
        assertThat(fromFx(() -> history.menu().isShowing())).isTrue();

        esc();
        assertThat(fromFx(() -> history.menu().isShowing())).isFalse();
        assertThat(queued()).contains("1d8");
    }

    @Test
    void escThatClosesAPopupLettingItThroughDoesNothingElse() throws Exception {
        // A ContextMenu (the History menu) doesn't consume the Esc that hides it when nothing in it has focus yet, so
        // the key goes on to the window. A popup like that, with nothing focusable in it, does so every time.
        faces.then(3);
        roll("1d6");
        playToRest();
        rollWithARollQueued();
        for (Node focus : List.of(ui().expressionBox(), history.list())) {
            Popup leaky = fromFx(() -> {
                Popup popup = new Popup();
                popup.getContent().add(new Label("A popup"));
                popup.setHideOnEscape(true);
                popup.setConsumeAutoHidingEvents(false);
                focus.requestFocus();
                popup.show(stage);
                return popup;
            });

            esc();

            assertThat(fromFx(leaky::isShowing)).isFalse();
            assertThat(queued()).as("after Esc with focus on %s", focus).contains("1d8");
        }
    }

    @Test
    void escInADialogIsCancelAndTheQueuedRollStays() throws Exception {
        rollWithARollQueued();
        ctrl(KeyCode.S);
        assertThat(fromFx(() -> saved.dialog().isPresent())).isTrue();
        onFx(() -> saved.dialog().orElseThrow().nameField().setText("Fireball"));

        // Typed into the dialog, which has the keyboard while it shows.
        esc();

        assertThat(fromFx(() -> saved.dialog().isPresent())).isFalse();
        assertThat(fromFx(() -> saved.saved().rolls())).isEmpty();
        assertThat(queued()).contains("1d8");
    }

    @Test
    void escInADeleteConfirmationIsCancelAndKeepsTheSavedRoll() throws Exception {
        Files.writeString(dataFolder.resolve("saved-rolls.txt"), "#andysdice saved-rolls.txt 1\nFireball\t8d6\n");
        restartApp();
        rollWithARollQueued();
        selectSavedRoll();
        onFx(() -> robot().keyType(KeyCode.DELETE));
        assertThat(fromFx(() -> saved.confirmation().isPresent())).isTrue();

        esc();

        assertThat(fromFx(() -> saved.confirmation().isPresent())).isFalse();
        assertThat(fromFx(() -> saved.saved().rolls())).hasSize(1);
        assertThat(queued()).contains("1d8");
    }

    @Test
    void escCancelsAnInlineEditBeforeTheQueuedRoll() throws Exception {
        Files.writeString(dataFolder.resolve("saved-rolls.txt"), "#andysdice saved-rolls.txt 1\nFireball\t8d6\n");
        restartApp();
        rollWithARollQueued();
        selectSavedRoll();
        onFx(() -> robot().keyType(KeyCode.F2));
        assertThat(fromFx(() -> saved.editingIndex())).isZero();

        esc();
        assertThat(fromFx(() -> saved.editingIndex())).isEqualTo(-1);
        assertThat(queued()).contains("1d8");

        esc();
        assertThat(queued()).isEmpty();
    }

    @Test
    void anInlineEditLeftOpenIsCancelledByEscBeforeTheQueuedRollWhereverFocusHasGone() throws Exception {
        Files.writeString(dataFolder.resolve("saved-rolls.txt"), "#andysdice saved-rolls.txt 1\nFireball\t8d6\n");
        restartApp();
        rollWithARollQueued();
        selectSavedRoll();
        onFx(() -> robot().keyType(KeyCode.F2));
        // Ctrl+L takes focus back to the box with the edit still open.
        ctrl(KeyCode.L);
        assertThat(fromFx(() -> saved.editingIndex())).isZero();
        assertThat(focusOwner()).isSameAs(ui().expressionBox());

        esc();
        assertThat(fromFx(() -> saved.editingIndex())).isEqualTo(-1);
        assertThat(queued()).contains("1d8");
    }

    @Test
    void escWithNothingToCloseOrCancelDoesNothingAndNeverSkips() throws Exception {
        faces.then(4);
        roll("1d6");
        advance(0.25);
        double shown = fromFx(tray::shownTime);
        StandInTray.Phase phase = tray.phase();

        for (int i = 0; i < 3; i++) {
            esc();
        }

        // The Roll plays on from where it was, at normal speed: nothing jumped ahead.
        assertThat(fromFx(() -> controller().status())).isInstanceOf(RollStatus.Rolling.class);
        assertThat(fromFx(tray::shownTime)).isEqualTo(shown);
        assertThat(tray.phase()).isEqualTo(phase);
        assertThat(fromFx(clock::speed)).isEqualTo(1.0);
        advance(FRAME);
        assertThat(fromFx(tray::shownTime)).isCloseTo(shown + FRAME, offset(1e-9));
        playToRest();
        assertThat(fromFx(() -> ui().resultStrip().text())).isEqualTo("1d6 = 4");
    }

    /** Rolls 1d6, left mid-Throw, then queues 1d8 behind it with Enter, focus back in the box. */
    private void rollWithARollQueued() throws Exception {
        roll("1d6");
        onFx(() -> {
            ui().expressionBox().setText("1d8");
            robot().keyType(KeyCode.ENTER);
        });
        assertThat(fromFx(() -> controller().underWay())).isTrue();
        assertThat(queued()).contains("1d8");
    }

    /** Opens the Saved tab and puts focus on its first Saved Roll. */
    private void selectSavedRoll() throws Exception {
        onFx(() -> ui().tabs().getSelectionModel().select(ui().savedTab()));
        pulse(stage.getScene());
        onFx(() -> {
            saved.list().requestFocus();
            saved.list().getSelectionModel().select(0);
        });
    }

    private Optional<String> queued() throws Exception {
        return fromFx(() -> session.entry().queue().queued());
    }

    /** Types Esc wherever the keyboard is: the main window, or the popup or dialog in front of it. */
    private void esc() throws Exception {
        onFx(() -> robot().keyType(KeyCode.ESCAPE));
    }

    /** Presses a key with Ctrl held, as one chord. */
    private void ctrl(KeyCode key) throws Exception {
        onFx(() -> {
            robot().keyPress(KeyCode.CONTROL);
            robot().keyType(key);
            robot().keyRelease(KeyCode.CONTROL);
        });
    }

    /** Presses a key {@code times} times, one press per JavaFX turn, and returns the focus owner after each. */
    private List<Node> walk(KeyCode key, int times) throws Exception {
        List<Node> owners = new ArrayList<>();
        for (int i = 0; i < times; i++) {
            onFx(() -> robot().keyType(key));
            owners.add(focusOwner());
        }
        return owners;
    }

    private Node focusOwner() throws Exception {
        return fromFx(() -> stage.getScene().getFocusOwner());
    }
}
